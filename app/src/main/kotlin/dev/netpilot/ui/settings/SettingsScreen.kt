package dev.netpilot.ui.settings

import dev.netpilot.R
import dev.netpilot.i18n.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import dev.netpilot.BuildConfig
import dev.netpilot.core.model.*
import dev.netpilot.data.repository.AppUiState
import dev.netpilot.ui.*
import dev.netpilot.ui.components.*

private const val NETPILOT_REPOSITORY_URL = "https://github.com/moeneri/NetPilot"

@Composable fun SettingsScreen(state: AppUiState, actions: UiActions, navigate: (Screen) -> Unit) {
    val strings = rememberLocalizedStrings()
    var reset by remember { mutableStateOf(false) }
    var advanced by remember { mutableStateOf(false) }
    PageHeading(strings(R.string.nav_settings), strings(R.string.settings_subtitle))
    Panel {
        SectionLabel(strings(R.string.language_title))
        ChoiceChips(AppLanguage.entries, state.language, {
            strings(when (it) {
                AppLanguage.SYSTEM -> R.string.follow_system
                AppLanguage.CHINESE -> R.string.language_chinese
                AppLanguage.ENGLISH -> R.string.language_english
            })
        }, actions.language)
        Text(strings(R.string.language_description), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(strings(R.string.language_active, strings(if (strings.locale.language == "zh") R.string.language_chinese else R.string.language_english)), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
    }
    Panel {
        SectionLabel(strings(R.string.running_permissions))
        SwitchSetting(strings(R.string.boot_start), strings(R.string.boot_start_hint), state.config.guardian.bootEnabled, { actions.config(state.config.copy(guardian = state.config.guardian.copy(bootEnabled = it))) }, state.busy == null)
        SwitchSetting(strings(R.string.foreground_status), strings(R.string.foreground_status_hint), state.config.guardian.foregroundEnabled, { actions.config(state.config.copy(guardian = state.config.guardian.copy(foregroundEnabled = it))) }, state.busy == null)
        SwitchSetting(strings(R.string.auto_recovery), strings(R.string.auto_recovery_hint), state.config.guardian.restoreOnFailure, { actions.config(state.config.copy(guardian = state.config.guardian.copy(restoreOnFailure = it))) }, state.busy == null)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        SettingLink(Icons.Outlined.Security, strings(R.string.root_permission), strings(R.string.root_permission_hint), strings.providerText(state.config.rootProvider), actions.install)
        SettingLink(Icons.Outlined.BatterySaver, strings(R.string.battery_optimization), strings(R.string.battery_optimization_hint), if (state.permissions.batteryExempt) strings(R.string.battery_exempt) else strings(R.string.pending_setting)) { actions.permissions(PermissionTarget.BATTERY) }
        SettingLink(Icons.Outlined.Alarm, strings(R.string.exact_alarm), strings(R.string.exact_alarm_hint), if (state.permissions.exactAlarm) strings(R.string.allowed) else strings(R.string.pending_setting)) { actions.permissions(PermissionTarget.EXACT_ALARM) }
        SettingLink(Icons.Outlined.Notifications, strings(R.string.notification_permission), strings(R.string.notification_permission_hint), if (state.permissions.notificationsAllowed) strings(R.string.allowed) else strings(R.string.pending_setting)) { actions.permissions(PermissionTarget.NOTIFICATIONS) }
        SettingLink(Icons.Outlined.SettingsApplications, strings(R.string.autostart_permissions), strings(R.string.autostart_permissions_hint), onClick = { actions.permissions(PermissionTarget.APP_DETAILS) })
    }
    Panel {
        SectionLabel(strings(R.string.sharing_configuration))
        SettingLink(Icons.Outlined.Usb, strings(R.string.usb_mode_debounce), strings(R.string.usb_debounce_summary, "%.1f".format(strings.locale, state.config.usb.debounceMillis / 1000f)), onClick = { navigate(Screen.USB) })
        SettingLink(Icons.Outlined.Schedule, strings(R.string.schedule_title), strings(R.string.schedule_summary, state.schedule.slots.size, strings.modeText(state.config.wifiMode)), onClick = { navigate(Screen.SCHEDULE) })
        Text(strings(R.string.sharing_priority), style = MaterialTheme.typography.titleSmall)
        ChoiceChips(SharingPriority.entries, state.config.priority, strings::priorityText) { actions.config(state.config.copy(priority = it)) }
        SettingLink(Icons.Outlined.Tune, strings(R.string.rom_compatibility), strings(R.string.rom_compatibility_hint), onClick = { advanced = true })
    }
    Panel {
        SectionLabel(strings(R.string.display_logs))
        ChoiceChips(ThemeMode.entries, state.config.theme, { when (it) { ThemeMode.SYSTEM -> strings(R.string.follow_system); ThemeMode.LIGHT -> strings(R.string.theme_light); ThemeMode.DARK -> strings(R.string.theme_dark) } }) { actions.config(state.config.copy(theme = it)) }
        Text(strings(R.string.log_level), style = MaterialTheme.typography.titleSmall)
        ChoiceChips(LogLevel.entries, state.config.logLevel, { when (it) { LogLevel.DEBUG -> strings(R.string.log_debug); LogLevel.INFO -> strings(R.string.log_info); LogLevel.WARN -> strings(R.string.log_warn); LogLevel.ERROR -> strings(R.string.log_error) } }) { actions.config(state.config.copy(logLevel = it)) }
    }
    Panel {
        SectionLabel(strings(R.string.config_backup))
        SettingLink(Icons.Outlined.FileDownload, strings(R.string.export_config), strings(R.string.export_config_hint), onClick = { actions.export(ExportKind.CONFIG) })
        SettingLink(Icons.Outlined.FileUpload, strings(R.string.restore_config), strings(R.string.restore_config_hint), onClick = actions.importConfig)
        SettingLink(Icons.Outlined.RestartAlt, strings(R.string.reset_settings), strings(R.string.reset_settings_hint), onClick = { reset = true })
    }
    Panel {
        val uriHandler = LocalUriHandler.current
        var repositoryLinkFailed by remember { mutableStateOf(false) }
        SectionLabel(strings(R.string.about_netpilot), BuildConfig.VERSION_NAME)
        Text(strings(R.string.about_description), style = MaterialTheme.typography.titleSmall)
        SettingLink(Icons.AutoMirrored.Outlined.OpenInNew, strings(R.string.about_repository), NETPILOT_REPOSITORY_URL) {
            try {
                uriHandler.openUri(NETPILOT_REPOSITORY_URL)
                repositoryLinkFailed = false
            } catch (_: IllegalArgumentException) {
                repositoryLinkFailed = true
            }
        }
        if (repositoryLinkFailed) Notice(strings(R.string.about_repository_unavailable), warning = true)
        InfoLine(strings(R.string.current_system), "Android ${state.runtime.capabilities.androidRelease.ifBlank { strings(R.string.pending_detection) }} · API ${state.runtime.capabilities.sdk}")
        InfoLine(strings(R.string.device), "${state.runtime.capabilities.manufacturer} ${state.runtime.capabilities.model}".trim().ifBlank { strings(R.string.pending_detection) })
        Text(strings(R.string.compatibility_notice), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (reset) AlertDialog(onDismissRequest = { reset = false }, title = { Text(strings(R.string.reset_question)) }, text = { Text(strings(R.string.reset_description)) }, confirmButton = { TextButton({ reset = false; actions.reset() }) { Text(strings(R.string.reset_confirm)) } }, dismissButton = { TextButton({ reset = false }) { Text(strings(R.string.cancel)) } })
    if (advanced) CompatibilityDialog(state.config, { advanced = false }, { actions.config(it); advanced = false })
}

@Composable private fun CompatibilityDialog(config: AppConfig, dismiss: () -> Unit, save: (AppConfig) -> Unit) {
    val strings = rememberLocalizedStrings()
    var mode by remember { mutableStateOf(config.tetheringAdapter) }
    var oem by remember { mutableStateOf(config.oem) }
    val next = config.copy(tetheringAdapter = mode, oem = oem)
    val error = next.validate()
    AlertDialog(onDismissRequest = dismiss, title = { Text(strings(R.string.rom_compatibility)) }, text = {
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(strings(R.string.rom_advanced_description), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ChoiceChips(AdapterMode.entries, mode, { when (it) { AdapterMode.AUTO -> strings(R.string.adapter_auto); AdapterMode.API_30 -> "Android 11"; AdapterMode.API_31 -> "Android 12–15"; AdapterMode.API_36 -> "Android 16+"; AdapterMode.OEM -> strings(R.string.oem_commands) } }) { mode = it }
            SwitchSetting(strings(R.string.allow_oem), strings(R.string.allow_oem_hint), oem.enabled, { oem = oem.copy(enabled = it) })
            OutlinedTextField(oem.usbInterfaceRegex, { oem = oem.copy(usbInterfaceRegex = it) }, label = { Text(strings(R.string.usb_interface_regex)) }, singleLine = true)
            OutlinedTextField(oem.wifiInterfaceRegex, { oem = oem.copy(wifiInterfaceRegex = it) }, label = { Text(strings(R.string.wifi_interface_regex)) }, singleLine = true)
            OutlinedTextField(oem.usbStatePath, { oem = oem.copy(usbStatePath = it) }, label = { Text(strings(R.string.usb_gadget_path)) }, singleLine = true)
            OutlinedTextField(oem.dataRolePath, { oem = oem.copy(dataRolePath = it) }, label = { Text(strings(R.string.usb_role_path)) }, singleLine = true)
            if (oem.enabled) {
                OutlinedTextField(oem.usbStartCommand, { oem = oem.copy(usbStartCommand = it) }, label = { Text(strings(R.string.usb_start_command)) }, singleLine = true)
                OutlinedTextField(oem.usbStopCommand, { oem = oem.copy(usbStopCommand = it) }, label = { Text(strings(R.string.usb_stop_command)) }, singleLine = true)
                OutlinedTextField(oem.wifiStartCommand, { oem = oem.copy(wifiStartCommand = it) }, label = { Text(strings(R.string.wifi_start_command)) }, singleLine = true)
                OutlinedTextField(oem.wifiStopCommand, { oem = oem.copy(wifiStopCommand = it) }, label = { Text(strings(R.string.wifi_stop_command)) }, singleLine = true)
                Notice(strings(R.string.oem_notice))
            }
            if (error != null) Text(strings.runtime(error), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton({ save(next) }, enabled = error == null) { Text(strings(R.string.save)) } }, dismissButton = { TextButton(dismiss) { Text(strings(R.string.cancel)) } })
}
