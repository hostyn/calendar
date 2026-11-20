package dev.hostyn.calendar.data

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** A weekday in a rule, maybe with its place in the month ("2SA": second Saturday, "-1FR": last Friday). */
data class WeekdayNum(val day: DayOfWeek, val ordinal: Int = 0) {
    override fun toString() = (if (ordinal != 0) ordinal.toString() else "") + CODES.getValue(day)

    companion object {
        val CODES = mapOf(
            DayOfWeek.MONDAY to "MO", DayOfWeek.TUESDAY to "TU", DayOfWeek.WEDNESDAY to "WE",
            DayOfWeek.THURSDAY to "TH", DayOfWeek.FRIDAY to "FR", DayOfWeek.SATURDAY to "SA", DayOfWeek.SUNDAY to "SU",
        )

        fun parse(s: String): WeekdayNum? {
            val code = s.takeLast(2).uppercase()
            val day = CODES.entries.firstOrNull { it.value == code }?.key ?: return null
            val ordinal = s.dropLast(2).takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 0
            return WeekdayNum(day, ordinal)
        }
    }
}

enum class Freq { DAILY, WEEKLY, MONTHLY, YEARLY }

/**
 * The parts of an RFC 5545 RRULE the app understands. Anything else is kept in [other] so that
 * changing the end of a rule doesn't lose it.
 */
data class Rule(
    val freq: Freq,
    val interval: Int = 1,
    val byDay: List<WeekdayNum> = emptyList(),
    val byMonthDay: List<Int> = emptyList(),
    val count: Int? = null,
    val until: String? = null,
    val other: List<Pair<String, String>> = emptyList(),
) {
    override fun toString(): String = buildList {
        add("FREQ=$freq")
        if (interval > 1) add("INTERVAL=$interval")
        if (byDay.isNotEmpty()) add("BYDAY=" + byDay.joinToString(","))
        if (byMonthDay.isNotEmpty()) add("BYMONTHDAY=" + byMonthDay.joinToString(","))
        other.forEach { (k, v) -> add("$k=$v") }
        count?.let { add("COUNT=$it") }
        until?.let { add("UNTIL=$it") }
    }.joinToString(";")

    /** Whether nothing but the parts above is in the rule, so the app can describe it fully. */
    val simple get() = other.isEmpty()

    /** The last day of the series, if it has an UNTIL. */
    fun untilDate(): LocalDate? = until?.let { u ->
        runCatching { LocalDate.parse(u.take(8), DateTimeFormatter.BASIC_ISO_DATE) }.getOrNull()
    }

    companion object {
        fun parse(rrule: String?): Rule? {
            if (rrule.isNullOrBlank()) return null
            val parts = rrule.removePrefix("RRULE:").split(';').mapNotNull {
                val i = it.indexOf('=')
                if (i <= 0) null else it.substring(0, i).uppercase() to it.substring(i + 1)
            }
            val freq = parts.firstOrNull { it.first == "FREQ" }?.second
                ?.let { f -> Freq.entries.firstOrNull { it.name == f.uppercase() } } ?: return null
            var rule = Rule(freq)
            val other = mutableListOf<Pair<String, String>>()
            for ((k, v) in parts) when (k) {
                "FREQ" -> {}
                "INTERVAL" -> rule = rule.copy(interval = v.toIntOrNull()?.coerceAtLeast(1) ?: 1)
                "BYDAY" -> rule = rule.copy(byDay = v.split(',').mapNotNull { WeekdayNum.parse(it.trim()) })
                "BYMONTHDAY" -> rule = rule.copy(byMonthDay = v.split(',').mapNotNull { it.trim().toIntOrNull() })
                "COUNT" -> rule = rule.copy(count = v.toIntOrNull())
                "UNTIL" -> rule = rule.copy(until = v)
                // WKST only changes which week a rule starts counting from; it is harmless to keep.
                else -> other += k to v
            }
            return rule.copy(other = other)
        }
    }
}

