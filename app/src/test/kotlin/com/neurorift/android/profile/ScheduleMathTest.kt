package com.neurorift.android.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ScheduleMath] boundary tests (issue #18): manual intervals never fire,
 * a full elapsed interval is always due, and the deterministic jitter keeps
 * every decision boundary inside the Windows-parity
 * `[interval*(1-r), interval*(1+r)]` band.
 */
class ScheduleMathTest {

    private val hourMs = 3_600_000L

    @Test
    fun `interval zero is never due`() {
        val now = 1_700_000_000_000L
        for (lastSyncAt in listOf(0L, now - hourMs, now - 25 * hourMs)) {
            assertFalse(ScheduleMath.isDue(lastSyncAt, 0, now))
            assertFalse(ScheduleMath.isDue(lastSyncAt, 0, now, jitterRatio = 0.5))
        }
    }

    @Test
    fun `profile is due exactly at the interval regardless of jitter`() {
        // lastSyncAt spread across the band; jitter only ever shortens the
        // effective interval, so a full elapsed interval is always due.
        val base = 1_700_000_000_000L
        for (offset in 0 until 200) {
            val lastSyncAt = base + offset * 137_000L
            for (intervalH in listOf(1, 6, 24, 168)) {
                assertTrue(
                    "interval=$intervalH offset=$offset",
                    ScheduleMath.isDue(lastSyncAt, intervalH, lastSyncAt + intervalH * hourMs),
                )
            }
        }
    }

    @Test
    fun `never-synced profile is immediately due`() {
        assertTrue(ScheduleMath.isDue(0L, 24, 1_700_000_000_000L))
    }

    @Test
    fun `not due well before the interval`() {
        val now = 1_700_000_000_000L
        // Half of a 24h interval — below the (1-r) lower bound of any r <= 0.1.
        assertFalse(ScheduleMath.isDue(now - 12 * hourMs, 24, now))
        assertFalse(ScheduleMath.isDue(now - 22 * hourMs, 24, now, jitterRatio = 0.1))
    }

    @Test
    fun `jitter keeps the update point inside the band`() {
        val intervalH = 24
        val intervalMs = intervalH * hourMs
        val ratio = 0.1
        val base = 1_700_000_000_000L
        // Scan minute-by-minute for the exact decision boundary of many
        // different sync instants; every boundary must land inside
        // [interval*(1-r), interval*(1+r)].
        val stepMs = 60_000L
        for (offset in 0 until 120) {
            val lastSyncAt = base + offset * 997_000L
            var now = lastSyncAt
            while (!ScheduleMath.isDue(lastSyncAt, intervalH, now, ratio)) {
                now += stepMs
                assertTrue("boundary scan ran past the upper band", now <= lastSyncAt + intervalMs * (1 + ratio) + stepMs)
            }
            val effective = (now - lastSyncAt).toDouble()
            assertTrue(
                "boundary ${effective / hourMs}h below lower band",
                effective >= intervalMs * (1 - ratio) - 1,
            )
            assertTrue(
                "boundary ${effective / hourMs}h above upper band",
                effective <= intervalMs * (1 + ratio),
            )
        }
    }

    @Test
    fun `zero jitter makes the plain interval the boundary`() {
        val lastSyncAt = 1_700_000_000_000L
        val intervalH = 6
        assertFalse(ScheduleMath.isDue(lastSyncAt, intervalH, lastSyncAt + intervalH * hourMs - 1, jitterRatio = 0.0))
        assertTrue(ScheduleMath.isDue(lastSyncAt, intervalH, lastSyncAt + intervalH * hourMs, jitterRatio = 0.0))
    }

    @Test
    fun `same sync instant always yields the same decision`() {
        val lastSyncAt = 1_700_123_456_789L
        val now = lastSyncAt + 23 * hourMs
        val expected = ScheduleMath.isDue(lastSyncAt, 24, now)
        repeat(50) {
            assertEquals(expected, ScheduleMath.isDue(lastSyncAt, 24, now))
        }
    }

    @Test
    fun `nextUpdateAt is the plain interval with zero for manual`() {
        val lastSyncAt = 1_700_000_000_000L
        assertEquals(lastSyncAt + 12 * hourMs, ScheduleMath.nextUpdateAt(lastSyncAt, 12))
        assertEquals(0L, ScheduleMath.nextUpdateAt(lastSyncAt, 0))
    }
}
