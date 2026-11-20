package dev.hostyn.calendar

import android.app.Application
import android.content.Context
import dev.hostyn.calendar.data.AppSettings
import dev.hostyn.calendar.data.CalendarRepository
import dev.hostyn.calendar.reminders.Reminders
import dev.hostyn.calendar.widget.WidgetUpdates
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class CalendarApp : Application() {
    val settings by lazy { AppSettings(this) }
    val repository by lazy { CalendarRepository(this, settings) }

    /** For work that must outlive a screen (saving, deleting, refreshing the widget). */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        Reminders.createChannel(this)
        settings.onEventsFilterChanged = {
            repository.invalidate()
            WidgetUpdates.refresh(this)
        }
        WidgetUpdates.watch(this)
    }

    companion object {
        fun of(context: Context) = context.applicationContext as CalendarApp
    }
}
