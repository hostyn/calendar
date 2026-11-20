package dev.hostyn.calendar

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.CalendarContract
import android.provider.CalendarContract.Events
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.hostyn.calendar.ui.PermissionGate
import dev.hostyn.calendar.ui.event.EditEventScreen
import dev.hostyn.calendar.ui.event.EventScreen
import dev.hostyn.calendar.ui.event.Prefill
import dev.hostyn.calendar.ui.home.HomeScreen
import dev.hostyn.calendar.ui.settings.SettingsScreen
import dev.hostyn.calendar.ui.theme.CalendarTheme
import dev.hostyn.calendar.ui.theme.LocalNavAnimatedScope
import dev.hostyn.calendar.ui.theme.LocalSharedTransitionScope
import dev.hostyn.calendar.ui.theme.LocalTransformOrigin
import dev.hostyn.calendar.ui.theme.TransformOrigin
import dev.hostyn.calendar.ui.theme.backwardEnter
import dev.hostyn.calendar.ui.theme.backwardExit
import dev.hostyn.calendar.ui.theme.forwardEnter
import dev.hostyn.calendar.ui.theme.forwardExit
import dev.hostyn.calendar.ui.theme.transformEnter
import dev.hostyn.calendar.ui.theme.transformExit
import kotlinx.coroutines.flow.filterNotNull
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Something the app was asked to show, by the widget, a notification or another app. */
sealed interface OpenRequest {
    data class Day(val date: LocalDate) : OpenRequest
    data class Event(val id: Long, val begin: Long, val end: Long) : OpenRequest
    data class Edit(val id: Long) : OpenRequest
    data class New(val prefill: Prefill) : OpenRequest
}

