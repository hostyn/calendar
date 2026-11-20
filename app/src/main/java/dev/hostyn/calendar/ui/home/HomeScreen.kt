@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package dev.hostyn.calendar.ui.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CalendarViewMonth
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Today
import androidx.compose.material.icons.rounded.ViewAgenda
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hostyn.calendar.CalendarApp
import dev.hostyn.calendar.R
import dev.hostyn.calendar.data.CalendarView
import dev.hostyn.calendar.data.EventInstance
import dev.hostyn.calendar.ui.components.capitalized
import dev.hostyn.calendar.ui.components.currentLocale
import dev.hostyn.calendar.ui.theme.LocalTransformOrigin
import dev.hostyn.calendar.ui.theme.containerTransform
import dev.hostyn.calendar.ui.theme.topLevelEnter
import dev.hostyn.calendar.ui.theme.topLevelExit
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle

/** Months the pager can reach on each side of the current one (100 years). */
private const val PAGES = 2401
private const val MIDDLE = PAGES / 2

val LocalDateSaver = Saver<LocalDate, Long>(save = { it.toEpochDay() }, restore = { LocalDate.ofEpochDay(it) })

const val NewEventTransformKey = "new-event"

/** Key of the element an event's screen grows out of (it can appear on several days). */
fun eventTransformKey(e: EventInstance, date: LocalDate) = "event-${e.eventId}-${e.begin}-${date.toEpochDay()}"

/** Month and agenda, with a floating toolbar to switch between them and add events. */
@Composable
fun HomeScreen(
    app: CalendarApp,
    jumpTo: MutableState<LocalDate?>,
    onOpenEvent: (EventInstance) -> Unit,
    onNewEvent: (LocalDate) -> Unit,
    onSettings: () -> Unit,
) {
    val settings = app.settings
    val version by app.repository.version.collectAsStateWithLifecycle()
    val today = rememberToday()
    var selected by rememberSaveable(stateSaver = LocalDateSaver) { mutableStateOf(LocalDate.now()) }
    val baseMonth = remember { YearMonth.now() }
    fun monthOf(page: Int): YearMonth = baseMonth.plusMonths((page - MIDDLE).toLong())
    fun pageOf(month: YearMonth): Int = MIDDLE + (month.year - baseMonth.year) * 12 + (month.monthValue - baseMonth.monthValue)
    val pager = rememberPagerState(initialPage = pageOf(YearMonth.from(selected))) { PAGES }
    val agenda = remember { AgendaController() }
    var agendaMonth by remember { mutableStateOf(YearMonth.from(selected)) }
    val scope = rememberCoroutineScope()

    fun show(date: LocalDate, animate: Boolean) {
        selected = date
        scope.launch {
            val page = pageOf(YearMonth.from(date))
            if (animate) pager.animateScrollToPage(page) else pager.scrollToPage(page)
        }
        agenda.scrollTo(date)
    }

    LaunchedEffect(jumpTo.value) {
        jumpTo.value?.let { show(it, animate = false); jumpTo.value = null }
    }
    // Swiping to another month selects its first day, or today in the current month.
    LaunchedEffect(pager.settledPage) {
        val month = monthOf(pager.settledPage)
        if (YearMonth.from(selected) != month) selected = if (month == YearMonth.from(today)) today else month.atDay(1)
    }

    val title = if (settings.view == CalendarView.Month) monthOf(pager.currentPage) else agendaMonth
    val origin = LocalTransformOrigin.current

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { MonthTitle(title, today) },
                actions = {
                    IconButton(onClick = { show(today, animate = true) }, shapes = IconButtonDefaults.shapes()) {
                        Icon(Icons.Rounded.Today, stringResource(R.string.go_to_today))
                    }
                    IconButton(onClick = onSettings, shapes = IconButtonDefaults.shapes()) {
                        Icon(Icons.Rounded.Settings, stringResource(R.string.settings))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
            AnimatedContent(
                settings.view,
                transitionSpec = { topLevelEnter() togetherWith topLevelExit() },
                label = "view",
            ) { view ->
                when (view) {
                    CalendarView.Month -> MonthView(
                        app = app,
                        version = version,
                        pager = pager,
                        monthOf = ::monthOf,
                        selected = selected,
                        today = today,
                        onSelect = { date ->
                            selected = date
                            val page = pageOf(YearMonth.from(date))
                            if (page != pager.currentPage) scope.launch { pager.animateScrollToPage(page) }
                        },
                        onOpenEvent = onOpenEvent,
                        onNewEvent = { origin.key = NewEventTransformKey; onNewEvent(selected) },
                    )
                    CalendarView.Agenda -> AgendaView(
                        app = app,
                        version = version,
                        today = today,
                        start = selected,
                        controller = agenda,
                        onMonthShown = { agendaMonth = it },
                        onDayShown = { selected = it },
                        onOpenEvent = onOpenEvent,
                    )
                }
            }
            HorizontalFloatingToolbar(
                expanded = true,
                floatingActionButton = {
                    FloatingToolbarDefaults.VibrantFloatingActionButton(
                        onClick = { origin.key = NewEventTransformKey; onNewEvent(selected) },
                        modifier = Modifier.containerTransform(NewEventTransformKey, MaterialTheme.shapes.large),
                    ) { Icon(Icons.Rounded.Add, stringResource(R.string.new_event)) }
                },
                colors = FloatingToolbarDefaults.vibrantFloatingToolbarColors(),
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().offset(y = (-16).dp),
            ) {
                ViewToggle(
                    checked = settings.view == CalendarView.Month,
                    onClick = {
                        settings.updateView(CalendarView.Month)
                        scope.launch { pager.scrollToPage(pageOf(YearMonth.from(selected))) }
                    },
                    icon = { Icon(Icons.Rounded.CalendarViewMonth, stringResource(R.string.view_month)) },
                )
                ViewToggle(
                    checked = settings.view == CalendarView.Agenda,
                    onClick = {
                        agendaMonth = YearMonth.from(selected)
                        agenda.scrollTo(selected)
                        settings.updateView(CalendarView.Agenda)
                    },
                    icon = { Icon(Icons.Rounded.ViewAgenda, stringResource(R.string.view_agenda)) },
                )
            }
        }
    }
}

@Composable
private fun ViewToggle(checked: Boolean, onClick: () -> Unit, icon: @Composable () -> Unit) {
    IconToggleButton(
        checked = checked,
        onCheckedChange = { if (!checked) onClick() },
        shapes = IconButtonDefaults.toggleableShapes(),
        // The view on screen stands out in the toolbar.
        colors = IconButtonDefaults.iconToggleButtonVibrantColors(
            // The toolbar is primaryContainer: without this the icon takes the screen's content color,
            // white in the dark theme, and vanishes on a light toolbar.
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            checkedContainerColor = MaterialTheme.colorScheme.onPrimaryContainer,
            checkedContentColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) { icon() }
}

/** "November" in large type, with the year when it isn't this one. */
@Composable
private fun MonthTitle(month: YearMonth, today: LocalDate) {
    val locale = currentLocale()
    val name = month.month.getDisplayName(TextStyle.FULL_STANDALONE, locale).capitalized()
    val yearColor = MaterialTheme.colorScheme.onSurfaceVariant
    Text(
        buildAnnotatedString {
            append(name)
            if (month.year != today.year) withStyle(SpanStyle(color = yearColor)) { append(" ${month.year}") }
        },
        style = MaterialTheme.typography.headlineSmallEmphasized,
    )
}
