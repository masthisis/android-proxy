package com.neurorift.android.engine

/**
 * Sampling gate (issue #26): when the app is backgrounded the UI consumers
 * are gone — the stream loops keep the cheap bookkeeping (atomics, log ring)
 * but skip the UI emissions until the app is foreground again.
 */
class SamplingGate {

    @Volatile
    var paused: Boolean = false

    fun shouldEmit(): Boolean = !paused
}
