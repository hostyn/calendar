@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package dev.hostyn.calendar.ui.settings

import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.ViewWeek
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hostyn.calendar.CalendarApp
import dev.hostyn.calendar.R
import dev.hostyn.calendar.data.AppSettings
import dev.hostyn.calendar.data.CalendarInfo
import dev.hostyn.calendar.data.ThemeMode
import dev.hostyn.calendar.ui.components.ListHeader
import dev.hostyn.calendar.ui.components.SegmentedGroup
import dev.hostyn.calendar.ui.components.SegmentedGroupScope
import dev.hostyn.calendar.ui.components.TIMED_REMINDERS
import dev.hostyn.calendar.ui.components.capitalized
import dev.hostyn.calendar.ui.components.reminderLabel
import dev.hostyn.calendar.ui.components.segmentedColors
import dev.hostyn.calendar.ui.event.ChoiceDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

private enum class Choosing { Theme, WeekStart, DefaultCalendar, Reminder }

@Composable
fun SettingsScreen(app: CalendarApp, onBack: () -> Unit) {
    val context = LocalContext.current
    val settings = app.settings
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    var choosing by remember { mutableStateOf<Choosing?>(null) }
    val version by app.repository.version.collectAsStateWithLifecycle()
    val calendars by produceState(emptyList<CalendarInfo>(), version) {
        value = withContext(Dispatchers.IO) { app.repository.calendars() }
    }
    val defaultCalendar = remember(calendars, settings.defaultCalendar, settings.hiddenCalendars) { app.repository.defaultCalendar(calendars) }

    val noApp = stringResource(R.string.error_no_app)
    fun open(intent: Intent) = try {
        context.startActivity(intent)
    } catch (_: Exception) {
        Toast.makeText(context, noApp, Toast.LENGTH_SHORT).show()
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack, shapes = IconButtonDefaults.shapes()) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back)) }
                },
                scrollBehavior = scroll,
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = 24.dp),
            modifier = Modifier.fillMaxSize().navigationBarsPadding(),
        ) {
            // Calendars, one group per account.
            calendars.groupBy { it.accountName }.forEach { (account, list) ->
                item(key = "account-$account") {
                    Section(account.ifEmpty { stringResource(R.string.calendars) }) {
                        list.forEach { calendar(it, shown = it.id !in settings.hiddenCalendars) { shown -> settings.setCalendarShown(it.id, shown) } }
                    }
                }
            }
            item {
                Section(stringResource(R.string.events_settings)) {
                    setting(
                        icon = Icons.Rounded.CalendarMonth,
                        title = stringResource(R.string.default_calendar),
                        summary = defaultCalendar?.name.orEmpty(),
                        onClick = { choosing = Choosing.DefaultCalendar },
                    )
                    setting(
                        icon = Icons.Rounded.NotificationsActive,
                        title = stringResource(R.string.default_reminder),
                        summary = if (settings.defaultReminder == AppSettings.NO_REMINDER) stringResource(R.string.no_reminder)
                        else reminderLabel(context, settings.defaultReminder, false),
                        onClick = { choosing = Choosing.Reminder },
                    )
                    setting(
                        icon = Icons.Rounded.ViewWeek,
                        title = stringResource(R.string.week_starts),
                        summary = settings.weekStart?.let { dayName(it) } ?: stringResource(R.string.week_starts_locale, dayName(settings.firstDayOfWeek())),
                        onClick = { choosing = Choosing.WeekStart },
                    )
                    setting(
                        icon = Icons.Rounded.Notifications,
                        title = stringResource(R.string.notifications),
                        summary = stringResource(R.string.notifications_summary),
                        onClick = {
                            open(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                        },
                    )
                }
            }
            item {
                Section(stringResource(R.string.appearance)) {
                    setting(
                        icon = Icons.Rounded.DarkMode,
                        title = stringResource(R.string.theme),
                        summary = stringResource(themeLabel(settings.theme)),
                        onClick = { choosing = Choosing.Theme },
                    )
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        toggle(
                            icon = Icons.Rounded.Palette,
                            title = stringResource(R.string.dynamic_color),
                            summary = stringResource(R.string.dynamic_color_summary),
                            checked = settings.dynamicColor,
                            onChange = { settings.updateDynamicColor(it); dev.hostyn.calendar.widget.WidgetUpdates.refresh(context) },
                        )
                    }
                }
            }
            item {
                Section(stringResource(R.string.about)) {
                    val appVersion = remember {
                        try { context.packageManager.getPackageInfo(context.packageName, 0).versionName } catch (_: Exception) { null }
                    }
                    setting(icon = Icons.Rounded.Info, title = stringResource(R.string.version), summary = appVersion.orEmpty())
                    setting(
                        icon = Icons.Rounded.Code,
                        title = stringResource(R.string.source_code),
                        summary = "github.com/hostyn/calendar",
                        onClick = { open(Intent(Intent.ACTION_VIEW, "https://github.com/hostyn/calendar".toUri())) },
                    )
                }
            }
        }
    }

    when (choosing) {
        Choosing.Theme -> ChoiceDialog(
            title = stringResource(R.string.theme),
            labels = ThemeMode.entries.map { stringResource(themeLabel(it)) },
            selected = ThemeMode.entries.indexOf(settings.theme),
            onSelect = { settings.updateTheme(ThemeMode.entries[it]) },
            onDismiss = { choosing = null },
        )
        Choosing.WeekStart -> {
            val days = listOf(null, DayOfWeek.MONDAY, DayOfWeek.SUNDAY, DayOfWeek.SATURDAY)
            ChoiceDialog(
                title = stringResource(R.string.week_starts),
                labels = days.map { d -> d?.let { dayName(it) } ?: stringResource(R.string.week_starts_locale, dayName(settings.firstDayOfWeekOfLocale())) },
                selected = days.indexOf(settings.weekStart),
                onSelect = { settings.updateWeekStart(days[it]) },
                onDismiss = { choosing = null },
            )
        }
        Choosing.DefaultCalendar -> {
            val writable = calendars.filter { it.writable }
            ChoiceDialog(
                title = stringResource(R.string.default_calendar),
                labels = writable.map { it.name },
                selected = writable.indexOfFirst { it.id == defaultCalendar?.id },
                onSelect = { settings.updateDefaultCalendar(writable[it].id) },
                onDismiss = { choosing = null },
            )
        }
        Choosing.Reminder -> {
            val options = listOf(AppSettings.NO_REMINDER) + TIMED_REMINDERS
            ChoiceDialog(
                title = stringResource(R.string.default_reminder),
                labels = options.map { if (it == AppSettings.NO_REMINDER) stringResource(R.string.no_reminder) else reminderLabel(context, it, false) },
                selected = options.indexOf(settings.defaultReminder),
                onSelect = { settings.updateDefaultReminder(options[it]) },
                onDismiss = { choosing = null },
            )
        }
        null -> {}
    }
}

