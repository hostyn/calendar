@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package dev.hostyn.calendar.ui.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.hostyn.calendar.CalendarApp
import dev.hostyn.calendar.R
import dev.hostyn.calendar.data.EventInstance
import dev.hostyn.calendar.ui.components.currentLocale
import dev.hostyn.calendar.ui.components.formatEventTimeOn
import dev.hostyn.calendar.ui.components.longDate
import dev.hostyn.calendar.ui.components.segmentedColors
import dev.hostyn.calendar.ui.theme.LocalTransformOrigin
import dev.hostyn.calendar.ui.theme.containerTransform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/** Rows the month grid always has, so its height doesn't jump between months. */
private const val WEEKS = 6
private val CellHeight = 52.dp

/** Today's date, checked again whenever the app comes back (it may be another day). */
@Composable
fun rememberToday(): LocalDate {
    var today by remember { mutableStateOf(LocalDate.now()) }
    LifecycleResumeEffect(Unit) {
        today = LocalDate.now()
        onPauseOrDispose {}
    }
    return today
}

/** First day shown in [month]'s grid: the start of the week holding the 1st. */
fun gridStart(month: YearMonth, firstDay: DayOfWeek): LocalDate =
    month.atDay(1).with(TemporalAdjusters.previousOrSame(firstDay))

@Composable
fun MonthView(
    app: CalendarApp,
    version: Int,
    pager: PagerState,
    monthOf: (Int) -> YearMonth,
    selected: LocalDate,
    today: LocalDate,
    onSelect: (LocalDate) -> Unit,
    onOpenEvent: (EventInstance) -> Unit,
    onNewEvent: () -> Unit,
) {
    val firstDay = app.settings.firstDayOfWeek()
    Column(Modifier.fillMaxSize()) {
        WeekdayHeader(firstDay)
        HorizontalPager(pager, Modifier.fillMaxWidth().height(CellHeight * WEEKS), beyondViewportPageCount = 1) { page ->
            MonthGrid(app, version, monthOf(page), firstDay, selected, today, onSelect)
        }
        DayPanel(app, version, selected, today, onOpenEvent, onNewEvent, Modifier.weight(1f))
    }
}

