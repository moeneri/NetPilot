package dev.netpilot.compat.root

import dev.netpilot.core.model.RootProvider
import dev.netpilot.core.root.RootCommandExecutor

class RootProviderDetector(private val executor: RootCommandExecutor) {
    fun requestAccess(): RootDetection {
        val identity = executor.execute(listOf("id", "-u"), 45)
        if (!identity.success || identity.output.trim() != "0") return RootDetection(false, RootProvider.NONE, identity.output.ifBlank { "未获得 Root 授权" })
        val probe = executor.executeScript("""
            if command -v magisk >/dev/null 2>&1; then echo MAGISK; magisk -v
            elif [ -d /data/adb/ksu ] || command -v ksud >/dev/null 2>&1; then echo KERNEL_SU
            elif [ -d /data/adb/ap ] || [ -d /data/adb/apatch ] || command -v apd >/dev/null 2>&1; then echo APATCH
            else echo OTHER; fi
        """.trimIndent())
        val provider = when (probe.output.lineSequence().firstOrNull()) {
            "MAGISK" -> RootProvider.MAGISK; "KERNEL_SU" -> RootProvider.KERNEL_SU; "APATCH" -> RootProvider.APATCH; else -> RootProvider.OTHER
        }
        return RootDetection(true, provider, probe.output)
    }
}

data class RootDetection(val granted: Boolean, val provider: RootProvider, val detail: String)
