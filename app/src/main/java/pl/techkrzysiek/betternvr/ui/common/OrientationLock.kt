package pl.techkrzysiek.betternvr.ui.common

import android.app.Activity
import android.content.pm.ActivityInfo
import android.provider.Settings
import android.view.OrientationEventListener

/** Turns the screen to landscape and back for the full-screen button. */
class OrientationLock(private val activity: Activity) : OrientationEventListener(activity) {
    private var wantLandscape: Boolean? = null

    fun request(landscape: Boolean) {
        wantLandscape = landscape
        activity.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        enable()
    }

    fun release() {
        wantLandscape = null
        disable()
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    /** Once the phone is physically held the requested way, rotation follows the sensor again. */
    override fun onOrientationChanged(degrees: Int) {
        val want = wantLandscape ?: return
        if (degrees == ORIENTATION_UNKNOWN) return
        val held = when {
            degrees in 60..120 || degrees in 240..300 -> true
            degrees <= 20 || degrees >= 340 -> false
            else -> return
        }
        if (held != want) return
        // With auto-rotate switched off the system would turn the screen right back: keep the lock.
        if (Settings.System.getInt(activity.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0) != 1) return
        release()
    }
}
