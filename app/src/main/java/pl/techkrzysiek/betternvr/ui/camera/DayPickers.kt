package pl.techkrzysiek.betternvr.ui.camera

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pl.techkrzysiek.betternvr.R
import pl.techkrzysiek.betternvr.proto.EventSegment
import pl.techkrzysiek.betternvr.proto.RecordFile
import pl.techkrzysiek.betternvr.ui.theme.RecordColors
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.format.TextStyle as JTextStyle
import java.util.Locale

/** Month calendar with a dot under every day that has footage. */
@Composable
fun RecordCalendarDialog(
    selected: LocalDate,
    today: LocalDate,
    days: Map<YearMonth, Set<LocalDate>>,
    onMonth: (YearMonth) -> Unit,
    onPick: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
) {
    var month by remember { mutableStateOf(YearMonth.from(selected)) }
    LaunchedEffect(month) { onMonth(month) }
    val locale = Locale.getDefault()
    val have = days[month]
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { month = month.minusMonths(1) }) { Icon(Icons.Filled.ChevronLeft, null) }
                Text(
                    "${month.month.getDisplayName(JTextStyle.FULL_STANDALONE, locale).replaceFirstChar { it.titlecase(locale) }} ${month.year}",
                    modifier = Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium,
                )
                IconButton(onClick = { month = month.plusMonths(1) }, enabled = month < YearMonth.from(today)) { Icon(Icons.Filled.ChevronRight, null) }
            }
        },
        text = {
            Column {
                Row(Modifier.fillMaxWidth()) {
                    DayOfWeek.entries.forEach {
                        Text(it.getDisplayName(JTextStyle.SHORT_STANDALONE, locale), Modifier.weight(1f), textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (have == null) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 4.dp))
                val offset = month.atDay(1).dayOfWeek.value - 1
                val cells = offset + month.lengthOfMonth()
                for (week in 0 until (cells + 6) / 7) {
                    Row(Modifier.fillMaxWidth()) {
                        for (dow in 0 until 7) {
                            val n = week * 7 + dow - offset + 1
                            Box(Modifier.weight(1f).aspectRatio(1f), contentAlignment = Alignment.Center) {
                                if (n in 1..month.lengthOfMonth()) {
                                    val d = month.atDay(n)
                                    val future = d.isAfter(today)
                                    val isSel = d == selected
                                    Column(
                                        Modifier.size(40.dp).clip(CircleShape)
                                            .background(if (isSel) MaterialTheme.colorScheme.primary else Color.Transparent)
                                            .clickable(enabled = !future) { onPick(d) },
                                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                                    ) {
                                        Text(
                                            "$n",
                                            color = when {
                                                isSel -> MaterialTheme.colorScheme.onPrimary
                                                future -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                                                else -> MaterialTheme.colorScheme.onSurface
                                            },
                                            fontWeight = if (d == today) FontWeight.Bold else FontWeight.Normal,
                                        )
                                        Box(
                                            Modifier.size(5.dp).clip(CircleShape).background(
                                                when {
                                                    have?.contains(d) != true -> Color.Transparent
                                                    isSel -> MaterialTheme.colorScheme.onPrimary
                                                    else -> RecordColors.continuous
                                                },
                                            ),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
    )
}

/** Whole day at a glance under the detailed timeline: drag across it to travel hours in one move. */
@Composable
fun DayOverview(
    day: LocalDate,
    records: List<RecordFile>,
    events: List<EventSegment>,
    position: LocalDateTime?,
    now: LocalDateTime?,
    onSeek: (LocalDateTime) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dayStart = day.atStartOfDay()
    var dragSec by remember { mutableStateOf<Float?>(null) }
    val seek by rememberUpdatedState(onSeek)
    val measurer = rememberTextMeasurer()
    val scheme = MaterialTheme.colorScheme
    val labelStyle = TextStyle(color = scheme.onSurfaceVariant, fontSize = 9.sp)
    val bubbleStyle = TextStyle(color = scheme.onPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    fun sec(t: LocalDateTime) = Duration.between(dayStart, t).seconds.toFloat()

    Canvas(
        modifier.height(36.dp).pointerInput(day) {
            awaitEachGesture {
                val down = awaitFirstDown()
                fun toSec(x: Float) = (x / size.width * 86400f).coerceIn(0f, 86399f)
                dragSec = toSec(down.position.x)
                do {
                    val ev = awaitPointerEvent()
                    ev.changes.firstOrNull()?.let { dragSec = toSec(it.position.x); it.consume() }
                } while (ev.changes.any { it.pressed })
                dragSec?.let { seek(dayStart.plusSeconds(it.toLong())) }
                dragSec = null
            }
        },
    ) {
        val w = size.width
        val h = size.height
        fun x(s: Float) = s / 86400f * w
        val top = h * 0.40f
        val bh = h * 0.30f
        drawRoundRect(scheme.surfaceVariant, Offset(0f, top), Size(w, bh), CornerRadius(4f, 4f))
        records.forEach { r ->
            val x0 = x(sec(r.start).coerceAtLeast(0f)); val x1 = x(sec(r.end).coerceAtMost(86400f))
            if (x1 > x0) drawRect(RecordColors.continuous.copy(alpha = 0.55f), Offset(x0, top), Size(maxOf(1f, x1 - x0), bh))
        }
        events.forEach { e ->
            val x0 = x(sec(e.start).coerceAtLeast(0f)); val x1 = x(sec(e.end).coerceAtMost(86400f))
            drawRect(e.type.color(), Offset(x0, top - 4f), Size(maxOf(2f, x1 - x0), 4f))
        }
        now?.takeIf { it.toLocalDate() == day }?.let { n ->
            val xn = x(sec(n)); drawRect(Color.Black.copy(alpha = 0.25f), Offset(xn, top), Size(w - xn, bh))
        }
        for (hr in 0..24 step 3) {
            val px = x(hr * 3600f)
            drawLine(scheme.onSurfaceVariant.copy(alpha = 0.5f), Offset(px, top + bh), Offset(px, top + bh + 4f))
            val l = measurer.measure("%02d".format(hr), labelStyle)
            drawText(l, topLeft = Offset((px - l.size.width / 2f).coerceIn(0f, w - l.size.width), top + bh + 4f))
        }
        val cur = dragSec ?: position?.takeIf { it.toLocalDate() == day }?.let { sec(it) }
        cur?.let { s ->
            val px = x(s)
            drawLine(scheme.error, Offset(px, top - 6f), Offset(px, top + bh + 2f), strokeWidth = 3f)
            if (dragSec != null) {
                val t = s.toInt()
                val l = measurer.measure("%02d:%02d".format(t / 3600, (t % 3600) / 60), bubbleStyle)
                val bx = (px - l.size.width / 2f - 6f).coerceIn(0f, w - l.size.width - 12f)
                drawRoundRect(scheme.primary, Offset(bx, 0f), Size(l.size.width + 12f, l.size.height + 2f), CornerRadius(8f, 8f))
                drawText(l, topLeft = Offset(bx + 6f, 1f))
            }
        }
    }
}
