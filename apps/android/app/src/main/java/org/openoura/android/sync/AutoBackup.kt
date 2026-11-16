package org.openoura.android.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.openoura.android.R
import org.openoura.android.alerts.ALERT_AMBER
import org.openoura.android.alerts.AlertKind
import org.openoura.android.alerts.AlertPrefs
import org.openoura.android.alerts.Alerts
import org.openoura.android.data.BackupResult
import org.openoura.android.data.BackupSecretStore
import org.openoura.android.data.Backups
import org.openoura.android.ui.nav.Tab
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

private const val TAG = "OpenOuraBackup"
private const val CHANNEL = "backup_failed"
private const val NOTIF = 1006
private const val WORK = "auto_backup"

/** What the Backup screen shows about the automatic backup. */
data class AutoBackupState(
    val enabled: Boolean = false,
    /** The destination file's name, as its provider reports it. */
    val destination: String? = null,
    val lastOkUnix: Long = 0,
    val lastEvents: Long = 0,
    val lastError: String? = null,
    val failStreak: Int = 0,
    /** A backup is being written right now, by the button or by the nightly job. */
    val running: Boolean = false,
)

/**
 * A daily encrypted backup to one file the user picked once.
 *
 * **One file, overwritten.** The system file picker grants lasting access to a document,
 * not to a folder in Google Drive (Drive does not offer folders to
 * `ACTION_OPEN_DOCUMENT_TREE`), so there is exactly one destination and each run replaces
 * it. That is why a backup is complete rather than incremental, and why it is built in the
 * app's cache first and only then copied over: a run that fails while reading the database
 * leaves yesterday's file as it was.
 *
 * **The provider uploads, not this app.** There is still no `INTERNET` permission. The
 * worker waits for charging and an unmetered network so that the provider's own upload,
 * which follows the write, most likely goes over Wi-Fi — but when and how it uploads is the
 * provider's decision, and this app cannot see it. "Backed up" here means "handed over".
 *
 * **Daily is enough.** The ring holds about eight and a half days, so a phone lost between
 * backups loses nothing the ring still has.
 */
object AutoBackup {

    /** Complain after this many failed days in a row; one bad night is not news. */
    const val NOTIFY_STREAK = 3

    /** Failures only the user can fix. These are said the first time. */
    val NEEDS_USER = setOf("no_access", "no_passphrase")

    private const val PREFS = "auto_backup"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_URI = "uri"
    private const val KEY_NAME = "name"
    private const val KEY_LAST_OK = "last_ok_unix"
    private const val KEY_LAST_EVENTS = "last_events"
    private const val KEY_LAST_ERROR = "last_error"
    private const val KEY_FAIL_STREAK = "fail_streak"

    /** The worker and the "Back up now" button must not write the same file at once. */
    private val running = Mutex()

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val live = MutableStateFlow<AutoBackupState?>(null)

    /**
     * The state, kept current. A backup finishes on its own schedule — overnight in the
     * worker, or after the screen that started it has been recreated — so the screen
     * watches this rather than reading once.
     */
    fun states(ctx: Context): StateFlow<AutoBackupState?> {
        if (live.value == null) publish(ctx)
        return live
    }

    private fun publish(ctx: Context, running: Boolean = false) {
        live.value = state(ctx).copy(running = running)
    }

    fun state(ctx: Context): AutoBackupState = prefs(ctx).let {
        AutoBackupState(
            enabled = it.getBoolean(KEY_ENABLED, false),
            destination = it.getString(KEY_NAME, null),
            lastOkUnix = it.getLong(KEY_LAST_OK, 0),
            lastEvents = it.getLong(KEY_LAST_EVENTS, 0),
            lastError = it.getString(KEY_LAST_ERROR, null),
            failStreak = it.getInt(KEY_FAIL_STREAK, 0),
        )
    }

    /**
     * Whether there is anything new to back up: the database changed after the last backup
     * began. Judged on the file's modification time so that deciding costs nothing — the
     * alternative is opening a gigabyte database to count rows, every night.
     */
    fun shouldRun(dbModifiedUnix: Long?, lastOkUnix: Long): Boolean =
        dbModifiedUnix != null && (lastOkUnix == 0L || dbModifiedUnix >= lastOkUnix)

