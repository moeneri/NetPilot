package dev.netpilot.ui.dashboard

import dev.netpilot.R
import dev.netpilot.i18n.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.netpilot.core.model.*
import dev.netpilot.core.schedule.ScheduleCalculator
import dev.netpilot.data.repository.AppUiState
import dev.netpilot.ui.Screen
import dev.netpilot.ui.UiActions
import dev.netpilot.ui.components.*
import dev.netpilot.ui.theme.Lime
import java.time.Instant
import java.time.ZoneId

@Composable fun DashboardScreen(state: AppUiState, actions: UiActions, now: Long, navigate: (Screen) -> Unit) {
    val strings = rememberLocalizedStrings()
    PageHeading(strings(R.string.gateway_title), strings(R.string.gateway_subtitle))
    Surface(shape = RoundedCornerShape(24.dp), color = Color.Transparent) {
        Row(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(Color(0xFF173F35), Color(0xFF335B43)))).padding(22.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("NATIVE TETHERING", color = Lime, style = MaterialTheme.typography.labelMedium)
                Text(strings(R.string.hero_title), color = Color.White, style = MaterialTheme.typography.titleLarge)
                Text(strings(R.string.hero_description), color = Color.White.copy(alpha = .72f), style = MaterialTheme.typography.bodyMedium)
                Surface(shape = RoundedCornerShape(50), color = Color.White.copy(alpha = .10f)) {
                    Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (state.connected) Icons.Outlined.Shield else Icons.Outlined.Security, null, Modifier.size(13.dp), tint = Lime)
                        Text(when { state.config.guardian.paused -> strings(R.string.automation_paused); state.connected -> strings(R.string.guardian_running); else -> strings(R.string.first_connection) }, color = Color.White.copy(alpha = .9f), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            PortIllustration(Modifier.padding(start = 8.dp))
        }
    }
    if (!state.config.guardian.installed) {
        Panel {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconTile(Icons.Outlined.Shield)
                Column(Modifier.weight(1f)) { Text(strings(R.string.onboarding_title), style = MaterialTheme.typography.titleSmall); Text(strings(R.string.onboarding_description), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Button(actions.install, modifier = Modifier.fillMaxWidth(), enabled = state.busy == null) { Text(strings(R.string.install_guardian)); Spacer(Modifier.width(8.dp)); Icon(Icons.Outlined.ArrowForward, null, Modifier.size(17.dp)) }
        }
    }
    SectionLabel(strings(R.string.connection_status), if (state.connected) strings(R.string.system_callbacks) else strings(R.string.waiting_detection))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val upstream = state.runtime.upstream
        val upstreamValue = when {
            !state.connected -> strings(R.string.pending_detection)
            upstream.dunUpstream -> strings(R.string.mobile_dun_upstream)
            upstream.type != UpstreamType.NONE -> strings.upstreamTypeText(upstream.type)
            else -> strings(R.string.not_ready)
        }
        val upstreamDetail = when {
            !state.connected -> strings(R.string.upstream_automatic)
            !upstream.tetherUpstreamConfirmed -> strings.runtime(upstream.detail)
            else -> strings(R.string.upstream_interface_status,
                strings(if (upstream.validated || upstream.dunUpstream) R.string.network_available else R.string.network_preparing),
                upstream.interfaceName ?: strings(R.string.waiting_interface))
        }
        val upstreamHealth = when {
            !state.connected -> Health.UNKNOWN
            upstream.tetherUpstreamConfirmed && (upstream.validated || upstream.dunUpstream) -> Health.OK
            else -> Health.WAITING
        }
        StatusCard(strings(R.string.upstream_network), upstreamValue, upstreamDetail, Icons.Outlined.DataUsage, upstreamHealth, Modifier.weight(1f))
        StatusCard(strings(R.string.usb_tethering), if (state.connected) strings.tetherTitle(state.runtime.usb.phase) else strings(R.string.pending_detection), if (state.connected) if (state.runtime.usb.ownedByNetPilot) strings(R.string.managed_by_netpilot) else strings(R.string.detect_data) else strings(R.string.detect_computer), Icons.Outlined.Usb, if (state.connected) tetherHealth(state.runtime.usb.phase) else Health.UNKNOWN, Modifier.weight(1f))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        StatusCard(strings(R.string.wifi_hotspot), if (state.connected) strings.tetherTitle(state.runtime.wifi.phase) else strings(R.string.pending_detection), strings.modeText(state.config.wifiMode), Icons.Outlined.Wifi, if (state.connected) tetherHealth(state.runtime.wifi.phase) else Health.UNKNOWN, Modifier.weight(1f))
        StatusCard(strings(R.string.root_guardian), if (state.connected) if (state.config.guardian.paused) strings(R.string.paused) else strings(R.string.running) else if (state.config.guardian.installed) strings(R.string.disconnected) else strings(R.string.root_pending), strings.providerText(state.config.rootProvider), Icons.Outlined.Shield, if (state.connected) Health.OK else if (state.config.guardian.installed) Health.WAITING else Health.UNKNOWN, Modifier.weight(1f))
    }
    Panel {
        SectionLabel(strings(R.string.quick_controls))
        SwitchSetting(strings(R.string.usb_automation), strings(R.string.usb_automation_quick_hint), state.config.usb.enabled, { actions.config(state.config.copy(usb = state.config.usb.copy(enabled = it))) }, state.busy == null)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) { Text(strings(R.string.wifi_hotspot), style = MaterialTheme.typography.titleSmall); Text(if (state.config.wifiMode == WifiMode.AUTO) strings(R.string.controlled_by_schedule) else strings(R.string.manual_schedule_description, strings.modeText(state.config.wifiMode)), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
            Switch(state.config.wifiMode == WifiMode.FORCE_ON || (state.config.wifiMode == WifiMode.AUTO && state.connected && state.runtime.wifi.ownedByNetPilot), { actions.config(state.config.copy(wifiMode = if (it) WifiMode.FORCE_ON else WifiMode.FORCE_OFF)) }, enabled = state.busy == null)
        }
        if (state.config.wifiMode != WifiMode.AUTO) TextButton({ actions.config(state.config.copy(wifiMode = WifiMode.AUTO)) }, contentPadding = PaddingValues(0.dp)) { Text(strings(R.string.resume_schedule)); Spacer(Modifier.width(5.dp)); Icon(Icons.Outlined.Schedule, null, Modifier.size(15.dp)) }
    }
    val next = ScheduleCalculator.evaluate(state.schedule.slots, Instant.ofEpochMilli(now), ZoneId.systemDefault()).next
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconTile(Icons.Outlined.Schedule)
            Column(Modifier.weight(1f)) {
                Text(if (state.config.wifiMode == WifiMode.AUTO) strings(R.string.next_hotspot_change) else strings(R.string.schedule_overridden), style = MaterialTheme.typography.titleSmall)
                Text(if (next != null && state.config.wifiMode == WifiMode.AUTO) strings(R.string.next_change_description, strings.dateTimeText(next.instant.toEpochMilli()), strings(if (next.turnOn) R.string.turn_on else R.string.turn_off)) else if (state.config.wifiMode != WifiMode.AUTO) strings.modeText(state.config.wifiMode) else strings(R.string.no_enabled_slots), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (next != null && state.config.wifiMode == WifiMode.AUTO) Text("${clockText(next.instant.toEpochMilli())}", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
        }
        if (next != null && state.config.wifiMode == WifiMode.AUTO) Text(strings(R.string.change_countdown, strings(if (state.connected) R.string.until_change else R.string.estimated_schedule), strings.countdownText(next.instant.toEpochMilli(), now)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton({ navigate(Screen.SCHEDULE) }, contentPadding = PaddingValues(0.dp)) { Text(strings(R.string.manage_schedule)); Icon(Icons.Outlined.KeyboardArrowRight, null, Modifier.size(16.dp)) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton({ navigate(Screen.GUARDIAN) }, Modifier.weight(1f)) { Icon(Icons.Outlined.Shield, null, Modifier.size(17.dp)); Spacer(Modifier.width(6.dp)); Text(strings(R.string.guardian_management)) }
        OutlinedButton(actions.logs, Modifier.weight(1f)) { Icon(Icons.Outlined.Description, null, Modifier.size(17.dp)); Spacer(Modifier.width(6.dp)); Text(strings(R.string.logs_title)) }
    }
}

@Composable private fun StatusCard(title: String, value: String, detail: String, icon: ImageVector, health: Health, modifier: Modifier) {
    Surface(modifier, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f)); Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            Text(value, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Medium), color = healthColor(health))
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
