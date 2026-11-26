package dev.netpilot.compat.hotspot

import android.content.Context
import android.net.wifi.SoftApConfiguration

/** Changes only the idle shutdown flag on the system's tethered Soft AP configuration. */
internal class HotspotIdleShutdown(private val context: Context) {
    fun setEnabled(enabled: Boolean, beforeWrite: () -> Unit = {}): Boolean {
        val wifi = checkNotNull(context.getSystemService(Context.WIFI_SERVICE)) { "Wi-Fi 服务不可用" }
        // These system APIs are used by the Root Supervisor, including on API 30.
        val read = wifi.javaClass.getMethod("getSoftApConfiguration")
        val current = checkNotNull(read.invoke(wifi) as? SoftApConfiguration) { "无法读取系统热点配置" }
        val isEnabled = SoftApConfiguration::class.java.getMethod("isAutoShutdownEnabled")
        if (isEnabled.invoke(current) == enabled) return false

        val builderClass = Class.forName("android.net.wifi.SoftApConfiguration\$Builder")
        val builder = builderClass.getConstructor(SoftApConfiguration::class.java).newInstance(current)
        builderClass.getMethod("setAutoShutdownEnabled", Boolean::class.javaPrimitiveType).invoke(builder, enabled)
        val updated = builderClass.getMethod("build").invoke(builder)
        val write = wifi.javaClass.getMethod("setSoftApConfiguration", SoftApConfiguration::class.java)
        beforeWrite()
        check(write.invoke(wifi, updated) == true) { "系统拒绝更新热点空闲关闭设置" }
        val confirmed = read.invoke(wifi)
        check(confirmed != null && isEnabled.invoke(confirmed) == enabled) { "系统未确认热点空闲关闭设置" }
        return true
    }
}
