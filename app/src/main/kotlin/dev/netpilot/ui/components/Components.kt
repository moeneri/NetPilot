package dev.netpilot.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.netpilot.core.model.*
import dev.netpilot.R
import dev.netpilot.i18n.LocalizedStrings
import dev.netpilot.ui.theme.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .8f))) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
    }
}
@Composable fun PageHeading(title: String, subtitle: String, action: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, style = MaterialTheme.typography.headlineMedium)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
        action?.invoke()
    }
}
@Composable fun SectionLabel(title: String, detail: String? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable fun HealthBadge(text: String, health: Health = Health.UNKNOWN) {
    val color = healthColor(health)
    Surface(shape = RoundedCornerShape(50), color = color.copy(alpha = .10f)) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(5.dp).background(color, CircleShape))
            Text(text, style = MaterialTheme.typography.labelMedium, color = color)
        }
    }
}
@Composable fun healthColor(health: Health): Color = when (health) {
    Health.OK -> if (MaterialTheme.colorScheme.background.red < .2f) Color(0xFFA4D2B5) else StatusGreen
    Health.RUNNING -> StatusBlue; Health.WAITING -> StatusAmber; Health.ERROR -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}
@Composable fun IconTile(icon: ImageVector, modifier: Modifier = Modifier, tint: Color = MaterialTheme.colorScheme.primary) {
    Box(modifier.size(38.dp).background(tint.copy(alpha = .08f), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(21.dp))
    }
}
@Composable fun SwitchSetting(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onChange, enabled = enabled, modifier = Modifier.height(40.dp))
    }
}
@Composable fun SettingLink(icon: ImageVector, title: String, description: String, trailing: String? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        IconTile(icon)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (trailing != null) Text(trailing, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Icon(Icons.Outlined.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
    }
}
@Composable fun InfoLine(label: String, value: String, color: Color = MaterialTheme.colorScheme.onSurface) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.weight(.4f), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        Text(value, Modifier.weight(.6f), color = color, style = MaterialTheme.typography.bodyMedium)
    }
}
@Composable fun Notice(message: String, warning: Boolean = false) {
    val color = if (warning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Surface(shape = RoundedCornerShape(16.dp), color = color.copy(alpha = .07f), modifier = Modifier.fillMaxWidth()) {
        Text(message, Modifier.padding(14.dp), style = MaterialTheme.typography.bodyMedium, color = color)
    }
}
@Composable fun <T> ChoiceChips(options: List<T>, selected: T, text: (T) -> String, onSelect: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
        options.forEach { option -> FilterChip(selected == option, { onSelect(option) }, label = { Text(text(option), style = MaterialTheme.typography.labelLarge) }) }
    }
}
@Composable fun PortIllustration(modifier: Modifier = Modifier) {
    Canvas(modifier.size(84.dp)) {
        val stroke = 2.8.dp.toPx()
        val center = Offset(size.width * .46f, size.height * .48f)
        val color = Lime.copy(alpha = .85f)
        val points = listOf(Offset(size.width * .46f, size.height * .12f), Offset(size.width * .89f, size.height * .48f), Offset(size.width * .46f, size.height * .90f), Offset(size.width * .08f, size.height * .48f))
        points.forEach { point -> drawLine(color.copy(alpha = .7f), center, point, stroke, StrokeCap.Round); drawCircle(color, 4.7.dp.toPx(), point) }
        drawCircle(Color(0xFF365B45), 14.dp.toPx(), center)
        drawCircle(color, 7.dp.toPx(), center)
    }
}

fun LocalizedStrings.tetherTitle(phase: TetherPhase) = invoke(when (phase) {
    TetherPhase.OFF -> R.string.tether_off; TetherPhase.STARTING -> R.string.tether_starting; TetherPhase.GADGET_READY -> R.string.tether_gadget
    TetherPhase.INTERFACE_READY -> R.string.tether_interface; TetherPhase.WAITING_UPSTREAM -> R.string.tether_upstream; TetherPhase.READY -> R.string.tether_ready
    TetherPhase.EXTERNAL -> R.string.tether_external; TetherPhase.ERROR -> R.string.tether_error; TetherPhase.UNSUPPORTED -> R.string.tether_unsupported
})
fun tetherHealth(phase: TetherPhase) = when (phase) {
    TetherPhase.READY -> Health.OK; TetherPhase.STARTING, TetherPhase.GADGET_READY, TetherPhase.INTERFACE_READY -> Health.RUNNING
    TetherPhase.WAITING_UPSTREAM -> Health.WAITING; TetherPhase.ERROR, TetherPhase.UNSUPPORTED -> Health.ERROR
    TetherPhase.EXTERNAL -> Health.RUNNING; else -> Health.OFF
}
fun LocalizedStrings.providerText(provider: RootProvider) = when (provider) {
    RootProvider.MAGISK -> "Magisk"; RootProvider.KERNEL_SU -> "KernelSU"; RootProvider.APATCH -> "APatch"; RootProvider.OTHER -> invoke(R.string.provider_other)
    RootProvider.NONE -> invoke(R.string.root_denied); RootProvider.UNKNOWN -> invoke(R.string.root_pending)
}
fun LocalizedStrings.upstreamTypeText(type: UpstreamType) = invoke(when (type) {
    UpstreamType.NONE -> R.string.not_ready
    UpstreamType.CELLULAR -> R.string.mobile_data
    UpstreamType.WIFI -> R.string.upstream_wifi
    UpstreamType.ETHERNET -> R.string.upstream_ethernet
    UpstreamType.BLUETOOTH -> R.string.upstream_bluetooth
    UpstreamType.VPN -> R.string.upstream_vpn
    UpstreamType.OTHER -> R.string.upstream_other
    UpstreamType.UNKNOWN -> R.string.pending_detection
})
fun LocalizedStrings.priorityText(priority: SharingPriority) = invoke(when (priority) { SharingPriority.AUTO -> R.string.priority_auto; SharingPriority.USB_FIRST -> R.string.priority_usb; SharingPriority.WIFI_FIRST -> R.string.priority_wifi })
fun LocalizedStrings.modeText(mode: WifiMode) = invoke(when (mode) { WifiMode.AUTO -> R.string.mode_auto; WifiMode.FORCE_ON -> R.string.mode_on; WifiMode.FORCE_OFF -> R.string.mode_off })
fun LocalizedStrings.dayText(day: Int) = invoke(listOf(R.string.day_mon, R.string.day_tue, R.string.day_wed, R.string.day_thu, R.string.day_fri, R.string.day_sat, R.string.day_sun)[day - 1])
fun LocalizedStrings.daysText(days: Set<Int>): String = when (days) {
    (1..7).toSet() -> invoke(R.string.days_every); (1..5).toSet() -> invoke(R.string.days_weekdays); setOf(6, 7) -> invoke(R.string.days_weekend)
    else -> days.sorted().joinToString(" · ") { dayText(it) }
}
fun LocalizedStrings.dateTimeText(timestamp: Long) = if (timestamp <= 0) invoke(R.string.waiting_start) else Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern(invoke(R.string.date_time_pattern), locale))
fun clockText(timestamp: Long) = Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm", java.util.Locale.ROOT))
fun LocalizedStrings.countdownText(target: Long, now: Long): String {
    val seconds = ((target - now).coerceAtLeast(0) / 1000)
    return when { seconds >= 3600 -> invoke(R.string.countdown_hours, seconds / 3600, (seconds % 3600) / 60); seconds >= 60 -> invoke(R.string.countdown_minutes, seconds / 60, seconds % 60); else -> invoke(R.string.countdown_seconds, seconds) }
}
