package org.openoura.android.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.openoura.android.R
import org.openoura.android.ble.SyncPhase
import org.openoura.android.data.Capability
import org.openoura.android.data.SummaryState
import org.openoura.android.ui.components.ScreenScaffold
import org.openoura.android.ui.components.ShapedIcon
import org.openoura.android.ui.components.SyncStatusCard
import org.openoura.android.ui.theme.dataColors

/**
 * The Ring tab: everything about the device rather than about you — syncing it, its
 * battery history, and what it is recording.
 */
@Composable
fun RingScreen(
    state: SummaryState,
    syncPhase: SyncPhase,
    onSync: () -> Unit,
    onDismissSync: () -> Unit,
    batteryExpanded: Boolean,
    onToggleBattery: () -> Unit,
    deviceExpanded: Boolean,
    onToggleDevice: () -> Unit,
    onToggleCapability: (Capability) -> Unit,
    busyCapability: String?,
    /** Size of the local oura.db, shown beside the event count. */
    databaseBytes: Long?,
    /** Battery alerts can't reach the user: notifications or a battery channel are off. */
    alertsOff: Boolean,
    onEnableAlerts: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val summary = (state as? SummaryState.Ready)?.summary
    val device = summary?.device
    val subtitle = listOfNotNull(
        device?.batteryPct?.let { "$it% battery" },
        device?.syncedHm?.let { "synced $it" },
    ).joinToString(" · ").ifEmpty { null }
    val syncing = syncPhase is SyncPhase.Running

    ScreenScaffold(title = "Ring", subtitle = subtitle, large = true, modifier = modifier) { padding ->
        val pull = rememberPullToRefreshState()
        PullToRefreshBox(
            isRefreshing = syncing,
            onRefresh = onSync,
            state = pull,
            indicator = {
                PullToRefreshDefaults.LoadingIndicator(
                    state = pull,
                    isRefreshing = syncing,
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            },
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = FabClearance),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(key = "sync") {
                    AnimatedVisibility(syncPhase is SyncPhase.Running || syncPhase is SyncPhase.Failed) {
                        SyncStatusCard(syncPhase, onRetry = onSync, onDismiss = onDismissSync)
                    }
                }
                if (alertsOff) {
                    item(key = "alerts-off") { AlertsOffCard(onEnableAlerts) }
                }
                if (summary == null) {
                    item {
                        Notice("Nothing synced yet. Sync with the ring, or import a database from the You tab.")
                    }
                } else {
                    item(key = "battery") {
                        BatteryPanel(
                            battery = summary.battery,
                            device = device,
                            expanded = batteryExpanded,
                            onToggle = onToggleBattery,
                        )
                    }
                    item(key = "device") {
                        DeviceHealthPanel(
                            device = device,
                            expanded = deviceExpanded,
                            onToggle = onToggleDevice,
                            onToggleCapability = onToggleCapability,
                            busyCapability = busyCapability,
                            databaseBytes = databaseBytes,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The battery warnings are notifications, and on Android 13+ notifications are off until
 * granted — so without this the 20%, 10% and 5% warnings would be dropped without a trace.
 */
@Composable
private fun AlertsOffCard(onEnable: () -> Unit) {
    val warn = MaterialTheme.dataColors.warn
    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                ShapedIcon(R.drawable.ic_sym_battery_alert, container = warn.container, content = warn.onContainer)
                Column(Modifier.weight(1f)) {
                    Text("Battery alerts are off", style = MaterialTheme.typography.titleMediumEmphasized)
                    Text(
                        "Allow notifications to hear when the ring reaches 20%, 10% and 5%, and when it's fully charged.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            FilledTonalButton(onClick = onEnable, shapes = ButtonDefaults.shapes(), modifier = Modifier.align(Alignment.End)) {
                Text("Turn on")
            }
        }
    }
}
