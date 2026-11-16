package org.openoura.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openoura.android.data.BackupResult
import org.openoura.android.data.Backups
import org.openoura.android.ui.describeBackup
import java.time.LocalDate

/**
 * The phone's side of a backup: what it accepts as a passphrase, and how it reads back
 * what the core says happened. The format and the encryption are tested in `oura-backup`.
 */
class BackupsTest {

    @Test
    fun `a passphrase must be long enough and typed the same twice`() {
        assertEquals("At least 8 characters", Backups.passphraseProblem("short", "short"))
        assertEquals("The two don't match", Backups.passphraseProblem("long enough", "long enoguh"))
        assertNull(Backups.passphraseProblem("long enough", "long enough"))
    }

    @Test
    fun `the file is named for the day it was made`() {
        assertEquals("open_oura-2026-11-16.ourabak", Backups.fileName(LocalDate.of(2026, 11, 16)))
    }

    @Test
    fun `the core's replies are read as outcomes`() {
        assertEquals(
            BackupResult.BackedUp(2_700_000),
            Backups.parse("""{"events":2700000,"readings":412,"devices":1}""", restoring = false),
        )
        assertEquals(
            BackupResult.Restored(events = 300, added = 200, createdUnix = 1_794_000_000),
            Backups.parse(
                """{"events":300,"events_added":200,"readings_added":1,"files_written":0,"created_unix":1794000000}""",
                restoring = true,
            ),
        )
        assertEquals(
            BackupResult.Failed("wrong_passphrase", "wrong passphrase, or the backup file is damaged"),
            Backups.parse(
                """{"error":"wrong passphrase, or the backup file is damaged","code":"wrong_passphrase"}""",
                restoring = true,
            ),
        )
        assertTrue(Backups.parse("not json", restoring = true) is BackupResult.Failed)
    }

    @Test
    fun `a failure says whether anything was changed`() {
        // The two a person will actually meet, and both leave the phone as it was.
        assertEquals(
            "That passphrase doesn't open this file. Nothing was changed.",
            describeBackup(BackupResult.Failed("wrong_passphrase", "x")),
        )
        assertEquals(
            "The backup file is damaged or incomplete. Nothing was changed.",
            describeBackup(BackupResult.Failed("damaged", "x")),
        )
    }

    @Test
    fun `restoring what is already here says so`() {
        val text = describeBackup(BackupResult.Restored(events = 300, added = 0, createdUnix = 0))
        assertEquals("Everything in that backup was already on this phone.", text)
        assertTrue(describeBackup(BackupResult.Restored(300, 200, 0)).startsWith("Restored 200 events"))
    }
}
