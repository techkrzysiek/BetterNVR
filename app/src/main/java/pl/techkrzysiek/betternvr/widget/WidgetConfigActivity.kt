package pl.techkrzysiek.betternvr.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.launch
import pl.techkrzysiek.betternvr.App
import pl.techkrzysiek.betternvr.R
import pl.techkrzysiek.betternvr.data.ChannelRef
import pl.techkrzysiek.betternvr.proto.DeviceInfo
import pl.techkrzysiek.betternvr.ui.common.app
import pl.techkrzysiek.betternvr.ui.theme.BetterNvrTheme

/** Shown when a widget is added (or reconfigured): pick the camera, set how often it refreshes. */
class WidgetConfigActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: android.content.Context) = super.attachBaseContext(pl.techkrzysiek.betternvr.data.Language.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        enableEdgeToEdge()
        setContent {
            BetterNvrTheme {
                WidgetConfigScreen(onPick = { config -> done(id, config) }, onClose = { finish() })
            }
        }
    }

    private fun done(id: Int, config: WidgetConfig) {
        CameraWidgets.save(this, id, config)
        CameraWidgets.render(this, id)
        CameraWidgets.requestRefresh(this, id)
        CameraWidgets.schedule(this, 15_000)
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
        finish()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WidgetConfigScreen(onPick: (WidgetConfig) -> Unit, onClose: () -> Unit) {
    val app: App = app()
    val context = LocalContext.current
    val devices by app.store.devices.collectAsState()
    // Channel names and permissions: a short login per recorder, nothing stays connected.
    val infos = remember { mutableStateMapOf<String, Result<DeviceInfo>>() }
    LaunchedEffect(devices) {
        devices.filter { it.id !in infos }.forEach { d ->
            launch { infos[d.id] = runCatching { app.manager.infos.value[d.id] ?: app.manager.test(d) } }
        }
    }
    var interval by remember { mutableLongStateOf(CameraWidgets.interval(context)) }
    var usageAccess by remember { mutableStateOf(CameraWidgets.hasUsageAccess(context)) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) usageAccess = CameraWidgets.hasUsageAccess(context) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.widget_pick_title)) },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Filled.Close, stringResource(R.string.cancel)) } },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()), contentPadding = WindowInsets.navigationBars.asPaddingValues()) {
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.widget_interval), style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CameraWidgets.INTERVALS.forEach { ms ->
                            FilterChip(
                                selected = interval == ms,
                                onClick = { interval = ms; CameraWidgets.setInterval(context, ms) },
                                label = { Text("${ms / 60_000} min") },
                            )
                        }
                    }
                    Text(stringResource(R.string.widget_interval_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item {
                Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.widget_usage_title), style = MaterialTheme.typography.titleSmall)
                        Text(
                            stringResource(if (usageAccess) R.string.widget_usage_on else R.string.widget_usage_text),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (!usageAccess) {
                            Button(onClick = { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }) {
                                Text(stringResource(R.string.widget_usage_grant))
                            }
                        }
                        Text(stringResource(R.string.widget_battery), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = {
                            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
                        }) { Text(stringResource(R.string.widget_app_settings)) }
                    }
                }
            }
            devices.forEach { d ->
                item(key = "h-${d.id}") {
                    Text(
                        d.name.ifBlank { d.host }, style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
                    )
                }
                val result = infos[d.id]
                val info = result?.getOrNull()
                when {
                    result == null -> item(key = "p-${d.id}") {
                        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp)) }
                    }
                    info == null -> item(key = "e-${d.id}") {
                        Text(
                            result.exceptionOrNull()?.message ?: stringResource(R.string.widget_offline),
                            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                    else -> items((0 until info.channelCount).filter { info.canLive(it) }, key = { "c-${d.id}-$it" }) { ch ->
                        val name = info.channelNames.getOrNull(ch)?.takeIf { it.isNotBlank() } ?: stringResource(R.string.channel_n, ch + 1)
                        val ref = ChannelRef(d.id, ch)
                        val thumb = remember(ref) { app.thumbnails.get(ref)?.asImageBitmap() }
                        ListItem(
                            headlineContent = { Text(name) },
                            leadingContent = {
                                Box(Modifier.size(width = 72.dp, height = 40.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                                    thumb?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                                }
                            },
                            modifier = Modifier.clickable { onPick(WidgetConfig(ref, name)) },
                        )
                    }
                }
            }
        }
    }
}
