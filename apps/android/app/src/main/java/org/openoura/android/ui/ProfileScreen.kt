package org.openoura.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.openoura.android.data.RingProfile
import org.openoura.android.ui.theme.Oura

/**
 * Age, sex, height and weight — the inputs the ring cannot measure.
 *
 * These are not cosmetic: they drive the Jackson VO₂max estimate, the Tanaka predicted
 * HR max, and the Schofield BMR that becomes total daily energy. Left at the defaults,
 * every one of those figures describes a generic 30-year-old rather than you.
 */
@Composable
fun ProfileScreen(
    initial: RingProfile,
    ringKeyFingerprint: String?,
    onSave: (RingProfile) -> Unit,
    onRingKey: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Oura.colors
    BackHandler(onBack = onBack)

    var age by remember { mutableStateOf(fmt(initial.age)) }
    var height by remember { mutableStateOf(fmt(initial.height_m)) }
    var weight by remember { mutableStateOf(fmt(initial.weight_kg)) }
    var sex by remember { mutableStateOf(initial.sex.uppercase().take(1).ifEmpty { "M" }) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "‹ Back",
                color = c.muted,
                fontSize = 13.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .border(1.dp, c.line, RoundedCornerShape(999.dp))
                    .clickable(onClick = onBack)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
            Text("Profile", color = c.text, fontSize = 15.sp, fontFamily = FontFamily.Monospace)
        }

        Text(
            "The ring can't measure these. They feed the VO₂max estimate, the predicted " +
                "maximum heart rate, and the resting-energy figure behind total kcal — so " +
                "leaving them at the defaults makes those numbers describe someone else. " +
                "They stay on this device.",
            color = c.muted,
            fontSize = 12.sp,
        )

        Field("Age (years)", age, { age = it })
        Field("Height (m)", height, { height = it })
        Field("Weight (kg)", weight, { weight = it })

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Sex", color = c.muted, fontSize = 13.sp)
            listOf("M", "F").forEach { opt ->
                val on = sex == opt
                Text(
                    opt,
                    color = if (on) c.text else c.muted,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(if (on) c.accentSoft else c.surface)
                        .border(1.dp, if (on) c.accent else c.line, RoundedCornerShape(999.dp))
                        .clickable { sex = opt }
                        .padding(horizontal = 16.dp, vertical = 7.dp),
                )
            }
        }

        Button(
            onClick = {
                onSave(
                    RingProfile(
                        sex = sex,
                        age = age.toDoubleOrNull() ?: initial.age,
                        height_m = height.toDoubleOrNull() ?: initial.height_m,
                        weight_kg = weight.toDoubleOrNull() ?: initial.weight_kg,
                        ring_size = initial.ring_size,
                    ),
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Save and recompute") }

        // Device setup lives here too rather than in the top bar, which is already carrying
        // battery, sync and this screen's own button. When Phase 4 adds a real sync screen
        // the ring key belongs next to it, and this row can point there instead.
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(c.surface)
                .border(1.dp, c.line, RoundedCornerShape(10.dp))
                .clickable(onClick = onRingKey)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Ring key", color = c.text, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    ringKeyFingerprint?.let { "#$it" } ?: "not set",
                    color = if (ringKeyFingerprint != null) c.accent else c.muted,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                )
                Text("›", color = c.muted, fontSize = 15.sp)
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { s -> onChange(s.filter { it.isDigit() || it == '.' }) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun fmt(v: Double): String =
    if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()
