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
import java.util.concurrent.atomic.AtomicInteger
import uniffi.oura_core.clearEffortLabel
import uniffi.oura_core.relabelSummaryJson
import uniffi.oura_core.setEffortLabel
import uniffi.oura_core.summaryJsonStaged

private const val TAG = "OpenOura"

/**
 * How long one recompute may spend staging nights with the on-device sleep stager
 * (`oura-summary`'s `staging.rs`). Staged nights are kept in the database, so this
 * only bounds the nights not yet staged: the newest goes first, and a history is
 * worked through over later recomputes, the ring's own hypnogram standing in until
 * then.
 */
internal const val STAGE_BUDGET_MS = 10_000L

/**
 * Whether a cached summary can still be trusted, given when everything happened.
 *
 * Two ways it goes bad, and they are not the same failure. Both have already happened.
 *
 * The database moving under it is the obvious one: a sync that does not reach the end never
 * recomputes, and a background drain stopped mid-flight on 2026-09-18 left the cursor
 * 234,743 ahead of a summary still showing the previous evening. Current-looking and wrong
 * is worse than visibly behind.
 *
 * The second is the code that *produced* it being replaced. `oura-summary` gained
 * `start_unix` on 2026-09-23 and the first Health Connect export wrote zero sleep sessions,
 * because the cache predated the field and nothing about the database had changed — so a
 * comparison of those two files saw nothing wrong. The app's own update time catches it,
 * and catches it without anyone having to remember to bump a constant when the brain's
 * shape changes, which is the version of this that would eventually have failed quietly.
 *
 * The cost is one recompute after each app update, which is the right trade: a new build is
 * precisely when a summary is most likely to come out different.
 */
internal fun summaryCacheIsStale(cacheWrittenAt: Long, dbModifiedAt: Long, appUpdatedAt: Long) =
    dbModifiedAt > cacheWrittenAt || appUpdatedAt > cacheWrittenAt

/**
 * One log line for a recompute: the core's own stages slowest-first, then what it cost on
 * top of them.
 *
 * `total` is the core's wall clock inside `build_summary`; [coreMs] is the same call timed
 * from here, so the gap between them — "other" — is serialising the summary to a string
 * and carrying it across the FFI. [parseMs] is this side turning that string back into
 * objects. Every part of the wait gets a number, because the split is what decides the
 * fix: a slow `read_events` wants less read off disk, a slow stage wants fewer passes, and
 * a slow parse wants a smaller payload.
 */
internal fun describeRecompute(kb: Int, coreMs: Long, parseMs: Long, stages: Map<String, Double>): String {
    val total = stages["total"]
    val parts = stages.filterKeys { it != "total" }.entries
        .sortedByDescending { it.value }
        .joinToString(", ") { "${it.key} ${it.value.toLong()}" }
    val other = total?.let { "; other ${(coreMs - it.toLong()).coerceAtLeast(0)}" }.orEmpty()
    val breakdown = if (parts.isEmpty()) "" else " — $parts$other"
    return "summary_json: $kb KB in $coreMs ms$breakdown; parse $parseMs ms"
}

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
class SummaryRepository private constructor(private val ctx: Context) {

    companion object {

        @Volatile
        private var instance: SummaryRepository? = null

        /**
         * The one repository for the process.
         *
         * This has to be a singleton, not a per-caller object: the sync service and the
         * Activity both recompute, and with separate instances the service would refresh
         * the cache file while the Activity went on rendering its own stale `StateFlow` —
         * a screen showing pre-sync numbers on top of a database that had already moved.
         * Two instances would also race writing `summary.json`.
         */
        fun get(ctx: Context): SummaryRepository =
            instance ?: synchronized(this) {
                instance ?: SummaryRepository(ctx.applicationContext).also { instance = it }
            }
    }

    private val json = Json {
        ignoreUnknownKeys = true   // the Rust core may add fields; never break on them
        isLenient = true
    }

    private val _state = MutableStateFlow<SummaryState>(SummaryState.Loading)
    val state: StateFlow<SummaryState> = _state.asStateFlow()

