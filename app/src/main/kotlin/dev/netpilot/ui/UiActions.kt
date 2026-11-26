package dev.netpilot.ui

import dev.netpilot.core.model.*
import dev.netpilot.i18n.AppLanguage

enum class PermissionTarget { ROOT, EXACT_ALARM, BATTERY, NOTIFICATIONS, APP_DETAILS, USB_SETTINGS }
enum class ExportKind { CONFIG, LOGS, DIAGNOSTICS }

data class UiActions(
    val language: (AppLanguage) -> Unit = {},
    val config: (AppConfig) -> Unit = {},
    val saveSlot: (ScheduleSlot) -> Unit = {},
    val deleteSlot: (ScheduleSlot) -> Unit = {},
    val duplicateSlot: (ScheduleSlot) -> Unit = {},
    val install: () -> Unit = {}, val start: () -> Unit = {}, val stop: () -> Unit = {}, val restart: () -> Unit = {},
    val pause: () -> Unit = {}, val retry: () -> Unit = {}, val uninstall: () -> Unit = {},
    val uninstallApp: () -> Unit = {},
    val logs: () -> Unit = {}, val clearLogs: () -> Unit = {}, val diagnose: () -> Unit = {}, val doze: () -> Unit = {},
    val export: (ExportKind) -> Unit = {}, val importConfig: () -> Unit = {}, val reset: () -> Unit = {},
    val permissions: (PermissionTarget) -> Unit = {}, val dismissMessage: () -> Unit = {}, val dismissDiagnostics: () -> Unit = {},
)
