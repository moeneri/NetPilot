package dev.netpilot.ui.schedule

import dev.netpilot.R
import dev.netpilot.i18n.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.netpilot.core.model.*
import dev.netpilot.core.schedule.ScheduleCalculator
import dev.netpilot.data.repository.AppUiState
import dev.netpilot.ui.UiActions
import dev.netpilot.ui.components.*
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

@Composable fun ScheduleScreen(state: AppUiState, actions: UiActions, now: Long) {
    val strings = rememberLocalizedStrings()
    var editor by remember { mutableStateOf<ScheduleSlot?>(null) }
    var deleting by remember { mutableStateOf<ScheduleSlot?>(null) }
    val evaluation = ScheduleCalculator.evaluate(state.schedule.slots, Instant.ofEpochMilli(now), ZoneId.systemDefault())
    PageHeading(strings(R.string.schedule_title), strings(R.string.schedule_subtitle)) {
        FilledIconButton({ editor = ScheduleSlot(UUID.randomUUID().toString(), strings(R.string.new_slot), 19 * 60, 20 * 60, enabled = true) }, enabled = state.schedule.slots.size < 20 && state.busy == null) { Icon(Icons.Outlined.Add, strings(R.string.add_slot)) }
    }
    Panel {
        SectionLabel(strings(R.string.operating_mode))
        ChoiceChips(WifiMode.entries, state.config.wifiMode, strings::modeText) { actions.config(state.config.copy(wifiMode = it)) }
        Text(when (state.config.wifiMode) { WifiMode.AUTO -> strings(R.string.mode_auto_hint); WifiMode.FORCE_ON -> strings(R.string.mode_on_hint); WifiMode.FORCE_OFF -> strings(R.string.mode_off_hint) }, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }
    Panel {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(strings(R.string.schedule_status), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(if (state.config.wifiMode != WifiMode.AUTO) strings.modeText(state.config.wifiMode) else if (evaluation.active) strings(R.string.schedule_active) else strings(R.string.schedule_inactive), style = MaterialTheme.typography.titleLarge)
            }
            IconTile(Icons.Outlined.Schedule)
        }
        val next = evaluation.next
        if (next != null) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            InfoLine(strings(R.string.next_schedule), "${strings.dateTimeText(next.instant.toEpochMilli())} ${if (next.turnOn) strings(R.string.turn_on) else strings(R.string.turn_off)}")
            InfoLine(strings(R.string.until_change), if (state.config.wifiMode == WifiMode.AUTO) strings.countdownText(next.instant.toEpochMilli(), now) else strings(R.string.manual_override))
        }
        InfoLine(strings(R.string.system_hotspot), if (state.connected) strings.tetherTitle(state.runtime.wifi.phase) else strings(R.string.waiting_root_detection))
        if (state.connected && state.runtime.wifi.phase == TetherPhase.EXTERNAL) Text(strings(R.string.external_hotspot_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        if (!state.config.guardian.installed) Text(strings(R.string.schedule_saved_uninstalled), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }
    SectionLabel(strings(R.string.daily_timeline), strings(R.string.slot_count, state.schedule.slots.size))
    if (state.schedule.slots.isEmpty()) Notice(strings(R.string.schedule_empty))
    state.schedule.slots.sortedBy { it.startMinute }.forEach { slot ->
        TimelineSlot(slot, now, { enabled -> actions.saveSlot(slot.copy(enabled = enabled)) }, { editor = slot }, { actions.duplicateSlot(slot) }, { deleting = slot }, state.busy == null)
    }
    OutlinedButton({ editor = ScheduleSlot(UUID.randomUUID().toString(), strings(R.string.new_slot), 19 * 60, 20 * 60) }, Modifier.fillMaxWidth(), enabled = state.schedule.slots.size < 20 && state.busy == null) { Icon(Icons.Outlined.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(7.dp)); Text(strings(R.string.add_slot)) }
    Text(strings(R.string.schedule_overnight_description), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    editor?.let { ScheduleEditor(it, state.schedule, { editor = null }, { value -> actions.saveSlot(value); editor = null }) }
    deleting?.let { slot -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text(strings(R.string.delete_slot_question, strings.slotLabel(slot))) }, text = { Text(strings(R.string.delete_slot_hint)) }, confirmButton = { TextButton({ actions.deleteSlot(slot); deleting = null }) { Text(strings(R.string.delete), color = MaterialTheme.colorScheme.error) } }, dismissButton = { TextButton({ deleting = null }) { Text(strings(R.string.cancel)) } }) }
}

@Composable private fun TimelineSlot(slot: ScheduleSlot, now: Long, toggle: (Boolean) -> Unit, edit: () -> Unit, duplicate: () -> Unit, delete: () -> Unit, enabled: Boolean) {
    val strings = rememberLocalizedStrings()
    var menu by remember { mutableStateOf(false) }
    val active = ScheduleCalculator.evaluate(listOf(slot), Instant.ofEpochMilli(now), ZoneId.systemDefault()).active
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.width(12.dp).height(166.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(25.dp)); Box(Modifier.size(9.dp).background(if (slot.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape)); Spacer(Modifier.height(8.dp)); Box(Modifier.width(1.dp).weight(1f).background(MaterialTheme.colorScheme.outlineVariant))
        }
        Panel(Modifier.weight(1f)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(strings.slotLabel(slot), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (active && slot.enabled) HealthBadge(strings(R.string.inside_slot), Health.OK)
                IconButton({ menu = true }, Modifier.size(28.dp)) { Icon(Icons.Outlined.MoreVert, strings(R.string.slot_actions), Modifier.size(18.dp)) }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ Text(strings(R.string.edit)) }, { menu = false; edit() }, leadingIcon = { Icon(Icons.Outlined.Edit, null) })
                    DropdownMenuItem({ Text(strings(R.string.duplicate)) }, { menu = false; duplicate() }, leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) })
                    DropdownMenuItem({ Text(strings(R.string.delete)) }, { menu = false; delete() }, leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null) })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(slot.startText, style = MaterialTheme.typography.headlineMedium, color = if (slot.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                Text("—", color = MaterialTheme.colorScheme.outline)
                Text(slot.endText, style = MaterialTheme.typography.headlineMedium, color = if (slot.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                if (slot.crossesMidnight) Text(strings(R.string.next_day), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(strings.daysText(slot.days), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(if (slot.enabled) strings(R.string.enabled) else strings(R.string.disabled), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Switch(slot.enabled, toggle, modifier = Modifier.height(28.dp), enabled = enabled)
            }
        }
    }
}
