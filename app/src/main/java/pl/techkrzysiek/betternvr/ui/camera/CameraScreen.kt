package pl.techkrzysiek.betternvr.ui.camera

import android.app.Activity
import androidx.activity.compose.BackHandler
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.SystemClock
import android.provider.Settings
import android.view.OrientationEventListener
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.input.pointer.PointerEventPass
import kotlinx.coroutines.flow.MutableStateFlow
import pl.techkrzysiek.betternvr.MainActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.ControlCamera
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material3.LocalContentColor
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicNone
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import pl.techkrzysiek.betternvr.R
import pl.techkrzysiek.betternvr.data.ChannelRef
import pl.techkrzysiek.betternvr.player.PlayerState
import pl.techkrzysiek.betternvr.player.StreamPlayer
import pl.techkrzysiek.betternvr.proto.EventSegment
import pl.techkrzysiek.betternvr.proto.EventType
import pl.techkrzysiek.betternvr.ui.SystemBars
import pl.techkrzysiek.betternvr.ui.common.LocalMessages
import pl.techkrzysiek.betternvr.ui.common.LocalShareMessages
import pl.techkrzysiek.betternvr.ui.common.RecordingTimer
import pl.techkrzysiek.betternvr.ui.common.PlayerStateOverlay
import pl.techkrzysiek.betternvr.ui.common.app
import pl.techkrzysiek.betternvr.ui.common.rememberStorageGate
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val TIME = DateTimeFormatter.ofPattern("HH:mm:ss")
private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

