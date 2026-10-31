package pl.techkrzysiek.betternvr.ui.grid

import android.content.res.Configuration
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import pl.techkrzysiek.betternvr.App
import pl.techkrzysiek.betternvr.R
import pl.techkrzysiek.betternvr.data.ChannelRef
import pl.techkrzysiek.betternvr.player.LiveController
import pl.techkrzysiek.betternvr.player.PlayerState
import pl.techkrzysiek.betternvr.ui.SystemBars
import pl.techkrzysiek.betternvr.ui.common.PlayerStateOverlay
import pl.techkrzysiek.betternvr.ui.common.VideoSurface
import pl.techkrzysiek.betternvr.ui.common.app
import kotlin.math.ceil
import kotlin.math.sqrt

private val LAYOUTS = listOf(1, 4, 9, 16)

class MultiViewModel(private val app: App, val deviceId: String) : ViewModel() {
    private val _layout = MutableStateFlow(4)
    val layout: StateFlow<Int> = _layout.asStateFlow()
    private val controllers = HashMap<Int, LiveController>()

    /** HD up to 4 tiles, SD beyond — keeps 9/16 views within what a phone can decode. */
    fun controller(channel: Int): LiveController = controllers.getOrPut(channel) {
        LiveController(viewModelScope, app.manager).also {
            it.setAutoSubStream(_layout.value > 4)
            it.setSource(ChannelRef(deviceId, channel))
        }
    }

    fun setLayout(n: Int) {
        _layout.value = n
        controllers.values.forEach { it.setAutoSubStream(n > 4) }
    }

    override fun onCleared() = controllers.values.forEach { it.release(app) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MultiViewScreen(deviceId: String, onBack: () -> Unit, onOpenCamera: (Int) -> Unit) {
    val app = app()
    val vm: MultiViewModel = viewModel(key = "multi-$deviceId") { MultiViewModel(app, deviceId) }
    val layout by vm.layout.collectAsState()
    val infos by app.manager.infos.collectAsState()
    val noCamera by app.manager.noCamera.collectAsState()
    val devices by app.store.devices.collectAsState()
    val info = infos[deviceId]
    val channels = info?.let { i -> (0 until i.channelCount).filter { i.canLive(it) && ChannelRef(deviceId, it) !in noCamera } }.orEmpty()
    val pageCount = maxOf(1, ceil(channels.size / layout.toDouble()).toInt())
    val pager = rememberPagerState { pageCount }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    SystemBars(hidden = landscape)

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        if (!landscape) {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } },
                title = { Text(devices.firstOrNull { it.id == deviceId }?.let { it.name.ifBlank { it.host } }.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                actions = { if (pageCount > 1) Text("${pager.currentPage + 1}/$pageCount", modifier = Modifier.padding(end = 16.dp)) },
            )
        }
        Box(Modifier.fillMaxWidth().then(if (landscape) Modifier.weight(1f) else Modifier.aspectRatio(if (layout == 1) 16f / 9f else 16f / 9f))) {
            HorizontalPager(state = pager, modifier = Modifier.fillMaxSize(), key = { it }) { page ->
                val cols = sqrt(layout.toDouble()).toInt()
                Column(Modifier.fillMaxSize()) {
                    repeat(cols) { r ->
                        Row(Modifier.weight(1f).fillMaxWidth()) {
                            repeat(cols) { c ->
                                val idx = page * layout + r * cols + c
                                Box(Modifier.weight(1f).fillMaxHeight().padding(if (layout > 1) 1.dp else 0.dp)) {
                                    channels.getOrNull(idx)?.let { ch ->
                                        val name = info?.channelNames?.getOrNull(ch)?.takeIf { it.isNotBlank() } ?: stringResource(R.string.channel_n, ch + 1)
                                        MultiTile(vm, ch, name, compact = layout > 4, onOpen = { onOpenCamera(ch) })
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (!landscape) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
                LAYOUTS.forEachIndexed { i, n ->
                    SegmentedButton(selected = layout == n, onClick = { vm.setLayout(n) }, shape = SegmentedButtonDefaults.itemShape(i, LAYOUTS.size)) {
                        Text(n.toString())
                    }
                }
            }
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
        }
    }
}

@Composable
private fun MultiTile(vm: MultiViewModel, channel: Int, name: String, compact: Boolean, onOpen: () -> Unit) {
    val app = app()
    val ref = ChannelRef(vm.deviceId, channel)
    val controller = remember(channel) { vm.controller(channel) }
    val state by controller.player.state.collectAsState()
    val thumbVersion by app.thumbnails.version.collectAsState()
    val thumb = remember(thumbVersion, ref) { app.thumbnails.get(ref)?.asImageBitmap() }
    androidx.compose.runtime.LaunchedEffect(state) {
        if (state is PlayerState.Playing && !app.thumbnails.isFresh(ref)) {
            kotlinx.coroutines.delay(1500)
            controller.player.capture(480)?.let { app.thumbnails.put(ref, it) }
        }
    }
    Box(Modifier.fillMaxSize().background(Color(0xFF14181E))) {
        VideoSurface(onAttach = { v, s -> controller.attachSurface(v, s) }, onDetach = { controller.detachSurface() }, modifier = Modifier.fillMaxSize())
        if (state !is PlayerState.Playing && thumb != null) Image(thumb, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        if (state is PlayerState.Connecting && thumb != null) {
            CircularProgressIndicator(Modifier.align(Alignment.TopEnd).padding(6.dp).size(14.dp), color = Color.White, strokeWidth = 2.dp)
        } else PlayerStateOverlay(state, onRetry = controller::retry, compact = true)
        Box(Modifier.matchParentSize().clickable(onClick = onOpen))
        Text(
            name, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
            modifier = Modifier.align(Alignment.BottomStart).background(Color.Black.copy(alpha = 0.45f)).padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}
