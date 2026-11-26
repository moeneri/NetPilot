package dev.netpilot.core.tethering

import dev.netpilot.core.model.AppConfig
import dev.netpilot.core.model.ParallelCapability
import dev.netpilot.core.model.SharingPriority
import dev.netpilot.core.model.WifiMode

data class SharingIntent(val usb: Boolean, val wifi: Boolean, val reason: String?)

object SharingPolicy {
    fun desired(config: AppConfig, usbDataReady: Boolean, scheduleActive: Boolean, parallel: ParallelCapability): SharingIntent {
        if (config.guardian.paused) return SharingIntent(false, false, "自动化已暂停")
        var usb = config.usb.enabled && usbDataReady
        var wifi = when (config.wifiMode) { WifiMode.AUTO -> scheduleActive; WifiMode.FORCE_ON -> true; WifiMode.FORCE_OFF -> false }
        var reason: String? = null
        if (usb && wifi) {
            when {
                config.priority == SharingPriority.WIFI_FIRST -> { usb = false; reason = "Wi-Fi 优先，USB 等待热点关闭" }
                config.priority == SharingPriority.USB_FIRST || config.usb.suspendWifiWhenConnected || parallel == ParallelCapability.UNSUPPORTED -> {
                    wifi = false; reason = "USB 优先，按时间计划恢复 Wi-Fi"
                }
            }
        }
        return SharingIntent(usb, wifi, reason)
    }
}

/** Bounded retry series. Only a meaningful state/configuration change resets it. */
class RetryGate(private val maxAttempts: Int = 6, private val maxDelayMillis: Long = 300_000) {
    var attempts: Int = 0; private set
    var nextAt: Long = 0; private set
    val exhausted: Boolean get() = attempts >= maxAttempts
    fun canAttempt(now: Long): Boolean = !exhausted && now >= nextAt
    fun failed(now: Long) {
        attempts++
        nextAt = now + (5_000L * (1L shl (attempts - 1).coerceAtMost(20))).coerceAtMost(maxDelayMillis)
    }
    fun succeeded() { attempts = 0; nextAt = 0 }
    fun reset() = succeeded()
}
