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