class MainActivity : ComponentActivity() {
    private val request = mutableStateOf<OpenRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) request.value = requestOf(intent)
        val app = application as CalendarApp
        setContent {
            CalendarTheme {
                PermissionGate { AppNavigation(app, request) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        request.value = requestOf(intent)
    }

    private fun requestOf(intent: Intent?): OpenRequest? {
        intent ?: return null
        val data = intent.data
        val segments = data?.pathSegments.orEmpty()
        val isCalendar = data?.authority == CalendarContract.AUTHORITY
        return when (intent.action) {
            Intent.ACTION_VIEW -> when {
                // content://com.android.calendar/time/<millis>: a day.
                isCalendar && segments.firstOrNull() == "time" -> segments.getOrNull(1)?.toLongOrNull()?.let {
                    OpenRequest.Day(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate())
                }
                isCalendar && segments.firstOrNull() == "events" -> segments.getOrNull(1)?.toLongOrNull()?.let {
                    OpenRequest.Event(
                        it,
                        intent.getLongExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, -1),
                        intent.getLongExtra(CalendarContract.EXTRA_EVENT_END_TIME, -1),
                    )
                }
                else -> null
            }
            Intent.ACTION_EDIT -> segments.getOrNull(1)?.toLongOrNull()?.let { OpenRequest.Edit(it) }
                ?: OpenRequest.New(prefillOf(intent))
            Intent.ACTION_INSERT -> OpenRequest.New(prefillOf(intent))
            else -> null
        }
    }

    /** "Add to calendar" from other apps: the standard extras of an insert intent. */
    private fun prefillOf(intent: Intent) = Prefill(
        title = intent.getStringExtra(Events.TITLE).orEmpty(),
        location = intent.getStringExtra(Events.EVENT_LOCATION).orEmpty(),
        description = intent.getStringExtra(Events.DESCRIPTION).orEmpty(),
        begin = intent.getLongExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, -1).takeIf { it > 0 },
        end = intent.getLongExtra(CalendarContract.EXTRA_EVENT_END_TIME, -1).takeIf { it > 0 },
        allDay = intent.getBooleanExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, false),
        rrule = intent.getStringExtra(Events.RRULE),
    )
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun AppNavigation(app: CalendarApp, request: MutableState<OpenRequest?>) {
    val nav = rememberNavController()
    // The day the month and agenda show; other screens can move it (a widget tap, a new event).
    val jumpTo = remember { mutableStateOf<LocalDate?>(null) }
    // New events from other apps carry more than a route can.
    val prefills = remember { HashMap<Int, Prefill>() }

    LaunchedEffect(Unit) {
        snapshotFlow { request.value }.filterNotNull().collect { r ->
            request.value = null
            when (r) {
                is OpenRequest.Day -> {
                    jumpTo.value = r.date
                    nav.popBackStack("home", inclusive = false)
                }
                is OpenRequest.Event -> nav.navigate("event/${r.id}?begin=${r.begin}&end=${r.end}") { popUpTo("home"); launchSingleTop = true }
                is OpenRequest.Edit -> nav.navigate("edit?id=${r.id}") { popUpTo("home") }
                is OpenRequest.New -> {
                    val key = prefills.size + 1
                    prefills[key] = r.prefill
                    nav.navigate("edit?prefill=$key") { popUpTo("home") }
                }
            }
        }
    }

    val density = LocalDensity.current
    val origin = remember { TransformOrigin() }
    /** [route], plus the element it grows out of if a row or button just asked for a container transform. */
    fun withOrigin(route: String): String {
        val key = origin.take() ?: return route
        return route + (if ('?' in route) "&" else "?") + "from=" + Uri.encode(key)
    }

    SharedTransitionLayout {
    CompositionLocalProvider(LocalSharedTransitionScope provides this, LocalTransformOrigin provides origin) {
    NavHost(
        navController = nav,
        startDestination = "home",
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
        // M3 forward and backward pattern; screens opened with a container transform only fade
        // underneath it.
        enterTransition = { if (targetState.transformKey() != null) transformEnter() else density.forwardEnter() },
        exitTransition = { if (targetState.transformKey() != null) transformExit() else density.forwardExit() },
        popEnterTransition = { if (initialState.transformKey() != null) transformEnter() else density.backwardEnter() },
        popExitTransition = { if (initialState.transformKey() != null) transformExit() else density.backwardExit() },
    ) {
        screen("home") {
            HomeScreen(
                app = app,
                jumpTo = jumpTo,
                onOpenEvent = { e -> nav.navigate(withOrigin("event/${e.eventId}?begin=${e.begin}&end=${e.end}")) },
                onNewEvent = { date -> nav.navigate(withOrigin("edit?date=${date.toEpochDay()}")) },
                onSettings = { nav.navigate("settings") },
            )
        }
        screen(
            "event/{id}?begin={begin}&end={end}&from={from}",
            listOf(
                navArgument("id") { type = NavType.LongType },
                navArgument("begin") { type = NavType.LongType; defaultValue = -1L },
                navArgument("end") { type = NavType.LongType; defaultValue = -1L },
                fromArgument(),
            ),
        ) { entry ->
            val args = entry.arguments!!
            val id = args.getLong("id")
            val begin = args.getLong("begin")
            EventScreen(
                app = app,
                eventId = id,
                instanceBegin = begin.takeIf { it >= 0 },
                instanceEnd = args.getLong("end").takeIf { it >= 0 },
                transformKey = entry.transformKey(),
                onBack = { if (!nav.popBackStack()) nav.navigate("home") },
                onEdit = { nav.navigate("edit?id=$id&begin=$begin") },
            )
        }
        screen(
            "edit?id={id}&begin={begin}&date={date}&prefill={prefill}&from={from}",
            listOf(
                navArgument("id") { type = NavType.LongType; defaultValue = -1L },
                navArgument("begin") { type = NavType.LongType; defaultValue = -1L },
                navArgument("date") { type = NavType.LongType; defaultValue = Long.MIN_VALUE },
                navArgument("prefill") { type = NavType.IntType; defaultValue = 0 },
                fromArgument(),
            ),
        ) { entry ->
            val args = entry.arguments!!
            val prefillKey = args.getInt("prefill")
            val prefill = remember(prefillKey) { prefills[prefillKey] }
            EditEventScreen(
                app = app,
                eventId = args.getLong("id").takeIf { it >= 0 },
                instanceBegin = args.getLong("begin").takeIf { it >= 0 },
                date = args.getLong("date").takeIf { it != Long.MIN_VALUE }?.let { LocalDate.ofEpochDay(it) },
                prefill = prefill,
                transformKey = entry.transformKey(),
                onDone = { savedOn ->
                    if (savedOn == null) {
                        if (!nav.popBackStack()) nav.navigate("home")
                    } else {
                        // Saved: back to the calendar, on the event's day (the details may be stale).
                        jumpTo.value = savedOn
                        if (!nav.popBackStack("home", inclusive = false)) nav.navigate("home")
                    }
                },
            )
        }
        screen("settings") {
            SettingsScreen(app = app, onBack = { nav.popBackStack() })
        }
    }
    }
    }
}

private fun fromArgument() = navArgument("from") { type = NavType.StringType; nullable = true; defaultValue = null }

/** The element this screen grows out of (container transform), if it was opened from one. */
private fun NavBackStackEntry.transformKey(): String? = arguments?.getString("from")

/** A destination whose content can take part in container transforms. */
private fun NavGraphBuilder.screen(
    route: String,
    arguments: List<NamedNavArgument> = emptyList(),
    content: @Composable (NavBackStackEntry) -> Unit,
) {
    composable(route, arguments) { entry ->
        CompositionLocalProvider(LocalNavAnimatedScope provides this) { content(entry) }
    }
}
