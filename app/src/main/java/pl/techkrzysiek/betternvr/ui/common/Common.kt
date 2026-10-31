package pl.techkrzysiek.betternvr.ui.common

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import pl.techkrzysiek.betternvr.App
import pl.techkrzysiek.betternvr.R
import pl.techkrzysiek.betternvr.player.PlayerState

@Composable
fun app(): App = LocalContext.current.applicationContext as App

/** Shows a transient message (snackbar) from anywhere in the UI. */
val LocalMessages = compositionLocalOf<(String) -> Unit> { {} }

/** Message with a "Share" action for a saved file (uri, mime type). */
val LocalShareMessages = compositionLocalOf<(String, android.net.Uri, String) -> Unit> { { _, _, _ -> } }

/** A SurfaceView whose surface lifetime drives streaming: no surface, no network traffic. */
@Composable
fun VideoSurface(
    onAttach: (SurfaceView, Surface) -> Unit,
    onDetach: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val attach by rememberUpdatedState(onAttach)
    val detach by rememberUpdatedState(onDetach)
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            SurfaceView(ctx).apply {
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) = attach(this@apply, holder.surface)
                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
                    override fun surfaceDestroyed(holder: SurfaceHolder) = detach()
                })
            }
        },
    )
}

@Composable
fun PlayerStateOverlay(state: PlayerState, onRetry: () -> Unit, modifier: Modifier = Modifier, compact: Boolean = false) {
    val text = when (state) {
        PlayerState.Connecting -> stringResource(R.string.state_connecting)
        is PlayerState.Error -> if (state.retrying) stringResource(R.string.state_reconnecting) else stringResource(R.string.state_error, state.message)
        PlayerState.NoRecording -> stringResource(R.string.state_no_recording)
        PlayerState.NoCamera -> stringResource(R.string.state_no_camera)
        PlayerState.Paused, PlayerState.Playing, PlayerState.Idle -> return
    }
    Box(modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (state == PlayerState.Connecting || (state is PlayerState.Error && state.retrying)) {
                CircularProgressIndicator(Modifier.size(if (compact) 22.dp else 36.dp), color = Color.White, strokeWidth = 2.dp)
            }
            if (!compact || state !is PlayerState.Connecting) {
                Text(
                    text, color = Color.White, textAlign = TextAlign.Center,
                    style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
            if (state is PlayerState.Error && !state.retrying) {
                TextButton(onClick = onRetry) { Text(stringResource(R.string.retry), color = Color.White) }
            }
        }
    }
}

/** Runs [action] once saving to shared storage is allowed (only Android 8–9 needs a runtime permission). */
@Composable
fun rememberStorageGate(onDenied: () -> Unit): ((() -> Unit) -> Unit) {
    val context = LocalContext.current
    val pending = remember { arrayOfNulls<() -> Unit>(1) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val a = pending[0]
        pending[0] = null
        if (granted) a?.invoke() else onDenied()
    }
    return remember {
        { action: () -> Unit ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
            ) action() else {
                pending[0] = action
                launcher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
    }
}
