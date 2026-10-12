package org.openoura.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.openoura.android.data.RingProfile
import org.openoura.android.ui.components.ChoiceOption
import org.openoura.android.ui.components.ConnectedChoice

/**
 * Age, sex, height and weight — the inputs the ring cannot measure.
 *
 * These are not cosmetic: they drive the Jackson VO₂max estimate, the Tanaka predicted
 * HR max, and the Schofield BMR that becomes total daily energy. Left at the defaults,
 * every one of those figures describes a generic 30-year-old rather than you.
 */
@Composable
fun ProfileCard(
    initial: RingProfile,
    onSave: (RingProfile) -> Unit,
    modifier: Modifier = Modifier,
) {
    var age by rememberSaveable { mutableStateOf(fmt(initial.age)) }
    var height by rememberSaveable { mutableStateOf(fmt(initial.height_m)) }
    var weight by rememberSaveable { mutableStateOf(fmt(initial.weight_kg)) }
    var sex by rememberSaveable { mutableStateOf(initial.sex.uppercase().take(1).ifEmpty { "M" }) }

    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                "The ring can't measure these. They feed the VO₂max estimate, the predicted " +
                    "maximum heart rate, and the resting-energy figure behind total kcal — so " +
                    "leaving them at the defaults makes those numbers describe someone else. " +
                    "They stay on this device.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            ConnectedChoice(
                options = listOf(ChoiceOption("Male"), ChoiceOption("Female")),
                selected = if (sex == "F") 1 else 0,
                onSelect = { sex = if (it == 1) "F" else "M" },
                modifier = Modifier.fillMaxWidth(),
            )

            Field("Age", age, "years", { age = it }, Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Field("Height", height, "m", { height = it }, Modifier.weight(1f))
                Field("Weight", weight, "kg", { weight = it }, Modifier.weight(1f), last = true)
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
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save and recompute") }
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    unit: String,
    onChange: (String) -> Unit,
    modifier: Modifier,
    last: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { s -> onChange(s.filter { it.isDigit() || it == '.' }) },
        label = { Text(label) },
        suffix = { Text(unit) },
        singleLine = true,
        shape = MaterialTheme.shapes.large,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Decimal,
            imeAction = if (last) ImeAction.Done else ImeAction.Next,
        ),
        modifier = modifier,
    )
}

private fun fmt(v: Double): String =
    if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()
