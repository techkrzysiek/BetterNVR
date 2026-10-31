package pl.techkrzysiek.betternvr.media

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import pl.techkrzysiek.betternvr.proto.AudioCodec
import pl.techkrzysiek.betternvr.proto.DhavFrame
import java.io.FileDescriptor
import java.nio.ByteBuffer

/**
 * Writes the received stream to MP4. Video and AAC audio are copied as they are; G.711 and PCM audio are
 * encoded to AAC (MP4 can't hold them). Cameras without sound get a silent AAC track, so every clip has
 * audio (editors and messengers handle such files better). Recording starts at the first keyframe.
 */
class ClipRecorder(fd: FileDescriptor) {
    private val muxer = MediaMuxer(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private var started = false
    private var videoTrack = -1
    private var audioTrack = -1
    /** First audio frame seen; decides between copying AAC and encoding PCM. */
    private var audioFirst: DhavFrame? = null
    private var encoder: AacEncoder? = null
    /** Silent track: how far silence has been written, -1 when the camera's own audio is used. */
    private var silenceUs = -1L
    private val unwrap = MsUnwrapper()
    private var baseUs = -1L
    private var lastVideoUs = -1L
    private var lastAudioUs = -1L
    private val info = MediaCodec.BufferInfo()
    var samples = 0
        private set

    /** Frames held after the first keyframe until the first audio frame shows up (or ~1.5 s pass). */
    private val waiting = ArrayList<DhavFrame>()

    @Synchronized
    fun onFrame(f: DhavFrame) {
        try {
            if (started) { write(f); return }
            if (f.isAudio && audioFirst == null && f.audioCodec != null && (f.audioCodec != AudioCodec.AAC || f.payload.size > 7)) audioFirst = f
            if (waiting.isEmpty()) {
                if (!f.isVideo || !f.isKey || f.videoCodec == null || Nal.codecConfig(f.videoCodec, f.payload) == null) return
            }
            waiting.add(f)
            // Recordings (and downloads) start on a keyframe, usually before any sound: wait a moment for audio
            // so the file gets both tracks, then write everything held so far in order.
            val heldMs = (f.msCounter - waiting.first().msCounter + 65536) % 65536
            if (audioFirst != null || heldMs > 1500 || waiting.size > 90) begin()
        } catch (e: Exception) {
            Log.w(TAG, "write failed", e)
        }
    }

    private fun begin() {
        val key = waiting.first()
        val vc = key.videoCodec ?: return
        val csd = Nal.codecConfig(vc, key.payload) ?: return
        val fmt = MediaFormat.createVideoFormat(vc.mime, key.width.coerceAtLeast(16), key.height.coerceAtLeast(16))
        csd.forEachIndexed { i, b -> fmt.setByteBuffer("csd-$i", ByteBuffer.wrap(b)) }
        videoTrack = muxer.addTrack(fmt)
        val a = audioFirst
        runCatching { if (a != null) addAudioTrack(a) else addSilentTrack() }
            .onFailure { Log.w(TAG, "clip without audio (${a?.audioCodec} ${a?.sampleRate} Hz)", it) }
        muxer.start()
        started = true
        val held = waiting.toList()
        waiting.clear()
        held.forEach(::write)
    }

    private fun addAudioTrack(a: DhavFrame) {
        val rate: Int
        val ch: Int
        val asc: Int
        if (a.audioCodec == AudioCodec.AAC) {
            val h = a.payload
            val freqIdx = (h[2].toInt() shr 2) and 0xf
            ch = (((h[2].toInt() and 1) shl 2) or ((h[3].toInt() and 0xff) shr 6)).coerceIn(1, 2)
            rate = RATES.getOrElse(freqIdx) { 8000 }
            asc = ((((h[2].toInt() and 0xff) shr 6) + 1) shl 11) or (freqIdx shl 7) or (ch shl 3)
        } else {
            val freqIdx = RATES.indexOf(a.sampleRate)
            require(freqIdx >= 0) { "no AAC sample rate ${a.sampleRate}" }
            rate = a.sampleRate
            ch = a.audioChannels.coerceIn(1, 2)
            asc = (2 shl 11) or (freqIdx shl 7) or (ch shl 3) // AAC-LC, what AacEncoder produces
            encoder = AacEncoder(rate, ch)
        }
        addAacTrack(rate, ch, asc)
    }

    private fun addSilentTrack() {
        encoder = AacEncoder(SILENT_RATE, 1)
        addAacTrack(SILENT_RATE, 1, (2 shl 11) or (RATES.indexOf(SILENT_RATE) shl 7) or (1 shl 3))
        silenceUs = 0
    }

    private fun addAacTrack(rate: Int, ch: Int, asc: Int) {
        val afmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, ch)
        afmt.setByteBuffer("csd-0", ByteBuffer.wrap(byteArrayOf((asc shr 8).toByte(), asc.toByte())))
        audioTrack = muxer.addTrack(afmt)
    }

