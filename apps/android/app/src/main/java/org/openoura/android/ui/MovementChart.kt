package org.openoura.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.openoura.android.ui.theme.Oura
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The 24-hour movement chart with a touch scrubber — the polysomnograph's affordance
 * applied to the day. Drag across it and the header reports that 15-minute bucket's step
 * count and intensity; let go and it returns to the day totals.
 *
 * Mirrors `metProfileChart` in the web client, including the "estimated, not counted"
 * caption: steps come from a MET→step-rate heuristic, so they land on multiples of 105
 * and a bare "525 steps" would read as a counted figure.
 */
@Composable
fun MovementChart(
    prof: List<Double>,
    steps: List<Double>,
    dayTotalSteps: Double?,
    modifier: Modifier = Modifier,
) {
    val c = Oura.colors
    var cursor by remember(prof) { mutableStateOf<Int?>(null) }

    val peak = maxOf(1.0, prof.maxOrNull() ?: 1.0)
    val bucketMin = if (prof.isEmpty()) 15.0 else 24.0 * 60.0 / prof.size

    val header = cursor?.let { i ->
        val t0 = (i * bucketMin).roundToInt()
        val t1 = ((i + 1) * bucketMin).roundToInt() % 1440
        val s = steps.getOrNull(i) ?: 0.0
        val stepText = if (s > 0) "${s.roundToInt()} steps" else "no steps"
        "%02d:%02d–%02d:%02d · %s · %.2f MET".format(t0 / 60, t0 % 60, t1 / 60, t1 % 60, stepText, prof[i])
    } ?: "${(dayTotalSteps ?: 0.0).roundToInt()} steps · peak %.1f MET".format(prof.maxOrNull() ?: 0.0)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(c.surface)
            .border(1.dp, c.line, RoundedCornerShape(10.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(header, color = c.muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)

        Canvas(
            Modifier
                .fillMaxWidth()
                .height(120.dp)
                .pointerInput(prof) {
                    // Both gestures resolve to the nearest bucket; release clears it.
                    detectDragGestures(
                        onDragEnd = { cursor = null },
                        onDragCancel = { cursor = null },
                    ) { change, _ ->
                        change.consume()
                        cursor = bucketAt(change.position.x, size.width, prof.size)
                    }
                }
                .pointerInput(prof) {
                    detectTapGestures(
                        onPress = { off ->
                            cursor = bucketAt(off.x, size.width, prof.size)
                            tryAwaitRelease()
                            cursor = null
                        },
                    )
                },
        ) {
            if (prof.size < 2) return@Canvas
            val stepX = size.width / (prof.size - 1)
            fun yOf(v: Double) = (6f + (1 - min(1.0, v / peak)) * (size.height - 12f)).toFloat()

            for (hr in 0..24 step 6) {
                val x = size.width * hr / 24f
                drawLine(c.lineSoft, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
            }

            val line = Path().apply {
                moveTo(0f, yOf(prof[0]))
                for (i in 1 until prof.size) lineTo(i * stepX, yOf(prof[i]))
            }
            val area = Path().apply {
                addPath(line)
                lineTo(size.width, size.height); lineTo(0f, size.height); close()
            }
            drawPath(area, c.accent.copy(alpha = 0.14f))
            drawPath(line, c.accent, style = Stroke(width = 1.8f))

            cursor?.let { i ->
                val x = i * stepX
                drawLine(c.accent.copy(alpha = 0.75f), Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.5f)
                drawCircle(c.accent, radius = 4.5f, center = Offset(x, yOf(prof[i])))
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf(0, 6, 12, 18, 24).forEach {
                Text("%02d".format(it), color = c.faint, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
            }
        }

        Text(
            "Steps are estimated from movement intensity per 15-minute bucket, so they land " +
                "on coarse multiples. The day total is the dependable number.",
            color = c.faint,
            fontSize = 10.sp,
        )
    }
}

private fun bucketAt(x: Float, width: Int, count: Int): Int {
    if (count < 2 || width <= 0) return 0
    val f = (x / width).coerceIn(0f, 1f)
    return (f * (count - 1)).roundToInt().coerceIn(0, count - 1)
}
