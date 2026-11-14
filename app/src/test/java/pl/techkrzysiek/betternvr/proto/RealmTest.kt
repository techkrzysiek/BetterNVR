package pl.techkrzysiek.betternvr.proto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** A recorder that answers the login challenge with [realm] and refuses every login (no real password needed). */
private class FakeRecorder(val realm: String) : AutoCloseable {
    val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    val logins = AtomicInteger()
    val endpoint get() = Endpoint("127.0.0.1", server.localPort)

    init {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val s = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) {
                    s.use {
                        val input = DataInputStream(s.getInputStream())
                        var n = 0
                        while (true) {
                            val h = ByteArray(Dvrip.HEADER_SIZE)
                            if (runCatching { input.readFully(h) }.isFailure) break
                            input.readFully(ByteArray(Dvrip.le32(h, 4)))
                            val reply = ByteArray(Dvrip.HEADER_SIZE)
                            reply[0] = 0xb0.toByte()
                            if (n++ == 0) {
                                val body = "Realm:$realm\r\nRandom:123456\r\n\r\n".toByteArray()
                                reply[4] = body.size.toByte()
                                s.getOutputStream().write(reply + body)
                            } else {
                                logins.incrementAndGet()
                                reply[8] = 0x01 // result 0x0100: refused
                                s.getOutputStream().write(reply)
                            }
                        }
                    }
                }
            }
        }
    }

    override fun close() = server.close()
}

class RealmTest {
    @Test
    fun anotherDeviceOnAnAddressIsSkippedWithoutALogin() {
        FakeRecorder("Login to OTHER").use { stranger ->
            FakeRecorder("Login to MINE").use { mine ->
                val c = DahuaClient(listOf(stranger.endpoint, mine.endpoint), "admin", "x", expectRealm = "Login to MINE")
                try {
                    c.connect()
                    fail()
                } catch (e: LoginException) {
                    // reached the right recorder (which refuses this password), never tried to log in to the other one
                }
                Thread.sleep(200)
                assertEquals(0, stranger.logins.get())
                assertEquals(1, mine.logins.get())
            }
        }
    }

    @Test
    fun onlyStrangersMeansUnreachable() {
        FakeRecorder("Login to OTHER").use { stranger ->
            val c = DahuaClient(listOf(stranger.endpoint, Endpoint("127.0.0.1", ServerSocket(0).use { it.localPort })), "admin", "x", expectRealm = "Login to MINE")
            try {
                c.connect()
                fail()
            } catch (e: UnreachableException) {
                assertTrue(e.message!!, e.message!!.contains("another device"))
            }
            assertEquals(0, stranger.logins.get())
        }
    }
}