    private fun write(f: DhavFrame) {
        if (f.isAudio) {
            if (audioTrack < 0 || silenceUs >= 0) return
            if (encoder == null) {
                if (f.audioCodec == AudioCodec.AAC && f.payload.size > 7) writeAudio(f)
            } else {
                val pcm = pcm16(f) ?: return
                encode(pcm, timeUs(f.msCounter))
            }
            return
        }
        if (!f.isVideo) return
        val vc = f.videoCodec ?: return
        val data = Nal.normalizeForMuxer(vc, f.payload)
        if (data.isEmpty()) return
        var us = timeUs(f.msCounter)
        if (us <= lastVideoUs) us = lastVideoUs + 1000
        lastVideoUs = us
        info.set(0, data.size, us, if (f.isKey) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
        muxer.writeSampleData(videoTrack, ByteBuffer.wrap(data), info)
        samples++
        if (silenceUs >= 0) writeSilence(us, SILENT_RATE / 10)
    }

    private fun encode(pcm: ByteArray, ptsUs: Long) {
        val enc = encoder ?: return
        runCatching { enc.encode(pcm, ptsUs, ::writeAacSample) }.onFailure {
            Log.w(TAG, "AAC encoding failed, rest of the clip without audio", it)
            enc.release(); encoder = null; audioTrack = -1
        }
    }

    /** Silence up to the video time, in chunks of at least [minSamples]; a long gap stays a gap. */
    private fun writeSilence(untilUs: Long, minSamples: Int) {
        if (untilUs - silenceUs > 2_000_000) silenceUs = untilUs - 200_000
        val n = ((untilUs - silenceUs) * SILENT_RATE / 1_000_000).toInt()
        if (n <= 0 || n < minSamples) return
        encode(ByteArray(n * 2), silenceUs)
        silenceUs += n * 1_000_000L / SILENT_RATE
    }

    /**
     * MP4 wants raw AAC frames without the ADTS header. The muxer reads from BufferInfo.offset of the backing
     * array and ignores the buffer position, so each frame is copied out instead of wrapped with an offset.
     */
    private fun writeAudio(f: DhavFrame) {
        val p = f.payload
        var off = 0
        var us = timeUs(f.msCounter)
        while (off + 7 <= p.size) {
            if ((p[off].toInt() and 0xff) != 0xff || (p[off + 1].toInt() and 0xf0) != 0xf0) return
            val hdr = if ((p[off + 1].toInt() and 1) == 0) 9 else 7 // protection_absent=0 adds a CRC
            val frameLen = ((p[off + 3].toInt() and 0x03) shl 11) or ((p[off + 4].toInt() and 0xff) shl 3) or ((p[off + 5].toInt() and 0xff) ushr 5)
            if (frameLen <= hdr || off + frameLen > p.size) return
            val raw = p.copyOfRange(off + hdr, off + frameLen)
            writeAacSample(ByteBuffer.wrap(raw), 0, raw.size, us)
            us = lastAudioUs + 1024 * 1_000_000L / RATES.getOrElse((p[off + 2].toInt() shr 2) and 0xf) { 8000 }
            off += frameLen
        }
    }

    private fun writeAacSample(buf: ByteBuffer, bi: MediaCodec.BufferInfo) = writeAacSample(buf, bi.offset, bi.size, bi.presentationTimeUs)

    private fun writeAacSample(buf: ByteBuffer, offset: Int, size: Int, ptsUs: Long) {
        var us = ptsUs
        if (us <= lastAudioUs) us = lastAudioUs + 1000
        lastAudioUs = us
        info.set(offset, size, us, 0)
        muxer.writeSampleData(audioTrack, buf, info)
    }

    /** G.711 / PCM frame as 16-bit little-endian samples for the encoder. */
    private fun pcm16(f: DhavFrame): ByteArray? {
        val p = f.payload
        val samples = when (f.audioCodec) {
            AudioCodec.PCM_S16LE -> return p
            AudioCodec.PCM_ALAW -> ShortArray(p.size) { AudioPlayer.alaw(p[it]) }
            AudioCodec.PCM_MULAW -> ShortArray(p.size) { AudioPlayer.ulaw(p[it]) }
            AudioCodec.PCM_S8 -> ShortArray(p.size) { (p[it].toInt() shl 8).toShort() }
            else -> return null
        }
        val out = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            out[2 * i] = samples[i].toByte()
            out[2 * i + 1] = (samples[i].toInt() shr 8).toByte()
        }
        return out
    }

    private fun timeUs(ms16: Int): Long {
        val us = unwrap.toUs(ms16)
        if (baseUs < 0) baseUs = us
        return (us - baseUs).coerceAtLeast(0)
    }

    /** Finishes the file; returns false when nothing usable was written. */
    @Synchronized
    fun stop(): Boolean {
        if (!started && waiting.isNotEmpty()) runCatching { begin() }
        if (silenceUs >= 0 && encoder != null) writeSilence(lastVideoUs, 1)
        encoder?.let { e ->
            runCatching { e.finish(::writeAacSample) }
            e.release()
            encoder = null
        }
        val ok = started && samples > 0
        runCatching { if (started) muxer.stop() }
        runCatching { muxer.release() }
        return ok
    }

    companion object {
        private const val TAG = "ClipRecorder"
        private const val SILENT_RATE = 16000
        private val RATES = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)
    }
}
