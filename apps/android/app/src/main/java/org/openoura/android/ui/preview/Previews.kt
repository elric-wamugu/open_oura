package org.openoura.android.ui.preview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import org.openoura.android.R
import org.openoura.android.ble.SyncPhase
import org.openoura.android.data.SummaryState
import org.openoura.android.ui.DayCard
import org.openoura.android.ui.DeviceHealthPanel
import org.openoura.android.ui.StatusKind
import org.openoura.android.ui.StatusLevel
import org.openoura.android.ui.TileStatus
import org.openoura.android.ui.TodayScreen
import org.openoura.android.ui.VitalTile
import org.openoura.android.ui.components.SyncStatusCard
import org.openoura.android.ui.theme.OpenOuraTheme

// Light and dark previews of the pieces most worth eyeballing after a theme change. They
// render the generated brand scheme; Material You can't be previewed without a device.

@Composable
private fun Frame(content: @Composable () -> Unit) {
    OpenOuraTheme {
        Surface { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() } }
    }
}

@PreviewLightDark
@Composable
private fun VitalTilePreview() = Frame {
    VitalTile(
        label = "Resting HR", value = 59.0, unit = "bpm", icon = R.drawable.ic_sym_favorite,
        reference = 66.6, status = TileStatus("Low", StatusKind.Ok, StatusLevel.Low),
        modifier = Modifier.width(180.dp),
    )
    VitalTile(
        label = "HRV (RMSSD)", value = 31.0, unit = "ms", icon = R.drawable.ic_sym_monitor_heart,
        reference = 36.6, status = TileStatus("Low", StatusKind.Warn, StatusLevel.Low),
        modifier = Modifier.width(180.dp),
    )
}

@PreviewLightDark
@Composable
private fun DayCardPreview() = Frame {
    DayCard(PreviewFixtures.summary, PreviewFixtures.DAY, onOpenSleep = {}, onOpenActivity = {})
}

@PreviewLightDark
@Composable
private fun SyncStatusPreview() = Frame {
    SyncStatusCard(SyncPhase.Running("sync", eventsSynced = 1_840, progress = 0.42f), onRetry = {}, onDismiss = {})
    SyncStatusCard(SyncPhase.Failed("The ring refused pairing — put it on its charger and retry."), onRetry = {}, onDismiss = {})
}

@PreviewLightDark
@Composable
private fun DeviceHealthPreview() = Frame {
    DeviceHealthPanel(
        device = PreviewFixtures.device,
        expanded = true,
        onToggle = {},
        onToggleCapability = {},
        busyCapability = "spo2",
        databaseBytes = 729_436_160,
    )
}

@PreviewLightDark
@Composable
private fun TodayScreenPreview() = OpenOuraTheme {
    TodayScreen(
        state = SummaryState.Ready(PreviewFixtures.summary, stale = false),
        busy = false,
        syncPhase = SyncPhase.Idle,
        onSync = {},
        onDismissSync = {},
        onRetrySummary = {},
        onImportDatabase = {},
        onOpenDay = { _, _ -> },
        onBrowseDays = {},
        onOpenRing = {},
    )
}
