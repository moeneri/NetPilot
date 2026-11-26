package dev.netpilot.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

val NetPilotJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    prettyPrint = false
    isLenient = false
}

@Serializable enum class WifiMode { AUTO, FORCE_ON, FORCE_OFF }
@Serializable enum class SharingPriority { AUTO, USB_FIRST, WIFI_FIRST }
@Serializable enum class ThemeMode { SYSTEM, LIGHT, DARK }
@Serializable enum class LogLevel { DEBUG, INFO, WARN, ERROR }
@Serializable enum class RootProvider { MAGISK, KERNEL_SU, APATCH, OTHER, NONE, UNKNOWN }
@Serializable enum class ParallelCapability { UNKNOWN, SUPPORTED, UNSUPPORTED }
@Serializable enum class AdapterMode { AUTO, API_30, API_31, API_36, OEM }

@Serializable
data class UsbSettings(
    val enabled: Boolean = true,
    val excludeChargers: Boolean = true,
    val requireDataConfirmation: Boolean = true,
    val strictDetection: Boolean = false,
    val debounceMillis: Long = 2_000,
    val stopOnDisconnect: Boolean = true,
    val restoreFunctions: Boolean = true,
    val suspendWifiWhenConnected: Boolean = false,
)

@Serializable
data class GuardianSettings(
    val installed: Boolean = false,
    val bootEnabled: Boolean = true,
    val foregroundEnabled: Boolean = true,
    val paused: Boolean = false,
    val restoreOnFailure: Boolean = true,
    val dozeWhitelistAddedByUs: Boolean = false,
)

/** OEM commands are disabled until the user explicitly enables the adapter. */
@Serializable
data class OemSettings(
    val enabled: Boolean = false,
    val usbStartCommand: String = "",
    val usbStopCommand: String = "",
    val wifiStartCommand: String = "",
    val wifiStopCommand: String = "",
    val usbInterfaceRegex: String = "",
    val wifiInterfaceRegex: String = "",
    val usbStatePath: String = "",
    val dataRolePath: String = "",
)

@Serializable
data class AppConfig(
    val schemaVersion: Int = 1,
    val usb: UsbSettings = UsbSettings(),
    val guardian: GuardianSettings = GuardianSettings(),
    val wifiMode: WifiMode = WifiMode.AUTO,
    val priority: SharingPriority = SharingPriority.AUTO,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val logLevel: LogLevel = LogLevel.INFO,
    val rootProvider: RootProvider = RootProvider.UNKNOWN,
    val tetheringAdapter: AdapterMode = AdapterMode.AUTO,
    val oem: OemSettings = OemSettings(),
) {
    fun validate(): String? {
        if (schemaVersion != 1) return "不支持的配置版本：$schemaVersion"
        if (usb.debounceMillis !in 500L..10_000L) return "USB 防抖时间须在 0.5–10 秒之间"
        val commands = listOf(oem.usbStartCommand, oem.usbStopCommand, oem.wifiStartCommand, oem.wifiStopCommand)
        if (commands.any { it.length > 2_048 || it.any { c -> c == '\n' || c == '\r' || c.code == 0 } }) {
            return "厂商命令必须为不超过 2048 字符的单行命令"
        }
        if (oem.enabled && ((oem.usbStartCommand.isBlank() != oem.usbStopCommand.isBlank()) || (oem.wifiStartCommand.isBlank() != oem.wifiStopCommand.isBlank()))) return "厂商启动与停止命令必须成对配置"
        if (tetheringAdapter == AdapterMode.OEM && (!oem.enabled || commands.all { it.isBlank() })) return "选择厂商适配时需启用并配置至少一组原生共享命令"
        val paths = listOf(oem.usbStatePath, oem.dataRolePath)
        if (paths.any { it.isNotBlank() && (!it.startsWith("/sys/") || ".." in it || it.any(Char::isWhitespace)) }) {
            return "USB 信号路径必须是 /sys/ 下的绝对路径"
        }
        val regexes = listOf(oem.usbInterfaceRegex, oem.wifiInterfaceRegex)
        if (regexes.any { it.length > 128 || runCatching { Regex(it) }.isFailure }) return "接口正则表达式无效或过长"
        return null
    }
}

@Serializable
data class ScheduleSlot(
    val id: String,
    val label: String,
    val startMinute: Int,
    val endMinute: Int,
    val days: Set<Int> = (1..7).toSet(),
    val enabled: Boolean = true,
) {
    val crossesMidnight: Boolean get() = endMinute < startMinute
    val startText: String get() = minuteText(startMinute)
    val endText: String get() = minuteText(endMinute)
    companion object {
        fun minuteText(value: Int): String = "%02d:%02d".format(value / 60, value % 60)
        fun defaults() = listOf(
            ScheduleSlot("default-morning", "上午", 8 * 60 + 30, 12 * 60 + 10),
            ScheduleSlot("default-afternoon", "下午", 13 * 60 + 30, 18 * 60 + 10),
        )
    }
}

@Serializable
data class ScheduleDocument(val schemaVersion: Int = 1, val slots: List<ScheduleSlot> = ScheduleSlot.defaults())

@Serializable
data class BackupDocument(
    val format: String = "netpilot-backup",
    val version: Int = 1,
    val createdAt: Long,
    val config: AppConfig,
    val schedule: ScheduleDocument,
)

@Serializable
data class RootInstallIdentity(val appUid: Int, val signerDigests: Set<String>)
