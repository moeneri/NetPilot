package dev.netpilot.core.root

import android.content.Context
import dev.netpilot.compat.root.bootAdapter
import dev.netpilot.compat.root.PackageIdentity
import dev.netpilot.core.model.*
import kotlinx.serialization.encodeToString
import java.io.File
import java.security.MessageDigest

class RootInstaller(private val context: Context, private val executor: RootCommandExecutor) {
    companion object { const val DIRECTORY = "/data/adb/netpilot" }

    fun install(config: AppConfig, schedule: ScheduleDocument): CommandResult {
        require(android.os.Process.myUid() / 100_000 == 0) { "Root 守护目前只支持 Android 主用户（user 0）安装" }
        require(config.validate() == null)
        require(dev.netpilot.core.schedule.ScheduleCalculator.validate(schedule) == null)
        val staging = File(context.noBackupFilesDir, "root-install").apply { mkdirs() }
        listOf("90-netpilot.sh", "start.sh", "supervise.sh", "uninstall.sh").forEach { name ->
            context.assets.open("scripts/$name").use { input -> File(staging, name).outputStream().use { input.copyTo(it) } }
        }
        File(staging, "config.json").writeText(NetPilotJson.encodeToString(config))
        File(staging, "schedule.json").writeText(NetPilotJson.encodeToString(schedule))
        File(staging, "identity.json").writeText(NetPilotJson.encodeToString(PackageIdentity.current(context)))
        val apk = File(context.applicationInfo.sourceDir)
        val hash = MessageDigest.getInstance("SHA-256").apply {
            apk.inputStream().use { input -> val buffer = ByteArray(1024 * 1024); while (true) { val count = input.read(buffer); if (count < 0) break; update(buffer, 0, count) } }
        }.digest().joinToString("") { "%02x".format(it) }
        val adapter = bootAdapter(config.rootProvider)
        val root = ShellEscaping.quote(DIRECTORY)
        val source = ShellEscaping.quote(staging.absolutePath)
        val sourceApk = ShellEscaping.quote(apk.absolutePath)
        val startup = adapter.startupPath?.let(ShellEscaping::quote)
        val bootScript = if (adapter.moduleBased) """
            mkdir -p /data/adb/modules/netpilot-guardian
            chmod 755 /data/adb/modules/netpilot-guardian
            printf '%s\n' 'id=netpilot-guardian' 'name=NetPilot Guardian' 'version=${dev.netpilot.BuildConfig.VERSION_NAME}' 'versionCode=${dev.netpilot.BuildConfig.VERSION_CODE}' 'author=NetPilot' 'description=Native USB and Wi-Fi tethering automation' > /data/adb/modules/netpilot-guardian/module.prop
            cp ${'$'}SRC/90-netpilot.sh $startup
            cp ${'$'}SRC/uninstall.sh /data/adb/modules/netpilot-guardian/uninstall.sh
            chmod 755 $startup /data/adb/modules/netpilot-guardian/uninstall.sh
        """.trimIndent() else if (startup != null) """
            mkdir -p /data/adb/service.d
            cp ${'$'}SRC/90-netpilot.sh $startup
            chmod 755 $startup
        """.trimIndent() else "echo 'BOOT_ADAPTER=MANUAL'"
        return executor.executeScript("""
            set -e
            umask 077
            NP_DIR=$root
            SRC=$source
            NP_PHASE=prepare-directory
            mkdir -p "${'$'}NP_DIR/logs"
            chmod 700 "${'$'}NP_DIR" "${'$'}NP_DIR/logs"
            NP_LOG="${'$'}NP_DIR/logs/install.log"
            if [ -f "${'$'}NP_LOG" ] && [ "${'$'}(wc -c < "${'$'}NP_LOG")" -gt 524288 ]; then
                mv "${'$'}NP_LOG" "${'$'}NP_DIR/logs/install.previous.log"
            fi
            NP_REPORT() {
                printf '%s\n' "NETPILOT_INSTALL: ${'$'}*"
                printf '%s %s\n' "${'$'}(date +%s)" "${'$'}*" >> "${'$'}NP_LOG"
            }
            trap 'NP_EXIT=${'$'}?; if [ "${'$'}NP_EXIT" -ne 0 ]; then NP_REPORT "FAILED phase=${'$'}NP_PHASE exit=${'$'}NP_EXIT"; fi' EXIT
            NP_PHASE=verify-source
            NP_REPORT "BEGIN version=${dev.netpilot.BuildConfig.VERSION_NAME}"
            [ -r $sourceApk ] || { NP_REPORT 'SOURCE_APK_UNREADABLE'; exit 20; }
            [ -r "${'$'}SRC/config.json" ] && [ -r "${'$'}SRC/schedule.json" ] && [ -r "${'$'}SRC/identity.json" ] || { NP_REPORT 'STAGING_UNREADABLE'; exit 20; }
            if [ -f "${'$'}NP_DIR/runtime/netpilot.apk" ] || [ -f "${'$'}NP_DIR/state.json" ]; then
                NP_PHASE=restore-previous-install
                NP_REPORT 'RESTORE_PREVIOUS'
                mkdir -p "${'$'}NP_DIR/run"
                touch "${'$'}NP_DIR/run/stopped"
                # Use the currently installed APK so a broken previous launcher cannot
                # prevent upgrading the restoration/control code.
                CLASSPATH=$sourceApk /system/bin/app_process /system/bin --nice-name=netpilot-install-cleanup dev.netpilot.service.RootSupervisor.RootSupervisorMain --request-cleanup >> "${'$'}NP_LOG" 2>&1 || exit 22
            fi
            NP_PHASE=copy-runtime
            NP_REPORT 'COPY_RUNTIME'
            mkdir -p "${'$'}NP_DIR/runtime" "${'$'}NP_DIR/run" "${'$'}NP_DIR/logs" "${'$'}NP_DIR/backup"
            chmod 700 "${'$'}NP_DIR" "${'$'}NP_DIR/runtime" "${'$'}NP_DIR/run" "${'$'}NP_DIR/logs" "${'$'}NP_DIR/backup"
            cp $sourceApk "${'$'}NP_DIR/runtime/netpilot.apk.new"
            NP_HASH=${'$'}(sha256sum "${'$'}NP_DIR/runtime/netpilot.apk.new" | cut -d ' ' -f 1)
            [ "${'$'}NP_HASH" = '$hash' ] || exit 21
            NP_PHASE=commit-runtime
            NP_REPORT 'COMMIT_RUNTIME'
            chmod 444 "${'$'}NP_DIR/runtime/netpilot.apk.new"
            mv -f "${'$'}NP_DIR/runtime/netpilot.apk.new" "${'$'}NP_DIR/runtime/netpilot.apk"
            printf '%s' $sourceApk > "${'$'}NP_DIR/runtime/source.path"
            chmod 600 "${'$'}NP_DIR/runtime/source.path"
            cp "${'$'}SRC/identity.json" "${'$'}NP_DIR/runtime/identity.json"
            chmod 600 "${'$'}NP_DIR/runtime/identity.json"
            NP_PHASE=copy-scripts
            NP_REPORT 'COPY_SCRIPTS'
            for NP_FILE in start.sh supervise.sh uninstall.sh; do
                cp "${'$'}SRC/${'$'}NP_FILE" "${'$'}NP_DIR/${'$'}NP_FILE"
                chmod 755 "${'$'}NP_DIR/${'$'}NP_FILE"
            done
            NP_PHASE=copy-config
            NP_REPORT 'COPY_CONFIG'
            for NP_FILE in config.json schedule.json; do
                cp "${'$'}SRC/${'$'}NP_FILE" "${'$'}NP_DIR/${'$'}NP_FILE.new"
                chmod 600 "${'$'}NP_DIR/${'$'}NP_FILE.new"
                mv -f "${'$'}NP_DIR/${'$'}NP_FILE.new" "${'$'}NP_DIR/${'$'}NP_FILE"
            done
            NP_PHASE=install-boot-adapter
            NP_REPORT 'INSTALL_BOOT_ADAPTER'
            $bootScript
            NP_PHASE=boot-marker
            NP_REPORT 'BOOT_MARKER'
            ${if (config.guardian.bootEnabled) "touch \"\${NP_DIR}/boot.enabled\"" else "rm -f \"\${NP_DIR}/boot.enabled\""}
            NP_PHASE=start-launcher
            NP_REPORT 'START_LAUNCHER'
            /system/bin/sh "${'$'}NP_DIR/start.sh"
            NP_REPORT 'FILES_INSTALLED'
            echo NETPILOT_INSTALLED
        """.trimIndent(), 60)
    }

