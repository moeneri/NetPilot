package dev.netpilot.compat.tethering

import dev.netpilot.core.model.AppConfig
import dev.netpilot.core.root.RootCommandExecutor
import android.content.Context
import android.os.UserManager

/** Only explicit, user-configured native service commands; no hard-coded Binder transactions. */
class OemTetheringAdapter(private val context: Context, private val config: AppConfig, private val executor: RootCommandExecutor) {
    fun start(type: Int) = run(if (type == TYPE_USB) config.oem.usbStartCommand else config.oem.wifiStartCommand)
    fun stop(type: Int) = run(if (type == TYPE_USB) config.oem.usbStopCommand else config.oem.wifiStopCommand)
    private fun run(command: String): TetherOperation {
        if (!context.getSystemService(UserManager::class.java).isUserUnlocked) return TetherOperation(false, "USER_LOCKED", "厂商命令在用户首次解锁后才执行")
        if (!config.oem.enabled || command.isBlank()) return TetherOperation(false, "OEM_NOT_CONFIGURED", "厂商原生共享适配器尚未配置")
        val result = executor.executeScript(command, 15)
        return if (result.success) TetherOperation(true, detail = "厂商命令已执行；以系统共享回调为准") else TetherOperation(false, "OEM_COMMAND_FAILED", result.output.ifBlank { "厂商命令执行失败" }, result.timedOut)
    }
}
