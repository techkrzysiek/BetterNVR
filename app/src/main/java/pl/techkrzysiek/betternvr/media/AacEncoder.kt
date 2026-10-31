package pl.techkrzysiek.betternvr.media

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import java.nio.ByteBuffer

/**
 * PCM → AAC-LC for MP4 clips, which cannot carry G.711 or raw PCM. DMSS does the same when saving
 * (PlaySDK CDataRecorder → CAudioTrans → AAC encoder).
 */
class AacEncoder(private val sampleRate: Int, private val channels: Int) {
    private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    private val info = MediaCodec.BufferInfo()
    private val bytesPerSecond = sampleRate * channels * 2L

    init {
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, if (sampleRate <= 8000) 24_000 else if (sampleRate <= 16000) 32_000 else 64_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
        }
        try {
            codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
        } catch (e: Exception) {
            codec.release()
            throw e
        }
    }

    /** Feeds 16-bit little-endian PCM; [out] receives every finished AAC frame (raw, no ADTS). */
    fun encode(pcm: ByteArray, ptsUs: Long, out: (ByteBuffer, MediaCodec.BufferInfo) -> Unit) {
        var off = 0
        var misses = 0
        while (off < pcm.size && misses < 10) {
            val idx = codec.dequeueInputBuffer(5_000)
            if (idx < 0) { misses++; drain(out); continue }
            val buf = codec.getInputBuffer(idx)!!
            buf.clear()
            val n = minOf(buf.remaining(), pcm.size - off)
            buf.put(pcm, off, n)
            codec.queueInputBuffer(idx, 0, n, ptsUs + off * 1_000_000L / bytesPerSecond, 0)
            off += n
        }
        drain(out)
    }

    /** Flushes what the encoder still holds (its last frame and the priming delay). */
    fun finish(out: (ByteBuffer, MediaCodec.BufferInfo) -> Unit) {
        val idx = codec.dequeueInputBuffer(50_000)
        if (idx < 0) return
        codec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        repeat(50) { if (drain(out, 10_000)) return }
    }

    fun release() {
        runCatching { codec.stop() }
        runCatching { codec.release() }
    }

    /** Returns true once the end of stream came out. */
    private fun drain(out: (ByteBuffer, MediaCodec.BufferInfo) -> Unit, timeoutUs: Long = 0): Boolean {
        while (true) {
            val i = codec.dequeueOutputBuffer(info, timeoutUs)
            if (i == MediaCodec.INFO_TRY_AGAIN_LATER) return false
            if (i < 0) continue // format / buffers changed
            val buf = codec.getOutputBuffer(i)!!
            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                buf.position(info.offset)
                buf.limit(info.offset + info.size)
                out(buf, info)
            }
            val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
            codec.releaseOutputBuffer(i, false)
            if (eos) return true
        }
    }
}
