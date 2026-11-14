package pl.techkrzysiek.betternvr.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import pl.techkrzysiek.betternvr.proto.LoginException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import pl.techkrzysiek.betternvr.proto.DahuaClient
import pl.techkrzysiek.betternvr.proto.DeviceInfo
import pl.techkrzysiek.betternvr.proto.Endpoint
import java.util.UUID

data class Device(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val host: String,
    val port: Int = 37777,
    val user: String,
    val password: String,
    /** Other addresses of the same recorder ("host" or "host:port"), tried after [host] – e.g. a VPN or public one. */
    val altHosts: List<String> = emptyList(),
) {
    /** Every address in order of preference; an entry without its own port uses [port]. */
    val endpoints: List<Endpoint>
        get() = (listOf(host) + altHosts).mapNotNull { Endpoint.parse(it, port) }.distinct().ifEmpty { listOf(Endpoint(host.trim(), port)) }

    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("host", host).put("port", port)
        .put("user", user).put("password", password)
        .apply { if (altHosts.isNotEmpty()) put("alt", JSONArray(altHosts)) }

    companion object {
        fun fromJson(o: JSONObject) = Device(
            id = o.getString("id"), name = o.optString("name"), host = o.getString("host"), port = o.optInt("port", 37777),
            user = o.optString("user"), password = o.optString("password"),
            altHosts = o.optJSONArray("alt")?.let { a -> List(a.length()) { a.optString(it) }.filter { it.isNotBlank() } }.orEmpty(),
        )
    }
}

/** A camera slot: channel [channel] (0-based) of device [deviceId]. */
data class ChannelRef(val deviceId: String, val channel: Int) {
    fun toJson(): JSONObject = JSONObject().put("d", deviceId).put("c", channel)

    companion object {
        fun fromJson(o: JSONObject) = ChannelRef(o.getString("d"), o.getInt("c"))
    }
}

/** Devices and UI preferences, kept in the app's private storage (never leaves the phone). */
class DeviceStore(context: Context) {
    private val prefs = context.getSharedPreferences("betternvr", Context.MODE_PRIVATE)
    private val _devices = MutableStateFlow(load())
    val devices: StateFlow<List<Device>> = _devices.asStateFlow()

