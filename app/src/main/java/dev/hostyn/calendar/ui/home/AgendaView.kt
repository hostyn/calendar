@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package dev.hostyn.calendar.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.hostyn.calendar.CalendarApp
import dev.hostyn.calendar.R
import dev.hostyn.calendar.data.EventInstance
import dev.hostyn.calendar.ui.components.capitalized
import dev.hostyn.calendar.ui.components.contentColorOn
import dev.hostyn.calendar.ui.components.currentLocale
import dev.hostyn.calendar.ui.components.formatEventTimeOn
import dev.hostyn.calendar.ui.theme.LocalTransformOrigin
import dev.hostyn.calendar.ui.theme.containerTransform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle

/** Lets the screen ask the agenda to scroll to a day. */
class AgendaController {
    var target by mutableStateOf<LocalDate?>(null)
        private set

    fun scrollTo(date: LocalDate) { target = date }
    fun done() { target = null }
}

private sealed interface AgendaItem {
    val key: String
    val date: LocalDate

    data class Month(val month: YearMonth) : AgendaItem {
        override val key get() = "m$month"
        override val date: LocalDate get() = month.atDay(1)
    }

    data class Day(override val date: LocalDate, val events: List<EventInstance>) : AgendaItem {
        override val key get() = "d$date"
    }
}

/** How much more the agenda loads when the end comes near. */
private const val CHUNK_DAYS = 180L

/** A continuous list of the coming days with events, by month. */
@Composable
fun AgendaView(
    app: CalendarApp,
    version: Int,
    today: LocalDate,
    start: LocalDate,
    controller: AgendaController,
    onMonthShown: (YearMonth) -> Unit,
    onDayShown: (LocalDate) -> Unit,
    onOpenEvent: (EventInstance) -> Unit,
) {
    var from by remember { mutableStateOf(minOf(today, start).minusDays(30)) }
    var until by remember { mutableStateOf(maxOf(today, start).plusDays(CHUNK_DAYS)) }
    val items by produceState<List<AgendaItem>?>(null, from, until, version, today, app.settings.hiddenCalendars) {
        value = withContext(Dispatchers.IO) { agendaItems(app.repository.instances(from, until), from, until, today) }
    }
    val state = rememberLazyListState()
    val list = items

    // Scroll to the asked day (or the first one after it), once the list has it.
    LaunchedEffect(list, controller.target) {
        val target = controller.target ?: return@LaunchedEffect
        list ?: return@LaunchedEffect
        if (target < from || target >= until) {
            from = minOf(from, target.minusDays(30))
            until = maxOf(until, target.plusDays(CHUNK_DAYS))
            return@LaunchedEffect
        }
        var index = list.indexOfFirst { it is AgendaItem.Day && it.date >= target }
        if (index < 0) index = list.lastIndex
        // Show the month title too when the day is the first of its month in the list.
        if (index > 0 && list[index - 1] is AgendaItem.Month) index--
        state.scrollToItem(index.coerceAtLeast(0))
        controller.done()
    }
    // Opening the agenda: start at the selected day.
    LaunchedEffect(Unit) { if (controller.target == null) controller.scrollTo(start) }

    LaunchedEffect(list) {
        list ?: return@LaunchedEffect
        snapshotFlow { state.firstVisibleItemIndex }.distinctUntilChanged().collect { i ->
            val item = list.getOrNull(i) ?: return@collect
            onMonthShown(YearMonth.from(item.date))
            if (item is AgendaItem.Day) onDayShown(item.date)
            // Near the end: load more.
            if (i > list.size - 15) until = until.plusDays(CHUNK_DAYS)
        }
    }

    if (list == null) {
        Box(Modifier.fillMaxSize(), Alignment.Center) { LoadingIndicator() }
        return
    }
    LazyColumn(state = state, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 112.dp)) {
        items(list, key = { it.key }, contentType = { it::class }) { item ->
            when (item) {
                is AgendaItem.Month -> MonthHeader(item.month, today)
                is AgendaItem.Day -> DayRow(item, today, onOpenEvent)
            }
        }
    }
}

