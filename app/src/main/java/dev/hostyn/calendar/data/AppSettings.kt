package dev.hostyn.calendar.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.time.DayOfWeek
import java.time.temporal.WeekFields
import java.util.Locale

enum class ThemeMode { System, Light, Dark }

/** The two ways to look at the calendar. */
enum class CalendarView { Month, Agenda }

/**
 * App preferences. Each value is Compose state (screens update when it changes) backed by
 * SharedPreferences, so the widget and receivers can read it too.
 */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Called after a change that affects which events are shown (the widget listens). */
    var onEventsFilterChanged: () -> Unit = {}

    var theme by mutableStateOf(ThemeMode.entries.firstOrNull { it.name == prefs.getString("theme", null) } ?: ThemeMode.System)
        private set
    fun updateTheme(value: ThemeMode) { theme = value; prefs.edit().putString("theme", value.name).apply() }

    var dynamicColor by mutableStateOf(prefs.getBoolean("dynamic_color", true))
        private set
    fun updateDynamicColor(value: Boolean) { dynamicColor = value; prefs.edit().putBoolean("dynamic_color", value).apply() }

    /** The view the app opens in: the last one used. */
    var view by mutableStateOf(CalendarView.entries.firstOrNull { it.name == prefs.getString("view", null) } ?: CalendarView.Month)
        private set
    fun updateView(value: CalendarView) { view = value; prefs.edit().putString("view", value.name).apply() }

    /** Calendars the user chose not to see. The rest, including new ones, are shown. */
    var hiddenCalendars by mutableStateOf(prefs.getStringSet("hidden_calendars", emptySet())!!.mapNotNull { it.toLongOrNull() }.toSet())
        private set
    fun setCalendarShown(id: Long, shown: Boolean) {
        hiddenCalendars = if (shown) hiddenCalendars - id else hiddenCalendars + id
        prefs.edit().putStringSet("hidden_calendars", hiddenCalendars.map { it.toString() }.toSet()).apply()
        onEventsFilterChanged()
    }

    /** Calendar new events go to; -1 picks the first writable one. */
    var defaultCalendar by mutableStateOf(prefs.getLong("default_calendar", -1))
        private set
    fun updateDefaultCalendar(value: Long) { defaultCalendar = value; prefs.edit().putLong("default_calendar", value).apply() }

    /** First day of the week; null follows the language and region. */
    var weekStart by mutableStateOf(prefs.getString("week_start", null)?.let { s -> DayOfWeek.entries.firstOrNull { it.name == s } })
        private set
    fun updateWeekStart(value: DayOfWeek?) {
        weekStart = value
        prefs.edit().putString("week_start", value?.name).apply()
        onEventsFilterChanged()
    }

    fun firstDayOfWeek(): DayOfWeek = weekStart ?: WeekFields.of(Locale.getDefault()).firstDayOfWeek

    /** Reminders for new events, in minutes before the start (timed and all-day events). */
    var defaultReminder by mutableStateOf(prefs.getInt("default_reminder", 10))
        private set
    fun updateDefaultReminder(value: Int) { defaultReminder = value; prefs.edit().putInt("default_reminder", value).apply() }

    companion object {
        /** No reminder by default. */
        const val NO_REMINDER = -1
    }
}