@Composable
fun EventType.label(): String = stringResource(
    when (this) {
        EventType.MOTION -> R.string.event_motion
        EventType.HUMAN -> R.string.event_human
        EventType.VEHICLE -> R.string.event_vehicle
        EventType.ALARM -> R.string.event_alarm
    },
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CameraScreen(deviceId: String, channel: Int, onBack: () -> Unit, onSwitch: (Int) -> Unit = {}) {
    val app = app()
    val vm: CameraViewModel = viewModel(key = "cam-$deviceId-$channel") { CameraViewModel(app, ChannelRef(deviceId, channel)) }
    val infos by app.manager.infos.collectAsState()
    val devices by app.store.devices.collectAsState()
    val info = infos[deviceId]
    val name = info?.channelNames?.getOrNull(channel)?.takeIf { it.isNotBlank() } ?: stringResource(R.string.channel_n, channel + 1)
    val deviceName = devices.firstOrNull { it.id == deviceId }?.let { it.name.ifBlank { it.host } }.orEmpty()

    val mode by vm.mode.collectAsState()
    val day by vm.day.collectAsState()
    val now by vm.now.collectAsState()
    val records by vm.timelineRecords.collectAsState()
    val loading by vm.playback.loading.collectAsState()
    val events by vm.visibleEvents.collectAsState()
    val allEvents by vm.events.collectAsState()
    val filter by vm.filter.collectAsState()
    val smartDenied by vm.smartDenied.collectAsState()
    val recordDays by vm.recordDays.collectAsState()
    val player: StreamPlayer = if (mode == Mode.LIVE) vm.live.player else vm.playback.player
    val state by player.state.collectAsState()
    val livePos by vm.live.player.position.collectAsState()
    val pbPos by vm.playback.player.position.collectAsState()
    val position = if (mode == Mode.LIVE) (livePos ?: now) else pbPos
    val paused by vm.playback.paused.collectAsState()
    val corrupt by vm.playback.player.corrupt.collectAsState()
    val liveAllowed = info?.canLive(channel) ?: true

    val activity = LocalContext.current as? MainActivity
    val inPip by (activity?.inPip ?: remember { MutableStateFlow(false) }).collectAsState()
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val fullscreen = landscape && !inPip
    val setLandscape = rememberOrientationLock()
    var picking by remember { mutableStateOf(false) }
    val ptz by vm.ptz.collectAsState()
    var ptzOpen by rememberSaveable { mutableStateOf(false) }
    SystemBars(hidden = fullscreen)

    // The picture keeps the stream's own shape (black bars instead of stretching).
    val videoSize by player.videoSize.collectAsState()
    var aspect by rememberSaveable { mutableFloatStateOf(16f / 9f) }
    LaunchedEffect(videoSize) { displayAspect(videoSize)?.let { aspect = it } }

    // Floating window (picture-in-picture) from the button, in the picture's shape.
    LaunchedEffect(aspect) { activity?.pipAspect = aspect }

    // Full screen: the controls hide after a few seconds without touching them; a tap on the picture toggles them.
    var controls by remember { mutableStateOf(true) }
    val lastTouch = remember { longArrayOf(0L) }
    LaunchedEffect(fullscreen) { controls = true }
    LaunchedEffect(controls, fullscreen) {
        if (!fullscreen || !controls) return@LaunchedEffect
        lastTouch[0] = SystemClock.uptimeMillis()
        while (true) {
            val left = lastTouch[0] + CONTROLS_HIDE_MS - SystemClock.uptimeMillis()
            if (left <= 0) break
            delay(left)
        }
        controls = false
    }
    val onVideoTap by rememberUpdatedState<() -> Unit> { if (fullscreen) controls = !controls }
    // System back in full screen returns to the normal camera view instead of leaving the camera.
    BackHandler(enabled = fullscreen) { setLandscape(false) }

    // Recording-only access: open straight into the archive.
    LaunchedEffect(liveAllowed, now != null) { if (!liveAllowed && vm.mode.value == Mode.LIVE) now?.let { vm.seek(it.minusMinutes(10)) } }
    // Keep the home-screen / grid thumbnail of this camera fresh (HD picture).
    LaunchedEffect(state, mode) {
        val ref = ChannelRef(deviceId, channel)
        if (mode == Mode.LIVE && state is PlayerState.Playing && !app.thumbnails.isFresh(ref)) {
            delay(2000)
            vm.live.player.capture(480)?.let { app.thumbnails.put(ref, it) }
        }
    }

    // The video moves between the portrait, full-screen and PiP layouts without being recreated,
    // so rotating the phone does not restart the stream.
    val zoomScope = rememberCoroutineScope() // zoom animations run on the screen's frame clock
    val zoom = remember(vm) { ZoomState(zoomScope) }
    val video = remember(vm) {
        movableContentOf { m: Modifier ->
            Box(m.background(Color.Black), contentAlignment = Alignment.Center) {
                val switchTo by rememberUpdatedState(onSwitch)
                val neighbours by rememberUpdatedState(neighbourChannels(app, deviceId, channel))
                // Compose does not clip embedded Android views by itself: without this the zoomed picture spills out.
                Box(Modifier.aspectRatio(aspect).clipToBounds()) {
                    ZoomableVideo(zoom, onAttach = { v, s -> vm.attachSurface(v, s) }, onDetach = { vm.detachSurface() }, modifier = Modifier.fillMaxSize())
                    // Swipe left / right on the picture: next / previous camera (like DMSS); pinch / double tap: zoom.
                    Box(Modifier.matchParentSize().videoGestures(
                        zoom,
                        onTap = { onVideoTap() },
                        onSwipe = { forward -> (if (forward) neighbours.second else neighbours.first)?.let(switchTo) },
                    ))
                }
                val st by (if (vm.mode.collectAsState().value == Mode.LIVE) vm.live.player else vm.playback.player).state.collectAsState()
                PlayerStateOverlay(st, onRetry = { if (vm.mode.value == Mode.LIVE) vm.live.retry() else vm.playback.retry() })
            }
        }
    }

    when {
        inPip -> Box(Modifier.fillMaxSize().background(Color.Black)) { video(Modifier.fillMaxSize()) }
        fullscreen -> Box(Modifier.fillMaxSize().background(Color.Black)) {
            video(Modifier.fillMaxSize())
            // Bars are hidden but still swipe in over the edges: keep the controls clear of them and of the camera cut-out.
            val safe = WindowInsets.displayCutout.union(WindowInsets.navigationBarsIgnoringVisibility)
            AnimatedVisibility(controls, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().windowInsetsPadding(safe.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))) {
                    Row(Modifier.align(Alignment.TopStart).fillMaxWidth().padding(4.dp).touchObserver(lastTouch), horizontalArrangement = Arrangement.SpaceBetween) {
                        IconButton(onClick = { setLandscape(false) }) { Icon(Icons.Filled.FullscreenExit, stringResource(R.string.fullscreen_exit), tint = Color.White) }
                        if (activity?.pipSupported == true) {
                            IconButton(onClick = { activity.enterPip() }) { Icon(Icons.Filled.PictureInPictureAlt, stringResource(R.string.pip), tint = Color.White) }
                        }
                    }
                    Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().touchObserver(lastTouch)) {
                        TransportRow(vm, mode, paused, overlay = true)
                        day?.let { Timeline(it, records, events, position, now, vm::seek, Modifier.fillMaxWidth().height(52.dp), overlay = true) }
                    }
                }
            }
        }
        else -> Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } },
                title = {
                    Column {
                        Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(deviceName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                actions = {
                    SirenAction(vm, name)
                    if (ptz != null) {
                        androidx.compose.material3.FilledTonalIconToggleButton(checked = ptzOpen, onCheckedChange = { ptzOpen = it }, enabled = mode == Mode.LIVE) {
                            Icon(Icons.Filled.ControlCamera, stringResource(if (ptzOpen) R.string.ptz_hide else R.string.ptz_show))
                        }
                    }
                    if (activity?.pipSupported == true) {
                        IconButton(onClick = { activity.enterPip() }) { Icon(Icons.Filled.PictureInPictureAlt, stringResource(R.string.pip)) }
                    }
                    IconButton(onClick = { setLandscape(true) }) { Icon(Icons.Filled.Fullscreen, stringResource(R.string.fullscreen)) }
                },
            )
            video(Modifier.fillMaxWidth().aspectRatio(aspect.coerceIn(4f / 3f, 21f / 9f)))
            ActionRow(vm, mode, player, name)
            ptz?.let { caps -> if (ptzOpen && mode == Mode.LIVE) PtzPad(caps, onStart = vm::ptzStart, onStop = vm::ptzStop) }
            HorizontalDivider()
            day?.let { d -> DayBar(d, isToday = now?.toLocalDate() == d, onDay = vm::setDay, onPick = { picking = true }) }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EventType.entries.forEach { t ->
                    FilterChip(
                        selected = t in filter, onClick = { vm.toggleFilter(t) },
                        label = { Text(t.label()) },
                        leadingIcon = { Box(Modifier.size(10.dp).clip(CircleShape).background(t.color())) },
                    )
                }
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth()) else Spacer(Modifier.height(4.dp))
            day?.let {
                Timeline(it, records, events, position, now, vm::seek, Modifier.fillMaxWidth().height(80.dp))
                DayOverview(it, records, events, position, now, vm::seek, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp))
            }
            TransportRow(vm, mode, paused, overlay = false)
            if (corrupt > 2 && mode == Mode.PLAYBACK) {
                Text(stringResource(R.string.corrupt_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp))
            }
            EventList(
                events = events.asReversed(),
                current = if (mode == Mode.PLAYBACK) vm.currentEvent(position) else null,
                total = allEvents.size,
                filtered = filter.isNotEmpty(),
                smartDenied = smartDenied && (EventType.HUMAN in filter || EventType.VEHICLE in filter),
                onClick = { vm.playEvent(it) },
                modifier = Modifier.weight(1f),
            )
        }
    }

    if (picking) {
        val today = now?.toLocalDate() ?: LocalDate.now()
        RecordCalendarDialog(
            selected = day ?: today, today = today, days = recordDays,
            onMonth = vm::loadMonth,
            onPick = { vm.setDay(it); picking = false },
            onDismiss = { picking = false },
        )
    }
}