private fun agendaItems(events: List<EventInstance>, from: LocalDate, until: LocalDate, today: LocalDate): List<AgendaItem> {
    val zone = ZoneId.systemDefault()
    val byDay = HashMap<LocalDate, MutableList<EventInstance>>()
    events.forEach { e ->
        var d = maxOf(e.startDate(zone), from)
        val last = minOf(e.endDate(zone), until.minusDays(1))
        while (d <= last) {
            byDay.getOrPut(d) { mutableListOf() } += e
            d = d.plusDays(1)
        }
    }
    val result = mutableListOf<AgendaItem>()
    var month: YearMonth? = null
    var d = from
    while (d < until) {
        val on = byDay[d]
        if (!on.isNullOrEmpty() || d == today) {
            val m = YearMonth.from(d)
            if (m != month) { result += AgendaItem.Month(m); month = m }
            // Same order as the provider gives: all-day first, then by start.
            result += AgendaItem.Day(d, on.orEmpty().sortedWith(compareBy({ !it.allDay }, { it.begin }, { -it.end })))
        }
        d = d.plusDays(1)
    }
    return result
}

/** "December", big, where a new month starts: an editorial break in the list. */
@Composable
private fun MonthHeader(month: YearMonth, today: LocalDate) {
    val name = month.month.getDisplayName(TextStyle.FULL_STANDALONE, currentLocale()).capitalized()
    val yearColor = MaterialTheme.colorScheme.onSurfaceVariant
    Text(
        buildAnnotatedString {
            append(name)
            if (month.year != today.year) withStyle(SpanStyle(color = yearColor)) { append(" ${month.year}") }
        },
        style = MaterialTheme.typography.headlineMediumEmphasized,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 28.dp, bottom = 8.dp),
    )
}

@Composable
private fun DayRow(day: AgendaItem.Day, today: LocalDate, onOpenEvent: (EventInstance) -> Unit) {
    val isToday = day.date == today
    val past = day.date < today
    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 16.dp, top = 6.dp, bottom = 6.dp)) {
        // Date column: weekday over the day number; today in an expressive shape.
        Column(Modifier.width(56.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                day.date.dayOfWeek.getDisplayName(TextStyle.SHORT_STANDALONE, currentLocale()).capitalized().trimEnd('.'),
                style = MaterialTheme.typography.labelMedium,
                color = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(2.dp))
            Box(
                Modifier.size(40.dp).then(
                    if (isToday) Modifier.background(MaterialTheme.colorScheme.primary, MaterialShapes.Cookie4Sided.toShape()) else Modifier,
                ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    day.date.dayOfMonth.toString(),
                    style = MaterialTheme.typography.titleLargeEmphasized,
                    color = when {
                        isToday -> MaterialTheme.colorScheme.onPrimary
                        past -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
            if (day.events.isEmpty()) {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                ) {
                    Text(
                        stringResource(R.string.no_events_today),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    )
                }
            }
            // Today's events that have already ended fade like past days.
            val now = System.currentTimeMillis()
            day.events.forEachIndexed { i, e ->
                val over = past || (day.date == today && !e.allDay && e.end <= now)
                EventCard(e, day.date, i, day.events.size, over, onOpenEvent)
            }
        }
    }
}

/** An event filled with its calendar's color, so the agenda reads by color at a glance. */
@Composable
private fun EventCard(event: EventInstance, date: LocalDate, index: Int, count: Int, past: Boolean, onOpen: (EventInstance) -> Unit) {
    val context = LocalContext.current
    val origin = LocalTransformOrigin.current
    val key = eventTransformKey(event, date)
    val shape = ListItemDefaults.segmentedShapes(index, count).shape
    val container = Color(event.color).copy(alpha = if (past) 0.6f else 1f)
    val content = contentColorOn(Color(event.color))
    Surface(
        shape = shape,
        color = container,
        contentColor = content,
        modifier = Modifier.fillMaxWidth().containerTransform(key, shape).clip(shape).clickable { origin.key = key; onOpen(event) },
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    event.title.ifBlank { stringResource(R.string.no_title) },
                    style = MaterialTheme.typography.titleMediumEmphasized,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOf(formatEventTimeOn(context, event, date), event.location).filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = content.copy(alpha = 0.85f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (event.recurring) Icon(Icons.Rounded.Repeat, stringResource(R.string.repeats), Modifier.size(18.dp), tint = content.copy(alpha = 0.8f))
        }
    }
}
