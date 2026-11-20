package dev.hostyn.calendar.ui.event

import android.content.Context
import dev.hostyn.calendar.R
import dev.hostyn.calendar.data.Freq
import dev.hostyn.calendar.data.Recurrence
import dev.hostyn.calendar.data.RepeatPreset
import dev.hostyn.calendar.data.Rule
import dev.hostyn.calendar.ui.components.longDate
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private fun dayName(day: DayOfWeek) = day.getDisplayName(TextStyle.FULL_STANDALONE, Locale.getDefault()).lowercase(Locale.getDefault())

private fun dayList(context: Context, days: List<DayOfWeek>): String {
    val names = days.map(::dayName)
    return if (names.size <= 1) names.joinToString() else context.getString(R.string.list_and, names.dropLast(1).joinToString(", "), names.last())
}

private fun ordinal(context: Context, n: Int): String {
    val words = context.resources.getStringArray(R.array.ordinals)
    return if (n < 0) words.last() else words.getOrElse(n - 1) { n.toString() }
}

private fun dayMonth(date: LocalDate): String =
    date.format(DateTimeFormatter.ofPattern(if (Locale.getDefault().language == "en") "MMMM d" else "d 'de' MMMM", Locale.getDefault()))

private val WEEKDAYS = listOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)

/** "Every week on Monday and Thursday, until 3 Dec" for an event that starts on [start]. */
fun describeRule(context: Context, rrule: String?, start: LocalDate): String {
    val rule = Rule.parse(rrule) ?: return context.getString(R.string.repeat_none)
    val res = context.resources
    val n = rule.interval
    val base = when (rule.freq) {
        Freq.DAILY -> if (n == 1) context.getString(R.string.repeat_daily) else res.getQuantityString(R.plurals.repeat_every_days, n, n)
        Freq.WEEKLY -> {
            val days = rule.byDay.map { it.day }.ifEmpty { listOf(start.dayOfWeek) }
                .sortedBy { (it.value - 1) }
            when {
                n == 1 && days == WEEKDAYS -> context.getString(R.string.repeat_weekdays)
                n == 1 -> context.getString(R.string.repeat_weekly_on, dayList(context, days))
                else -> res.getQuantityString(R.plurals.repeat_every_weeks_on, n, n, dayList(context, days))
            }
        }
        Freq.MONTHLY -> {
            val on = rule.byDay.firstOrNull()?.let { d ->
                context.getString(R.string.repeat_on_nth_weekday, ordinal(context, if (d.ordinal == 0) Recurrence.weekOfMonth(start) else d.ordinal), dayName(d.day))
            } ?: context.getString(R.string.repeat_on_day, rule.byMonthDay.firstOrNull() ?: start.dayOfMonth)
            if (n == 1) context.getString(R.string.repeat_monthly_on, on) else res.getQuantityString(R.plurals.repeat_every_months_on, n, n, on)
        }
        Freq.YEARLY -> if (n == 1) context.getString(R.string.repeat_yearly_on, dayMonth(start)) else res.getQuantityString(R.plurals.repeat_every_years, n, n)
    }
    val end = when {
        rule.count != null -> res.getQuantityString(R.plurals.repeat_times, rule.count, rule.count)
        rule.untilDate() != null -> context.getString(R.string.repeat_until, longDate(context, rule.untilDate()!!, abbreviated = true))
        else -> null
    }
    return listOfNotNull(base, end).joinToString(", ")
}

/** Label of a repeat option in the editor, for an event that starts on [start]. */
fun presetLabel(context: Context, preset: RepeatPreset, start: LocalDate): String =
    if (preset == RepeatPreset.None) context.getString(R.string.repeat_none)
    else describeRule(context, Recurrence.rruleFor(preset, start), start)
