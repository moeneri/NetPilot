package dev.netpilot.compat.root

import dev.netpilot.core.model.RootProvider

interface RootBootAdapter {
    val name: String
    val startupPath: String?
    val moduleBased: Boolean
}
class MagiskAdapter : RootBootAdapter {
    override val name = "Magisk service.d"
    override val startupPath = "/data/adb/service.d/90-netpilot.sh"
    override val moduleBased = false
}
class KernelSUAdapter : RootBootAdapter {
    override val name = "KernelSU 模块 service.sh"
    override val startupPath = "/data/adb/modules/netpilot-guardian/service.sh"
    override val moduleBased = true
}
class APatchAdapter : RootBootAdapter {
    override val name = "APatch 模块 service.sh"
    override val startupPath = "/data/adb/modules/netpilot-guardian/service.sh"
    override val moduleBased = true
}
class OtherRootAdapter : RootBootAdapter {
    override val name = "手动启动 / Android 开机广播（尽力恢复）"
    override val startupPath: String? = null
    override val moduleBased = false
}
fun bootAdapter(provider: RootProvider): RootBootAdapter = when (provider) {
    RootProvider.MAGISK -> MagiskAdapter(); RootProvider.KERNEL_SU -> KernelSUAdapter(); RootProvider.APATCH -> APatchAdapter(); else -> OtherRootAdapter()
}
