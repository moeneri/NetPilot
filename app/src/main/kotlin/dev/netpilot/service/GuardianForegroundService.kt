package dev.netpilot.service

import dev.netpilot.R
import dev.netpilot.i18n.*
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dev.netpilot.MainActivity
import dev.netpilot.NetPilotApplication
import dev.netpilot.core.model.TetherPhase
import dev.netpilot.data.repository.AppUiState
import dev.netpilot.receiver.NotificationActionReceiver
import kotlinx.coroutines.*

class GuardianForegroundService : Service() {
    private val strings get() = AppLocale.strings(this)
    companion object {
        private const val CHANNEL = "netpilot-guardian"
        private const val NOTIFICATION = 90
        @Volatile var running = false; private set
        fun start(context: Context, action: String? = null) { androidx.core.content.ContextCompat.startForegroundService(context, Intent(context, GuardianForegroundService::class.java).setAction(action)) }
        fun stop(context: Context) { context.stopService(Intent(context, GuardianForegroundService::class.java)) }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, strings(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW))
        val initial = notification(strings(R.string.notification_connecting), false)
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION, initial, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(NOTIFICATION, initial)
        running = true
        val repository = (application as NetPilotApplication).repository
        scope.launch {
            repository.state.collect { state ->
                if (!state.config.guardian.installed || !state.config.guardian.foregroundEnabled) stopSelf()
                else updateNotification(state)
            }
        }
        scope.launch {
            while (isActive) {
                repository.refresh(); repository.updatePermissions()
                val state = repository.state.value
                if (!state.config.guardian.installed || !state.config.guardian.foregroundEnabled) { stopSelf(); break }
                delay(30_000)
            }
        }
    }
    private fun updateNotification(state: AppUiState) {
        val strings = AppLocale.strings(this, state.language)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, strings(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW))
        val summary = strings(when {
            state.config.guardian.paused -> R.string.automation_paused
            !state.connected -> R.string.notification_disconnected
            state.runtime.usb.phase == TetherPhase.READY || state.runtime.wifi.phase == TetherPhase.READY -> R.string.notification_ready
            else -> R.string.notification_waiting
        })
        manager.notify(NOTIFICATION, notification(summary, state.config.guardian.paused, strings))
    }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateNotification((application as NetPilotApplication).repository.state.value)
    }
    private fun notification(summary: String, paused: Boolean, strings: LocalizedStrings = this.strings): android.app.Notification {
        val open = PendingIntent.getActivity(this, 90, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val action = PendingIntent.getBroadcast(this, 92, Intent(this, NotificationActionReceiver::class.java).setAction(if (paused) "dev.netpilot.RESUME" else "dev.netpilot.PAUSE"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_netpilot).setContentTitle(strings(R.string.notification_title))
            .setContentText(summary).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(0, if (paused) strings(R.string.resume_automation) else strings(R.string.pause_automation), action).build()
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action in setOf("dev.netpilot.PAUSE", "dev.netpilot.RESUME")) scope.launch {
            val repository = (application as NetPilotApplication).repository
            try { repository.setAutomationPaused(intent?.action == "dev.netpilot.PAUSE") }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { repository.recordActionFailure(strings(R.string.notification_action), error); repository.message(strings(R.string.notification_action_failed, strings.runtime(error.message ?: strings(R.string.action_failed)))) }
        }
        return START_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { running = false; scope.cancel(); super.onDestroy() }
}
