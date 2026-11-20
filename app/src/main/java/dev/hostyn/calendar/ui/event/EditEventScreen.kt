@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package dev.hostyn.calendar.ui.event

import android.text.format.DateFormat
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDialog
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import dev.hostyn.calendar.CalendarApp
import dev.hostyn.calendar.R
import dev.hostyn.calendar.data.AppSettings
import dev.hostyn.calendar.data.CalendarInfo
import dev.hostyn.calendar.data.EventDetails
import dev.hostyn.calendar.data.EventDraft
import dev.hostyn.calendar.data.Recurrence
import dev.hostyn.calendar.data.RepeatPreset
import dev.hostyn.calendar.data.Scope
import dev.hostyn.calendar.ui.components.ALL_DAY_DEFAULT_REMINDER
import dev.hostyn.calendar.ui.components.ALL_DAY_REMINDERS
import dev.hostyn.calendar.ui.components.SegmentedGroup
import dev.hostyn.calendar.ui.components.SegmentedGroupScope
import dev.hostyn.calendar.ui.components.TIMED_REMINDERS
import dev.hostyn.calendar.ui.components.longDate
import dev.hostyn.calendar.ui.components.reminderLabel
import dev.hostyn.calendar.ui.components.segmentedColors
import dev.hostyn.calendar.ui.theme.containerTransform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/** What another app asked to add ("Add to calendar"). */
data class Prefill(
    val title: String = "",
    val location: String = "",
    val description: String = "",
    val begin: Long? = null,
    val end: Long? = null,
    val allDay: Boolean = false,
    val rrule: String? = null,
)

private class Loaded(val existing: EventDetails?, val draft: EventDraft, val calendars: List<CalendarInfo>)

/** Which date or time picker is open. */
private enum class Picking { StartDate, StartTime, EndDate, EndTime }

/**
 * Creates an event ([eventId] null) or changes one. For a repeating event, [instanceBegin] is the
 * occurrence it was opened from; saving asks which occurrences the change is for.
 */
@Composable
fun EditEventScreen(
    app: CalendarApp,
    eventId: Long?,
    instanceBegin: Long?,
    date: LocalDate?,
    prefill: Prefill?,
    transformKey: String?,
    onDone: (savedOn: LocalDate?) -> Unit,
) {
    val loaded by produceState<Loaded?>(null, eventId) {
        value = withContext(Dispatchers.IO) { load(app, eventId, instanceBegin, date, prefill) }
    }
    Box(Modifier.fillMaxSize().containerTransform(transformKey, MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.surface)) {
        val l = loaded
        if (l == null) {
            Box(Modifier.fillMaxSize(), Alignment.Center) { LoadingIndicator() }
        } else if (l.calendars.isEmpty()) {
            NoCalendar(onDone)
        } else {
            Editor(app, l, instanceBegin, onDone)
        }
    }
}

