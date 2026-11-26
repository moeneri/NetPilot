package dev.netpilot.core.tethering

import dev.netpilot.core.model.*

/** Downstream requests use the native service's upstream; the app default is diagnostic only. */
object SharingStartPolicy {
    fun network(upstream: UpstreamStatus, upstreamObserved: Boolean, upstreamRejected: Boolean): StartEligibility = when {
        !upstreamObserved -> StartEligibility(code = "OBSERVATION_UNAVAILABLE", detail = "等待系统共享状态回调，暂不请求共享")
        upstream.type == UpstreamType.VPN -> StartEligibility(code = "UNSUPPORTED_UPSTREAM", detail = "系统共享选择了 VPN 上游；当前未管理其路由")
        upstreamRejected && !upstream.tetherUpstreamConfirmed -> StartEligibility(code = "UPSTREAM_REJECTED", detail = "系统共享上游尚未恢复，等待网络变化或手动重试")
        else -> StartEligibility(true, "ALLOW_NATIVE_START", "请求系统共享并等待上游网络")
    }

    fun usb(
        config: AppConfig,
        state: RuntimeState,
        dataReady: Boolean,
        intent: SharingIntent,
        network: StartEligibility,
        systemInterfaces: List<String>,
        unsupported: Boolean,
        retry: RetryGate,
        elapsed: Long,
    ): StartEligibility = when {
        config.guardian.paused -> StartEligibility(code = "PAUSED", detail = "自动化已暂停")
        !config.usb.enabled -> StartEligibility(code = "USB_DISABLED", detail = "USB 自动共享已关闭")
        state.usb.ownedByNetPilot || state.usbStartPending -> StartEligibility(code = "OWNED_OR_PENDING", detail = state.usb.detail)
        systemInterfaces.isNotEmpty() -> StartEligibility(code = "SYSTEM_MANAGED", detail = "由系统 / 用户开启，NetPilot 未接管")
        state.usbDecision.connection != UsbConnection.DATA_HOST -> StartEligibility(code = "USB_WAITING_DATA", detail = state.usbDecision.detail)
        !dataReady -> StartEligibility(code = "USB_DEBOUNCING", detail = "数据主机已确认，等待防抖结束")
        unsupported -> StartEligibility(code = "USB_UNSUPPORTED", detail = "本轮 USB 共享不可用，请查看系统错误并重试")
        retry.exhausted -> StartEligibility(code = "RETRY_EXHAUSTED", detail = "本轮恢复次数已达上限，等待连接、网络或配置变化；可手动重试")
        !intent.usb -> StartEligibility(code = "PRIORITY_WAIT", detail = intent.reason ?: "Wi-Fi 优先，USB 等待热点关闭")
        !network.allowed -> network
        !retry.canAttempt(elapsed) -> StartEligibility(code = "RETRY_WAIT", detail = "恢复请求失败，按退避策略等待", retryAt = state.supervisor.lastHeartbeat + (retry.nextAt - elapsed).coerceAtLeast(0))
        else -> StartEligibility(true, "REQUEST_READY", "已确认 USB 数据主机，准备请求系统共享")
    }
}