    private val dbFile: File get() = File(ctx.filesDir, "oura.db")
    private val cacheFile: File get() = File(ctx.filesDir, "summary.json")

    val hasDatabase: Boolean get() = dbFile.exists() && dbFile.length() > 0

    /**
     * What the database occupies on this phone, or null when there isn't one. The store runs
     * SQLite in WAL mode, so writes not yet checkpointed sit in `oura.db-wal` beside it —
     * counted too, since that is space the history is genuinely taking up.
     */
    fun databaseBytes(): Long? =
        dbFile.takeIf { it.exists() }?.let { it.length() + File(ctx.filesDir, "oura.db-wal").length() }

    /** Local UTC offset in whole hours — what `summary_json` wants for day bucketing. */
    private fun tzOffsetHours(): Long =
        (java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 3_600_000).toLong()

    /**
     * Show the cached snapshot immediately (marked stale), then recompute if asked.
     * Returns once the freshest available state has been emitted.
     */
    suspend fun load(refresh: Boolean) {
        // Off the main thread: this is the read and parse of a megabyte of JSON, and at
        // cold start it used to sit between the process starting and its first frame.
        val cached = withContext(Dispatchers.Default) { readCache() }
        if (cached != null) _state.value = SummaryState.Ready(cached, stale = true)
        else if (!refresh) _state.value = SummaryState.Empty("No summary computed yet.")

        if (!hasDatabase) {
            if (cached == null) {
                _state.value = SummaryState.Empty(
                    "No ring database on this device yet.\n\n" +
                        "Import an oura.db synced by the desktop client, or (for development, on " +
                        "a debug build) push one straight into app storage:\n\n" +
                        "adb push oura.db /data/local/tmp/oura.db\n" +
                        "adb shell run-as org.openoura.android cp /data/local/tmp/oura.db files/oura.db"
                )
            }
            return
        }
        if (refresh || cached == null || cacheIsStale()) recompute()
    }

    private fun cacheIsStale(): Boolean {
        if (!hasDatabase || !cacheFile.exists()) return false
        return summaryCacheIsStale(cacheFile.lastModified(), dbFile.lastModified(), appUpdatedAt())
    }

    /** When this APK was last installed over. Zero if it cannot be read, which reads as
     *  "no reason to invalidate" rather than as a reason to recompute on every launch. */
    private fun appUpdatedAt(): Long = runCatching {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime
    }.getOrDefault(0L)

    /** Runs the Rust core off the main thread and replaces the cache. */
    suspend fun recompute() {
        if (!hasDatabase) {
            _state.value = SummaryState.Empty("No database on the device yet.")
            return
        }
        if (_state.value is SummaryState.Empty) _state.value = SummaryState.Loading

        val result = withContext(Dispatchers.Default) {
            runCatching {
                val edits = labelEdits.get()
                val started = System.nanoTime()
                var raw = summaryJsonStaged(dbFile.absolutePath, tzOffsetHours(), STAGE_BUDGET_MS.toULong())
                val coreMs = (System.nanoTime() - started) / 1_000_000
                val parsed = json.decodeFromString<Summary>(raw)
                val parseMs = (System.nanoTime() - started) / 1_000_000 - coreMs
                Log.i(TAG, describeRecompute(raw.length / 1024, coreMs, parseMs, parsed.timingsMs))
                parsed.stager?.let {
                    Log.i(TAG, "sleep stager: ${it.staged} staged, ${it.reused} from cache, ${it.pending} waiting")
                }
                // Only cache what parsed cleanly, so a bad write can't poison cold start.
                synchronized(cacheLock) {
                    // A label set while this ran may have missed the core's read of them;
                    // put it on before this replaces the cache [labelEffort] patched.
                    val final = if (labelEdits.get() == edits) parsed else {
                        raw = relabelSummaryJson(dbFile.absolutePath, raw)
                        json.decodeFromString<Summary>(raw)
                    }
                    cacheFile.writeText(raw)
                    parsedCache = cacheFile.lastModified() to final
                    final
                }
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
                // A label set after this recompute cached its result patched the cache since;
                // show that, not the copy from before it.
                _state.value = if (err != null) SummaryState.Failed(err)
                else SummaryState.Ready(synchronized(cacheLock) { parsedCache?.second } ?: s, stale = false)
            },
            onFailure = { e ->
                Log.e(TAG, "summary refresh failed", e)
                val cached = readCache()
                _state.value = if (cached != null) SummaryState.Ready(cached, stale = true)
                else SummaryState.Failed(e.message ?: e.toString())
            },
        )
    }

