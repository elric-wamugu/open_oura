package org.openoura.android.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The demographic profile the Rust core reads.
 *
 * IMPORTANT: this is **not** stored in the database. `oura-summary::profile_path` looks for
 * `profile.json` *next to the .db*, and falls back to a generic 30-year-old / 75 kg / 1.78 m
 * default when it is missing. So copying only `oura.db` onto a device silently changes every
 * demographic-derived figure — VO₂max, the Tanaka predicted HR max, Schofield BMR, and
 * therefore active and total kcal. The vitals and sleep numbers are unaffected.
 *
 * Keep the field names and types exactly as `Demographics::from_json` expects.
 */
@Serializable
data class RingProfile(
    val sex: String = "M",
    val age: Double = 30.0,
    val height_m: Double = 1.78,
    val weight_kg: Double = 75.0,
    val ring_size: Double = 10.0,
)

class ProfileStore(private val ctx: Context) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    // Must sit beside oura.db, because that is where the core looks for it.
    private val file: File get() = File(ctx.filesDir, "profile.json")

    val exists: Boolean get() = file.exists()

    fun read(): RingProfile = runCatching {
        if (!file.exists()) return RingProfile()
        json.decodeFromString<RingProfile>(file.readText())
    }.getOrElse { RingProfile() }

    fun write(p: RingProfile) {
        file.writeText(json.encodeToString(RingProfile.serializer(), p))
    }
}
