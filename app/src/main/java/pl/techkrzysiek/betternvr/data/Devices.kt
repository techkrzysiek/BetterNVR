package pl.techkrzysiek.betternvr.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
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
import java.util.UUID

data class Device(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val host: String,
    val port: Int = 37777,
    val user: String,
    val password: String,
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("host", host).put("port", port)
        .put("user", user).put("password", password)

    companion object {
        fun fromJson(o: JSONObject) = Device(
            id = o.getString("id"), name = o.optString("name"), host = o.getString("host"), port = o.optInt("port", 37777),
            user = o.optString("user"), password = o.optString("password"),
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

/** One logged-in session per recorder, shared by live view and playback. */
class DeviceManager(private val store: DeviceStore) {
    private val clients = HashMap<String, Pair<Device, DahuaClient>>()
    private val locks = HashMap<String, Mutex>()
    private val _infos = MutableStateFlow<Map<String, DeviceInfo>>(emptyMap())
    val infos: StateFlow<Map<String, DeviceInfo>> = _infos.asStateFlow()
    private val _errors = MutableStateFlow<Map<String, String>>(emptyMap())
    val errors: StateFlow<Map<String, String>> = _errors.asStateFlow()

    private val _noCamera = MutableStateFlow<Set<ChannelRef>>(emptySet())
    /** Channels the recorder reported as empty, so the grid can push them to the end. */
    val noCamera: StateFlow<Set<ChannelRef>> = _noCamera.asStateFlow()

    fun markNoCamera(ref: ChannelRef) = _noCamera.update { it + ref }

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
                val c = DahuaClient(device.host, device.port, device.user, device.password)
                try {
                    c.connect()
                    val info = c.deviceInfo()
                    _infos.update { it + (deviceId to info) }
                    _errors.update { it - deviceId }
                } catch (e: Exception) {
                    c.close()
                    _errors.update { it + (deviceId to (e.message ?: e.javaClass.simpleName)) }
                    throw e
                }
                synchronized(clients) { clients[deviceId] = device to c }
                c
            }
        }
    }

    /** Logs in once just to verify settings, without keeping the session. */
    suspend fun test(device: Device): DeviceInfo = withContext(Dispatchers.IO) {
        DahuaClient(device.host, device.port, device.user, device.password).use { c ->
            c.connect()
            c.deviceInfo()
        }
    }

    fun disconnect(deviceId: String) {
        val c = synchronized(clients) { clients.remove(deviceId) } ?: return
        Thread { c.second.close() }.start()
    }

    fun disconnectAll() {
        val all = synchronized(clients) { clients.values.toList().also { clients.clear() } }
        if (all.isNotEmpty()) Thread { all.forEach { it.second.close() } }.start()
    }

    fun channelName(ref: ChannelRef, fallback: (Int) -> String): String =
        _infos.value[ref.deviceId]?.channelNames?.getOrNull(ref.channel)?.takeIf { it.isNotBlank() } ?: fallback(ref.channel + 1)
}
