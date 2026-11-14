package pl.techkrzysiek.betternvr.proto

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock

/**
 * Dahua private protocol ("DVRIP") on TCP 37777: every packet is a 32-byte header followed by
 * `header[4..7]` (LE32) bytes of payload. Only packet shapes verified against libnetsdk and real
 * recorders are built here (an unverified field value once rebooted a recorder).
 */
object Dvrip {
    const val HEADER_SIZE = 32
    const val MAX_PAYLOAD = 16 * 1024 * 1024

    const val CMD_LOGIN = 0xa0
    const val CMD_LOGIN_REPLY = 0xb0
    const val CMD_HEARTBEAT = 0xa1
    const val CMD_MONITOR = 0x11
    const val CMD_SUBCONN = 0xf1
    const val CMD_RPC = 0xf6
    const val CMD_TEXT = 0xf4
    const val CMD_TALK_DATA = 0x1d
    const val CMD_PLAYBACK = 0xc2
    const val CMD_PLAYBACK_STOP = 0xc9
    const val CMD_LIVE_DATA = 0xbc
    const val CMD_PLAYBACK_DATA = 0xbb

    const val SUBCONN_LIVE = 1
    const val SUBCONN_PLAYBACK = 2

    fun le32(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xff) or ((b[off + 1].toInt() and 0xff) shl 8) or
            ((b[off + 2].toInt() and 0xff) shl 16) or ((b[off + 3].toInt() and 0xff) shl 24)

    fun le16(b: ByteArray, off: Int): Int = (b[off].toInt() and 0xff) or ((b[off + 1].toInt() and 0xff) shl 8)

    fun putLe32(b: ByteArray, off: Int, v: Int) {
        b[off] = v.toByte(); b[off + 1] = (v shr 8).toByte(); b[off + 2] = (v shr 16).toByte(); b[off + 3] = (v shr 24).toByte()
    }

    fun md5Upper(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.ISO_8859_1)).joinToString("") { "%02X".format(it) }

    /** Dahua's legacy 8-character password hash ("gen1"). */
    fun gen1Hash(password: String): String {
        val d = MessageDigest.getInstance("MD5").digest(password.toByteArray(Charsets.ISO_8859_1))
        val sb = StringBuilder()
        for (i in 0 until 16 step 2) {
            val v = ((d[i].toInt() and 0xff) + (d[i + 1].toInt() and 0xff)) % 62
            sb.append((if (v < 10) v + 48 else if (v < 36) v + 55 else v + 61).toChar())
        }
        return sb.toString()
    }

    fun realmRequest(): ByteArray = header(CMD_LOGIN).also {
        it[1] = 0x01
        hexInto(it, 24, "050201010000a1aa")
    }

    fun loginRequest(user: String, password: String, realm: String, random: String): Pair<ByteArray, ByteArray> {
        val ha1 = md5Upper("$user:$realm:$password")
        val h2 = md5Upper("$user:$random:$ha1")
        val h1 = md5Upper(gen1Hash(password))
        val body = "$user&&$h2$h1".toByteArray(Charsets.ISO_8859_1)
        val h = header(CMD_LOGIN, body.size)
        h[1] = 0x05
        hexInto(h, 24, "050200080000a1aa")
        return h to body
    }

    fun heartbeat(): ByteArray = header(CMD_HEARTBEAT)

    /** Text command packet ("Key:Value\r\n" lines) used by protocol version > 5 recorders and DMSS. */
    fun text(body: String): Pair<ByteArray, ByteArray> {
        val b = body.toByteArray(Charsets.UTF_8)
        return header(CMD_TEXT, b.size) to b
    }

    fun rpc(id: Int, session: Int, body: ByteArray): ByteArray {
        val h = header(CMD_RPC, body.size)
        putLe32(h, 8, id)
        putLe32(h, 16, body.size)
        putLe32(h, 24, session)
        return h
    }

    fun subConnect(session: Int, channel: Int, type: Int): ByteArray = header(CMD_SUBCONN).also {
        it[2] = ((channel + 1) shr 8).toByte()
        putLe32(it, 8, session)
        it[12] = type.toByte()
        it[13] = (channel + 1).toByte()
    }

    /** Realtime monitor request: a bitmap of ALL active channels (channel -> stream, 0 = main, 1 = sub). */
    fun monitor(active: Map<Int, Int>, channelCount: Int): ByteArray {
        val dataLen = maxOf(16, channelCount)
        val p = ByteArray(HEADER_SIZE + dataLen)
        p[0] = CMD_MONITOR.toByte()
        p[3] = 1
        putLe32(p, 4, dataLen)
        for ((ch, stream) in active) {
            if (ch in 0 until 24 && ch < dataLen) {
                p[8 + ch] = 1
                p[HEADER_SIZE + ch] = stream.toByte()
            }
        }
        return p
    }

    /** Play a record file from [from] to [to] ("IsTime" mode, so [from] may lie inside the file). */
    fun playback(channel: Int, disk: Int, cluster: Int, from: LocalDateTime, to: LocalDateTime): ByteArray = header(CMD_PLAYBACK).also {
        it[2] = ((channel + 1) shr 8).toByte()
        it[8] = (channel + 1).toByte()
        it[9] = from.year.toByte(); it[10] = (from.year shr 8).toByte()
        it[11] = from.monthValue.toByte(); it[12] = from.dayOfMonth.toByte()
        it[13] = from.hour.toByte(); it[14] = from.minute.toByte(); it[15] = from.second.toByte()
        it[16] = disk.toByte()
        putLe32(it, 17, cluster)
        it[26] = (disk shr 8).toByte()
        it[27] = 1
        putLe32(it, 28, DhavTime.pack(to))
    }

    /**
     * PTZ move as DMSS sends it (command 0x12): [8] 0 = move / 1 = stop, [9] channel (0-based),
     * [10] action (0 up, 1 down, 2 left, 3 right, 4/5 zoom in/out, 0x20–0x23 diagonals), [11]/[12] speeds.
     */
    fun ptz(channel: Int, action: Int, p1: Int, p2: Int, stop: Boolean): ByteArray = header(0x12).also {
        it[8] = if (stop) 1 else 0
        it[9] = channel.toByte()
        it[10] = action.toByte()
        if (!stop) { it[11] = p1.toByte(); it[12] = p2.toByte() }
    }

    /** Which days of a month have footage (as DMSS's calendar asks): reply 0xb6, body[day-1] == 1. */
    fun recordMonth(channel: Int, year: Int, month: Int): ByteArray = header(0xa5).also {
        it[8] = (channel + 1).toByte()
        it[9] = year.toByte(); it[10] = (year shr 8).toByte()
        it[11] = month.toByte()
        it[20] = 1
    }

    fun playbackStop(channel: Int): ByteArray = header(CMD_PLAYBACK_STOP).also {
        it[2] = ((channel + 1) shr 8).toByte()
        it[8] = (channel + 1).toByte()
    }

    fun header(cmd: Int, payloadLen: Int = 0): ByteArray = ByteArray(HEADER_SIZE).also {
        it[0] = cmd.toByte()
        putLe32(it, 4, payloadLen)
    }

    private fun hexInto(b: ByteArray, off: Int, hex: String) {
        for (i in 0 until hex.length / 2) b[off + i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }
}

