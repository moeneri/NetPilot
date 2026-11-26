package dev.netpilot.core.usb

import dev.netpilot.core.model.UsbConnection
import dev.netpilot.core.model.UsbDecision
import dev.netpilot.core.model.UsbSettings
import dev.netpilot.core.model.UsbSignals

object UsbClassifier {
    fun classify(signals: UsbSignals, settings: UsbSettings): UsbDecision {
        val evidence = mutableListOf<String>()
        val states = signals.gadgetStates.values.map { it.trim().uppercase() }
        val serviceRoles = signals.dataRoles.filterKeys { it.startsWith("usb_service/") }
        val roles = (if (serviceRoles.isNotEmpty()) serviceRoles else signals.dataRoles).values.map { value ->
            Regex("\\[([^]]+)]").find(value)?.groupValues?.get(1) ?: value.trim()
        }
        val deviceRole = roles.any { it.equals("device", true) || it.equals("peripheral", true) || it.equals("ufp", true) }
        val hostRole = roles.any { it.equals("host", true) || it.equals("dfp", true) }
        val gadgetEnumerated = states.any { it == "CONFIGURED" }
        val addressed = states.any { it == "ADDRESS" || it == "ADDRESSED" }
        val broadcastFresh = signals.broadcastUpdatedAt == 0L || signals.capturedAt - signals.broadcastUpdatedAt <= 5_000
        val liveDisconnected = (states.isNotEmpty() && states.all { it in setOf("DISCONNECTED", "NOT ATTACHED", "NOT_ATTACHED") }) ||
            (states.isEmpty() && signals.servicePortConnected == false)
        val staleConnectedBroadcast = !broadcastFresh && liveDisconnected
        val broadcastEnumerated = signals.broadcastConfigured == true && !staleConnectedBroadcast
        val supplyTypes = signals.powerSupplyTypes.map { (path, value) ->
            val active = Regex("\\[([^]]+)]").find(value)?.groupValues?.get(1) ?: value.trim()
            if (path.endsWith("/real_type") && active.equals("USB", true)) "SDP" else active.uppercase()
        }
        val downstreamDataPort = supplyTypes.any { it in setOf("SDP", "USB_SDP", "CDP", "USB_CDP") }
        val dedicatedCharger = supplyTypes.any { it in setOf("DCP", "USB_DCP") }
        if (signals.broadcastConnected == true && !staleConnectedBroadcast) evidence += "USB_CONNECTED"
        if (broadcastEnumerated) evidence += "USB_CONFIGURED"
        if (gadgetEnumerated) evidence += "Gadget 已完成数据枚举"
        if (addressed) evidence += "Gadget 已收到主机地址"
        if (deviceRole) evidence += "USB 数据角色为设备端"
        if (downstreamDataPort) evidence += "BC 1.2 下行数据端口（SDP / CDP）"
        if (dedicatedCharger) evidence += "充电协议报告专用充电端口（DCP）"
        if (signals.servicePortConnected == true) evidence += "USB 服务确认端口连接"
        if (signals.config.isNotBlank()) evidence += "功能配置：${signals.config}"
        if (staleConnectedBroadcast) evidence += "旧 USB 广播已过期，采用当前端口 / Gadget 状态"
        val connected = (signals.broadcastConnected == true && !staleConnectedBroadcast) || broadcastEnumerated || gadgetEnumerated || addressed || states.any { it == "CONNECTED" } || signals.servicePortConnected == true || (downstreamDataPort && deviceRole && signals.powerConnected && !liveDisconnected)
        if ((signals.broadcastConnected == false && broadcastFresh && (broadcastEnumerated || gadgetEnumerated)) || (broadcastEnumerated && broadcastFresh && liveDisconnected)) {
            return UsbDecision(UsbConnection.ENUMERATING, 0, evidence, "USB 信号正在变化，等待稳定")
        }
        if (signals.serviceDataEnabled == false) return UsbDecision(UsbConnection.SIGNALS_INSUFFICIENT, 0, evidence, "系统已禁用此端口 USB 数据，保持现有安全设置")
        if (hostRole && !deviceRole) return UsbDecision(UsbConnection.SIGNALS_INSUFFICIENT, 0, evidence, "手机处于 USB 主机角色，不作为共享设备")
        if (!connected) return if (signals.powerConnected) {
            UsbDecision(UsbConnection.POWER_ONLY, 0, evidence, "仅检测到供电，未确认数据连接")
        } else UsbDecision(UsbConnection.DISCONNECTED, 0, evidence, "USB 未连接")
        val enumSources = (if (broadcastEnumerated) 1 else 0) + (if (gadgetEnumerated) 1 else 0)
        val negotiatedDataHost = downstreamDataPort && !dedicatedCharger && deviceRole && signals.serviceDataEnabled != false && (signals.servicePortConnected == true || signals.powerConnected)
        val earlyReset = !settings.requireDataConfirmation && deviceRole && !dedicatedCharger && (states.any { it == "DEFAULT" } || (!settings.excludeChargers && signals.broadcastConnected == true && states.any { it == "CONNECTED" }))
        val dataConfirmed = enumSources > 0 || (addressed && deviceRole && signals.serviceDataEnabled != false) || negotiatedDataHost || earlyReset
        if (!dataConfirmed) return UsbDecision(UsbConnection.ENUMERATING, 25, evidence, "已连接，等待电脑数据枚举；不会切换 USB 功能")
        if (settings.strictDetection && (enumSources < 2 || !deviceRole)) {
            return UsbDecision(UsbConnection.SIGNALS_INSUFFICIENT, 50, evidence, "严格模式需要广播、Gadget 枚举及设备数据角色同时确认")
        }
        return UsbDecision(UsbConnection.DATA_HOST, (60 + enumSources * 15 + if (deviceRole) 10 else 0).coerceAtMost(100), evidence, if (negotiatedDataHost && enumSources == 0) "下行数据端口与设备角色已确认，可从充电模式自动启动共享" else "已确认 USB 数据主机，等待防抖后启动共享")
    }
}

/** Monotonic time only; wall-clock/timezone changes cannot shorten the debounce. */
class UsbDebouncer {
    private var lastConnection: UsbConnection? = null
    private var firstSeenAt = 0L
    fun isReady(decision: UsbDecision, nowElapsed: Long, debounceMillis: Long): Boolean {
        if (lastConnection != decision.connection) {
            lastConnection = decision.connection
            firstSeenAt = nowElapsed
        }
        return decision.connection == UsbConnection.DATA_HOST && nowElapsed - firstSeenAt >= debounceMillis
    }
    fun reset() { lastConnection = null; firstSeenAt = 0 }
}
