@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.hostyn.calendar.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.hostyn.calendar.CalendarApp
import dev.hostyn.calendar.R
import dev.hostyn.calendar.widget.WidgetUpdates

/** Calendar access, plus notifications for reminders (Android 13+), asked for together. */
private fun permissions(): Array<String> = buildList {
    add(Manifest.permission.READ_CALENDAR)
    add(Manifest.permission.WRITE_CALENDAR)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
}.toTypedArray()

/** Asks for calendar access and shows [content] once the app has it. */
@Composable
fun PermissionGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val app = CalendarApp.of(context)
    val repository = app.repository
    var granted by remember { mutableStateOf(repository.hasPermission()) }
    var asked by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        asked = true
        granted = repository.hasPermission()
    }
    val request = { launcher.launch(permissions()) }

    LifecycleResumeEffect(Unit) {
        granted = repository.hasPermission()
        onPauseOrDispose {}
    }
    LaunchedEffect(granted) {
        if (granted) {
            repository.start()
            // A widget placed before the permission was granted shows a message: redraw it.
            WidgetUpdates.refresh(context)
        }
    }
    LaunchedEffect(Unit) { if (!granted && !asked) request() }

    if (!granted) {
        PermissionScreen(
            onAllow = {
                val activity = context as? Activity
                val canAsk = !asked || permissions().any { activity?.shouldShowRequestPermissionRationale(it) == true }
                if (canAsk) {
                    request()
                } else {
                    // Denied for good: only the app's settings page can grant it now.
                    runCatching {
                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()))
                    }
                }
            },
        )
    } else {
        content()
    }
}

@Composable
private fun PermissionScreen(onAllow: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Box(Modifier.fillMaxSize().safeDrawingPadding().padding(32.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Box(
                    Modifier.size(128.dp).background(MaterialTheme.colorScheme.primaryContainer, MaterialShapes.Cookie12Sided.toShape()),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.CalendarMonth, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(52.dp))
                }
                Spacer(Modifier.height(28.dp))
                Text(stringResource(R.string.permission_title), style = MaterialTheme.typography.headlineSmallEmphasized, textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.permission_body),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(32.dp))
                val height = ButtonDefaults.MediumContainerHeight
                Button(
                    onClick = onAllow,
                    shapes = ButtonDefaults.shapesFor(height),
                    contentPadding = ButtonDefaults.contentPaddingFor(height),
                    modifier = Modifier.heightIn(height),
                ) { Text(stringResource(R.string.allow_access), style = ButtonDefaults.textStyleFor(height)) }
            }
        }
    }
}
