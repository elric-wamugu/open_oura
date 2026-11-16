package org.openoura.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.openoura.android.data.DatabaseCare

/** When compacting may start, and what it reports. The rewrite itself is SQLite's. */
class DatabaseCareTest {

    private val mb = { bytes: Long -> "${bytes / 1_000_000} MB" }

    @Test
    fun `it needs a database, a quiet ring and room for a second copy`() {
        assertEquals("There is no database to compact yet.", DatabaseCare.problem(null, 9_000_000_000, false, mb))
        assertEquals(
            "A sync is running. Try again when it finishes.",
            DatabaseCare.problem(1_000_000_000, 9_000_000_000, true, mb),
        )
        assertEquals(
            "Compacting needs about 2000 MB free while it runs, and this phone has 1500 MB.",
            DatabaseCare.problem(1_000_000_000, 1_500_000_000, false, mb),
        )
        assertNull(DatabaseCare.problem(1_000_000_000, 2_000_000_000, false, mb))
    }

    @Test
    fun `it says what was freed, or that there was nothing to free`() {
        assertEquals(
            "Freed 164 MB. The database is now 868 MB.",
            DatabaseCare.describe("""{"before":1033195520,"after":868220928}""", mb),
        )
        assertEquals(
            "The database was already compact (868 MB).",
            DatabaseCare.describe("""{"before":868220928,"after":868216832}""", mb),
        )
        assertEquals("Compacting didn't work: database or disk is full", DatabaseCare.describe("""{"error":"database or disk is full"}""", mb))
        assertEquals("Compacting didn't work.", DatabaseCare.describe("not json", mb))
    }
}
