package org.openoura.android.ui

import android.content.pm.ApplicationInfo
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import org.openoura.android.R
import org.openoura.android.alerts.AlertKind
import org.openoura.android.data.RingProfile
import org.openoura.android.ui.components.ScreenScaffold
import org.openoura.android.ui.components.SectionHeader
import org.openoura.android.ui.components.ShapedIcon
import org.openoura.android.ui.theme.CodeType
import org.openoura.android.ui.theme.ThemePrefs

/** The You tab: your profile, the ring's key and your data, and how the app looks. */
@Composable
fun YouScreen(
    profile: RingProfile,
    ringKeyFingerprint: String?,
    onSaveProfile: (RingProfile) -> Unit,
    onRingKey: () -> Unit,
    onHealthConnect: () -> Unit,
    onBackup: () -> Unit,
    onImportDatabase: () -> Unit,
    /** Each alert's switch, and whether Android lets the app notify at all. */
    alerts: Map<AlertKind, Boolean>,
    notificationsAllowed: Boolean,
    onAlert: (AlertKind, Boolean) -> Unit,
    onNotificationSettings: () -> Unit,
    dynamicColor: Boolean,
    onDynamicColor: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    ScreenScaffold(title = "You", large = true, modifier = modifier) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SectionHeader("Profile")
            ProfileCard(initial = profile, onSave = onSaveProfile)

            SectionHeader("Ring & data", Modifier.padding(top = 8.dp))
            Group(
                listOf(
                    LinkRow(
                        icon = R.drawable.ic_sym_key,
                        title = "Ring key",
                        detail = ringKeyFingerprint?.let { "#$it" } ?: "Not set — the ring won't sync without it",
                        code = ringKeyFingerprint != null,
                        onClick = onRingKey,
                    ),
                    LinkRow(
                        icon = R.drawable.ic_sym_health_and_safety,
                        title = "Health Connect",
                        detail = "Share nights, vitals and activity with other apps",
                        onClick = onHealthConnect,
                    ),
                    LinkRow(
                        icon = R.drawable.ic_sym_database,
                        title = "Backup",
                        detail = "Save an encrypted copy, or restore one",
                        onClick = onBackup,
                    ),
                    LinkRow(
                        icon = R.drawable.ic_sym_file_open,
                        title = "Import database",
                        detail = "Load history from an oura.db file",
                        onClick = onImportDatabase,
                        chevron = false,
                    ),
                ),
            )

            SectionHeader("Notifications", Modifier.padding(top = 8.dp))
            AlertRows(alerts, notificationsAllowed, onAlert, onNotificationSettings)

            SectionHeader("Appearance", Modifier.padding(top = 8.dp))
            AppearanceRow(dynamicColor, onDynamicColor)

            SectionHeader("About", Modifier.padding(top = 8.dp))
            val version = rememberVersion()
            Group(
                listOf(
                    LinkRow(
                        icon = R.drawable.ic_sym_info,
                        title = "open_oura${version?.let { " $it" } ?: ""}",
                        detail = "Everything here is computed on this device. Nothing left it.",
                        onClick = null,
                        chevron = false,
                    ),
                ),
            )
        }
    }
}

private data class LinkRow(
    @param:DrawableRes val icon: Int,
    val title: String,
    val detail: String,
    val onClick: (() -> Unit)?,
    val code: Boolean = false,
    val chevron: Boolean = true,
)

/** A run of rows as one Expressive segmented group. */
@Composable
private fun Group(rows: List<LinkRow>) {
    Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
        rows.forEachIndexed { i, r ->
            val shapes = ListItemDefaults.segmentedShapes(i, rows.size)
            val supporting: @Composable () -> Unit = {
                Text(r.detail, style = if (r.code) CodeType.body else MaterialTheme.typography.bodyMedium)
            }
            val leading: @Composable () -> Unit = { ShapedIcon(r.icon) }
            val trailing: (@Composable () -> Unit)? = if (r.chevron) {
                { Icon(painterResource(R.drawable.ic_sym_chevron_right), contentDescription = null) }
            } else {
                null
            }
            if (r.onClick != null) {
                SegmentedListItem(
                    onClick = r.onClick,
                    shapes = shapes,
                    colors = groupColors(),
                    leadingContent = leading,
                    supportingContent = supporting,
                    trailingContent = trailing,
                ) { Text(r.title) }
            } else {
                SegmentedListItem(
                    shapes = shapes,
                    colors = groupColors(),
                    leadingContent = leading,
                    supportingContent = supporting,
                    trailingContent = trailing,
                ) { Text(r.title) }
            }
        }
    }
}