private const val CONTROLS_HIDE_MS = 4000L

/** Notes every touch on the controls (without taking it), so they stay up while being used. */
private fun Modifier.touchObserver(lastTouch: LongArray): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            awaitPointerEvent(PointerEventPass.Initial)
            lastTouch[0] = SystemClock.uptimeMillis()
        }
    }
}

/** Shape of the picture. D1 / CIF sub-streams squeeze a 16:9 camera into 704×576: keep the last known shape for them. */
private fun displayAspect(size: Pair<Int, Int>): Float? {
    val (w, h) = size
    if (w <= 0 || h <= 0) return null
    if (w in SQUEEZED_WIDTHS && h in SQUEEZED_HEIGHTS) return null
    return w.toFloat() / h
}

private val SQUEEZED_WIDTHS = setOf(352, 704, 720)
private val SQUEEZED_HEIGHTS = setOf(240, 288, 480, 576)

/**
 * Full-screen button → landscape (and back). The lock belongs to the activity: swiping to the next camera
 * replaces this screen, and releasing the lock with it dropped the phone out of full screen.
 */
@Composable
private fun rememberOrientationLock(): (Boolean) -> Unit {
    val activity = LocalContext.current as? MainActivity ?: return {}
    DisposableEffect(activity) {
        activity.cameraScreenShown()
        onDispose { activity.cameraScreenGone() }
    }
    return remember(activity) { { landscape: Boolean -> activity.orientationLock.request(landscape) } }
}

