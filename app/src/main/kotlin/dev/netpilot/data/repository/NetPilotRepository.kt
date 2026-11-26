package dev.netpilot.data.repository

import android.app.AlarmManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import dev.netpilot.BuildConfig
import dev.netpilot.compat.android.CapabilityDetector
import dev.netpilot.compat.root.RootProviderDetector
import dev.netpilot.core.model.*
import dev.netpilot.core.root.CommandResult
import dev.netpilot.core.root.RootCommandExecutor
import dev.netpilot.core.root.RootInstaller
import dev.netpilot.core.schedule.ScheduleCalculator
import dev.netpilot.data.logs.RuntimeLogger
import dev.netpilot.i18n.AppLanguage
import dev.netpilot.i18n.AppLocale
import dev.netpilot.i18n.LocalizedStrings
import dev.netpilot.R
import dev.netpilot.service.RootSupervisor.LocalControlClient
import dev.netpilot.service.ScheduleEngine.AppScheduleEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import java.io.File
import java.util.UUID

data class SystemPermissions(
    val exactAlarm: Boolean = true,
    val batteryExempt: Boolean = false,
    val notificationsAllowed: Boolean = true,
    val foregroundRunning: Boolean = false,
)
data class AppUiState(
    val config: AppConfig = AppConfig(),
    val schedule: ScheduleDocument = ScheduleDocument(),
    val runtime: RuntimeState = RuntimeState(),
    val connected: Boolean = false,
    val permissions: SystemPermissions = SystemPermissions(),
    val logs: List<LogEntry> = emptyList(),
    val busy: String? = null,
    val message: String? = null,
    val diagnosticText: String? = null,
    val language: AppLanguage = AppLanguage.SYSTEM,
)

