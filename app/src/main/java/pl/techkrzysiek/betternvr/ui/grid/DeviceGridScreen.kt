package pl.techkrzysiek.betternvr.ui.grid

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.delay
import pl.techkrzysiek.betternvr.player.PlayerState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import pl.techkrzysiek.betternvr.App
import pl.techkrzysiek.betternvr.R
import pl.techkrzysiek.betternvr.data.ChannelRef
import pl.techkrzysiek.betternvr.player.LiveController
import pl.techkrzysiek.betternvr.ui.common.PlayerStateOverlay
import pl.techkrzysiek.betternvr.ui.common.VideoSurface
import pl.techkrzysiek.betternvr.ui.common.app

class GridViewModel(private val app: App, val deviceId: String) : ViewModel() {
    private val controllers = HashMap<Int, LiveController>()

    /** Tiles always use the sub-stream: many small pictures, little bandwidth. */
    fun controller(channel: Int): LiveController = controllers.getOrPut(channel) {
        LiveController(viewModelScope, app.manager).also {
            it.setAutoSubStream(true)
            it.setSource(ChannelRef(deviceId, channel))
        }
    }

    override fun onCleared() {
        controllers.values.forEach { it.release(app) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceGridScreen(deviceId: String, onBack: () -> Unit, onOpenCamera: (Int) -> Unit, onMultiView: () -> Unit) {
    val app = app()
    val vm: GridViewModel = viewModel(key = "grid-$deviceId") { GridViewModel(app, deviceId) }
    val devices by app.store.devices.collectAsState()
    val infos by app.manager.infos.collectAsState()
    val errors by app.manager.errors.collectAsState()
    val noCamera by app.manager.noCamera.collectAsState()
    val device = devices.firstOrNull { it.id == deviceId }
    val info = infos[deviceId]

    LaunchedEffect(deviceId) { runCatching { app.manager.client(deviceId) } }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } },
                actions = { IconButton(onClick = onMultiView) { Icon(Icons.Filled.GridView, stringResource(R.string.multiview)) } },
                title = {
                    Column {
                        Text(device?.name?.ifBlank { device.host } ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        info?.let { Text("${it.type} · ${it.version}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                },
            )
        },
    ) { padding ->
        when {
            info == null && errors[deviceId] != null -> Box(Modifier.fillMaxSize().padding(padding).padding(24.dp), contentAlignment = Alignment.Center) {
                Text(errors[deviceId]!!, color = MaterialTheme.colorScheme.error)
            }
            info == null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else -> {
                val channels = (0 until info.channelCount).filter { info.canLive(it) || info.canPlay(it) }
                    .sortedBy { ChannelRef(deviceId, it) in noCamera }
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 170.dp),
                    contentPadding = PaddingValues(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize().padding(padding),
                ) {
                    items(channels, key = { it }) { ch ->
                        val name = info.channelNames.getOrNull(ch)?.takeIf { it.isNotBlank() } ?: stringResource(R.string.channel_n, ch + 1)
                        CameraTile(vm, ch, name, liveAllowed = info.canLive(ch), onClick = { onOpenCamera(ch) })
                    }
                }
            }
        }
    }
}

@Composable
private fun CameraTile(vm: GridViewModel, channel: Int, name: String, liveAllowed: Boolean, onClick: () -> Unit) {
    val app = app()
    val ref = ChannelRef(vm.deviceId, channel)
    val thumbVersion by app.thumbnails.version.collectAsState()
    val thumb = remember(thumbVersion, ref) { app.thumbnails.get(ref)?.asImageBitmap() }
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)).background(Color(0xFF14181E))) {
            if (liveAllowed) {
                val controller = remember(channel) { vm.controller(channel) }
                val state by controller.player.state.collectAsState()
                VideoSurface(
                    onAttach = { v, s -> controller.attachSurface(v, s) },
                    onDetach = { controller.detachSurface() },
                    modifier = Modifier.fillMaxSize(),
                )
                // Last known picture until the live stream shows its first frame, then live takes over.
                if (state !is PlayerState.Playing && thumb != null) {
                    Image(thumb, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                if (state is PlayerState.Connecting && thumb != null) {
                    CircularProgressIndicator(Modifier.align(Alignment.TopEnd).padding(8.dp).size(16.dp), color = Color.White, strokeWidth = 2.dp)
                } else {
                    PlayerStateOverlay(state, onRetry = controller::retry, compact = true)
                }
                LaunchedEffect(state) {
                    if (state is PlayerState.Playing && !app.thumbnails.isFresh(ref)) {
                        delay(1500)
                        controller.player.capture(480)?.let { app.thumbnails.put(ref, it) }
                    }
                }
            } else {
                if (thumb != null) Image(thumb, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                Icon(Icons.Filled.History, null, tint = Color.White, modifier = Modifier.align(Alignment.Center).size(32.dp))
            }
            // tap target above the SurfaceView
            Box(Modifier.matchParentSize().clickable(onClick = onClick))
        }
        Row(Modifier.padding(start = 4.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
