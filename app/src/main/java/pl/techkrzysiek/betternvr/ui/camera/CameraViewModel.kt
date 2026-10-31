package pl.techkrzysiek.betternvr.ui.camera

import android.os.SystemClock
import android.view.Surface
import android.view.View
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import pl.techkrzysiek.betternvr.App
import pl.techkrzysiek.betternvr.data.ChannelRef
import pl.techkrzysiek.betternvr.player.LiveController
import pl.techkrzysiek.betternvr.player.PlaybackController
import pl.techkrzysiek.betternvr.proto.EventSegment
import pl.techkrzysiek.betternvr.proto.EventType
import pl.techkrzysiek.betternvr.proto.RecordFile
import pl.techkrzysiek.betternvr.proto.RecordKind
import pl.techkrzysiek.betternvr.proto.RpcException
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

enum class Mode { LIVE, PLAYBACK }

/**
 * One camera: starts live; scrubbing the timeline into the past switches to recordings on the same
 * surface, "Live" switches back. All times are the recorder's own clock.
 */
class CameraViewModel(private val app: App, val ref: ChannelRef) : ViewModel() {
    /** Single camera view opens in HD (main stream); SD is one tap away. */
    val live = LiveController(viewModelScope, app.manager).apply { setAutoSubStream(false); setSource(ref) }
    val playback = PlaybackController(viewModelScope, app.manager)

    private val _audioOn = MutableStateFlow(true)
    val audioOn: StateFlow<Boolean> = _audioOn.asStateFlow()
    private val _recordDays = MutableStateFlow<Map<java.time.YearMonth, Set<LocalDate>>>(emptyMap())
    /** Days that have footage, per month (for the calendar dots). */
    val recordDays: StateFlow<Map<java.time.YearMonth, Set<LocalDate>>> = _recordDays.asStateFlow()

    private val _mode = MutableStateFlow(Mode.LIVE)
    val mode: StateFlow<Mode> = _mode.asStateFlow()
    private val _day = MutableStateFlow<LocalDate?>(null)
    val day: StateFlow<LocalDate?> = _day.asStateFlow()
    private val _filter = MutableStateFlow<Set<EventType>>(emptySet())
    /** Selected event types; empty = all. */
    val filter: StateFlow<Set<EventType>> = _filter.asStateFlow()
    private val _smart = MutableStateFlow<List<EventSegment>>(emptyList())
    private val _smartDenied = MutableStateFlow(false)
    val smartDenied: StateFlow<Boolean> = _smartDenied.asStateFlow()
    private val _now = MutableStateFlow<LocalDateTime?>(null)
    /** Recorder clock, ticking. */
    val now: StateFlow<LocalDateTime?> = _now.asStateFlow()

    private val _ptz = MutableStateFlow<pl.techkrzysiek.betternvr.proto.DahuaClient.PtzCaps?>(null)
    val ptz: StateFlow<pl.techkrzysiek.betternvr.proto.DahuaClient.PtzCaps?> = _ptz.asStateFlow()
    private var ptzJob: Job? = null
    private val _sirenSupported = MutableStateFlow(false)
    val sirenSupported: StateFlow<Boolean> = _sirenSupported.asStateFlow()
    private val _sirenOn = MutableStateFlow(false)
    val sirenOn: StateFlow<Boolean> = _sirenOn.asStateFlow()

    val talk = pl.techkrzysiek.betternvr.player.TalkController(viewModelScope, app.manager, ref)
    val download = pl.techkrzysiek.betternvr.player.DownloadController(viewModelScope, app.manager)

    fun startDownload(context: android.content.Context, from: LocalDateTime, to: LocalDateTime, label: String, onDone: (android.net.Uri?, String?) -> Unit) =
        download.start(context, ref, playback.records.value, from, to, label, onDone)

