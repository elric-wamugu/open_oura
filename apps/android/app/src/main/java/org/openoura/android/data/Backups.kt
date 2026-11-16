package org.openoura.android.data

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import uniffi.oura_core.BackupProgressListener
import uniffi.oura_core.backupToFd
import uniffi.oura_core.restoreFromFd
import java.io.File
import java.time.LocalDate

private const val TAG = "OpenOuraBackup"

/** How a backup or a restore ended. */
sealed interface BackupResult {
    data class BackedUp(val events: Long) : BackupResult

    /** [added] of the backup's [events] were not already on this phone. */
    data class Restored(val events: Long, val added: Long, val createdUnix: Long) : BackupResult

    /** [code] is the core's: `wrong_passphrase`, `not_a_backup`, `newer_format`, `damaged`, `failed`. */
    data class Failed(val code: String, val message: String) : BackupResult
}

/**
 * Encrypted backups, written and read by the Rust core (`oura-backup`) so the desktop
 * `oura restore` reads exactly what the phone writes.
 *
 * The destination is whatever the system file picker returns — Drive, another cloud, local
 * storage — reached through its file descriptor, so this app needs no network permission
 * and no account of its own: the provider the user picked does the uploading. The core
 * writes the file without seeking, which is what makes a cloud provider's pipe usable.
 *
 * A manual backup's passphrase is never stored: it is asked for each time and handed
 * straight to the core. Only the automatic backup keeps one — see [BackupSecretStore].
 */
object Backups {

    /** Short enough to type on a phone, long enough that Argon2id makes guessing it slow. */
    const val MIN_PASSPHRASE = 8

    /** The automatic backup's one file, replaced daily — so no date in the name. */
    const val AUTO_FILE_NAME = "open_oura-auto.ourabak"

    fun fileName(today: LocalDate = LocalDate.now()): String = "open_oura-$today.ourabak"

    /** Why this passphrase cannot be used yet, or null when it can. */
    fun passphraseProblem(passphrase: String, confirm: String): String? = when {
        passphrase.length < MIN_PASSPHRASE -> "At least $MIN_PASSPHRASE characters"
        passphrase != confirm -> "The two don't match"
        else -> null
    }

    /** The core's JSON reply as a result. Pure, so the mapping is tested without a ring. */
    fun parse(reply: String, restoring: Boolean): BackupResult = runCatching {
        val o = Json.parseToJsonElement(reply).jsonObject
        fun long(key: String) = o[key]?.jsonPrimitive?.longOrNull ?: 0L
        val error = o["error"]?.jsonPrimitive?.content
        when {
            error != null -> BackupResult.Failed(o["code"]?.jsonPrimitive?.content ?: "failed", error)
            restoring -> BackupResult.Restored(long("events"), long("events_added"), long("created_unix"))
            else -> BackupResult.BackedUp(long("events"))
        }
    }.getOrElse { BackupResult.Failed("failed", "Unexpected reply from the core") }

    private fun dbPath(ctx: Context) = File(ctx.filesDir, "oura.db").absolutePath

    private fun listener(onProgress: (Float?) -> Unit) = object : BackupProgressListener {
        override fun onProgress(done: ULong, total: ULong) {
            onProgress(if (total > 0u) (done.toFloat() / total.toFloat()).coerceIn(0f, 1f) else null)
        }
    }

    suspend fun backup(ctx: Context, to: Uri, passphrase: String, onProgress: (Float?) -> Unit): BackupResult =
        run(restoring = false, open = { ctx.contentResolver.openFileDescriptor(to, "wt") }) { fd ->
            backupToFd(dbPath(ctx), passphrase, fd, listener(onProgress))
        }

    /**
     * Back up into a plain file. The automatic backup writes here first and copies the
     * finished file to its destination, so a backup that fails part-way never replaces the
     * good one already there.
     */
    suspend fun backupToFile(ctx: Context, to: File, passphrase: String, onProgress: (Float?) -> Unit): BackupResult =
        run(
            restoring = false,
            open = {
                ParcelFileDescriptor.open(
                    to,
                    ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or
                        ParcelFileDescriptor.MODE_TRUNCATE,
                )
            },
        ) { fd -> backupToFd(dbPath(ctx), passphrase, fd, listener(onProgress)) }

    suspend fun restore(ctx: Context, from: Uri, passphrase: String, onProgress: (Float?) -> Unit): BackupResult =
        run(restoring = true, open = { ctx.contentResolver.openFileDescriptor(from, "r") }) { fd ->
            restoreFromFd(dbPath(ctx), passphrase, fd, listener(onProgress))
        }

    private suspend fun run(
        restoring: Boolean,
        open: () -> ParcelFileDescriptor?,
        call: (Int) -> String,
    ): BackupResult = withContext(Dispatchers.IO) {
        runCatching {
            val pfd = requireNotNull(open()) { "could not open the file" }
            // Detached: the core owns the descriptor from here and closes it when done,
            // which for a cloud provider is also what tells it the upload is complete.
            parse(call(pfd.detachFd()), restoring)
        }.getOrElse {
            Log.e(TAG, if (restoring) "restore failed" else "backup failed", it)
            BackupResult.Failed("failed", it.message ?: it.toString())
        }.also { Log.i(TAG, "${if (restoring) "restore" else "backup"}: ${describe(it)}") }
    }

    /** For the log: outcomes and counts, never the passphrase or the destination. */
    private fun describe(r: BackupResult) = when (r) {
        is BackupResult.BackedUp -> "${r.events} events"
        is BackupResult.Restored -> "${r.added} of ${r.events} events new"
        is BackupResult.Failed -> "failed (${r.code})"
    }
}
