package org.openoura.android.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.painterResource
import org.openoura.android.ui.components.ExpandablePanel
import org.openoura.android.ui.components.SectionHeader
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
 * The Ring tab: everything about the device rather than about you — its battery, what it is
 * recording, what has come in, and its identity. Laid out as a bento since 2026-11-20: the
 * battery as the tall tile, the ring's features as switch rows, and the detail behind rows.
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
    /** The "Synced 23:44" chip opens the sync log. */
    onOpenSyncLog: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val summary = (state as? SummaryState.Ready)?.summary
    val device = summary?.device
    val subtitle = device?.firmware?.let { "Firmware $it" }
    val syncing = syncPhase is SyncPhase.Running
    var insightsOpen by rememberSaveable { mutableStateOf(false) }
    var streamsOpen by rememberSaveable { mutableStateOf(false) }

    ScreenScaffold(
        title = "Ring",
        subtitle = subtitle,
        large = true,
        // When it last synced lives here now that Today no longer says; the chip opens the log.
        actions = {
            device?.syncedHm?.let { hm ->
                AssistChip(
                    onClick = onOpenSyncLog,
                    label = { Text("Synced $hm") },
                    leadingIcon = {
                        Icon(painterResource(R.drawable.ic_sym_sync), contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize))
                    },
                    shape = CircleShape,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
        },
        modifier = modifier,
    ) { padding ->
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
                    item(key = "bento") { RingBento(device, summary.battery, databaseBytes) }
                    item(key = "charge") { ChargeTile(summary.battery, batteryExpanded, onToggleBattery) }
                    if (device != null) {
                        if (device.measuring.isNotEmpty()) {
                            item(key = "recording") {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    SectionHeader("Recording on the ring")
                                    Capabilities(device, onToggleCapability, busyCapability)
                                    Text(
                                        "Each switch writes to the ring, which takes about fifteen seconds.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 4.dp),
                                    )
                                }
                            }
                        }
                        if (device.streams.isNotEmpty()) {
                            item(key = "data") {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    SectionHeader("Data captured")
                                    DataTile(device, streamsOpen) { streamsOpen = !streamsOpen }
                                }
                            }
                        }
                        if (device.insights.isNotEmpty()) {
                            item(key = "insights") {
                                val live = device.insights.count { !it.gated }
                                val gated = device.insights.size - live
                                ExpandablePanel(
                                    icon = R.drawable.ic_sym_info,
                                    title = "Insights",
                                    summary = listOfNotNull("$live live", if (gated > 0) "$gated need more" else null).joinToString(" · "),
                                    expanded = insightsOpen,
                                    onToggle = { insightsOpen = !insightsOpen },
                                ) { Insights(device) }
                            }
                        }
                        item(key = "device") {
                            ExpandablePanel(
                                icon = R.drawable.ic_sym_database,
                                title = "Device details",
                                summary = "ID, firmware, hardware, sync cursor",
                                expanded = deviceExpanded,
                                onToggle = onToggleDevice,
                            ) {
                                ShortPeriodsNote(device)
                                Identity(device)
                            }
                        }
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
