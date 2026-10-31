package pl.techkrzysiek.betternvr.media

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface
import pl.techkrzysiek.betternvr.proto.DhavFrame
import pl.techkrzysiek.betternvr.proto.VideoCodec
import java.nio.ByteBuffer
import java.time.LocalDateTime
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Hardware H.264/H.265 decoding straight to a Surface. Frames are shown when [clock] says they are due (their
 * DHAV timestamps plus a jitter buffer shared with the sound), live and in playback alike, so the picture
 * moves evenly however unevenly the recorder sends it.
 */
class VideoDecoder(
    private val surface: Surface,
    private val live: Boolean,
    private val callback: Callback,
    private val clock: MediaClock,
) {
    interface Callback {
        fun onVideoSize(width: Int, height: Int) {}
        /** A frame reached the screen; [time] is the recorder wall-clock of that frame. */
        fun onRendered(time: LocalDateTime?) {}
        fun onDecoderError(message: String) {}
    }

    private class Config(val codec: VideoCodec, val width: Int, val height: Int, val csd: List<ByteArray>)

    private val queue = LinkedBlockingDeque<DhavFrame>()
    @Volatile private var running = true
    @Volatile private var resetRequested = false
    @Volatile private var waitKeyOnSubmit = true
    @Volatile var paused = false
        set(v) { field = v; if (!v) resync = true }
    @Volatile private var resync = true
    /** Playback speed factor (frames arrive N× faster, so they are shown N× faster). */
    @Volatile var speed = 1
        set(v) { field = v; clock.speed = v; resync = true }

    private var codec: MediaCodec? = null
    private var config: Config? = null
    private val unwrap = MsUnwrapper()
    /** pts → recorder time and 16-bit ms counter of frames fed to the codec. */
    private val frameTimes = ArrayDeque<Triple<Long, LocalDateTime?, Int>>()
    /** ms counter of the frame on screen (-1 = none yet) and when it got there, for [awaitRoom]. */
    @Volatile private var shownMs = -1
    @Volatile private var shownAtNs = 0L
    /** Bumped by [reset]: a frame decoded before a seek must not count as shown afterwards. */
    @Volatile private var generation = 0
    private val shownLock = Any()
    private var anchorPtsUs = 0L
    private var anchorNs = 0L
    private var failures = 0
    private var inputs = 0
    private var outputs = 0

    // Started last so every field above is initialized before the worker touches it.
    private val worker = thread(name = "video-decoder", isDaemon = true) { loop() }

    fun submit(frame: DhavFrame) {
        if (!frame.isVideo || !running) return
        // the jitter buffer keeps up to a few seconds queued; far beyond that the decoder is not keeping up
        if (live && queue.size > 150) {
            Log.i(TAG, "live queue overflow (${queue.size}), waiting for next keyframe")
            queue.clear(); waitKeyOnSubmit = true
        }
        if (waitKeyOnSubmit) {
            if (!frame.isKey) return
            waitKeyOnSubmit = false
        }
        queue.offer(frame)
    }

    /**
     * Playback only: holds the caller (the stream's network thread) while more than [AHEAD_MS] (× speed) of
     * video waits to be shown. Recordings are requested at twice the playing speed, because at 1× recorders
     * send some footage slower than real time (4K at 0.91× on the NVR4216) and picture and sound kept running
     * dry; holding the reader lets TCP slow the recorder down once this buffer is full.
     */
    fun awaitRoom(f: DhavFrame) {
        if (live) return
        // always room for the jitter buffer plus a margin, or the stream would be held back below what is needed
        val limit = maxOf((AHEAD_MS * speed.coerceAtLeast(1)).coerceAtMost(MAX_AHEAD_MS), (clock.delayMs * speed.coerceAtLeast(1) + 2000).toInt())
        while (running && !resetRequested) {
            val shown = shownMs
            if (shown < 0) return
            // signed distance on the 16-bit ms clock: audio is often a little behind the video frame shown
            val ahead = Math.floorMod(f.msCounter - shown + 32768, 65536) - 32768
            if (ahead <= limit) return
            // Safety net: if nothing reaches the screen, holding the stream back could only stall it.
            if (System.nanoTime() - shownAtNs > STALL_NS) return
            Thread.sleep(20)
        }
    }

    /** Drops everything queued and restarts at the next keyframe (used for seeking). */
    fun reset() {
        synchronized(shownLock) { generation++; shownMs = -1 }
        queue.clear()
        waitKeyOnSubmit = true
        resetRequested = true
    }

    /** Stops decoding; waits briefly so the codec no longer touches a surface that is being destroyed. */
    fun release() {
        running = false
        queue.clear()
        worker.interrupt()
        if (Thread.currentThread() !== worker) runCatching { worker.join(700) }
    }

    private fun loop() {
        val info = MediaCodec.BufferInfo()
        var pending: DhavFrame? = null
        try {
            while (running) {
                if (resetRequested) {
                    resetRequested = false
                    pending = null
                    releaseCodec()
                    unwrap.reset()
                    frameTimes.clear()
                    resync = true
                }
                if (pending == null) pending = queue.poll(10, TimeUnit.MILLISECONDS)
                val f = pending
                if (f != null) {
                    if (f.isKey) prepareCodec(f)
                    val c = codec
                    if (c == null) {
                        pending = null
                    } else if (feed(c, f)) {
                        pending = null
                    }
                }
                codec?.let { drain(it, info, if (pending != null) 2_000 else 8_000) }
            }
        } catch (_: InterruptedException) {
        } catch (e: Exception) {
            Log.w(TAG, "decoder loop failed", e)
            callback.onDecoderError(e.message ?: e.javaClass.simpleName)
        } finally {
            releaseCodec()
        }
    }

    private fun prepareCodec(f: DhavFrame) {
        val vc = f.videoCodec ?: return
        val csd = Nal.codecConfig(vc, f.payload) ?: return
        val cur = config
        if (codec != null && cur != null && cur.codec == vc && cur.width == f.width && cur.height == f.height &&
            cur.csd.size == csd.size && cur.csd.indices.all { cur.csd[it].contentEquals(csd[it]) }
        ) return
        releaseCodec()
        val w = if (f.width > 0) f.width else 1920
        val h = if (f.height > 0) f.height else 1080
        val format = MediaFormat.createVideoFormat(vc.mime, w, h).apply {
            csd.forEachIndexed { i, b -> setByteBuffer("csd-$i", ByteBuffer.wrap(b)) }
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxOf(w * h, 2 * 1024 * 1024))
            if (live && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        }
        try {
            val c = MediaCodec.createDecoderByType(vc.mime)
            try {
                c.configure(format, surface, null, 0)
                c.start()
            } catch (e: Exception) {
                c.release()
                throw e
            }
            codec = c
            config = Config(vc, f.width, f.height, csd)
            inputs = 0; outputs = 0
            Log.i(TAG, "started ${c.name} ${vc.mime} ${w}x$h live=$live csd=${csd.map { it.size }}")
            failures = 0
            resync = true
            if (f.width > 0) callback.onVideoSize(f.width, f.height)
        } catch (e: Exception) {
            Log.w(TAG, "cannot start ${vc.mime} ${w}x$h", e)
            failures++
            callback.onDecoderError("${vc.name} ${w}x$h: ${e.message ?: "unsupported"}")
        }
    }

    /** Returns true when the frame was consumed (queued or deliberately dropped). */
    private fun feed(c: MediaCodec, f: DhavFrame): Boolean {
        val idx = try {
            c.dequeueInputBuffer(5_000)
        } catch (e: IllegalStateException) {
            onCodecFailure(e); return true
        }
        if (idx < 0) return false
        val buf = c.getInputBuffer(idx) ?: return true
        buf.clear()
        if (f.payload.size > buf.capacity()) {
            c.queueInputBuffer(idx, 0, 0, 0, 0)
            waitKeyOnSubmit = true
            return true
        }
        buf.put(f.payload)
        val pts = unwrap.toUs(f.msCounter)
        c.queueInputBuffer(idx, 0, f.payload.size, pts, if (f.isKey) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
        inputs++
        if (inputs == 1 || inputs == 15 || inputs % 300 == 0) Log.i(TAG, "${c.name}: in=$inputs out=$outputs queue=${queue.size} key=${f.isKey} size=${f.payload.size}")
        frameTimes.addLast(Triple(pts, f.time, f.msCounter))
        if (frameTimes.size > 300) frameTimes.removeFirst()
        return true
    }

    private fun drain(c: MediaCodec, info: MediaCodec.BufferInfo, timeoutUs: Long) {
        val idx = try {
            c.dequeueOutputBuffer(info, timeoutUs)
        } catch (e: IllegalStateException) {
            onCodecFailure(e); return
        }
        when {
            idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                val fmt = c.outputFormat
                var w = fmt.getInteger(MediaFormat.KEY_WIDTH)
                var h = fmt.getInteger(MediaFormat.KEY_HEIGHT)
                if (fmt.containsKey("crop-right") && fmt.containsKey("crop-left")) {
                    w = fmt.getInteger("crop-right") - fmt.getInteger("crop-left") + 1
                    h = fmt.getInteger("crop-bottom") - fmt.getInteger("crop-top") + 1
                }
                callback.onVideoSize(w, h)
            }
            idx >= 0 -> {
                val gen = generation
                var time: LocalDateTime? = null
                var ms = -1
                while (frameTimes.isNotEmpty() && frameTimes.first().first <= info.presentationTimeUs) {
                    val (_, t, m) = frameTimes.removeFirst()
                    time = t; ms = m
                }
                while (paused && running && !resetRequested) Thread.sleep(15)
                if (resync) { resync = false; clock.reset() }
                val target = if (ms >= 0) clock.dueNs(ms) else renderTimeNs(info.presentationTimeUs)
                // wait in small steps so a seek or release is not held up by a long buffer
                while (running && !resetRequested) {
                    val wait = target - System.nanoTime() - 4_000_000
                    if (wait <= 0) break
                    Thread.sleep(minOf(wait / 1_000_000, 20L).coerceAtLeast(1))
                }
                if (!running || resetRequested) {
                    c.releaseOutputBuffer(idx, false); return
                }
                c.releaseOutputBuffer(idx, target)
                if (ms >= 0) synchronized(shownLock) {
                    if (gen == generation) { shownMs = ms; shownAtNs = System.nanoTime() }
                }
                outputs++
                if (outputs == 1) Log.i(TAG, "${c.name}: first frame out after $inputs inputs")
                callback.onRendered(time)
            }
        }
    }

    private fun renderTimeNs(ptsUs: Long): Long {
        val now = System.nanoTime()
        if (resync) {
            resync = false
            anchorPtsUs = ptsUs; anchorNs = now + 60_000_000
        }
        val t = anchorNs + (ptsUs - anchorPtsUs) * 1000 / speed.coerceAtLeast(1)
        if (t < now - 400_000_000 || t > now + 3_000_000_000) {
            anchorPtsUs = ptsUs; anchorNs = now
            return now
        }
        return t
    }

    private fun onCodecFailure(e: Exception) {
        Log.w(TAG, "codec failure", e)
        releaseCodec()
        waitKeyOnSubmit = true
        if (++failures >= 3) callback.onDecoderError(e.message ?: "decoder error")
    }

    private fun releaseCodec() {
        codec?.let { runCatching { it.stop() }; runCatching { it.release() } }
        codec = null
        config = null
    }

    companion object {
        private const val TAG = "VideoDecoder"
        private const val AHEAD_MS = 3000
        private const val MAX_AHEAD_MS = 8000
        private const val STALL_NS = 1_500_000_000L
    }
}
