package pl.techkrzysiek.betternvr.ui.camera

import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import android.view.View
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sign

/**
 * Zoom of the camera picture: a TextureView transform (a SurfaceView's picture cannot be scaled). Pinch and drag
 * follow the fingers; double tap, the bounce back from zooming past 1× or the maximum, the glide after a drag
 * and the pull past an edge (which leads to the next camera) are animated.
 */
class ZoomState(private val scope: CoroutineScope) {
    var view: TextureView? = null
    var scale by mutableFloatStateOf(1f)
        private set
    private var tx = 0f
    private var ty = 0f
    /** Horizontal rubber-band offset while the picture is pulled past its edge (towards another camera). */
    private var pull = 0f
    private val matrix = Matrix()
    private var animation: Job? = null

    val zoomed get() = scale > 1.01f
    private val w get() = view?.width?.toFloat() ?: 0f
    private val h get() = view?.height?.toFloat() ?: 0f

    /** The picture's left / right edge is on screen: pulling further leads to the previous / next camera. */
    val atLeftEdge get() = tx >= -EDGE_PX
    val atRightEdge get() = tx <= w - scale * w + EDGE_PX

    fun reset() {
        stop()
        scale = 1f; tx = 0f; ty = 0f
        apply()
    }

    /** Fingers down: whatever was moving stops where it is. */
    fun stop() {
        animation?.cancel()
        animation = null
        if (pull != 0f) { pull = 0f; apply() }
    }

    /** Pinch: scales by [factor] around ([cx], [cy]); a little past 1× or the maximum is allowed, then bounced back. */
    fun zoomBy(factor: Float, cx: Float, cy: Float) {
        val s = (scale * factor).coerceIn(SOFT_MIN_SCALE, MAX_SCALE * SOFT_MAX_FACTOR)
        val z = s / scale
        tx = cx - z * (cx - tx)
        ty = cy - z * (cy - ty)
        scale = s
        apply()
    }

    /** Drag: moves the picture; returns the horizontal part that would have gone past its edge. */
    fun panBy(dx: Float, dy: Float): Float {
        val before = tx
        tx += dx
        ty += dy
        apply()
        return dx - (tx - before)
    }

    /** Pull past the edge: [travel] of the finger, shown with growing resistance. */
    fun setPull(travel: Float) {
        val limit = w * 0.3f
        pull = if (limit <= 0f) 0f else sign(travel) * limit * (1 - exp(-abs(travel) / limit))
        apply()
    }

    /** Double tap: zoom in on that point, or back out to the whole picture. */
    fun toggle(cx: Float, cy: Float) {
        stop()
        animation = scope.launch {
            if (zoomed) animateTo(1f, 0f, 0f)
            else animateTo(DOUBLE_TAP_SCALE, clampX(cx - DOUBLE_TAP_SCALE / scale * (cx - tx), DOUBLE_TAP_SCALE),
                clampY(cy - DOUBLE_TAP_SCALE / scale * (cy - ty), DOUBLE_TAP_SCALE))
        }
    }

    /** Fingers up: back into range, the edge pull springs back, a drag glides on with the finger's speed. */
    fun settle(focusX: Float, focusY: Float, vx: Float, vy: Float) {
        stop()
        val glide = zoomed && (abs(vx) > MIN_GLIDE || abs(vy) > MIN_GLIDE)
        if (pull == 0f && scale in 1f..MAX_SCALE && !glide) return
        animation = scope.launch {
            launch {
                animate(pull, 0f, animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) { p, _ -> pull = p; apply() }
            }
            when {
                scale < 1f -> animateTo(1f, 0f, 0f)
                scale > MAX_SCALE -> animateTo(MAX_SCALE, clampX(focusX - MAX_SCALE / scale * (focusX - tx), MAX_SCALE),
                    clampY(focusY - MAX_SCALE / scale * (focusY - ty), MAX_SCALE))
                glide -> glide(vx, vy)
            }
        }
    }

    private suspend fun animateTo(s1: Float, tx1: Float, ty1: Float) {
        val s0 = scale
        val tx0 = tx
        val ty0 = ty
        animate(0f, 1f, animationSpec = tween(ANIMATION_MS, easing = FastOutSlowInEasing)) { p, _ ->
            scale = s0 + (s1 - s0) * p
            tx = tx0 + (tx1 - tx0) * p
            ty = ty0 + (ty1 - ty0) * p
            apply()
        }
    }