    /** Whether this failure is worth a notification. Once per streak, never per failure. */
    fun shouldNotify(code: String, failStreak: Int): Boolean =
        if (code in NEEDS_USER) failStreak == 1 else failStreak == NOTIFY_STREAK

    /** The line under the switch. Pure, so the wording is tested. */
    fun describe(s: AutoBackupState, zone: ZoneId = ZoneId.systemDefault()): String {
        val last = if (s.lastOkUnix > 0) {
            val at = DateTimeFormatter.ofPattern("MMM d, HH:mm").format(Instant.ofEpochSecond(s.lastOkUnix).atZone(zone))
            "Last backup $at, %,d events.".format(s.lastEvents)
        } else {
            "No backup yet."
        }
        return if (s.lastError != null) "$last The latest try failed: ${s.lastError}" else last
    }

    /**
     * Switch it on for [uri], which must have just come back from the file picker. Returns
     * what went wrong, or null.
     */
    fun enable(ctx: Context, uri: Uri, passphrase: String): String? {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        // Without this the grant ends with the process, and the first scheduled run fails.
        runCatching { ctx.contentResolver.takePersistableUriPermission(uri, flags) }.onFailure {
            Log.w(TAG, "destination refused lasting access", it)
            return "That location doesn't let the app keep access to the file. Choose another."
        }
        if (!BackupSecretStore(ctx).save(passphrase)) {
            release(ctx, uri)
            return "The passphrase could not be stored on this phone."
        }
        // A new destination: whatever was held for the old one is no longer needed.
        prefs(ctx).getString(KEY_URI, null)?.toUri()?.takeIf { it != uri }?.let { release(ctx, it) }
        prefs(ctx).edit {
            clear()
            putBoolean(KEY_ENABLED, true)
            putString(KEY_URI, uri.toString())
            putString(KEY_NAME, displayName(ctx, uri))
        }
        publish(ctx)
        schedule(ctx)
        return null
    }

    /** Switch it off and forget the passphrase. The file already written is left alone. */
    fun disable(ctx: Context) {
        WorkManager.getInstance(ctx).cancelUniqueWork(WORK)
        prefs(ctx).getString(KEY_URI, null)?.toUri()?.let { release(ctx, it) }
        BackupSecretStore(ctx).clear()
        prefs(ctx).edit { clear() }
        publish(ctx)
        ctx.getSystemService(NotificationManager::class.java)?.cancel(NOTIF)
    }