@Composable
private fun AppearanceRow(dynamicColor: Boolean, onDynamicColor: (Boolean) -> Unit) {
    val supported = ThemePrefs.dynamicSupported
    SegmentedListItem(
        onClick = { if (supported) onDynamicColor(!dynamicColor) },
        enabled = supported,
        shapes = ListItemDefaults.segmentedShapes(index = 0, count = 1),
        colors = groupColors(),
        leadingContent = { ShapedIcon(R.drawable.ic_sym_palette) },
        supportingContent = {
            Text(
                if (supported) {
                    "Use Material You colours from your wallpaper instead of the open_oura teal. " +
                        "Sleep-stage and status colours stay the same either way."
                } else {
                    "Needs Android 12 or newer."
                },
            )
        },
        trailingContent = { CheckSwitch(dynamicColor, onDynamicColor, enabled = supported) },
    ) { Text("Wallpaper colours") }
}

/** A switch that shows a tick in its thumb while on. */
@Composable
private fun CheckSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        thumbContent = if (checked) {
            {
                Icon(
                    painterResource(R.drawable.ic_sym_check),
                    contentDescription = null,
                    modifier = Modifier.size(SwitchDefaults.IconSize),
                )
            }
        } else {
            null
        },
    )
}

@DrawableRes
private fun alertIcon(kind: AlertKind): Int = when (kind) {
    AlertKind.BATTERY_LOW -> R.drawable.ic_sym_battery_alert
    AlertKind.BATTERY_FULL -> R.drawable.ic_sym_battery_charging_full
    AlertKind.NIGHT_FORECAST -> R.drawable.ic_sym_bedtime
    AlertKind.SYNC_OVERDUE -> R.drawable.ic_sym_sync
}

/**
 * One switch per alert, then the way into Android's own settings — where sound and
 * importance live, and where a blocked app is unblocked. The switches stay usable while
 * Android blocks notifications, so the last row says so rather than greying everything out.
 */
@Composable
private fun AlertRows(
    alerts: Map<AlertKind, Boolean>,
    notificationsAllowed: Boolean,
    onAlert: (AlertKind, Boolean) -> Unit,
    onNotificationSettings: () -> Unit,
) {
    val kinds = AlertKind.entries
    val count = kinds.size + 1
    Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
        kinds.forEachIndexed { i, kind ->
            val on = alerts[kind] != false
            SegmentedListItem(
                onClick = { onAlert(kind, !on) },
                shapes = ListItemDefaults.segmentedShapes(i, count),
                colors = groupColors(),
                leadingContent = { ShapedIcon(alertIcon(kind)) },
                supportingContent = { Text(kind.detail) },
                trailingContent = { CheckSwitch(on, { onAlert(kind, it) }) },
            ) { Text(kind.title) }
        }
        SegmentedListItem(
            onClick = onNotificationSettings,
            shapes = ListItemDefaults.segmentedShapes(count - 1, count),
            colors = groupColors(),
            leadingContent = {
                ShapedIcon(if (notificationsAllowed) R.drawable.ic_sym_info else R.drawable.ic_sym_error)
            },
            supportingContent = {
                Text(
                    if (notificationsAllowed) {
                        "Sound, vibration and importance for each alert"
                    } else {
                        "Android is blocking notifications from open_oura, so none of these can be shown."
                    },
                )
            },
            trailingContent = { Icon(painterResource(R.drawable.ic_sym_chevron_right), contentDescription = null) },
        ) { Text(if (notificationsAllowed) "Android notification settings" else "Notifications are blocked") }
    }
}

/** Rows sit on the container tone, so a group reads as one block against the page. */
@Composable
internal fun groupColors() = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)

@Composable
private fun rememberVersion(): String? {
    val context = LocalContext.current
    return remember {
        // Both builds carry the same version and install over each other, so the debug one
        // says what it is: it is the slower of the two, and the only one `run-as` works on.
        val debug = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
            ?.let { if (debug) "$it (debug)" else it }
    }
}
