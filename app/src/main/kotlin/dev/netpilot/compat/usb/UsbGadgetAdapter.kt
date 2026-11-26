package dev.netpilot.compat.usb

import android.content.Context
import android.provider.Settings
import dev.netpilot.core.root.CommandResult
import dev.netpilot.core.root.RootCommandExecutor

class UsbGadgetAdapter(private val context: Context, private val executor: RootCommandExecutor) {
    fun currentFunctions(): String = systemProperty("sys.usb.config").ifBlank { systemProperty("sys.usb.state") }
    fun isRndis(): Boolean = listOf(currentFunctions(), systemProperty("sys.usb.state")).any { "rndis" in it.split(',') }
    fun preserveAdb(original: String): CommandResult {
        val adbEnabled = runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1 }.getOrDefault(false)
        if (!adbEnabled || "adb" !in original.split(',') || "adb" in currentFunctions().split(',')) return CommandResult(0, "ADB 功能无需调整")
        val functions = currentFunctions().split(',').filter { it.isNotBlank() && it != "none" }.toMutableSet().apply { add("adb") }
        return setFunctions(functions.joinToString(","))
    }
    fun restore(original: String): CommandResult {
        if (!original.matches(Regex("[a-zA-Z0-9_,.-]{0,128}"))) return CommandResult(-1, "原 USB 功能配置无效，拒绝恢复")
        val adbEnabled = runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1 }.getOrDefault(false)
        val functions = original.split(',').filter { it.isNotBlank() && it != "adb" && it != "none" }.toMutableSet()
        if (adbEnabled) functions.add("adb")
        val target = functions.joinToString(",").ifBlank { "none" }
        val result = setFunctions(target)
        if (!result.success) return result
        val expected = normalize(target)
        val deadline = android.os.SystemClock.elapsedRealtime() + 5_000
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            val configMatches = normalize(currentFunctions()) == expected
            val state = systemProperty("sys.usb.state")
            if (configMatches && (state.isBlank() || normalize(state) == expected)) return result
            Thread.sleep(200)
        }
        return CommandResult(-1, "USB 恢复请求已执行，但系统未确认功能配置：${currentFunctions()} / ${systemProperty("sys.usb.state")}")
    }
    private fun setFunctions(functions: String) = executor.execute(listOf("svc", "usb", "setFunctions", functions), 10)
    private fun normalize(value: String) = value.split(',').filter { it.isNotBlank() && it != "none" }.toSet()
}
