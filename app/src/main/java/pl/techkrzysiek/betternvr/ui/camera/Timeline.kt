package pl.techkrzysiek.betternvr.ui.camera

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import pl.techkrzysiek.betternvr.proto.EventSegment
import pl.techkrzysiek.betternvr.proto.EventType
import pl.techkrzysiek.betternvr.proto.RecordFile
import pl.techkrzysiek.betternvr.ui.theme.RecordColors
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.abs

fun EventType.color(): Color = when (this) {
    EventType.MOTION -> RecordColors.motion
    EventType.HUMAN -> RecordColors.human
    EventType.VEHICLE -> RecordColors.vehicle
    EventType.ALARM -> RecordColors.alarm
}

private val STEPS = intArrayOf(60, 300, 600, 900, 1800, 3600, 7200, 10800, 21600)

/**
 * Scrollable, pinch-zoomable day ruler. Blue = footage exists, coloured marks = detections (only
 * the selected types). The centre cursor follows the frame on screen; dragging leaves the stream
 * alone and seeks once on release; a tap seeks to the tapped moment. The part after "now" is dimmed.
 */
@Composable
fun Timeline(
    day: LocalDate,
    records: List<RecordFile>,
    events: List<EventSegment>,
    position: LocalDateTime?,
    now: LocalDateTime?,
    onSeek: (LocalDateTime) -> Unit,
    modifier: Modifier = Modifier,
    overlay: Boolean = false,
) {
    val dayStart = day.atStartOfDay()
    var spanSec by rememberSaveable { mutableFloatStateOf(2 * 3600f) }
    var centerSec by remember(day) { mutableFloatStateOf(position?.let { secondsOf(dayStart, it) } ?: 43200f) }
    var interacting by remember { mutableStateOf(false) }
    val seek by rememberUpdatedState(onSeek)
    val measurer = rememberTextMeasurer()
    val scheme = MaterialTheme.colorScheme
    val fg = if (overlay) Color.White else scheme.onSurfaceVariant
    val labelStyle = TextStyle(color = fg, fontSize = 10.sp)
    val cursorStyle = TextStyle(color = if (overlay) Color.White else scheme.onSurface, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    val nowSec = now?.takeIf { it.toLocalDate() == day }?.let { secondsOf(dayStart, it) }
        ?: if (now != null && now.toLocalDate().isBefore(day)) 0f else null

    LaunchedEffect(position, day) {
        if (!interacting && position != null && position.toLocalDate() == day) centerSec = secondsOf(dayStart, position).coerceIn(0f, 86399f)
    }

    Canvas(
        modifier.pointerInput(day) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                interacting = true
                var moved = false
                do {
                    val ev = awaitPointerEvent()
                    val zoom = ev.calculateZoom()
                    val pan = ev.calculatePan()
                    if (zoom != 1f) { spanSec = (spanSec / zoom).coerceIn(300f, 86400f); moved = true }
                    if (abs(pan.x) > 0.5f) {
                        centerSec = (centerSec - pan.x * spanSec / size.width).coerceIn(0f, 86399f)
                        moved = true
                    }
                    ev.changes.forEach { if (it.positionChange() != Offset.Zero) it.consume() }
                } while (ev.changes.any { it.pressed })
                if (!moved) {
                    centerSec = (centerSec + (down.position.x - size.width / 2f) * spanSec / size.width).coerceIn(0f, 86399f)
                }
                interacting = false
                seek(dayStart.plusSeconds(centerSec.toLong()))
            }
        },
    ) {
        val w = size.width
        val h = size.height
        val pxPerSec = w / spanSec
        val viewStart = centerSec - spanSec / 2f
        fun x(sec: Float) = (sec - viewStart) * pxPerSec

        drawRect(if (overlay) Color.Black.copy(alpha = 0.45f) else scheme.surfaceVariant)
        // footage
        val barTop = h * 0.34f
        val barH = h * 0.22f
        for (r in records) {
            val x0 = x(secondsOf(dayStart, r.start).coerceAtLeast(0f)).coerceAtLeast(0f)
            val x1 = x(secondsOf(dayStart, r.end).coerceAtMost(86400f)).coerceAtMost(w)
            if (x1 <= 0f || x0 >= w || x1 <= x0) continue
            drawRect(RecordColors.continuous.copy(alpha = 0.6f), Offset(x0, barTop), Size(maxOf(1.5f, x1 - x0), barH))
        }
        // detections, stacked a little so overlapping types stay visible
        for (e in events) {
            val x0 = x(secondsOf(dayStart, e.start).coerceAtLeast(0f)).coerceAtLeast(0f)
            val x1 = x(secondsOf(dayStart, e.end).coerceAtMost(86400f)).coerceAtMost(w)
            if (x1 <= 0f || x0 >= w) continue
            val lane = when (e.type) { EventType.MOTION -> 0; EventType.HUMAN -> 1; EventType.VEHICLE -> 2; EventType.ALARM -> 3 }
            drawRect(e.type.color(), Offset(x0, barTop - 6f - lane * 5f), Size(maxOf(3f, x1 - x0), 5f))
        }
        // future
        nowSec?.let { ns ->
            val xn = x(ns).coerceIn(0f, w)
            if (xn < w) drawRect(Color.Black.copy(alpha = 0.25f), Offset(xn, 0f), Size(w - xn, h))
        }
        // ticks
        val step = STEPS.firstOrNull { spanSec / it <= 6f } ?: 21600
        val minor = when (step) { 60 -> 10; 300 -> 60; 600 -> 120; 900 -> 300; 1800 -> 300; 3600 -> 900; 7200 -> 1800; else -> 3600 }
        var t = (viewStart / minor).toInt() * minor
        while (t <= viewStart + spanSec) {
            if (t in 0..86400) {
                val px = x(t.toFloat())
                val major = t % step == 0
                drawLine(fg.copy(alpha = if (major) 0.8f else 0.35f), Offset(px, h - (if (major) 14f else 7f)), Offset(px, h), strokeWidth = 1.5f)
                if (major) {
                    val label = if (t == 86400) "24:00" else "%02d:%02d".format(t / 3600, (t % 3600) / 60)
                    val layout = measurer.measure(label, labelStyle)
                    drawText(layout, topLeft = Offset(px - layout.size.width / 2f, h - 16f - layout.size.height))
                }
            }
            t += minor
        }
        // cursor
        val cx = w / 2f
        drawLine(scheme.error, Offset(cx, 0f), Offset(cx, h), strokeWidth = 3f)
        val c = centerSec.toInt()
        val label = "%02d:%02d:%02d".format(c / 3600, (c % 3600) / 60, c % 60)
        val layout = measurer.measure(label, cursorStyle)
        val bx = (cx - layout.size.width / 2f - 6f).coerceIn(0f, w - layout.size.width - 12f)
        drawRoundRect(
            if (overlay) Color.Black.copy(alpha = 0.6f) else scheme.surface,
            topLeft = Offset(bx, 2f), size = Size(layout.size.width + 12f, layout.size.height + 4f), cornerRadius = CornerRadius(8f, 8f),
        )
        drawText(layout, topLeft = Offset(bx + 6f, 4f))
    }
}

private fun secondsOf(dayStart: LocalDateTime, t: LocalDateTime): Float = Duration.between(dayStart, t).seconds.toFloat()
