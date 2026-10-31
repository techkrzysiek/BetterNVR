package pl.techkrzysiek.betternvr.media

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import pl.techkrzysiek.betternvr.proto.AudioCodec
import pl.techkrzysiek.betternvr.proto.DhavFrame
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Camera audio: AAC (ADTS) through MediaCodec, G.711 and PCM decoded in software, out through AudioTrack.
 * Live keeps latency low (an overgrown queue is dropped); playback plays everything, the network side keeps
 * the backlog bounded (VideoDecoder.awaitRoom).
 */
class AudioPlayer(private val live: Boolean = true, private val clock: MediaClock? = null) {
    private val queue = LinkedBlockingDeque<DhavFrame>()
    @Volatile private var running = true
    @Volatile private var resetRequested = false

    @Volatile var enabled = false
        set(v) {
            field = v
            if (!v) { queue.clear(); resetRequested = true }
        }

    private var track: AudioTrack? = null
    private var decoder: MediaCodec? = null
    private var key: String? = null
    private var firstOut = true

    private val worker = thread(name = "audio-player", isDaemon = true) { loop() }

    fun submit(frame: DhavFrame) {
        if (!enabled || !running || !frame.isAudio || frame.audioCodec == null) return
        if (queue.size > (if (live) 300 else 400)) queue.clear() // far more than the jitter buffer holds: start afresh
        queue.offer(frame)
    }

    fun reset() {
        queue.clear(); resetRequested = true
    }

    fun release() {
        running = false
        queue.clear()
        worker.interrupt()
    }

    private fun loop() {
        try {
            while (running) {
                if (resetRequested) {
                    resetRequested = false
                    releaseAll()
                }
                val f = queue.poll(50, TimeUnit.MILLISECONDS) ?: continue
                // in step with the picture: written a little ahead of its time (the track's own short buffer)
                clock?.let { c ->
                    val due = c.dueNs(f.msCounter) - AUDIO_LEAD_NS
                    while (running && !resetRequested) {
                        val wait = due - System.nanoTime()
                        if (wait <= 0) break
                        Thread.sleep(minOf(wait / 1_000_000, 10L).coerceAtLeast(1))
                    }
                    if (resetRequested) return@let
                }
                if (resetRequested) continue
                runCatching { play(f) }.onFailure {
                    Log.w(TAG, "audio frame failed", it)
                    releaseAll()
                }
            }
        } catch (_: InterruptedException) {
        } finally {
            releaseAll()
        }
    }

    private fun play(f: DhavFrame) {
        when (f.audioCodec) {
            AudioCodec.AAC -> playAac(f.payload)
            AudioCodec.PCM_ALAW -> write(f.sampleRate, 1, ShortArray(f.payload.size) { alaw(f.payload[it]) }, "alaw${f.sampleRate}")
            AudioCodec.PCM_MULAW -> write(f.sampleRate, 1, ShortArray(f.payload.size) { ulaw(f.payload[it]) }, "ulaw${f.sampleRate}")
            AudioCodec.PCM_S16LE -> write(f.sampleRate, f.audioChannels.coerceIn(1, 2),
                ShortArray(f.payload.size / 2) { i -> ((f.payload[2 * i].toInt() and 0xff) or (f.payload[2 * i + 1].toInt() shl 8)).toShort() },
                "s16_${f.sampleRate}")
            AudioCodec.PCM_S8 -> write(f.sampleRate, 1, ShortArray(f.payload.size) { (f.payload[it].toInt() shl 8).toShort() }, "s8_${f.sampleRate}")
            null -> Unit
        }
    }