private fun dayName(day: DayOfWeek) = day.getDisplayName(TextStyle.FULL_STANDALONE, Locale.getDefault()).capitalized()

private fun AppSettings.firstDayOfWeekOfLocale(): DayOfWeek = java.time.temporal.WeekFields.of(Locale.getDefault()).firstDayOfWeek

private fun themeLabel(t: ThemeMode) = when (t) {
    ThemeMode.System -> R.string.theme_system
    ThemeMode.Light -> R.string.theme_light
    ThemeMode.Dark -> R.string.theme_dark
}

/** A titled M3 segmented list of settings. */
// Inline, like SegmentedGroup, so a switch's new value redraws its item (see SegmentedGroup).
@Composable
private inline fun Section(title: String, content: @Composable SegmentedGroupScope.() -> Unit) {
    ListHeader(title, Modifier.padding(top = 8.dp))
    SegmentedGroup(content = content)
}

/** A calendar: its color, its name, and a switch to show its events. */
private fun SegmentedGroupScope.calendar(calendar: CalendarInfo, shown: Boolean, onChange: (Boolean) -> Unit) = item { shapes ->
    SegmentedListItem(
        checked = shown,
        onCheckedChange = onChange,
        shapes = shapes,
        colors = segmentedColors(),
        leadingContent = {
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(18.dp).background(Color(calendar.color), CircleShape))
            }
        },
        supportingContent = if (!calendar.writable) { { Text(stringResource(R.string.read_only)) } } else null,
        trailingContent = { Switch(checked = shown, onCheckedChange = null) },
    ) { Text(calendar.name) }
}

/** A setting: tap to change it, or plain information without [onClick]. */
private fun SegmentedGroupScope.setting(
    icon: ImageVector,
    title: String,
    summary: String? = null,
    onClick: (() -> Unit)? = null,
) = item { shapes ->
    val leading: @Composable () -> Unit = { Icon(icon, null) }
    val supporting: (@Composable () -> Unit)? = summary?.takeIf { it.isNotEmpty() }?.let { { Text(it) } }
    if (onClick != null) {
        SegmentedListItem(onClick = onClick, shapes = shapes, colors = segmentedColors(), leadingContent = leading, supportingContent = supporting) { Text(title) }
    } else {
        SegmentedListItem(shapes = shapes, colors = segmentedColors(), leadingContent = leading, supportingContent = supporting) { Text(title) }
    }
}

/** An on/off setting: the whole item toggles the switch. */
private fun SegmentedGroupScope.toggle(
    icon: ImageVector,
    title: String,
    summary: String? = null,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) = item { shapes ->
    SegmentedListItem(
        checked = checked,
        onCheckedChange = onChange,
        shapes = shapes,
        colors = segmentedColors(),
        leadingContent = { Icon(icon, null) },
        supportingContent = summary?.let { { Text(it) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
    ) { Text(title) }
}
