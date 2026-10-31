package pl.techkrzysiek.betternvr.ui.camera

import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import android.view.View
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.math.abs

/** Zoom of the camera picture: a TextureView transform (a SurfaceView's picture cannot be scaled). */
class ZoomState {
    var view: TextureView? = null
    var scale by mutableFloatStateOf(1f)
        private set
    private var tx = 0f
    private var ty = 0f
    private val matrix = Matrix()

    val zoomed get() = scale > 1.01f

    fun reset() {
        scale = 1f; tx = 0f; ty = 0f
        apply()
    }

    /** Scales by [factor] keeping the point ([cx], [cy]) under the fingers, then moves by ([dx], [dy]). */
    fun transform(factor: Float, cx: Float, cy: Float, dx: Float, dy: Float) {
        val s = (scale * factor).coerceIn(1f, MAX_SCALE)
        val z = s / scale
        tx = cx - z * (cx - tx) + dx
        ty = cy - z * (cy - ty) + dy
        scale = s
        apply()
    }

    fun toggle(cx: Float, cy: Float) = if (zoomed) reset() else transform(2.5f, cx, cy, 0f, 0f)

    private fun apply() {
        val v = view ?: return
        val w = v.width.toFloat()
        val h = v.height.toFloat()
        tx = tx.coerceIn(w - scale * w, 0f)
        ty = ty.coerceIn(h - scale * h, 0f)
        matrix.setScale(scale, scale)
        matrix.postTranslate(tx, ty)
        v.setTransform(matrix)
    }

    companion object {
        const val MAX_SCALE = 6f
    }
}

/**
 * Camera picture on a TextureView. Unlike a SurfaceView its surface outlives a stopped activity, so the
 * stream is detached on ON_STOP and attached again on ON_START (no picture = no network traffic).
 */
@Composable
fun ZoomableVideo(zoom: ZoomState, onAttach: (View, Surface) -> Unit, onDetach: () -> Unit, modifier: Modifier = Modifier) {
    val attach by rememberUpdatedState(onAttach)
    val detach by rememberUpdatedState(onDetach)
    val holder = remember { SurfaceHolder() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> if (holder.attached) { holder.attached = false; detach() }
                Lifecycle.Event.ON_START -> {
                    val v = holder.view
                    val s = holder.surface
                    if (!holder.attached && v != null && s != null) { holder.attached = true; attach(v, s) }
                }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    AndroidView(
        modifier = modifier.clipToBounds(),
        factory = { ctx ->
            TextureView(ctx).apply {
                zoom.view = this
                holder.view = this
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                        val s = Surface(st)
                        holder.surface = s
                        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) { holder.attached = true; attach(this@apply, s) }
                    }

                    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) = zoom.reset()

                    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                        if (holder.attached) { holder.attached = false; detach() }
                        holder.surface?.release()
                        holder.surface = null
                        return true
                    }

                    override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
                }
            }
        },
    )
}

private class SurfaceHolder {
    var view: TextureView? = null
    var surface: Surface? = null
    var attached = false
}

/**
 * Gestures on the picture: tap, double tap = zoom in / back, pinch = zoom, drag = move the zoomed picture,
 * and at normal size a horizontal swipe = previous / next camera.
 */
fun Modifier.videoGestures(zoom: ZoomState, onTap: () -> Unit, onSwipe: (forward: Boolean) -> Unit): Modifier = this
    .pointerInput(zoom) {
        detectTapGestures(onTap = { onTap() }, onDoubleTap = { zoom.toggle(it.x, it.y) })
    }
    .pointerInput(zoom) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            var dx = 0f
            var multi = false
            var swiping = false
            do {
                val event = awaitPointerEvent()
                if (event.changes.count { it.pressed } > 1) multi = true
                val factor = event.calculateZoom()
                val pan = event.calculatePan()
                if (multi || zoom.zoomed) {
                    val c = event.calculateCentroid(useCurrent = true)
                    if (c != Offset.Unspecified && (factor != 1f || pan != Offset.Zero)) {
                        zoom.transform(factor, c.x, c.y, pan.x, pan.y)
                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                    }
                } else {
                    dx += pan.x
                    if (swiping || abs(dx) > viewConfiguration.touchSlop) {
                        swiping = true
                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                    }
                }
            } while (event.changes.any { it.pressed })
            if (!multi && !zoom.zoomed) {
                val limit = 80.dp.toPx()
                if (dx < -limit) onSwipe(true) else if (dx > limit) onSwipe(false)
            }
        }
    }