    /** Bumped by every label change, so a recompute running alongside one can tell. */
    private val labelEdits = AtomicInteger()

    /**
     * Put the person's own label on an effort session, or take it off with a null [kind].
     *
     * The label goes into `oura.db` (so it is backed up and restored with the history), then
     * onto the summary already showing through the core's own matching — no recompute,
     * which on a phone would be a ten-second wait to see one word change. Returns null on
     * success, or what went wrong.
     */
    suspend fun labelEffort(ring: EffortRing, kind: String?, note: String?): String? {
        if (!hasDatabase) return "No database on this device yet."
        val lo = ring.capture.getOrNull(0) ?: return "This session has no ring time."
        val hi = ring.capture.getOrNull(1) ?: lo
        return withContext(Dispatchers.IO) {
            runCatching {
                val db = dbFile.absolutePath
                val out = if (kind == null) {
                    clearEffortLabel(db, ring.serial, lo, hi, ring.startDs, ring.endDs)
                } else {
                    setEffortLabel(db, ring.serial, lo, hi, ring.startDs, ring.endDs, kind, note)
                }
                coreError(out)?.let { return@runCatching it }
                labelEdits.incrementAndGet()
                val relabelled = synchronized(cacheLock) {
                    if (!cacheFile.exists()) return@synchronized null
                    val raw = relabelSummaryJson(db, cacheFile.readText())
                    val parsed = json.decodeFromString<Summary>(raw)
                    if (parsed.error != null) return@synchronized null
                    cacheFile.writeText(raw)
                    parsedCache = cacheFile.lastModified() to parsed
                    parsed
                }
                relabelled?.let { s ->
                    val stale = (_state.value as? SummaryState.Ready)?.stale ?: false
                    _state.value = SummaryState.Ready(s, stale)
                }
                null
            }.getOrElse {
                Log.e(TAG, "labelling an effort session failed", it)
                it.message ?: it.toString()
            }
        }
    }

    /** The `error` a core call returned in-band, if it did. */
    private fun coreError(out: String): String? =
        runCatching { json.parseToJsonElement(out) }.getOrNull()
            ?.let { it as? kotlinx.serialization.json.JsonObject }
            ?.get("error")
            ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }

    /**
     * The last computed snapshot, straight off disk and without touching [state].
     *
     * For callers with no UI to drive — the auto-sync worker reads the nightly times to
     * work out quiet hours, and would otherwise have to wait out a recompute to learn
     * something it already has on disk.
     */
    fun cached(): Summary? = readCache()

    private val cacheLock = Any()

    /** The cache file as last parsed, with the file's modification time it was parsed at. */
    private var parsedCache: Pair<Long, Summary>? = null

    /**
     * The cache file, parsed at most once per version of it.
     *
     * Opening the app used to parse it twice — once to draw, once in the app-open sync
     * worker to work out quiet hours — and every later [cached] call parsed it again. The
     * lock makes two callers arriving together share one parse rather than race to it.
     */
    private fun readCache(): Summary? = synchronized(cacheLock) {
        if (!cacheFile.exists()) return null
        val stamp = cacheFile.lastModified()
        parsedCache?.takeIf { it.first == stamp }?.let { return it.second }
        runCatching {
            val started = System.nanoTime()
            val raw = cacheFile.readText()
            val parsed = json.decodeFromString<Summary>(raw)
            // Timed, with the thread, so a parse back on the main thread shows in the log.
            val ms = (System.nanoTime() - started) / 1_000_000
            Log.i(TAG, "summary cache: ${raw.length / 1024} KB read and parsed in $ms ms on ${Thread.currentThread().name}")
            parsedCache = stamp to parsed
            parsed
        }.onFailure { Log.w(TAG, "discarding unreadable summary cache", it) }.getOrNull()
    }

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
