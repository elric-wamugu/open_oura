package org.openoura.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.openoura.android.R
import org.openoura.android.ble.HeartReading
import org.openoura.android.ble.HeartState
import org.openoura.android.ble.LiveHeartRate
import org.openoura.android.ui.theme.chartColors

/**
 * Measuring heart rate now.
 *
 * One screen for the whole of it — looking for the ring, the live figure, the result —
 * because it is half a minute of one thing, and the number that matters is in the same
 * place throughout.
 */
@Composable
fun HeartRateScreen(
    state: HeartState,
    last: HeartReading?,
    /** Last night's resting rate, to set the reading against. */
    restingBpm: Int?,
    onStart: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val heart = chartColors().hr
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(painterResource(R.drawable.ic_sym_favorite), contentDescription = null, tint = heart, modifier = Modifier.size(36.dp))
        // The reading on show: live while measuring, the result after, the last one before.
        val shown = when (state) {
            is HeartState.Measuring -> state.bpm
            is HeartState.Done -> state.reading.bpm
            else -> last?.bpm
        }
        Text(shown?.toString() ?: "—", style = MaterialTheme.typography.displayLargeEmphasized)
        Text(
            when (state) {
                is HeartState.Measuring -> if (state.bpm == null) "Waiting for a beat…" else "bpm, live"
                is HeartState.Done -> "bpm average over ${state.reading.seconds} s"
                is HeartState.Connecting -> "Looking for the ring…"
                else -> last?.let { "bpm, ${LiveHeartRate.ago(it.atUnix, System.currentTimeMillis() / 1000)}" } ?: "No reading yet"
            },
            style = MaterialTheme.typography.bodyLarge,
            color = cs.onSurfaceVariant,
        )

        when (state) {
            is HeartState.Connecting -> {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Note("Keep the ring on and close to the phone.")
                FilledTonalButton(onClick = onStop, shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
            }
            is HeartState.Measuring -> {
                Trace(state.trace, heart, Modifier.fillMaxWidth().height(96.dp))
                LinearProgressIndicator(
                    progress = { state.elapsedSec.toFloat() / state.totalSec },
                    modifier = Modifier.fillMaxWidth(),
                )
                Note("${(state.totalSec - state.elapsedSec).coerceAtLeast(0)} s left. Keep your hand still.")
                FilledTonalButton(onClick = onStop, shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth()) { Text("Stop") }
            }
            is HeartState.Done -> {
                val r = state.reading
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Tile("Low", r.low.toString(), Modifier.weight(1f))
                    Tile("High", r.high.toString(), Modifier.weight(1f))
                    Tile("Resting", restingBpm?.toString() ?: "—", Modifier.weight(1f))
                }
                LiveHeartRate.againstResting(r.bpm, restingBpm)?.let { Note(it) }
                Button(onClick = onStart, shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth()) { Text("Measure again") }
            }
            is HeartState.Failed -> {
                Surface(shape = MaterialTheme.shapes.large, color = cs.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
                    Text(state.message, style = MaterialTheme.typography.bodyMedium, color = cs.onSecondaryContainer, modifier = Modifier.padding(16.dp))
                }
                Button(onClick = onStart, shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth()) { Text("Try again") }
            }
            HeartState.Idle -> {
                Note("Wear the ring and keep your hand still. It takes ${LiveHeartRate.SECONDS} seconds.")
                Button(onClick = onStart, shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth()) { Text("Measure") }
            }
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun Tile(label: String, value: String, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Surface(shape = MaterialTheme.shapes.large, color = cs.surfaceContainer, modifier = modifier) {
        Column(Modifier.padding(vertical = 12.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleLarge)
        }
    }
}

/**
 * The reading as it has moved during this measurement. Drawn against its own range, with
 * a floor on that range so a steady pulse reads as a flat line rather than as noise.
 */
@Composable
private fun Trace(values: List<Int>, color: Color, modifier: Modifier = Modifier) {
    val spoken = if (values.isEmpty()) "No beats yet" else "Heart rate so far, from ${values.min()} to ${values.max()} bpm"
    Canvas(modifier.semantics { contentDescription = spoken }) {
        if (values.size < 2) return@Canvas
        val lo = values.min()
        val hi = values.max()
        val pad = ((12 - (hi - lo)) / 2f).coerceAtLeast(2f)
        val yMin = lo - pad
        val yMax = hi + pad
        val step = size.width / (values.size - 1)
        val path = Path()
        values.forEachIndexed { i, v ->
            val x = i * step
            val y = size.height - (v - yMin) / (yMax - yMin) * size.height
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        val last = values.last()
        drawCircle(color, 4.dp.toPx(), Offset(size.width, size.height - (last - yMin) / (yMax - yMin) * size.height))
    }
}
