package dev.netpilot.ui.components

import dev.netpilot.R
import dev.netpilot.i18n.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.netpilot.core.model.ScheduleDocument
import dev.netpilot.core.model.ScheduleSlot
import dev.netpilot.core.schedule.ScheduleCalculator

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun ScheduleEditor(slot: ScheduleSlot, document: ScheduleDocument, onDismiss: () -> Unit, onSave: (ScheduleSlot) -> Unit) {
    val strings = rememberLocalizedStrings()
    var label by remember(slot.id) { mutableStateOf(strings.slotLabel(slot)) }
    var start by remember(slot.id) { mutableIntStateOf(slot.startMinute) }
    var end by remember(slot.id) { mutableIntStateOf(slot.endMinute) }
    var days by remember(slot.id) { mutableStateOf(slot.days) }
    var enabled by remember(slot.id) { mutableStateOf(slot.enabled) }
    var picker by remember { mutableIntStateOf(0) }
    val edited = slot.copy(label = label.trim(), startMinute = start, endMinute = end, days = days, enabled = enabled)
    val candidate = document.copy(slots = if (document.slots.any { it.id == slot.id }) document.slots.map { if (it.id == slot.id) edited else it } else document.slots + edited)
    val validation = ScheduleCalculator.validate(candidate)
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (document.slots.any { it.id == slot.id }) strings(R.string.edit_slot) else strings(R.string.add_slot)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(label, { if (it.length <= 32) label = it }, label = { Text(strings(R.string.slot_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilledTonalButton({ picker = 1 }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp)) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Text(strings(R.string.turn_on), style = MaterialTheme.typography.bodySmall); Text(ScheduleSlot.minuteText(start), style = MaterialTheme.typography.titleLarge) } }
                    FilledTonalButton({ picker = 2 }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp)) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Text(strings(R.string.turn_off), style = MaterialTheme.typography.bodySmall); Text(ScheduleSlot.minuteText(end), style = MaterialTheme.typography.titleLarge) } }
                }
                if (end < start) Text(strings(R.string.overnight_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                Text(strings(R.string.repeat_days), style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(strings(R.string.day_mon_short), strings(R.string.day_tue_short), strings(R.string.day_wed_short), strings(R.string.day_thu_short), strings(R.string.day_fri_short), strings(R.string.day_sat_short), strings(R.string.day_sun_short)).forEachIndexed { index, text ->
                        val day = index + 1; val selected = day in days
                        Box(Modifier.weight(1f).height(44.dp).background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                            .clickable(role = Role.Checkbox) { days = if (selected) days - day else days + day }
                            .semantics { contentDescription = strings(R.string.day_selection_description, strings.dayText(day), strings(if (selected) R.string.selected else R.string.not_selected)) }, contentAlignment = Alignment.Center) {
                            Text(text, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton({ days = (1..7).toSet() }, contentPadding = PaddingValues(4.dp)) { Text(strings(R.string.days_every)) }
                    TextButton({ days = (1..5).toSet() }, contentPadding = PaddingValues(4.dp)) { Text(strings(R.string.days_weekdays)) }
                    TextButton({ days = setOf(6, 7) }, contentPadding = PaddingValues(4.dp)) { Text(strings(R.string.days_weekend)) }
                }
                SwitchSetting(strings(R.string.enable_slot), strings(R.string.enable_slot_description), enabled, { enabled = it })
                if (validation != null) Text(strings.runtime(validation), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }, confirmButton = { TextButton({ onSave(edited) }, enabled = validation == null) { Text(strings(R.string.save)) } }, dismissButton = { TextButton(onDismiss) { Text(strings(R.string.cancel)) } })
    if (picker != 0) {
        val initial = if (picker == 1) start else end
        val time = rememberTimePickerState(initialHour = initial / 60, initialMinute = initial % 60, is24Hour = true)
        AlertDialog(onDismissRequest = { picker = 0 }, icon = { Icon(Icons.Outlined.Schedule, null) }, title = { Text(if (picker == 1) strings(R.string.start_time) else strings(R.string.end_time)) },
            text = { TimeInput(time) }, confirmButton = { TextButton({ val value = time.hour * 60 + time.minute; if (picker == 1) start = value else end = value; picker = 0 }) { Text(strings(R.string.confirm)) } },
            dismissButton = { TextButton({ picker = 0 }) { Text(strings(R.string.cancel)) } })
    }
}
