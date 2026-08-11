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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.openoura.android.data.RingKeyStore
import org.openoura.android.ui.theme.Oura

/**
 * Import the ring's auth key from the desktop client.
 *
 * This is a transfer screen, not a pairing screen — and the difference matters. Pairing
 * (`set_auth_key`) installs a *new* key and is only valid on a factory-reset ring, so
 * running it here would mean resetting the ring and losing its onboarding. Copying the
 * existing key across changes nothing on the ring: the desktop client keeps working, and
 * both clients can sync it independently.
 */
@Composable
fun RingKeyScreen(
    fingerprint: String?,
    onSave: (String) -> Boolean,
    onImportFile: () -> Unit,
    onRemove: () -> Unit,
    probing: Boolean,
    probeStatus: String?,
    onTestConnection: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Oura.colors
    BackHandler(onBack = onBack)

    var entry by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var confirmRemove by remember { mutableStateOf(false) }
    val valid = RingKeyStore.normalize(entry) != null

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
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
            Text("Ring key", color = c.text, fontSize = 15.sp, fontFamily = FontFamily.Monospace)
        }

        Text(
            "The ring will not hand over any history without its 16-byte auth key. Copy the " +
                "one your desktop client already uses: the dashboard's key export shows it as " +
                "a QR code and can download it as a .key file, or take the contents of " +
                "key.hex directly.",
            color = c.muted,
            fontSize = 12.sp,
        )
        Text(
            "Importing it here does not re-pair the ring — nothing on the ring changes, and " +
                "the desktop client goes on working. It is stored encrypted under the Android " +
                "Keystore, never as a file, and never leaves this phone.",
            color = c.muted,
            fontSize = 12.sp,
        )

        StoredKeyRow(fingerprint)

        OutlinedTextField(
            value = entry,
            onValueChange = { entry = it; status = null },
            label = { Text("Paste the key (32 hex characters)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
            // Shown rather than masked on purpose: the user is pasting a 32-character hex
            // string they need to be able to eyeball. Once stored it is never displayed
            // again — only the fingerprint above.
            textStyle = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                color = c.text,
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        val typed = entry.filterNot { it.isWhitespace() || it == ':' || it == '-' }.length
        val hint = when {
            entry.isEmpty() -> null
            valid -> "Looks like a valid key."
            else -> "Must be 32 hex characters — $typed so far."
        }
        if (hint != null || status != null) {
            Text(
                status ?: hint.orEmpty(),
                color = if (status != null || valid) c.accent else c.muted,
                fontSize = 12.sp,
            )
        }

        Button(
            onClick = {
                status = if (onSave(entry)) {
                    entry = ""
                    "Key stored."
                } else {
                    "Couldn't store that key."
                }
            },
            enabled = valid,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Save key") }

        OutlinedButton(onClick = onImportFile, modifier = Modifier.fillMaxWidth()) {
            Text("Import a .key file…")
        }

        if (fingerprint != null) {
            OutlinedButton(
                onClick = {
                    if (confirmRemove) {
                        onRemove()
                        confirmRemove = false
                        status = "Key removed."
                    } else {
                        confirmRemove = true
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (confirmRemove) "Tap again to remove the key" else "Remove key")
            }
        }

        // Diagnostic, not the sync path. It proves the Bluetooth link end to end — scan,
        // connect, MTU, the CCCD write, a request out and a notification back — which is
        // otherwise invisible: a link that subscribes but delivers nothing looks exactly
        // like a working one until a sync mysteriously produces no events.
        OutlinedButton(
            onClick = onTestConnection,
            enabled = !probing,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (probing) "Testing…" else "Test ring connection")
        }
        if (probeStatus != null) {
            Text(probeStatus, color = c.muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        }

        Text(
            "The connection test needs no key — it only asks the ring for its firmware. " +
                "Syncing history does need one, and is still to come.",
            color = c.faint,
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun StoredKeyRow(fingerprint: String?) {
    val c = Oura.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(c.surface)
            .border(1.dp, c.line, RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (fingerprint != null) "Key stored" else "No key stored",
            color = if (fingerprint != null) c.text else c.muted,
            fontSize = 13.sp,
        )
        Text(
            fingerprint?.let { "#$it" } ?: "—",
            color = if (fingerprint != null) c.accent else c.muted,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}
