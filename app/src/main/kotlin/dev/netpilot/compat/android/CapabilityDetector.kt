package dev.netpilot.compat.android

import android.os.Build
import dev.netpilot.core.model.DeviceCapabilities
import dev.netpilot.core.model.RootProvider
import java.io.File

object CapabilityDetector {
    fun detect(root: Boolean, provider: RootProvider): DeviceCapabilities {
        val type = runCatching { Class.forName("android.net.TetheringManager") }.getOrNull()
        return DeviceCapabilities(
            sdk = Build.VERSION.SDK_INT, androidRelease = Build.VERSION.RELEASE,
            manufacturer = Build.MANUFACTURER, model = Build.MODEL,
            rootGranted = root, rootProvider = provider,
            tetheringApi = type?.methods?.any { it.name == "startTethering" } == true,
            tetheringEvents = type?.methods?.any { it.name == "registerTetheringEventCallback" } == true,
            tetheringRequest = runCatching { Class.forName("android.net.TetheringManager\$TetheringRequest\$Builder") }.isSuccess,
            usbSignalPaths = signalPaths(), usbRolePaths = rolePaths(),
            notes = listOf("接口存在性已检测；系统权限和 ROM 行为须以运行状态为准", "USB 与 Wi-Fi 并行能力由实际系统回调判断"),
        )
    }

    fun signalPaths(): List<String> = buildList {
        File("/sys/class/android_usb/android0/state").takeIf { it.exists() }?.let { add(it.path) }
        File("/sys/class/udc").listFiles()?.forEach { dir -> File(dir, "state").takeIf { it.exists() }?.let { add(it.path) } }
    }
    fun rolePaths(): List<String> = buildList {
        File("/sys/class/typec").listFiles()?.filter { it.name.matches(Regex("port\\d+")) }?.forEach { dir ->
            File(dir, "data_role").takeIf { it.exists() }?.let { add(it.path) }
        }
        File("/sys/class/dual_role_usb").listFiles()?.forEach { dir ->
            File(dir, "data_role").takeIf { it.exists() }?.let { add(it.path) }
        }
    }
}
