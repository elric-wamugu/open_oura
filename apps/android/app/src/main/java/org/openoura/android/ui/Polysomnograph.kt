package org.openoura.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.openoura.android.data.NightRow
import org.openoura.android.ui.theme.Oura
import org.openoura.android.ui.theme.OuraColors
import kotlin.math.roundToInt

/** The night's clock window, unwrapped across midnight so `end` may exceed 24 h. */
internal data class NightWindow(val startMin: Int, val spanMin: Int) {
    fun clockAt(f: Float): String {
        val t = ((startMin + (f * spanMin).roundToInt()) % 1440 + 1440) % 1440
        return "%02d:%02d".format(t / 60, t % 60)
    }
}

internal fun nightWindow(start: String?, end: String?): NightWindow {
    fun hm(s: String?): Int {
        val parts = (s ?: "").split(":")
        val h = parts.getOrNull(0)?.toIntOrNull() ?: 0
        val m = parts.getOrNull(1)?.toIntOrNull() ?: 0
        return h * 60 + m
    }
    val a = hm(start)
    var b = hm(end)
    if (b <= a) b += 1440
    return NightWindow(a, (b - a).coerceAtLeast(1))
}

/** One signal lane: its own series, its own vertical scale, a shared horizontal one. */
private class Lane(
    val label: String,
    val unit: String,
    val values: List<Double>,
    val colour: Color,
    val decimals: Int = 0,
) {
    val mean = values.average()
    fun fmt(v: Double) = if (decimals > 0) "%.${decimals}f".format(v) else "${v.roundToInt()}"
    fun summary() = "${fmt(mean)} $unit"
    fun valueAt(f: Float): String {
        if (values.isEmpty()) return "—"
        val i = (f * (values.size - 1)).roundToInt().coerceIn(values.indices)
        return "${fmt(values[i])} $unit"
    }
}

/** Awake at the top, Deep at the bottom — the clinical convention the web plot uses. */
private fun stageLevel(code: Int) = when (code) {
    1 -> 3 // deep
    3 -> 1 // rem
    4 -> 0 // awake
    else -> 2 // light
}

private fun stageName(code: Int) = when (code) {
    1 -> "Deep"
    3 -> "REM"
    4 -> "Awake"
    else -> "Light"
}

private fun stageColour(code: Int, c: OuraColors) = when (code) {
    1 -> c.deep
    3 -> c.rem
    4 -> c.wake
    else -> c.light
}

/**
 * The overnight polysomnograph: the hypnogram stacked over every signal the night
 * recorded, all on one time axis, with a scrubber that reads them together.
 *
 * Mirrors `polysomnograph` in the web client, with one deliberate difference. The web lays
 * each lane's label and value in a gutter to the left of its plot; at phone width that
 * gutter would cost a fifth of the chart, so here the label and value sit in a thin header
 * row above each lane and the plots run the full width. Same information, same shared
 * cursor, more pixels for the part that carries it.
 *
 * Reading a lane in isolation is the thing this replaces: a dip in HR means one thing
 * during deep sleep and another during an awakening, and the only way to tell is to see
 * both at the same instant. Hence one cursor across every lane rather than a chart each.
 */
@Composable
fun Polysomnograph(night: NightRow, modifier: Modifier = Modifier) {
    val c = Oura.colors
    val stages = night.stagesFull?.takeIf { it.size > 1 } ?: night.stages.orEmpty()
    if (stages.size < 2) {
        Text("No hypnogram for this night.", color = c.muted, fontSize = 12.sp)
        return
    }
    val s = night.series
    val lanes = remember(night.id, c) {
        listOfNotNull(
            s?.hr?.takeIf { it.size > 1 }?.let { Lane("Heart rate", "bpm", it, c.warn) },
            s?.hrv?.takeIf { it.size > 1 }?.let { Lane("HRV", "ms", it, c.accent) },
            s?.spo2?.takeIf { it.size > 1 }?.let { Lane("Blood O₂", "%", it, c.rem) },
            s?.temp?.takeIf { it.size > 1 }?.let { Lane("Skin temp", "°C", it, c.light, decimals = 1) },
            s?.motion?.takeIf { it.size > 1 }?.let { Lane("Motion", "s", it, c.faint) },
        )
    }
    val win = remember(night.id) { nightWindow(night.start, night.end) }
    var cursor by remember(night.id) { mutableStateOf<Float?>(null) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(c.surface)
            .border(1.dp, c.line, RoundedCornerShape(10.dp))
            .padding(12.dp)
            // One gesture surface for the whole stack: the lanes only mean anything read
            // together, so they move together.
            //
            // Horizontal only, and that is not a detail. This chart is most of the screen,
            // and a plain detectDragGestures claims every direction — which left the page
            // unscrollable anywhere over it, because every attempt to swipe past became a
            // scrub. Taking only the horizontal axis lets a vertical drag reach the
            // scrolling parent, which is the one it was meant for.
            .pointerInput(night.id) {
                detectHorizontalDragGestures(
                    onDragEnd = { cursor = null },
                    onDragCancel = { cursor = null },
                ) { change, _ ->
                    change.consume()
                    cursor = (change.position.x / size.width).coerceIn(0f, 1f)
                }
            },
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            cursor?.let { win.clockAt(it) } ?: "${night.start ?: "—"}–${night.end ?: "—"}",
            color = if (cursor != null) c.text else c.muted,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = if (cursor != null) FontWeight.Medium else FontWeight.Normal,
        )

        LaneHeader(
            "Hypnogram",
            cursor?.let { f -> stageName(stages[(f * (stages.size - 1)).roundToInt().coerceIn(stages.indices)]) }
                ?: "",
            c,
        )
        Canvas(Modifier.fillMaxWidth().height(92.dp)) {
            drawHypnogram(stages, c)
            cursor?.let { drawCursor(it, c) }
        }

        lanes.forEach { lane ->
            LaneHeader(lane.label, cursor?.let { lane.valueAt(it) } ?: lane.summary(), c)
            Canvas(Modifier.fillMaxWidth().height(44.dp)) {
                drawLane(lane, c)
                cursor?.let { drawCursor(it, c) }
            }
        }

        HourAxis(win, c)
    }
}

