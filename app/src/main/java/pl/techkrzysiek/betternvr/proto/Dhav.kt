package pl.techkrzysiek.betternvr.proto

import java.time.LocalDateTime

/** DHAV packs wall-clock time (recorder local time, no zone) into 32 bits. */
object DhavTime {
    fun pack(t: LocalDateTime): Int =
        ((t.year % 100) shl 26) or (t.monthValue shl 22) or (t.dayOfMonth shl 17) or (t.hour shl 12) or (t.minute shl 6) or t.second

    fun unpack(v: Int): LocalDateTime? = runCatching {
        LocalDateTime.of(2000 + (v ushr 26), (v ushr 22) and 0xf, (v ushr 17) and 0x1f, (v ushr 12) and 0x1f, (v ushr 6) and 0x3f, v and 0x3f)
    }.getOrNull()
}

enum class VideoCodec(val mime: String) { H264("video/avc"), H265("video/hevc") }

enum class AudioCodec { AAC, PCM_ALAW, PCM_MULAW, PCM_S16LE, PCM_S8 }

class DhavFrame(
    val type: Int,
    val sequence: Int,
    /** Recorder local wall-clock time of the frame (1 s resolution). */
    val time: LocalDateTime?,
    /** Free-running 16-bit millisecond counter. */
    val msCounter: Int,
    val payload: ByteArray,
    val videoCodec: VideoCodec?,
    val width: Int,
    val height: Int,
    val fps: Int,
    val audioCodec: AudioCodec?,
    val sampleRate: Int,
    val audioChannels: Int,
) {
    val isVideo get() = type == TYPE_I || type == TYPE_P || type == TYPE_B
    val isKey get() = type == TYPE_I
    val isAudio get() = type == TYPE_AUDIO

    companion object {
        const val TYPE_I = 0xfd
        const val TYPE_P = 0xfc
        const val TYPE_B = 0xfb
        const val TYPE_AUDIO = 0xf0
        const val TYPE_AUX = 0xf1
    }
}

/**
 * Incremental DHAV demuxer. Frames are validated against their "dhav" trailer; a damaged frame is
 * dropped and reported via [corruptFrames] so the decoder can wait for the next intact keyframe.
 */
class DhavParser {
    private var buf = ByteArray(1 shl 20)
    private var start = 0
    private var end = 0

    var corruptFrames = 0L
        private set

    private var lastVideoCodec: VideoCodec? = null
    private var lastWidth = 0
    private var lastHeight = 0
    private var lastFps = 0

    fun reset() {
        start = 0; end = 0
    }

    fun feed(data: ByteArray, off: Int = 0, len: Int = data.size, out: (DhavFrame) -> Unit) {
        ensureSpace(len)
        System.arraycopy(data, off, buf, end, len)
        end += len
        parse(out)
    }

    private fun ensureSpace(len: Int) {
        if (end + len <= buf.size) return
        val used = end - start
        if (used + len <= buf.size && start > 0) {
            System.arraycopy(buf, start, buf, 0, used)
        } else {
            val nb = ByteArray(maxOf(buf.size * 2, used + len))
            System.arraycopy(buf, start, nb, 0, used)
            buf = nb
        }
        start = 0; end = used
    }

    private fun parse(out: (DhavFrame) -> Unit) {
        while (end - start >= 24) {
            if (!isMagic(start)) {
                val next = findMagic(start + 1)
                if (next < 0) {
                    start = maxOf(start, end - 3)
                    return
                }
                start = next
                continue
            }
            val total = Dvrip.le32(buf, start + 12)
            if (total < 24 || total > Dvrip.MAX_PAYLOAD) {
                corruptFrames++; start += 4; continue
            }
            if (end - start < total) return
            val trailer = start + total - 8
            val ok = buf[trailer] == 'd'.code.toByte() && buf[trailer + 1] == 'h'.code.toByte() &&
                buf[trailer + 2] == 'a'.code.toByte() && buf[trailer + 3] == 'v'.code.toByte() &&
                Dvrip.le32(buf, trailer + 4) == total
            if (!ok) {
                corruptFrames++; start += 4; continue
            }
            decodeFrame(start, total)?.let(out)
            start += total
        }
        if (start == end) { start = 0; end = 0 }
    }

    private fun isMagic(p: Int) = buf[p] == 'D'.code.toByte() && buf[p + 1] == 'H'.code.toByte() &&
        buf[p + 2] == 'A'.code.toByte() && buf[p + 3] == 'V'.code.toByte()

    private fun findMagic(from: Int): Int {
        var p = from
        while (p + 4 <= end) {
            if (isMagic(p)) return p
            p++
        }
        return -1
    }

