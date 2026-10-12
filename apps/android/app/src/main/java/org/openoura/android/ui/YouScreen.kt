package org.openoura.android.ui

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
    onImportDatabase: () -> Unit,
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
                        icon = R.drawable.ic_sym_file_open,
                        title = "Import database",
                        detail = "Load history from an oura.db file",
                        onClick = onImportDatabase,
                        chevron = false,
                    ),
                ),
            )

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
        trailingContent = {
            Switch(
                checked = dynamicColor,
                onCheckedChange = onDynamicColor,
                enabled = supported,
                thumbContent = if (dynamicColor) {
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
        },
    ) { Text("Wallpaper colours") }
}

/** Rows sit on the container tone, so a group reads as one block against the page. */
@Composable
internal fun groupColors() = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)

@Composable
private fun rememberVersion(): String? {
    val context = LocalContext.current
    return remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
    }
}
