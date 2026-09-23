package org.openoura.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openoura.android.data.summaryCacheIsStale

private const val HOUR = 3_600_000L
private const val NOW = 1_000 * HOUR

/**
 * When a cached summary stops being the truth.
 *
 * Both ways of getting this wrong have already happened here. A summary left behind a moved
 * database put the previous evening's numbers on a screen that looked current. A summary
 * left behind a *new build* made the first Health Connect export write zero sleep sessions,
 * because the field it needed had only just been added to the brain and nothing about the
 * database had changed.
 */
class SummaryCacheTest {

    @Test
    fun `a cache newer than everything is good`() {
        assertFalse(summaryCacheIsStale(cacheWrittenAt = NOW, dbModifiedAt = NOW - HOUR, appUpdatedAt = NOW - 10 * HOUR))
    }

    @Test
    fun `a database that moved past the cache invalidates it`() {
        assertTrue(summaryCacheIsStale(cacheWrittenAt = NOW - HOUR, dbModifiedAt = NOW, appUpdatedAt = NOW - 10 * HOUR))
    }

    @Test
    fun `an app updated after the cache invalidates it too`() {
        // The 2026-09-23 case: the database had not moved, but the code reading it had.
        assertTrue(summaryCacheIsStale(cacheWrittenAt = NOW - HOUR, dbModifiedAt = NOW - 2 * HOUR, appUpdatedAt = NOW))
    }

    @Test
    fun `an unreadable install time is not a reason to recompute forever`() {
        // appUpdatedAt() falls back to 0, which must never read as newer than the cache.
        assertFalse(summaryCacheIsStale(cacheWrittenAt = NOW, dbModifiedAt = NOW - HOUR, appUpdatedAt = 0))
    }

    @Test
    fun `equal timestamps are not stale`() {
        // A recompute lands in the same second as the sync that prompted it often enough
        // that treating equality as stale would recompute on every launch.
        assertFalse(summaryCacheIsStale(cacheWrittenAt = NOW, dbModifiedAt = NOW, appUpdatedAt = NOW))
    }
}