@Composable
private fun LaneHeader(label: String, value: String, c: OuraColors) {
    Row(
        Modifier.fillMaxWidth().padding(top = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = c.muted, fontSize = 10.sp)
        Text(
            value,
            color = c.text,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
        )
    }
}

/** Hour ticks across the night, placed by fraction so they line up with the plots. */
@Composable
private fun HourAxis(win: NightWindow, c: OuraColors) {
    val ticks = remember(win) {
        val first = ((win.startMin + 59) / 60) * 60
        generateSequence(first) { it + 60 }
            .takeWhile { it <= win.startMin + win.spanMin }
            .map { t -> (t - win.startMin).toFloat() / win.spanMin to "%02d".format((t % 1440) / 60) }
            .toList()
    }
    // padding before height: the other order sizes the box at 14 dp and then insets the
    // labels inside it, which clipped their descenders.
    BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 4.dp).height(14.dp)) {
        val w = maxWidth
        ticks.forEach { (f, label) ->
            Text(
                label,
                Modifier.offset(x = w * f - 7.dp),
                color = c.faint,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

/** Stepped clinical hypnogram: flat coloured runs, faint risers between stage changes. */
private fun DrawScope.drawHypnogram(stages: List<Int>, c: OuraColors) {
    val padT = 8f
    val plotH = size.height - 16f
    fun yOf(level: Int) = padT + (level / 3f) * plotH
    fun xOf(i: Int) = i.toFloat() / (stages.size - 1) * size.width

    for (level in 0..3) {
        val y = yOf(level)
        drawLine(c.lineSoft, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
    }
    var i = 0
    var prev: Int? = null
    while (i < stages.size) {
        val code = stages[i]
        var j = i
        while (j < stages.size && stages[j] == code) j++
        val level = stageLevel(code)
        val x1 = xOf(i)
        val x2 = xOf(minOf(j, stages.size - 1))
        val y = yOf(level)
        prev?.let { drawLine(c.faint.copy(alpha = 0.5f), Offset(x1, yOf(it)), Offset(x1, y), strokeWidth = 1f) }
        drawLine(stageColour(code, c), Offset(x1, y), Offset(x2, y), strokeWidth = 3f)
        prev = level
        i = j
    }
}

/** Auto-scaled line with a faint fill and a dashed mean, per the web's `laneSvg`. */
private fun DrawScope.drawLane(lane: Lane, c: OuraColors) {
    val v = lane.values
    val min = v.min()
    val range = (v.max() - min).takeIf { it > 0 } ?: 1.0
    val pad = 5f
    fun yOf(x: Double) = pad + (1 - (x - min) / range).toFloat() * (size.height - 2 * pad)
    fun xOf(i: Int) = i.toFloat() / (v.size - 1) * size.width

    val line = Path().apply {
        moveTo(0f, yOf(v[0]))
        for (i in 1 until v.size) lineTo(xOf(i), yOf(v[i]))
    }
    val area = Path().apply {
        addPath(line)
        lineTo(size.width, size.height)
        lineTo(0f, size.height)
        close()
    }
    drawPath(area, lane.colour.copy(alpha = 0.09f))
    val my = yOf(lane.mean)
    drawLine(lane.colour.copy(alpha = 0.45f), Offset(0f, my), Offset(size.width, my), strokeWidth = 1f)
    drawPath(line, lane.colour, style = Stroke(width = 1.6f))
    // The grid line the web draws behind every lane, kept so an empty-looking lane still
    // reads as a lane rather than as a rendering failure.
    drawLine(c.lineSoft, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1f)
}

private fun DrawScope.drawCursor(f: Float, c: OuraColors) {
    val x = f * size.width
    drawLine(c.text.copy(alpha = 0.55f), Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.5f)
}

/** Deep / Light / REM / Awake swatches, so the hypnogram's colours are readable. */
@Composable
fun StageLegend(modifier: Modifier = Modifier) {
    val c = Oura.colors
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        listOf("Deep" to c.deep, "Light" to c.light, "REM" to c.rem, "Awake" to c.wake)
            .forEach { (label, colour) ->
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(
                        Modifier
                            .padding(top = 3.dp)
                            .height(8.dp)
                            .width(8.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(colour),
                    )
                    Text(label, color = c.muted, fontSize = 10.sp)
                }
            }
    }
}