/** Previous and next camera of the same recorder that this account may watch. */
private fun neighbourChannels(app: pl.techkrzysiek.betternvr.App, deviceId: String, channel: Int): Pair<Int?, Int?> {
    val info = app.manager.infos.value[deviceId] ?: return null to null
    val empty = app.manager.noCamera.value
    val list = (0 until info.channelCount).filter { (info.canLive(it) || info.canPlay(it)) && ChannelRef(deviceId, it) !in empty }
    // By channel number, so it also works from a channel that turned out to be empty (not in the list).
    return list.lastOrNull { it < channel } to list.firstOrNull { it > channel }
}

@Composable
private fun ActionRow(vm: CameraViewModel, mode: Mode, player: StreamPlayer, label: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val message = LocalMessages.current
    val shareMessage = LocalShareMessages.current
    val recording by player.recording.collectAsState()
    val audioOn by vm.audioOn.collectAsState()
    val sub by vm.live.subStream.collectAsState()
    val snapOk = stringResource(R.string.snapshot_saved)
    val snapFail = stringResource(R.string.snapshot_failed)
    val clipOk = stringResource(R.string.clip_saved)
    val clipFail = stringResource(R.string.clip_failed)
    val denied = stringResource(R.string.storage_permission)
    val gate = rememberStorageGate { message(denied) }
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = {
            gate { scope.launch { player.snapshot(context, label)?.let { shareMessage(snapOk, it, "image/jpeg") } ?: message(snapFail) } }
        }) { Icon(Icons.Filled.PhotoCamera, stringResource(R.string.snapshot)) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = {
                gate {
                    if (recording) player.stopRecording(context)?.let { shareMessage(clipOk, it, "video/mp4") } ?: message(clipFail)
                    else player.startRecording(context, label)
                }
            }) {
                if (recording) Icon(Icons.Filled.Stop, stringResource(R.string.record), tint = Color.Red) else Icon(Icons.Filled.FiberManualRecord, stringResource(R.string.record))
            }
            if (recording) RecordingTimer(player.recordingSince)
        }
        IconButton(onClick = { vm.toggleAudio() }) {
            Icon(if (audioOn) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff, stringResource(R.string.audio))
        }
        if (mode == Mode.PLAYBACK) DownloadButton(vm, label) else TalkButton(vm, enabled = true)
        TextButton(onClick = { vm.live.toggleQuality() }, enabled = mode == Mode.LIVE) {
            Text(if (sub) stringResource(R.string.quality_sd) else stringResource(R.string.quality_hd), style = MaterialTheme.typography.titleMedium)
        }
    }
}

