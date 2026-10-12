package org.openoura.android.ui

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
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import org.openoura.android.R
import org.openoura.android.data.RingProfile
import org.openoura.android.ui.components.ScreenScaffold
import org.openoura.android.ui.components.SectionHeader
import org.openoura.android.ui.components.ShapedIcon
import org.openoura.android.ui.theme.ThemePrefs

/** The You tab: your profile, the ring's key, Health Connect, and how the app looks. */
@Composable
fun YouScreen(
    profile: RingProfile,
    ringKeyFingerprint: String?,
    onSaveProfile: (RingProfile) -> Unit,
    onRingKey: () -> Unit,
    onHealthConnect: () -> Unit,
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
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ProfileScreen(
                initial = profile,
                ringKeyFingerprint = ringKeyFingerprint,
                onSave = onSaveProfile,
                onRingKey = onRingKey,
                onHealthConnect = onHealthConnect,
            )
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionHeader("Appearance")
                AppearanceRow(dynamicColor, onDynamicColor)
            }
        }
    }
}

@Composable
private fun AppearanceRow(dynamicColor: Boolean, onDynamicColor: (Boolean) -> Unit) {
    val supported = ThemePrefs.dynamicSupported
    SegmentedListItem(
        shapes = ListItemDefaults.segmentedShapes(index = 0, count = 1),
        supportingContent = {
            Text(
                if (supported) {
                    "Use Material You colours from your wallpaper instead of the open_oura teal. " +
                        "Sleep-stage colours stay the same either way."
                } else {
                    "Needs Android 12 or newer."
                },
            )
        },
        leadingContent = { ShapedIcon(R.drawable.ic_sym_palette) },
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
