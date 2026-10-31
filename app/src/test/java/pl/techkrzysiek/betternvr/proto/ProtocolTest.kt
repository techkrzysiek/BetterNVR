package pl.techkrzysiek.betternvr.proto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.time.LocalDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ProtocolTest {
    @Test
    fun dhavTimeRoundTrip() {
        val t = LocalDateTime.of(2026, 10, 30, 14, 0, 59)
        assertEquals(t, DhavTime.unpack(DhavTime.pack(t)))
    }

    @Test
    fun loginPacketsMatchCapturedLayout() {
        val realm = Dvrip.realmRequest()
        assertEquals("a0010000" + "00".repeat(20) + "050201010000a1aa", realm.joinToString("") { "%02x".format(it) })
        val (h, b) = Dvrip.loginRequest("u", "p", "Login to x", "123")
        assertEquals(0xa0, h[0].toInt() and 0xff)
        assertEquals(5, h[1].toInt())
        assertEquals(b.size, Dvrip.le32(h, 4))
        assertTrue(String(b).startsWith("u&&"))
        assertEquals(3 + 64, b.size)
    }

    @Test
    fun playbackRequestLayout() {
        val p = Dvrip.playback(0, 2, 34917, LocalDateTime.of(2026, 10, 30, 21, 0, 1), LocalDateTime.of(2026, 10, 30, 22, 0, 1))
        val hex = p.joinToString("") { "%02x".format(it) }
        // byte-for-byte the request that played correctly on both test recorders
        assertEquals("c20000000000000001ea070a1e1500010265880000000000000000010160bd6a", hex)
    }

    @Test
    fun parsesCapturedPlayback() {
        // A few seconds captured from a real recorder; camera footage, so it is not part of the public repository.
        val sample = javaClass.getResourceAsStream("/playback_packets.bin")
        assumeTrue("no captured sample in src/test/resources", sample != null)
        val raw = sample!!.use { it.readBytes() }
        val parser = DhavParser()
        var video = 0
        var keys = 0
        var audio = 0
        var i = 0
        var lastTime: LocalDateTime? = null
        while (i + 32 <= raw.size) {
            val len = Dvrip.le32(raw, i + 4)
            if ((raw[i].toInt() and 0xff) == Dvrip.CMD_PLAYBACK_DATA) {
                parser.feed(raw, i + 32, len) { f ->
                    if (f.isVideo) { video++; if (f.isKey) keys++; assertTrue(f.videoCodec != null) }
                    if (f.isAudio) audio++
                    lastTime = f.time ?: lastTime
                }
            }
            i += 32 + len
        }
        assertTrue("video frames: $video", video > 20)
        assertTrue(keys >= 1)
        assertEquals(0L, parser.corruptFrames)
        assertTrue(lastTime != null)
    }

    /** Talks to a real recorder: DH_HOST=… DH_USER=… DH_PASS=… ./gradlew testDebugUnitTest */
    @Test
    fun liveRecorder() {
        val host = System.getenv("DH_HOST")
        assumeTrue(host != null)
        DahuaClient(host!!, 37777, System.getenv("DH_USER") ?: "admin", System.getenv("DH_PASS") ?: "").use { c ->
            c.connect()
            val info = c.deviceInfo()
            println("device ${info.type} ${info.version} channels=${info.channelCount} names=${info.channelNames.take(4)} proto=${c.protocolVersion}")
            assertTrue(info.channelCount > 0)
            val now = c.currentTime()!!
            println("recorder time $now")

            val frames = AtomicInteger()
            val keyLatch = CountDownLatch(1)
            val ch = System.getenv("DH_CH")?.toInt() ?: 0
            val live = c.openLive(ch, true, object : MediaListener {
                override fun onFrame(frame: DhavFrame) { frames.incrementAndGet(); if (frame.isKey) keyLatch.countDown() }
            })
            assertTrue("live keyframe", keyLatch.await(8, TimeUnit.SECONDS))
            Thread.sleep(1500)
            live.close()
            println("live frames ${frames.get()} corrupt=${live.corruptFrames}")

            val records = c.findRecords(ch, now.minusHours(3), now)
            println("records: ${records.size}, kinds=${records.groupingBy { it.kind }.eachCount()}")
            assertTrue(records.isNotEmpty())
            val file = records.first { it.end.isAfter(it.start.plusSeconds(30)) }
            val at = file.start.plusSeconds(20)
            val first = arrayOfNulls<LocalDateTime>(1)
            val got = CountDownLatch(1)
            val t0 = System.nanoTime()
            val pb = c.openPlayback(file, at, file.end, object : MediaListener {
                override fun onFrame(frame: DhavFrame) {
                    if (frame.isKey && first[0] == null) { first[0] = frame.time; got.countDown() }
                }
            })
            assertTrue("playback keyframe", got.await(8, TimeUnit.SECONDS))
            println("playback first keyframe ${first[0]} (asked $at) after ${(System.nanoTime() - t0) / 1_000_000} ms")
            pb.close()
            // playback starts at the keyframe at or just before the requested moment
            val lead = java.time.Duration.between(first[0], at).seconds
            assertTrue("keyframe $lead s before requested time", lead in 0..5)
        }
    }
}
