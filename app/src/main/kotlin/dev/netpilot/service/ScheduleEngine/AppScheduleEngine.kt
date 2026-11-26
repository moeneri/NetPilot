package dev.netpilot.service.ScheduleEngine

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import dev.netpilot.core.model.*
import dev.netpilot.core.schedule.ScheduleCalculator
import dev.netpilot.receiver.ScheduleAlarmReceiver
import java.time.Instant
import java.time.ZoneId

/** Public SDK fallback. The independent root engine also owns its own system alarm. */
class AppScheduleEngine(private val context: Context) {
    private val alarm = context.getSystemService(AlarmManager::class.java)
    private val pending = PendingIntent.getBroadcast(context, 91, Intent(context, ScheduleAlarmReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun cancel() { alarm.cancel(pending) }
    fun reschedule(config: AppConfig, schedule: ScheduleDocument): AlarmMode {
        cancel()
        if (!config.guardian.installed || config.guardian.paused || config.wifiMode != WifiMode.AUTO) return AlarmMode.UNKNOWN
        val next = ScheduleCalculator.evaluate(schedule.slots, Instant.now(), ZoneId.systemDefault()).next ?: return AlarmMode.UNKNOWN
        return try {
            if (Build.VERSION.SDK_INT < 31 || alarm.canScheduleExactAlarms()) { alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.instant.toEpochMilli(), pending); AlarmMode.APP_EXACT }
            else { alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.instant.toEpochMilli(), pending); AlarmMode.INEXACT }
        } catch (_: Exception) { AlarmMode.TIMER_ONLY }
    }
}
