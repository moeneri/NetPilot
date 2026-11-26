package dev.netpilot.service.RootSupervisor

import android.content.Context
import android.os.Looper
import android.os.Process
import android.system.Os
import android.system.OsConstants
import dev.netpilot.core.model.NetPilotJson
import dev.netpilot.core.model.RootInstallIdentity
import dev.netpilot.compat.root.PackageIdentity
import dev.netpilot.core.root.RootCommandExecutor
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.StandardOpenOption
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

@Serializable
data class SupervisorIdentity(val pid: Int, val startTicks: String, val bootId: String, val name: String = "netpilot-supervisor")

/** Entry point executed directly by ART via app_process, never by an Activity. */
object RootSupervisorMain {
    @JvmStatic fun main(arguments: Array<String>) {
        if (Process.myUid() != 0) { System.err.println("ROOT_REQUIRED: Supervisor must be started by an authorized root provider"); exitProcess(13) }
        try {
            exemptHiddenApis()
            if (Looper.getMainLooper() == null) Looper.prepareMainLooper()
            val context = systemContext()
            val mode = arguments.firstOrNull() ?: "--daemon"
            if (mode == "--request-cleanup") {
                val online = runCatching { LocalControlClient().request("UNINSTALL_PREP", timeoutMillis = 60_000) }.getOrNull()
                if (online != null && !online.ok) { System.err.println(online.error); exitProcess(5) }
                if (online != null) {
                    if (!waitForExit()) { System.err.println("Supervisor exit was not confirmed"); exitProcess(6) }
                    exitProcess(0)
                }
                terminateVerifiedSupervisor()
            }
            File(SupervisorEngine.ROOT, "run").mkdirs()
            val channel = FileChannel.open(File(SupervisorEngine.ROOT, "run/supervisor.lock").toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            val lock = channel.tryLock()
            if (lock == null) { channel.close(); System.err.println("ALREADY_RUNNING: Root Supervisor lock is held"); exitProcess(if (mode == "--daemon") 0 else 10) }
            val exiting = AtomicBoolean(false)
            var control: LocalControlServer? = null
            lateinit var engine: SupervisorEngine
            val expectedIdentity = runCatching { NetPilotJson.decodeFromString<RootInstallIdentity>(File(SupervisorEngine.ROOT, "runtime/identity.json").readText()) }.getOrNull()
            val identityValid = expectedIdentity != null && PackageIdentity.matches(context, expectedIdentity)
            val cleanupOnly = mode == "--cleanup" || mode == "--request-cleanup" || !identityValid
            if (!identityValid && mode == "--daemon") {
                File(SupervisorEngine.ROOT, "run/stopped").writeText("install-identity-changed")
                System.err.println("INSTALL_IDENTITY_CHANGED: Reauthorize Root from the installed app")
            }
            val release = {
                runCatching { control?.close() }; runCatching { engine.close() }
                File(SupervisorEngine.ROOT, "run/pid.json").delete()
                runCatching { lock.release() }; runCatching { channel.close() }
            }
            engine = SupervisorEngine(context, !cleanupOnly) { code, removeFiles ->
                Thread({
                    if (!exiting.compareAndSet(false, true)) return@Thread
                    release()
                    if (removeFiles) removeInstallationFiles()
                    exitProcess(code)
                }, "netpilot-exit").start()
            }
            Runtime.getRuntime().addShutdownHook(Thread({
                if (exiting.compareAndSet(false, true)) { engine.shutdownCleanup(); release() }
            }, "netpilot-shutdown"))
            if (cleanupOnly) {
                // ART callbacks require the main Looper while the worker restores shared services.
                Thread({
                    val ok = engine.cleanup()
                    exiting.set(true); release()
                    exitProcess(if (ok) 0 else 5)
                }, "netpilot-cleanup").start()
            } else {
                val appUid = context.packageManager.getPackageUid(SupervisorEngine.PACKAGE, 0)
                control = LocalControlServer(appUid, { expectedIdentity != null && PackageIdentity.matches(context, expectedIdentity) }, engine::request)
                val identity = SupervisorIdentity(Process.myPid(), startTicks(Process.myPid()), File("/proc/sys/kernel/random/boot_id").readText().trim())
                File(SupervisorEngine.ROOT, "run/pid.json").writeText(NetPilotJson.encodeToString(identity))
                Os.chmod(File(SupervisorEngine.ROOT, "run/pid.json").path, 384)
                System.out.println("ROOT_SUPERVISOR_READY pid=${identity.pid} sdk=${android.os.Build.VERSION.SDK_INT}")
            }
            Looper.loop()
        } catch (error: Throwable) {
            System.err.println("SUPERVISOR_ERROR: ${error.javaClass.name}: ${error.message}")
            error.printStackTrace(System.err)
            exitProcess(2)
        }
    }

    private fun exemptHiddenApis() {
        runCatching {
            val type = Class.forName("dalvik.system.VMRuntime")
            val runtime = type.getDeclaredMethod("getRuntime").invoke(null)
            type.getDeclaredMethod("setHiddenApiExemptions", Array<String>::class.java).invoke(runtime, arrayOf("L"))
        }.onFailure { System.err.println("HIDDEN_API_ACCESS_LIMITED: ${it.message}") }
    }
    private fun systemContext(): Context {
        val type = Class.forName("android.app.ActivityThread")
        val thread = type.getDeclaredMethod("systemMain").invoke(null)
        return type.getDeclaredMethod("getSystemContext").invoke(thread) as Context
    }
    private fun startTicks(pid: Int): String {
        val stat = File("/proc/$pid/stat").readText()
        return stat.substring(stat.lastIndexOf(')') + 2).split(' ').getOrElse(19) { "" }
    }
    private fun terminateVerifiedSupervisor() {
        val identity = runCatching { NetPilotJson.decodeFromString<SupervisorIdentity>(File(SupervisorEngine.ROOT, "run/pid.json").readText()) }.getOrNull() ?: return
        if (identity.pid <= 1 || identity.pid == Process.myPid()) return
        val currentBootId = File("/proc/sys/kernel/random/boot_id").readText().trim()
        if (identity.bootId != currentBootId) return
        val cmdline = runCatching { File("/proc/${identity.pid}/cmdline").readText().substringBefore('\u0000') }.getOrNull() ?: return
        if (cmdline != identity.name || startTicks(identity.pid) != identity.startTicks) throw IllegalStateException("PID identity mismatch; refusing to signal a different process")
        Os.kill(identity.pid, OsConstants.SIGTERM)
        if (!waitForExit(45_000)) throw IllegalStateException("Verified Supervisor did not exit after SIGTERM")
    }
    private fun waitForExit(timeoutMillis: Int = 5_000): Boolean {
        val file = File(SupervisorEngine.ROOT, "run/supervisor.lock")
        if (!file.exists()) return true
        repeat(timeoutMillis / 100) {
            val free = runCatching { FileChannel.open(file.toPath(), StandardOpenOption.WRITE).use { channel -> channel.tryLock()?.let { lock -> lock.release(); true } ?: false } }.getOrDefault(false)
            if (free) return true
            Thread.sleep(100)
        }
        return false
    }
    private fun removeInstallationFiles() {
        // Constant, app-owned paths only. No other modules or network rules are touched.
        RootCommandExecutor(true).executeScript("""
            rm -f /data/adb/service.d/90-netpilot.sh
            if [ -f /data/adb/modules/netpilot-guardian/module.prop ] && grep -q '^id=netpilot-guardian$' /data/adb/modules/netpilot-guardian/module.prop; then
                rm -rf /data/adb/modules/netpilot-guardian
            fi
            rm -rf /data/adb/netpilot
        """.trimIndent(), 15)
    }
}
