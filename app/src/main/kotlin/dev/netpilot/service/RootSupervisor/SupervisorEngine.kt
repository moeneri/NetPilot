package dev.netpilot.service.RootSupervisor

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import dev.netpilot.compat.android.CapabilityDetector
import dev.netpilot.compat.hotspot.HotspotIdleShutdown
import dev.netpilot.compat.root.bootAdapter
import dev.netpilot.compat.root.PackageIdentity
import dev.netpilot.compat.tethering.*
import dev.netpilot.compat.usb.*
import dev.netpilot.core.model.*
import dev.netpilot.core.network.NetworkUpstreamMonitor
import dev.netpilot.core.root.RootCommandExecutor
import dev.netpilot.core.schedule.ScheduleCalculator
import dev.netpilot.core.tethering.RetryGate
import dev.netpilot.core.tethering.SharingPolicy
import dev.netpilot.core.tethering.SharingStartPolicy
import dev.netpilot.core.usb.UsbClassifier
import dev.netpilot.core.usb.UsbDebouncer
import dev.netpilot.data.logs.RuntimeLogger
import dev.netpilot.data.repository.AtomicJsonStore
import dev.netpilot.service.ScheduleEngine.RootScheduleEngine
import kotlinx.serialization.encodeToString
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.net.NetworkInterface
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** One actor serializes ownership, requests, configuration and persistence. */
class SupervisorEngine(private val context: Context, private val runAutomation: Boolean = true, private val quit: (Int, Boolean) -> Unit) : AutoCloseable {
    companion object { const val PACKAGE = "dev.netpilot"; val ROOT = File("/data/adb/netpilot") }
    private val actor = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "netpilot-engine") }
    private val initialized = AtomicBoolean(false)
    private val tickQueued = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    @Volatile private var cleanupRequested = false
    private val store = AtomicJsonStore(ROOT, true).apply { recoverPendingRestore() }
    private val executor = RootCommandExecutor(true)
    private var config = if (runAutomation) store.requireConfig() else store.config()
    private var schedule = if (runAutomation) store.requireSchedule() else store.schedule()
    private val bootId = runCatching { File("/proc/sys/kernel/random/boot_id").readText().trim() }.getOrDefault("unknown")
    private val restored = store.stateForRecovery()
    private val installedIdentity = runCatching { NetPilotJson.decodeFromString<RootInstallIdentity>(File(ROOT, "runtime/identity.json").readText()) }.getOrNull()
    private var state = if (restored.supervisor.bootId == bootId) restored else RuntimeState(
        // Android persists the hotspot setting across boots, but the old sharing session is gone.
        wifiIdleShutdownRestorePending = restored.wifiIdleShutdownRestorePending,
    )
    private val logger = RuntimeLogger(File(ROOT, "logs"), config.logLevel)
    private val debouncer = UsbDebouncer()
    private val usbRetry = RetryGate()
    private val wifiRetry = RetryGate()
    private val usbStopRetry = RetryGate()
    private val wifiStopRetry = RetryGate()
    private val usbRestoreRetry = RetryGate()
    private val wifiIdleShutdownRetry = RetryGate()
    private val wifiIdleShutdownRestoreRetry = RetryGate()
    private val wifiIdleShutdown = HotspotIdleShutdown(context)
    private var wifiIdleShutdownApplied = false
    private var fingerprint = ""
    private var usbResetGraceUntil = 0L
    private var wifiResetGraceUntil = 0L
    private var usbUnsupported = false
    private var upstreamRejected = false
    private var timeFuture: ScheduledFuture<*>? = null
    private var usbPollFuture: ScheduledFuture<*>? = null
    private var usbPollFingerprint: Pair<UsbSignals, UsbConnection>? = null
    private var debounceFuture: ScheduledFuture<*>? = null
    private var retryFuture: ScheduledFuture<*>? = null
    private var retryFutureAt: Long? = null
    private var lastSummary = ""
    private var lastUsbSignalSummary = ""
    private var lastUsbAutomation: StartEligibility? = null
    private var lastUserUnlocked = context.getSystemService(android.os.UserManager::class.java).isUserUnlocked
    private var monitor: UsbCompat = RootUsbMonitor(context, config.oem, ::signal)
    private var tethering: TetheringCompat = selectTetheringAdapter(context, config, executor, ::signal)
    private val network = NetworkUpstreamMonitor(context, ::signal)
    private val gadget = UsbGadgetAdapter(context, executor)
    private var rootAlarm: RootScheduleEngine? = runCatching { RootScheduleEngine(context, logger) { signal(true) } }.onFailure { logger.error("schedule", "Root 闹钟初始化失败：${it.message}") }.getOrNull()

    init {
        state = state.copy(
            capabilities = CapabilityDetector.detect(true, config.rootProvider).copy(bootAdapter = bootAdapter(config.rootProvider).name, parallel = state.capabilities.parallel),
            supervisor = state.supervisor.copy(running = runAutomation, pid = Process.myPid(), startedAt = System.currentTimeMillis(), bootId = bootId, paused = config.guardian.paused, lastError = null),
        )
        initialized.set(true)
        if (runAutomation) {
            actor.scheduleWithFixedDelay({ guardedTick(checkPackage = true) }, 0, 60, TimeUnit.SECONDS)
            // sysfs does not reliably deliver inotify events. Sample independently of health checks.
            usbPollFuture = actor.scheduleWithFixedDelay({ pollUsb() }, 2, 2, TimeUnit.SECONDS)
            signal(true)
            logger.info("guardian", "Root Supervisor 已启动，PID=${Process.myPid()}；不依赖 Activity 或普通 APP 进程")
        }
    }

    private fun signal(forceAlarm: Boolean = false) {
        if (!runAutomation || !initialized.get() || closed.get() || cleanupRequested) return
        if (forceAlarm) actor.execute { rootAlarm?.arm(null, true); guardedTick() }
        else if (tickQueued.compareAndSet(false, true)) actor.schedule({ tickQueued.set(false); guardedTick() }, 300, TimeUnit.MILLISECONDS)
    }

    private fun pollUsb() {
        if (closed.get() || cleanupRequested) return
        try {
            val signals = monitor.capture()
            val probe = signals.copy(capturedAt = 0, broadcastUpdatedAt = 0) to UsbClassifier.classify(signals, config.usb).connection
            if (probe != usbPollFingerprint) { usbPollFingerprint = probe; guardedTick() }
        } catch (error: Exception) {
            logger.warn("usb", "USB 兼容采样失败：${error.javaClass.simpleName}: ${error.message}")
        }
    }

    private fun guardedTick(checkPackage: Boolean = false) {
        if (closed.get() || cleanupRequested) return
        try {
            if (checkPackage) {
                val application = runCatching { context.packageManager.getApplicationInfo(PACKAGE, 0) }.getOrNull()
                if (application == null) {
                    logger.warn("guardian", "应用已卸载，停止本应用共享并恢复 USB 配置")
                    if (cleanup()) quit(0, true) else { store.child("run").mkdirs(); File(ROOT, "run/stopped").writeText("restore-incomplete"); quit(5, false) }
                    return
                }
                if (installedIdentity == null || !PackageIdentity.matches(context, installedIdentity)) {
                    logger.warn("guardian", "应用安装 UID 或签名发生变化，停止本次守护；新安装必须重新授予 Root")
                    File(ROOT, "run/stopped").writeText("install-identity-changed")
                    cleanup(); quit(0, false); return
                }
                if (application.flags and ApplicationInfo.FLAG_STOPPED != 0) {
                    logger.info("guardian", "检测到用户强制停止应用，停止自动化并保留诊断配置")
                    config = config.copy(guardian = config.guardian.copy(paused = true)); persistConfig()
                    File(ROOT, "run/stopped").writeText("user-force-stop")
                    cleanup(); quit(0, false); return
                }
                val oldSource = runCatching { File(ROOT, "runtime/source.path").readText() }.getOrNull()
                if (oldSource != null && oldSource != application.sourceDir) {
                    logger.info("guardian", "检测到应用更新，刷新已安装 APK 与启动脚本后重启守护")
                    if (cleanup() && refreshInstalledPayload(application.sourceDir)) { quit(75, false); return }
                    logger.error("guardian", "更新恢复失败，保留原守护与配置供诊断")
                }
            }
            tick(verifyWifiIdleShutdown = checkPackage)
        } catch (error: Exception) {
            logger.error("guardian", "状态更新失败：${error.javaClass.simpleName}: ${error.message}")
            state = state.copy(supervisor = state.supervisor.copy(lastError = error.message), lastUpdated = System.currentTimeMillis())
            store.save(state)
        }
    }

    private fun tick(verifyWifiIdleShutdown: Boolean) {
        val now = System.currentTimeMillis()
        val userUnlocked = context.getSystemService(android.os.UserManager::class.java).isUserUnlocked
        if (userUnlocked != lastUserUnlocked) { lastUserUnlocked = userUnlocked; resetRetryGates(); usbUnsupported = false; logger.info("guardian", "用户解锁状态变化，重新评估共享与计划") }
        val elapsed = SystemClock.elapsedRealtime()
        val signals = monitor.capture()
        var decision = UsbClassifier.classify(signals, config.usb)
        usbPollFingerprint = signals.copy(capturedAt = 0, broadcastUpdatedAt = 0) to decision.connection
        val usbSignalSummary = "connected=${signals.broadcastConnected} configured=${signals.broadcastConfigured} config=${signals.config} functions=${signals.functions} gadget=${signals.gadgetStates} roles=${signals.dataRoles} power=${signals.powerConnected} supply=${signals.powerSupplyTypes} portConnected=${signals.servicePortConnected} dataEnabled=${signals.serviceDataEnabled} monitorError=${signals.monitorError}".take(4_096)
        if (usbSignalSummary != lastUsbSignalSummary) { logger.info("usb-signals", usbSignalSummary); lastUsbSignalSummary = usbSignalSummary }
        if (decision.connection == UsbConnection.ENUMERATING && signals.capturedAt > 0 && elapsed > usbResetGraceUntil && !signals.broadcastConfigured.orFalse() && signals.gadgetStates.values.none { it.equals("configured", true) }) {
            // The state remains unconfirmed: never label supply alone as a verified ordinary charger.
            decision = decision.copy(detail = "供电 / 数据尚未枚举，保持原 USB 模式")
        }
        val stableHost = debouncer.isReady(decision, elapsed, config.usb.debounceMillis)
        if (decision.connection == UsbConnection.DATA_HOST && !stableHost) {
            if (debounceFuture == null || debounceFuture?.isDone == true) debounceFuture = actor.schedule({ guardedTick() }, config.usb.debounceMillis, TimeUnit.MILLISECONDS)
        } else if (decision.connection != UsbConnection.DATA_HOST) { debounceFuture?.cancel(false); debounceFuture = null }
        // Reconcile missed ROM callbacks before deciding whether system sharing is already active.
        tethering.refresh()
        val observation = tethering.observation()
        if (observation.observationError != null || (!observation.upstreamObserved && now - state.supervisor.startedAt > 15_000)) {
            val reason = observation.observationError ?: "原生共享状态回调在 15 秒内未确认；ROM 接口或系统权限受限，未执行自动共享"
            if (state.supervisor.lastError != reason) logger.error("tethering", reason)
            state = state.copy(supervisor = state.supervisor.copy(lastError = reason))
        }
        val networkSnapshot = network.capture(observation.upstream, observation.upstreamObserved)
        val upstreamStatus = networkSnapshot.upstream
        val key = "${decision.connection}:${networkSnapshot.sourceKey}"
        if (key != fingerprint) { fingerprint = key; resetRetryGates(); usbUnsupported = false; upstreamRejected = false }
        if (upstreamStatus.tetherUpstreamConfirmed) upstreamRejected = false
        val evaluation = ScheduleCalculator.evaluate(schedule.slots, Instant.ofEpochMilli(now), currentZone())
        val effectiveHost = stableHost || (state.usb.ownedByNetPilot && elapsed < usbResetGraceUntil && signals.powerConnected)
        val priorityIntent = SharingPolicy.desired(config, effectiveHost && !usbUnsupported && !usbRetry.exhausted, evaluation.active, state.capabilities.parallel)
        state = state.copy(
            usbSignals = signals, usbDecision = decision, mobile = networkSnapshot.mobile, upstream = upstreamStatus,
            supervisor = state.supervisor.copy(lastHeartbeat = now, paused = config.guardian.paused, currentScheduleActive = evaluation.active,
                nextTransitionAt = evaluation.next?.instant?.toEpochMilli(), nextTransitionOn = evaluation.next?.turnOn,
                recoveryAttempts = maxOf(usbRetry.attempts, wifiRetry.attempts)),
        )
        val physicallyDisconnected = decision.connection == UsbConnection.DISCONNECTED || decision.connection == UsbConnection.POWER_ONLY
        val transientUsbReset = elapsed < usbResetGraceUntil && signals.powerConnected
        if (state.usb.ownedByNetPilot && !priorityIntent.usb && !transientUsbReset) {
            if (!physicallyDisconnected || config.usb.stopOnDisconnect || config.guardian.paused || !config.usb.enabled || observation.interfacesByType[TYPE_USB].isNullOrEmpty()) stopOwned(TYPE_USB)
        }
        if ((state.wifi.ownedByNetPilot || state.wifiStartPending) && !priorityIntent.wifi) stopOwned(TYPE_WIFI)
        if (!state.wifi.ownedByNetPilot && !state.wifiStartPending) restoreWifiIdleShutdown()
        if (!state.usb.ownedByNetPilot && !state.usbStartPending && physicallyDisconnected && state.originalUsbFunctions != null) restoreUsbFunctions()

        if ((state.usb.ownedByNetPilot || state.wifi.ownedByNetPilot) && observation.upstreamObserved && observation.upstream != null && network.isVpn(observation.upstream)) {
            logger.warn("upstream", "系统共享选择了 VPN 上游；当前未管理其路由")
            upstreamRejected = true
            stopOwned(TYPE_USB); stopOwned(TYPE_WIFI)
            state = state.copy(supervisor = state.supervisor.copy(lastError = "系统共享上游尚未恢复，等待网络变化或手动重试"))
        }
        val startNetwork = SharingStartPolicy.network(upstreamStatus, observation.upstreamObserved, upstreamRejected)
        val mayStart = startNetwork.allowed
        if (priorityIntent.usb && !state.usb.ownedByNetPilot && observation.interfacesByType[TYPE_USB].isNullOrEmpty()) {
            if (mayStart && usbRetry.canAttempt(elapsed)) {
                val operation = startOwned(TYPE_USB)
                if (!operation.accepted && !operation.outcomeUncertain) {
                    usbRetry.failed(elapsed)
                    if (!observation.interfacesByType[TYPE_WIFI].isNullOrEmpty() && config.priority != SharingPriority.WIFI_FIRST && config.priority == SharingPriority.AUTO) {
                        if (state.wifi.ownedByNetPilot) {
                            logger.warn("parallel", "当前并行请求失败，回退 USB 优先；以关闭热点后的结果判断 USB 能力")
                            state = state.copy(capabilities = state.capabilities.copy(parallel = ParallelCapability.UNSUPPORTED))
                            stopOwned(TYPE_WIFI)
                            actor.schedule({ guardedTick() }, 5, TimeUnit.SECONDS)
                        } else logger.warn("parallel", "系统热点由用户管理，无法为了 USB 共享关闭它")
                    } else if (operation.code in setOf("API_UNAVAILABLE", "TETHER_ERROR_3", "OBSERVATION_UNAVAILABLE")) usbUnsupported = true
                }
            } else if (!mayStart) state = state.copy(usb = state.usb.copy(phase = TetherPhase.WAITING_UPSTREAM, detail = startNetwork.detail))
        }
        if (priorityIntent.wifi && !state.wifi.ownedByNetPilot && !state.wifiStartPending && tethering.observation().interfacesByType[TYPE_WIFI].isNullOrEmpty()) {
            if (mayStart && wifiRetry.canAttempt(elapsed)) {
                val operation = startOwned(TYPE_WIFI)
                if (!operation.accepted && !operation.outcomeUncertain) wifiRetry.failed(elapsed)
            } else if (!mayStart) state = state.copy(wifi = state.wifi.copy(phase = TetherPhase.WAITING_UPSTREAM, detail = startNetwork.detail))
        }
        val latest = tethering.observation()
        val latestNetwork = network.capture(latest.upstream, latest.upstreamObserved)
        state = state.copy(mobile = latestNetwork.mobile, upstream = latestNetwork.upstream)
        state = state.copy(usb = describe(TYPE_USB, state.usb, latest), wifi = describe(TYPE_WIFI, state.wifi, latest))
        if (!latest.interfacesByType[TYPE_USB].isNullOrEmpty() && !latest.interfacesByType[TYPE_WIFI].isNullOrEmpty()) {
            state = state.copy(capabilities = state.capabilities.copy(parallel = ParallelCapability.SUPPORTED))
        }
        if (state.usb.phase == TetherPhase.READY) usbRetry.succeeded()
        if (state.wifi.phase == TetherPhase.READY) wifiRetry.succeeded()
        if (state.usb.ownedByNetPilot && latest.interfacesByType[TYPE_USB].isNullOrEmpty() && elapsed >= usbResetGraceUntil && priorityIntent.usb && mayStart && config.guardian.restoreOnFailure) {
            recoverLost(TYPE_USB, usbRetry, elapsed)
        }
        if (priorityIntent.wifi && (state.wifi.ownedByNetPilot || state.wifiStartPending)) {
            val wifiInterfaceReported = !latest.interfacesByType[TYPE_WIFI].isNullOrEmpty()
            if (wifiInterfaceReported && state.wifi.ownedByNetPilot) {
                disableWifiIdleShutdown(verifyWifiIdleShutdown)
            } else if (!wifiInterfaceReported && elapsed >= wifiResetGraceUntil && mayStart && config.guardian.restoreOnFailure) {
                recoverLost(TYPE_WIFI, wifiRetry, elapsed)
            }
        }
        if (usbRetry.exhausted || wifiRetry.exhausted) state = state.copy(supervisor = state.supervisor.copy(lastError = "本轮恢复次数已达上限，等待连接、网络或配置变化；可手动重试"))
        if (!priorityIntent.usb && !state.usb.ownedByNetPilot && latest.interfacesByType[TYPE_USB].isNullOrEmpty() && state.usb.phase in setOf(TetherPhase.WAITING_UPSTREAM, TetherPhase.STARTING)) state = state.copy(usb = TetherStatus(adapter = tethering.name))
        if (!priorityIntent.wifi && !state.wifi.ownedByNetPilot && latest.interfacesByType[TYPE_WIFI].isNullOrEmpty() && state.wifi.phase in setOf(TetherPhase.WAITING_UPSTREAM, TetherPhase.STARTING)) state = state.copy(wifi = TetherStatus(adapter = tethering.name))
        val nextAt = if (config.guardian.paused || config.wifiMode != WifiMode.AUTO) null else evaluation.next?.instant?.toEpochMilli()
        rootAlarm?.arm(nextAt)
        timeFuture?.cancel(false)
        if (nextAt != null) timeFuture = actor.schedule({ guardedTick() }, (nextAt - now).coerceAtLeast(1), TimeUnit.MILLISECONDS)
        state = state.copy(supervisor = state.supervisor.copy(alarmMode = rootAlarm?.mode ?: AlarmMode.TIMER_ONLY), lastUpdated = now)
        state = state.copy(usbAutomation = SharingStartPolicy.usb(config, state, effectiveHost, priorityIntent,
            SharingStartPolicy.network(state.upstream, latest.upstreamObserved, upstreamRejected), latest.interfacesByType[TYPE_USB].orEmpty(), usbUnsupported, usbRetry, elapsed))
        val summary = "USB=${state.usb.phase} Wi-Fi=${state.wifi.phase} 上游可用=${state.upstream.available} 验证=${state.upstream.validated} 共享上游=${state.upstream.tetherUpstreamConfirmed} 上游类型=${state.upstream.type} 默认网络=${state.upstream.defaultType} 识别=${decision.connection} 暂停=${config.guardian.paused} USB启用=${config.usb.enabled} 自动USB=${state.usbAutomation.code} 回调=${latest.upstreamObserved}"
        if (summary != lastSummary) { logger.info("state", summary); lastSummary = summary }
        if (state.usbAutomation != lastUsbAutomation) { logger.info("usb-auto", "${state.usbAutomation.code}: ${state.usbAutomation.detail}"); lastUsbAutomation = state.usbAutomation }
        store.save(state)
        val retryAt = retryGates().filter { !it.exhausted && it.attempts > 0 && it.nextAt > elapsed }.minOfOrNull { it.nextAt }
        if (retryAt != retryFutureAt) {
            retryFuture?.cancel(false); retryFutureAt = retryAt
            retryFuture = retryAt?.let { target -> actor.schedule({ retryFutureAt = null; guardedTick() }, target - elapsed, TimeUnit.MILLISECONDS) }
        }
    }

    private fun startOwned(type: Int): TetherOperation = withTransitionWakeLock {
        val previous = if (type == TYPE_USB) state.usb else state.wifi
        if (type == TYPE_USB) {
            state = state.copy(originalUsbFunctions = state.originalUsbFunctions ?: gadget.currentFunctions(), usbStartPending = true, usb = previous.copy(phase = TetherPhase.STARTING, detail = "请求系统 USB 网络共享", adapter = tethering.name))
            usbResetGraceUntil = SystemClock.elapsedRealtime() + 12_000
        } else {
            wifiResetGraceUntil = SystemClock.elapsedRealtime() + 12_000
            state = state.copy(wifiStartPending = true, wifi = previous.copy(phase = TetherPhase.STARTING, detail = "请求系统 Wi-Fi Internet Tethering", adapter = tethering.name))
        }
        store.save(state)
        if (type == TYPE_WIFI) disableWifiIdleShutdown()
        val operation = tethering.start(type)
        val status = previous.copy(ownedByNetPilot = operation.accepted || operation.outcomeUncertain, phase = if (operation.accepted) TetherPhase.STARTING else TetherPhase.ERROR, detail = operation.detail, errorCode = operation.code, adapter = tethering.name)
        if (type == TYPE_USB) {
            state = state.copy(usb = status, usbStartPending = operation.outcomeUncertain, usbFunctionsChangedByUs = gadget.currentFunctions() != state.originalUsbFunctions)
            if (operation.accepted && state.originalUsbFunctions != null) {
                val adbResult = gadget.preserveAdb(state.originalUsbFunctions!!)
                if (!adbResult.success) logger.warn("usb", "保留原 ADB 功能失败：${adbResult.output}")
            }
            if (!operation.accepted && !operation.outcomeUncertain) restoreUsbFunctions()
        } else {
            state = state.copy(wifi = status, wifiStartPending = operation.outcomeUncertain)
            if (!operation.accepted && !operation.outcomeUncertain) restoreWifiIdleShutdown()
        }
        logger.log(if (operation.accepted) LogLevel.INFO else LogLevel.ERROR, if (type == TYPE_USB) "usb" else "wifi", "${operation.code ?: "REQUEST_ACCEPTED"}: ${operation.detail}")
        store.save(state)
        actor.schedule({ guardedTick() }, 3, TimeUnit.SECONDS)
        return@withTransitionWakeLock operation
    }

    private fun stopOwned(type: Int): Boolean = withTransitionWakeLock {
        val current = if (type == TYPE_USB) state.usb else state.wifi
        val pending = if (type == TYPE_USB) state.usbStartPending else state.wifiStartPending
        if (!current.ownedByNetPilot && !pending) return@withTransitionWakeLock type != TYPE_WIFI || restoreWifiIdleShutdown()
        val stopRetry = if (type == TYPE_USB) usbStopRetry else wifiStopRetry
        if (!stopRetry.canAttempt(SystemClock.elapsedRealtime())) return@withTransitionWakeLock false
        val operation = tethering.stop(type)
        if (!operation.accepted) {
            logger.error("restore", "停止 ${if (type == TYPE_USB) "USB" else "Wi-Fi"} 失败：${operation.code} ${operation.detail}")
            stopRetry.failed(SystemClock.elapsedRealtime())
            state = state.copy(supervisor = state.supervisor.copy(lastError = "共享停止失败：${operation.code} ${operation.detail}"))
            return@withTransitionWakeLock false
        }
        val deadline = SystemClock.elapsedRealtime() + 6_000
        tethering.refresh()
        while (!tethering.observation().interfacesByType[type].isNullOrEmpty() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(200)
        tethering.refresh()
        if (!tethering.observation().interfacesByType[type].isNullOrEmpty()) { stopRetry.failed(SystemClock.elapsedRealtime()); logger.error("restore", "共享停止尚未得到系统确认，保留归属与恢复信息"); return@withTransitionWakeLock false }
        stopRetry.succeeded()
        if (type == TYPE_USB) {
            state = state.copy(usb = TetherStatus(adapter = tethering.name), usbStartPending = false)
            if (!restoreUsbFunctions()) { store.save(state); return@withTransitionWakeLock false }
        } else state = state.copy(wifi = TetherStatus(adapter = tethering.name), wifiStartPending = false)
        store.save(state)
        logger.info("restore", "已停止本应用启动的 ${if (type == TYPE_USB) "USB" else "Wi-Fi"} 共享")
        type != TYPE_WIFI || restoreWifiIdleShutdown()
    }

    private fun disableWifiIdleShutdown(verify: Boolean = false) {
        if ((wifiIdleShutdownApplied && !verify) || !wifiIdleShutdownRetry.canAttempt(SystemClock.elapsedRealtime())) return
        try {
            val changed = wifiIdleShutdown.setEnabled(false) {
                if (!state.wifiIdleShutdownRestorePending) saveWifiIdleShutdownRestorePending(true)
            }
            if (changed || !wifiIdleShutdownApplied) logger.info("wifi-idle", "热点空闲自动关闭已停用")
            wifiIdleShutdownApplied = true
            wifiIdleShutdownRetry.succeeded()
        } catch (error: Exception) {
            wifiIdleShutdownApplied = false
            wifiIdleShutdownRetry.failed(SystemClock.elapsedRealtime())
            val reason = (error as? InvocationTargetException)?.targetException ?: error
            logger.warn("wifi-idle", "热点空闲自动关闭设置未确认：${reason.javaClass.simpleName}: ${reason.message}")
        }
    }

    private fun restoreWifiIdleShutdown(): Boolean {
        wifiIdleShutdownApplied = false
        wifiIdleShutdownRetry.reset()
        if (!state.wifiIdleShutdownRestorePending) return true
        if (!wifiIdleShutdownRestoreRetry.canAttempt(SystemClock.elapsedRealtime())) return false
        return try {
            wifiIdleShutdown.setEnabled(true)
            saveWifiIdleShutdownRestorePending(false)
            wifiIdleShutdownRestoreRetry.succeeded()
            logger.info("wifi-idle", "已恢复热点空闲自动关闭")
            true
        } catch (error: Exception) {
            wifiIdleShutdownRestoreRetry.failed(SystemClock.elapsedRealtime())
            val reason = (error as? InvocationTargetException)?.targetException ?: error
            logger.error("wifi-idle", "恢复热点空闲自动关闭失败：${reason.javaClass.simpleName}: ${reason.message}")
            false
        }
    }

    private fun saveWifiIdleShutdownRestorePending(pending: Boolean) {
        val next = state.copy(wifiIdleShutdownRestorePending = pending)
        store.save(next)
        state = next
    }

    private fun restoreUsbFunctions(): Boolean {
        val original = state.originalUsbFunctions ?: return true
        if (!config.usb.restoreFunctions) { state = state.copy(originalUsbFunctions = null, usbFunctionsChangedByUs = false); return true }
        if (gadget.currentFunctions() != original || state.usbFunctionsChangedByUs) {
            if (!usbRestoreRetry.canAttempt(SystemClock.elapsedRealtime())) return false
            val result = gadget.restore(original)
            if (!result.success) {
                logger.error("usb", "USB 功能恢复失败，保留重试信息：${result.output}")
                state = state.copy(usb = state.usb.copy(phase = TetherPhase.ERROR, errorCode = "USB_RESTORE_FAILED", detail = result.output))
                usbRestoreRetry.failed(SystemClock.elapsedRealtime())
                return false
            }
        }
        state = state.copy(originalUsbFunctions = null, usbFunctionsChangedByUs = false)
        usbRestoreRetry.succeeded()
        return true
    }

    private fun recoverLost(type: Int, retry: RetryGate, elapsed: Long) {
        if (!retry.canAttempt(elapsed)) return
        // Cancel a late/pending request before reissuing it; never run duplicate pending requests.
        if (!stopOwned(type)) { retry.failed(elapsed); return }
        val operation = startOwned(type)
        retry.failed(elapsed)
        if (!operation.accepted) logger.warn("guardian", "恢复请求失败，按退避策略等待")
    }

    private fun describe(type: Int, current: TetherStatus, observation: TetherObservation): TetherStatus {
        val interfaces = observation.interfacesByType[type].orEmpty()
        val addresses = interfaces.flatMap { iface -> runCatching { NetworkInterface.getByName(iface)?.inetAddresses?.toList().orEmpty().filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }.mapNotNull { it.hostAddress } }.getOrDefault(emptyList()) }
        val error = interfaces.firstNotNullOfOrNull { observation.errors[it] }
        if (interfaces.isNotEmpty()) {
            val phase = when { !current.ownedByNetPilot -> TetherPhase.EXTERNAL; error != null -> TetherPhase.ERROR; addresses.isEmpty() -> TetherPhase.INTERFACE_READY; !state.upstream.tetherUpstreamConfirmed || (!state.upstream.validated && !state.upstream.dunUpstream) -> TetherPhase.WAITING_UPSTREAM; else -> TetherPhase.READY }
            val detail = when (phase) {
                TetherPhase.EXTERNAL -> "由系统 / 用户开启，NetPilot 未接管"
                TetherPhase.ERROR -> "系统共享接口错误 $error"
                TetherPhase.INTERFACE_READY -> "系统共享已启动，等待接口分配 IP"
                TetherPhase.WAITING_UPSTREAM -> "共享接口已配置，等待上游网络就绪"
                else -> if (state.upstream.dunUpstream) "系统已选择移动共享专用上游（DUN）；客户端联网须由客户端确认" else "共享接口与系统上游已就绪；客户端联网须由客户端确认"
            }
            return current.copy(phase = phase, interfaces = interfaces, addresses = addresses, clientAddressCount = observation.clientAddressCounts[type], detail = detail, errorCode = error?.let { "TETHER_ERROR_$it" }, adapter = tethering.name)
        }
        if (current.ownedByNetPilot) return current.copy(phase = if (type == TYPE_USB && gadget.isRndis()) TetherPhase.GADGET_READY else TetherPhase.STARTING, interfaces = emptyList(), addresses = emptyList(), detail = "等待系统报告实际共享接口", adapter = tethering.name)
        if (current.phase == TetherPhase.EXTERNAL) return TetherStatus(adapter = tethering.name, clientAddressCount = observation.clientAddressCounts[type])
        return current.copy(interfaces = emptyList(), addresses = emptyList(), clientAddressCount = observation.clientAddressCounts[type], adapter = tethering.name)
    }

    private fun persistConfig() {
        store.save(config); logger.threshold = config.logLevel
        if (config.guardian.bootEnabled) File(ROOT, "boot.enabled").writeText("enabled") else File(ROOT, "boot.enabled").delete()
    }

    fun request(request: IpcRequest): IpcResponse = try {
        actor.submit<IpcResponse> {
            when (request.command) {
                "PING" -> IpcResponse(true, "pong")
                "SNAPSHOT" -> IpcResponse(true, NetPilotJson.encodeToString(Snapshot(state, config, schedule)))
                "SAVE_CONFIG" -> {
                    val next = NetPilotJson.decodeFromString<AppConfig>(request.payload)
                    next.validate()?.let { return@submit IpcResponse(false, error = it) }
                    val adapterChanged = next.oem != config.oem || next.tetheringAdapter != config.tetheringAdapter
                    val businessChanged = next.usb != config.usb || next.wifiMode != config.wifiMode || next.priority != config.priority || next.guardian.paused != config.guardian.paused || adapterChanged
                    if (adapterChanged && (!stopOwned(TYPE_USB) || !stopOwned(TYPE_WIFI))) return@submit IpcResponse(false, error = "旧适配器未能确认共享停止，暂不切换适配器")
                    if (cleanupRequested && config.guardian.paused && !next.guardian.paused) {
                        cleanupRequested = false
                        File(ROOT, "run/stopped").delete()
                    }
                    config = next.copy(rootProvider = config.rootProvider, guardian = next.guardian.copy(installed = true, dozeWhitelistAddedByUs = config.guardian.dozeWhitelistAddedByUs))
                    persistConfig()
                    if (adapterChanged) { monitor.close(); tethering.close(); monitor = RootUsbMonitor(context, config.oem, ::signal); tethering = selectTetheringAdapter(context, config, executor, ::signal) }
                    if (businessChanged) { resetRetryGates(); usbUnsupported = false; upstreamRejected = false }
                    if (adapterChanged) debouncer.reset()
                    guardedTick(); IpcResponse(true)
                }
                "SAVE_SCHEDULE" -> {
                    val next = NetPilotJson.decodeFromString<ScheduleDocument>(request.payload)
                    ScheduleCalculator.validate(next)?.let { return@submit IpcResponse(false, error = it) }
                    schedule = next; store.save(schedule); guardedTick(); logger.info("schedule", "已保存 ${next.slots.size} 个时间段并重新计算闹钟"); IpcResponse(true)
                }
                "PAUSE", "RESUME" -> {
                    if (request.command == "RESUME") {
                        cleanupRequested = false
                        File(ROOT, "run/stopped").delete()
                    }
                    config = config.copy(guardian = config.guardian.copy(paused = request.command == "PAUSE")); persistConfig()
                    resetRetryGates(); upstreamRejected = false; guardedTick(); IpcResponse(true)
                }
                "RETRY" -> {
                    if (cleanupRequested) {
                        if (!cleanup()) IpcResponse(false, error = "停止恢复仍未完成，保留恢复信息；请查看诊断日志")
                        else { actor.schedule({ quit(0, false) }, 700, TimeUnit.MILLISECONDS); IpcResponse(true) }
                    } else { resetRetryGates(); usbUnsupported = false; upstreamRejected = false; guardedTick(); IpcResponse(true) }
                }
                "WAKE" -> { guardedTick(); IpcResponse(true) }
                "RECALCULATE" -> { rootAlarm?.arm(null, true); guardedTick(); IpcResponse(true) }
                "LOGS" -> IpcResponse(true, NetPilotJson.encodeToString(logger.entries()))
                "EXPORT_LOGS" -> IpcResponse(true, "[Root snapshot]\n${NetPilotJson.encodeToString(Snapshot(state, config, schedule))}\n\n[Root runtime logs]\n${logger.export()}")
                "CLEAR_LOGS" -> { logger.clear(); IpcResponse(true) }
                "DIAGNOSTICS" -> IpcResponse(true, diagnostics())
                "DOZE_ADD" -> {
                    val power = context.getSystemService(PowerManager::class.java)
                    if (!power.isIgnoringBatteryOptimizations(PACKAGE)) {
                        val result = executor.execute(listOf("cmd", "deviceidle", "whitelist", "+$PACKAGE"), 10)
                        if (!result.success) return@submit IpcResponse(false, error = result.output)
                        config = config.copy(guardian = config.guardian.copy(dozeWhitelistAddedByUs = true)); persistConfig()
                    }
                    IpcResponse(true)
                }
                "BACKUP" -> {
                    val backup = NetPilotJson.encodeToString(BackupDocument(createdAt = System.currentTimeMillis(), config = config, schedule = schedule))
                    AtomicJsonStore(File(ROOT, "backup"), true).writeText("latest.json", backup)
                    IpcResponse(true, backup)
                }
                "RESTORE" -> {
                    val backup = NetPilotJson.decodeFromString<BackupDocument>(request.payload)
                    if (backup.format != "netpilot-backup" || backup.version != 1) return@submit IpcResponse(false, error = "备份格式或版本无效")
                    backup.config.validate()?.let { return@submit IpcResponse(false, error = it) }
                    ScheduleCalculator.validate(backup.schedule)?.let { return@submit IpcResponse(false, error = it) }
                    if (!stopOwned(TYPE_USB) || !stopOwned(TYPE_WIFI)) return@submit IpcResponse(false, error = "共享恢复尚未完成，未导入配置")
                    val restoredConfig = backup.config.copy(rootProvider = config.rootProvider, guardian = backup.config.guardian.copy(installed = true, dozeWhitelistAddedByUs = config.guardian.dozeWhitelistAddedByUs))
                    val transaction = backup.copy(config = restoredConfig)
                    store.writeText("restore.pending.json", NetPilotJson.encodeToString(transaction))
                    config = restoredConfig; schedule = backup.schedule; persistConfig(); store.save(schedule); store.child("restore.pending.json").delete()
                    monitor.close(); tethering.close(); monitor = RootUsbMonitor(context, config.oem, ::signal); tethering = selectTetheringAdapter(context, config, executor, ::signal)
                    resetRetryGates(); debouncer.reset(); guardedTick(); logger.info("config", "已恢复配置与时间计划"); IpcResponse(true)
                }
                "STOP", "RESTART", "UNINSTALL_PREP" -> {
                    val restart = request.command == "RESTART"
                    if (!restart) File(ROOT, "run/stopped").writeText("user-stop")
                    if (!cleanup()) IpcResponse(false, error = "系统设置恢复未完成；保留守护文件，请查看诊断日志")
                    else { actor.schedule({ quit(if (restart) 75 else 0, false) }, 700, TimeUnit.MILLISECONDS); IpcResponse(true) }
                }
                else -> IpcResponse(false, error = "不支持的控制命令")
            }
        }.get(45, TimeUnit.SECONDS)
    } catch (error: Exception) { IpcResponse(false, error = "${error.javaClass.simpleName}: ${error.cause?.message ?: error.message}") }

    fun cleanup(): Boolean {
        // Teardown must prevent queued USB, network and alarm callbacks from starting
        // new sharing sessions before the exit request has released the control socket.
        cleanupRequested = true
        resetRetryGates()
        runCatching { rootAlarm?.arm(null, true) }.onFailure { logger.warn("schedule", "取消 Root 闹钟失败：${it.message}") }
        timeFuture?.cancel(false)
        debounceFuture?.cancel(false); retryFuture?.cancel(false); retryFutureAt = null
        val usbOk = stopOwned(TYPE_USB)
        val wifiOk = stopOwned(TYPE_WIFI)
        val restoreOk = if (!state.usb.ownedByNetPilot) restoreUsbFunctions() else false
        var whitelistOk = true
        if (config.guardian.dozeWhitelistAddedByUs) {
            val result = executor.execute(listOf("cmd", "deviceidle", "whitelist", "-$PACKAGE"), 10)
            whitelistOk = result.success
            if (whitelistOk) { config = config.copy(guardian = config.guardian.copy(dozeWhitelistAddedByUs = false)); persistConfig() }
        }
        val complete = usbOk && wifiOk && restoreOk && whitelistOk
        if (!complete && !config.guardian.paused) {
            config = config.copy(guardian = config.guardian.copy(paused = true))
            persistConfig()
        }
        state = state.copy(supervisor = state.supervisor.copy(running = !complete && runAutomation, paused = true, lastError = if (complete) state.supervisor.lastError else "系统设置恢复未完成，保留守护与诊断配置")); store.save(state)
        return complete
    }

    fun shutdownCleanup(): Boolean = runCatching { actor.submit<Boolean> { cleanup() }.get(40, TimeUnit.SECONDS) }.getOrDefault(false)
    private fun retryGates() = listOf(usbRetry, wifiRetry, usbStopRetry, wifiStopRetry, usbRestoreRetry, wifiIdleShutdownRetry, wifiIdleShutdownRestoreRetry)
    private fun resetRetryGates() { retryGates().forEach { it.reset() } }
    private fun refreshInstalledPayload(source: String): Boolean = runCatching {
        val apk = File(source)
        require(apk.isFile && source.startsWith("/data/app/") && source.endsWith(".apk"))
        java.util.zip.ZipFile(apk).use { zip ->
            listOf("start.sh", "supervise.sh", "uninstall.sh").forEach { name ->
                val entry = zip.getEntry("assets/scripts/$name") ?: error("启动脚本缺失：$name")
                require(entry.size in 1..32_768)
                val temporary = File(ROOT, "$name.new")
                zip.getInputStream(entry).use { input -> temporary.outputStream().use { output -> input.copyTo(output) } }
                android.system.Os.chmod(temporary.path, 493)
                android.system.Os.rename(temporary.path, File(ROOT, name).path)
            }
        }
        val temporaryApk = File(ROOT, "runtime/netpilot.apk.new")
        apk.inputStream().use { input -> java.io.FileOutputStream(temporaryApk).use { output -> input.copyTo(output); output.fd.sync() } }
        android.system.Os.chmod(temporaryApk.path, 292)
        android.system.Os.rename(temporaryApk.path, File(ROOT, "runtime/netpilot.apk").path)
        File(ROOT, "runtime/source.path").writeText(source)
        true
    }.onFailure { logger.error("update", "更新缓存失败：${it.message}") }.getOrDefault(false)
    private fun diagnostics(): String {
        val usbDump = executor.execute(listOf("dumpsys", "usb"), 8).output.take(25_000)
        val tetherDump = executor.execute(listOf("dumpsys", "tethering"), 8).output.take(25_000)
        val connectivityDump = executor.execute(listOf("dumpsys", "connectivity"), 8).output.take(25_000)
        return "NetPilot ${dev.netpilot.BuildConfig.VERSION_NAME}\n[Root snapshot]\n${NetPilotJson.encodeToString(Snapshot(state, config, schedule))}\n\nUSB service\n$usbDump\n\nTethering service\n$tetherDump\n\nConnectivity service\n$connectivityDump\n\n${logger.export().takeLast(40_000)}"
    }
    private fun currentZone(): ZoneId = runCatching { ZoneId.of(systemProperty("persist.sys.timezone").ifBlank { ZoneId.systemDefault().id }) }.getOrDefault(ZoneId.systemDefault())
    private fun Boolean?.orFalse() = this == true
    private fun <T> withTransitionWakeLock(block: () -> T): T {
        val lock = runCatching { context.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NetPilot:transition").apply { acquire(30_000) } }.getOrNull()
        return try { block() } finally { if (lock?.isHeld == true) runCatching { lock.release() } }
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        initialized.set(false); timeFuture?.cancel(false); usbPollFuture?.cancel(false); debounceFuture?.cancel(false); retryFuture?.cancel(false); rootAlarm?.close(); monitor.close(); tethering.close(); network.close(); actor.shutdownNow()
    }
}