    private fun load(): List<Device> = runCatching {
        val a = JSONArray(prefs.getString("devices", "[]"))
        List(a.length()) { Device.fromJson(a.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    fun device(id: String): Device? = _devices.value.firstOrNull { it.id == id }

    fun save(d: Device) {
        _devices.update { list -> if (list.any { it.id == d.id }) list.map { if (it.id == d.id) d else it } else list + d }
        persist()
    }

    /** Moves a recorder up (delta < 0) or down in the list. */
    fun move(id: String, delta: Int) {
        _devices.update { list ->
            val i = list.indexOfFirst { it.id == id }
            val j = i + delta
            if (i < 0 || j !in list.indices) list else list.toMutableList().apply { add(j, removeAt(i)) }
        }
        persist()
    }

    fun moveTo(id: String, index: Int) {
        _devices.update { list ->
            val i = list.indexOfFirst { it.id == id }
            if (i < 0 || index !in list.indices || i == index) list else list.toMutableList().apply { add(index, removeAt(i)) }
        }
        persist()
    }

    fun delete(id: String) {
        _devices.update { list -> list.filterNot { it.id == id } }
        persist()
    }

    private fun persist() {
        prefs.edit().putString("devices", JSONArray(_devices.value.map { it.toJson() }).toString()).apply()
    }

}

/**
 * One logged-in session per recorder, shared by live view and playback. A recorder with several addresses is
 * reached on the first one that answers; while that is not its first address, [watch] keeps checking whether a
 * preferred one works again and moves the session there.
 */
class DeviceManager(private val store: DeviceStore) {
    private val clients = HashMap<String, Pair<Device, DahuaClient>>()
    private val locks = HashMap<String, Mutex>()
    private val _infos = MutableStateFlow<Map<String, DeviceInfo>>(emptyMap())
    val infos: StateFlow<Map<String, DeviceInfo>> = _infos.asStateFlow()
    private val _errors = MutableStateFlow<Map<String, String>>(emptyMap())
    val errors: StateFlow<Map<String, String>> = _errors.asStateFlow()
    private val _via = MutableStateFlow<Map<String, Endpoint>>(emptyMap())
    /** The address each connected recorder answered on (matters for recorders with several addresses). */
    val via: StateFlow<Map<String, Endpoint>> = _via.asStateFlow()
    private val _moved = MutableSharedFlow<String>(extraBufferCapacity = 16)
    /** A recorder's session moved to a preferred address (device id): players on it restart there. */
    val moved: SharedFlow<String> = _moved.asSharedFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val watchers = HashMap<String, Job>()
    private val retiring = ConcurrentHashMap.newKeySet<DahuaClient>()
    private val network = MutableStateFlow(0)

    private val _noCamera = MutableStateFlow<Set<ChannelRef>>(emptySet())
    /** Channels the recorder reported as empty, so the grid can push them to the end. */
    val noCamera: StateFlow<Set<ChannelRef>> = _noCamera.asStateFlow()

    fun markNoCamera(ref: ChannelRef) = _noCamera.update { it + ref }

    /** The phone joined or lost a network (Wi-Fi, mobile data, VPN): sessions on a fallback address check at once. */
    fun networkChanged() = network.update { it + 1 }

    private fun lock(id: String) = synchronized(locks) { locks.getOrPut(id) { Mutex() } }

    suspend fun client(deviceId: String): DahuaClient {
        val device = store.device(deviceId) ?: throw IllegalStateException("Unknown device")
        return lock(deviceId).withLock {
            synchronized(clients) { clients[deviceId] }?.let { (d, c) ->
                if (d == device && c.isConnected) return@withLock c
            }
            val old = synchronized(clients) { clients.remove(deviceId) }
            withContext(Dispatchers.IO) {
                old?.second?.close()
                val c = DahuaClient(device.endpoints, device.user, device.password)
                try {
                    c.connect()
                    val info = c.deviceInfo()
                    _infos.update { it + (deviceId to info) }
                    _via.update { it + (deviceId to c.endpoint) }
                    _errors.update { it - deviceId }
                } catch (e: Exception) {
                    c.close()
                    _errors.update { it + (deviceId to (e.message ?: e.javaClass.simpleName)) }
                    throw e
                }
                synchronized(clients) { clients[deviceId] = device to c }
                watch(deviceId, device, c)
                c
            }
        }
    }

    /**
     * While [current] runs on a later address of [device] than its first, tries the earlier ones every [PROBE_MS]
     * and soon after a network change – e.g. the home network again after the VPN. Only the same recorder (same
     * login realm) is logged in to, so another device on that address never sees a login attempt. On success new
     * work goes to the new session, players move over ([moved]) and the old session closes once nothing uses it
     * (a download or a clip being recorded finishes where it started). Runs only while the app holds sessions
     * (they are closed when it goes to the background).
     */
    private fun watch(deviceId: String, device: Device, current: DahuaClient) {
        synchronized(watchers) { watchers.remove(deviceId)?.cancel() }
        val all = device.endpoints
        val rank = all.indexOf(current.endpoint)
        val realm = current.realm
        if (rank <= 0 || realm == null) return
        val job = scope.launch {
            var seen = network.value
            while (isActive) {
                val nudged = withTimeoutOrNull(PROBE_MS) { network.first { it != seen } } != null
                seen = network.value
                if (nudged) delay(SETTLE_MS) // let routes and DNS settle after the change
                if (synchronized(clients) { clients[deviceId]?.second } !== current || !current.isConnected) return@launch
                val better = DahuaClient(all.subList(0, rank), device.user, device.password, expectRealm = realm)
                val info = try {
                    better.connect()
                    better.deviceInfo()
                } catch (e: CancellationException) {
                    better.close()
                    throw e
                } catch (e: LoginException) {
                    better.close()
                    return@launch // the same recorder refused the login: never repeat that (accounts get locked)
                } catch (e: Exception) {
                    better.close()
                    continue // not reachable yet
                }
                val swapped = lock(deviceId).withLock {
                    synchronized(clients) {
                        (clients[deviceId]?.second === current).also { if (it) clients[deviceId] = device to better }
                    }
                }
                if (!swapped) {
                    better.close()
                    return@launch
                }
                _infos.update { it + (deviceId to info) }
                _via.update { it + (deviceId to better.endpoint) }
                _moved.tryEmit(deviceId)
                retire(current)
                watch(deviceId, device, better) // replaces (cancels) this watcher; there may be a still better address
                return@launch
            }
        }
        synchronized(watchers) { watchers[deviceId] = job }
    }

    /** Closes a replaced session as soon as nothing uses it any more. */
    private fun retire(old: DahuaClient) {
        retiring += old
        scope.launch {
            while (!old.isIdle && old.isConnected) delay(1000)
            retiring -= old
            old.close()
        }
    }

    /** Logs in once just to verify settings, without keeping the session; also tells which address answered. */
    suspend fun test(device: Device): Pair<DeviceInfo, Endpoint> = withContext(Dispatchers.IO) {
        DahuaClient(device.endpoints, device.user, device.password).use { c ->
            c.connect()
            c.deviceInfo() to c.endpoint
        }
    }

    fun disconnect(deviceId: String) {
        synchronized(watchers) { watchers.remove(deviceId)?.cancel() }
        val c = synchronized(clients) { clients.remove(deviceId) } ?: return
        Thread { c.second.close() }.start()
    }

    fun disconnectAll() {
        synchronized(watchers) { watchers.values.forEach { it.cancel() }; watchers.clear() }
        val all = synchronized(clients) { clients.values.map { it.second }.also { clients.clear() } } + retiring.toList()
        retiring.clear()
        if (all.isNotEmpty()) Thread { all.forEach { it.close() } }.start()
    }

    fun channelName(ref: ChannelRef, fallback: (Int) -> String): String =
        _infos.value[ref.deviceId]?.channelNames?.getOrNull(ref.channel)?.takeIf { it.isNotBlank() } ?: fallback(ref.channel + 1)

    private companion object {
        /** How often a session on a fallback address checks the preferred ones. */
        const val PROBE_MS = 30_000L
        const val SETTLE_MS = 2_000L
    }
}