class DvripPacket(val header: ByteArray, val body: ByteArray) {
    val cmd: Int get() = header[0].toInt() and 0xff
}

/** One TCP connection speaking DVRIP framing. Writes are serialized; reads must come from a single thread. */
class DvripSocket(host: String, port: Int, connectTimeoutMs: Int = 6000, readTimeoutMs: Int = 15000) : Closeable {
    private val socket = Socket()
    private val input: DataInputStream
    private val output: BufferedOutputStream

    init {
        socket.tcpNoDelay = true
        socket.receiveBufferSize = 2 * 1024 * 1024
        try {
            socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
        } catch (e: IOException) {
            runCatching { socket.close() }
            throw e
        }
        socket.soTimeout = readTimeoutMs
        input = DataInputStream(BufferedInputStream(socket.getInputStream(), 256 * 1024))
        output = BufferedOutputStream(socket.getOutputStream(), 64 * 1024)
    }

    var readTimeoutMs: Int
        get() = socket.soTimeout
        set(v) { socket.soTimeout = v }

    fun send(header: ByteArray, body: ByteArray? = null) = synchronized(output) {
        output.write(header)
        if (body != null) output.write(body)
        output.flush()
    }

    fun read(): DvripPacket {
        val h = ByteArray(Dvrip.HEADER_SIZE)
        input.readFully(h)
        val len = Dvrip.le32(h, 4)
        if (len < 0 || len > Dvrip.MAX_PAYLOAD) throw IOException("Invalid packet length $len")
        val body = ByteArray(len)
        input.readFully(body)
        return DvripPacket(h, body)
    }

    override fun close() {
        runCatching { socket.close() }
    }

    companion object {
        /** Head start of each address over the next one. */
        const val STAGGER_MS = 400L

        /**
         * Connects to whichever of [endpoints] answers first, preferring the earlier ones: each address starts
         * [STAGGER_MS] after the previous one (at once when all before it have failed), so a local address wins
         * whenever it works, while an unreachable one costs this short delay instead of its whole timeout.
         * Sockets that connect after the winner are closed. Throws [UnreachableException] when none answers
         * (with a single address, its own error).
         */
        fun connectFirst(endpoints: List<Endpoint>, connectTimeoutMs: Int = 6000, readTimeoutMs: Int = 15000): Pair<DvripSocket, Endpoint> {
            require(endpoints.isNotEmpty()) { "No address" }
            if (endpoints.size == 1) endpoints[0].let { return DvripSocket(it.host, it.port, connectTimeoutMs, readTimeoutMs) to it }
            val lock = ReentrantLock()
            val changed = lock.newCondition()
            var winner: Pair<DvripSocket, Endpoint>? = null
            val errors = arrayOfNulls<Throwable>(endpoints.size)
            val start = System.currentTimeMillis()
            endpoints.forEachIndexed { i, ep ->
                thread(name = "dh-connect-${ep.host}", isDaemon = true) {
                    val go = lock.withLock {
                        val at = start + i * STAGGER_MS
                        while (winner == null && (0 until i).any { errors[it] == null }) {
                            val wait = at - System.currentTimeMillis()
                            if (wait <= 0) break
                            changed.await(wait, TimeUnit.MILLISECONDS)
                        }
                        winner == null
                    }
                    if (go) {
                        val result = runCatching { DvripSocket(ep.host, ep.port, connectTimeoutMs, readTimeoutMs) }
                        lock.withLock {
                            result.onSuccess { s -> if (winner == null) winner = s to ep else s.close() }
                                .onFailure { errors[i] = it }
                            changed.signalAll()
                        }
                    }
                }
            }
            lock.withLock {
                while (true) {
                    winner?.let { return it }
                    if (errors.all { it != null }) throw UnreachableException(endpoints.zip(errors.map { it!! }))
                    changed.await()
                }
            }
        }
    }
}
