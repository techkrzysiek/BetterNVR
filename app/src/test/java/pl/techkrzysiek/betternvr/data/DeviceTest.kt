package pl.techkrzysiek.betternvr.data

import org.junit.Assert.assertEquals
import org.json.JSONObject
import org.junit.Test
import pl.techkrzysiek.betternvr.proto.Endpoint

class DeviceTest {
    @Test
    fun endpointsInOrderWithOwnOrDevicePort() {
        val d = Device(name = "NVR", host = "192.168.1.10", port = 37777, user = "u", password = "p", altHosts = listOf("10.8.0.2", "nvr.example.com:40000", "10.8.0.2", "bad host"))
        assertEquals(
            listOf(Endpoint("192.168.1.10", 37777), Endpoint("10.8.0.2", 37777), Endpoint("nvr.example.com", 40000)),
            d.endpoints,
        )
    }

    @Test
    fun jsonKeepsAlternativeAddresses() {
        val d = Device(name = "NVR", host = "192.168.1.10", port = 37778, user = "u", password = "p", altHosts = listOf("10.8.0.2", "vpn:1"))
        assertEquals(d, Device.fromJson(JSONObject(d.toJson().toString())))
        // saved by older versions: no "alt"
        val old = Device.fromJson(JSONObject("""{"id":"x","name":"n","host":"h","port":37777,"user":"u","password":"p"}"""))
        assertEquals(emptyList<String>(), old.altHosts)
    }
}
