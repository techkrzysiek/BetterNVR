package pl.techkrzysiek.betternvr.proto

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.time.LocalDateTime
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class RpcException(val method: String, val code: Long, message: String) : IOException("$method: $message ($code)")

/**
 * One logged-in session with a Dahua recorder over TCP 37777. All calls block and must run off the
 * main thread. Live and playback streams use their own sub-connections bound to this session.
 * A recorder may have several [endpoints] (e.g. LAN and VPN address): the session uses the first that answers
 * (see [DvripSocket.connectFirst]), and its sub-connections go to that same address.
 */
class DahuaClient(
    private val endpoints: List<Endpoint>,
    private val user: String,
    private val password: String,
    /** When set, only a recorder with this login [realm] is logged in to; another device on an address counts as unreachable. */
    private val expectRealm: String? = null,
) : Closeable {
    constructor(host: String, port: Int, user: String, password: String) : this(listOf(Endpoint(host, port)), user, password)

    /** The address in use: the one that answered in [connect]. */
    @Volatile var endpoint: Endpoint = endpoints.first(); private set
    val host: String get() = endpoint.host
    val port: Int get() = endpoint.port

    /**
     * The recorder's login realm ("Login to <id>"). It is the same on every address of one recorder (the stored
     * password hash depends on it), so it tells the recorder apart from another device answering on an address.
     */
    @Volatile var realm: String? = null; private set

    @Volatile var sessionId = 0; private set
    @Volatile var channelCount = 0; private set
    @Volatile var protocolVersion = 0; private set

    @Volatile private var main: DvripSocket? = null
    @Volatile private var closed = false
    private val pending = ConcurrentHashMap<Int, CompletableFuture<JSONObject>>()
    private val fragments = HashMap<Int, ByteArrayOutputStream>()
    private val nextId = AtomicInteger(1)
    private val liveLock = Any()
    private val liveStreams = HashMap<Int, Pair<MediaStream, Int>>()
    private val streams = ConcurrentHashMap.newKeySet<MediaStream>()
    private val talks = ConcurrentHashMap.newKeySet<TalkSession>()
    private val pendingText = ConcurrentHashMap<Int, CompletableFuture<Map<String, String>>>()
    @Volatile private var pendingMonth: CompletableFuture<ByteArray>? = null
    private val monthLock = Any()
    private val nextTid = AtomicInteger(1000)
    /** Stop/cleanup requests go out on this thread, never on the caller's (which may be the UI thread). */
    private val background = Executors.newSingleThreadExecutor { r -> Thread(r, "dh-bg-$host").apply { isDaemon = true } }

    /** Newer recorders (protocol > 5) are driven with the text commands DMSS uses; older ones with binary packets. */
    val textMode: Boolean get() = protocolVersion > 5

    /** Invoked once when the session is lost (not after [close]). */
    @Volatile var onDisconnected: ((Throwable?) -> Unit)? = null

    val isConnected: Boolean get() = main != null && !closed

    /** Not used by any stream, playback, download or talk. */
    val isIdle: Boolean get() = streams.isEmpty() && talks.isEmpty()

    fun connect() {
        var left = endpoints
        val others = ArrayList<Pair<Endpoint, Throwable>>()
        while (true) {
            val (s, used) = try {
                DvripSocket.connectFirst(left)
            } catch (e: IOException) {
                if (others.isEmpty()) throw e
                throw UnreachableException(others + ((e as? UnreachableException)?.failures ?: listOf(left[0] to e)))
            }
            val loggedIn = try {
                login(s)
            } catch (e: Exception) {
                s.close()
                throw e
            }
            if (loggedIn) {
                endpoint = used
                s.readTimeoutMs = 60_000
                main = s
                thread(name = "dh-main-$host", isDaemon = true) { readLoop(s) }
                thread(name = "dh-keepalive-$host", isDaemon = true) { keepAliveLoop(s) }
                return
            }
            s.close()
            others += used to WrongDeviceException()
            left = left - used
            if (left.isEmpty()) throw UnreachableException(others)
        }
    }

    /** Challenge and login on [s]; false, before any login attempt, when the realm is not [expectRealm]. */
    private fun login(s: DvripSocket): Boolean {
        s.send(Dvrip.realmRequest())
        val challenge = parseKv(String(s.read().body, Charsets.ISO_8859_1))
        val realm = challenge["Realm"] ?: throw LoginException("Recorder did not send a login challenge", -1)
        val random = challenge["Random"] ?: throw LoginException("Recorder did not send a login challenge", -1)
        if (expectRealm != null && realm != expectRealm) return false
        val (h, b) = Dvrip.loginRequest(user, password, realm, random)
        s.send(h, b)
        val reply = s.read().header
        val result = ((reply[8].toInt() and 0xff) shl 8) or (reply[9].toInt() and 0xff)
        if (result != 0x0008) throw LoginException(loginError(result), result)
        sessionId = Dvrip.le32(reply, 16)
        channelCount = (reply[10].toInt() and 0xff) or ((reply[2].toInt() and 0xff) shl 8)
        protocolVersion = (reply[3].toInt() and 0xff) shr 4
        this.realm = realm
        return true
    }

    private fun readLoop(s: DvripSocket) {
        try {
            while (!closed) {
                val p = s.read()
                when (p.cmd) {
                    Dvrip.CMD_RPC -> handleRpc(p)
                    0xb6 -> pendingMonth?.complete(p.body)
                    Dvrip.CMD_TEXT -> {
                        val kv = parseKv(String(p.body, Charsets.ISO_8859_1))
                        kv["TransactionID"]?.toIntOrNull()?.let { pendingText.remove(it)?.complete(kv) }
                    }
                }
            }
        } catch (e: Throwable) {
            if (!closed) lost(e)
        }
    }

    private fun handleRpc(p: DvripPacket) {
        val id = Dvrip.le32(p.header, 8)
        val total = Dvrip.le32(p.header, 16)
        var body = p.body
        if (total > body.size || fragments.containsKey(id)) {
            val acc = fragments.getOrPut(id) { ByteArrayOutputStream(total.coerceAtLeast(body.size)) }
            acc.write(body)
            if (acc.size() < total) return
            fragments.remove(id)
            body = acc.toByteArray()
        }
        val json = runCatching { JSONObject(String(body, Charsets.UTF_8)) }.getOrNull() ?: return
        pending.remove(json.optInt("id", id))?.complete(json)
    }

    private fun keepAliveLoop(s: DvripSocket) {
        try {
            while (!closed) {
                Thread.sleep(20_000)
                if (closed) break
                s.send(Dvrip.heartbeat())
            }
        } catch (_: InterruptedException) {
        } catch (e: Throwable) {
            if (!closed) lost(e)
        }
    }

    private fun lost(e: Throwable) {
        if (closed) return
        closed = true
        main?.close()
        pending.values.forEach { it.completeExceptionally(IOException("Connection lost", e)) }
        pending.clear()
        pendingText.values.forEach { it.completeExceptionally(IOException("Connection lost", e)) }
        pendingText.clear()
        streams.forEach { it.close() }
        onDisconnected?.invoke(e)
    }

    /** Text command (0xf4, "Key:Value" lines). Returns the reply fields; throws unless FaultCode is OK. */
    fun text(body: String, timeoutMs: Long = 8_000): Map<String, String> {
        val s = main ?: throw IOException("Not connected")
        val tid = nextTid.getAndIncrement()
        val f = CompletableFuture<Map<String, String>>()
        pendingText[tid] = f
        try {
            val (h, b) = Dvrip.text("TransactionID:$tid\r\n$body")
            s.send(h, b)
            val reply = f.get(timeoutMs, TimeUnit.MILLISECONDS)
            if (reply["FaultCode"] == "ChannelNotAdd") throw NoCameraException("no camera on this channel")
            if (reply["FaultCode"] != "OK") {
                val name = body.substringAfter("ParameterName:").substringBefore("\r\n")
                throw IOException("$name: ${reply["FaultCode"] ?: "no result"}")
            }
            return reply
        } catch (e: TimeoutException) {
            throw IOException("no answer from recorder", e)
        } catch (e: java.util.concurrent.ExecutionException) {
            throw (e.cause as? IOException) ?: IOException(e.cause)
        } finally {
            pendingText.remove(tid)
        }
    }

    /** Fire-and-forget text command, sent from the background thread. */
    private fun textLater(body: String) {
        if (closed) return
        background.execute {
            runCatching {
                val (h, b) = Dvrip.text("TransactionID:${nextTid.getAndIncrement()}\r\n$body")
                main?.send(h, b)
            }
        }
    }

    /** JSON-RPC over DVRIP (command 0xf6). Throws [RpcException] when the recorder answers result=false. */
    fun rpc(method: String, params: Any? = null, obj: Long? = null, timeoutMs: Long = 10_000): JSONObject {
        val s = main ?: throw IOException("Not connected")
        val id = nextId.getAndIncrement()
        val req = JSONObject()
            .put("method", method)
            .put("params", params ?: JSONObject.NULL)
            .put("id", id)
            .put("session", sessionId)
        if (obj != null) req.put("object", obj)
        val body = req.toString().toByteArray(Charsets.UTF_8)
        val f = CompletableFuture<JSONObject>()
        pending[id] = f
        try {
            s.send(Dvrip.rpc(id, sessionId, body), body)
            val res = f.get(timeoutMs, TimeUnit.MILLISECONDS)
            if (!res.optBoolean("result", false) && res.opt("result") !is Number) {
                val err = res.optJSONObject("error")
                throw RpcException(method, err?.optLong("code") ?: 0, err?.optString("message").orEmpty().ifEmpty { "error" })
            }
            return res
        } catch (e: TimeoutException) {
            throw IOException("$method: no answer from recorder", e)
        } catch (e: java.util.concurrent.ExecutionException) {
            throw (e.cause as? IOException) ?: IOException(e.cause)
        } finally {
            pending.remove(id)
        }
    }

    fun deviceInfo(): DeviceInfo {
        val type = runCatching { rpc("magicBox.getDeviceType").optJSONObject("params")?.optString("type") }.getOrNull().orEmpty()
        val version = runCatching {
            rpc("magicBox.getSoftwareVersion").optJSONObject("params")?.optJSONObject("version")?.optString("Version")
        }.getOrNull().orEmpty()
        val names = runCatching {
            val table = rpc("configManager.getConfig", JSONObject().put("name", "ChannelTitle")).optJSONObject("params")?.optJSONArray("table")
            List(table?.length() ?: 0) { i -> table!!.optJSONObject(i)?.optString("Name").orEmpty() }
        }.getOrDefault(emptyList())
        val (live, playback) = permissions()
        return DeviceInfo(
            type, version, channelCount, List(channelCount) { i -> names.getOrNull(i)?.takeIf { it.isNotBlank() } ?: "" },
            liveChannels = live, playbackChannels = playback,
        )
    }

    /**
     * Per-channel rights of the logged-in account from its AuthorityList ("Monitor_01", "Replay_03", …).
     * Returns nulls (= show everything) when the recorder does not report them.
     */
    private fun permissions(): Pair<Set<Int>?, Set<Int>?> = runCatching {
        val list = rpc("userManager.getUserInfo", JSONObject().put("name", user))
            .optJSONObject("params")?.optJSONObject("user")?.optJSONArray("AuthorityList").toStrings()
        fun channels(prefix: String): Set<Int>? {
            val chs = list.filter { it.startsWith(prefix) }.mapNotNull { it.removePrefix(prefix).toIntOrNull()?.minus(1) }.toSet()
            return chs.takeIf { list.isNotEmpty() && (it.isNotEmpty() || list.none { a -> a.startsWith("AuthAll") || a == "Monitor" || a == "Replay" }) }
        }
        channels("Monitor_") to channels("Replay_")
    }.getOrDefault(null to null)

    /** Recorder clock (local time, as the recorder shows it). */
    fun currentTime(): LocalDateTime? = runCatching {
        LocalDateTime.parse(rpc("global.getCurrentTime").getJSONObject("params").getString("time"), NVR_TIME)
    }.getOrNull()

    /** Days of [month] with footage on [channel] — one quick query, as DMSS's calendar does. */
    fun recordDays(channel: Int, month: java.time.YearMonth): Set<java.time.LocalDate> = synchronized(monthLock) {
        val s = main ?: throw IOException("Not connected")
        val f = CompletableFuture<ByteArray>()
        pendingMonth = f
        try {
            s.send(Dvrip.recordMonth(channel, month.year, month.monthValue))
            val body = f.get(8, TimeUnit.SECONDS)
            // Some channels answer with an empty error reply; the caller then lists the month's files instead.
            if (body.isEmpty()) throw IOException("record status not available")
            (1..month.lengthOfMonth()).filter { it - 1 < body.size && body[it - 1].toInt() != 0 }.map { month.atDay(it) }.toSet()
        } catch (e: TimeoutException) {
            throw IOException("no answer from recorder", e)
        } finally {
            pendingMonth = null
        }
    }

    fun findRecords(channel: Int, from: LocalDateTime, to: LocalDateTime): List<RecordFile> {
        val obj = rpc("mediaFileFind.factory.create").optLong("result")
        val out = ArrayList<RecordFile>()
        try {
            val cond = JSONObject()
                .put("Channel", channel)
                .put("StartTime", from.format(NVR_TIME))
                .put("EndTime", to.format(NVR_TIME))
                .put("Types", JSONArray().put("dav"))
            val found = try {
                rpc("mediaFileFind.findFile", JSONObject().put("condition", cond), obj)
                true
            } catch (e: RpcException) {
                // result=false means "nothing matches"; a permission problem must still surface.
                if (e.message.orEmpty().contains("Authority", ignoreCase = true)) throw e
                false
            }
            while (found) {
                val infos = rpc("mediaFileFind.findNextFile", JSONObject().put("count", 100), obj)
                    .optJSONObject("params")?.optJSONArray("infos") ?: break
                for (i in 0 until infos.length()) parseRecord(infos.getJSONObject(i))?.let(out::add)
                if (infos.length() < 100) break
            }
        } finally {
            runCatching { rpc("mediaFileFind.close", null, obj, 5000) }
            runCatching { rpc("mediaFileFind.destroy", null, obj, 5000) }
        }
        return out.sortedBy { it.start }
    }

    /**
     * Human / vehicle detections (SMD) from the recorder's smart-search index. Needs a permission
     * that restricted accounts may lack — then [RpcException] with "Authority" is thrown.
     */
    fun findSmartEvents(channel: Int, from: LocalDateTime, to: LocalDateTime): List<EventSegment> {
        val start = rpc("SmdDataFinder.startFind", JSONObject().put("Condition", JSONObject()
            .put("Channels", JSONArray().put(channel))
            .put("SmdType", JSONArray().put("smdTypeHuman").put("smdTypeVehicle").put("smdTypeHumanAndVehicle"))
            .put("StartTime", from.format(NVR_TIME))
            .put("EndTime", to.format(NVR_TIME))
            .put("Order", "ascOrder"))).optJSONObject("params") ?: return emptyList()
        val token = start.optLong("Token")
        val count = start.optInt("Count")
        val out = ArrayList<EventSegment>()
        try {
            var offset = 0
            while (offset < count) {
                val infos = rpc("SmdDataFinder.doFind", JSONObject().put("Token", token).put("Offset", offset).put("Count", 100))
                    .optJSONObject("params")?.optJSONArray("SmdInfo") ?: break
                if (infos.length() == 0) break
                for (i in 0 until infos.length()) {
                    val o = infos.getJSONObject(i)
                    val s = runCatching { LocalDateTime.parse(o.getString("StartTime"), NVR_TIME) }.getOrNull() ?: continue
                    val e = runCatching { LocalDateTime.parse(o.getString("EndTime"), NVR_TIME) }.getOrNull() ?: s.plusSeconds(1)
                    when (o.optString("Type")) {
                        "smdTypeHuman" -> out += EventSegment(s, e, EventType.HUMAN)
                        "smdTypeVehicle" -> out += EventSegment(s, e, EventType.VEHICLE)
                        "smdTypeHumanAndVehicle" -> { out += EventSegment(s, e, EventType.HUMAN); out += EventSegment(s, e, EventType.VEHICLE) }
                    }
                }
                offset += infos.length()
            }
        } finally {
            runCatching { rpc("SmdDataFinder.stopFind", JSONObject().put("Token", token), timeoutMs = 3000) }
        }
        return out
    }

    data class PtzCaps(val pan: Boolean, val tilt: Boolean, val zoom: Boolean)

    /** PTZ abilities of the camera on [channel] (0-based), or null when it has none. */
    fun ptzCaps(channel: Int): PtzCaps? = runCatching {
        val obj = rpc("ptz.factory.instance", JSONObject().put("channel", channel)).optLong("result")
        try {
            val c = rpc("ptz.getCurrentProtocolCaps", null, obj).optJSONObject("params")?.optJSONObject("caps") ?: return@runCatching null
            val pan = c.optBoolean("Pan")
            val tilt = if (c.has("Tile")) c.optBoolean("Tile") else c.optBoolean("Tilt", pan)
            val zoom = c.optBoolean("Zoom")
            if (pan || tilt || zoom) PtzCaps(pan, tilt, zoom) else null
        } finally {
            runCatching { rpc("ptz.destroy", null, obj, 3000) }
        }
    }.getOrNull()

    fun ptz(channel: Int, action: Int, stop: Boolean, speed: Int = 5) {
        val diagonal = action >= 0x20
        val packet = Dvrip.ptz(channel, action, if (diagonal) speed else 0, speed, stop)
        background.execute { runCatching { main?.send(packet) } }
    }

    /**
     * Two-way audio to the camera on [channel]: G.711A, 8 kHz. A recorder relays it to the camera
     * (TalkMode 1); a camera added directly talks itself (TalkMode 0). Throws when refused.
     */
    fun openTalk(channel: Int): TalkSession {
        val (s, cid) = openPassiveConnection()
        val direct = channelCount <= 1
        val ch = if (direct) 0 else channel
        val mode = if (direct) 0 else 1
        fun talk(state: Int) = "Method:GetParameterNames\r\nParameterName:Dahua.Device.Network.Talk.General\r\nChannel:$ch\r\n" +
            "EncodeFormat:2\r\nDepth:16\r\nFrequency:8000\r\nState:$state\r\nConnectionID:$cid\r\nTalkMode:$mode\r\nType:0\r\n\r\n"
        lateinit var session: TalkSession
        session = TalkSession(s) {
            talks.remove(session)
            textLater(talk(0))
            textLater(deletePassive(cid))
        }
        talks.add(session)
        try {
            text(talk(1))
        } catch (e: IOException) {
            session.close()
            throw TalkRefusedException(e.message ?: "refused")
        }
        session.start()
        val silence = ByteArray(320) { 0xd5.toByte() }
        repeat(3) { session.send(silence) }
        return session
    }

    /** Whether the camera on [channel] (0-based) has a siren for active deterrence. */
    fun hasSiren(channel: Int): Boolean = runCatching {
        rpc("CoaxialControlIO.getCaps", JSONObject().put("channel", channel)).optJSONObject("params")
            ?.optJSONObject("caps")?.optInt("SupportControlSpeaker") == 1
    }.getOrDefault(false)

    fun sirenOn(channel: Int): Boolean = runCatching {
        rpc("CoaxialControlIO.getStatus", JSONObject().put("channel", channel)).optJSONObject("params")
            ?.optJSONObject("status")?.optString("Speaker") == "On"
    }.getOrDefault(false)

    /** Active deterrence siren, exactly as DMSS switches it (IO 1 = on, 2 = off; Type 2 = speaker). */
    fun setSiren(channel: Int, on: Boolean) {
        rpc("CoaxialControlIO.control", JSONObject().put("channel", channel).put("info",
            JSONArray().put(JSONObject().put("IO", if (on) 1 else 2).put("TriggerMode", 2).put("Type", 2))))
    }

    private fun parseRecord(o: JSONObject): RecordFile? = runCatching {
        val flags = o.optJSONArray("Flags").toStrings()
        val events = o.optJSONArray("Events").toStrings()
        val kind = when {
            events.any { it.startsWith("SmartMotion") || it.startsWith("Cross") || it.contains("Detection") } -> RecordKind.SMART
            events.any { it.startsWith("Alarm") } -> RecordKind.ALARM
            events.contains("VideoMotion") || "Event" in flags -> RecordKind.MOTION
            "Manual" in flags -> RecordKind.MANUAL
            else -> RecordKind.CONTINUOUS
        }
        RecordFile(
            channel = o.getInt("Channel"),
            start = LocalDateTime.parse(o.getString("StartTime"), NVR_TIME),
            end = LocalDateTime.parse(o.getString("EndTime"), NVR_TIME),
            disk = o.getInt("Disk"),
            cluster = o.getLong("Cluster").toInt(),
            partition = o.optInt("Partition"),
            lengthBytes = o.optLong("Length"),
            kind = kind,
            events = events,
        )
    }.getOrNull()

    /** Starts live video of [channel] (0-based). Closing the returned stream stops it. */
    fun openLive(channel: Int, subStream: Boolean, listener: MediaListener): MediaStream {
        if (textMode) return openLiveText(channel, subStream, listener)
        val s = openSubConnection(channel, Dvrip.SUBCONN_LIVE)
        lateinit var stream: MediaStream
        stream = MediaStream(s, listener, "live-$host-$channel", liveData = true) {
            streams.remove(stream)
            synchronized(liveLock) {
                if (liveStreams[channel]?.first === stream) {
                    liveStreams.remove(channel)
                    sendMonitorLocked()
                }
            }
        }
        streams.add(stream)
        synchronized(liveLock) {
            liveStreams.put(channel, stream to (if (subStream) 1 else 0))?.first?.takeIf { it !== stream }?.close()
            sendMonitorLocked()
        }
        stream.start()
        return stream
    }

    private fun sendMonitorLocked() {
        if (closed) return
        val packet = Dvrip.monitor(liveStreams.mapValues { it.value.second }, channelCount)
        background.execute { runCatching { main?.send(packet) } }
    }

    private fun openLiveText(channel: Int, subStream: Boolean, listener: MediaListener): MediaStream {
        val stream = if (subStream) 1 else 0
        val (s, cid) = openPassiveConnection()
        lateinit var ms: MediaStream
        ms = MediaStream(s, listener, "live-$host-$channel", liveData = true) {
            streams.remove(ms)
            textLater("Method:GetParameterNames\r\nParameterName:Dahua.Device.Network.Monitor.General\r\nchannel:$channel\r\nstate:0\r\n" +
                "ConnectionID:$cid\r\nstream:$stream\r\nspecifictype:0\r\n\r\n")
            textLater(deletePassive(cid))
        }
        streams.add(ms)
        try {
            text("Method:GetParameterNames\r\nParameterName:Dahua.Device.Network.Monitor.General\r\nchannel:$channel\r\nstate:1\r\n" +
                "ConnectionID:$cid\r\nstream:$stream\r\n\r\n")
        } catch (e: Exception) {
            ms.close()
            throw e
        }
        ms.start()
        return ms
    }

    /**
     * Data connection the DMSS way: the recorder allocates a ConnectionID (AddObject Passive), the new
     * socket binds to it with AckSubChannel, then live/playback commands on the main socket refer to it.
     */
    private fun openPassiveConnection(): Pair<DvripSocket, Int> {
        if (!isConnected) throw IOException("Not connected")
        val cid = text("Method:AddObject\r\nParameterName:Dahua.Device.Network.ControlConnection.Passive\r\nConnectProtocol:0\r\n\r\n")["ConnectionID"]
            ?.toIntOrNull() ?: throw IOException("Recorder did not allocate a connection")
        val s = try {
            DvripSocket(host, port, readTimeoutMs = 8000)
        } catch (e: Exception) {
            textLater(deletePassive(cid))
            throw e
        }
        try {
            val (h, b) = Dvrip.text("TransactionID:0\r\nMethod:GetParameterNames\r\nParameterName:Dahua.Device.Network.ControlConnection.AckSubChannel\r\n" +
                "SessionID:$sessionId\r\nConnectionID:$cid\r\nEncrypt:0\r\n\r\n")
            s.send(h, b)
            while (true) {
                val p = s.read()
                if (p.cmd != Dvrip.CMD_TEXT) continue
                val kv = parseKv(String(p.body, Charsets.ISO_8859_1))
                if (!kv["ParameterName"].orEmpty().endsWith("AckSubChannel")) continue
                if (kv["FaultCode"] != "OK") throw IOException("AckSubChannel: ${kv["FaultCode"]}")
                break
            }
            s.readTimeoutMs = 15_000
            return s to cid
        } catch (e: Exception) {
            s.close()
            textLater(deletePassive(cid))
            throw e
        }
    }

    private fun deletePassive(cid: Int) =
        "Method:DeleteObject\r\nParameterName:Dahua.Device.Network.ControlConnection.Passive\r\nConnectionID:$cid\r\n\r\n"

    private fun openPlaybackText(file: RecordFile, from: LocalDateTime, to: LocalDateTime, listener: MediaListener, speed: Int): MediaStream {
        val (s, cid) = openPassiveConnection()
        val ch = file.channel + 1
        lateinit var ms: MediaStream
        ms = MediaStream(s, listener, "playback-$host-${file.channel}", liveData = false) {
            streams.remove(ms)
            textLater("Method:GetParameterNames\r\nParameterName:Dahua.Device.Network.PlayBack.Stop\r\nchannel:$ch\r\nConnectionID:$cid\r\n\r\n")
            textLater(deletePassive(cid))
        }
        streams.add(ms)
        fun t6(t: LocalDateTime) = "${t.year}&${t.monthValue}&${t.dayOfMonth}&${t.hour}&${t.minute}&${t.second}"
        try {
            text("Method:GetParameterNames\r\nParameterName:Dahua.Device.Network.PlayBack.General\r\nchannel:$ch\r\nConnectionID:$cid\r\n" +
                "StartTime:${t6(from)}\r\nEndTime:${t6(to)}\r\nDriveNo:${file.disk}\r\nClusterNo:${file.cluster}\r\n" +
                "Hint:${if (file.partition > 0) file.partition else 1}\r\nType:0\r\nIFrames:0\r\nIsTime:1\r\nOffLength:0\r\n" +
                "Direction:0\r\nRecordStream:0\r\n\r\n")
            // N× speed, as DMSS asks for its fast downloads (speed:4 verified = 4× real time).
            if (speed > 1) text("Method:GetParameterNames\r\nParameterName:Dahua.Device.Network.PlayBack.CtlSpeed\r\nchannel:$ch\r\n" +
                "ConnectionID:$cid\r\nspeed:$speed\r\nspeedMode:0\r\nposition:0\r\n\r\n")
        } catch (e: Exception) {
            ms.close()
            throw e
        }
        ms.start()
        return ms
    }

    /** Plays [file] from [from] to [to]; the stream reports [MediaListener.onEnd] when the range is done. */
    fun openPlayback(file: RecordFile, from: LocalDateTime, to: LocalDateTime, listener: MediaListener, speed: Int = 1): MediaStream {
        if (textMode) return openPlaybackText(file, from, to, listener, speed)
        val s = openSubConnection(file.channel, Dvrip.SUBCONN_PLAYBACK)
        lateinit var stream: MediaStream
        stream = MediaStream(s, listener, "playback-$host-${file.channel}", liveData = false) {
            streams.remove(stream)
            val stop = Dvrip.playbackStop(file.channel)
            if (!closed) background.execute { runCatching { main?.send(stop) } }
        }
        streams.add(stream)
        try {
            val m = main ?: throw IOException("Not connected")
            m.send(Dvrip.playback(file.channel, file.disk, file.cluster, from, to))
        } catch (e: Exception) {
            stream.close()
            throw e
        }
        stream.start()
        return stream
    }

    private fun openSubConnection(channel: Int, type: Int): DvripSocket {
        if (!isConnected) throw IOException("Not connected")
        val s = DvripSocket(host, port, readTimeoutMs = 8000)
        try {
            s.send(Dvrip.subConnect(sessionId, channel, type))
            val reply = s.read()
            if (reply.cmd != Dvrip.CMD_SUBCONN) throw IOException("Unexpected sub-connection reply 0x%02x".format(reply.cmd))
            s.readTimeoutMs = 15_000
            return s
        } catch (e: Exception) {
            s.close()
            throw e
        }
    }

    override fun close() {
        if (closed) return
        streams.forEach { it.close() }
        // let queued stop/cleanup commands go out before logging out
        runCatching { background.submit {}.get(2, TimeUnit.SECONDS) }
        runCatching { if (main != null) rpc("global.logout", timeoutMs = 2000) }
        closed = true
        main?.close()
        background.shutdown()
        pending.values.forEach { it.completeExceptionally(IOException("Closed")) }
        pendingText.values.forEach { it.completeExceptionally(IOException("Closed")) }
    }

    companion object {
        fun parseKv(text: String): Map<String, String> = text.split("\r\n").mapNotNull { line ->
            val i = line.indexOf(':')
            if (i <= 0) null else line.substring(0, i).trim() to line.substring(i + 1).trim()
        }.toMap()

        fun loginError(code: Int): String = when (code) {
            0x0100 -> "Wrong password"
            0x0101 -> "Unknown user"
            0x0104 -> "Account locked"
            0x0111 -> "Device not initialised"
            0x0303 -> "User already logged in"
            else -> "Login rejected (0x%04x)".format(code)
        }

        private fun JSONArray?.toStrings(): List<String> = if (this == null) emptyList() else List(length()) { optString(it) }
    }
}

