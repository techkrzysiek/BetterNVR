package pl.techkrzysiek.betternvr

import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.flow.MutableStateFlow
import pl.techkrzysiek.betternvr.data.ChannelRef
import pl.techkrzysiek.betternvr.data.Device
import pl.techkrzysiek.betternvr.data.Language
import pl.techkrzysiek.betternvr.ui.devices.DeviceQr
import pl.techkrzysiek.betternvr.ui.BetterNvrApp
import pl.techkrzysiek.betternvr.ui.common.OrientationLock
import pl.techkrzysiek.betternvr.ui.theme.BetterNvrTheme

class MainActivity : ComponentActivity() {
    /** Camera to open, asked for by a home-screen widget. */
    val openCamera = MutableStateFlow<ChannelRef?>(null)
    val inPip = MutableStateFlow(false)
    /** Recorder from a QR link (betternvr://device?…) waiting for the user to confirm it. */
    val importDevice = MutableStateFlow<Device?>(null)
    val pipSupported by lazy { packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE) }

    /** Full-screen orientation, kept while moving from camera to camera; released once no camera is shown. */
    val orientationLock by lazy { OrientationLock(this) }
    private var cameraScreens = 0
    private val handler = Handler(Looper.getMainLooper())
    private val releaseOrientation = Runnable { if (cameraScreens == 0) orientationLock.release() }

    fun cameraScreenShown() {
        cameraScreens++
        handler.removeCallbacks(releaseOrientation)
    }

    fun cameraScreenGone() {
        cameraScreens = maxOf(0, cameraScreens - 1)
        // the next camera's screen may appear a moment after this one goes
        if (cameraScreens == 0) handler.postDelayed(releaseOrientation, 500)
    }

    /**
     * Status and navigation bars hidden while a full-screen view is shown. Counted like the orientation lock:
     * the next camera's screen hides them before the previous one, going away, would show them again.
     */
    private var barsHidden = 0
    private val showBars = Runnable {
        if (barsHidden == 0) WindowCompat.getInsetsController(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
    }

    fun hideSystemBars() {
        barsHidden++
        handler.removeCallbacks(showBars)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    fun releaseSystemBars() {
        barsHidden = maxOf(0, barsHidden - 1)
        if (barsHidden == 0) handler.postDelayed(showBars, 300)
    }

    /** Shape of the floating window: the camera picture's. PiP starts only from the button, never by itself. */
    var pipAspect = 16f / 9f

    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(Language.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            BetterNvrTheme {
                BetterNvrApp(openCamera)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) {
            intent.dataString?.let(DeviceQr::decode)?.let { importDevice.value = it }
            return
        }
        val device = intent?.getStringExtra(EXTRA_DEVICE) ?: return
        val channel = intent.getIntExtra(EXTRA_CHANNEL, -1)
        if (channel >= 0) openCamera.value = ChannelRef(device, channel)
    }

    fun enterPip() {
        if (pipSupported) runCatching { enterPictureInPictureMode(pipParams()) }
    }

    private fun pipParams(): PictureInPictureParams = PictureInPictureParams.Builder()
        .setAspectRatio(Rational((pipAspect.coerceIn(0.42f, 2.38f) * 1000).toInt(), 1000))
        .build()

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip.value = isInPictureInPictureMode
    }

    override fun onStop() {
        super.onStop()
        // Streams stop with their surfaces; also end the recorder sessions while in background.
        if (!isChangingConfigurations) (application as App).manager.disconnectAll()
    }

    companion object {
        const val EXTRA_DEVICE = "pl.techkrzysiek.betternvr.DEVICE"
        const val EXTRA_CHANNEL = "pl.techkrzysiek.betternvr.CHANNEL"
    }
}