private fun load(app: CalendarApp, eventId: Long?, instanceBegin: Long?, date: LocalDate?, prefill: Prefill?): Loaded {
    val repo = app.repository
    val calendars = repo.calendars().filter { it.writable }
    val zone = ZoneId.systemDefault()
    val existing = eventId?.let { repo.event(it) }
    if (existing != null) {
        // A series is edited from the occurrence it was opened from.
        val begin = if (existing.recurring && existing.originalId == null && instanceBegin != null) instanceBegin else existing.dtStart
        val tz = if (existing.allDay) ZoneOffset.UTC else zone
        val start = LocalDateTime.ofInstant(Instant.ofEpochMilli(begin), tz)
        var end = LocalDateTime.ofInstant(Instant.ofEpochMilli(begin + existing.durationMillis), tz)
        // All-day events end at the next midnight; the editor shows the last day.
        if (existing.allDay) end = end.minusDays(1)
        if (end < start) end = start
        val draft = EventDraft(
            calendarId = existing.calendarId,
            title = existing.title,
            location = existing.location,
            description = existing.description,
            allDay = existing.allDay,
            start = start,
            end = end,
            zone = zone,
            rrule = existing.rrule,
            reminders = existing.reminders,
        )
        return Loaded(existing, draft, calendars)
    }
    val calendar = repo.defaultCalendar(repo.calendars())
    val now = LocalDateTime.now(zone)
    val day = date ?: now.toLocalDate()
    // Today: the next full hour. Another day: 9:00.
    val start = prefill?.begin?.let { LocalDateTime.ofInstant(Instant.ofEpochMilli(it), if (prefill.allDay) ZoneOffset.UTC else zone) }
        ?: if (day == now.toLocalDate()) now.toLocalDate().atTime(now.hour, 0).plusHours(1) else day.atTime(9, 0)
    var end = prefill?.end?.let { LocalDateTime.ofInstant(Instant.ofEpochMilli(it), if (prefill.allDay) ZoneOffset.UTC else zone) }
        ?: start.plusHours(1)
    val allDay = prefill?.allDay == true
    if (allDay && prefill?.end != null) end = end.minusDays(1)
    if (end < start) end = start
    val reminder = if (allDay) ALL_DAY_DEFAULT_REMINDER else app.settings.defaultReminder
    val draft = EventDraft(
        calendarId = calendar?.id ?: -1,
        title = prefill?.title.orEmpty(),
        location = prefill?.location.orEmpty(),
        description = prefill?.description.orEmpty(),
        allDay = allDay,
        start = start,
        end = end,
        zone = zone,
        rrule = prefill?.rrule,
        reminders = if (reminder == AppSettings.NO_REMINDER) emptyList() else listOf(reminder),
    )
    return Loaded(null, draft, calendars)
}