    /** Idempotent; called on every launch so an update or a reboot cannot lose the schedule. */
    fun schedule(ctx: Context) {
        if (!state(ctx).enabled) return
        val req = PeriodicWorkRequestBuilder<AutoBackupWorker>(24, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiresCharging(true)
                    .setRequiredNetworkType(NetworkType.UNMETERED)
                    .build(),
            )
            .build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    /**
     * Write a backup now. [force] skips the "nothing changed" check, for the button.
     * Returns null when there was nothing new to back up.
     */
    suspend fun run(ctx: Context, force: Boolean, onProgress: (Float?) -> Unit = {}): BackupResult? = running.withLock {
        val prefs = prefs(ctx)
        if (!prefs.getBoolean(KEY_ENABLED, false)) return null
        val started = System.currentTimeMillis() / 1000
        if (!force && !shouldRun(dbModifiedUnix(ctx), prefs.getLong(KEY_LAST_OK, 0))) {
            Log.i(TAG, "automatic backup: nothing new since the last one")
            return null
        }
        publish(ctx, running = true)
        val result = try {
            write(ctx, prefs.getString(KEY_URI, null)?.toUri(), onProgress)
        } catch (e: Throwable) {
            publish(ctx)
            throw e
        }
        when (result) {
            is BackupResult.BackedUp -> {
                prefs.edit {
                    putLong(KEY_LAST_OK, started)
                    putLong(KEY_LAST_EVENTS, result.events)
                    remove(KEY_LAST_ERROR)
                    putInt(KEY_FAIL_STREAK, 0)
                }
                ctx.getSystemService(NotificationManager::class.java)?.cancel(NOTIF)
            }
            is BackupResult.Failed -> {
                val streak = prefs.getInt(KEY_FAIL_STREAK, 0) + 1
                prefs.edit {
                    putString(KEY_LAST_ERROR, result.message.take(200))
                    putInt(KEY_FAIL_STREAK, streak)
                }
                if (shouldNotify(result.code, streak)) notifyFailure(ctx, result, prefs.getLong(KEY_LAST_OK, 0))
            }
            is BackupResult.Restored -> Unit
        }
        publish(ctx)
        Log.i(TAG, "automatic backup: " + if (result is BackupResult.BackedUp) "${result.events} events handed over" else "failed")
        result
    }

    private suspend fun write(ctx: Context, uri: Uri?, onProgress: (Float?) -> Unit): BackupResult {
        val held = uri != null && ctx.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }
        if (uri == null || !held) {
            return BackupResult.Failed("no_access", "The app no longer has access to the backup file. Choose it again.")
        }
        val passphrase = BackupSecretStore(ctx).read()
            ?: return BackupResult.Failed("no_passphrase", "The stored passphrase can't be read. Set automatic backup up again.")
        val tmp = File(ctx.cacheDir, "auto-backup.ourabak")
        try {
            val built = Backups.backupToFile(ctx, tmp, passphrase, onProgress)
            if (built !is BackupResult.BackedUp) return built
            return withContext(Dispatchers.IO) {
                runCatching {
                    // "wt": replace, never append. Closing the stream is what tells a cloud
                    // provider the file is complete and may be uploaded.
                    val out = requireNotNull(ctx.contentResolver.openOutputStream(uri, "wt")) { "could not open the backup file" }
                    out.use { o -> tmp.inputStream().use { it.copyTo(o, 1 shl 16) } }
                    built
                }.getOrElse {
                    Log.e(TAG, "copying the backup to its destination failed", it)
                    BackupResult.Failed("failed", it.message ?: it.toString())
                }
            }
        } finally {
            tmp.delete()
        }
    }

    private fun dbModifiedUnix(ctx: Context): Long? {
        val db = File(ctx.filesDir, "oura.db")
        if (!db.exists()) return null
        // Fresh writes sit in the WAL until a checkpoint, so the newer of the two counts.
        return maxOf(db.lastModified(), File(ctx.filesDir, "oura.db-wal").lastModified()) / 1000
    }

    private fun displayName(ctx: Context, uri: Uri): String? = runCatching {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull()

    private fun release(ctx: Context, uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { ctx.contentResolver.releasePersistableUriPermission(uri, flags) }
    }

    /** Title and text. Pure, so the wording is tested. */
    fun describeFailure(result: BackupResult.Failed, lastOkUnix: Long, nowUnix: Long): Pair<String, String> {
        val days = if (lastOkUnix > 0) (nowUnix - lastOkUnix) / 86_400 else -1
        val since = when {
            days < 0 -> "No automatic backup has been written yet."
            days <= 1 -> "The last good backup is from the past day."
            else -> "The last good backup is $days days old."
        }
        return "Automatic backup isn't working" to "$since ${result.message}"
    }

    private fun notifyFailure(ctx: Context, result: BackupResult.Failed, lastOkUnix: Long) {
        if (!AlertPrefs.enabled(ctx, AlertKind.BACKUP_FAILED)) return
        val manager = ctx.getSystemService(NotificationManager::class.java)
        if (manager == null || !Alerts.canPost(ctx, CHANNEL)) {
            Log.w(TAG, "automatic backup failing — warning held: notifications are off")
            return
        }
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Automatic backup failing", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "Shown when the daily backup can't be written." },
        )
        val (title, text) = describeFailure(result, lastOkUnix, System.currentTimeMillis() / 1000)
        val notification: Notification = NotificationCompat.Builder(ctx, CHANNEL)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setColor(ALERT_AMBER)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            // Backup lives under the You tab.
            .setContentIntent(Alerts.open(ctx, Tab.YOU))
            .build()
        manager.notify(NOTIF, notification)
    }
}

/** One scheduled backup. About 25 s for 5.6 million events, well inside a worker's budget. */
class AutoBackupWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        // No retry: the next day's run is the retry, and each try counts towards the
        // streak that decides when to say something.
        AutoBackup.run(applicationContext, force = false)
        return Result.success()
    }
}
