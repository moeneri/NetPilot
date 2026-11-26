package dev.netpilot.service.ScheduleEngine

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import dev.netpilot.compat.android.RootBroadcastCompat
import dev.netpilot.core.model.AlarmMode
import dev.netpilot.data.logs.RuntimeLogger

class RootScheduleEngine(private val context: Context, private val logger: RuntimeLogger, private val onAlarm: () -> Unit) : AutoCloseable {
    private val alarm = context.getSystemService(AlarmManager::class.java)
    private val action = "dev.netpilot.ROOT_SCHEDULE_ALARM"
    private val pending = PendingIntent.getBroadcast(context, 90, Intent(action).setPackage(context.packageName), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private val receiver = object : BroadcastReceiver() { override fun onReceive(context: Context?, intent: Intent?) { onAlarm() } }
    private var nextAt: Long? = null
    var mode: AlarmMode = AlarmMode.UNKNOWN; private set
    private val registration = RootBroadcastCompat.register(context, receiver, IntentFilter().apply {
        addAction(action); addAction(Intent.ACTION_TIME_CHANGED); addAction(Intent.ACTION_TIMEZONE_CHANGED); addAction(Intent.ACTION_DATE_CHANGED); addAction(Intent.ACTION_USER_UNLOCKED)
    }, exported = false)
    fun arm(timestamp: Long?, force: Boolean = false) {
        if (timestamp == nextAt && !force) return
        alarm.cancel(pending)
        nextAt = timestamp
        if (timestamp == null) return
        try { alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, timestamp, pending); mode = AlarmMode.ROOT_EXACT }
        catch (error: Exception) {
            logger.warn("schedule", "Root 精确闹钟不可用：${error.message}；启用系统非精确闹钟与进程计时降级")
            try { alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, timestamp, pending); mode = AlarmMode.INEXACT }
            catch (fallback: Exception) { mode = AlarmMode.TIMER_ONLY; logger.error("schedule", "系统闹钟不可用：${fallback.message}") }
        }
    }
    override fun close() { runCatching { alarm.cancel(pending) }; registration.close() }
}
