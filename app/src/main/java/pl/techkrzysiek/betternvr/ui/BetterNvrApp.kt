package pl.techkrzysiek.betternvr.ui

import android.app.Activity
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Scaffold
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import pl.techkrzysiek.betternvr.MainActivity
import pl.techkrzysiek.betternvr.data.ChannelRef
import pl.techkrzysiek.betternvr.ui.camera.CameraScreen
import pl.techkrzysiek.betternvr.ui.common.LocalMessages
import pl.techkrzysiek.betternvr.ui.common.LocalShareMessages
import pl.techkrzysiek.betternvr.ui.common.shareMedia
import pl.techkrzysiek.betternvr.ui.devices.DevicesScreen
import pl.techkrzysiek.betternvr.ui.grid.DeviceGridScreen

/** Recorders → camera grid of one recorder → single camera (live + timeline). */
@Composable
fun BetterNvrApp(openCamera: MutableStateFlow<ChannelRef?>) {
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val context = androidx.compose.ui.platform.LocalContext.current
    val shareLabel = androidx.compose.ui.res.stringResource(pl.techkrzysiek.betternvr.R.string.share)
    CompositionLocalProvider(
        LocalMessages provides { msg: String -> scope.launch { snackbar.showSnackbar(msg) } },
        LocalShareMessages provides { msg: String, uri: android.net.Uri, mime: String ->
            scope.launch {
                val r = snackbar.showSnackbar(msg, actionLabel = shareLabel, duration = androidx.compose.material3.SnackbarDuration.Long)
                if (r == androidx.compose.material3.SnackbarResult.ActionPerformed) shareMedia(context, uri, mime)
            }
        },
    ) {
        // No messages over the floating (PiP) window: it only shows the picture.
        val inPip by ((context as? MainActivity)?.inPip ?: remember { MutableStateFlow(false) }).collectAsState()
        LaunchedEffect(inPip) { if (inPip) snackbar.currentSnackbarData?.dismiss() }
        Scaffold(snackbarHost = { if (!inPip) SnackbarHost(snackbar, Modifier.navigationBarsPadding()) }, contentWindowInsets = WindowInsets(0)) { _ ->
            Box(Modifier.fillMaxSize()) {
                // No animated transitions: video SurfaceViews cannot fade, so animated screens overlapped.
                NavHost(
                    navController = nav, startDestination = "devices",
                    enterTransition = { EnterTransition.None }, exitTransition = { ExitTransition.None },
                    popEnterTransition = { EnterTransition.None }, popExitTransition = { ExitTransition.None },
                ) {
                    composable("devices") {
                        DevicesScreen(
                            onOpen = { id -> nav.navigate("grid/$id") },
                            onMultiView = { id -> nav.navigate("multi/$id") },
                            onOpenCamera = { id, ch -> nav.navigate("camera/$id/$ch") },
                        )
                    }
                    composable("grid/{id}") { entry ->
                        val id = entry.arguments?.getString("id") ?: return@composable
                        DeviceGridScreen(
                            deviceId = id,
                            onBack = { nav.popBackStack() },
                            onOpenCamera = { ch -> nav.navigate("camera/$id/$ch") },
                            onMultiView = { nav.navigate("multi/$id") },
                        )
                    }
                    composable("multi/{id}") { entry ->
                        val id = entry.arguments?.getString("id") ?: return@composable
                        pl.techkrzysiek.betternvr.ui.grid.MultiViewScreen(
                            deviceId = id,
                            onBack = { nav.popBackStack() },
                            onOpenCamera = { ch -> nav.navigate("camera/$id/$ch") },
                        )
                    }
                    composable("camera/{id}/{ch}") { entry ->
                        val id = entry.arguments?.getString("id") ?: return@composable
                        val ch = entry.arguments?.getString("ch")?.toIntOrNull() ?: return@composable
                        CameraScreen(
                            deviceId = id, channel = ch, onBack = { nav.popBackStack() },
                            onSwitch = { next -> nav.navigate("camera/$id/$next") { popUpTo("camera/{id}/{ch}") { inclusive = true } } },
                        )
                    }
                }
                // A recorder QR link was opened: back to the list, where the add dialog shows it.
                val importing by ((context as? MainActivity)?.importDevice ?: remember { MutableStateFlow<pl.techkrzysiek.betternvr.data.Device?>(null) }).collectAsState()
                LaunchedEffect(importing != null) { if (importing != null) nav.popBackStack("devices", inclusive = false) }
                // A widget asked for a camera: open it on top of the recorder list.
                val target by openCamera.collectAsState()
                LaunchedEffect(target) {
                    val ref = target ?: return@LaunchedEffect
                    openCamera.value = null
                    nav.navigate("camera/${ref.deviceId}/${ref.channel}") { popUpTo("devices") }
                }
            }
        }
    }
}

@Composable
fun SystemBars(hidden: Boolean) {
    val view = LocalView.current
    DisposableEffect(hidden) {
        val window = (view.context as? Activity)?.window
        if (window != null) {
            val c = WindowCompat.getInsetsController(window, view)
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (hidden) c.hide(WindowInsetsCompat.Type.systemBars()) else c.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            if (hidden && window != null) WindowCompat.getInsetsController(window, view).show(WindowInsetsCompat.Type.systemBars())
        }
    }
}
