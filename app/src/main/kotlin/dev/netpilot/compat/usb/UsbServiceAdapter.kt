package dev.netpilot.compat.usb

import android.content.Context

data class UsbServiceState(val connected: Boolean? = null, val dataEnabled: Boolean? = null, val dataRoles: Map<String, String> = emptyMap())

/** Hidden port APIs are capability-probed in the root runtime, including Android 11. */
class UsbServiceAdapter(private val context: Context) {
    fun capture(): UsbServiceState = runCatching {
        val manager = context.getSystemService("usb") ?: return UsbServiceState()
        val ports = manager.javaClass.getMethod("getPorts").invoke(manager) as? List<*> ?: return UsbServiceState()
        val roles = mutableMapOf<String, String>()
        var connected = false
        var observedPorts = 0
        var dataEnabled: Boolean? = null
        ports.forEachIndexed { index, port ->
            if (port == null) return@forEachIndexed
            val status = port.javaClass.getMethod("getStatus").invoke(port) ?: return@forEachIndexed
            observedPorts++
            val active = status.javaClass.getMethod("isConnected").invoke(status) as Boolean
            if (!active) return@forEachIndexed
            connected = true
            val role = status.javaClass.getMethod("getCurrentDataRole").invoke(status) as Int
            roles["usb_service/port$index"] = when (role) { 1 -> "host"; 2 -> "device"; else -> "none" }
            val enabled = runCatching {
                val value = status.javaClass.getMethod("getUsbDataStatus").invoke(status) as Int
                val flag = status.javaClass.getField("DATA_STATUS_ENABLED").getInt(null)
                if (value == 0) null else value and flag != 0
            }.getOrNull()
            if (enabled != null) dataEnabled = enabled
        }
        // Micro-USB ROMs can have no port API; absence is unknown, not a disconnect.
        UsbServiceState(connected.takeIf { observedPorts > 0 }, dataEnabled, roles)
    }.getOrDefault(UsbServiceState())
}
