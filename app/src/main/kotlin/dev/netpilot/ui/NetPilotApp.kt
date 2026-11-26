package dev.netpilot.ui

import dev.netpilot.R
import dev.netpilot.i18n.*
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.netpilot.core.model.*
import dev.netpilot.data.repository.AppUiState
import dev.netpilot.ui.components.*
import dev.netpilot.ui.dashboard.DashboardScreen
import dev.netpilot.ui.guardian.GuardianScreen
import dev.netpilot.ui.schedule.ScheduleScreen
import dev.netpilot.ui.settings.SettingsScreen
import dev.netpilot.ui.theme.NetPilotTheme
import dev.netpilot.ui.usb.UsbScreen
import kotlinx.coroutines.delay

enum class Screen(val labelRes: Int, val icon: ImageVector) {
    DASHBOARD(R.string.nav_dashboard, Icons.Outlined.Home), SCHEDULE(R.string.nav_schedule, Icons.Outlined.Schedule), USB(R.string.nav_usb, Icons.Outlined.Usb), GUARDIAN(R.string.nav_guardian, Icons.Outlined.Shield), SETTINGS(R.string.nav_settings, Icons.Outlined.Settings)
}

@Composable fun NetPilotApp(state: AppUiState, actions: UiActions, initialScreen: Screen = Screen.DASHBOARD, fixedNow: Long? = null) {
    ProvideAppLanguage(state.language) { NetPilotContent(state, actions, initialScreen, fixedNow) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun NetPilotContent(state: AppUiState, actions: UiActions, initialScreen: Screen, fixedNow: Long?) {
    val strings = rememberLocalizedStrings()
    var screen by rememberSaveable { mutableStateOf(initialScreen) }
    var logSheet by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(fixedNow ?: System.currentTimeMillis()) }
    LaunchedEffect(fixedNow) { if (fixedNow == null) while (true) { delay(1_000); now = System.currentTimeMillis() } }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message, strings.locale) { state.message?.let { snackbar.showSnackbar(strings.runtime(it), duration = SnackbarDuration.Long); actions.dismissMessage() } }
    val withLogs = actions.copy(logs = { logSheet = true; actions.logs() })
    NetPilotTheme(state.config.theme) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth >= 700.dp
            Scaffold(containerColor = MaterialTheme.colorScheme.background,
                contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
                snackbarHost = { SnackbarHost(snackbar) },
                topBar = {
                    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 24.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.size(33.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(11.dp)), contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Usb, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onPrimary) }
                        Text("NetPilot", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.weight(1f))
                        HealthBadge(if (state.connected) strings(R.string.connected) else strings(R.string.waiting_connection), if (state.connected) Health.OK else Health.UNKNOWN)
                    }
                },
                bottomBar = {
                    if (!wide) NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                        Screen.entries.forEach { item -> NavigationBarItem(selected = screen == item, onClick = { screen = item }, icon = { Icon(item.icon, strings(item.labelRes), Modifier.size(22.dp)) }, label = { Text(strings(item.labelRes)) }) }
                    }
                }) { padding ->
                Row(Modifier.fillMaxSize().padding(padding)) {
                    if (wide) NavigationRail(containerColor = MaterialTheme.colorScheme.surface) {
                        Screen.entries.forEach { item -> NavigationRailItem(selected = screen == item, onClick = { screen = item }, icon = { Icon(item.icon, strings(item.labelRes)) }, label = { Text(strings(item.labelRes)) }) }
                    }
                    Box(Modifier.weight(1f), contentAlignment = Alignment.TopCenter) {
                        AnimatedContent(screen, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "navigation") { selected ->
                            Column(Modifier.widthIn(max = 760.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = if (wide) 32.dp else 22.dp).padding(top = 12.dp, bottom = 26.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                                when (selected) {
                                    Screen.DASHBOARD -> DashboardScreen(state, withLogs, now) { screen = it }
                                    Screen.SCHEDULE -> ScheduleScreen(state, withLogs, now)
                                    Screen.USB -> UsbScreen(state, withLogs)
                                    Screen.GUARDIAN -> GuardianScreen(state, withLogs)
                                    Screen.SETTINGS -> SettingsScreen(state, withLogs) { screen = it }
                                }
                            }
                        }
                    }
                }
            }
            if (state.busy != null) Surface(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 65.dp, start = 20.dp, end = 20.dp), shape = RoundedCornerShape(50), shadowElevation = 6.dp, color = MaterialTheme.colorScheme.surface) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Text(strings.runtime(state.busy), style = MaterialTheme.typography.bodySmall) }
            }
        }
        if (logSheet) ModalBottomSheet(onDismissRequest = { logSheet = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.fillMaxWidth().fillMaxHeight(.86f).padding(horizontal = 22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionLabel(strings(R.string.logs_title), strings(R.string.log_count, state.logs.size))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(actions.logs) { Icon(Icons.Outlined.Refresh, null, Modifier.size(17.dp)); Spacer(Modifier.width(5.dp)); Text(strings(R.string.refresh)) }
                    TextButton({ actions.export(ExportKind.LOGS) }) { Text(strings(R.string.export)) }
                    TextButton(actions.clearLogs, enabled = state.connected && state.busy == null) { Text(strings(R.string.clear)) }
                }
                if (state.logs.isEmpty()) Notice(if (state.config.guardian.installed) strings(R.string.logs_empty_installed) else strings(R.string.logs_empty_uninstalled))
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(state.logs.asReversed()) { entry -> Panel {
                        Row { Text("${clockText(entry.timestamp)} · ${entry.source}", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall); Text(entry.level.name, color = if (entry.level >= LogLevel.WARN) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium) }
                        SelectionContainer { Text(strings.runtime(entry.message), style = MaterialTheme.typography.bodyMedium) }
                    } }
                }
            }
        }
        state.diagnosticText?.let { text -> AlertDialog(onDismissRequest = actions.dismissDiagnostics, title = { Text(strings(R.string.diagnostics_title)) },
            text = { SelectionContainer { Text(text, Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall) } },
            confirmButton = { TextButton({ actions.export(ExportKind.DIAGNOSTICS) }) { Text(strings(R.string.export_diagnostics)) } }, dismissButton = { TextButton(actions.dismissDiagnostics) { Text(strings(R.string.close)) } }) }
    }
}
