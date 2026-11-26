package dev.netpilot.data.logs

import android.system.Os
import dev.netpilot.core.model.*
import kotlinx.serialization.encodeToString
import java.io.File

class RuntimeLogger(private val directory: File, @Volatile var threshold: LogLevel = LogLevel.INFO) {
    private val current = File(directory, "runtime.jsonl")
    private val previous = File(directory, "runtime.previous.jsonl")
    init { directory.mkdirs(); runCatching { Os.chmod(directory.path, 448) } }
    @Synchronized fun log(level: LogLevel, source: String, message: String) {
        if (level.ordinal < threshold.ordinal) return
        if (current.length() > 512 * 1024) { previous.delete(); current.renameTo(previous) }
        val entry = LogEntry(System.currentTimeMillis(), level, source.take(64), message.take(4_096))
        current.appendText(NetPilotJson.encodeToString(entry) + "\n")
        runCatching { Os.chmod(current.path, 384) }
    }
    fun info(source: String, message: String) = log(LogLevel.INFO, source, message)
    fun warn(source: String, message: String) = log(LogLevel.WARN, source, message)
    fun error(source: String, message: String) = log(LogLevel.ERROR, source, message)
    @Synchronized fun entries(limit: Int = 250): List<LogEntry> {
        val entries = listOf(previous, current).flatMap { file ->
        if (file.exists()) file.useLines { lines -> lines.mapNotNull { line -> runCatching { NetPilotJson.decodeFromString<LogEntry>(line) }.getOrNull() }.toList() } else emptyList()
        }.takeLast(limit.coerceIn(1, 1000))
        var bytes = 0
        return entries.asReversed().takeWhile { entry -> bytes += NetPilotJson.encodeToString(entry).toByteArray().size; bytes < 220_000 }.asReversed()
    }
    @Synchronized fun export(): String = entries(1000).joinToString("\n") { entry -> "${java.time.Instant.ofEpochMilli(entry.timestamp)} [${entry.level}] ${entry.source}: ${entry.message}" }.takeLast(120_000)
    @Synchronized fun clear() { current.writeText(""); previous.delete(); info("logs", "用户已清空运行日志") }
}
