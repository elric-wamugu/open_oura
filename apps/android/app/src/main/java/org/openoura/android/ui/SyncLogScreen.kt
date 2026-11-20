package org.openoura.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.openoura.android.sync.SyncLog
import org.openoura.android.sync.SyncLogEntry
import org.openoura.android.sync.SyncLogResult
import org.openoura.android.ui.components.SectionHeader

/**
 * You → Syncing: whether the ring is linked as Android's companion device, and every sync
 * attempt, newest first, with what woke it and how it ended.
 */
@Composable
fun SyncLogScreen(
    log: List<SyncLogEntry>,
    /** Null where the phone has no companion presence API (below Android 13). */
    linked: Boolean?,
    message: String?,
    onLink: () -> Unit,
    onUnlink: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "link") {
            Surface(shape = MaterialTheme.shapes.extraLarge, color = cs.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    when (linked) {
                        true -> {
                            Text("Ring linked to this phone", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Android lets the app sync every hour in the background, and wakes it when " +
                                    "the ring comes back into range.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = cs.onSurfaceVariant,
                            )
                            TextButton(onClick = onUnlink) { Text("Unlink") }
                        }
                        false -> {
                            Text("Sync more often", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Link the ring as this phone's companion device. Android then stops holding " +
                                    "back the hourly sync and wakes the app when the ring comes into range. " +
                                    "Android shows its own window to pick the ring.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = cs.onSurfaceVariant,
                            )
                            Button(onClick = onLink) { Text("Link the ring") }
                        }
                        null -> Text(
                            "This phone syncs every hour when Android lets it. Linking the ring needs Android 13 or later.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = cs.onSurfaceVariant,
                        )
                    }
                    message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = cs.primary) }
                }
            }
        }
        item(key = "header") {
            SectionHeader("Sync log", Modifier.padding(top = 8.dp))
            SyncLog.todaySummary(log)?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp))
            }
        }
        if (log.isEmpty()) {
            item(key = "empty") {
                Text(
                    "Nothing yet. Each sync, and each one that was skipped, shows up here with the reason.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(4.dp),
                )
            }
        } else {
            item(key = "list") {
                Surface(shape = MaterialTheme.shapes.extraLarge, color = cs.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(vertical = 4.dp)) {
                        log.forEachIndexed { i, e ->
                            if (i > 0) HorizontalDivider(color = cs.outlineVariant, modifier = Modifier.padding(horizontal = 16.dp))
                            LogRow(e)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LogRow(e: SyncLogEntry) {
    val cs = MaterialTheme.colorScheme
    val tone = when (e.result) {
        SyncLogResult.SYNCED -> cs.primary
        SyncLogResult.FAILED, SyncLogResult.STOPPED -> cs.error
        else -> cs.onSurfaceVariant
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(
            SyncLog.timeLabel(e.at),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.width(88.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(SyncLog.triggerLabel(e.trigger), style = MaterialTheme.typography.bodyMedium)
            Text(SyncLog.resultLabel(e), style = MaterialTheme.typography.bodySmall, color = tone)
        }
    }
}
