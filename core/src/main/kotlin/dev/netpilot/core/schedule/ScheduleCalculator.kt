package dev.netpilot.core.schedule

import dev.netpilot.core.model.ScheduleDocument
import dev.netpilot.core.model.ScheduleSlot
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

data class ScheduleTransition(val instant: Instant, val turnOn: Boolean, val label: String)
data class ScheduleEvaluation(val active: Boolean, val activeLabels: List<String>, val next: ScheduleTransition?)

/** Calendar based: weekdays belong to the start date, including overnight intervals. */
object ScheduleCalculator {
    private data class Window(val start: Instant, val end: Instant, val label: String)

    fun validate(document: ScheduleDocument): String? {
        if (document.schemaVersion != 1) return "不支持的时间计划版本"
        if (document.slots.size > 20) return "最多支持 20 个时间段"
        if (document.slots.map { it.id }.distinct().size != document.slots.size) return "时间段 ID 重复"
        for (slot in document.slots) {
            if (slot.id.isBlank() || slot.id.length > 100) return "时间段 ID 无效"
            if (slot.label.isBlank() || slot.label.length > 32) return "时间段名称须为 1–32 个字符"
            if (slot.startMinute !in 0..1439 || slot.endMinute !in 0..1439) return "时间超出范围"
            if (slot.startMinute == slot.endMinute) return "开始和结束时间不能相同"
            if (slot.days.isEmpty() || slot.days.any { it !in 1..7 }) return "请至少选择一个有效星期"
        }
        val windows = document.slots.filter { it.enabled }.flatMap { slot ->
            slot.days.flatMap { day ->
                val start = (day - 1) * 1440 + slot.startMinute
                val end = (day - 1) * 1440 + slot.endMinute + if (slot.crossesMidnight) 1440 else 0
                listOf(Triple(start, end, slot), Triple(start - 10080, end - 10080, slot))
            }
        }
        for (i in windows.indices) for (j in i + 1 until windows.size) {
            val a = windows[i]; val b = windows[j]
            if (a.third.id != b.third.id && a.first < b.second && b.first < a.second) {
                return "“${a.third.label}”与“${b.third.label}”时间重叠，请调整时间或星期"
            }
        }
        return null
    }

    fun evaluate(slots: List<ScheduleSlot>, now: Instant, zone: ZoneId): ScheduleEvaluation {
        val date = now.atZone(zone).toLocalDate()
        val windows = (-1L..8L).flatMap { delta ->
            val startDate = date.plusDays(delta)
            slots.filter { it.enabled && startDate.dayOfWeek.value in it.days }.map { slot ->
                val endDate = if (slot.crossesMidnight) startDate.plusDays(1) else startDate
                Window(resolve(startDate, slot.startMinute, zone, false), resolve(endDate, slot.endMinute, zone, true), slot.label)
            }
        }.filter { it.end.isAfter(it.start) }
        val active = windows.filter { !now.isBefore(it.start) && now.isBefore(it.end) }
        val boundaries = windows.flatMap { listOf(it.start, it.end) }.filter { it.isAfter(now) }.distinct().sorted()
        val next = boundaries.firstNotNullOfOrNull { boundary ->
            val before = windows.any { !boundary.minusNanos(1).isBefore(it.start) && boundary.minusNanos(1).isBefore(it.end) }
            val after = windows.any { !boundary.isBefore(it.start) && boundary.isBefore(it.end) }
            if (before == after) null else ScheduleTransition(boundary, after, windows.firstOrNull { if (after) it.start == boundary else it.end == boundary }?.label ?: "时间计划")
        }
        return ScheduleEvaluation(active.isNotEmpty(), active.map { it.label }, next)
    }

    private fun resolve(date: LocalDate, minute: Int, zone: ZoneId, end: Boolean): Instant {
        val local = LocalDateTime.of(date, LocalTime.of(minute / 60, minute % 60))
        val offsets = zone.rules.getValidOffsets(local)
        // Gaps move forward; an overlap uses the earliest start and latest end.
        return if (offsets.size > 1) local.toInstant(if (end) offsets.last() else offsets.first()) else local.atZone(zone).toInstant()
    }
}
