package pl.techkrzysiek.betternvr.proto

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Times exchanged with the recorder are its own local time; they are never converted through UTC or the phone's zone. */
val NVR_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

enum class RecordKind { CONTINUOUS, MOTION, ALARM, SMART, MANUAL }

data class RecordFile(
    val channel: Int,
    val start: LocalDateTime,
    val end: LocalDateTime,
    val disk: Int,
    val cluster: Int,
    val partition: Int,
    val lengthBytes: Long,
    val kind: RecordKind,
    val events: List<String>,
) {
    operator fun contains(t: LocalDateTime) = !t.isBefore(start) && t.isBefore(end)
}

enum class EventType { MOTION, HUMAN, VEHICLE, ALARM }

/** Something detected between [start] and [end] (recorder local time). */
data class EventSegment(val start: LocalDateTime, val end: LocalDateTime, val type: EventType)

/** The recorder has no camera attached to this channel ("ChannelNotAdd"). */
class NoCameraException(message: String) : java.io.IOException(message)

class LoginException(message: String, val code: Int) : Exception(message)

/** One address of a recorder. */
data class Endpoint(val host: String, val port: Int) {
    /** "host" when the port is [defaultPort], else "host:port" (IPv6 in brackets). */
    fun label(defaultPort: Int = DEFAULT_PORT): String {
        val h = if (':' in host) "[$host]" else host
        return if (port == defaultPort) h else "$h:$port"
    }

    companion object {
        const val DEFAULT_PORT = 37777

        /** "host", "host:port", "[ipv6]" or "[ipv6]:port"; a bare IPv6 address keeps [defaultPort]. Null when unusable. */
        fun parse(text: String, defaultPort: Int = DEFAULT_PORT): Endpoint? {
            val t = text.trim()
            if (t.isEmpty()) return null
            val (host, port) = when {
                t.startsWith("[") -> {
                    val end = t.indexOf(']')
                    if (end < 0) return null
                    val rest = t.substring(end + 1)
                    t.substring(1, end) to when {
                        rest.isEmpty() -> defaultPort
                        rest.startsWith(":") -> rest.substring(1).toIntOrNull() ?: return null
                        else -> return null
                    }
                }
                t.count { it == ':' } == 1 -> t.substringBefore(':') to (t.substringAfter(':').toIntOrNull() ?: return null)
                else -> t to defaultPort
            }
            if (host.isBlank() || host.any { it.isWhitespace() } || port !in 1..65535) return null
            return Endpoint(host, port)
        }
    }
}

/** A different device (another login realm) answered on an address of the recorder. */
class WrongDeviceException : java.io.IOException("another device")

/** No address of the recorder answered; [failures] holds each address with its error, in the order tried. */
class UnreachableException(val failures: List<Pair<Endpoint, Throwable>>) : java.io.IOException(
    failures.joinToString("; ") { (e, t) -> "${e.label()}: ${reason(t)}" },
    failures.firstOrNull()?.second,
) {
    private companion object {
        fun reason(t: Throwable): String = when (t) {
            is java.net.SocketTimeoutException -> "no answer"
            is WrongDeviceException -> "another device"
            is java.net.UnknownHostException -> "unknown host"
            is java.net.NoRouteToHostException -> "no route"
            is java.net.ConnectException -> if (t.message.orEmpty().contains("refused", true)) "refused" else t.message ?: "cannot connect"
            else -> t.message ?: t.javaClass.simpleName
        }
    }
}

data class DeviceInfo(
    val type: String,
    val version: String,
    val channelCount: Int,
    val channelNames: List<String>,
    /** Channels (0-based) this account may watch live / play back; null = no restriction reported. */
    val liveChannels: Set<Int>? = null,
    val playbackChannels: Set<Int>? = null,
) {
    fun canLive(ch: Int) = ch in 0 until channelCount && (liveChannels?.contains(ch) ?: true)
    fun canPlay(ch: Int) = ch in 0 until channelCount && (playbackChannels?.contains(ch) ?: true)
}