@Composable
private fun WeekdayHeader(firstDay: DayOfWeek) {
    val locale = currentLocale()
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        repeat(7) { i ->
            val day = firstDay.plus(i.toLong())
            Text(
                day.getDisplayName(TextStyle.NARROW_STANDALONE, locale),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun MonthGrid(
    app: CalendarApp,
    version: Int,
    month: YearMonth,
    firstDay: DayOfWeek,
    selected: LocalDate,
    today: LocalDate,
    onSelect: (LocalDate) -> Unit,
) {
    val start = gridStart(month, firstDay)
    val end = start.plusDays(WEEKS * 7L)
    // Colors of the events on each day of the grid, in order.
    val colors by produceState(emptyMap<LocalDate, List<Int>>(), month, firstDay, version, app.settings.hiddenCalendars) {
        value = withContext(Dispatchers.IO) {
            val zone = ZoneId.systemDefault()
            val byDay = HashMap<LocalDate, MutableList<Int>>()
            app.repository.instances(start, end, zone).forEach { e ->
                var d = maxOf(e.startDate(zone), start)
                val last = minOf(e.endDate(zone), end.minusDays(1))
                while (d <= last) {
                    byDay.getOrPut(d) { mutableListOf() } += e.color
                    d = d.plusDays(1)
                }
            }
            byDay
        }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
        repeat(WEEKS) { week ->
            Row(Modifier.fillMaxWidth().height(CellHeight)) {
                repeat(7) { i ->
                    val date = start.plusDays(week * 7L + i)
                    DayCell(
                        date = date,
                        inMonth = YearMonth.from(date) == month,
                        isToday = date == today,
                        isSelected = date == selected,
                        dots = colors[date].orEmpty(),
                        onClick = { onSelect(date) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun DayCell(
    date: LocalDate,
    inMonth: Boolean,
    isToday: Boolean,
    isSelected: Boolean,
    dots: List<Int>,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val effects = MaterialTheme.motionScheme.fastEffectsSpec<Color>()
    val background by animateColorAsState(
        when {
            isToday -> scheme.primary
            isSelected -> scheme.secondaryContainer
            else -> Color.Transparent
        },
        effects, label = "day",
    )
    val textColor = when {
        isToday -> scheme.onPrimary
        isSelected -> scheme.onSecondaryContainer
        inMonth -> scheme.onSurface
        else -> scheme.onSurfaceVariant.copy(alpha = 0.5f)
    }
    // The selected day grows a little, with the expressive spring.
    val scale by animateFloatAsState(if (isSelected) 1.12f else 1f, MaterialTheme.motionScheme.fastSpatialSpec(), label = "scale")
    Column(
        modifier.fillMaxHeight().clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(36.dp).scale(scale)
                // Today breaks from the round shapes around it: it's the one to find at a glance.
                .background(background, if (isToday) MaterialShapes.Cookie4Sided.toShape() else CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                date.dayOfMonth.toString(),
                style = if (isToday || isSelected) MaterialTheme.typography.titleSmallEmphasized else MaterialTheme.typography.bodyMedium,
                fontWeight = if (isToday || isSelected) FontWeight.Bold else null,
                color = textColor,
            )
        }
        Spacer(Modifier.height(3.dp))
        Row(Modifier.height(6.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            dots.distinct().take(3).forEach { c ->
                Box(Modifier.size(5.dp).background(Color(c).copy(alpha = if (inMonth) 1f else 0.5f), CircleShape))
            }
        }
    }
}

/** The selected day's events under the grid. */
@Composable
private fun DayPanel(
    app: CalendarApp,
    version: Int,
    date: LocalDate,
    today: LocalDate,
    onOpenEvent: (EventInstance) -> Unit,
    onNewEvent: () -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val events by produceState<List<EventInstance>?>(null, date, version, app.settings.hiddenCalendars) {
        value = withContext(Dispatchers.IO) { app.repository.instances(date, date.plusDays(1)) }
    }
    LazyColumn(modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = 112.dp)) {
        item(key = "header") {
            Text(
                if (date == today) stringResource(R.string.today_long, longDate(context, date, capitalize = false)) else longDate(context, date),
                style = MaterialTheme.typography.titleMediumEmphasized,
                color = if (date == today) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 16.dp, bottom = 10.dp),
            )
        }
        val list = events
        if (list != null && list.isEmpty()) {
            item(key = "empty") {
                Row(Modifier.fillMaxWidth().padding(horizontal = 32.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.no_events_day),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onNewEvent, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.add)) }
                }
            }
        }
        itemsIndexed(list.orEmpty(), key = { _, e -> e.key }) { i, e ->
            EventListItem(e, date, i, list!!.size, onOpenEvent)
        }
    }
}

/** An event in a segmented list: its color, title, time and place. */
@Composable
fun EventListItem(event: EventInstance, date: LocalDate, index: Int, count: Int, onOpen: (EventInstance) -> Unit) {
    val context = LocalContext.current
    val origin = LocalTransformOrigin.current
    val key = eventTransformKey(event, date)
    val shapes = ListItemDefaults.segmentedShapes(index, count)
    Box(Modifier.padding(horizontal = 16.dp).padding(bottom = if (index == count - 1) 0.dp else ListItemDefaults.SegmentedGap)) {
        SegmentedListItem(
            onClick = { origin.key = key; onOpen(event) },
            shapes = shapes,
            colors = segmentedColors(),
            leadingContent = { Box(Modifier.width(6.dp).height(36.dp).background(Color(event.color), CircleShape)) },
            supportingContent = {
                Text(
                    listOf(formatEventTimeOn(context, event, date), event.location).filter { it.isNotBlank() }.joinToString(" · "),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            trailingContent = if (event.recurring) {
                { Icon(Icons.Rounded.Repeat, stringResource(R.string.repeats), Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else null,
            modifier = Modifier.fillMaxWidth().containerTransform(key, shapes.shape),
        ) {
            Text(
                event.title.ifBlank { stringResource(R.string.no_title) },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
