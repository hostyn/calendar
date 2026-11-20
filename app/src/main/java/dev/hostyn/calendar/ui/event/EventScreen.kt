@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package dev.hostyn.calendar.ui.event

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.EventBusy
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.toShape
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hostyn.calendar.CalendarApp
import dev.hostyn.calendar.R
import dev.hostyn.calendar.data.EventDetails
import dev.hostyn.calendar.data.Scope
import dev.hostyn.calendar.ui.components.EmptyState
import dev.hostyn.calendar.ui.components.ListHeader
import dev.hostyn.calendar.ui.components.SegmentedGroup
import dev.hostyn.calendar.ui.components.SegmentedGroupScope
import dev.hostyn.calendar.ui.components.formatTime
import dev.hostyn.calendar.ui.components.linkStyles
import dev.hostyn.calendar.ui.components.linkify
import dev.hostyn.calendar.ui.components.longDate
import dev.hostyn.calendar.ui.components.reminderLabel
import dev.hostyn.calendar.ui.components.segmentedColors
import dev.hostyn.calendar.ui.components.utcDate
import dev.hostyn.calendar.ui.theme.containerTransform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId

/** An event's details, with buttons to edit and delete it when its calendar allows. */
@Composable
fun EventScreen(
    app: CalendarApp,
    eventId: Long,
    instanceBegin: Long?,
    instanceEnd: Long?,
    transformKey: String?,
    onBack: () -> Unit,
    onEdit: () -> Unit,
) {
    val context = LocalContext.current
    val version by app.repository.version.collectAsStateWithLifecycle()
    // Null while loading; a missing event once it is known to be gone.
    val loaded by produceState<Result<EventDetails?>?>(null, eventId, version) {
        value = Result.success(withContext(Dispatchers.IO) { app.repository.event(eventId) })
    }
    var deleting by remember { mutableStateOf(false) }
    val event = loaded?.getOrNull()

    Scaffold(
        modifier = Modifier.containerTransform(transformKey, MaterialTheme.shapes.large),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack, shapes = IconButtonDefaults.shapes()) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back)) }
                },
                actions = {
                    if (event?.writable == true) {
                        IconButton(onClick = onEdit, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.Edit, stringResource(R.string.edit)) }
                        IconButton(onClick = { deleting = true }, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.Delete, stringResource(R.string.delete)) }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { padding ->
        when {
            loaded == null -> Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) { LoadingIndicator() }
            event == null -> EmptyState(
                icon = Icons.Rounded.EventBusy,
                title = stringResource(R.string.event_gone),
                body = stringResource(R.string.event_gone_body),
                modifier = Modifier.fillMaxSize().padding(padding),
            )
            else -> {
                val begin = instanceBegin ?: event.dtStart
                val end = instanceEnd ?: (begin + event.durationMillis)
                EventDetailsList(event, begin, end, PaddingValues(top = padding.calculateTopPadding()))
            }
        }
    }

    if (deleting && event != null) {
        val begin = instanceBegin ?: event.dtStart
        val delete: (Scope) -> Unit = { scope ->
            deleting = false
            app.scope.launch {
                val ok = runCatching { app.repository.delete(event, begin, scope) }.isSuccess
                withContext(Dispatchers.Main) {
                    if (!ok) Toast.makeText(context, R.string.error_delete, Toast.LENGTH_SHORT).show()
                }
            }
            onBack()
        }
        if (event.recurring && event.originalId == null) {
            ScopeDialog(
                title = stringResource(R.string.delete_repeating),
                confirm = stringResource(R.string.delete),
                allowThis = true,
                onConfirm = delete,
                onDismiss = { deleting = false },
            )
        } else {
            ConfirmDialog(
                title = stringResource(R.string.delete_event_title),
                body = event.title.ifBlank { stringResource(R.string.no_title) },
                confirm = stringResource(R.string.delete),
                onConfirm = { delete(Scope.All) },
                onDismiss = { deleting = false },
            )
        }
    }
}

