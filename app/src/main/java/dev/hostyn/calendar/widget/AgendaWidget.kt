package dev.hostyn.calendar.widget

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.CalendarContract
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.material3.ColorProviders
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import dev.hostyn.calendar.CalendarApp
import dev.hostyn.calendar.MainActivity
import dev.hostyn.calendar.R
import dev.hostyn.calendar.data.EventInstance
import dev.hostyn.calendar.data.eventUri
import dev.hostyn.calendar.ui.components.dayTitle
import dev.hostyn.calendar.ui.components.formatEventTimeOn
import dev.hostyn.calendar.ui.theme.DarkColors
import dev.hostyn.calendar.ui.theme.LightColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What the widget shows: the next days with events, each with its events. */
private data class Agenda(val today: LocalDate, val rows: List<Row>, val permission: Boolean, val dynamic: Boolean, val locale: Locale)

private data class AgendaDay(val date: LocalDate, val events: List<EventInstance>)

private sealed interface Row {
    data class Day(val date: LocalDate) : Row
    data class Event(val date: LocalDate, val event: EventInstance) : Row
    data object Empty : Row
}

/** Home-screen agenda: today's date, a button for a new event and the coming events by day. */
class AgendaWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val app = CalendarApp.of(context)
        val first = withContext(Dispatchers.IO) { load(app) }
        val firstGeneration = WidgetUpdates.generation.value
        provideContent {
            // updateAll() only recomposes a running session: reading the data here, keyed by the refresh
            // count, is what makes a refresh show new events.
            val generation by WidgetUpdates.generation.collectAsState()
            val agenda by produceState(first, generation) {
                if (generation != firstGeneration) value = withContext(Dispatchers.IO) { load(app) }
            }
            if (agenda.dynamic) GlanceTheme { Content(agenda) }
            else GlanceTheme(colors = ColorProviders(light = LightColors, dark = DarkColors)) { Content(agenda) }
        }
    }

    companion object {
        private fun load(app: CalendarApp): Agenda {
            val zone = ZoneId.systemDefault()
            val today = LocalDate.now(zone)
            val permission = app.repository.hasPermission()
            val rows = if (permission) rowsOf(upcoming(app, today, zone), today) else emptyList()
            val dynamic = app.settings.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            return Agenda(today, rows, permission, dynamic, Locale.getDefault())
        }

        /** How far ahead the widget looks first, and how far when that leaves it nearly empty. */
        private const val DAYS = 30L
        private const val MAX_DAYS = 365L
        /** Fewer events than this in [DAYS] and the widget looks up to [MAX_DAYS] ahead. */
        private const val ENOUGH_EVENTS = 15
        private const val MAX_EVENTS = 60

        private fun upcoming(app: CalendarApp, today: LocalDate, zone: ZoneId): List<AgendaDay> {
            val near = app.repository.instances(today, today.plusDays(DAYS), zone)
            if (near.size >= ENOUGH_EVENTS) return days(near, today, DAYS, zone)
            return days(app.repository.instances(today, today.plusDays(MAX_DAYS), zone), today, MAX_DAYS, zone)
        }

        private fun days(events: List<EventInstance>, today: LocalDate, span: Long, zone: ZoneId): List<AgendaDay> =
            (0 until span).map { today.plusDays(it) }.mapNotNull { day ->
                val on = events.filter { it.occursOn(day, zone) }
                    // Today, only what hasn't finished yet.
                    .filter { day != today || it.allDay || it.end > System.currentTimeMillis() }
                if (on.isEmpty() && day != today) null else AgendaDay(day, on)
            }

        private fun rowsOf(days: List<AgendaDay>, today: LocalDate): List<Row> {
            val rows = mutableListOf<Row>()
            var count = 0
            for (day in days) {
                if (count >= MAX_EVENTS) break
                // Nothing left today: the list starts with the next day that has something.
                if (day.events.isEmpty()) continue
                rows += Row.Day(day.date)
                day.events.forEach { rows += Row.Event(day.date, it); count++ }
            }
            if (rows.isEmpty()) rows += Row.Empty
            return rows
        }
    }
}

@Composable
private fun Content(agenda: Agenda) {
    val (today, rows, permission) = agenda
    val context = LocalContext.current
    val colors = GlanceTheme.colors
    Column(
        GlanceModifier.fillMaxSize().background(colors.widgetBackground).appWidgetCorners().padding(top = 10.dp, start = 10.dp, end = 10.dp),
    ) {
        Header(today, agenda.locale)
        Spacer(GlanceModifier.height(2.dp))
        if (!permission) {
            Message(context.getString(R.string.widget_no_permission), GlanceModifier.clickable(actionStartActivity(openApp(context, null))))
            return@Column
        }
        LazyColumn(GlanceModifier.fillMaxSize()) {
            items(rows, itemId = { row ->
                when (row) {
                    is Row.Day -> row.date.toEpochDay() * 2
                    is Row.Event -> (row.event.eventId * 1_000_003 + row.event.begin / 60_000 + row.date.toEpochDay()) * 2 + 1
                    Row.Empty -> -1L
                }
            }) { row ->
                when (row) {
                    is Row.Day -> DayHeader(row.date, today)
                    is Row.Event -> EventRow(row.event, row.date)
                    Row.Empty -> Message(context.getString(R.string.widget_empty), GlanceModifier)
                }
            }
        }
    }
}

