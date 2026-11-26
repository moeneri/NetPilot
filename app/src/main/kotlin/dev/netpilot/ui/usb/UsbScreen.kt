package dev.netpilot.ui.usb

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
import kotlin.math.roundToLong

@Composable fun UsbScreen(state: AppUiState, actions: UiActions) {
    val strings = rememberLocalizedStrings()
    val usb = state.config.usb
    fun save(value: UsbSettings) = actions.config(state.config.copy(usb = value))
    val signals = state.runtime.usbSignals
    val connection = state.runtime.usbDecision.connection
    val shared = state.runtime.usb
    var debounce by remember(usb.debounceMillis) { mutableFloatStateOf(usb.debounceMillis / 1000f) }
    PageHeading(strings(R.string.usb_automation), strings(R.string.usb_subtitle))
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconTile(Icons.Outlined.Usb)
            Column(Modifier.weight(1f)) { Text(if (state.connected) strings.runtime(state.runtime.usbDecision.detail) else strings(R.string.waiting_root_guardian), style = MaterialTheme.typography.titleMedium); Text(strings(R.string.power_only_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        InfoLine(strings(R.string.usb_connection), if (!state.connected) strings(R.string.pending_detection) else when (connection) { UsbConnection.DISCONNECTED -> strings(R.string.disconnected); UsbConnection.POWER_ONLY -> strings(R.string.usb_power_unconfirmed); UsbConnection.DATA_HOST -> strings(R.string.usb_data_confirmed); else -> strings(R.string.usb_waiting_signals) })
        InfoLine(strings(R.string.computer_detection), if (!state.connected) strings(R.string.pending_detection) else if (connection == UsbConnection.DATA_HOST) strings(R.string.usb_confidence, state.runtime.usbDecision.confidence) else strings(R.string.usb_keep_mode))
        InfoLine(strings(R.string.usb_auto_start_status), if (state.connected) strings.runtime(state.runtime.usbAutomation.detail) else strings(R.string.pending_detection))
        if (state.connected) state.runtime.usbAutomation.retryAt?.let { InfoLine(strings(R.string.usb_next_retry), strings.dateTimeText(it)) }
        InfoLine(strings(R.string.usb_current_mode), if (state.connected) signals.config.ifBlank { strings(R.string.system_not_provided) } else strings(R.string.pending_detection))
        InfoLine(strings(R.string.rndis_function), if (!state.connected) strings(R.string.pending_detection) else if ("rndis" in signals.functions.split(',') || "rndis" in signals.config.split(',')) strings(R.string.configured) else strings(R.string.not_configured))
        InfoLine(strings(R.string.system_usb_tethering), if (state.connected) strings.tetherTitle(shared.phase) else strings(R.string.pending_detection))
        InfoLine(strings(R.string.tether_interface_ip), if (state.connected) (shared.interfaces + shared.addresses).joinToString(" · ").ifBlank { strings(R.string.not_assigned) } else strings(R.string.pending_detection))
        InfoLine(strings(R.string.client_addresses), if (!state.connected) strings(R.string.pending_detection) else shared.clientAddressCount?.let { strings(R.string.client_address_count, it) } ?: strings(R.string.client_addresses_unknown))
        if (state.connected && shared.phase != TetherPhase.OFF) Text(strings.runtime(shared.detail), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Panel {
        SectionLabel(strings(R.string.auto_detection_recovery))
        SwitchSetting(strings(R.string.usb_automation), strings(R.string.usb_automation_hint), usb.enabled, { save(usb.copy(enabled = it)) }, state.busy == null)
        SwitchSetting(strings(R.string.exclude_chargers), strings(R.string.exclude_chargers_hint), usb.excludeChargers, { save(usb.copy(excludeChargers = it)) }, state.busy == null)
        SwitchSetting(strings(R.string.require_enumeration), strings(R.string.require_enumeration_hint), usb.requireDataConfirmation, { save(usb.copy(requireDataConfirmation = it)) }, state.busy == null)
        SwitchSetting(strings(R.string.strict_detection), strings(R.string.strict_detection_hint), usb.strictDetection, { save(usb.copy(strictDetection = it)) }, state.busy == null)
        SwitchSetting(strings(R.string.stop_on_disconnect), strings(R.string.stop_on_disconnect_hint), usb.stopOnDisconnect, { save(usb.copy(stopOnDisconnect = it)) }, state.busy == null)
        SwitchSetting(strings(R.string.restore_usb), strings(R.string.restore_usb_hint), usb.restoreFunctions, { save(usb.copy(restoreFunctions = it)) }, state.busy == null)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(Modifier.fillMaxWidth()) { Text(strings(R.string.usb_debounce), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall); Text(strings(R.string.seconds_value, "%.1f".format(strings.locale, debounce)), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge) }
        Slider(debounce, { debounce = it }, valueRange = .5f..10f, steps = 18, onValueChangeFinished = { save(usb.copy(debounceMillis = ((debounce * 1000) / 500).roundToLong() * 500)) }, enabled = state.busy == null)
    }
    Panel {
        SectionLabel(strings(R.string.usb_wifi_parallel))
        ChoiceChips(SharingPriority.entries, state.config.priority, strings::priorityText) { actions.config(state.config.copy(priority = it)) }
        SwitchSetting(strings(R.string.suspend_wifi), strings(R.string.suspend_wifi_hint), usb.suspendWifiWhenConnected, { save(usb.copy(suspendWifiWhenConnected = it)) }, state.busy == null)
        Text(strings(R.string.parallel_capability, strings(when (state.runtime.capabilities.parallel) { ParallelCapability.SUPPORTED -> R.string.parallel_supported; ParallelCapability.UNSUPPORTED -> R.string.parallel_unsupported; else -> R.string.parallel_pending })), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (state.runtime.usbDecision.evidence.isNotEmpty() && state.connected) Panel { SectionLabel(strings(R.string.detection_evidence)); state.runtime.usbDecision.evidence.forEach { Text("· ${strings.runtime(it)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
    if (state.connected) state.runtime.usbSignals.monitorError?.let { Notice(strings.runtime(it)) }
    OutlinedButton({ actions.permissions(PermissionTarget.USB_SETTINGS) }, Modifier.fillMaxWidth()) { Icon(Icons.Outlined.Settings, null, Modifier.size(17.dp)); Spacer(Modifier.width(7.dp)); Text(strings(R.string.system_usb_settings)) }
}
