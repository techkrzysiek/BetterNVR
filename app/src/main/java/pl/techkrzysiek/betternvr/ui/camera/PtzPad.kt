package pl.techkrzysiek.betternvr.ui.camera

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.NorthEast
import androidx.compose.material.icons.filled.NorthWest
import androidx.compose.material.icons.filled.SouthEast
import androidx.compose.material.icons.filled.SouthWest
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import pl.techkrzysiek.betternvr.proto.DahuaClient

/**
 * Eight-way pad, + zoom only when the camera reports it (DMSS shows zoom on every PTZ camera, even those
 * without a zoom lens). Press and hold to move, release to stop.
 */
@Composable
fun PtzPad(caps: DahuaClient.PtzCaps, onStart: (Int) -> Unit, onStop: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                HoldButton(Icons.Filled.NorthWest, 0x20, onStart, onStop)
                HoldButton(Icons.Filled.KeyboardArrowUp, 0, onStart, onStop, enabled = caps.tilt)
                HoldButton(Icons.Filled.NorthEast, 0x21, onStart, onStop)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                HoldButton(Icons.Filled.KeyboardArrowLeft, 2, onStart, onStop, enabled = caps.pan)
                Box(Modifier.size(52.dp))
                HoldButton(Icons.Filled.KeyboardArrowRight, 3, onStart, onStop, enabled = caps.pan)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                HoldButton(Icons.Filled.SouthWest, 0x22, onStart, onStop)
                HoldButton(Icons.Filled.KeyboardArrowDown, 1, onStart, onStop, enabled = caps.tilt)
                HoldButton(Icons.Filled.SouthEast, 0x23, onStart, onStop)
            }
        }
        if (caps.zoom) {
            Column(Modifier.padding(start = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                HoldButton(Icons.Filled.ZoomIn, 4, onStart, onStop)
                HoldButton(Icons.Filled.ZoomOut, 5, onStart, onStop)
            }
        }
    }
}

@Composable
private fun HoldButton(icon: ImageVector, action: Int, onStart: (Int) -> Unit, onStop: (Int) -> Unit, enabled: Boolean = true) {
    var pressed by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier.size(52.dp).clip(CircleShape)
            .background(if (pressed) scheme.primary else scheme.surfaceVariant)
            .then(if (enabled) Modifier.pointerInput(action) {
                detectTapGestures(onPress = {
                    pressed = true
                    onStart(action)
                    tryAwaitRelease()
                    onStop(action)
                    pressed = false
                })
            } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = if (!enabled) scheme.onSurface.copy(alpha = 0.3f) else if (pressed) scheme.onPrimary else scheme.onSurfaceVariant)
    }
}