    /** Starts the guardian and repairs a partial Root installation from this APK when needed. */
    fun start(config: AppConfig, schedule: ScheduleDocument): CommandResult {
        val result = executor.executeScript("""
            NP_DIR=${ShellEscaping.quote(DIRECTORY)}
            for NP_FILE in start.sh supervise.sh uninstall.sh runtime/netpilot.apk runtime/identity.json config.json schedule.json; do
                if [ ! -s "${'$'}NP_DIR/${'$'}NP_FILE" ]; then
                    echo "NETPILOT_REPAIR_REQUIRED=${'$'}NP_FILE"
                    exit 44
                fi
            done
            for NP_FILE in start.sh supervise.sh uninstall.sh; do
                if [ ! -x "${'$'}NP_DIR/${'$'}NP_FILE" ]; then
                    echo "NETPILOT_REPAIR_REQUIRED=${'$'}NP_FILE (not executable)"
                    exit 44
                fi
            done
            /system/bin/sh "${'$'}NP_DIR/start.sh"
        """.trimIndent(), 15)
        val missing = Regex("NETPILOT_REPAIR_REQUIRED=([^\\r\\n]+)").find(result.output)?.groupValues?.get(1)
            ?: return result
        val repaired = install(config, schedule)
        return repaired.copy(output = "NETPILOT_AUTO_REPAIR missing=${missing.trim()}\n${repaired.output}")
    }
    fun uninstall(): CommandResult = executor.execute(listOf("/system/bin/sh", "$DIRECTORY/uninstall.sh"), 60)
    fun bootstrapLogs(): String {
        val result = executor.executeScript("""
            NP_DIR=/data/adb/netpilot
            printf '%s\n' 'NetPilot Root 启动诊断'
            if [ ! -d "${'$'}NP_DIR" ]; then
                printf '%s\n' 'Root 守护目录尚未创建。安装失败的详细原因见 APP 本地启动日志。'
                exit 0
            fi
            for NP_FILE in install.previous.log install.log launcher.previous.log launcher.log runtime.previous.jsonl runtime.jsonl; do
                printf '\n[%s]\n' "${'$'}NP_FILE"
                if [ -s "${'$'}NP_DIR/logs/${'$'}NP_FILE" ]; then
                    tail -n 80 "${'$'}NP_DIR/logs/${'$'}NP_FILE"
                elif [ -f "${'$'}NP_DIR/logs/${'$'}NP_FILE" ]; then
                    printf '%s\n' '文件已创建，尚无记录。'
                else
                    printf '%s\n' '尚未生成。'
                fi
            done
            for NP_FILE in config.json schedule.json state.json; do
                if [ -s "${'$'}NP_DIR/${'$'}NP_FILE" ]; then
                    printf '\n[Root %s]\n' "${'$'}NP_FILE"
                    tail -c 32768 "${'$'}NP_DIR/${'$'}NP_FILE"
                    printf '\n'
                fi
            done
            if [ -f "${'$'}NP_DIR/run/pid.json" ]; then
                printf '\n[supervisor identity]\n'
                cat "${'$'}NP_DIR/run/pid.json"
            fi
            if [ -f "${'$'}NP_DIR/run/stopped" ]; then
                printf '\n[stop reason]\n'
                cat "${'$'}NP_DIR/run/stopped"
            fi
        """.trimIndent(), 10)
        return if (result.success) result.output.ifBlank { "Root 诊断命令未返回内容，APP 本地启动记录仍可导出。" }
        else "Root 诊断读取失败：exit=${result.exitCode} timeout=${result.timedOut}\n${result.output.ifBlank { "命令未返回错误详情" }}"
    }
}
