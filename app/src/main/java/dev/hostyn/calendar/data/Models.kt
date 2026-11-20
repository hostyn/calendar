package dev.hostyn.calendar.data

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/** A calendar from an account on the phone (Atlas, Google, local…). */
data class CalendarInfo(
    val id: Long,
    val name: String,
    val color: Int,
    val accountName: String,
    val accountType: String,
    /** The user can add and change events in it (not a read-only feed like birthdays). */
    val writable: Boolean,
    val isPrimary: Boolean,
)

/** One occurrence of an event, as the month and agenda show it. */
data class EventInstance(
    val eventId: Long,
    val title: String,
    /** Start and end in UTC millis. For all-day events they are UTC midnights. */
    val begin: Long,
    val end: Long,
    val allDay: Boolean,
    val color: Int,
    val calendarId: Long,
    val location: String,
    val recurring: Boolean,
) {
    /** First day it shows on, in [zone]. */
    fun startDate(zone: ZoneId): LocalDate =
        if (allDay) Instant.ofEpochMilli(begin).atZone(ZoneOffset.UTC).toLocalDate()
        else Instant.ofEpochMilli(begin).atZone(zone).toLocalDate()

    /** Last day it shows on, in [zone] (the end is exclusive). */
    fun endDate(zone: ZoneId): LocalDate {
        val start = startDate(zone)
        val last = if (allDay) Instant.ofEpochMilli(end).atZone(ZoneOffset.UTC).toLocalDate().minusDays(1)
        else Instant.ofEpochMilli(maxOf(begin, end - 1)).atZone(zone).toLocalDate()
        return if (last < start) start else last
    }

    fun occursOn(day: LocalDate, zone: ZoneId) = day >= startDate(zone) && day <= endDate(zone)

    /** Stable key for lists and transitions. */
    val key get() = "$eventId-$begin"
}

/** Everything about an event, for its details screen and the editor. */
data class EventDetails(
    val id: Long,
    val calendarId: Long,
    val calendarName: String,
    val calendarColor: Int,
    val writable: Boolean,
    val title: String,
    val location: String,
    val description: String,
    val color: Int,
    val allDay: Boolean,
    /** Start of the series (or of the event) in UTC millis. */
    val dtStart: Long,
    /** Length of each occurrence in millis. */
    val durationMillis: Long,
    val timeZone: String,
    val rrule: String?,
    /** Set when this event is a changed occurrence of [originalId]'s series. */
    val originalId: Long?,
    val reminders: List<Int>,
    val attendees: List<Attendee>,
    val organizer: String,
) {
    val recurring get() = !rrule.isNullOrEmpty()
}

data class Attendee(val name: String, val email: String, val status: Int)

/** Which occurrences a change to a repeating event applies to. */
enum class Scope { This, Following, All }

/**
 * An event being created or edited. Dates and times are local to [zone]; all-day events only use
 * the dates.
 */
data class EventDraft(
    val calendarId: Long,
    val title: String = "",
    val location: String = "",
    val description: String = "",
    val allDay: Boolean = false,
    val start: LocalDateTime,
    val end: LocalDateTime,
    val zone: ZoneId = ZoneId.systemDefault(),
    val rrule: String? = null,
    val reminders: List<Int> = emptyList(),
)
