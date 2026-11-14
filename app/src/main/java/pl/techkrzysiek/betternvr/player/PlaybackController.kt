package pl.techkrzysiek.betternvr.player

import android.view.Surface
import android.view.View
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import pl.techkrzysiek.betternvr.data.ChannelRef
import pl.techkrzysiek.betternvr.data.DeviceManager
import pl.techkrzysiek.betternvr.proto.DhavFrame
import pl.techkrzysiek.betternvr.proto.LoginException
import pl.techkrzysiek.betternvr.proto.MediaListener
import pl.techkrzysiek.betternvr.proto.MediaStream
import pl.techkrzysiek.betternvr.proto.RecordFile
import pl.techkrzysiek.betternvr.proto.RecordKind
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.coroutines.resume

/**
 * Archive playback of one channel. Position always comes from the timestamps of frames actually on
 * screen, so the timeline cannot drift; files are chained automatically; a dropped connection is
 * resumed from the last shown second. Pause and seek only use stop/start requests verified on real
 * recorders (pause = stop and remember the time; resume = start from it, ~0.1 s).
 */
class PlaybackController(private val scope: CoroutineScope, private val manager: DeviceManager) {
    val player = StreamPlayer(live = false)
    private val _records = MutableStateFlow<List<RecordFile>>(emptyList())
    val records: StateFlow<List<RecordFile>> = _records.asStateFlow()
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()
    private val _paused = MutableStateFlow(false)
    val paused: StateFlow<Boolean> = _paused.asStateFlow()
    private val _speed = MutableStateFlow(1)
    val speed: StateFlow<Int> = _speed.asStateFlow()
    private val _loadError = MutableStateFlow<String?>(null)
    val loadError: StateFlow<String?> = _loadError.asStateFlow()

    var ref: ChannelRef? = null; private set
    var day: LocalDate? = null; private set
    private var loadJob: Job? = null
    private var playJob: Job? = null
    @Volatile private var stream: MediaStream? = null
    private var hasSurface = false
    private var resumeAt: LocalDateTime? = null

    init {
        // the recorder became reachable on a preferred address: continue from the same moment there
        scope.launch {
            manager.moved.collect { id ->
                if (id == ref?.deviceId && playJob?.isActive == true && !_paused.value && !player.recording.value) {
                    player.position.value?.let { launchPlay(it) }
                }
            }
        }
    }

    /** Loads the day's recordings; with [autoplay] starts at [startAt] (or a sensible default). */
    fun load(ref: ChannelRef, day: LocalDate, startAt: LocalDateTime? = null, autoplay: Boolean = true) {
        this.ref = ref
        this.day = day
        stopStream()
        loadJob?.cancel()
        _records.value = emptyList()
        _loadError.value = null
        player.clearCorrupt()
        if (autoplay) player.setPosition(startAt ?: day.atStartOfDay())
        loadJob = scope.launch(Dispatchers.IO) {
            _loading.value = true
            try {
                val client = manager.client(ref.deviceId)
                val list = client.findRecords(ref.channel, day.atStartOfDay(), day.atTime(23, 59, 59))
                _records.value = list
                if (!autoplay) return@launch
                if (list.isEmpty()) {
                    player.setState(PlayerState.NoRecording)
                    return@launch
                }
                val now = client.currentTime()
                val start = startAt ?: if (now != null && now.toLocalDate() == day) {
                    maxOf(list.first().start, now.minusMinutes(10))
                } else list.first().start
                launchPlay(start)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _loadError.value = e.message ?: e.javaClass.simpleName
                player.setState(PlayerState.Error(e.message ?: "error", retrying = false))
            } finally {
                _loading.value = false
            }
        }
    }

    fun attachSurface(view: View, surface: Surface, resume: Boolean = true) {
        player.attachSurface(view, surface)
        hasSurface = true
        val at = resumeAt ?: player.position.value
        if (resume && !_paused.value && at != null && _records.value.isNotEmpty()) launchPlay(at)
    }

    /** Re-reads the list (new footage keeps arriving for today) without touching playback. */
    fun refreshRecords() {
        val r = ref ?: return
        val d = day ?: return
        scope.launch(Dispatchers.IO) {
            runCatching { manager.client(r.deviceId).findRecords(r.channel, d.atStartOfDay(), d.atTime(23, 59, 59)) }
                .onSuccess { if (ref == r && day == d) _records.value = it }
        }
    }

