package dev.netpilot.data.repository

import android.system.Os
import dev.netpilot.core.model.*
import kotlinx.serialization.encodeToString
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class AtomicJsonStore(val directory: File, private val rootPermissions: Boolean = false) {
    init { directory.mkdirs(); if (rootPermissions) runCatching { Os.chmod(directory.path, 448) } }
    fun readText(name: String): String? = runCatching {
        val file = child(name)
        require(file.length() <= 1024 * 1024)
        file.readText()
    }.getOrNull()
    @Synchronized fun writeText(name: String, value: String) {
        require(value.toByteArray().size <= 1024 * 1024)
        val target = child(name)
        val temporary = child("$name.new")
        FileOutputStream(temporary).use { stream -> stream.write(value.toByteArray()); stream.fd.sync() }
        runCatching { Os.chmod(temporary.path, 384) }
        Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
    fun config(): AppConfig = readText("config.json")?.let { runCatching { NetPilotJson.decodeFromString<AppConfig>(it) }.getOrNull()?.takeIf { config -> config.validate() == null } } ?: AppConfig()
    fun schedule(): ScheduleDocument = readText("schedule.json")?.let { runCatching { NetPilotJson.decodeFromString<ScheduleDocument>(it) }.getOrNull()?.takeIf { schedule -> dev.netpilot.core.schedule.ScheduleCalculator.validate(schedule) == null } } ?: ScheduleDocument()
    fun state(): RuntimeState = readText("state.json")?.let { runCatching { NetPilotJson.decodeFromString<RuntimeState>(it) }.getOrNull() } ?: RuntimeState()
    fun requireConfig(): AppConfig = NetPilotJson.decodeFromString<AppConfig>(readText("config.json") ?: error("Root 配置缺失或不可读")).also { require(it.validate() == null) { it.validate().orEmpty() } }
    fun requireSchedule(): ScheduleDocument = NetPilotJson.decodeFromString<ScheduleDocument>(readText("schedule.json") ?: error("Root 时间计划缺失或不可读")).also { require(dev.netpilot.core.schedule.ScheduleCalculator.validate(it) == null) { "Root 时间计划无效" } }
    fun stateForRecovery(): RuntimeState {
        if (!child("state.json").exists()) return RuntimeState()
        return NetPilotJson.decodeFromString<RuntimeState>(readText("state.json") ?: error("Root 状态不可读，无法安全恢复共享归属")).also { require(it.schemaVersion == 1) { "Root 状态版本不支持，保留恢复文件" } }
    }
    fun save(config: AppConfig) = writeText("config.json", NetPilotJson.encodeToString(config))
    fun save(schedule: ScheduleDocument) = writeText("schedule.json", NetPilotJson.encodeToString(schedule))
    fun save(state: RuntimeState) = writeText("state.json", NetPilotJson.encodeToString(state))
    fun recoverPendingRestore() {
        val text = readText("restore.pending.json") ?: return
        val backup = runCatching { NetPilotJson.decodeFromString<BackupDocument>(text) }.getOrNull() ?: return
        if (backup.format != "netpilot-backup" || backup.version != 1 || backup.config.validate() != null || dev.netpilot.core.schedule.ScheduleCalculator.validate(backup.schedule) != null) return
        save(backup.config); save(backup.schedule); child("restore.pending.json").delete()
    }
    fun child(name: String): File { require(name.matches(Regex("[a-zA-Z0-9_.-]+"))); return File(directory, name) }
}
