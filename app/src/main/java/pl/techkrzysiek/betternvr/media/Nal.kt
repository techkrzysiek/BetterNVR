package pl.techkrzysiek.betternvr.media

import pl.techkrzysiek.betternvr.proto.VideoCodec
import java.io.ByteArrayOutputStream

/** Annex-B NAL unit helpers. */
object Nal {
    private val START = byteArrayOf(0, 0, 0, 1)

    /** Ranges of NAL unit payloads (start codes excluded). */
    fun units(data: ByteArray): List<IntRange> {
        val starts = ArrayList<Pair<Int, Int>>() // (startCodePos, payloadPos)
        var i = 0
        while (i + 3 <= data.size) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0) {
                if (data[i + 2].toInt() == 1) { starts.add(i to i + 3); i += 3; continue }
                if (i + 4 <= data.size && data[i + 2].toInt() == 0 && data[i + 3].toInt() == 1) { starts.add(i to i + 4); i += 4; continue }
            }
            i++
        }
        return starts.mapIndexed { idx, (_, payload) ->
            val end = if (idx + 1 < starts.size) starts[idx + 1].first else data.size
            payload until end
        }.filter { !it.isEmpty() }
    }

    fun type(codec: VideoCodec, first: Byte): Int =
        if (codec == VideoCodec.H264) first.toInt() and 0x1f else (first.toInt() shr 1) and 0x3f

    fun isParameterSet(codec: VideoCodec, type: Int) =
        if (codec == VideoCodec.H264) type == 7 || type == 8 else type in 32..34

    /** csd buffers for MediaFormat, or null if the frame lacks parameter sets. */
    fun codecConfig(codec: VideoCodec, payload: ByteArray): List<ByteArray>? {
        val units = units(payload)
        fun withStart(r: IntRange) = START + payload.copyOfRange(r.first, r.last + 1)
        return if (codec == VideoCodec.H264) {
            val sps = units.firstOrNull { type(codec, payload[it.first]) == 7 } ?: return null
            val pps = units.firstOrNull { type(codec, payload[it.first]) == 8 } ?: return null
            listOf(withStart(sps), withStart(pps))
        } else {
            val out = ByteArrayOutputStream()
            for (t in 32..34) {
                val u = units.firstOrNull { type(codec, payload[it.first]) == t } ?: return null
                out.write(withStart(u))
            }
            listOf(out.toByteArray())
        }
    }

    /** Re-emits the access unit with 4-byte start codes, dropping parameter sets (they live in csd). */
    fun normalizeForMuxer(codec: VideoCodec, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(payload.size + 16)
        for (u in units(payload)) {
            val t = type(codec, payload[u.first])
            if (isParameterSet(codec, t)) continue
            if (codec == VideoCodec.H264 && t == 9) continue // access unit delimiter
            if (codec == VideoCodec.H265 && t == 35) continue
            out.write(START)
            out.write(payload, u.first, u.last - u.first + 1)
        }
        return out.toByteArray()
    }
}

/** Unwraps the 16-bit DHAV millisecond counter into a monotonic microsecond timestamp. */
class MsUnwrapper {
    private var last = -1
    private var base = 0L

    fun reset() { last = -1; base = 0 }

    fun toUs(ms16: Int): Long {
        if (last >= 0) {
            val diff = ms16 - last
            if (diff < -32768) base += 65536 else if (diff > 32768) base -= 65536
        }
        last = ms16
        return (base + ms16) * 1000L
    }
}
