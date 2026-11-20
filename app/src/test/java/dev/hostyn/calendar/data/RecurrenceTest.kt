package dev.hostyn.calendar.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class RecurrenceTest {
    private val saturday = LocalDate.of(2026, 11, 14)

    @Test fun parsesAndWritesRules() {
        val rule = Rule.parse("FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,TH;WKST=MO;UNTIL=20261231T225959Z")!!
        assertEquals(Freq.WEEKLY, rule.freq)
        assertEquals(2, rule.interval)
        assertEquals(listOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY), rule.byDay.map { it.day })
        assertEquals(LocalDate.of(2026, 12, 31), rule.untilDate())
        assertEquals("FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,TH;WKST=MO;UNTIL=20261231T225959Z", rule.toString())
    }

    @Test fun ordinalWeekdays() {
        assertEquals(WeekdayNum(DayOfWeek.SATURDAY, 2), WeekdayNum.parse("2SA"))
        assertEquals(WeekdayNum(DayOfWeek.FRIDAY, -1), WeekdayNum.parse("-1FR"))
        assertEquals(2, Recurrence.weekOfMonth(saturday))
        assertEquals(-1, Recurrence.weekOfMonth(LocalDate.of(2026, 11, 29)))
    }

    @Test fun presets() {
        assertEquals("FREQ=WEEKLY;BYDAY=SA", Recurrence.rruleFor(RepeatPreset.Weekly, saturday))
        assertEquals("FREQ=MONTHLY;BYDAY=2SA", Recurrence.rruleFor(RepeatPreset.MonthlyByWeekday, saturday))
        assertEquals("FREQ=MONTHLY;BYMONTHDAY=14", Recurrence.rruleFor(RepeatPreset.MonthlyByDay, saturday))
        assertNull(Recurrence.rruleFor(RepeatPreset.None, saturday))
        for (p in RepeatPreset.entries) assertEquals(p, Recurrence.presetOf(Recurrence.rruleFor(p, saturday), saturday))
    }

    @Test fun presetsOfRulesFromOtherApps() {
        assertEquals(RepeatPreset.Weekly, Recurrence.presetOf("FREQ=WEEKLY", saturday))
        assertEquals(RepeatPreset.Yearly, Recurrence.presetOf("FREQ=YEARLY;COUNT=5", saturday))
        assertNull(Recurrence.presetOf("FREQ=WEEKLY;INTERVAL=2", saturday))
        assertNull(Recurrence.presetOf("FREQ=WEEKLY;BYDAY=TU,TH", saturday))
    }

    @Test fun endsBeforeAnOccurrence() {
        val begin = LocalDateTime.of(2026, 11, 20, 10, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        assertEquals("FREQ=WEEKLY;BYDAY=FR;UNTIL=20261120T095959Z", Recurrence.endBefore("FREQ=WEEKLY;BYDAY=FR;COUNT=10", begin, false))
        val day = LocalDate.of(2026, 11, 20).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        assertEquals("FREQ=YEARLY;UNTIL=20261119", Recurrence.endBefore("FREQ=YEARLY", day, true))
    }

    @Test fun durations() {
        assertEquals(3_600_000L, Durations.parse("P3600S"))
        assertEquals(5_400_000L, Durations.parse("PT1H30M"))
        assertEquals(86_400_000L, Durations.parse("P1D"))
        assertEquals(7 * 86_400_000L, Durations.parse("P1W"))
        assertNull(Durations.parse("nonsense"))
        assertEquals("P2D", Durations.format(2 * 86_400_000L, allDay = true))
        assertEquals("P1800S", Durations.format(1_800_000L, allDay = false))
    }

    @Test fun shiftsTheSeriesLikeTheOccurrence() {
        val zone = ZoneId.of("Europe/Madrid")
        val seriesStart = LocalDateTime.of(2026, 11, 3, 18, 0).atZone(zone).toInstant().toEpochMilli()
        val instance = LocalDateTime.of(2026, 11, 17, 18, 0).atZone(zone).toInstant().toEpochMilli()
        val series = EventDetails(
            id = 1, calendarId = 1, calendarName = "", calendarColor = 0, writable = true, title = "Gym", location = "",
            description = "", color = 0, allDay = false, dtStart = seriesStart, durationMillis = 3_600_000, timeZone = zone.id,
            rrule = "FREQ=WEEKLY;BYDAY=TU", originalId = null, reminders = emptyList(), attendees = emptyList(), organizer = "",
        )
        // The occurrence moved to 19:30: every occurrence moves to 19:30, from the first one.
        val draft = EventDraft(1, start = LocalDateTime.of(2026, 11, 17, 19, 30), end = LocalDateTime.of(2026, 11, 17, 21, 0), zone = zone)
        val shifted = CalendarRepository.shiftToSeries(draft, series, instance)
        assertEquals(LocalDateTime.of(2026, 11, 3, 19, 30), shifted.start)
        assertEquals(LocalDateTime.of(2026, 11, 3, 21, 0), shifted.end)
    }

    @Test fun allDayInstancesSpanTheirDays() {
        val zone = ZoneId.of("Europe/Madrid")
        val begin = LocalDate.of(2026, 11, 21).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val end = LocalDate.of(2026, 11, 24).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val e = EventInstance(1, "Lisboa", begin, end, true, 0, 1, "", false)
        assertEquals(LocalDate.of(2026, 11, 21), e.startDate(zone))
        assertEquals(LocalDate.of(2026, 11, 23), e.endDate(zone))
    }
}
