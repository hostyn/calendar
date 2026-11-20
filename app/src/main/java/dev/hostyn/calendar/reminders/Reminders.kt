package dev.hostyn.calendar.reminders

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.CalendarContract
import android.provider.CalendarContract.CalendarAlerts
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.hostyn.calendar.MainActivity
import dev.hostyn.calendar.R
import dev.hostyn.calendar.data.eventUri
import dev.hostyn.calendar.ui.components.formatEventTime

/** One reminder to show: an occurrence of an event. */
data class Alert(
    val eventId: Long,
    val title: String,
    val begin: Long,
    val end: Long,
    val allDay: Boolean,
    val location: String,
    val color: Int,
) {
    /** One notification per occurrence, however many reminders it has. */
    val notificationId get() = (eventId * 31 + begin / 60_000).hashCode()
}

/**
 * Event reminders as notifications. Android's calendar provider sets the alarms for every
 * reminder and broadcasts EVENT_REMINDER when one is due; whoever listens shows it. Another
 * calendar app may show it too, so alerts already shown by someone else are still shown here.
 */
object Reminders {
    private const val CHANNEL = "reminders"
    const val ACTION_SNOOZE = "dev.hostyn.calendar.SNOOZE"
    const val ACTION_SHOW = "dev.hostyn.calendar.SHOW_ALERT"
    private const val SNOOZE_MINUTES = 10

    /** How late an alarm can arrive and still be shown (the phone may have been asleep). */
    private const val LATE_LIMIT = 24 * 60 * 60_000L

    fun createChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL, context.getString(R.string.channel_reminders), NotificationManager.IMPORTANCE_HIGH)
        channel.description = context.getString(R.string.channel_reminders_summary)
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /** Shows the reminders that are due and not shown yet. */
    fun showDue(context: Context) {
        if (context.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) return
        val now = System.currentTimeMillis()
        val prefs = context.getSharedPreferences("alerts", Context.MODE_PRIVATE)
        val shown = prefs.getStringSet("shown", emptySet())!!
        val stillRecent = mutableSetOf<String>()
        val projection = arrayOf(
            CalendarAlerts._ID, CalendarAlerts.EVENT_ID, CalendarAlerts.TITLE, CalendarAlerts.BEGIN, CalendarAlerts.END,
            CalendarAlerts.ALL_DAY, CalendarAlerts.EVENT_LOCATION, CalendarAlerts.ALARM_TIME, CalendarAlerts.DISPLAY_COLOR,
        )
        val selection = "${CalendarAlerts.ALARM_TIME} <= ? AND ${CalendarAlerts.ALARM_TIME} > ? AND " +
            "${CalendarAlerts.STATE} != ${CalendarAlerts.STATE_DISMISSED}"
        val args = arrayOf((now + 60_000).toString(), (now - LATE_LIMIT).toString())
        val due = mutableListOf<Alert>()
        context.contentResolver.query(CalendarAlerts.CONTENT_URI, projection, selection, args, "${CalendarAlerts.ALARM_TIME} ASC")?.use { c ->
            while (c.moveToNext()) {
                val key = c.getLong(0).toString()
                stillRecent += key
                if (key in shown) continue
                val alert = Alert(
                    eventId = c.getLong(1),
                    title = c.getString(2).orEmpty(),
                    begin = c.getLong(3),
                    end = c.getLong(4),
                    allDay = c.getInt(5) == 1,
                    location = c.getString(6).orEmpty(),
                    color = c.getInt(8),
                )
                // The event may have ended already if the phone was off: don't ring for the past.
                if (alert.end > now || alert.allDay && alert.end > now - 86_400_000L) due += alert
            }
        }
        due.distinctBy { it.notificationId }.forEach { notify(context, it) }
        prefs.edit().putStringSet("shown", stillRecent).apply()
        // Tell the provider they were shown, as calendar apps do.
        stillRecent.forEach { id ->
            val values = android.content.ContentValues().apply { put(CalendarAlerts.STATE, CalendarAlerts.STATE_FIRED) }
            runCatching {
                context.contentResolver.update(
                    CalendarAlerts.CONTENT_URI, values,
                    "${CalendarAlerts._ID} = ? AND ${CalendarAlerts.STATE} = ${CalendarAlerts.STATE_SCHEDULED}", arrayOf(id),
                )
            }
        }
    }

    fun notify(context: Context, alert: Alert) {
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            android.os.Build.VERSION.SDK_INT >= 33
        ) return
        val open = Intent(Intent.ACTION_VIEW, eventUri(alert.eventId), context, MainActivity::class.java)
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, alert.begin)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, alert.end)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val snooze = alertIntent(context, ACTION_SNOOZE, alert)
        val text = buildList {
            add(formatEventTime(context, alert.begin, alert.end, alert.allDay))
            if (alert.location.isNotBlank()) add(alert.location)
        }.joinToString(" · ")
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_event)
            .setContentTitle(alert.title.ifBlank { context.getString(R.string.no_title) })
            .setContentText(text)
            .setColor(alert.color)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setWhen(alert.begin)
            .setShowWhen(false)
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntent.getActivity(context, alert.notificationId, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            )
            .addAction(
                0, context.getString(R.string.snooze),
                PendingIntent.getBroadcast(context, alert.notificationId, snooze, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            )
            .build()
        NotificationManagerCompat.from(context).notify(alert.notificationId, notification)
    }

    /** Hides the reminder and shows it again in a few minutes. */
    fun snooze(context: Context, alert: Alert) {
        NotificationManagerCompat.from(context).cancel(alert.notificationId)
        val at = System.currentTimeMillis() + SNOOZE_MINUTES * 60_000L
        val pending = PendingIntent.getBroadcast(
            context, alert.notificationId, alertIntent(context, ACTION_SHOW, alert),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // A calendar app may ring on time (USE_EXACT_ALARM); without it the system may delay the snooze by minutes.
        val alarms = context.getSystemService(AlarmManager::class.java)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        } else {
            alarms.setWindow(AlarmManager.RTC_WAKEUP, at, 60_000L, pending)
        }
    }

    private fun alertIntent(context: Context, action: String, alert: Alert) =
        Intent(action, null, context, AlertActionReceiver::class.java)
            .putExtra("event", alert.eventId)
            .putExtra("title", alert.title)
            .putExtra("begin", alert.begin)
            .putExtra("end", alert.end)
            .putExtra("allDay", alert.allDay)
            .putExtra("location", alert.location)
            .putExtra("color", alert.color)

    fun alertOf(intent: Intent) = Alert(
        eventId = intent.getLongExtra("event", -1),
        title = intent.getStringExtra("title").orEmpty(),
        begin = intent.getLongExtra("begin", 0),
        end = intent.getLongExtra("end", 0),
        allDay = intent.getBooleanExtra("allDay", false),
        location = intent.getStringExtra("location").orEmpty(),
        color = intent.getIntExtra("color", 0),
    )
}

/** EVENT_REMINDER from the calendar provider. Exported: it only makes the app look for due reminders. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // The query is quick, but off the main thread.
        val pending = goAsync()
        Thread {
            try { Reminders.showDue(context) } finally { pending.finish() }
        }.start()
    }
}

/** The notification's own actions (snooze, and showing it again). Not exported. */
class AlertActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Reminders.ACTION_SNOOZE -> Reminders.snooze(context, Reminders.alertOf(intent))
            Reminders.ACTION_SHOW -> Reminders.notify(context, Reminders.alertOf(intent))
        }
    }
}
