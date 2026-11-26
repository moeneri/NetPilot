package dev.netpilot.compat.tethering

import dev.netpilot.core.model.AdapterMode
import dev.netpilot.core.model.AppConfig

class RootTetheringAdapter(private val native: ReflectiveTetheringAdapter, private val oem: OemTetheringAdapter, private val config: AppConfig) : TetheringCompat {
    override val name get() = if (config.tetheringAdapter == AdapterMode.OEM) "厂商原生共享适配" else native.name
    init { native.configureRegex(config.oem.usbInterfaceRegex, config.oem.wifiInterfaceRegex) }
    override fun start(type: Int): TetherOperation {
        if (!native.observation().eventsRegistered) return TetherOperation(false, "OBSERVATION_UNAVAILABLE", "无法观察原生共享状态，未执行启动。${native.initializationError.orEmpty()}")
        if (config.tetheringAdapter == AdapterMode.OEM) return oem.start(type)
        val first = native.start(type)
        return if (!first.accepted && !first.outcomeUncertain && first.code in setOf("API_UNAVAILABLE", "PERMISSION_DENIED", "TETHER_ERROR_14", "TETHER_ERROR_15") && config.oem.enabled) oem.start(type) else first
    }
    override fun stop(type: Int): TetherOperation {
        if (config.tetheringAdapter == AdapterMode.OEM) return oem.stop(type)
        val first = native.stop(type)
        return if (!first.accepted && !first.outcomeUncertain && config.oem.enabled) oem.stop(type) else first
    }
    override fun observation() = native.observation()
    override fun refresh() = native.refresh()
    override fun close() = native.close()
}
