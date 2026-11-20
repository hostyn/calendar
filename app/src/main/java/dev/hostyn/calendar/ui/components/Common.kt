@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package dev.hostyn.calendar.ui.components

import android.content.Context
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.util.Patterns
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import dev.hostyn.calendar.R
import dev.hostyn.calendar.data.EventInstance
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Formatter
import java.util.Locale

// --- Dates and times ---

/** "Today", "Tomorrow", "Yesterday" or "Monday, 17 Nov" (with the year when it isn't this one). */
fun dayTitle(context: Context, date: LocalDate, today: LocalDate = LocalDate.now()): String = when (date) {
    today -> context.getString(R.string.today)
    today.plusDays(1) -> context.getString(R.string.tomorrow)
    today.minusDays(1) -> context.getString(R.string.yesterday)
    else -> longDate(context, date, abbreviated = true)
}

/** "Saturday, 14 November" (with the year when it isn't this one). */
fun longDate(context: Context, date: LocalDate, abbreviated: Boolean = false, capitalize: Boolean = true): String {
    val millis = date.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    var flags = DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_SHOW_DATE
    flags = flags or if (date.year == LocalDate.now().year) DateUtils.FORMAT_NO_YEAR else DateUtils.FORMAT_SHOW_YEAR
    if (abbreviated) flags = flags or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_ABBREV_WEEKDAY
    val text = DateUtils.formatDateTime(context, millis, flags)
    return if (capitalize) text.capitalized() else text
}

/** The app's language, read so that text follows a change of it. */
@Composable
@ReadOnlyComposable
fun currentLocale(): Locale = LocalConfiguration.current.locales[0]

fun formatTime(context: Context, millis: Long): String = DateFormat.getTimeFormat(context).format(millis)

/**
 * When an event happens, in full: "10:00 – 11:00" on its day, "All day", "14 – 16 Nov" for
 * several days. Used where the day is already known (notifications show it next to the title).
 */
fun formatEventTime(context: Context, begin: Long, end: Long, allDay: Boolean): String {
    if (allDay) {
        val days = ((end - begin) / 86_400_000L).coerceAtLeast(1)
        if (days == 1L) return context.getString(R.string.all_day)
        return DateUtils.formatDateRange(
            context, Formatter(StringBuilder(), Locale.getDefault()), begin, end,
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_NO_YEAR, "UTC",
        ).toString()
    }
    val zone = ZoneId.systemDefault()
    val sameDay = Instant.ofEpochMilli(begin).atZone(zone).toLocalDate() == Instant.ofEpochMilli(maxOf(begin, end - 1)).atZone(zone).toLocalDate()
    var flags = DateUtils.FORMAT_SHOW_TIME
    if (!sameDay) flags = flags or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_NO_YEAR
    return DateUtils.formatDateRange(context, begin, end, flags)
}

/** When [event] happens on [date], one of the days it spans: "10:00 – 11:00", "Until 12:00", "All day". */
fun formatEventTimeOn(context: Context, event: EventInstance, date: LocalDate): String {
    if (event.allDay) return context.getString(R.string.all_day)
    val zone = ZoneId.systemDefault()
    val first = event.startDate(zone)
    val last = event.endDate(zone)
    return when {
        first == last -> if (event.begin == event.end) formatTime(context, event.begin) else DateUtils.formatDateRange(context, event.begin, event.end, DateUtils.FORMAT_SHOW_TIME)
        date == first -> context.getString(R.string.from_time, formatTime(context, event.begin))
        date == last -> context.getString(R.string.until_time, formatTime(context, event.end))
        else -> context.getString(R.string.all_day)
    }
}

/** The date an all-day event's UTC millis stand for. */
fun utcDate(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()

/** "10 minutes before", "1 day before at 9:00"… for a reminder [minutes] before the start. */
fun reminderLabel(context: Context, minutes: Int, allDay: Boolean): String {
    val res = context.resources
    if (allDay) {
        if (minutes <= 0) return context.getString(R.string.reminder_on_day)
        // All-day events start at midnight: count whole days back, then the time on that day.
        val days = (minutes + 1439) / 1440
        val atMinute = days * 1440 - minutes
        val time = DateFormat.getTimeFormat(context).format(
            LocalDate.now().atStartOfDay(ZoneId.systemDefault()).plusMinutes(atMinute.toLong()).toInstant().toEpochMilli(),
        )
        return res.getQuantityString(R.plurals.reminder_days_before_at, days, days, time)
    }
    return when {
        minutes == 0 -> context.getString(R.string.reminder_at_start)
        minutes % 10080 == 0 -> res.getQuantityString(R.plurals.reminder_weeks, minutes / 10080, minutes / 10080)
        minutes % 1440 == 0 -> res.getQuantityString(R.plurals.reminder_days, minutes / 1440, minutes / 1440)
        minutes % 60 == 0 -> res.getQuantityString(R.plurals.reminder_hours, minutes / 60, minutes / 60)
        else -> res.getQuantityString(R.plurals.reminder_minutes, minutes, minutes)
    }
}

/** Reminders the editor offers. All-day ones are counted back from midnight: the day before at 9:00… */
val TIMED_REMINDERS = listOf(0, 5, 10, 15, 30, 60, 120, 1440, 2880, 10080)
val ALL_DAY_REMINDERS = listOf(900, 360, 2340, 9540)

/** Reminder for new all-day events: the day before at 9:00. */
const val ALL_DAY_DEFAULT_REMINDER = 900

fun String.capitalized(): String = replaceFirstChar { it.titlecase(Locale.getDefault()) }

// --- Colors ---

/** Text color that reads well on [background] (calendar colors can be light or dark). */
fun contentColorOn(background: Color): Color = if (background.luminance() > 0.4f) Color(0xFF1B1B1F) else Color.White

// --- Text ---

/** Text with tappable web links, emails and phone numbers. */
fun linkify(text: String, styles: TextLinkStyles): AnnotatedString {
    data class Link(val start: Int, val end: Int, val url: String)
    val links = mutableListOf<Link>()
    fun scan(pattern: java.util.regex.Pattern, toUrl: (String) -> String?) {
        val m = pattern.matcher(text)
        while (m.find()) {
            if (links.any { m.start() < it.end && m.end() > it.start }) continue
            val url = toUrl(m.group()) ?: continue
            links += Link(m.start(), m.end(), url)
        }
    }
    scan(Patterns.EMAIL_ADDRESS) { "mailto:$it" }
    scan(Patterns.WEB_URL) { if (it.startsWith("http", true)) it else if ('.' in it && it.length > 4) "https://$it" else null }
    scan(Patterns.PHONE) { phone ->
        val digits = phone.filter { it.isDigit() }
        if (digits.length < 6) null else "tel:${phone.filter { c -> c.isDigit() || c == '+' }}"
    }
    if (links.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text)
        links.forEach { addLink(LinkAnnotation.Url(it.url, styles), it.start, it.end) }
    }
}

fun linkStyles(color: Color) = TextLinkStyles(style = SpanStyle(color = color, textDecoration = TextDecoration.Underline))

// --- Composables ---

@Composable
fun EmptyState(icon: ImageVector, title: String, body: String, modifier: Modifier = Modifier) {
    Column(
        modifier.padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // An expressive shape draws the eye to the empty state's icon.
        Surface(shape = MaterialShapes.Cookie9Sided.toShape(), color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(104.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(40.dp))
            }
        }
        Spacer(Modifier.height(20.dp))
        Text(title, style = MaterialTheme.typography.titleLargeEmphasized, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}
