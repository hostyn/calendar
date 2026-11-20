package dev.hostyn.calendar.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import androidx.glance.appwidget.updateAll
import dev.hostyn.calendar.CalendarApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

/**
 * Keeps the widget current: redraws it when the calendar provider changes (a sync, an edit here or
 * in another app), at midnight, and when the clock or time zone changes.
 */
object WidgetUpdates {
    // Far from the ids WorkManager (used by Glance) hands out from 0 upwards, which would replace this job.
    private const val JOB_ID = 0x0CA1E9D4

    private fun hasWidgets(context: Context) =
        AppWidgetManager.getInstance(context).getAppWidgetIds(AgendaWidgetReceiver.component(context)).isNotEmpty()

    /** Bumped on every refresh: running widget sessions load their events again when it changes. */
    val generation = MutableStateFlow(0)

    /** Redraws every widget now. */
    fun refresh(context: Context) {
        if (!hasWidgets(context)) return
        generation.update { it + 1 }
        val app = CalendarApp.of(context)
        app.scope.launch { AgendaWidget().updateAll(app) }
    }

    /** Starts listening for changes, if there is any widget. Safe to call often. */
    fun watch(context: Context) {
        if (!hasWidgets(context)) return
        scheduleChangeJob(context)
        scheduleMidnight(context)
    }

    /** A content-triggered job runs once, so it is scheduled again each time it runs. */
    fun scheduleChangeJob(context: Context) {
        val job = JobInfo.Builder(JOB_ID, ComponentName(context, CalendarChangedJob::class.java))
            .addTriggerContentUri(JobInfo.TriggerContentUri(CalendarContract.CONTENT_URI, JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS))
            // A sync writes many rows: wait for it to settle before redrawing.
            .setTriggerContentUpdateDelay(2_000)
            .setTriggerContentMaxDelay(10_000)
            .build()
        context.getSystemService(JobScheduler::class.java).schedule(job)
    }

    private fun scheduleMidnight(context: Context) {
        val zone = ZoneId.systemDefault()
        // A minute past, so "today" has certainly changed.
        val at = LocalDate.now(zone).plusDays(1).atStartOfDay(zone).plusMinutes(1).toInstant().toEpochMilli()
        val intent = Intent(context, WidgetTimeReceiver::class.java).setAction(ACTION_MIDNIGHT)
        val pending = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        context.getSystemService(AlarmManager::class.java).set(AlarmManager.RTC, at, pending)
    }

    const val ACTION_MIDNIGHT = "dev.hostyn.calendar.MIDNIGHT"
}

class CalendarChangedJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        WidgetUpdates.refresh(this)
        WidgetUpdates.scheduleChangeJob(this)
        return false
    }

    override fun onStopJob(params: JobParameters) = false
}

/** Midnight, clock and time zone changes, and boot: "today" may be another day now. */
class WidgetTimeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in ACTIONS) return
        WidgetUpdates.refresh(context)
        WidgetUpdates.watch(context)
    }

    private companion object {
        val ACTIONS = setOf(
            WidgetUpdates.ACTION_MIDNIGHT,
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_LOCALE_CHANGED,
        )
    }
}
