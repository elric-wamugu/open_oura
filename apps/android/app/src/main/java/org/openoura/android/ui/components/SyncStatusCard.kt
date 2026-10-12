package org.openoura.android.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import org.openoura.android.R
import org.openoura.android.ble.SyncPhase
import org.openoura.android.ble.describeSync

/**
 * A sync in flight, or one that failed. A success is announced by a snackbar instead —
 * it is read at a glance — while a failure has to be read properly and acted on, so it
 * stays on the page with Retry until dismissed or until it times out.
 */
@Composable
fun SyncStatusCard(
    phase: SyncPhase,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    when (phase) {
        is SyncPhase.Running -> Card(
            modifier = modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerHigh),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    LoadingIndicator(Modifier.size(40.dp))
                    Column {
                        Text("Syncing with the ring", style = MaterialTheme.typography.titleMedium)
                        Text(describeSync(phase), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                    }
                }
                // Determinate once the ring has said how much it holds; indeterminate
                // through connect, auth and setup, where there is nothing to measure.
                val progress = phase.progress
                if (progress != null) {
                    LinearWavyProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearWavyProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        }

        is SyncPhase.Failed -> Card(
            modifier = modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = cs.errorContainer, contentColor = cs.onErrorContainer),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(painterResource(R.drawable.ic_sym_error), contentDescription = null)
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Sync didn't finish", style = MaterialTheme.typography.titleMedium)
                        Text(phase.message, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) {
                        Text("Dismiss", color = cs.onErrorContainer)
                    }
                    Button(
                        onClick = onRetry,
                        shapes = ButtonDefaults.shapes(),
                        colors = ButtonDefaults.buttonColors(containerColor = cs.error, contentColor = cs.onError),
                    ) { Text("Retry") }
                }
            }
        }

        else -> Unit
    }
}
