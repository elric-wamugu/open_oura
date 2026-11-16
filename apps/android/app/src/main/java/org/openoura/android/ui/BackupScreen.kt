package org.openoura.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import org.openoura.android.R
import org.openoura.android.data.BackupResult
import org.openoura.android.data.Backups
import org.openoura.android.ui.components.SectionHeader
import org.openoura.android.ui.components.ShapedIcon
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** What a finished backup or restore should say. Pure, so the wording is tested. */
internal fun describeBackup(result: BackupResult): String = when (result) {
    is BackupResult.BackedUp -> "Backed up %,d events. Keep the passphrase — the file cannot be opened without it."
        .format(result.events)
    is BackupResult.Restored -> {
        val made = if (result.createdUnix > 0) {
            " made on " + DateTimeFormatter.ofPattern("MMM d, yyyy")
                .format(Instant.ofEpochSecond(result.createdUnix).atZone(ZoneId.systemDefault()))
        } else {
            ""
        }
        if (result.added == 0L) "Everything in that backup$made was already on this phone."
        else "Restored %,d events this phone didn't have, from a backup$made of %,d.".format(result.added, result.events)
    }
    is BackupResult.Failed -> when (result.code) {
        "wrong_passphrase" -> "That passphrase doesn't open this file. Nothing was changed."
        "not_a_backup" -> "That isn't an open_oura backup file."
        "newer_format" -> "That backup was made by a newer version of open_oura. Update the app to restore it."
        "damaged" -> "The backup file is damaged or incomplete. Nothing was changed."
        else -> "It didn't work: ${result.message}"
    }
}

/**
 * Back up to, and restore from, an encrypted file.
 *
 * Its own screen for the same reason Health Connect has one: this is the one place the
 * app's data can leave the phone, so what goes where, and what protects it, is said plainly
 * before anything happens. The passphrase lives in this screen's memory only — typed, used,
 * and gone when the screen closes.
 */
@Composable
fun BackupScreen(
    hasDatabase: Boolean,
    busy: Boolean,
    /** 0..1 while working, or null when it cannot be known yet. */
    progress: Float?,
    message: String?,
    /** A backup file has been picked and is waiting for its passphrase. */
    restorePicked: Boolean,
    onBackup: (passphrase: String) -> Unit,
    onPickRestore: () -> Unit,
    onRestore: (passphrase: String) -> Unit,
    onCancelRestore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    var passphrase by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var restorePassphrase by remember { mutableStateOf("") }
    Column(
        modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ShapedIcon(R.drawable.ic_sym_database, size = 56.dp, container = cs.primaryContainer, content = cs.onPrimaryContainer)
            Text(
                "Saves everything the ring has recorded as one encrypted file, wherever you " +
                    "choose: Google Drive, another cloud, or this phone.",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            "The ring only keeps about a week, so this phone holds the only copy of everything " +
                "older. The file is encrypted here, before it goes anywhere, with a passphrase " +
                "you choose. The passphrase is not stored: without it nobody can open the " +
                "backup, including you.",
            style = MaterialTheme.typography.bodyMedium,
            color = cs.onSurfaceVariant,
        )

        if (busy) {
            Surface(shape = MaterialTheme.shapes.extraLarge, color = cs.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        progress?.let { "Working… ${(it * 100).toInt()}%" } ?: "Working…",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (progress != null) {
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    Text(
                        "Keep the app open until this finishes.",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                    )
                }
            }
        }
        message?.let {
            Surface(shape = MaterialTheme.shapes.large, color = cs.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = cs.onSecondaryContainer, modifier = Modifier.padding(16.dp))
            }
        }

        SectionHeader("Back up")
        Surface(shape = MaterialTheme.shapes.extraLarge, color = cs.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!hasDatabase) {
                    Text("Nothing to back up yet. Sync with the ring first.", color = cs.onSurfaceVariant)
                } else {
                    val problem = Backups.passphraseProblem(passphrase, confirm)
                    PassphraseField("Passphrase", passphrase, { passphrase = it }, enabled = !busy)
                    PassphraseField(
                        "Passphrase again",
                        confirm,
                        { confirm = it },
                        enabled = !busy,
                        // Only once there is something to compare: an error under an empty
                        // field is nagging, not help.
                        supporting = problem.takeIf { passphrase.isNotEmpty() },
                    )
                    Button(
                        onClick = { onBackup(passphrase) },
                        enabled = !busy && problem == null,
                        shapes = ButtonDefaults.shapes(),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Choose where to save") }
                }
            }
        }

        SectionHeader("Restore")
        Surface(shape = MaterialTheme.shapes.extraLarge, color = cs.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Adds what is in a backup to what is on this phone. Nothing already here is " +
                        "replaced, so it is safe to restore the same file twice.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant,
                )
                if (restorePicked) {
                    PassphraseField("This backup's passphrase", restorePassphrase, { restorePassphrase = it }, enabled = !busy)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { onRestore(restorePassphrase) },
                            enabled = !busy && restorePassphrase.isNotEmpty(),
                            shapes = ButtonDefaults.shapes(),
                            modifier = Modifier.weight(1f),
                        ) { Text("Restore") }
                        TextButton(onClick = onCancelRestore, enabled = !busy) { Text("Cancel") }
                    }
                } else {
                    FilledTonalButton(
                        onClick = onPickRestore,
                        enabled = !busy,
                        shapes = ButtonDefaults.shapes(),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Choose a backup file") }
                }
            }
        }

        Text(
            "The ring key is not in a backup. On a new phone, import it again on the Ring key " +
                "screen. On a computer, the same file opens with: oura restore <file>",
            style = MaterialTheme.typography.bodySmall,
            color = cs.onSurfaceVariant,
        )
    }
}

@Composable
private fun PassphraseField(
    label: String,
    value: String,
    onValue: (String) -> Unit,
    enabled: Boolean,
    supporting: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        isError = supporting != null,
        supportingText = supporting?.let { { Text(it) } },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
}
