package dev.netpilot.ui

import dev.netpilot.R
import dev.netpilot.i18n.*
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.annotation.StringRes
import androidx.lifecycle.viewModelScope
import dev.netpilot.NetPilotApplication
import dev.netpilot.core.model.*
import dev.netpilot.service.GuardianForegroundService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

class NetPilotViewModel(application: Application) : AndroidViewModel(application) {
    val repository = (application as NetPilotApplication).repository
    val state = repository.state
    private val strings get() = AppLocale.strings(getApplication())
    private val visible = MutableStateFlow(false)
    init {
        viewModelScope.launch {
            while (isActive) {
                visible.first { it }
                repository.refresh(); repository.updatePermissions()
                delay(4_000)
            }
        }
    }
    fun visible(value: Boolean) { visible.value = value; if (value) repository.updatePermissions() }
    private fun action(@StringRes label: Int, @StringRes success: Int? = null, block: suspend () -> Unit) {
        if (state.value.busy != null) return
        viewModelScope.launch {
            repository.busy(strings(label))
            try { block(); if (success != null) repository.message(strings(success)) }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { repository.recordActionFailure(strings(label), error); repository.message(strings.runtime(error.message ?: strings(R.string.action_failed))) }
            finally { repository.busy(null); repository.updatePermissions() }
        }
    }
    fun language(language: AppLanguage) {
        try { repository.setLanguage(language) }
        catch (error: Exception) {
            viewModelScope.launch { repository.recordActionFailure(strings(R.string.language_title), error) }
            repository.message(error.message ?: strings(R.string.language_save_failed))
        }
    }
    fun config(config: AppConfig) = action(R.string.save_settings_action, R.string.settings_saved) {
        repository.updateConfig { config }
        if (config.guardian.installed && config.guardian.foregroundEnabled) startForeground() else GuardianForegroundService.stop(getApplication())
    }
    fun save(slot: ScheduleSlot) = action(R.string.save_schedule_action, R.string.schedule_saved) { repository.saveSlot(slot) }
    fun delete(slot: ScheduleSlot) = action(R.string.delete_slot_action, R.string.slot_deleted) { repository.deleteSlot(slot.id) }
    fun duplicate(slot: ScheduleSlot) = action(R.string.duplicate_slot_action, R.string.slot_duplicated) { repository.duplicateSlot(slot) }
    fun install() = action(R.string.install_guardian_action, R.string.root_guardian_connected) { repository.installGuardian(); startForeground() }
    fun start() = action(R.string.start_guardian_action, R.string.guardian_now_connected) { repository.startGuardian(); startForeground() }
    fun stop() = action(R.string.stop_guardian_action, R.string.guardian_stopped) { repository.stopGuardian(); GuardianForegroundService.stop(getApplication()) }
    fun restart() = action(R.string.restart_guardian_action, R.string.guardian_reconnected) { repository.restartGuardian() }
    fun pause() = action(R.string.automation_action) { repository.pauseAutomation(); if (state.value.connected) startForeground() }
    fun retry() = action(R.string.retry_action, R.string.retry_requested) { repository.command("RETRY") }
    fun uninstall() = action(R.string.uninstall_guardian_action, R.string.guardian_removed) { repository.uninstallGuardian(); GuardianForegroundService.stop(getApplication()) }
    fun logs() = action(R.string.read_logs_action) { repository.readLogs() }
    fun clearLogs() = action(R.string.clear_logs_action, R.string.logs_cleared) { repository.clearLogs() }
    fun diagnose() = action(R.string.generate_diagnostics) { repository.diagnostic(repository.diagnostics()) }
    fun doze() = action(R.string.doze_action, R.string.doze_updated) { repository.command("DOZE_ADD"); repository.updatePermissions() }
    fun restore(text: String) = action(R.string.restore_configuration, R.string.configuration_restored) { repository.restore(text) }
    fun reset() = action(R.string.reset_settings, R.string.settings_reset) { repository.resetDefaults() }
    fun dismissMessage() = repository.message(null)
    fun dismissDiagnostics() = repository.diagnostic(null)
    fun report(text: String) = repository.message(text)
    private fun startForeground() {
        if (!state.value.config.guardian.foregroundEnabled) return
        try { GuardianForegroundService.start(getApplication()) }
        catch (error: Exception) {
            viewModelScope.launch { repository.recordActionFailure(strings(R.string.foreground_start_action), error) }
            repository.message(strings(R.string.foreground_not_allowed, strings.runtime(error.message ?: strings(R.string.action_failed))))
        }
    }
}
