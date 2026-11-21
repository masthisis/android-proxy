package com.neurorift.android.profile

/**
 * Pure scheduling math for the subscription auto-update worker (issue #18).
 * No Android imports and no hidden state, so the boundaries are pinned by
 * JVM tests ([ScheduleMathTest]).
 *
 * A profile is due when `now` has passed its next update time
 * (`lastSyncAt + intervalH` hours) pulled earlier by a deterministic jitter
 * of up to [jitterRatio] of the interval. The jitter is derived from
 * `lastSyncAt` itself — "stored implicitly": the registry persists no extra
 * field, yet repeated checks within one sync cycle always give the same
 * answer, and each cycle lands on a different point of the band (the classic
 * thundering-herd guard for many subscriptions syncing at once).
 *
 * The jitter never pushes an update LATER than the plain interval: once a
 * full `intervalH` has elapsed the profile is always due, so a stalled sync
 * can never be postponed forever by bad luck. The effective interval stays
 * inside `[interval*(1-r), interval]` — within the Windows-parity
 * `[interval*(1-r), interval*(1+r)]` acceptance band.
 */
object ScheduleMath {

    private const val HOUR_MS = 3_600_000L

    /**
     * True when the subscription last synced at [lastSyncAt] should be
     * refreshed at [now]. [intervalH] = 0 (manual) is never due; a profile
     * that has never synced (`lastSyncAt` = 0) is always due.
     */
    fun isDue(lastSyncAt: Long, intervalH: Int, now: Long, jitterRatio: Double = 0.1): Boolean {
        if (intervalH <= 0) return false
        val intervalMs = intervalH * HOUR_MS
        val jitter = jitterFraction(lastSyncAt, jitterRatio)
        return now - lastSyncAt >= (intervalMs * (1.0 - jitter)).toLong()
    }

    /**
     * Plain next-update epoch millis (`lastSyncAt + intervalH` hours) for
     * display purposes — no jitter, so the shown time is the worst case.
     * 0 when the interval is manual / unset.
     */
    fun nextUpdateAt(lastSyncAt: Long, intervalH: Int): Long =
        if (intervalH <= 0) 0L else lastSyncAt + intervalH * HOUR_MS

    /**
     * Deterministic jitter fraction in `[0, jitterRatio]` derived from
     * [lastSyncAt] (integer-mixed so consecutive millis spread across the
     * band instead of collapsing onto one point).
     */
    private fun jitterFraction(lastSyncAt: Long, jitterRatio: Double): Double {
        if (jitterRatio <= 0.0) return 0.0
        val spread = ((lastSyncAt * 2654435761L) ushr 16) % 1001
        return (spread / 1000.0) * jitterRatio
    }
}