    /** Cycles 1× → 2× → 4× → 8×; sound only at normal speed. */
    fun cycleSpeed() {
        val next = when (playback.speed.value) { 1 -> 2; 2 -> 4; 4 -> 8; else -> 1 }
        playback.setSpeed(next)
        playback.player.setAudio(_audioOn.value && next == 1)
    }

    private var surface: Pair<View, Surface>? = null
    private var clockBase: LocalDateTime? = null
    private var clockAt = 0L
    private var smartJob: Job? = null

    /** All detections of the day (from recording flags and the smart-search index), merged. */
    val events: StateFlow<List<EventSegment>> = combine(playback.records, _smart) { recs, smart -> mergeEvents(eventsFromRecords(recs) + smart) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * Records for the timeline: the file still being written is drawn up to the recorder's "now"
     * (the list itself is re-read every 30 s), so the bar never lags behind the live cursor.
     */
    val timelineRecords: StateFlow<List<RecordFile>> = combine(playback.records, _now) { recs, now ->
        if (now == null || recs.isEmpty() || recs.none { it.end.toLocalDate() == now.toLocalDate() }) recs
        else recs.map { r -> val lag = Duration.between(r.end, now).seconds; if (lag in 0..120) r.copy(end = now) else r }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val visibleEvents: StateFlow<List<EventSegment>> = combine(events, _filter) { ev, f -> if (f.isEmpty()) ev else ev.filter { it.type in f } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        live.player.setAudio(true)
        playback.player.setAudio(true)
        viewModelScope.launch(Dispatchers.IO) {
            val client = runCatching { app.manager.client(ref.deviceId) }.getOrNull()
            val t = client?.currentTime() ?: LocalDateTime.now()
            clockBase = t; clockAt = SystemClock.elapsedRealtime()
            _day.value = t.toLocalDate()
            loadDay(t.toLocalDate(), play = false)
            if (client != null) _ptz.value = client.ptzCaps(ref.channel)
            if (client != null && client.hasSiren(ref.channel)) {
                _sirenSupported.value = true
                _sirenOn.value = client.sirenOn(ref.channel)
            }
        }
        viewModelScope.launch {
            var tick = 0
            while (isActive) {
                clockBase?.let { _now.value = it.plusNanos((SystemClock.elapsedRealtime() - clockAt) * 1_000_000) }
                // new footage and detections keep arriving for today
                if (++tick % 30 == 0 && _day.value == _now.value?.toLocalDate()) {
                    playback.refreshRecords(); loadSmart(_day.value ?: break)
                }
                delay(1000)
            }
        }
    }

    fun attachSurface(view: View, s: Surface) {
        surface = view to s
        if (_mode.value == Mode.LIVE) live.attachSurface(view, s) else playback.attachSurface(view, s)
    }

    fun detachSurface() {
        surface = null
        live.detachSurface()
        playback.detachSurface()
    }

    /** Event the previous/next buttons last jumped to; null after any other kind of seek. */
    private var eventCursor: EventSegment? = null

    /** Timeline / event list / skip buttons land here. */
    fun seek(t: LocalDateTime) {
        eventCursor = null
        seekTo(t)
    }

    private fun seekTo(t: LocalDateTime) {
        val now = _now.value
        if (now != null && !t.isBefore(now.minusSeconds(8))) { goLive(); return }
        if (_mode.value == Mode.LIVE) {
            live.detachSurface()
            _mode.value = Mode.PLAYBACK
            surface?.let { playback.attachSurface(it.first, it.second, resume = false) }
        }
        val d = t.toLocalDate()
        if (d != playback.day) {
            _day.value = d
            loadDay(d, play = true, startAt = t)
        } else {
            playback.seek(t)
        }
    }

    fun skip(seconds: Long) {
        val pos = position() ?: return
        seek(pos.plusSeconds(seconds))
    }

    fun position(): LocalDateTime? =
        if (_mode.value == Mode.LIVE) live.player.position.value ?: _now.value else playback.player.position.value

    fun goLive() {
        if (_mode.value == Mode.LIVE) return
        playback.detachSurface()
        _mode.value = Mode.LIVE
        surface?.let { live.attachSurface(it.first, it.second) }
        val today = _now.value?.toLocalDate() ?: return
        if (_day.value != today) {
            _day.value = today
            loadDay(today, play = false)
        }
    }

    fun setDay(d: LocalDate) {
        val today = _now.value?.toLocalDate()
        if (d == today) {
            if (_mode.value == Mode.LIVE) return
            _day.value = d
            loadDay(d, play = false)
            goLive()
            return
        }
        if (_mode.value == Mode.LIVE) {
            live.detachSurface()
            _mode.value = Mode.PLAYBACK
            surface?.let { playback.attachSurface(it.first, it.second, resume = false) }
        }
        _day.value = d
        loadDay(d, play = true)
    }

    fun togglePause() {
        if (_mode.value == Mode.LIVE) {
            // Pausing live = freeze at this moment in the recording.
            position()?.let { seek(it.minusSeconds(2)) }
            playback.togglePause()
        } else playback.togglePause()
    }

    /** Hold-to-move: repeats the move command while the button is held, like DMSS (~150 ms). */
    fun ptzStart(action: Int) {
        ptzJob?.cancel()
        ptzJob = viewModelScope.launch(Dispatchers.IO) {
            val c = runCatching { app.manager.client(ref.deviceId) }.getOrNull() ?: return@launch
            while (isActive) {
                c.ptz(ref.channel, action, stop = false)
                delay(150)
            }
        }
    }

    fun ptzStop(action: Int) {
        ptzJob?.cancel()
        ptzJob = null
        viewModelScope.launch(Dispatchers.IO) { runCatching { app.manager.client(ref.deviceId).ptz(ref.channel, action, stop = true) } }
    }

    fun setSiren(on: Boolean, onError: (String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val c = app.manager.client(ref.deviceId)
                c.setSiren(ref.channel, on)
                _sirenOn.value = c.sirenOn(ref.channel)
            }.onFailure { onError(it.message ?: "error") }
        }
    }

    fun toggleAudio() {
        val on = !_audioOn.value
        _audioOn.value = on
        live.player.setAudio(on)
        playback.player.setAudio(on)
    }

    fun loadMonth(month: java.time.YearMonth) {
        if (_recordDays.value.containsKey(month)) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val client = app.manager.client(ref.deviceId)
                runCatching { client.recordDays(ref.channel, month) }.getOrElse {
                    // older recorders: derive the days from the month's file list
                    client.findRecords(ref.channel, month.atDay(1).atStartOfDay(), month.atEndOfMonth().atTime(23, 59, 59))
                        .flatMap { listOf(it.start.toLocalDate(), it.end.minusSeconds(1).toLocalDate()) }.filter { java.time.YearMonth.from(it) == month }.toSet()
                }
            }.onSuccess { days -> _recordDays.value = _recordDays.value + (month to days) }
        }
    }

    fun toggleFilter(t: EventType) {
        _filter.value = _filter.value.let { if (t in it) it - t else it + t }
    }

    /**
     * Previous / next event. Playback starts at the keyframe just before an event, so the position
     * alone is ambiguous; the event we jumped to is remembered and navigation continues from it.
     */
    fun jumpToEvent(forward: Boolean) {
        val pos = position() ?: return
        val stops = visibleEvents.value.distinctBy { it.start }
        if (stops.isEmpty()) return
        val cursor = eventCursor
        val near = cursor != null && !pos.isBefore(cursor.start.minusSeconds(15)) && !pos.isAfter(cursor.end.plusSeconds(15))
        val idx = if (cursor != null && near) stops.indexOfFirst { it.start == cursor.start }.takeIf { it >= 0 }
        else stops.indexOfLast { !pos.isBefore(it.start.minusSeconds(5)) && pos.isBefore(it.end) }.takeIf { it >= 0 }
        val target = when {
            idx != null -> stops.getOrNull(if (forward) idx + 1 else idx - 1)
            forward -> stops.firstOrNull { it.start.isAfter(pos) }
            else -> stops.lastOrNull { it.start.isBefore(pos) }
        } ?: return
        eventCursor = target
        seekTo(eventLead(target))
    }

    /** Open an event from the list: like the buttons, a few seconds before it starts. */
    fun playEvent(e: EventSegment) {
        eventCursor = e
        seekTo(eventLead(e))
    }

    /**
     * An event file starts at the moment motion was detected, when the person is often already in view
     * (and short files are over quickly) – start a little earlier so the whole thing is seen.
     */
    private fun eventLead(e: EventSegment): LocalDateTime =
        maxOf(e.start.minusSeconds(5), e.start.toLocalDate().atStartOfDay())

    /** Event under the playback position (highlighted in the list). */
    fun currentEvent(pos: LocalDateTime?): EventSegment? =
        pos?.let { p -> visibleEvents.value.lastOrNull { !p.isBefore(it.start.minusSeconds(5)) && p.isBefore(it.end) } }

    private fun loadDay(d: LocalDate, play: Boolean, startAt: LocalDateTime? = null) {
        playback.load(ref, d, startAt, autoplay = play)
        loadSmart(d)
    }

    private fun loadSmart(d: LocalDate) {
        smartJob?.cancel()
        smartJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val client = app.manager.client(ref.deviceId)
                _smart.value = client.findSmartEvents(ref.channel, d.atStartOfDay(), d.atTime(23, 59, 59))
                _smartDenied.value = false
            } catch (e: RpcException) {
                _smart.value = emptyList()
                _smartDenied.value = e.message.orEmpty().contains("Authority", ignoreCase = true)
            } catch (e: Exception) {
                _smart.value = emptyList()
            }
        }
    }

    /** Talk: the camera's sound is muted meanwhile so it does not loop back into the microphone. */
    fun toggleTalk(onError: (String) -> Unit) {
        if (talk.talking.value) {
            talk.stop()
            live.player.setAudio(_audioOn.value)
        } else {
            live.player.setAudio(false)
            talk.start { msg -> live.player.setAudio(_audioOn.value); onError(msg) }
        }
    }

    override fun onCleared() {
        talk.stop()
        live.release(app)
        playback.release(app)
    }

    companion object {
        fun eventsFromRecords(records: List<RecordFile>): List<EventSegment> = records.filter { it.kind != RecordKind.CONTINUOUS }.flatMap { r ->
            val types = buildSet {
                r.events.forEach { e ->
                    when {
                        e.contains("Human", ignoreCase = true) -> add(EventType.HUMAN)
                        e.contains("Vehicle", ignoreCase = true) -> add(EventType.VEHICLE)
                        e.startsWith("Alarm") -> add(EventType.ALARM)
                        else -> add(EventType.MOTION)
                    }
                }
                if (isEmpty()) add(if (r.kind == RecordKind.ALARM) EventType.ALARM else EventType.MOTION)
            }
            types.map { EventSegment(r.start, r.end, it) }
        }

        /** Joins pieces of the same detection split across consecutive files (gap ≤ 5 s). */
        fun mergeEvents(list: List<EventSegment>): List<EventSegment> {
            val out = ArrayList<EventSegment>()
            for ((_, group) in list.groupBy { it.type }) {
                var cur: EventSegment? = null
                for (e in group.sortedBy { it.start }) {
                    val c = cur
                    cur = if (c != null && Duration.between(c.end, e.start).seconds <= 5) c.copy(end = maxOf(c.end, e.end)) else {
                        c?.let(out::add); e
                    }
                }
                cur?.let(out::add)
            }
            return out.sortedBy { it.start }
        }
    }
}
