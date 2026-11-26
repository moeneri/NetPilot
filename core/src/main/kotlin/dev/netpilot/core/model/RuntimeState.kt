package dev.netpilot.core.model

import kotlinx.serialization.Serializable

@Serializable enum class Health { OK, RUNNING, WAITING, ERROR, OFF, UNKNOWN }
@Serializable enum class UsbConnection { DISCONNECTED, ENUMERATING, POWER_ONLY, DATA_HOST, SIGNALS_INSUFFICIENT }
@Serializable enum class TetherPhase { OFF, STARTING, GADGET_READY, INTERFACE_READY, WAITING_UPSTREAM, READY, EXTERNAL, ERROR, UNSUPPORTED }
@Serializable enum class AlarmMode { ROOT_EXACT, APP_EXACT, INEXACT, TIMER_ONLY, UNKNOWN }
@Serializable enum class UpstreamType { NONE, CELLULAR, WIFI, ETHERNET, BLUETOOTH, VPN, OTHER, UNKNOWN }

@Serializable
data class UsbSignals(
    val broadcastConnected: Boolean? = null,
    val broadcastConfigured: Boolean? = null,
    val broadcastUpdatedAt: Long = 0,
    val gadgetStates: Map<String, String> = emptyMap(),
    val config: String = "",
    val functions: String = "",
    val dataRoles: Map<String, String> = emptyMap(),
    val powerConnected: Boolean = false,
    val powerSupplyTypes: Map<String, String> = emptyMap(),
    val servicePortConnected: Boolean? = null,
    val serviceDataEnabled: Boolean? = null,
    val monitorError: String? = null,
    val capturedAt: Long = 0,
)

@Serializable
data class UsbDecision(
    val connection: UsbConnection = UsbConnection.DISCONNECTED,
    val confidence: Int = 0,
    val evidence: List<String> = emptyList(),
    val detail: String = "USB 未连接",
)

@Serializable
data class TetherStatus(
    val phase: TetherPhase = TetherPhase.OFF,
    val ownedByNetPilot: Boolean = false,
    val interfaces: List<String> = emptyList(),
    val addresses: List<String> = emptyList(),
    val clientAddressCount: Int? = null,
    val detail: String = "未开启",
    val errorCode: String? = null,
    val adapter: String = "待检测",
)

/** Retained as cellular-only diagnostic data for older v1 snapshots. */
@Serializable
data class MobileStatus(
    val available: Boolean = false,
    val validated: Boolean = false,
    val tetherUpstreamConfirmed: Boolean = false,
    val defaultIsMobile: Boolean = false,
    val defaultNetworkPresent: Boolean = false,
    val dunUpstream: Boolean = false,
    val interfaceName: String? = null,
    val detail: String = "等待移动数据",
    val monitorError: String? = null,
)

@Serializable
data class UpstreamStatus(
    val available: Boolean = false,
    val validated: Boolean = false,
    val tetherUpstreamConfirmed: Boolean = false,
    val type: UpstreamType = UpstreamType.NONE,
    val defaultType: UpstreamType = UpstreamType.NONE,
    val defaultNetworkPresent: Boolean = false,
    val networkHandle: Long? = null,
    val interfaceName: String? = null,
    val dunUpstream: Boolean = false,
    val detail: String = "等待系统共享选择上游网络",
    val monitorError: String? = null,
)

@Serializable
data class StartEligibility(
    val allowed: Boolean = false,
    val code: String = "USB_WAITING_DATA",
    val detail: String = "USB 未连接",
    val retryAt: Long? = null,
)

@Serializable
data class DeviceCapabilities(
    val sdk: Int = 0,
    val androidRelease: String = "",
    val manufacturer: String = "",
    val model: String = "",
    val rootGranted: Boolean = false,
    val rootProvider: RootProvider = RootProvider.UNKNOWN,
    val tetheringApi: Boolean = false,
    val tetheringEvents: Boolean = false,
    val tetheringRequest: Boolean = false,
    val usbSignalPaths: List<String> = emptyList(),
    val usbRolePaths: List<String> = emptyList(),
    val parallel: ParallelCapability = ParallelCapability.UNKNOWN,
    val bootAdapter: String = "尚未安装",
    val notes: List<String> = listOf("ROM 网络共享能力尚未实机验证"),
)

@Serializable
data class SupervisorStatus(
    val running: Boolean = false,
    val pid: Int = 0,
    val startedAt: Long = 0,
    val lastHeartbeat: Long = 0,
    val bootId: String = "",
    val paused: Boolean = false,
    val alarmMode: AlarmMode = AlarmMode.UNKNOWN,
    val nextTransitionAt: Long? = null,
    val nextTransitionOn: Boolean? = null,
    val currentScheduleActive: Boolean = false,
    val lastError: String? = null,
    val recoveryAttempts: Int = 0,
)

@Serializable
data class RuntimeState(
    val schemaVersion: Int = 1,
    val supervisor: SupervisorStatus = SupervisorStatus(),
    val capabilities: DeviceCapabilities = DeviceCapabilities(),
    val usbSignals: UsbSignals = UsbSignals(),
    val usbDecision: UsbDecision = UsbDecision(),
    val usbAutomation: StartEligibility = StartEligibility(),
    val usb: TetherStatus = TetherStatus(),
    val wifi: TetherStatus = TetherStatus(),
    val mobile: MobileStatus = MobileStatus(),
    val upstream: UpstreamStatus = UpstreamStatus(),
    val originalUsbFunctions: String? = null,
    val usbFunctionsChangedByUs: Boolean = false,
    val usbStartPending: Boolean = false,
    val wifiStartPending: Boolean = false,
    val wifiIdleShutdownRestorePending: Boolean = false,
    val lastUpdated: Long = 0,
)

@Serializable
data class LogEntry(val timestamp: Long, val level: LogLevel, val source: String, val message: String)

@Serializable
data class IpcRequest(val command: String, val payload: String = "", val protocolVersion: Int = 1)

@Serializable
data class IpcResponse(val ok: Boolean, val payload: String = "", val error: String? = null, val protocolVersion: Int = 1)

@Serializable
data class Snapshot(val state: RuntimeState, val config: AppConfig, val schedule: ScheduleDocument)