@Composable
private fun Header(today: LocalDate, locale: Locale) {
    val context = LocalContext.current
    val colors = GlanceTheme.colors
    Row(GlanceModifier.fillMaxWidth().padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(GlanceModifier.defaultWeight().clickable(actionStartActivity(openApp(context, today)))) {
            Text(
                today.format(DateTimeFormatter.ofPattern("EEEE", locale)).replaceFirstChar { it.titlecase(locale) },
                style = TextStyle(color = colors.primary, fontSize = 12.sp, fontWeight = FontWeight.Medium),
                maxLines = 1,
            )
            Text(
                today.format(DateTimeFormatter.ofPattern(if (locale.language == "en") "MMMM d" else "d MMMM", locale)),
                style = TextStyle(color = colors.onSurface, fontSize = 18.sp, fontWeight = FontWeight.Bold),
                maxLines = 1,
            )
        }
        Box(
            GlanceModifier.size(40.dp).background(colors.primary).cornerRadius(20.dp)
                .clickable(actionStartActivity(newEvent(context))),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                ImageProvider(R.drawable.ic_widget_add),
                contentDescription = context.getString(R.string.new_event),
                colorFilter = ColorFilter.tint(colors.onPrimary),
                modifier = GlanceModifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun DayHeader(date: LocalDate, today: LocalDate) {
    val context = LocalContext.current
    val colors = GlanceTheme.colors
    Text(
        dayTitle(context, date, today),
        style = TextStyle(
            color = if (date == today) colors.primary else colors.onSurfaceVariant,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        ),
        maxLines = 1,
        modifier = GlanceModifier.fillMaxWidth().padding(start = 4.dp, top = 6.dp, bottom = 3.dp)
            .clickable(actionStartActivity(openApp(context, date))),
    )
}

@Composable
private fun EventRow(event: EventInstance, date: LocalDate) {
    val context = LocalContext.current
    val colors = GlanceTheme.colors
    val open = Intent(Intent.ACTION_VIEW, eventUri(event.eventId), context, MainActivity::class.java)
        .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, event.begin)
        .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, event.end)
    Column(GlanceModifier.fillMaxWidth().padding(bottom = 2.dp)) {
        Row(
            GlanceModifier.fillMaxWidth().background(colors.surface).cornerRadius(12.dp)
                .clickable(actionStartActivity(open)).padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(GlanceModifier.width(4.dp).height(30.dp).background(ColorProvider(Color(event.color))).cornerRadius(2.dp)) {}
            Spacer(GlanceModifier.width(10.dp))
            Column(GlanceModifier.defaultWeight()) {
                Text(
                    event.title.ifBlank { context.getString(R.string.no_title) },
                    style = TextStyle(color = colors.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium),
                    maxLines = 1,
                )
                Text(
                    listOf(formatEventTimeOn(context, event, date), event.location).filter { it.isNotBlank() }.joinToString(" · "),
                    style = TextStyle(color = colors.onSurfaceVariant, fontSize = 12.sp),
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun Message(text: String, modifier: GlanceModifier) {
    Box(modifier.fillMaxWidth().padding(vertical = 24.dp, horizontal = 8.dp), contentAlignment = Alignment.Center) {
        Text(text, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 14.sp))
    }
}

/** Rounded like other widgets of the launcher (Android 12+). */
private fun GlanceModifier.appWidgetCorners(): GlanceModifier =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) cornerRadius(android.R.dimen.system_app_widget_background_radius)
    else cornerRadius(24.dp)

/** Opens the app on [date] (or where it was). */
fun openApp(context: Context, date: LocalDate?): Intent {
    val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    if (date != null) {
        intent.action = Intent.ACTION_VIEW
        val millis = date.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        intent.data = CalendarContract.CONTENT_URI.buildUpon().appendPath("time").appendPath(millis.toString()).build()
    }
    return intent
}

fun newEvent(context: Context): Intent =
    Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI, context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

class AgendaWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = AgendaWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        WidgetUpdates.watch(context)
    }

    companion object {
        fun component(context: Context) = ComponentName(context, AgendaWidgetReceiver::class.java)
    }
}
