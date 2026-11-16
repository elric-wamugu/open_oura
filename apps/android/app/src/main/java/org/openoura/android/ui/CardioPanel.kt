package org.openoura.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.openoura.android.R
import org.openoura.android.data.Summary
import org.openoura.android.ui.components.ExpandablePanel
import org.openoura.android.ui.theme.chartColors
import kotlin.math.abs
import kotlin.math.roundToInt

/** How many of the newest nights the resting-HR sparkline shows. */
private const val SPARK_NIGHTS = 30

/** "8.3 bpm below your 64.3 bpm baseline", or null without both figures. */
internal fun restingVsBaseline(latest: Double?, baseline: Double?): String? {
    if (latest == null || baseline == null) return null
    val delta = (((latest - baseline) * 10).roundToInt() / 10.0)
    val base = trim(baseline)
    return if (delta <= 0) "${trim(abs(delta))} bpm below your $base bpm baseline"
    else "${trim(delta)} bpm above your $base bpm baseline"
}

/** "3 years older than" / "in line with": a vascular age against the real one, as the web's `relAge`. */
internal fun vascularVsAge(vascular: Double, chronological: Double): String {
    val diff = vascular - chronological
    val years = abs((diff * 10).roundToInt() / 10.0)
    val unit = if (years == 1.0) "year" else "years"
    return when {
        diff < -0.05 -> "${trim(years)} $unit younger than"
        diff > 0.05 -> "${trim(years)} $unit older than"
        else -> "in line with"
    }
}

private fun trim(v: Double): String =
    if (v == v.roundToInt().toDouble()) v.roundToInt().toString() else ((v * 10).roundToInt() / 10.0).toString()

/**
 * The Cardiovascular panel, mirroring the web's `renderCardio` and `renderVascular`.
 *
 * Resting heart rate leads, because it is the one fitness number here that responds to
 * training. VO₂max is a demographic estimate and says so; vascular age needs a model that
 * is not bundled, and says that rather than leaving a hole. The wording is the web's.
 */
@Composable
fun CardioPanel(
    summary: Summary,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val rhr = summary.vitals.rhr
    val latest = rhr.latest
    val versus = restingVsBaseline(latest, rhr.baseline)
    ExpandablePanel(
        icon = R.drawable.ic_sym_monitor_heart,
        title = "Cardiovascular",
        summary = latest?.let { "${trim(it)} bpm resting" } ?: "Needs a scored night",
        expanded = expanded,
        onToggle = onToggle,
        modifier = modifier,
    ) {
        if (latest == null) {
            Text(
                "Resting heart rate needs a scored night — it's measured from the ring's overnight beat intervals.",
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onSurfaceVariant,
            )
        } else {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(trim(latest), style = MaterialTheme.typography.displaySmallEmphasized)
                Text(
                    "bpm resting",
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            Text(
                if (versus == null) {
                    "Measured overnight from the ring's own beat intervals."
                } else {
                    "${versus.replaceFirstChar { it.uppercase() }}. Measured overnight, and it falls as " +
                        "conditioning improves — the fitness signal on this page that responds to training."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onSurfaceVariant,
            )
            val nights = rhr.series.takeLast(SPARK_NIGHTS)
            if (nights.size > 1) {
                val colors = chartColors()
                // The trend chart, fed one point per night: the brain's series has no dates,
                // and none are needed to see which way it is heading.
                val window = TrendWindow(
                    points = nights.mapIndexed { i, v -> TrendPoint(i.toLong(), v) },
                    endDay = nights.size - 1L,
                    days = nights.size,
                    mean = rhr.baseline,
                    previousMean = null,
                )
                Surface(shape = MaterialTheme.shapes.large, color = cs.surfaceContainerHigh) {
                    Canvas(
                        Modifier
                            .fillMaxWidth()
                            .height(84.dp)
                            .padding(12.dp)
                            .semantics {
                                contentDescription = "Resting heart rate over the last ${nights.size} nights, " +
                                    "from ${trim(nights.min())} to ${trim(nights.max())} bpm."
                            },
                    ) { drawTrend(window, bars = false, line = colors.hr, grid = colors.grid) }
                }
                Text(
                    "Last ${nights.size} nights" + (rhr.baseline?.let { " · dashed line: your ${trim(it)} bpm baseline" } ?: ""),
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.onSurfaceVariant,
                )
            }
        }

        summary.fitness?.vo2max?.let { vo2 ->
            Fact("VO₂max · demographic baseline", "${trim(vo2)} ml/kg/min")
            Text(
                "The VO₂max figure is a Jackson non-exercise estimate computed from age, sex and weight " +
                    "alone — no ring data reaches it, so it shifts only when you age or change weight, " +
                    "never with training.",
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
            )
        }

        val cv = summary.cardio
        val vascular = cv?.vascularAge
        if (vascular == null) {
            Fact("Vascular age", "needs Oura's CVA model")
            Text(
                "Cardiovascular age needs Oura's CVA model, which isn't bundled. The raw PPG is " +
                    "captured — scoring it requires that model.",
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
            )
        } else {
            Fact("Vascular age", "${trim(vascular)} years")
            cv.chronologicalAge?.let { age ->
                Text(
                    "${vascularVsAge(vascular, age).replaceFirstChar { it.uppercase() }} your age (${trim(age)}).",
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                )
            }
            Fact("Pulse-wave velocity", cv.pwvMs?.let { "${trim(it)} m/s" } ?: "—")
            cv.segments?.let { Fact("Segments analysed", "$it") }
        }
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleSmallEmphasized)
        }
    }
}