@Composable
private fun Editor(app: CalendarApp, loaded: Loaded, instanceBegin: Long?, onDone: (LocalDate?) -> Unit) {
    val context = LocalContext.current
    val existing = loaded.existing
    val initial = loaded.draft
    var draft by remember { mutableStateOf(initial) }
    // Repeat option picked in the editor; until then the event keeps its own rule as it is.
    var preset by remember { mutableStateOf<RepeatPreset?>(null) }
    var picking by remember { mutableStateOf<Picking?>(null) }
    var choosingCalendar by remember { mutableStateOf(false) }
    var choosingRepeat by remember { mutableStateOf(false) }
    var addingReminder by remember { mutableStateOf(false) }
    var askingScope by remember { mutableStateOf(false) }
    var askingDiscard by remember { mutableStateOf(false) }
    val titleFocus = remember { FocusRequester() }

    val startDate = draft.start.toLocalDate()
    val rrule = preset?.let { Recurrence.rruleFor(it, startDate) }
        // A rule tied to the start day (weekly on Mondays…) follows it when the start moves.
        ?: initial.rrule?.let { r ->
            val original = Recurrence.presetOf(r, initial.start.toLocalDate())
            if (startDate != initial.start.toLocalDate() && original != null && original != RepeatPreset.None) Recurrence.rruleFor(original, startDate) else r
        }
    val result = draft.copy(rrule = rrule)
    val dirty = result != initial
    val series = existing != null && existing.recurring && existing.originalId == null
    val canRepeat = existing?.originalId == null

    fun save(scope: Scope) {
        app.scope.launch {
            val ok = runCatching { app.repository.save(result, existing, instanceBegin ?: existing?.dtStart, scope) }.isSuccess
            withContext(Dispatchers.Main) {
                if (!ok) Toast.makeText(context, R.string.error_save, Toast.LENGTH_SHORT).show()
            }
        }
        onDone(result.start.toLocalDate())
    }

    fun close() = if (dirty) askingDiscard = true else onDone(null)
    BackHandler(onBack = ::close)
    LaunchedEffect(Unit) { if (existing == null && initial.title.isEmpty()) runCatching { titleFocus.requestFocus() } }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (existing == null) R.string.new_event else R.string.edit_event)) },
                navigationIcon = {
                    IconButton(onClick = ::close, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.Close, stringResource(R.string.close)) }
                },
                actions = {
                    Button(
                        onClick = { if (series && dirty) askingScope = true else if (dirty || existing == null) save(Scope.All) else onDone(null) },
                        shapes = ButtonDefaults.shapes(),
                        modifier = Modifier.padding(end = 8.dp),
                    ) { Text(stringResource(R.string.save)) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()).imePadding().navigationBarsPadding()
                .verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
        ) {
            TextField(
                value = draft.title,
                onValueChange = { draft = draft.copy(title = it) },
                placeholder = { Text(stringResource(R.string.add_title), style = MaterialTheme.typography.headlineSmall) },
                textStyle = MaterialTheme.typography.headlineSmall,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).focusRequester(titleFocus),
            )
            Spacer(Modifier.height(8.dp))

            val calendar = loaded.calendars.firstOrNull { it.id == draft.calendarId }
            SegmentedGroup {
                item { shapes ->
                    val leading: @Composable () -> Unit = {
                        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                            Box(Modifier.size(16.dp).background(Color(calendar?.color ?: 0xFF888888.toInt()), CircleShape))
                        }
                    }
                    val supporting = calendar?.accountName?.takeIf { it.isNotEmpty() && it != calendar.name }?.let { @Composable { Text(it) } }
                    val name = calendar?.name ?: stringResource(R.string.calendar)
                    // An event stays in its calendar: moving it between accounts isn't supported.
                    if (existing == null) {
                        SegmentedListItem(
                            onClick = { choosingCalendar = true },
                            shapes = shapes,
                            colors = segmentedColors(),
                            leadingContent = leading,
                            supportingContent = supporting,
                        ) { Text(name) }
                    } else {
                        SegmentedListItem(shapes = shapes, colors = segmentedColors(), leadingContent = leading, supportingContent = supporting) { Text(name) }
                    }
                }
                item { shapes ->
                    SegmentedListItem(
                        checked = draft.allDay,
                        onCheckedChange = { allDay ->
                            // Swap the default reminder for its all-day counterpart, and back.
                            val timedDefault = listOf(app.settings.defaultReminder)
                            val reminders = when {
                                allDay && draft.reminders == timedDefault -> listOf(ALL_DAY_DEFAULT_REMINDER)
                                !allDay && draft.reminders == listOf(ALL_DAY_DEFAULT_REMINDER) && app.settings.defaultReminder != AppSettings.NO_REMINDER -> timedDefault
                                else -> draft.reminders
                            }
                            draft = draft.copy(allDay = allDay, reminders = reminders)
                        },
                        shapes = shapes,
                        colors = segmentedColors(),
                        leadingContent = { Icon(Icons.Rounded.WbSunny, null) },
                        trailingContent = { Switch(checked = draft.allDay, onCheckedChange = null) },
                    ) { Text(stringResource(R.string.all_day)) }
                }
                dateTimeRow(
                    label = R.string.starts,
                    dateTime = draft.start,
                    allDay = draft.allDay,
                    zone = draft.zone,
                    onDate = { picking = Picking.StartDate },
                    onTime = { picking = Picking.StartTime },
                )
                dateTimeRow(
                    label = R.string.ends,
                    dateTime = draft.end,
                    allDay = draft.allDay,
                    zone = draft.zone,
                    onDate = { picking = Picking.EndDate },
                    onTime = { picking = Picking.EndTime },
                )
            }

            Spacer(Modifier.height(16.dp))
            SegmentedGroup {
                if (canRepeat) {
                    item { shapes ->
                        SegmentedListItem(
                            onClick = { choosingRepeat = true },
                            shapes = shapes,
                            colors = segmentedColors(),
                            leadingContent = { Icon(Icons.Rounded.Repeat, null) },
                        ) { Text(describeRule(context, rrule, startDate)) }
                    }
                }
                draft.reminders.sorted().forEach { m ->
                    item { shapes ->
                        SegmentedListItem(
                            shapes = shapes,
                            colors = segmentedColors(),
                            leadingContent = { Icon(Icons.Rounded.Notifications, null) },
                            trailingContent = {
                                IconButton(onClick = { draft = draft.copy(reminders = draft.reminders - m) }, shapes = IconButtonDefaults.shapes()) {
                                    Icon(Icons.Rounded.Close, stringResource(R.string.remove_reminder))
                                }
                            },
                        ) { Text(reminderLabel(context, m, draft.allDay)) }
                    }
                }
                item { shapes ->
                    SegmentedListItem(
                        onClick = { addingReminder = true },
                        shapes = shapes,
                        colors = segmentedColors(),
                        leadingContent = { Icon(Icons.Rounded.Add, null) },
                    ) { Text(stringResource(R.string.add_reminder)) }
                }
            }

            Spacer(Modifier.height(16.dp))
            Field(draft.location, { draft = draft.copy(location = it) }, R.string.add_location, Icons.Rounded.Place, singleLine = true)
            Spacer(Modifier.height(ListItemGap))
            Field(draft.description, { draft = draft.copy(description = it) }, R.string.add_description, Icons.AutoMirrored.Rounded.Notes, singleLine = false)
        }
    }

    when (picking) {
        Picking.StartDate, Picking.EndDate -> {
            val isStart = picking == Picking.StartDate
            val current = (if (isStart) draft.start else draft.end).toLocalDate()
            val state = rememberDatePickerState(initialSelectedDateMillis = current.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
            DatePickerDialog(
                onDismissRequest = { picking = null },
                confirmButton = {
                    TextButton(
                        onClick = {
                            state.selectedDateMillis?.let { millis ->
                                val day = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                                draft = if (isStart) moveStart(draft, draft.start.with(day)) else setEnd(draft, draft.end.with(day))
                            }
                            picking = null
                        },
                        shapes = ButtonDefaults.shapes(),
                    ) { Text(stringResource(R.string.ok)) }
                },
                dismissButton = { TextButton(onClick = { picking = null }, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.cancel)) } },
            ) { DatePicker(state) }
        }
        Picking.StartTime, Picking.EndTime -> {
            val isStart = picking == Picking.StartTime
            val current = if (isStart) draft.start else draft.end
            val state = rememberTimePickerState(current.hour, current.minute, DateFormat.is24HourFormat(context))
            TimePickerDialog(
                onDismissRequest = { picking = null },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val time = current.with(LocalTime.of(state.hour, state.minute))
                            draft = if (isStart) moveStart(draft, time) else setEnd(draft, time)
                            picking = null
                        },
                        shapes = ButtonDefaults.shapes(),
                    ) { Text(stringResource(R.string.ok)) }
                },
                dismissButton = { TextButton(onClick = { picking = null }, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.cancel)) } },
                title = { Text(stringResource(if (isStart) R.string.starts else R.string.ends)) },
            ) { TimePicker(state) }
        }
        null -> {}
    }

    if (choosingCalendar) {
        ChoiceDialog(
            title = stringResource(R.string.calendar),
            labels = loaded.calendars.map { it.name },
            selected = loaded.calendars.indexOfFirst { it.id == draft.calendarId },
            onSelect = { draft = draft.copy(calendarId = loaded.calendars[it].id) },
            onDismiss = { choosingCalendar = false },
        )
    }
    if (choosingRepeat) {
        // The event's own rule stays on the list when it isn't one of the options.
        val custom = initial.rrule?.takeIf { Recurrence.presetOf(it, initial.start.toLocalDate()) == null }
        val options = RepeatPreset.entries.map { presetLabel(context, it, startDate) } +
            listOfNotNull(custom?.let { describeRule(context, it, startDate) })
        val current = preset ?: Recurrence.presetOf(rrule, startDate)
        ChoiceDialog(
            title = stringResource(R.string.repeat),
            labels = options,
            selected = current?.ordinal ?: options.lastIndex,
            onSelect = { i -> preset = RepeatPreset.entries.getOrNull(i) },
            onDismiss = { choosingRepeat = false },
        )
    }
    if (addingReminder) {
        val options = (if (draft.allDay) ALL_DAY_REMINDERS else TIMED_REMINDERS).filter { it !in draft.reminders }
        ChoiceDialog(
            title = stringResource(R.string.add_reminder),
            labels = options.map { reminderLabel(context, it, draft.allDay) },
            selected = -1,
            onSelect = { draft = draft.copy(reminders = draft.reminders + options[it]) },
            onDismiss = { addingReminder = false },
        )
    }
    if (askingScope) {
        ScopeDialog(
            title = stringResource(R.string.save_repeating),
            confirm = stringResource(R.string.save),
            // A new rule can't apply to a single occurrence.
            allowThis = rrule == initial.rrule,
            onConfirm = { askingScope = false; save(it) },
            onDismiss = { askingScope = false },
        )
    }
    if (askingDiscard) {
        ConfirmDialog(
            title = stringResource(R.string.discard_title),
            body = null,
            confirm = stringResource(R.string.discard),
            onConfirm = { askingDiscard = false; onDone(null) },
            onDismiss = { askingDiscard = false },
        )
    }
}