    private fun decodeFrame(p: Int, total: Int): DhavFrame? {
        val type = buf[p + 4].toInt() and 0xff
        val seq = Dvrip.le32(buf, p + 8)
        val date = Dvrip.le32(buf, p + 16)
        if (type == DhavFrame.TYPE_AUX) return null
        val ms = Dvrip.le16(buf, p + 20)
        val extLen = buf[p + 22].toInt() and 0xff
        var videoCodec: VideoCodec? = null
        var width = 0
        var height = 0
        var fps = 0
        var audioCodec: AudioCodec? = null
        var sampleRate = 8000
        var channels = 1
        var e = p + 24
        val extEnd = p + 24 + extLen
        if (extEnd > p + total - 8) return null
        while (e < extEnd) {
            when (buf[e].toInt() and 0xff) {
                0x80 -> { width = 8 * (buf[e + 2].toInt() and 0xff); height = 8 * (buf[e + 3].toInt() and 0xff); e += 4 }
                0x81 -> {
                    videoCodec = when (buf[e + 2].toInt() and 0xff) { 0x02, 0x04, 0x08 -> VideoCodec.H264; 0x0c -> VideoCodec.H265; else -> null }
                    fps = buf[e + 3].toInt() and 0xff; e += 4
                }
                0x82 -> { width = Dvrip.le16(buf, e + 4); height = Dvrip.le16(buf, e + 6); e += 8 }
                0x83 -> {
                    channels = buf[e + 1].toInt() and 0xff
                    audioCodec = audioCodecOf(buf[e + 2].toInt() and 0xff)
                    sampleRate = sampleRateOf(buf[e + 3].toInt() and 0xff); e += 4
                }
                0x8c -> {
                    channels = buf[e + 2].toInt() and 0xff
                    audioCodec = audioCodecOf(buf[e + 3].toInt() and 0xff)
                    sampleRate = sampleRateOf(buf[e + 4].toInt() and 0xff); e += 8
                }
                0x88, 0x91, 0x92, 0x93, 0x95, 0x9a, 0x9b, 0xb3 -> e += 8
                0x84, 0x85, 0x8b, 0x94, 0x96, 0xa0, 0xb2, 0xb4 -> e += 4
                else -> e = extEnd
            }
        }
        val payStart = extEnd
        val payEnd = p + total - 8
        if (payEnd < payStart) return null
        val isVideo = type == DhavFrame.TYPE_I || type == DhavFrame.TYPE_P || type == DhavFrame.TYPE_B
        if (isVideo) {
            if (videoCodec != null) lastVideoCodec = videoCodec else videoCodec = lastVideoCodec
            if (width > 0 && height > 0) { lastWidth = width; lastHeight = height } else { width = lastWidth; height = lastHeight }
            if (fps > 0) lastFps = fps else fps = lastFps
            if (videoCodec == null) videoCodec = sniffVideoCodec(payStart, payEnd)?.also { lastVideoCodec = it }
        }
        return DhavFrame(
            type = type, sequence = seq, time = DhavTime.unpack(date), msCounter = ms,
            payload = buf.copyOfRange(payStart, payEnd),
            videoCodec = if (isVideo) videoCodec else null, width = width, height = height, fps = fps,
            audioCodec = if (type == DhavFrame.TYPE_AUDIO) audioCodec else null, sampleRate = sampleRate, audioChannels = channels,
        )
    }

    /** Fallback when the 0x81 extension is missing: look at the first NAL header. */
    private fun sniffVideoCodec(from: Int, to: Int): VideoCodec? {
        var i = from
        while (i + 4 < to) {
            if (buf[i].toInt() == 0 && buf[i + 1].toInt() == 0 && buf[i + 2].toInt() == 1) {
                val b = buf[i + 3].toInt() and 0xff
                val h265Type = (b shr 1) and 0x3f
                if (h265Type in 32..34) return VideoCodec.H265
                if ((b and 0x1f) in 7..8 || (b and 0x1f) == 5) return VideoCodec.H264
                return null
            }
            i++
        }
        return null
    }

    companion object {
        private val SAMPLE_RATES = intArrayOf(8000, 4000, 8000, 11025, 16000, 20000, 22050, 32000, 44100, 48000, 96000, 192000, 64000)
        fun sampleRateOf(index: Int) = SAMPLE_RATES.getOrElse(index) { 8000 }
        fun audioCodecOf(code: Int): AudioCodec? = when (code) {
            0x1a -> AudioCodec.AAC
            0x0e -> AudioCodec.PCM_ALAW
            0x0a, 0x16 -> AudioCodec.PCM_MULAW
            0x0c, 0x10 -> AudioCodec.PCM_S16LE
            0x07 -> AudioCodec.PCM_S8
            else -> null
        }
    }
}