    /**
     * AAC arrives as ADTS. Not every decoder honours KEY_IS_ADTS (the one on the S23 stayed in raw mode and
     * produced nothing), so the ADTS header is stripped and the decoder gets an AudioSpecificConfig instead.
     */
    private fun playAac(p: ByteArray) {
        var off = 0
        while (off + 7 <= p.size) {
            if ((p[off].toInt() and 0xff) != 0xff || (p[off + 1].toInt() and 0xf0) != 0xf0) return
            val header = if ((p[off + 1].toInt() and 1) == 1) 7 else 9
            val frameLen = ((p[off + 3].toInt() and 0x03) shl 11) or ((p[off + 4].toInt() and 0xff) shl 3) or ((p[off + 5].toInt() and 0xff) ushr 5)
            if (frameLen <= header || off + frameLen > p.size) return
            val objType = ((p[off + 2].toInt() and 0xff) ushr 6) + 1
            val freqIdx = (p[off + 2].toInt() ushr 2) and 0xf
            val channels = ((((p[off + 2].toInt() and 1) shl 2) or ((p[off + 3].toInt() and 0xff) ushr 6))).coerceIn(1, 2)
            decodeAac(p, off + header, frameLen - header, objType, freqIdx, channels)
            off += frameLen
        }
    }

    private fun decodeAac(p: ByteArray, from: Int, len: Int, objType: Int, freqIdx: Int, channels: Int) {
        val rate = AAC_RATES.getOrElse(freqIdx) { 8000 }
        val k = "aac$objType/$rate/$channels"
        if (key != k || decoder == null) {
            releaseAll()
            val asc = (objType shl 11) or (freqIdx shl 7) or (channels shl 3)
            val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, channels).apply {
                setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(byteArrayOf((asc shr 8).toByte(), asc.toByte())))
            }
            decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).also { it.configure(fmt, null, null, 0); it.start() }
            key = k
            firstOut = true
            Log.i(TAG, "AAC decoder: object type $objType, $rate Hz, $channels ch")
        }
        val d = decoder ?: return
        val inIdx = d.dequeueInputBuffer(20_000)
        if (inIdx >= 0) {
            d.getInputBuffer(inIdx)!!.apply { clear(); put(p, from, len) }
            d.queueInputBuffer(inIdx, 0, len, 0, 0)
        }
        val info = MediaCodec.BufferInfo()
        while (true) {
            val out = d.dequeueOutputBuffer(info, 2_000)
            if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) continue
            if (out < 0) break
            val buf = d.getOutputBuffer(out)!!
            val pcm = ShortArray(info.size / 2)
            buf.position(info.offset)
            buf.order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcm)
            d.releaseOutputBuffer(out, false)
            if (pcm.isEmpty()) continue
            val outFmt = d.outputFormat
            ensureTrack(outFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE), outFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
            if (firstOut) { firstOut = false; Log.i(TAG, "AAC: first ${pcm.size} samples decoded, playing") }
            track?.write(pcm, 0, pcm.size)
        }
    }

    private fun write(rate: Int, channels: Int, pcm: ShortArray, k: String) {
        if (key != k) { releaseAll(); key = k }
        ensureTrack(rate, channels)
        track?.write(pcm, 0, pcm.size)
    }

    private fun ensureTrack(rate: Int, channels: Int) {
        val t = track
        if (t != null && t.sampleRate == rate && t.channelCount == channels) return
        t?.let { runCatching { it.stop() }; it.release() }
        val mask = if (channels == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val min = AudioTrack.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT)
        track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(mask).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(min * 2, rate * channels * 2 / 10)) // ~100 ms: timing comes from the clock
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .also { it.play() }
    }

    private fun releaseAll() {
        decoder?.let { runCatching { it.stop() }; runCatching { it.release() } }
        decoder = null
        track?.let { runCatching { it.pause(); it.flush(); it.stop() }; runCatching { it.release() } }
        track = null
        key = null
    }

    companion object {
        private const val TAG = "AudioPlayer"
        private const val AUDIO_LEAD_NS = 60_000_000L
        private val AAC_RATES = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)

        fun alaw(b: Byte): Short {
            var a = (b.toInt() and 0xff) xor 0x55
            var t = (a and 0x0f) shl 4
            val seg = (a and 0x70) shr 4
            t = when (seg) { 0 -> t + 8; 1 -> t + 0x108; else -> (t + 0x108) shl (seg - 1) }
            return (if (a and 0x80 != 0) t else -t).toShort()
        }

        fun ulaw(b: Byte): Short {
            val u = (b.toInt() and 0xff).inv() and 0xff
            var t = ((u and 0x0f) shl 3) + 0x84
            t = t shl ((u and 0x70) shr 4)
            return (if (u and 0x80 != 0) 0x84 - t else t - 0x84).toShort()
        }
    }
}
