package dev.netpilot.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ScheduleAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) { dispatchRecovery(context, "RECALCULATE") }
}