private val ListItemGap = 8.dp

/** Moving the start moves the end too, keeping the length. */
private fun moveStart(draft: EventDraft, start: LocalDateTime): EventDraft {
    val length = Duration.between(draft.start, draft.end)
    return draft.copy(start = start, end = start.plus(length))
}

/** An end before the start keeps the event's length from the start instead. */
private fun setEnd(draft: EventDraft, end: LocalDateTime): EventDraft =
    if (end >= draft.start) draft.copy(end = end)
    else draft.copy(end = if (draft.allDay) draft.start else draft.start.plusHours(1))

private fun SegmentedGroupScope.dateTimeRow(
    label: Int,
    dateTime: LocalDateTime,
    allDay: Boolean,
    zone: ZoneId,
    onDate: () -> Unit,
    onTime: () -> Unit,
) = item { shapes ->
    val context = LocalContext.current
    SegmentedListItem(
        onClick = onDate,
        shapes = shapes,
        colors = segmentedColors(),
        leadingContent = { Icon(Icons.Rounded.Schedule, null) },
        overlineContent = { Text(stringResource(label)) },
        trailingContent = if (allDay) null else {
            {
                FilledTonalButton(onClick = onTime, shapes = ButtonDefaults.shapes()) {
                    Text(DateFormat.getTimeFormat(context).format(dateTime.atZone(zone).toInstant().toEpochMilli()))
                }
            }
        },
    ) { Text(longDate(context, dateTime.toLocalDate(), abbreviated = true)) }
}

@Composable
private fun Field(value: String, onValue: (String) -> Unit, placeholder: Int, icon: androidx.compose.ui.graphics.vector.ImageVector, singleLine: Boolean) {
    TextField(
        value = value,
        onValueChange = onValue,
        placeholder = { Text(stringResource(placeholder)) },
        leadingIcon = { Icon(icon, null) },
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 3,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        shape = MaterialTheme.shapes.large,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    )
}

@Composable
private fun NoCalendar(onDone: (LocalDate?) -> Unit) {
    ConfirmDialog(
        title = stringResource(R.string.no_calendar_title),
        body = stringResource(R.string.no_calendar_body),
        confirm = stringResource(R.string.ok),
        onConfirm = { onDone(null) },
        onDismiss = { onDone(null) },
    )
}
