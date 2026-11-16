package org.openoura.android.data

import android.content.Context
import android.text.format.Formatter
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import uniffi.oura_core.compactDb
import java.io.File

private const val TAG = "OpenOura"

/**
 * Giving unused space in the database back to the phone.
 *
 * SQLite never shrinks a file on its own: pages freed inside it (an index that was
 * dropped, rows that were replaced) are kept for reuse. Compacting rewrites the file
 * without them. Nothing in the history changes.
 *
 * Manual, not automatic: it needs room for a second copy while it runs and holds the
 * database for the duration, which is not something to spring on a phone overnight.
 */
object DatabaseCare {

    /**
     * Why compacting cannot start, or null when it can. The rewrite keeps the old file
     * until the new one is complete, and logs the change on top, so it wants about twice
     * the database's size free.
     */
    fun problem(dbBytes: Long?, freeBytes: Long, syncRunning: Boolean, size: (Long) -> String): String? = when {
        dbBytes == null || dbBytes == 0L -> "There is no database to compact yet."
        syncRunning -> "A sync is running. Try again when it finishes."
        freeBytes < 2 * dbBytes ->
            "Compacting needs about ${size(2 * dbBytes)} free while it runs, and this phone has ${size(freeBytes)}."
        else -> null
    }

    /** What happened, in words. Pure, so the wording is tested. */
    fun describe(reply: String, size: (Long) -> String): String = runCatching {
        val o = Json.parseToJsonElement(reply).jsonObject
        o["error"]?.jsonPrimitive?.content?.let { return "Compacting didn't work: $it" }
        val before = o["before"]?.jsonPrimitive?.longOrNull ?: 0
        val after = o["after"]?.jsonPrimitive?.longOrNull ?: 0
        val freed = before - after
        // Under a megabyte is the file's own rounding, not space worth announcing.
        if (freed < 1_000_000) "The database was already compact (${size(after)})."
        else "Freed ${size(freed)}. The database is now ${size(after)}."
    }.getOrElse { "Compacting didn't work." }

    // `usableSpace` on purpose: the allocatable figure lint suggests counts cache the system
    // could clear, which SQLite has no way to ask for part-way through a rewrite.
    @Suppress("UsableSpace")
    suspend fun compact(ctx: Context, syncRunning: Boolean): String {
        val size = { bytes: Long -> Formatter.formatShortFileSize(ctx, bytes) }
        val db = File(ctx.filesDir, "oura.db")
        problem(db.takeIf { it.exists() }?.length(), ctx.filesDir.usableSpace, syncRunning, size)?.let { return it }
        return withContext(Dispatchers.IO) {
            val started = System.currentTimeMillis()
            val reply = runCatching { compactDb(db.absolutePath) }
                .getOrElse { """{"error":"${it.message?.replace('"', '\'')}"}""" }
            Log.i(TAG, "compact: $reply in ${System.currentTimeMillis() - started} ms")
            describe(reply, size)
        }
    }
}
