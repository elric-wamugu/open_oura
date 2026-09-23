package org.openoura.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.openoura.android.ui.theme.Oura

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
    message: String?,
    onGrant: () -> Unit,
    onExport: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Oura.colors
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        BackHeader("Health Connect", onBack)

        Text(
            "Writes the ring's nights and daily activity into Health Connect, where any " +
                "other app you have allowed can read them — Fitbit, Samsung Health, a " +
                "training app, whatever you use.",
            color = c.muted, fontSize = 13.sp, lineHeight = 19.sp,
        )
        Text(
            "Health Connect is storage on this phone, so nothing is uploaded by exporting. " +
                "What happens next is between you and the apps you grant access to — some " +
                "of them do sync to their own servers.",
            color = c.faint, fontSize = 12.sp, lineHeight = 17.sp,
        )

        when (state) {
            HealthConnectState.UNSUPPORTED -> Text(
                "Health Connect isn't available on this device.",
                color = c.warn, fontSize = 13.sp,
            )

            HealthConnectState.NEEDS_UPDATE -> Text(
                "Health Connect needs updating from the Play Store before it can accept data.",
                color = c.warn, fontSize = 13.sp,
            )

            HealthConnectState.NEEDS_PERMISSION -> {
                Text(
                    "Permission is per data type, and this app only ever asks to write: " +
                        "sleep, resting heart rate, HRV, blood oxygen, steps and energy. " +
                        "It never asks to read, so it cannot show you a number it did not " +
                        "measure itself.",
                    color = c.faint, fontSize = 12.sp, lineHeight = 17.sp,
                )
                Button(onClick = onGrant, modifier = Modifier.fillMaxWidth()) {
                    Text("Allow writing to Health Connect")
                }
            }

            HealthConnectState.READY -> {
                Text(
                    "$nights ${if (nights == 1) "night" else "nights"} and $days " +
                        "${if (days == 1) "day" else "days"} of activity ready to export. " +
                        "Running it again is safe — each record is keyed by its date, so a " +
                        "second export corrects the first rather than duplicating it.",
                    color = c.faint, fontSize = 12.sp, lineHeight = 17.sp,
                )
                Button(onClick = onExport, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text(if (busy) "Exporting…" else "Export to Health Connect")
                }
            }
        }

        message?.let {
            Text(it, color = c.muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace, lineHeight = 17.sp)
        }

        Text(
            "The raw event stream stays here. Health Connect takes the nights and the daily " +
                "totals — the shape other apps can actually read — not two million protocol " +
                "frames, and it is not a backup of your database.",
            color = c.faint, fontSize = 11.sp, lineHeight = 16.sp,
        )
    }
}

@Composable
private fun BackHeader(title: String, onBack: () -> Unit) {
    val c = Oura.colors
    androidx.compose.foundation.layout.Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        OutlinedButton(onClick = onBack) { Text("‹ Back") }
        Text(title, color = c.text, fontSize = 17.sp, fontFamily = FontFamily.Monospace)
    }
}
