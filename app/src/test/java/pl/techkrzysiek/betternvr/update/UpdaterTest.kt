package pl.techkrzysiek.betternvr.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterTest {
    @Test
    fun comparesVersionsNumberByNumber() {
        assertTrue(Updater.isNewer("0.1.6", "0.1.5"))
        assertTrue(Updater.isNewer("0.2.0", "0.1.10"))
        assertTrue(Updater.isNewer("1.0", "0.9.9"))
        assertFalse(Updater.isNewer("0.1.5", "0.1.5"))
        assertFalse(Updater.isNewer("0.1.4", "0.1.5"))
        assertFalse(Updater.isNewer("0.1.5", "0.1.5.0"))
    }
}