interface MediaListener {
    fun onFrame(frame: DhavFrame)
    fun onEnd() {}
    /** The stream stopped on its own (network error, recorder closed it). Not called after [MediaStream.close]. */
    fun onClosed(error: Throwable?) {}
    /** A damaged frame was dropped (it failed the DHAV trailer check). */
    fun onCorrupt() {}
}

/** Reads one sub-connection and demuxes DHAV frames on a background thread. */
class MediaStream internal constructor(
    private val socket: DvripSocket,
    private val listener: MediaListener,
    private val name: String,
    private val liveData: Boolean,
    private val onClose: () -> Unit,
) : Closeable {
    private val parser = DhavParser()
    @Volatile private var closed = false
    @Volatile var bytesReceived = 0L; private set
    val corruptFrames: Long get() = parser.corruptFrames

    internal fun start() {
        thread(name = name, isDaemon = true) { run() }
    }

    private fun run() {
        var error: Throwable? = null
        var ended = false
        try {
            while (!closed) {
                val p = socket.read()
                val cmd = p.cmd
                if (cmd != Dvrip.CMD_LIVE_DATA && cmd != Dvrip.CMD_PLAYBACK_DATA) continue
                if (p.body.isEmpty()) {
                    // An empty playback packet marks the end of the requested range (both protocol flavours).
                    if (!liveData && cmd == Dvrip.CMD_PLAYBACK_DATA) { ended = true; break }
                    continue
                }
                bytesReceived += p.body.size
                val bad = parser.corruptFrames
                parser.feed(p.body) { listener.onFrame(it) }
                if (parser.corruptFrames != bad) listener.onCorrupt()
            }
        } catch (e: Throwable) {
            if (!closed) error = e
        }
        val wasClosed = closed
        close()
        if (!wasClosed) {
            if (ended) listener.onEnd() else listener.onClosed(error)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        socket.close()
        runCatching { onClose() }
    }
}
