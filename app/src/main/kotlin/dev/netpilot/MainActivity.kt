package dev.netpilot

import dev.netpilot.R
import dev.netpilot.i18n.*
import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.core.view.WindowCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.compose.foundation.isSystemInDarkTheme
import dev.netpilot.core.model.*
import dev.netpilot.core.schedule.ScheduleCalculator
import dev.netpilot.ui.*
import dev.netpilot.ui.components.Notice
import dev.netpilot.ui.theme.NetPilotTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    private val model: NetPilotViewModel by viewModels()
    private val strings get() = AppLocale.strings(this)
    private var pendingExport = ExportKind.CONFIG
    private var restorePreview by mutableStateOf<Pair<String, BackupDocument>?>(null)
    private val notifyPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { model.repository.updatePermissions() }
    private val createDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) lifecycleScope.launch {
            try {
                val text = when (pendingExport) { ExportKind.CONFIG -> model.repository.backup(); ExportKind.LOGS -> model.repository.exportLogs(); ExportKind.DIAGNOSTICS -> model.state.value.diagnosticText ?: model.repository.diagnostics() }
                withContext(Dispatchers.IO) { contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) } ?: error(strings(R.string.export_write_error)) }
                model.report(strings(R.string.file_exported))
            } catch (error: Exception) { model.repository.recordActionFailure(strings(R.string.export_file_action), error); model.report(strings(R.string.export_failed, strings.runtime(error.message ?: strings(R.string.action_failed)))) }
        }
    }
    private val openDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) lifecycleScope.launch {
            try {
                val text = withContext(Dispatchers.IO) { contentResolver.openInputStream(uri)?.use { stream ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(4096)
                    while (true) { val count = stream.read(buffer); if (count < 0) break; require(output.size() + count <= 128 * 1024) { strings(R.string.backup_too_large) }; output.write(buffer, 0, count) }
                    output.toString(Charsets.UTF_8.name())
                } ?: error(strings(R.string.export_read_error)) }
                val document = NetPilotJson.decodeFromString<BackupDocument>(text)
                require(document.format == "netpilot-backup" && document.version == 1) { strings(R.string.backup_invalid) }
                document.config.validate()?.let { error(it) }; ScheduleCalculator.validate(document.schedule)?.let { error(it) }
                restorePreview = text to document
            } catch (error: Exception) { model.repository.recordActionFailure(strings(R.string.read_backup_action), error); model.report(strings(R.string.backup_read_failed, strings.runtime(error.message ?: strings(R.string.action_failed)))) }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pendingExport = savedInstanceState?.getString("pendingExport")?.let { runCatching { ExportKind.valueOf(it) }.getOrNull() } ?: ExportKind.CONFIG
        setContent {
            val state by model.state.collectAsStateWithLifecycle()
            val strings = rememberLocalizedStrings(state.language)
            val dark = when (state.config.theme) { ThemeMode.DARK -> true; ThemeMode.LIGHT -> false; ThemeMode.SYSTEM -> isSystemInDarkTheme() }
            SideEffect { WindowCompat.getInsetsController(window, window.decorView).apply { isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark } }
            val actions = UiActions(
                language = model::language,
                config = model::config, saveSlot = model::save, deleteSlot = model::delete, duplicateSlot = model::duplicate,
                install = model::install, start = model::start, stop = model::stop, restart = model::restart,
                pause = model::pause, retry = model::retry, uninstall = model::uninstall, logs = model::logs, clearLogs = model::clearLogs,
                uninstallApp = ::uninstallCompletely,
                diagnose = model::diagnose, doze = model::doze, export = ::export, importConfig = { openDocument.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                reset = model::reset, permissions = ::openPermission, dismissMessage = model::dismissMessage, dismissDiagnostics = model::dismissDiagnostics,
            )
            NetPilotApp(state, actions)
            LaunchedEffect(state.connected) {
                if (state.connected) {
                    val preferences = createDeviceProtectedStorageContext().getSharedPreferences("onboarding", MODE_PRIVATE)
                    if (!preferences.getBoolean("notificationsPrompted", false)) { preferences.edit { putBoolean("notificationsPrompted", true) }; requestNotificationsIfNeeded() }
                }
            }
            restorePreview?.let { (text, backup) -> ProvideAppLanguage(state.language) { NetPilotTheme(state.config.theme) {
                AlertDialog(onDismissRequest = { restorePreview = null }, title = { Text(strings(R.string.restore_question)) }, text = {
                    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(strings(R.string.restore_summary, backup.schedule.slots.size, strings(if (backup.config.usb.enabled) R.string.turn_on else R.string.turn_off)))
                        if (backup.config.oem.enabled) {
                            Notice(strings(R.string.restore_oem_warning), true)
                            Text(listOf(backup.config.oem.usbStartCommand, backup.config.oem.usbStopCommand, backup.config.oem.wifiStartCommand, backup.config.oem.wifiStopCommand).joinToString("\n"), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }, confirmButton = { TextButton({ restorePreview = null; model.restore(text) }) { Text(strings(R.string.restore_configuration)) } }, dismissButton = { TextButton({ restorePreview = null }) { Text(strings(R.string.cancel)) } })
            } } }
        }
    }
    private fun export(kind: ExportKind) {
        pendingExport = kind
        val date = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"))
        createDocument.launch(when (kind) { ExportKind.CONFIG -> "NetPilot-config-$date.json"; ExportKind.LOGS -> "NetPilot-logs-$date.txt"; ExportKind.DIAGNOSTICS -> "NetPilot-diagnostics-$date.txt" })
    }
    private fun requestNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    private fun uninstallCompletely() {
        if (model.state.value.busy != null) return
        lifecycleScope.launch {
            model.repository.busy(strings(R.string.uninstall_recovery_action))
            try {
                if (model.state.value.config.guardian.installed) model.repository.uninstallGuardian()
                dev.netpilot.service.GuardianForegroundService.stop(this@MainActivity)
                startActivity(Intent(Intent.ACTION_DELETE, "package:$packageName".toUri()))
            } catch (error: Exception) { model.repository.recordActionFailure(strings(R.string.full_uninstall_action), error); model.report(strings(R.string.uninstall_incomplete, strings.runtime(error.message ?: strings(R.string.action_failed)))) }
            finally { model.repository.busy(null) }
        }
    }
    private fun openPermission(target: PermissionTarget) {
        val packageUri = "package:$packageName".toUri()
        val intent = when (target) {
            PermissionTarget.ROOT -> { model.install(); return }
            PermissionTarget.EXACT_ALARM -> if (Build.VERSION.SDK_INT >= 31) Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, packageUri) else { model.report(strings(R.string.android11_alarm_hint)); return }
            PermissionTarget.BATTERY -> Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            PermissionTarget.NOTIFICATIONS -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            PermissionTarget.APP_DETAILS -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)
            PermissionTarget.USB_SETTINGS -> Intent("android.settings.USB_SETTINGS")
        }
        try { startActivity(intent) } catch (_: Exception) { runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)) }.onFailure { model.report(strings(R.string.settings_unavailable)) } }
    }
    override fun onStart() { super.onStart(); model.visible(true) }
    override fun onStop() { model.visible(false); super.onStop() }
    override fun onSaveInstanceState(outState: Bundle) { outState.putString("pendingExport", pendingExport.name); super.onSaveInstanceState(outState) }
}
