package pl.techkrzysiek.betternvr.proto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket

class EndpointTest {
    @Test
    fun parsesAddresses() {
        assertEquals(Endpoint("10.0.0.5", 37777), Endpoint.parse(" 10.0.0.5 "))
        assertEquals(Endpoint("nvr.example.com", 40000), Endpoint.parse("nvr.example.com:40000"))
        assertEquals(Endpoint("10.0.0.5", 1234), Endpoint.parse("10.0.0.5", 1234))
        assertEquals(Endpoint("fd00::5", 37777), Endpoint.parse("fd00::5"))
        assertEquals(Endpoint("fd00::5", 8000), Endpoint.parse("[fd00::5]:8000"))
        assertEquals(Endpoint("fd00::5", 37777), Endpoint.parse("[fd00::5]"))
        for (bad in listOf("", "  ", "host:", "host:abc", "host:70000", ":80", "[fd00::5", "[fd00::5]x", "a b")) assertNull(bad, Endpoint.parse(bad))
    }

    @Test
    fun labels() {
        assertEquals("10.0.0.5", Endpoint("10.0.0.5", 37777).label())
        assertEquals("10.0.0.5:8000", Endpoint("10.0.0.5", 8000).label())
        assertEquals("[fd00::5]:8000", Endpoint("fd00::5", 8000).label())
    }

    private fun listening() = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    private fun closedPort() = ServerSocket(0, 50, InetAddress.getLoopbackAddress()).use { it.localPort }

    @Test
    fun firstAnsweringAddressWins() {
        listening().use { a ->
            listening().use { b ->
                val (s, used) = DvripSocket.connectFirst(listOf(Endpoint("127.0.0.1", a.localPort), Endpoint("127.0.0.1", b.localPort)))
                s.close()
                assertEquals(a.localPort, used.port) // both answer: the earlier one is preferred
            }
        }
    }

    @Test
    fun refusedAddressPassesOnAtOnce() {
        listening().use { b ->
            val t = System.currentTimeMillis()
            val (s, used) = DvripSocket.connectFirst(listOf(Endpoint("127.0.0.1", closedPort()), Endpoint("127.0.0.1", b.localPort)))
            s.close()
            assertEquals(b.localPort, used.port)
            assertTrue(System.currentTimeMillis() - t < DvripSocket.STAGGER_MS)
        }
    }

    /** 192.0.2.1 (TEST-NET-1) never answers: the next address must not wait for its timeout. */
    @Test
    fun silentAddressCostsOnlyTheHeadStart() {
        listening().use { b ->
            val t = System.currentTimeMillis()
            val (s, used) = DvripSocket.connectFirst(listOf(Endpoint("192.0.2.1", 37777), Endpoint("127.0.0.1", b.localPort)), connectTimeoutMs = 6000)
            s.close()
            assertEquals(b.localPort, used.port)
            assertTrue(System.currentTimeMillis() - t < 3000)
        }
    }

    @Test
    fun reportsEveryAddressWhenNoneAnswers() {
        val a = closedPort()
        val b = closedPort()
        try {
            DvripSocket.connectFirst(listOf(Endpoint("127.0.0.1", a), Endpoint("127.0.0.1", b)))
            fail()
        } catch (e: UnreachableException) {
            assertEquals(listOf(a, b), e.failures.map { it.first.port })
            assertTrue(e.message!!, e.message!!.contains("127.0.0.1:$a") && e.message!!.contains("127.0.0.1:$b"))
        }
    }
}
