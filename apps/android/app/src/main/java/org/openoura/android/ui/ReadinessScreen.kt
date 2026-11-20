package org.openoura.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.openoura.android.data.Summary

/**
 * A day's Readiness Score and its contributors. Its own page because readiness belongs to
 * neither half of the day report: it is built from the night, the day before and the
 * weeks before that.
 */
@Composable
fun ReadinessScreen(summary: Summary, ymd: String, modifier: Modifier = Modifier) {
    val r = summary.readiness[ymd]
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val score = r?.score
        if (r == null || score == null) {
            Text("No readiness for this day: it needs a scored main sleep.", style = MaterialTheme.typography.bodyLarge)
            return@Column
        }
        ScoreCard(score, ScoreText.readiness(r.contributors))
        Text(
            "Worked out on this phone the way the Oura app does: last night against your own " +
                "baselines, which build up from the start of your history, with the day before and " +
                "the two weeks before that. A contributor the data cannot support is left out.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}