/** The repeat options the editor offers, for an event starting on a given day. */
enum class RepeatPreset { None, Daily, Weekdays, Weekly, MonthlyByDay, MonthlyByWeekday, Yearly }

object Recurrence {
    private val WEEKDAYS = listOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)

    /** Which week of the month [date] falls in: 1 to 4, or -1 when it is the last one (5th). */
    fun weekOfMonth(date: LocalDate): Int {
        val n = (date.dayOfMonth - 1) / 7 + 1
        return if (n >= 5) -1 else n
    }

    fun rruleFor(preset: RepeatPreset, start: LocalDate): String? = when (preset) {
        RepeatPreset.None -> null
        RepeatPreset.Daily -> Rule(Freq.DAILY).toString()
        RepeatPreset.Weekdays -> Rule(Freq.WEEKLY, byDay = WEEKDAYS.map { WeekdayNum(it) }).toString()
        RepeatPreset.Weekly -> Rule(Freq.WEEKLY, byDay = listOf(WeekdayNum(start.dayOfWeek))).toString()
        RepeatPreset.MonthlyByDay -> Rule(Freq.MONTHLY, byMonthDay = listOf(start.dayOfMonth)).toString()
        RepeatPreset.MonthlyByWeekday -> Rule(Freq.MONTHLY, byDay = listOf(WeekdayNum(start.dayOfWeek, weekOfMonth(start)))).toString()
        RepeatPreset.Yearly -> Rule(Freq.YEARLY).toString()
    }

    /** The preset [rrule] is, for an event starting on [start], or null if it is something else. */
    fun presetOf(rrule: String?, start: LocalDate): RepeatPreset? {
        // The end of the series (COUNT, UNTIL) doesn't change which preset it is.
        val rule = Rule.parse(rrule)?.copy(count = null, until = null)
            ?: return if (rrule.isNullOrBlank()) RepeatPreset.None else null
        if (!rule.simple || rule.interval != 1) return null
        return RepeatPreset.entries.firstOrNull { p ->
            p != RepeatPreset.None && Rule.parse(rruleFor(p, start)) == rule
        } ?: when {
            // Rules written by other apps without the day spelled out mean the same thing.
            rule.freq == Freq.WEEKLY && rule.byDay.isEmpty() && rule.byMonthDay.isEmpty() -> RepeatPreset.Weekly
            rule.freq == Freq.MONTHLY && rule.byDay.isEmpty() && rule.byMonthDay.isEmpty() -> RepeatPreset.MonthlyByDay
            rule.freq == Freq.DAILY && rule.byDay.isEmpty() && rule.byMonthDay.isEmpty() -> RepeatPreset.Daily
            rule.freq == Freq.YEARLY && rule.byDay.isEmpty() && rule.byMonthDay.isEmpty() -> RepeatPreset.Yearly
            else -> null
        }
    }

    /**
     * [rrule] ending right before the occurrence that starts at [instanceBegin], for "change this
     * and the following ones": the old series keeps the earlier occurrences.
     */
    fun endBefore(rrule: String, instanceBegin: Long, allDay: Boolean): String {
        val rule = Rule.parse(rrule) ?: return rrule
        val until = if (allDay) {
            Instant.ofEpochMilli(instanceBegin).atZone(ZoneOffset.UTC).toLocalDate().minusDays(1)
                .format(DateTimeFormatter.BASIC_ISO_DATE)
        } else {
            UNTIL_FORMAT.format(Instant.ofEpochMilli(instanceBegin - 1000))
        }
        return rule.copy(count = null, until = until).toString()
    }

    /** [rrule] without its end (COUNT/UNTIL), for the part of a series that continues. */
    fun withoutEnd(rrule: String): String = Rule.parse(rrule)?.copy(count = null, until = null)?.toString() ?: rrule

    private val UNTIL_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
}
