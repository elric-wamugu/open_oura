package org.openoura.android.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import uniffi.oura_core.summaryJson

private const val TAG = "OpenOura"

/** What the UI is currently looking at. */
sealed interface SummaryState {
    data object Loading : SummaryState
    data class Ready(val summary: Summary, val stale: Boolean) : SummaryState
    data class Empty(val reason: String) : SummaryState
    data class Failed(val message: String) : SummaryState
}

/**
 * Single source of truth for the summary.
 *
 * `build_summary` is expensive — ~3 s over 846k events on an M2, and 10–20 s on the
 * Pixel 5's Snapdragon 765G, growing with history. So it never runs on the UI path:
 * the parsed result is cached as JSON on disk, the UI renders that instantly on cold
 * start, and a refresh recomputes in the background. Widgets (Phase 3) read a much
 * smaller projection and must never call the core at all.
 */
class SummaryRepository(private val ctx: Context) {

    private val json = Json {
        ignoreUnknownKeys = true   // the Rust core may add fields; never break on them
        isLenient = true
    }

    private val _state = MutableStateFlow<SummaryState>(SummaryState.Loading)
    val state: StateFlow<SummaryState> = _state.asStateFlow()

    private val dbFile: File get() = File(ctx.filesDir, "oura.db")
    private val cacheFile: File get() = File(ctx.filesDir, "summary.json")

    val hasDatabase: Boolean get() = dbFile.exists() && dbFile.length() > 0

    /** Local UTC offset in whole hours — what `summary_json` wants for day bucketing. */
    private fun tzOffsetHours(): Long =
        (java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 3_600_000).toLong()

    /**
     * Show the cached snapshot immediately (marked stale), then recompute if asked.
     * Returns once the freshest available state has been emitted.
     */
    suspend fun load(refresh: Boolean) {
        val cached = readCache()
        if (cached != null) _state.value = SummaryState.Ready(cached, stale = true)
        else if (!refresh) _state.value = SummaryState.Empty("No summary computed yet.")

        if (!hasDatabase) {
            if (cached == null) {
                _state.value = SummaryState.Empty(
                    "No ring database on this device yet.\n\n" +
                        "Import an oura.db synced by the desktop client, or (for development) " +
                        "push one straight into app storage:\n\n" +
                        "adb push oura.db /data/local/tmp/oura.db\n" +
                        "adb shell run-as org.openoura.android cp /data/local/tmp/oura.db files/oura.db"
                )
            }
            return
        }
        if (refresh || cached == null) recompute()
    }

    /** Runs the Rust core off the main thread and replaces the cache. */
    suspend fun recompute() {
        if (!hasDatabase) {
            _state.value = SummaryState.Empty("No database on the device yet.")
            return
        }
        if (_state.value is SummaryState.Empty) _state.value = SummaryState.Loading

        val result = withContext(Dispatchers.Default) {
            runCatching {
                val started = System.nanoTime()
                val raw = summaryJson(dbFile.absolutePath, tzOffsetHours())
                val elapsedMs = (System.nanoTime() - started) / 1_000_000
                Log.i(TAG, "summary_json: ${raw.length / 1024} KB in ${elapsedMs} ms")
                val parsed = json.decodeFromString<Summary>(raw)
                // Only cache what parsed cleanly, so a bad write can't poison cold start.
                cacheFile.writeText(raw)
                parsed
            }
        }

        result.fold(
            onSuccess = { s ->
                // The core reports its own failures in-band rather than throwing.
                val err = s.error
                if (err == null) {
                    // Widgets read this projection and never call the core, so it has to be
                    // written here — the one place we know the summary is fresh and valid.
                    WidgetStore.write(ctx, WidgetStore.from(s))
                    org.openoura.android.widget.WidgetUpdates.refreshAll(ctx)
                }
                _state.value = if (err != null) SummaryState.Failed(err)
                else SummaryState.Ready(s, stale = false)
            },
            onFailure = { e ->
                Log.e(TAG, "summary refresh failed", e)
                val cached = readCache()
                _state.value = if (cached != null) SummaryState.Ready(cached, stale = true)
                else SummaryState.Failed(e.message ?: e.toString())
            },
        )
    }

    private fun readCache(): Summary? = runCatching {
        if (!cacheFile.exists()) return null
        json.decodeFromString<Summary>(cacheFile.readText())
    }.onFailure { Log.w(TAG, "discarding unreadable summary cache", it) }.getOrNull()

    /**
     * Copy a desktop-synced database into app-private storage, which is where the Rust
     * core needs a plain filesystem path.
     *
     * This goes through the Storage Access Framework rather than reading
     * `/sdcard/Download` directly: since scoped storage (Android 11+) an app cannot open
     * arbitrary paths in shared storage, and `READ_EXTERNAL_STORAGE` no longer grants it.
     * The user picks the file, which also means no storage permission is needed at all.
     *
     * Superseded in Phase 4, when BLE sync writes the database itself.
     */
    suspend fun importFrom(uri: android.net.Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val tmp = File(ctx.filesDir, "oura.db.importing")
            ctx.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "could not open $uri" }
                tmp.outputStream().use { input.copyTo(it) }
            }
            // Swap in only once the copy completed, so an interrupted import can't leave
            // a truncated database that the core would fail on.
            if (!tmp.renameTo(dbFile)) {
                tmp.copyTo(dbFile, overwrite = true)
                tmp.delete()
            }
            Log.i(TAG, "imported ${dbFile.length() / 1024 / 1024} MB database")
            true
        }.getOrElse {
            Log.e(TAG, "database import failed", it)
            false
        }
    }
}
