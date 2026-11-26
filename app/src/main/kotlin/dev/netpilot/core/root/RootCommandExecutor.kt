package dev.netpilot.core.root

import android.os.Process
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

data class CommandResult(val exitCode: Int, val output: String, val timedOut: Boolean = false) {
    val success: Boolean get() = exitCode == 0 && !timedOut
}

/** All privileged shell execution lives here, including OEM commands. Never used by composables. */
open class RootCommandExecutor(private val alreadyRoot: Boolean = Process.myUid() == 0) {
    fun execute(arguments: List<String>, timeoutSeconds: Long = 20): CommandResult = executeScript(ShellEscaping.command(arguments), timeoutSeconds)

    protected open fun createProcess(script: String): java.lang.Process = ProcessBuilder(
        if (alreadyRoot) listOf("/system/bin/sh", "-c", script) else listOf("su", "-c", script)
    ).redirectErrorStream(true).start()

    open fun executeScript(script: String, timeoutSeconds: Long = 20): CommandResult {
        require(script.length <= 64 * 1024 && '\u0000' !in script)
        val process = try {
            createProcess(script).also { it.outputStream.close() }
        } catch (error: Exception) { return CommandResult(-1, error.message ?: "Root 命令无法启动") }
        val out = ByteArrayOutputStream()
        val pool = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "netpilot-root-output").apply { isDaemon = true } }
        val reader = pool.submit {
            process.inputStream.use { input ->
                val buffer = ByteArray(4096)
                var remaining = 256 * 1024
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    val keep = count.coerceAtMost(remaining)
                    if (keep > 0) synchronized(out) { out.write(buffer, 0, keep) }
                    remaining -= keep
                }
            }
        }
        return try {
            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) { process.destroy(); if (!process.waitFor(500, TimeUnit.MILLISECONDS)) process.destroyForcibly() }
            val readError = try { reader.get(2, TimeUnit.SECONDS); null }
                catch (_: TimeoutException) { "命令输出读取超时；已保留部分输出" }
                catch (error: Exception) { "命令输出读取失败：${error.cause?.message ?: error.message}" }
            val captured = synchronized(out) { out.toString(Charsets.UTF_8.name()) }
            val output = listOfNotNull(captured.takeIf { it.isNotBlank() }, readError).joinToString("\n")
            CommandResult(if (finished) process.exitValue() else -1, output.trim(), !finished)
        } finally {
            runCatching { process.inputStream.close() }
            reader.cancel(true)
            pool.shutdownNow()
        }
    }
}
