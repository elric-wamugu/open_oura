package org.openoura.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import org.openoura.android.R
import org.openoura.android.health.HealthExport
import org.openoura.android.ui.components.SectionHeader
import org.openoura.android.ui.components.ShapedIcon
import org.openoura.android.ui.theme.dataColors

/** What the screen needs to know about Health Connect's state on this phone. */
enum class HealthConnectState { READY, NEEDS_PERMISSION, NEEDS_UPDATE, UNSUPPORTED }

/**
 * Publish the ring's history to Health Connect.
 *
 * Its own screen rather than a switch, because this one deserves explaining. Health
 * Connect is on-device, so exporting does not contradict the footer — but it does hand the
 * data to whatever else the user has installed, and that is a decision worth making with
 * the facts in front of them rather than by flipping something.
 */
@Composable
fun HealthConnectScreen(
    state: HealthConnectState,
    nights: Int,
    days: Int,
    busy: Boolean,
    onGrant: () -> Unit,
    onExport: () -> Unit,
    /** Whether a finished ring sync also publishes what it brought in. */
    autoExport: Boolean = true,
    onAutoExport: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ShapedIcon(
                R.drawable.ic_sym_health_and_safety,
                size = 56.dp,
                container = cs.primaryContainer,
                content = cs.onPrimaryContainer,
            )
            Text(
                "Writes the ring's nights and daily activity into Health Connect, where any " +
                    "other app you have allowed can read them.",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            "Health Connect is storage on this phone, so nothing is uploaded by exporting. " +
                "What happens next is between you and the apps you grant access to — some " +
                "of them do sync to their own servers.",
            style = MaterialTheme.typography.bodyMedium,
            color = cs.onSurfaceVariant,
        )

        when (state) {
            HealthConnectState.UNSUPPORTED -> Problem("Health Connect isn't available on this device.")

            HealthConnectState.NEEDS_UPDATE ->
                Problem("Health Connect needs updating from the Play Store before it can accept data.")

            HealthConnectState.NEEDS_PERMISSION -> Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = cs.surfaceContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(
                        "Permission is per data type, and this app only ever asks to write: " +
                            "sleep, resting heart rate, HRV, blood oxygen, steps and energy. " +
                            "It never asks to read, so it cannot show you a number it did not " +
                            "measure itself.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = cs.onSurfaceVariant,
                    )
                    Button(onClick = onGrant, shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth()) {
                        Text("Allow writing to Health Connect")
                    }
                }
            }

            HealthConnectState.READY -> {
                SectionHeader("Automatic", Modifier.padding(top = 4.dp))
                SegmentedListItem(
                    onClick = { onAutoExport(!autoExport) },
                    shapes = ListItemDefaults.segmentedShapes(index = 0, count = 1),
                    colors = groupColors(),
                    leadingContent = { ShapedIcon(R.drawable.ic_sym_sync) },
                    supportingContent = {
                        Text(
                            "Publish each sync's new nights and days, the last " +
                                "${HealthExport.RECENT_DAYS} days at a time.",
                        )
                    },
                    trailingContent = {
                        Switch(
                            checked = autoExport,
                            onCheckedChange = onAutoExport,
                            thumbContent = if (autoExport) {
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
                ) { Text("Keep in sync") }

                SectionHeader("Everything", Modifier.padding(top = 8.dp))
                Surface(shape = MaterialTheme.shapes.extraLarge, color = cs.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text(
                            "$nights ${if (nights == 1) "night" else "nights"} and $days " +
                                "${if (days == 1) "day" else "days"} in total. Export everything to " +
                                "backfill history the ring can no longer reach — running it again is " +
                                "safe, since each record is keyed by its date and a second export " +
                                "corrects the first rather than duplicating it.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = cs.onSurfaceVariant,
                        )
                        Button(
                            onClick = onExport,
                            enabled = !busy,
                            shapes = ButtonDefaults.shapes(),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            if (busy) {
                                LoadingIndicator(Modifier.size(20.dp), color = LocalContentColor.current)
                                Text("Exporting…", Modifier.padding(start = 8.dp))
                            } else {
                                Text("Export everything now")
                            }
                        }
                    }
                }
            }
        }

        Text(
            "The raw event stream stays here. Health Connect takes the nights and the daily " +
                "totals — the shape other apps can actually read — not two million protocol " +
                "frames, and it is not a backup of your database.",
            style = MaterialTheme.typography.bodySmall,
            color = cs.onSurfaceVariant,
        )
    }
}

@Composable
private fun Problem(text: String) {
    val warn = MaterialTheme.dataColors.warn
    Surface(shape = MaterialTheme.shapes.extraLarge, color = warn.container, contentColor = warn.onContainer) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(painterResource(R.drawable.ic_sym_error), contentDescription = null)
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
