package org.openoura.android.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.openoura.android.ble.SyncPhase
import org.openoura.android.data.Capability
import org.openoura.android.data.SummaryState
import org.openoura.android.ui.components.ScreenScaffold
import org.openoura.android.ui.components.SyncStatusCard

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