/** Save a stretch of the recording as MP4: from the current moment, chosen length or the current event. */
@Composable
private fun DownloadButton(vm: CameraViewModel, label: String) {
    val context = LocalContext.current
    val message = LocalMessages.current
    val shareMessage = LocalShareMessages.current
    val progress by vm.download.progress.collectAsState()
    var asking by remember { mutableStateOf(false) }
    val saved = stringResource(R.string.download_saved)
    val failed = stringResource(R.string.download_failed)
    val denied = stringResource(R.string.storage_permission)
    val gate = rememberStorageGate { message(denied) }
    IconButton(onClick = { if (progress == null) asking = true }) { Icon(Icons.Filled.Download, stringResource(R.string.download)) }
    if (asking) {
        val pos = vm.playback.player.position.value ?: return
        val event = vm.currentEvent(pos)
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text(stringResource(R.string.download)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.download_from, pos.format(STAMP)), style = MaterialTheme.typography.bodyMedium)
                    fun go(from: java.time.LocalDateTime, to: java.time.LocalDateTime) {
                        asking = false
                        gate { vm.startDownload(context, from, to, label) { uri, err -> if (uri != null) shareMessage(saved, uri, "video/mp4") else if (err != "cancelled") message(failed) } }
                    }
                    listOf(30L to "30 s", 60L to "1 min", 300L to "5 min", 900L to "15 min").forEach { (secs, text) ->
                        androidx.compose.material3.OutlinedButton(onClick = { go(pos, pos.plusSeconds(secs)) }, modifier = Modifier.fillMaxWidth()) { Text(text) }
                    }
                    event?.let { e ->
                        androidx.compose.material3.FilledTonalButton(onClick = { go(e.start.minusSeconds(3), e.end.plusSeconds(3)) }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.download_event, e.start.format(TIME), e.end.format(TIME)))
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { asking = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    progress?.let { p ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.download_progress)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                    Text("${(p * 100).toInt()} %")
                }
            },
            confirmButton = { TextButton(onClick = { vm.download.cancel() }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** Tap to talk through the camera speaker, tap again to stop. Asks for the microphone first. */
@Composable
private fun TalkButton(vm: CameraViewModel, enabled: Boolean) {
    val talking by vm.talk.talking.collectAsState()
    val context = LocalContext.current
    val message = LocalMessages.current
    val refused = stringResource(R.string.talk_refused)
    val needMic = stringResource(R.string.talk_need_mic)
    val onError: (String) -> Unit = { msg -> message(if (msg.contains("Talk.General")) refused else msg) }
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) vm.toggleTalk(onError) else message(needMic)
    }
    IconButton(enabled = enabled, onClick = {
        if (talking || androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            vm.toggleTalk(onError)
        } else launcher.launch(android.Manifest.permission.RECORD_AUDIO)
    }) {
        Icon(if (talking) Icons.Filled.Mic else Icons.Filled.MicNone, stringResource(R.string.talk), tint = if (talking) Color(0xFFD32F2F) else LocalContentColor.current)
    }
}