class NetPilotRepository(private val context: Context, private val root: RootCommandExecutor = RootCommandExecutor()) {
    private val protectedContext = context.createDeviceProtectedStorageContext()
    private val store = AtomicJsonStore(File(protectedContext.filesDir, "netpilot")).apply { recoverPendingRestore() }
    private val startupLogger = RuntimeLogger(File(protectedContext.filesDir, "startup-logs"))
    private val client = LocalControlClient()
    private val installer = RootInstaller(context, root)
    private val mutex = Mutex()
    private var lastConnectionFailure: String? = null
    private val mutable = MutableStateFlow(AppUiState(config = store.config(), schedule = store.schedule(), runtime = store.state().copy(supervisor = store.state().supervisor.copy(running = false)), language = AppLocale.read(context)))
    val state = mutable.asStateFlow()
    private val strings get() = AppLocale.strings(context, state.value.language)
    fun setLanguage(language: AppLanguage) {
        val strings = AppLocale.strings(context, language)
        check(AppLocale.save(context, language)) { strings(R.string.language_save_failed) }
        mutable.update { it.copy(language = language, message = strings(R.string.language_changed)) }
    }
    init {
        if (!store.child("schedule.json").exists()) store.save(ScheduleDocument())
        if (!store.child("config.json").exists()) store.save(AppConfig())
        val capabilities = CapabilityDetector.detect(false, mutable.value.config.rootProvider)
        mutable.update { currentState -> currentState.copy(runtime = currentState.runtime.copy(capabilities = if (currentState.runtime.capabilities.sdk > 0) currentState.runtime.capabilities else capabilities)) }
        recordLocal(LogLevel.INFO, "app-start", "NetPilot ${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）启动；Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}；守护已安装=${mutable.value.config.guardian.installed}")
        updatePermissions()
    }
    fun updatePermissions() {
        val alarm = context.getSystemService(AlarmManager::class.java)
        val power = context.getSystemService(PowerManager::class.java)
        val notifications = androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()
        val permissions = SystemPermissions(
            exactAlarm = Build.VERSION.SDK_INT < 31 || alarm.canScheduleExactAlarms(),
            batteryExempt = power.isIgnoringBatteryOptimizations(context.packageName),
            notificationsAllowed = notifications,
            foregroundRunning = dev.netpilot.service.GuardianForegroundService.running,
        )
        mutable.update { it.copy(permissions = permissions) }
    }
    suspend fun refresh(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock { refreshLocked() }
    }
    private fun refreshLocked(): Boolean {
        return try {
            if (store.child("sync.pending").exists()) {
                val configResult = client.request("SAVE_CONFIG", NetPilotJson.encodeToString(mutable.value.config))
                check(configResult.ok) { configResult.error.orEmpty() }
                val scheduleResult = client.request("SAVE_SCHEDULE", NetPilotJson.encodeToString(mutable.value.schedule))
                check(scheduleResult.ok) { scheduleResult.error.orEmpty() }
                store.child("sync.pending").delete()
            }
            val reply = client.request("SNAPSHOT", timeoutMillis = 3_000)
            check(reply.ok) { reply.error.orEmpty() }
            val snapshot = NetPilotJson.decodeFromString<Snapshot>(reply.payload)
            store.save(snapshot.config); store.save(snapshot.schedule); store.save(snapshot.state)
            if (!mutable.value.connected) recordLocal(LogLevel.INFO, "app-control", "Root 控制通道已连接，Supervisor PID=${snapshot.state.supervisor.pid}")
            lastConnectionFailure = null
            mutable.update { currentState -> currentState.copy(config = snapshot.config, schedule = snapshot.schedule, runtime = snapshot.state, connected = true) }
            true
        } catch (error: Exception) {
            val detail = errorDetail(error)
            if ((mutable.value.config.guardian.installed || mutable.value.connected) && detail != lastConnectionFailure) {
                recordLocal(LogLevel.WARN, "app-control", "Root 控制通道连接失败：$detail")
                lastConnectionFailure = detail
            }
            mutable.update { currentState -> currentState.copy(connected = false, runtime = currentState.runtime.copy(supervisor = currentState.runtime.supervisor.copy(running = false))) }
            false
        }
    }
    suspend fun updateConfig(transform: (AppConfig) -> AppConfig) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val next = transform(mutable.value.config)
            next.validate()?.let { error(it) }
            if (mutable.value.connected) {
                val response = client.request("SAVE_CONFIG", NetPilotJson.encodeToString(next))
                check(response.ok) { response.error.orEmpty() }
            } else store.writeText("sync.pending", "true")
            store.save(next); mutable.update { currentState -> currentState.copy(config = next) }
            refreshLocked(); reschedule()
        }
    }
    suspend fun updateSchedule(transform: (ScheduleDocument) -> ScheduleDocument) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val next = transform(mutable.value.schedule)
            ScheduleCalculator.validate(next)?.let { error(it) }
            if (mutable.value.connected) { val response = client.request("SAVE_SCHEDULE", NetPilotJson.encodeToString(next)); check(response.ok) { response.error.orEmpty() } }
            else store.writeText("sync.pending", "true")
            store.save(next); mutable.update { currentState -> currentState.copy(schedule = next) }
            refreshLocked(); reschedule()
        }
    }
    suspend fun saveSlot(slot: ScheduleSlot) = updateSchedule { document ->
        document.copy(slots = if (document.slots.any { it.id == slot.id }) document.slots.map { if (it.id == slot.id) slot else it } else document.slots + slot)
    }
    suspend fun deleteSlot(id: String) = updateSchedule { it.copy(slots = it.slots.filterNot { slot -> slot.id == id }) }
    suspend fun duplicateSlot(slot: ScheduleSlot) = saveSlot(slot.copy(id = UUID.randomUUID().toString(), label = strings(R.string.slot_duplicate_label, strings.slotLabel(slot).take(27)), enabled = false))
    suspend fun installGuardian() = guardianOperation("安装 Root 守护") {
        recordLocal(LogLevel.INFO, "app-root", "请求 Root 授权并检测 Root Provider")
        val detection = RootProviderDetector(root).requestAccess()
        recordLocal(if (detection.granted) LogLevel.INFO else LogLevel.ERROR, "app-root", "Root 授权${if (detection.granted) "通过" else "未通过"}；Provider=${detection.provider}；${detection.detail}")
        if (!detection.granted) {
            mutable.update { currentState -> currentState.copy(runtime = currentState.runtime.copy(capabilities = currentState.runtime.capabilities.copy(rootGranted = false, rootProvider = RootProvider.NONE))) }
            error("Root 授权未通过：${detection.detail}")
        }
        val detectedConfig = mutable.value.config.copy(rootProvider = detection.provider)
        store.save(detectedConfig)
        mutable.update { currentState -> currentState.copy(config = detectedConfig, runtime = currentState.runtime.copy(capabilities = currentState.runtime.capabilities.copy(rootGranted = true, rootProvider = detection.provider))) }
        if (mutable.value.connected) { val stopped = client.request("STOP"); check(stopped.ok) { stopped.error.orEmpty() }; delay(1_200) }
        val next = mutable.value.config.copy(rootProvider = detection.provider, guardian = mutable.value.config.guardian.copy(installed = true, paused = false))
        val result = installer.install(next, mutable.value.schedule)
        recordRootResult("安装脚本", result)
        check(result.success && "NETPILOT_INSTALLED" in result.output) { "Root 守护安装失败：${result.output}" }
        store.save(next); store.child("sync.pending").delete()
        mutable.update { currentState -> currentState.copy(config = next, runtime = currentState.runtime.copy(capabilities = currentState.runtime.capabilities.copy(rootGranted = true, rootProvider = detection.provider))) }
        if (!waitForConnection()) error("守护已安装，控制通道尚未连接。启动日志：\n${installer.bootstrapLogs().takeLast(2_000)}")
        reschedule()
    }
    suspend fun startGuardian() = guardianOperation("启动 Root 守护") {
        check(mutable.value.config.guardian.installed) { "请先授予 Root 并安装守护" }
        val next = mutable.value.config.copy(guardian = mutable.value.config.guardian.copy(paused = false))
        store.save(next); store.writeText("sync.pending", "true"); mutable.update { currentState -> currentState.copy(config = next) }
        val started = installer.start(next, mutable.value.schedule)
        if ("NETPILOT_AUTO_REPAIR" in started.output) recordLocal(LogLevel.WARN, "app-root", "检测到 Root 启动文件缺失，已尝试从当前 APK 自动修复")
        recordRootResult("启动脚本", started); check(started.success) { started.output }
        check(waitForConnection()) { "启动请求已执行，但未连接到守护。启动日志：\n${installer.bootstrapLogs().takeLast(2_000)}" }
        reschedule()
    }
    suspend fun stopGuardian() = guardianOperation("停止守护并恢复配置") {
        val next = mutable.value.config.copy(guardian = mutable.value.config.guardian.copy(paused = true))
        if (mutable.value.connected) {
            val saved = client.request("SAVE_CONFIG", NetPilotJson.encodeToString(next)); check(saved.ok) { saved.error.orEmpty() }
            val stopped = client.request("STOP"); check(stopped.ok) { stopped.error.orEmpty() }
            delay(1_200)
        } else {
            val apk = dev.netpilot.core.root.ShellEscaping.quote(context.applicationInfo.sourceDir)
            val result = root.executeScript("mkdir -p /data/adb/netpilot/run && touch /data/adb/netpilot/run/stopped && CLASSPATH=$apk /system/bin/app_process /system/bin --nice-name=netpilot-control dev.netpilot.service.RootSupervisor.RootSupervisorMain --request-cleanup", 60)
            recordRootResult("停止与恢复入口", result)
            check(result.success) { "守护停止与恢复未确认：${result.output}" }
        }
        store.save(next); store.writeText("sync.pending", "true")
        mutable.update { currentState -> currentState.copy(config = next, connected = false, runtime = currentState.runtime.copy(supervisor = currentState.runtime.supervisor.copy(running = false, paused = true))) }
        AppScheduleEngine(protectedContext).cancel()
    }
    suspend fun restartGuardian() = guardianOperation("重启 Root 守护") {
        if (mutable.value.connected) { val response = client.request("RESTART"); check(response.ok) { response.error.orEmpty() }; delay(1_200) }
        else {
            val result = installer.start(mutable.value.config, mutable.value.schedule)
            if ("NETPILOT_AUTO_REPAIR" in result.output) recordLocal(LogLevel.WARN, "app-root", "检测到 Root 启动文件缺失，已尝试从当前 APK 自动修复")
            recordRootResult("重启启动入口", result); check(result.success) { result.output }
        }
        check(waitForConnection()) { "未确认守护重启。启动日志：\n${installer.bootstrapLogs().takeLast(2_000)}" }; reschedule()
    }
    suspend fun pauseAutomation() {
        setAutomationPaused(!state.value.config.guardian.paused)
    }
    suspend fun setAutomationPaused(paused: Boolean) {
        if (!state.value.connected && state.value.config.guardian.installed) {
            if (paused) stopGuardian() else startGuardian()
        } else updateConfig { it.copy(guardian = it.guardian.copy(paused = paused)) }
    }
    suspend fun uninstallGuardian() = guardianOperation("卸载守护并恢复配置") {
        if (mutable.value.connected) {
            val response = client.request("UNINSTALL_PREP")
            check(response.ok) { response.error.orEmpty() }
            delay(1_200)
        }
        val result = installer.uninstall()
        recordRootResult("卸载与恢复脚本", result)
        check(result.success) { "卸载恢复未完成，已保留守护文件：${result.output}" }
        val next = mutable.value.config.copy(guardian = mutable.value.config.guardian.copy(installed = false, paused = true, dozeWhitelistAddedByUs = false))
        store.save(next); store.child("sync.pending").delete()
        mutable.update { currentState -> currentState.copy(config = next, connected = false, runtime = RuntimeState(capabilities = currentState.runtime.capabilities)) }
        AppScheduleEngine(protectedContext).cancel()
    }
    suspend fun command(name: String): String = withContext(Dispatchers.IO) {
        mutex.withLock { val response = client.request(name); check(response.ok) { response.error.orEmpty() }; refreshLocked(); response.payload }
    }
    suspend fun readLogs() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val remote = if (mutable.value.connected) try {
                NetPilotJson.decodeFromString<List<LogEntry>>(requestPayloadLocked("LOGS"))
            } catch (error: Exception) {
                recordConnectionLoss("读取运行日志", error)
                bootstrapEntries()
            } else bootstrapEntries()
            mutable.update { currentState -> currentState.copy(logs = (startupLogger.entries() + remote).sortedBy { it.timestamp }.takeLast(300)) }
        }
    }
    suspend fun exportLogs(): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            recordLocal(LogLevel.INFO, "app-logs", "生成日志导出；控制通道已连接=${mutable.value.connected}")
            val remote = remoteTextOrBootstrap("EXPORT_LOGS", "导出运行日志")
            val strings = this@NetPilotRepository.strings
            "${exportHeader(strings)}\n\n[${strings(R.string.export_snapshot_section)}]\n${NetPilotJson.encodeToString(Snapshot(mutable.value.runtime, mutable.value.config, mutable.value.schedule))}\n\n[${strings(R.string.export_local_section)}]\n${startupLogger.export().ifBlank { strings(R.string.export_empty_local) }}\n\n[${strings(R.string.export_root_section)}]\n$remote\n"
        }
    }
    suspend fun diagnostics(): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            val remote = remoteTextOrBootstrap("DIAGNOSTICS", "生成诊断")
            val strings = this@NetPilotRepository.strings
            "${exportHeader(strings)}\n\n[${strings(R.string.export_snapshot_section)}]\n${NetPilotJson.encodeToString(Snapshot(mutable.value.runtime, mutable.value.config, mutable.value.schedule))}\n\n[${strings(R.string.export_local_section)}]\n${startupLogger.export()}\n\n[${strings(R.string.export_root_diagnostics_section)}]\n$remote"
        }
    }
    suspend fun clearLogs() = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (mutable.value.connected) requestPayloadLocked("CLEAR_LOGS")
            startupLogger.clear()
            mutable.update { currentState -> currentState.copy(logs = startupLogger.entries()) }
        }
        readLogs()
    }
    suspend fun backup(): String = if (mutable.value.connected) command("BACKUP") else NetPilotJson.encodeToString(BackupDocument(createdAt = System.currentTimeMillis(), config = mutable.value.config, schedule = mutable.value.schedule))
    suspend fun restore(text: String) = withContext(Dispatchers.IO) {
        require(text.toByteArray().size <= 128 * 1024) { "配置备份超过 128 KB" }
        val document = NetPilotJson.decodeFromString<BackupDocument>(text)
        require(document.format == "netpilot-backup" && document.version == 1) { "不是受支持的 NetPilot 配置备份" }
        document.config.validate()?.let { error(it) }
        ScheduleCalculator.validate(document.schedule)?.let { error(it) }
        mutex.withLock {
            val current = mutable.value.config
            val config = document.config.copy(rootProvider = current.rootProvider, guardian = document.config.guardian.copy(installed = current.guardian.installed, dozeWhitelistAddedByUs = current.guardian.dozeWhitelistAddedByUs))
            val restored = document.copy(config = config)
            if (mutable.value.connected) { val response = client.request("RESTORE", NetPilotJson.encodeToString(restored)); check(response.ok) { response.error.orEmpty() } }
            else { store.writeText("restore.pending.json", NetPilotJson.encodeToString(restored)); store.recoverPendingRestore(); store.writeText("sync.pending", "true") }
            store.save(config); store.save(restored.schedule)
            mutable.update { currentState -> currentState.copy(config = config, schedule = restored.schedule) }
            refreshLocked(); reschedule()
        }
    }
    suspend fun resetDefaults() = restore(NetPilotJson.encodeToString(BackupDocument(createdAt = System.currentTimeMillis(), config = AppConfig(), schedule = ScheduleDocument())))
    suspend fun recordActionFailure(action: String, error: Exception) = withContext(Dispatchers.IO) {
        recordLocal(LogLevel.ERROR, "app-action", "$action 失败：${errorDetail(error)}")
    }
    private suspend fun guardianOperation(label: String, block: suspend () -> Unit) = withContext(Dispatchers.IO) {
        mutex.withLock {
            recordLocal(LogLevel.INFO, "app-guardian", "$label：开始")
            try {
                block()
                recordLocal(LogLevel.INFO, "app-guardian", "$label：已完成")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val detail = errorDetail(error)
                recordLocal(LogLevel.ERROR, "app-guardian", "$label 失败：$detail")
                mutable.update { currentState -> currentState.copy(runtime = currentState.runtime.copy(supervisor = currentState.runtime.supervisor.copy(lastError = "$label 失败：$detail"))) }
                throw error
            }
        }
    }
    private fun recordRootResult(step: String, result: CommandResult) {
        recordLocal(if (result.success) LogLevel.INFO else LogLevel.ERROR, "app-root", "$step：exit=${result.exitCode} timeout=${result.timedOut}\n${result.output.ifBlank { "命令无输出" }}")
    }
    private fun recordLocal(level: LogLevel, source: String, message: String) {
        runCatching { startupLogger.log(level, source, message) }.onFailure { android.util.Log.e("NetPilot", "本地日志写入失败", it) }
    }
    private fun errorDetail(error: Exception) = "${error.javaClass.simpleName}: ${error.message ?: "未提供错误详情"}".take(4_096)
    private fun recordConnectionLoss(action: String, error: Exception) {
        recordLocal(LogLevel.WARN, "app-control", "$action 时控制通道断开，改读本地记录及 Root 启动诊断：${errorDetail(error)}")
        mutable.update { currentState -> currentState.copy(connected = false, runtime = currentState.runtime.copy(supervisor = currentState.runtime.supervisor.copy(running = false))) }
    }
    private fun requestPayloadLocked(command: String): String {
        val response = client.request(command)
        check(response.ok) { response.error.orEmpty() }
        return response.payload
    }
    private fun bootstrapEntries(): List<LogEntry> = if (mutable.value.config.guardian.installed || mutable.value.runtime.capabilities.rootGranted) {
        listOf(LogEntry(System.currentTimeMillis(), LogLevel.WARN, "root-startup", installer.bootstrapLogs()))
    } else emptyList()
    private fun remoteTextOrBootstrap(command: String, action: String): String {
        if (mutable.value.connected) try {
            val text = requestPayloadLocked(command)
            if (text.isNotBlank()) return text
            recordLocal(LogLevel.WARN, "app-control", "$action 返回空内容，读取 Root 启动诊断")
        } catch (error: Exception) {
            recordConnectionLoss(action, error)
        }
        return if (mutable.value.config.guardian.installed || mutable.value.runtime.capabilities.rootGranted) installer.bootstrapLogs()
        else "尚未安装或未获得 Root 授权；失败详情已保存在 APP 本地启动与操作日志中。"
    }
    private fun exportHeader(strings: LocalizedStrings) = strings(R.string.export_header,
        BuildConfig.VERSION_NAME, java.time.Instant.now().toString(), Build.VERSION.RELEASE, Build.VERSION.SDK_INT,
        mutable.value.config.rootProvider.name, mutable.value.runtime.capabilities.rootGranted.toString(),
        mutable.value.config.guardian.installed.toString(), mutable.value.connected.toString())
    private suspend fun waitForConnection(): Boolean { repeat(20) { if (refreshLocked()) return true; delay(750) }; return false }
    private fun reschedule() = AppScheduleEngine(protectedContext).reschedule(mutable.value.config, mutable.value.schedule)
    fun busy(message: String?) { mutable.update { it.copy(busy = message) } }
    fun message(message: String?) { mutable.update { it.copy(message = message) } }
    fun diagnostic(text: String?) { mutable.update { it.copy(diagnosticText = text) } }
}
