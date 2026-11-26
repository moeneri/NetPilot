package dev.netpilot.ui.guardian

import dev.netpilot.R
import dev.netpilot.i18n.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.netpilot.core.model.*
import dev.netpilot.data.repository.AppUiState
import dev.netpilot.ui.*
import dev.netpilot.ui.components.*

@Composable fun GuardianScreen(state: AppUiState, actions: UiActions) {
    val strings = rememberLocalizedStrings()
    var confirmStop by remember { mutableStateOf(false) }
    var confirmUninstall by remember { mutableStateOf(false) }
    var confirmFullUninstall by remember { mutableStateOf(false) }
    val supervisor = state.runtime.supervisor
    val installed = state.config.guardian.installed
    PageHeading("Root Guardian", strings(R.string.guardian_subtitle))
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            IconTile(Icons.Outlined.Shield, Modifier.size(48.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(if (state.connected) strings(R.string.guardian_connected) else if (installed) strings(R.string.guardian_channel_disconnected) else strings(R.string.guardian_waiting_install), style = MaterialTheme.typography.titleLarge)
                Text(if (state.connected) strings(R.string.guardian_independent_hint) else strings(R.string.guardian_provider_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        HealthBadge(if (state.connected) if (state.config.guardian.paused) strings(R.string.automation_paused) else strings(R.string.running) else strings(R.string.waiting_connection), if (state.connected) if (state.config.guardian.paused) Health.WAITING else Health.OK else Health.UNKNOWN)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        InfoLine("Root Provider", strings.providerText(state.config.rootProvider))
        InfoLine(strings(R.string.boot_start), if (installed) if (state.config.guardian.bootEnabled) strings(R.string.boot_enabled_detail, strings.runtime(state.runtime.capabilities.bootAdapter)) else strings(R.string.tether_off) else strings(R.string.after_install))
        InfoLine(strings(R.string.last_start), if (state.connected) strings.dateTimeText(supervisor.startedAt) else strings(R.string.waiting_start))
        InfoLine(strings(R.string.schedule_wakeup), if (!state.connected) strings(R.string.waiting_runtime) else when (supervisor.alarmMode) { AlarmMode.ROOT_EXACT -> strings(R.string.alarm_root); AlarmMode.APP_EXACT -> strings(R.string.alarm_app); AlarmMode.INEXACT -> strings(R.string.alarm_inexact); AlarmMode.TIMER_ONLY -> strings(R.string.alarm_timer); else -> strings(R.string.waiting_runtime) })
        if (!installed) Button(actions.install, Modifier.fillMaxWidth(), enabled = state.busy == null) { Icon(Icons.Outlined.Security, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(strings(R.string.install_guardian)) }
    }
    if (installed) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilledTonalButton(actions.start, Modifier.weight(1f), enabled = !state.connected && state.busy == null, contentPadding = PaddingValues(horizontal = 8.dp)) { Icon(Icons.Outlined.PlayArrow, null, Modifier.size(17.dp)); Spacer(Modifier.width(4.dp)); Text(strings(R.string.start)) }
            OutlinedButton(actions.restart, Modifier.weight(1f), enabled = state.busy == null, contentPadding = PaddingValues(horizontal = 8.dp)) { Icon(Icons.Outlined.Refresh, null, Modifier.size(17.dp)); Spacer(Modifier.width(4.dp)); Text(strings(R.string.restart)) }
            OutlinedButton({ confirmStop = true }, Modifier.weight(1f), enabled = state.busy == null, contentPadding = PaddingValues(horizontal = 8.dp)) { Icon(Icons.Outlined.PowerSettingsNew, null, Modifier.size(17.dp)); Spacer(Modifier.width(4.dp)); Text(strings(R.string.stop)) }
        }
        Button(actions.pause, Modifier.fillMaxWidth(), enabled = state.busy == null, colors = ButtonDefaults.buttonColors(containerColor = if (state.config.guardian.paused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer, contentColor = if (state.config.guardian.paused) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary)) {
            Icon(if (state.config.guardian.paused) Icons.Outlined.PlayArrow else Icons.Outlined.PauseCircleOutline, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(if (state.config.guardian.paused) strings(R.string.resume_automation) else strings(R.string.pause_automation))
        }
    }
    Panel {
        SectionLabel(strings(R.string.background_requirements))
        InfoLine(strings(R.string.foreground_service), if (state.permissions.foregroundRunning) strings(R.string.service_running) else strings(R.string.service_stopped))
        InfoLine(strings(R.string.persistent_notification), if (state.permissions.notificationsAllowed) strings(R.string.notification_allowed) else strings(R.string.not_allowed_yet))
        InfoLine(strings(R.string.battery_doze), if (state.permissions.batteryExempt) strings(R.string.battery_exempt) else strings(R.string.battery_not_exempt))
        InfoLine(strings(R.string.alarm_app), if (state.permissions.exactAlarm) strings(R.string.allowed) else strings(R.string.alarm_not_allowed))
        SettingLink(Icons.Outlined.BatterySaver, strings(R.string.background_permissions), strings(R.string.background_permissions_hint), onClick = { actions.permissions(PermissionTarget.BATTERY) })
        if (state.connected && !state.permissions.batteryExempt) OutlinedButton(actions.doze, Modifier.fillMaxWidth(), enabled = state.busy == null) { Text(strings(R.string.add_doze)) }
    }
    val error = supervisor.lastError
    if (error != null) {
        Notice(strings.runtime(error), warning = true)
        OutlinedButton(actions.retry, Modifier.fillMaxWidth(), enabled = state.connected && state.busy == null) { Icon(Icons.Outlined.Refresh, null, Modifier.size(17.dp)); Spacer(Modifier.width(8.dp)); Text(strings(R.string.retry_recovery)) }
    }
    Panel {
        SectionLabel(strings(R.string.logs_diagnostics))
        SettingLink(Icons.Outlined.Description, strings(R.string.logs_title), strings(R.string.logs_description), onClick = actions.logs)
        SettingLink(Icons.Outlined.Troubleshoot, strings(R.string.generate_diagnostics), strings(R.string.diagnostics_description), onClick = actions.diagnose)
        SettingLink(Icons.Outlined.FileDownload, strings(R.string.export_logs), strings(R.string.save_text_file), onClick = { actions.export(ExportKind.LOGS) })
    }
    Notice(strings(R.string.guardian_background_notice))
    if (installed) TextButton({ confirmUninstall = true }, Modifier.fillMaxWidth(), enabled = state.busy == null) { Text(strings(R.string.remove_guardian), color = MaterialTheme.colorScheme.error) }
    TextButton({ confirmFullUninstall = true }, Modifier.fillMaxWidth(), enabled = state.busy == null) { Text(strings(R.string.uninstall_app), color = MaterialTheme.colorScheme.onSurfaceVariant) }
    if (confirmStop) AlertDialog(onDismissRequest = { confirmStop = false }, title = { Text(strings(R.string.stop_guardian_question)) }, text = { Text(strings(R.string.stop_guardian_description)) }, confirmButton = { TextButton({ confirmStop = false; actions.stop() }) { Text(strings(R.string.stop_restore)) } }, dismissButton = { TextButton({ confirmStop = false }) { Text(strings(R.string.cancel)) } })
    if (confirmUninstall) AlertDialog(onDismissRequest = { confirmUninstall = false }, title = { Text(strings(R.string.remove_guardian_question)) }, text = { Text(strings(R.string.remove_guardian_description)) }, confirmButton = { TextButton({ confirmUninstall = false; actions.uninstall() }) { Text(strings(R.string.remove_restore), color = MaterialTheme.colorScheme.error) } }, dismissButton = { TextButton({ confirmUninstall = false }) { Text(strings(R.string.cancel)) } })
    if (confirmFullUninstall) AlertDialog(onDismissRequest = { confirmFullUninstall = false }, title = { Text(strings(R.string.uninstall_question)) }, text = { Text(strings(R.string.uninstall_description)) }, confirmButton = { TextButton({ confirmFullUninstall = false; actions.uninstallApp() }) { Text(strings(R.string.restore_uninstall), color = MaterialTheme.colorScheme.error) } }, dismissButton = { TextButton({ confirmFullUninstall = false }) { Text(strings(R.string.cancel)) } })
}
