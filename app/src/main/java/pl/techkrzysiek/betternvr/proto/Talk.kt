package pl.techkrzysiek.betternvr.proto

import java.io.Closeable
import java.io.IOException
import java.time.LocalDateTime
import kotlin.concurrent.thread

class TalkRefusedException(message: String) : IOException(message)

/**
 * Open talk channel. [send] takes 40 ms of G.711A (320 bytes) and wraps it the way the camera itself
 * sends its microphone: a DHAV audio frame inside a 0x1d packet on the data connection.
 */
class TalkSession internal constructor(private val socket: DvripSocket, private val onClose: () -> Unit) : Closeable {
    @Volatile private var closed = false
    private var seq = 0

    internal fun start() {
        // The camera streams its own microphone back on this socket; read and drop it so it never stalls.
        thread(name = "talk-drain", isDaemon = true) {
            try { while (!closed) socket.read() } catch (_: Exception) { }
            close()
        }
    }

    val isOpen: Boolean get() = !closed

    fun send(alaw: ByteArray) {
        if (closed) return
        val ext = byteArrayOf(0x83.toByte(), 0x01, 0x0e, 0x02) // mono, G.711A, 8000 Hz
        val total = 24 + ext.size + alaw.size + 8
        val f = ByteArray(total)
        "DHAV".toByteArray().copyInto(f)
        f[4] = 0xf0.toByte()
        Dvrip.putLe32(f, 8, seq)
        Dvrip.putLe32(f, 12, total)
        Dvrip.putLe32(f, 16, DhavTime.pack(LocalDateTime.now()))
        val ms = seq * 40
        f[20] = ms.toByte(); f[21] = (ms shr 8).toByte()
        f[22] = ext.size.toByte()
        var sum = 0
        for (i in 0 until 23) sum += f[i].toInt() and 0xff
        f[23] = sum.toByte()
        ext.copyInto(f, 24)
        alaw.copyInto(f, 24 + ext.size)
        "dhav".toByteArray().copyInto(f, total - 8)
        Dvrip.putLe32(f, total - 4, total)
        seq++
        try {
            socket.send(Dvrip.header(Dvrip.CMD_TALK_DATA, total), f)
        } catch (e: Exception) {
            close()
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        socket.close()
        runCatching { onClose() }
    }

    companion object {
        /** 16-bit linear PCM sample to G.711 A-law. */
        fun alaw(sample: Int): Byte {
            var s = sample
            val sign = if (s >= 0) 0x80 else 0
            if (s < 0) s = -s - 1
            s = minOf(s, 32767) shr 3
            val out = if (s < 32) s shr 1 else {
                var seg = 1
                while (s >= 64 && seg < 7) { s = s shr 1; seg++ }
                (seg shl 4) or ((s shr 1) and 0x0f)
            }
            return ((out or sign) xor 0x55).toByte()
        }
    }
}
