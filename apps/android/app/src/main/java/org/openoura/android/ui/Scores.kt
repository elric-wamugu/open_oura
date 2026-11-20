package org.openoura.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.openoura.android.data.ActivityScoreContributors
import org.openoura.android.data.ReadinessContributors

/** Contributor names and order for the three scores. Pure, so tested. */
object ScoreText {
    /** The app's order for readiness; a contributor at 0 had no data and is left out. */
    fun readiness(c: ReadinessContributors): List<Pair<String, Int>> = listOfNotNull(
        c.restingHeartRate?.let { "Resting heart rate" to it },
        c.hrvBalance?.let { "HRV balance" to it },
        c.bodyTemperature?.let { "Body temperature" to it },
        c.recoveryIndex?.let { "Recovery index" to it },
        c.previousNight?.let { "Sleep" to it },
        c.sleepBalance?.let { "Sleep balance" to it },
        c.sleepRegularity?.let { "Sleep regularity" to it },
        c.previousDayActivity?.let { "Previous day activity" to it },
        c.activityBalance?.let { "Activity balance" to it },
    ).filter { it.second > 0 }

    fun activity(c: ActivityScoreContributors): List<Pair<String, Int>> = listOfNotNull(
        c.stayActive?.let { "Stay active" to it },
        c.moveEveryHour?.let { "Move every hour" to it },
        c.meetDailyTargets?.let { "Meet daily targets" to it },
        c.trainingFrequency?.let { "Training frequency" to it },
        c.trainingVolume?.let { "Training volume" to it },
        c.recoveryTime?.let { "Recovery time" to it },
    )
}

/** A score with its word, then each contributor as a bar out of 100. */
@Composable
fun ScoreCard(total: Int, contributors: List<Pair<String, Int>>, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Surface(shape = MaterialTheme.shapes.extraLarge, color = cs.surfaceContainer, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("$total", style = MaterialTheme.typography.displaySmallEmphasized)
                Text(
                    SleepInterpretation.scoreLabel(total),
                    style = MaterialTheme.typography.titleMedium,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            contributors.forEach { (name, value) ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth()) {
                        Text(name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Text("$value", style = MaterialTheme.typography.labelLarge)
                    }
                    LinearProgressIndicator(
                        progress = { value / 100f },
                        modifier = Modifier.fillMaxWidth(),
                        drawStopIndicator = {},
                    )
                }
            }
        }
    }
}
