package org.openoura.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.openoura.android.R
import org.openoura.android.data.RingKeyStore
import org.openoura.android.ui.app.LocalAppMessages
import org.openoura.android.ui.components.SectionHeader
import org.openoura.android.ui.components.ShapedIcon
import org.openoura.android.ui.theme.CodeType
import org.openoura.android.ui.theme.dataColors

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
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val messages = LocalAppMessages.current
    var entry by remember { mutableStateOf("") }
    var confirmRemove by remember { mutableStateOf(false) }
    val valid = RingKeyStore.normalize(entry) != null

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StoredKey(fingerprint, onRemove = { confirmRemove = true })

        Text(
            "The ring will not hand over any history without its 16-byte auth key. Copy the " +
                "one your desktop client already uses: the dashboard's key export shows it as " +
                "a QR code and can download it as a .key file, or take the contents of " +
                "key.hex directly.",
            style = MaterialTheme.typography.bodyMedium,
            color = cs.onSurfaceVariant,
        )
        Text(
            "Importing it here does not re-pair the ring — nothing on the ring changes, and " +
                "the desktop client goes on working. It is stored encrypted under the Android " +
                "Keystore, never as a file, and never leaves this phone.",
            style = MaterialTheme.typography.bodyMedium,
            color = cs.onSurfaceVariant,
        )

        SectionHeader(if (fingerprint == null) "Add the key" else "Replace the key", Modifier.padding(top = 4.dp))
        val typed = entry.filterNot { it.isWhitespace() || it == ':' || it == '-' }.length
        OutlinedTextField(
            value = entry,
            onValueChange = { entry = it },
            label = { Text("Paste the key") },
            supportingText = {
                Text(
                    when {
                        entry.isEmpty() -> "32 hex characters"
                        valid -> "Looks like a valid key."
                        else -> "Must be 32 hex characters — $typed so far."
                    },
                )
            },
            isError = entry.isNotEmpty() && !valid,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
            // Shown rather than masked on purpose: the user is pasting a 32-character hex
            // string they need to be able to eyeball. Once stored it is never displayed
            // again — only the fingerprint above.
            textStyle = CodeType.body,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    if (onSave(entry)) {
                        entry = ""
                        messages.show("Key stored.")
                    } else {
                        messages.show("Couldn't store that key.")
                    }
                },
                enabled = valid,
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier.weight(1f),
            ) { Text("Save key") }
            OutlinedButton(onClick = onImportFile, shapes = ButtonDefaults.shapes(), modifier = Modifier.weight(1f)) {
                Icon(painterResource(R.drawable.ic_sym_file_open), contentDescription = null, Modifier.size(18.dp))
                Text("Import .key", Modifier.padding(start = 8.dp))
            }
        }

        // Diagnostic, not the sync path. It proves the Bluetooth link end to end — scan,
        // connect, MTU, the CCCD write, a request out and a notification back — which is
        // otherwise invisible: a link that subscribes but delivers nothing looks exactly
        // like a working one until a sync mysteriously produces no events.
        SectionHeader("Connection test", Modifier.padding(top = 8.dp))
        FilledTonalButton(
            onClick = onTestConnection,
            enabled = !probing,
            shapes = ButtonDefaults.shapes(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (probing) {
                LoadingIndicator(Modifier.size(20.dp), color = LocalContentColor.current)
            } else {
                Icon(painterResource(R.drawable.ic_sym_bluetooth_searching), contentDescription = null, Modifier.size(18.dp))
            }
            Text(if (probing) "Testing…" else "Pair & test connection", Modifier.padding(start = 8.dp))
        }
        if (probeStatus != null) {
            Surface(shape = MaterialTheme.shapes.large, color = cs.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                Text(probeStatus, style = CodeType.body, modifier = Modifier.padding(14.dp))
            }
        }
        Text(
            "The test needs no key — it only asks the ring for its firmware. The first run " +
                "creates a Bluetooth pairing with the ring, which the ring requires before " +
                "it will hold a connection. That pairing is separate from the auth key and " +
                "does not affect the desktop client's.",
            style = MaterialTheme.typography.bodySmall,
            color = cs.onSurfaceVariant,
        )
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            icon = { Icon(painterResource(R.drawable.ic_sym_delete), contentDescription = null) },
            title = { Text("Remove the ring key?") },
            text = {
                Text(
                    "This phone won't be able to sync until you add the key again. Nothing " +
                        "changes on the ring, and the desktop client keeps working.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRemove()
                        confirmRemove = false
                        messages.show("Key removed.")
                    },
                    shapes = ButtonDefaults.shapes(),
                ) { Text("Remove", color = cs.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = false }, shapes = ButtonDefaults.shapes()) { Text("Cancel") }
            },
        )
    }
}

/** What is stored now: the key's fingerprint, never the key itself. */
@Composable
private fun StoredKey(fingerprint: String?, onRemove: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val warn = MaterialTheme.dataColors.warn
    Surface(shape = MaterialTheme.shapes.extraLarge, color = cs.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(start = 16.dp, top = 16.dp, bottom = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ShapedIcon(
                R.drawable.ic_sym_key,
                size = 48.dp,
                container = if (fingerprint != null) cs.primaryContainer else warn.container,
                content = if (fingerprint != null) cs.onPrimaryContainer else warn.onContainer,
            )
            Column(Modifier.weight(1f)) {
                Text(
                    if (fingerprint != null) "Key stored" else "No key stored",
                    style = MaterialTheme.typography.titleMediumEmphasized,
                )
                Text(
                    fingerprint?.let { "#$it" } ?: "Syncing needs one",
                    style = if (fingerprint != null) CodeType.body else MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant,
                )
            }
            if (fingerprint != null) {
                TextButton(onClick = onRemove, shapes = ButtonDefaults.shapes()) { Text("Remove", color = cs.error) }
            }
        }
    }
}