    fun detachSurface() {
        hasSurface = false
        resumeAt = player.position.value
        stopStream()
        player.detachSurface()
    }

    fun seek(t: LocalDateTime) {
        _paused.value = false
        player.paused = false
        launchPlay(t)
    }

    fun skip(seconds: Long) {
        val pos = player.position.value ?: return
        seek(pos.plusSeconds(seconds))
    }

    /** 1×, 2×, 4×, 8×: frames are shown N× faster (sound muted above 1×); the recorder is asked for twice that (see [netSpeed]). */
    fun setSpeed(n: Int) {
        _speed.value = n
        player.speed = n
        if (!_paused.value) player.position.value?.let { launchPlay(it) }
    }

    fun togglePause() {
        if (_paused.value) {
            _paused.value = false
            player.paused = false
            launchPlay(player.position.value ?: return)
        } else {
            _paused.value = true
            player.paused = true
            stopStream()
            player.setState(PlayerState.Paused)
        }
    }

    fun jumpToEvent(forward: Boolean) {
        val pos = player.position.value ?: return
        val events = _records.value.filter { it.kind != RecordKind.CONTINUOUS }
        val target = if (forward) events.firstOrNull { it.start.isAfter(pos.plusSeconds(1)) }
        else events.lastOrNull { it.start.isBefore(pos.minusSeconds(3)) }
        target?.let { seek(it.start) }
    }

    fun retry() {
        val r = ref ?: return
        val d = day ?: return
        if (_records.value.isEmpty()) load(r, d, player.position.value) else seek(player.position.value ?: return)
    }

    private fun stopStream() {
        playJob?.cancel() // reference kept so the next start can join() the teardown
        stream?.close()
        stream = null
    }

    private fun launchPlay(at: LocalDateTime) {
        val previous = playJob
        previous?.cancel()
        stream?.close()
        stream = null
        resumeAt = at
        player.expect(at)
        if (!hasSurface) return
        val r = ref ?: return
        player.reset()
        playJob = scope.launch(Dispatchers.IO) {
            // The previous request must be fully torn down first, or its late "stop" could kill this one.
            previous?.join()
            var from = at
            var failures = 0
            while (isActive) {
                val files = _records.value
                val file = files.firstOrNull { from in it } ?: files.firstOrNull { it.start.isAfter(from) }
                if (file == null) {
                    player.setState(PlayerState.NoRecording)
                    return@launch
                }
                val start = maxOf(from, file.start)
                if (player.state.value !is PlayerState.Playing) player.setState(PlayerState.Connecting)
                val outcome: Throwable? = try {
                    val client = manager.client(r.deviceId)
                    suspendCancellableCoroutine<Throwable?> { cont ->
                        val s = client.openPlayback(file, start, file.end, speed = netSpeed(_speed.value), listener = object : MediaListener {
                            override fun onFrame(frame: DhavFrame) = player.onFrame(frame)
                            override fun onCorrupt() = player.markCorrupt()
                            override fun onEnd() { if (cont.isActive) cont.resume(null) }
                            override fun onClosed(error: Throwable?) { if (cont.isActive) cont.resume(error ?: Exception("closed")) }
                        })
                        stream = s
                        cont.invokeOnCancellation { s.close() }
                    }
                } catch (e: LoginException) {
                    player.setState(PlayerState.Error(e.message ?: "login", retrying = false))
                    return@launch
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    e
                }
                if (!isActive) break
                if (outcome == null) {
                    // File finished: continue with whatever comes next (frames already queued keep playing).
                    from = file.end
                    failures = 0
                    continue
                }
                if (++failures > 5) {
                    player.setState(PlayerState.Error(outcome.message ?: "error", retrying = false))
                    return@launch
                }
                player.setState(PlayerState.Error(outcome.message ?: "error", retrying = true))
                delay(700L * failures)
                from = player.position.value?.plusSeconds(1) ?: start
                player.reset()
            }
        }
    }

    /**
     * Speed asked from the recorder: twice the playing speed. At 1× recorders pace some footage slower than
     * real time (measured 0.91× for 4K on the NVR4216, 0.97× on the NVR5216), so a 1× player kept running out
     * of data and the sound broke up; the surplus is held back by VideoDecoder.awaitRoom.
     */
    private fun netSpeed(n: Int) = (n * 2).coerceAtMost(8)

    fun release(context: android.content.Context) {
        loadJob?.cancel()
        stopStream()
        player.release(context)
    }
}