/** Active deterrence (siren) in the top bar — only on cameras that have it; switching it on always asks first. */
@Composable
private fun SirenAction(vm: CameraViewModel, label: String) {
    val supported by vm.sirenSupported.collectAsState()
    if (!supported) return
    val on by vm.sirenOn.collectAsState()
    val message = LocalMessages.current
    var confirm by remember { mutableStateOf(false) }
    if (on) {
        FilledIconButton(
            onClick = { vm.setSiren(false, message) },
            colors = androidx.compose.material3.IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFFD32F2F), contentColor = Color.White),
        ) { Icon(Icons.Filled.Campaign, stringResource(R.string.siren_off)) }
    } else {
        IconButton(onClick = { confirm = true }) { Icon(Icons.Filled.Campaign, stringResource(R.string.siren_on)) }
    }
    if (confirm) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirm = false },
            icon = { Icon(Icons.Filled.Campaign, null) },
            title = { Text(stringResource(R.string.siren_confirm_title)) },
            text = { Text(stringResource(R.string.siren_confirm_text, label)) },
            confirmButton = { TextButton(onClick = { confirm = false; vm.setSiren(true, message) }) { Text(stringResource(R.string.siren_on)) } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun TransportRow(vm: CameraViewModel, mode: Mode, paused: Boolean, overlay: Boolean) {
    val tint = if (overlay) Color.White else MaterialTheme.colorScheme.onSurface
    Row(
        Modifier.fillMaxWidth().then(if (overlay) Modifier.background(Color.Black.copy(alpha = 0.35f)) else Modifier).padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { vm.jumpToEvent(false) }) { Icon(Icons.Filled.SkipPrevious, stringResource(R.string.prev_event), tint = tint) }
        IconButton(onClick = { vm.skip(-10) }) { Icon(Icons.Filled.Replay10, stringResource(R.string.back_10s), tint = tint) }
        FilledIconButton(onClick = { vm.togglePause() }, modifier = Modifier.size(48.dp)) {
            val playing = mode == Mode.LIVE || !paused
            Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, stringResource(if (playing) R.string.pause else R.string.play))
        }
        IconButton(onClick = { vm.skip(10) }, enabled = mode == Mode.PLAYBACK) { Icon(Icons.Filled.Forward10, stringResource(R.string.fwd_10s), tint = tint) }
        IconButton(onClick = { vm.jumpToEvent(true) }) { Icon(Icons.Filled.SkipNext, stringResource(R.string.next_event), tint = tint) }
        if (mode == Mode.PLAYBACK) {
            val speed by vm.playback.speed.collectAsState()
            TextButton(onClick = { vm.cycleSpeed() }) { Text("${speed}×", color = tint, style = MaterialTheme.typography.titleSmall) }
        }
        AssistChip(
            onClick = { vm.goLive() },
            enabled = mode == Mode.PLAYBACK,
            label = { Text(stringResource(R.string.go_live)) },
            leadingIcon = { Box(Modifier.size(8.dp).clip(CircleShape).background(if (mode == Mode.PLAYBACK) Color(0xFFD32F2F) else Color.Gray)) },
        )
    }
}

@Composable
private fun DayBar(day: LocalDate, isToday: Boolean, onDay: (LocalDate) -> Unit, onPick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onDay(day.minusDays(1)) }) { Icon(Icons.Filled.ChevronLeft, stringResource(R.string.playback_prev_day)) }
        TextButton(onClick = onPick, modifier = Modifier.weight(1f)) {
            Text(if (isToday) stringResource(R.string.today) else day.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)))
        }
        IconButton(onClick = { onDay(day.plusDays(1)) }, enabled = !isToday) { Icon(Icons.Filled.ChevronRight, stringResource(R.string.playback_next_day)) }
    }
}

@Composable
private fun EventList(events: List<EventSegment>, current: EventSegment?, total: Int, filtered: Boolean, smartDenied: Boolean, onClick: (EventSegment) -> Unit, modifier: Modifier) {
    LazyColumn(modifier.fillMaxWidth(), contentPadding = WindowInsets.navigationBars.asPaddingValues()) {
        item {
            Text(
                stringResource(R.string.events_title, events.size),
                style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp),
            )
            if (smartDenied) {
                Text(stringResource(R.string.smart_denied), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            } else if (events.isEmpty()) {
                Text(stringResource(if (filtered && total > 0) R.string.events_none_filtered else R.string.events_none),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }
        }
        items(events, key = { "${it.type}-${it.start}" }) { e ->
            Row(
                Modifier.fillMaxWidth()
                    .background(if (e == current) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else Color.Transparent)
                    .clickable { onClick(e) }.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(Modifier.size(12.dp).clip(CircleShape).background(e.type.color()))
                Text(e.start.format(TIME), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(e.type.label(), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                val secs = Duration.between(e.start, e.end).seconds
                Text(if (secs >= 60) "${secs / 60} min ${secs % 60} s" else "$secs s", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
