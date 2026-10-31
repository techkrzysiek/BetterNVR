package pl.techkrzysiek.betternvr.media

/**
 * When a frame should reach the screen / speaker: its stream time (the DHAV 16-bit ms counter) on a wall clock,
 * plus an adaptive jitter buffer shared by picture and sound, so both stay in sync.
 *
 * Recorders do not send evenly: over a slow uplink a 4K keyframe takes a second, and the frames behind it come
 * in a burst (measured on an NVR4108 over a VPN: up to 1.9 s late live, 0.75× real time in playback). Showing
 * frames as they arrive made the picture stop and then race. Instead a late frame raises the buffer by its
 * lateness (one pause, then smooth); while frames keep arriving early the buffer shrinks again.
 */
class MediaClock(live: Boolean) {
    private val minDelayNs = if (live) 150 * MS else 300 * MS
    private val maxDelayNs = if (live) 3000 * MS else 4000 * MS
    private val shrinkWindowNs = if (live) 10_000 * MS else 20_000 * MS

    /** Playback speed factor (stream time runs N× faster than the wall clock). */
    @Volatile var speed = 1

    private var delayNs = if (live) 300 * MS else 500 * MS
    private var anchored = false
    private var anchorMs = 0L
    private var anchorNs = 0L
    private var refMs16 = -1
    private var refMs = 0L
    private var minSlackNs = Long.MAX_VALUE
    private var windowStartNs = 0L

    /** Current buffer, for the network side (how much to keep ahead). */
    val delayMs: Long get() = synchronized(this) { delayNs / MS }

    /** Start over at the next frame (seek, new stream, resume after pause); the learned buffer is kept. */
    @Synchronized
    fun reset() {
        anchored = false
        refMs16 = -1
    }

    /** Wall-clock time (System.nanoTime) at which the frame with stream time [ms16] is due. */
    @Synchronized
    fun dueNs(ms16: Int): Long {
        val now = System.nanoTime()
        val ms = unwrap(ms16)
        if (!anchored) {
            anchored = true
            anchorMs = ms; anchorNs = now
            windowStartNs = now; minSlackNs = Long.MAX_VALUE
        }
        var due = anchorNs + (ms - anchorMs) * MS / speed.coerceAtLeast(1) + delayNs
        val slack = due - now
        when {
            // timestamps jumped (next file, camera clock change): start over from this frame
            slack > delayNs + JUMP_NS || slack < -JUMP_NS -> {
                anchorMs = ms; anchorNs = now
                due = now + delayNs
            }
            // late: data came in later than the buffer covers – grow it by the lateness, never fast-forward
            slack < -LATE_TOLERANCE_NS -> {
                delayNs = (delayNs - slack + MARGIN_NS).coerceAtMost(maxDelayNs)
                due = now + MARGIN_NS
                windowStartNs = now; minSlackNs = Long.MAX_VALUE
            }
            else -> {
                if (slack < minSlackNs) minSlackNs = slack
                // everything early for a while: give some of the buffer back (lower live delay)
                if (now - windowStartNs > shrinkWindowNs) {
                    if (minSlackNs > KEEP_SLACK_NS && delayNs > minDelayNs) {
                        delayNs = (delayNs - minOf(minSlackNs - KEEP_SLACK_NS, SHRINK_STEP_NS)).coerceAtLeast(minDelayNs)
                    }
                    windowStartNs = now; minSlackNs = Long.MAX_VALUE
                }
            }
        }
        return due
    }

    /** 16-bit ms counter → continuous ms; audio is often a little behind the video, so small steps back are fine. */
    private fun unwrap(ms16: Int): Long {
        if (refMs16 < 0) { refMs16 = ms16; refMs = ms16.toLong(); return refMs }
        val d = Math.floorMod(ms16 - refMs16 + 32768, 65536) - 32768
        val ms = refMs + d
        if (d > 0) { refMs16 = ms16; refMs = ms }
        return ms
    }

    private companion object {
        const val MS = 1_000_000L
        const val LATE_TOLERANCE_NS = 30 * MS
        const val MARGIN_NS = 80 * MS
        const val KEEP_SLACK_NS = 250 * MS
        const val SHRINK_STEP_NS = 100 * MS
        const val JUMP_NS = 5_000 * MS
    }
}
