package dev.netpilot.receiver

import android.content.BroadcastReceiver
import android.content.Context
import dev.netpilot.NetPilotApplication
import dev.netpilot.core.root.RootCommandExecutor
import dev.netpilot.data.repository.AtomicJsonStore
import dev.netpilot.service.GuardianForegroundService
import dev.netpilot.service.RootSupervisor.LocalControlClient
import dev.netpilot.service.ScheduleEngine.AppScheduleEngine
import kotlinx.coroutines.launch
import java.io.File

fun BroadcastReceiver.dispatchRecovery(context: Context, command: String, boot: Boolean = false) {
    val protectedContext = context.createDeviceProtectedStorageContext()
    val store = AtomicJsonStore(File(protectedContext.filesDir, "netpilot"))
    val config = store.config()
    if (!config.guardian.installed || config.guardian.paused || (boot && !config.guardian.bootEnabled)) return
    val pending = goAsync()
    (context.applicationContext as NetPilotApplication).applicationScope.launch {
        try {
            AppScheduleEngine(protectedContext).reschedule(config, store.schedule())
            val online = runCatching { LocalControlClient().request(command, timeoutMillis = 4_000).ok }.getOrDefault(false)
            if (!online && boot) {
                val result = RootCommandExecutor().execute(listOf("/system/bin/sh", "/data/adb/netpilot/start.sh", "--boot"), 4)
                if (!result.success) (context.applicationContext as NetPilotApplication).repository.recordActionFailure("开机启动 Root 守护", IllegalStateException("exit=${result.exitCode} timeout=${result.timedOut} ${result.output}"))
            }
            if (config.guardian.foregroundEnabled) runCatching { GuardianForegroundService.start(context) }
        } finally { pending.finish() }
    }
}
