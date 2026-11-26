package dev.netpilot.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.netpilot.NetPilotApplication
import dev.netpilot.service.GuardianForegroundService

class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf("dev.netpilot.PAUSE", "dev.netpilot.RESUME")) return
        try { GuardianForegroundService.start(context, intent.action) }
        catch (error: Exception) { (context.applicationContext as NetPilotApplication).repository.message("系统未允许处理通知操作：${error.message}") }
    }
}
