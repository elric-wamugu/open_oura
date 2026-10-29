package org.openoura.android.ui

import androidx.annotation.DrawableRes
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import org.openoura.android.R
import org.openoura.android.ui.theme.dataColors

/**
 * How the battery looks at each of the brain's bands: plain while `ok`, amber at 20%
 * (`low`), red at 10% and 5% (`very_low`, `critical`) — the same bands the notifications
 * fire on and the web pill colours by. Always from `battery_status`, never from a threshold
 * on the number: the number shown is the freshest reading, which a sync's radio load drags
 * down, while the band is judged on a rested one.
 */
enum class BatteryTone { Normal, Amber, Red }

fun batteryTone(status: String?): BatteryTone = when (status) {
    "low" -> BatteryTone.Amber
    "very_low", "critical" -> BatteryTone.Red
    else -> BatteryTone.Normal
}

/** A tone's colours: the mark itself, and a container with what sits on it. */
data class ToneColors(val color: Color, val container: Color, val onContainer: Color)

/**
 * Amber is the fixed data warn family; red is the scheme's error role, which stays red
 * under Material You. Null for [BatteryTone.Normal], where each surface keeps its own look.
 */
@Composable
@ReadOnlyComposable
fun BatteryTone.colors(): ToneColors? = when (this) {
    BatteryTone.Normal -> null
    BatteryTone.Amber -> MaterialTheme.dataColors.warn.let { ToneColors(it.color, it.container, it.onContainer) }
    BatteryTone.Red -> MaterialTheme.colorScheme.let { ToneColors(it.error, it.errorContainer, it.onErrorContainer) }
}

/** The battery glyph: a bolt while charging, an alert at any low band, full otherwise. */
@DrawableRes
fun batteryIcon(tone: BatteryTone, charging: Boolean): Int = when {
    charging -> R.drawable.ic_sym_battery_charging_full
    tone != BatteryTone.Normal -> R.drawable.ic_sym_battery_alert
    else -> R.drawable.ic_sym_battery_full
}
