package pl.techkrzysiek.betternvr.player

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.view.PixelCopy
import android.view.Surface
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import pl.techkrzysiek.betternvr.media.AudioPlayer
import pl.techkrzysiek.betternvr.media.ClipRecorder
import pl.techkrzysiek.betternvr.media.MediaSaver
import pl.techkrzysiek.betternvr.media.VideoDecoder
import pl.techkrzysiek.betternvr.proto.DhavFrame
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.coroutines.resume

sealed interface PlayerState {
    data object Idle : PlayerState
    data object Connecting : PlayerState
    data object Playing : PlayerState
    data object Paused : PlayerState
    data object NoRecording : PlayerState
    data object NoCamera : PlayerState
    data class Error(val message: String, val retrying: Boolean) : PlayerState
}

/** Glue between a network stream and the screen: decoder, audio, clip recorder and snapshot. */
class StreamPlayer(private val live: Boolean) : VideoDecoder.Callback {
    private val _state = MutableStateFlow<PlayerState>(PlayerState.Idle)
    val state: StateFlow<PlayerState> = _state.asStateFlow()
    private val _videoSize = MutableStateFlow(0 to 0)
    val videoSize: StateFlow<Pair<Int, Int>> = _videoSize.asStateFlow()
    private val _position = MutableStateFlow<LocalDateTime?>(null)
    /** Wall-clock time of the last frame shown — drives the playback timeline. */
    val position: StateFlow<LocalDateTime?> = _position.asStateFlow()
    private val _recording = MutableStateFlow(false)
    val recording: StateFlow<Boolean> = _recording.asStateFlow()
    /** SystemClock.elapsedRealtime() when the current clip started, for the on-screen timer. */
    @Volatile var recordingSince = 0L
        private set
    private val _audioOn = MutableStateFlow(false)
    val audioOn: StateFlow<Boolean> = _audioOn.asStateFlow()

    @Volatile private var decoder: VideoDecoder? = null
    /** Shared by picture and sound: when each frame is due, with the jitter buffer. */
    private val clock = pl.techkrzysiek.betternvr.media.MediaClock(live)
    private val audio = AudioPlayer(live, clock)
    @Volatile private var recorder: ClipRecorder? = null
    private var pendingVideo: MediaSaver.PendingVideo? = null
    private var surfaceView: View? = null
    private val _corrupt = MutableStateFlow(0)
    /** Frames the recorder sent damaged (seen with old firmware on 4K playback). */
    val corrupt: StateFlow<Int> = _corrupt.asStateFlow()

    fun markCorrupt() { _corrupt.value++ }
    fun clearCorrupt() { _corrupt.value = 0 }

    val hasSurface get() = decoder != null

    fun setState(s: PlayerState) { _state.value = s }

    fun attachSurface(view: View, surface: Surface) {
        decoder?.release()
        surfaceView = view
        clock.reset()
        decoder = VideoDecoder(surface, live, this, clock).also { it.speed = speed }
    }

    fun detachSurface() {
        decoder?.release()
        decoder = null
        surfaceView = null
    }

    fun onFrame(f: DhavFrame) {
        if (!live) decoder?.awaitRoom(f)
        recorder?.onFrame(f)
        when {
            f.isVideo -> decoder?.submit(f)
            f.isAudio -> audio.submit(f)
        }
    }

    /** Discards buffered media (before a seek / reconnect). */
    fun reset() {
        clock.reset()
        decoder?.reset()
        audio.reset()
    }

    var paused: Boolean
        get() = decoder?.paused ?: false
        set(v) { decoder?.paused = v }

    var speed: Int = 1
        set(v) { field = v; clock.speed = v; decoder?.speed = v }

    fun setPosition(t: LocalDateTime?) { _position.value = t }

    @Volatile private var target: LocalDateTime? = null

    /**
     * A seek to [t] was requested: show [t] and "loading" right away and ignore frames that still
     * belong to the previous position, so the cursor never jumps back before the new picture arrives.
     */
    fun expect(t: LocalDateTime) {
        target = t
        _position.value = t
        _state.value = PlayerState.Connecting
    }

    fun setAudio(on: Boolean) {
        audio.enabled = on
        _audioOn.value = on
    }

    override fun onVideoSize(width: Int, height: Int) { _videoSize.value = width to height }

    override fun onRendered(time: LocalDateTime?) {
        target?.let { t ->
            if (time == null || kotlin.math.abs(java.time.Duration.between(t, time).seconds) > 90) return
            target = null
        }
        if (_state.value !is PlayerState.Playing && _state.value !is PlayerState.Paused) _state.value = PlayerState.Playing
        if (time != null) _position.value = time
    }

    override fun onDecoderError(message: String) {
        _state.value = PlayerState.Error(message, retrying = false)
    }

    fun startRecording(context: Context, label: String): Boolean {
        if (recorder != null) return true
        val v = MediaSaver.createVideo(context, fileName(label)) ?: return false
        return try {
            recorder = ClipRecorder(v.pfd.fileDescriptor)
            pendingVideo = v
            recordingSince = android.os.SystemClock.elapsedRealtime()
            _recording.value = true
            true
        } catch (e: Exception) {
            MediaSaver.finishVideo(context, v, keep = false)
            false
        }
    }

    /** Returns the saved clip, or null when nothing was recorded. */
    fun stopRecording(context: Context): Uri? {
        val r = recorder ?: return null
        recorder = null
        _recording.value = false
        val ok = r.stop()
        val v = pendingVideo ?: return null
        pendingVideo = null
        MediaSaver.finishVideo(context, v, keep = ok)
        return if (ok) v.uri else null
    }

    suspend fun snapshot(context: Context, label: String): Uri? {
        val view = surfaceView ?: return null
        val (w, h) = _videoSize.value
        val bw = if (w > 0) w else view.width
        val bh = if (h > 0) h else view.height
        if (bw <= 0 || bh <= 0) return null
        val bitmap = copyPicture(view, bw, bh) ?: return null
        return MediaSaver.saveJpeg(context, bitmap, fileName(label))
    }

    /** Current picture scaled to [width] px wide (for thumbnails); null when nothing is shown yet. */
    suspend fun capture(width: Int): Bitmap? {
        val view = surfaceView ?: return null
        val (w, h) = _videoSize.value
        if (w <= 0 || h <= 0 || _state.value !is PlayerState.Playing) return null
        return copyPicture(view, width, (width.toLong() * h / w).toInt().coerceAtLeast(1))
    }

    /** The decoded picture at [w]×[h]: PixelCopy for a SurfaceView, the texture itself for a TextureView. */
    private suspend fun copyPicture(view: View, w: Int, h: Int): Bitmap? = when (view) {
        is TextureView -> withContext(Dispatchers.Main) { runCatching { view.getBitmap(w, h) }.getOrNull() }
        is SurfaceView -> {
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val ok = suspendCancellableCoroutine { cont ->
                runCatching { PixelCopy.request(view, bitmap, { r -> cont.resume(r == PixelCopy.SUCCESS) }, copyHandler) }.onFailure { cont.resume(false) }
            }
            if (ok) bitmap else null
        }
        else -> null
    }

    fun release(context: Context) {
        stopRecording(context)
        decoder?.release()
        decoder = null
        audio.release()
    }

    companion object {
        private val copyHandler by lazy { Handler(HandlerThread("pixel-copy").apply { start() }.looper) }
        private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")

        fun fileName(label: String): String {
            val clean = label.replace(Regex("[^\\p{L}\\p{N}_-]+"), "_").trim('_').take(40)
            return "${clean}_${LocalDateTime.now().format(STAMP)}"
        }
    }
}
