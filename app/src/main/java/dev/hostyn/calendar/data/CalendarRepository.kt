package dev.hostyn.calendar.data

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Instances
import android.provider.CalendarContract.Reminders
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Calendars and events from Android's calendar provider, which every account on the phone
 * (Atlas, Google, Exchange…) syncs into. The app never talks to a server: the accounts' sync
 * adapters upload the changes made here.
 */
class CalendarRepository(private val context: Context, private val settings: AppSettings) {
    private val resolver get() = context.contentResolver

    private val _version = MutableStateFlow(0)

    /** Changes whenever an event or calendar changes, here or in a sync, or the filter changes. */
    val version: StateFlow<Int> = _version.asStateFlow()

    private var observing = false

    fun hasPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED

    /** Starts watching the provider; call once the permission is granted. */
    fun start() {
        if (observing || !hasPermission()) return
        observing = true
        resolver.registerContentObserver(
            CalendarContract.CONTENT_URI, true,
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) = invalidate()
            },
        )
        invalidate()
    }

    fun invalidate() { _version.value++ }

    // --- Reading ---

    fun calendars(): List<CalendarInfo> {
        if (!hasPermission()) return emptyList()
        val projection = arrayOf(
            Calendars._ID, Calendars.CALENDAR_DISPLAY_NAME, Calendars.CALENDAR_COLOR, Calendars.ACCOUNT_NAME,
            Calendars.ACCOUNT_TYPE, Calendars.CALENDAR_ACCESS_LEVEL, Calendars.IS_PRIMARY, Calendars.OWNER_ACCOUNT,
        )
        return resolver.query(Calendars.CONTENT_URI, projection, null, null, null)?.use { c ->
            buildList {
                while (c.moveToNext()) {
                    val accountName = c.string(3)
                    add(
                        CalendarInfo(
                            id = c.getLong(0),
                            name = c.string(1).ifEmpty { accountName },
                            color = c.getInt(2),
                            accountName = accountName,
                            accountType = c.string(4),
                            writable = c.getInt(5) >= Calendars.CAL_ACCESS_CONTRIBUTOR,
                            isPrimary = c.getInt(6) == 1 || (c.string(7).isNotEmpty() && c.string(7) == accountName),
                        ),
                    )
                }
            }.sortedWith(compareBy({ it.accountName.lowercase() }, { !it.isPrimary }, { it.name.lowercase() }))
        } ?: emptyList()
    }

    /** The calendar new events go to: the chosen one, or the first primary writable one. */
    fun defaultCalendar(calendars: List<CalendarInfo> = calendars()): CalendarInfo? {
        val writable = calendars.filter { it.writable }
        return writable.firstOrNull { it.id == settings.defaultCalendar }
            ?: writable.firstOrNull { it.isPrimary && it.id !in settings.hiddenCalendars }
            ?: writable.firstOrNull { it.id !in settings.hiddenCalendars }
            ?: writable.firstOrNull()
    }

    /**
     * Occurrences of the shown calendars' events on the days from [from] to [until] (exclusive),
     * sorted by day, all-day ones first.
     */
    fun instances(from: LocalDate, until: LocalDate, zone: ZoneId = ZoneId.systemDefault()): List<EventInstance> {
        if (!hasPermission()) return emptyList()
        // All-day events are stored in UTC: ask for a day more on each side, then keep the right days.
        val begin = from.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = until.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val uri = Instances.CONTENT_URI.buildUpon()
            .also { ContentUris.appendId(it, begin); ContentUris.appendId(it, end) }.build()
        val projection = arrayOf(
            Instances.EVENT_ID, Instances.TITLE, Instances.BEGIN, Instances.END, Instances.ALL_DAY,
            Instances.DISPLAY_COLOR, Instances.CALENDAR_ID, Instances.EVENT_LOCATION, Instances.RRULE,
            Instances.ORIGINAL_ID, Instances.RDATE,
        )
        val hidden = settings.hiddenCalendars
        val selection = buildString {
            append("(${Instances.STATUS} IS NULL OR ${Instances.STATUS} != ${Events.STATUS_CANCELED})")
            if (hidden.isNotEmpty()) append(" AND ${Instances.CALENDAR_ID} NOT IN (${hidden.joinToString(",")})")
        }
        val lastDay = until.minusDays(1)
        val list = resolver.query(uri, projection, selection, null, null)?.use { c ->
            buildList {
                while (c.moveToNext()) {
                    val instance = EventInstance(
                        eventId = c.getLong(0),
                        title = c.string(1),
                        begin = c.getLong(2),
                        end = c.getLong(3),
                        allDay = c.getInt(4) == 1,
                        color = c.getInt(5),
                        calendarId = c.getLong(6),
                        location = c.string(7),
                        recurring = c.string(8).isNotEmpty() || !c.isNull(9) || c.string(10).isNotEmpty(),
                    )
                    if (instance.startDate(zone) <= lastDay && instance.endDate(zone) >= from) add(instance)
                }
            }
        } ?: emptyList()
        return list.sortedWith(compareBy({ it.startDate(zone) }, { !it.allDay }, { it.begin }, { -it.end }, { it.title.lowercase() }))
    }

    fun event(id: Long): EventDetails? {
        if (!hasPermission()) return null
        val projection = arrayOf(
            Events.CALENDAR_ID, Events.TITLE, Events.EVENT_LOCATION, Events.DESCRIPTION, Events.DISPLAY_COLOR,
            Events.ALL_DAY, Events.DTSTART, Events.DTEND, Events.DURATION, Events.EVENT_TIMEZONE, Events.RRULE,
            Events.ORIGINAL_ID, Events.ORGANIZER, Events.CALENDAR_DISPLAY_NAME, Events.CALENDAR_COLOR,
            Events.CALENDAR_ACCESS_LEVEL,
        )
        val details = resolver.query(ContentUris.withAppendedId(Events.CONTENT_URI, id), projection, null, null, null)?.use { c ->
            if (!c.moveToFirst()) return@use null
            val allDay = c.getInt(5) == 1
            val start = c.getLong(6)
            val duration = if (!c.isNull(7)) c.getLong(7) - start else Durations.parse(c.string(8)) ?: if (allDay) 86_400_000L else 0L
            EventDetails(
                id = id,
                calendarId = c.getLong(0),
                title = c.string(1),
                location = c.string(2),
                description = c.string(3),
                color = c.getInt(4),
                allDay = allDay,
                dtStart = start,
                durationMillis = duration.coerceAtLeast(0),
                timeZone = c.string(9).ifEmpty { if (allDay) "UTC" else ZoneId.systemDefault().id },
                rrule = c.string(10).ifEmpty { null },
                originalId = if (c.isNull(11)) null else c.getLong(11),
                organizer = c.string(12),
                calendarName = c.string(13),
                calendarColor = c.getInt(14),
                writable = c.getInt(15) >= Calendars.CAL_ACCESS_CONTRIBUTOR,
                reminders = emptyList(),
                attendees = emptyList(),
            )
        } ?: return null
        return details.copy(reminders = reminders(id), attendees = attendees(id))
    }

    private fun reminders(eventId: Long): List<Int> =
        resolver.query(
            Reminders.CONTENT_URI, arrayOf(Reminders.MINUTES, Reminders.METHOD),
            "${Reminders.EVENT_ID} = ?", arrayOf(eventId.toString()), null,
        )?.use { c ->
            buildList {
                while (c.moveToNext()) {
                    // Only alerts on the phone; e-mail and SMS reminders are the server's business.
                    val method = c.getInt(1)
                    if (method == Reminders.METHOD_ALERT || method == Reminders.METHOD_DEFAULT) add(c.getInt(0))
                }
            }.map { if (it < 0) 10 else it }.distinct().sorted()
        } ?: emptyList()

    private fun attendees(eventId: Long): List<Attendee> =
        resolver.query(
            Attendees.CONTENT_URI, arrayOf(Attendees.ATTENDEE_NAME, Attendees.ATTENDEE_EMAIL, Attendees.ATTENDEE_STATUS),
            "${Attendees.EVENT_ID} = ?", arrayOf(eventId.toString()), null,
        )?.use { c ->
            buildList { while (c.moveToNext()) add(Attendee(c.string(0), c.string(1), c.getInt(2))) }
        } ?: emptyList()

    // --- Writing ---

    /**
     * Saves [draft]. Without [existing] it is a new event; otherwise it changes [existing], and for
     * a repeating event the occurrence starting at [instanceBegin] and the ones [scope] says.
     * Returns the id of the event that holds the change.
     */
    fun save(draft: EventDraft, existing: EventDetails? = null, instanceBegin: Long? = null, scope: Scope = Scope.All): Long {
        if (existing == null) return insert(draft)
        val series = existing.recurring && existing.originalId == null
        if (!series || instanceBegin == null) {
            update(existing.id, draft)
            return existing.id
        }
        val first = instanceBegin == existing.dtStart
        return when {
            scope == Scope.This -> insertException(existing, instanceBegin, exceptionValues(draft), draft.reminders)
            scope == Scope.Following && !first -> {
                endSeries(existing, instanceBegin)
                insert(draft.copy(rrule = draft.rrule?.let { r -> Rule.parse(r)?.copy(count = null)?.toString() ?: r }))
            }
            else -> {
                // The whole series moves by as much as this occurrence did.
                update(existing.id, shiftToSeries(draft, existing, instanceBegin))
                existing.id
            }
        }
    }

    /** Deletes [event]; for a repeating one, the occurrence at [instanceBegin] and the ones [scope] says. */
    fun delete(event: EventDetails, instanceBegin: Long?, scope: Scope) {
        when {
            event.originalId != null -> {
                // A changed occurrence: deleting the row would bring the original one back.
                resolver.update(eventUri(event.id), ContentValues().apply { put(Events.STATUS, Events.STATUS_CANCELED) }, null, null)
            }
            !event.recurring || instanceBegin == null || scope == Scope.All -> resolver.delete(eventUri(event.id), null, null)
            scope == Scope.This -> insertException(
                event, instanceBegin, ContentValues().apply { put(Events.STATUS, Events.STATUS_CANCELED) }, null,
            )
            instanceBegin == event.dtStart -> resolver.delete(eventUri(event.id), null, null)
            else -> endSeries(event, instanceBegin)
        }
    }

    private fun eventUri(id: Long) = ContentUris.withAppendedId(Events.CONTENT_URI, id)

    private fun insert(draft: EventDraft): Long {
        val uri = resolver.insert(Events.CONTENT_URI, values(draft).apply { put(Events.CALENDAR_ID, draft.calendarId) })
            ?: error("Event not saved")
        val id = ContentUris.parseId(uri)
        setReminders(id, draft.reminders)
        return id
    }

    private fun update(id: Long, draft: EventDraft) {
        resolver.update(eventUri(id), values(draft), null, null)
        setReminders(id, draft.reminders)
    }

    /** Stops [event]'s series right before the occurrence at [instanceBegin]. */
    private fun endSeries(event: EventDetails, instanceBegin: Long) {
        val rule = Recurrence.endBefore(event.rrule ?: return, instanceBegin, event.allDay)
        // The provider only expands the occurrences again when it gets the whole timing, not just the rule.
        val values = ContentValues().apply {
            put(Events.RRULE, rule)
            put(Events.DTSTART, event.dtStart)
            put(Events.DURATION, Durations.format(event.durationMillis, event.allDay))
            put(Events.ALL_DAY, if (event.allDay) 1 else 0)
            put(Events.EVENT_TIMEZONE, event.timeZone)
        }
        resolver.update(eventUri(event.id), values, null, null)
    }

    /** Adds an exception to [series] for its occurrence at [instanceBegin] (changed or cancelled). */
    private fun insertException(series: EventDetails, instanceBegin: Long, values: ContentValues, reminders: List<Int>?): Long {
        values.put(Events.ORIGINAL_INSTANCE_TIME, instanceBegin)
        if (!values.containsKey(Events.STATUS)) values.put(Events.STATUS, Events.STATUS_CONFIRMED)
        val uri = resolver.insert(ContentUris.withAppendedId(Events.CONTENT_EXCEPTION_URI, series.id), values)
            ?: error("Exception not saved")
        val id = ContentUris.parseId(uri)
        if (reminders != null) setReminders(id, reminders)
        return id
    }

    private fun setReminders(eventId: Long, minutes: List<Int>) {
        resolver.delete(Reminders.CONTENT_URI, "${Reminders.EVENT_ID} = ?", arrayOf(eventId.toString()))
        minutes.distinct().forEach { m ->
            resolver.insert(
                Reminders.CONTENT_URI,
                ContentValues().apply {
                    put(Reminders.EVENT_ID, eventId)
                    put(Reminders.MINUTES, m)
                    put(Reminders.METHOD, Reminders.METHOD_ALERT)
                },
            )
        }
        resolver.update(eventUri(eventId), ContentValues().apply { put(Events.HAS_ALARM, if (minutes.isEmpty()) 0 else 1) }, null, null)
    }

    companion object {
        /** The provider's columns for [draft], without the calendar. */
        fun values(draft: EventDraft): ContentValues = ContentValues().apply {
            val (start, end) = draftMillis(draft)
            put(Events.TITLE, draft.title.trim())
            put(Events.EVENT_LOCATION, draft.location.trim())
            put(Events.DESCRIPTION, draft.description.trim())
            put(Events.ALL_DAY, if (draft.allDay) 1 else 0)
            put(Events.EVENT_TIMEZONE, if (draft.allDay) "UTC" else draft.zone.id)
            put(Events.DTSTART, start)
            if (draft.rrule.isNullOrEmpty()) {
                // A single event has an end; a repeating one a length instead.
                put(Events.DTEND, end)
                putNull(Events.DURATION)
                putNull(Events.RRULE)
            } else {
                putNull(Events.DTEND)
                put(Events.DURATION, Durations.format(end - start, draft.allDay))
                put(Events.RRULE, draft.rrule)
            }
        }

        /**
         * Columns for a changed occurrence of a series. The provider copies the rest from the series
         * and refuses an end (DTEND) or a rule: the occurrence's length goes in DURATION.
         */
        fun exceptionValues(draft: EventDraft): ContentValues = ContentValues().apply {
            val (start, end) = draftMillis(draft)
            put(Events.TITLE, draft.title.trim())
            put(Events.EVENT_LOCATION, draft.location.trim())
            put(Events.DESCRIPTION, draft.description.trim())
            put(Events.ALL_DAY, if (draft.allDay) 1 else 0)
            put(Events.EVENT_TIMEZONE, if (draft.allDay) "UTC" else draft.zone.id)
            put(Events.DTSTART, start)
            put(Events.DURATION, Durations.format(end - start, draft.allDay))
        }

        /** Start and end of [draft] in UTC millis, as the provider stores them. */
        fun draftMillis(draft: EventDraft): Pair<Long, Long> = if (draft.allDay) {
            val first = draft.start.toLocalDate()
            val last = maxOf(first, draft.end.toLocalDate())
            first.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() to
                last.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        } else {
            val start = draft.start.atZone(draft.zone).toInstant().toEpochMilli()
            start to maxOf(start, draft.end.atZone(draft.zone).toInstant().toEpochMilli())
        }

        /**
         * [draft], which was edited from the occurrence starting at [instanceBegin], moved to the
         * start of [series]: the series keeps its first day and moves by what the occurrence moved.
         */
        fun shiftToSeries(draft: EventDraft, series: EventDetails, instanceBegin: Long): EventDraft {
            val zone = if (series.allDay) ZoneOffset.UTC else draft.zone
            val instanceStart = LocalDateTime.ofInstant(Instant.ofEpochMilli(instanceBegin), if (series.allDay) ZoneOffset.UTC else draft.zone)
            val seriesStart = LocalDateTime.ofInstant(Instant.ofEpochMilli(series.dtStart), zone)
            val shift = Duration.between(instanceStart, draft.start)
            val length = Duration.between(draft.start, draft.end)
            val start = seriesStart.plus(shift)
            return draft.copy(start = start, end = start.plus(length))
        }
    }
}

private fun Cursor.string(i: Int): String = if (isNull(i)) "" else getString(i) ?: ""

/** Uri that shows an event in this app from other apps. */
fun eventUri(id: Long): Uri = ContentUris.withAppendedId(Events.CONTENT_URI, id)