    /** Keeps moving with the release speed, slowing down; stops against an edge. */
    private suspend fun glide(vx0: Float, vy0: Float) {
        var vx = vx0
        var vy = vy0
        var last = withFrameNanos { it }
        while (abs(vx) > MIN_GLIDE || abs(vy) > MIN_GLIDE) {
            val now = withFrameNanos { it }
            val dt = (now - last) / 1e9f
            last = now
            val bx = tx
            val by = ty
            tx += vx * dt
            ty += vy * dt
            apply()
            if (tx == bx) vx = 0f
            if (ty == by) vy = 0f
            val k = exp(-GLIDE_FRICTION * dt)
            vx *= k
            vy *= k
        }
    }

    private fun clampX(t: Float, s: Float) = t.coerceIn(minOf(0f, w - s * w), 0f)
    private fun clampY(t: Float, s: Float) = t.coerceIn(minOf(0f, h - s * h), 0f)

    private fun apply() {
        val v = view ?: return
        if (scale >= 1f) {
            tx = clampX(tx, scale)
            ty = clampY(ty, scale)
        } else { // pinched below 1× for a moment: keep it centred
            tx = (w - scale * w) / 2
            ty = (h - scale * h) / 2
        }
        matrix.setScale(scale, scale)
        matrix.postTranslate(tx + pull, ty)
        v.setTransform(matrix)
    }

    companion object {
        const val MAX_SCALE = 6f
        private const val SOFT_MIN_SCALE = 0.85f
        private const val SOFT_MAX_FACTOR = 1.15f
        private const val DOUBLE_TAP_SCALE = 2.5f
        private const val ANIMATION_MS = 260
        private const val EDGE_PX = 1f
        private const val MIN_GLIDE = 40f // px/s
        private const val GLIDE_FRICTION = 4.5f // 1/s
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
 * Gestures on the picture: tap, double tap = zoom in / back, pinch = zoom, drag = move the zoomed picture.
 * A horizontal swipe = previous / next camera: at 1× right away, zoomed from the picture's edge – drag to the
 * edge first, then a new swipe from there pulls the picture past it (a drag that only reaches the edge stops).
 */
fun Modifier.videoGestures(zoom: ZoomState, onTap: () -> Unit, onSwipe: (forward: Boolean) -> Unit): Modifier = this
    .pointerInput(zoom) {
        detectTapGestures(onTap = { onTap() }, onDoubleTap = { zoom.toggle(it.x, it.y) })
    }
    .pointerInput(zoom) {
        val slop = viewConfiguration.touchSlop
        val switchAt = 72.dp.toPx()
        val flickAt = 24.dp.toPx()
        val flickSpeed = 800.dp.toPx() // px/s
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            zoom.stop()
            val canNext = zoom.atRightEdge
            val canPrev = zoom.atLeftEdge
            val velocity = VelocityTracker()
            velocity.addPosition(down.uptimeMillis, down.position)
            var multi = false
            var dragging = false
            var travel = Offset.Zero
            var over = 0f // finger travel past the edge; negative = towards the next camera
            var focus = down.position
            do {
                val event = awaitPointerEvent()
                if (event.changes.count { it.pressed } > 1 && !multi) {
                    multi = true
                    over = 0f
                    zoom.setPull(0f)
                }
                event.changes.firstOrNull { it.id == down.id && it.pressed }?.let { velocity.addPosition(it.uptimeMillis, it.position) }
                val factor = event.calculateZoom()
                val pan = event.calculatePan()
                val centroid = event.calculateCentroid(useCurrent = true)
                if (centroid != Offset.Unspecified) focus = centroid
                if (!dragging) {
                    travel += pan
                    // a tap or the first touch of a double tap must not move anything
                    if (!multi && travel.getDistance() <= slop) continue
                    dragging = true
                }
                if (multi) {
                    if (factor != 1f) zoom.zoomBy(factor, focus.x, focus.y)
                    zoom.panBy(pan.x, pan.y)
                } else {
                    var dx = pan.x
                    if (over != 0f && dx != 0f && sign(dx) != sign(over)) { // moving back: undo the pull first
                        val back = minOf(abs(dx), abs(over)) * sign(dx)
                        over += back
                        dx -= back
                    }
                    val rest = zoom.panBy(dx, pan.y)
                    if ((rest < 0f && canNext) || (rest > 0f && canPrev)) over += rest
                    zoom.setPull(over)
                }
                event.changes.forEach { if (it.positionChanged()) it.consume() }
            } while (event.changes.any { it.pressed })
            val v = velocity.calculateVelocity()
            if (!multi && over != 0f) {
                val forward = over < 0f
                val flick = abs(over) > flickAt && abs(v.x) > flickSpeed && (v.x < 0f) == forward
                if (abs(over) > switchAt || flick) onSwipe(forward)
            }
            if (multi) zoom.settle(focus.x, focus.y, 0f, 0f) else zoom.settle(focus.x, focus.y, v.x, v.y)
        }
    }
