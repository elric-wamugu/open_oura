package org.openoura.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openoura.android.data.BackupResult
import org.openoura.android.sync.AutoBackup
import org.openoura.android.sync.AutoBackupState
import java.time.ZoneOffset

/** When the automatic backup runs, when it complains, and what it says. */
class AutoBackupTest {

    @Test
    fun `it runs only when the database changed since the last backup`() {
        assertFalse("no database", AutoBackup.shouldRun(null, 0))
        assertTrue("never backed up", AutoBackup.shouldRun(1_000, 0))
        assertTrue("synced since", AutoBackup.shouldRun(2_000, 1_500))
        assertTrue("written during the backup", AutoBackup.shouldRun(1_500, 1_500))
        assertFalse("untouched since", AutoBackup.shouldRun(1_000, 1_500))
    }

    @Test
    fun `a passing failure is mentioned on the third day, once`() {
        assertFalse(AutoBackup.shouldNotify("failed", 1))
        assertFalse(AutoBackup.shouldNotify("failed", 2))
        assertTrue(AutoBackup.shouldNotify("failed", 3))
        assertFalse(AutoBackup.shouldNotify("failed", 4))
    }

    @Test
    fun `a failure only the user can fix is mentioned at once, once`() {
        assertTrue(AutoBackup.shouldNotify("no_access", 1))
        assertFalse(AutoBackup.shouldNotify("no_access", 2))
        assertTrue(AutoBackup.shouldNotify("no_passphrase", 1))
    }

    @Test
    fun `the status line says when, how much, and what went wrong`() {
        assertEquals("No backup yet.", AutoBackup.describe(AutoBackupState(enabled = true), ZoneOffset.UTC))
        val ok = AutoBackupState(enabled = true, lastOkUnix = 1_794_829_200, lastEvents = 5_655_007)
        assertEquals("Last backup Nov 16, 11:40, 5,655,007 events.", AutoBackup.describe(ok, ZoneOffset.UTC))
        assertEquals(
            "Last backup Nov 16, 11:40, 5,655,007 events. The latest try failed: disk full",
            AutoBackup.describe(ok.copy(lastError = "disk full", failStreak = 1), ZoneOffset.UTC),
        )
    }

    @Test
    fun `the warning says how old the last good backup is`() {
        val failed = BackupResult.Failed("failed", "disk full")
        val day = 86_400L
        assertEquals(
            "Automatic backup isn't working" to "No automatic backup has been written yet. disk full",
            AutoBackup.describeFailure(failed, 0, 10 * day),
        )
        assertEquals("The last good backup is 3 days old. disk full", AutoBackup.describeFailure(failed, 7 * day, 10 * day).second)
        assertEquals("The last good backup is from the past day. disk full", AutoBackup.describeFailure(failed, 9 * day, 10 * day).second)
    }
}