@Composable
private fun EventDetailsList(event: EventDetails, begin: Long, end: Long, padding: PaddingValues) {
    val context = LocalContext.current
    val zone = ZoneId.systemDefault()
    fun open(uri: Uri) = runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
        .onFailure { Toast.makeText(context, R.string.error_no_app, Toast.LENGTH_SHORT).show() }

    // When: the day (or days) and the time.
    val firstDay = if (event.allDay) utcDate(begin) else Instant.ofEpochMilli(begin).atZone(zone).toLocalDate()
    val lastDay = if (event.allDay) utcDate(end).minusDays(1).let { if (it < firstDay) firstDay else it }
    else Instant.ofEpochMilli(maxOf(begin, end - 1)).atZone(zone).toLocalDate()
    val dateLine = if (firstDay == lastDay) longDate(context, firstDay)
    else stringResource(R.string.date_range, longDate(context, firstDay, abbreviated = true), longDate(context, lastDay, abbreviated = true))
    val timeLine = when {
        event.allDay -> stringResource(R.string.all_day)
        firstDay == lastDay -> if (begin == end) formatTime(context, begin) else "${formatTime(context, begin)} – ${formatTime(context, end)}"
        else -> stringResource(R.string.date_range, formatTime(context, begin), formatTime(context, end))
    }

    LazyColumn(Modifier.fillMaxSize().navigationBarsPadding(), contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = 24.dp)) {
        item {
            Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 20.dp)) {
                // The calendar's color in an expressive shape marks whose event this is.
                Box(Modifier.size(44.dp).background(Color(event.color), MaterialShapes.Cookie6Sided.toShape()))
                Spacer(Modifier.height(16.dp))
                Text(
                    event.title.ifBlank { stringResource(R.string.no_title) },
                    style = MaterialTheme.typography.headlineMediumEmphasized,
                )
            }
        }
        item {
            SegmentedGroup {
                info(Icons.Rounded.Schedule, dateLine, timeLine)
                if (event.recurring) info(Icons.Rounded.Repeat, describeRule(context, event.rrule, firstDay))
                calendar(event)
                if (event.location.isNotBlank()) {
                    info(Icons.Rounded.Place, event.location) { open(Uri.parse("geo:0,0?q=" + Uri.encode(event.location))) }
                }
            }
        }
        if (event.reminders.isNotEmpty()) {
            item { ListHeader(stringResource(R.string.reminders), Modifier.padding(top = 12.dp)) }
            item {
                SegmentedGroup {
                    event.reminders.forEach { info(Icons.Rounded.Notifications, reminderLabel(context, it, event.allDay)) }
                }
            }
        }
        if (event.description.isNotBlank()) {
            item { ListHeader(stringResource(R.string.description), Modifier.padding(top = 12.dp)) }
            item {
                SegmentedGroup {
                    item { shapes ->
                        SegmentedListItem(
                            shapes = shapes,
                            colors = segmentedColors(),
                            leadingContent = { Icon(Icons.AutoMirrored.Rounded.Notes, null) },
                        ) { Text(linkify(event.description.trim(), linkStyles(MaterialTheme.colorScheme.primary))) }
                    }
                }
            }
        }
        if (event.attendees.isNotEmpty()) {
            item { ListHeader(stringResource(R.string.guests), Modifier.padding(top = 12.dp)) }
            item {
                SegmentedGroup {
                    event.attendees.forEach { a ->
                        info(Icons.Rounded.Group, a.name.ifBlank { a.email }, a.email.takeIf { a.name.isNotBlank() })
                    }
                }
            }
        }
    }
}

private fun SegmentedGroupScope.info(icon: ImageVector, title: String, summary: String? = null, onClick: (() -> Unit)? = null) =
    item { shapes ->
        val leading: @Composable () -> Unit = { Icon(icon, null) }
        val supporting: (@Composable () -> Unit)? = summary?.takeIf { it.isNotBlank() }?.let { { Text(it) } }
        if (onClick != null) {
            SegmentedListItem(onClick = onClick, shapes = shapes, colors = segmentedColors(), leadingContent = leading, supportingContent = supporting) { Text(title) }
        } else {
            SegmentedListItem(shapes = shapes, colors = segmentedColors(), leadingContent = leading, supportingContent = supporting) { Text(title) }
        }
    }

private fun SegmentedGroupScope.calendar(event: EventDetails) = item { shapes ->
    SegmentedListItem(
        shapes = shapes,
        colors = segmentedColors(),
        leadingContent = {
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(16.dp).background(Color(event.calendarColor), CircleShape))
            }
        },
        supportingContent = if (!event.writable) { { Text(stringResource(R.string.read_only)) } } else null,
    ) { Text(event.calendarName) }
}

