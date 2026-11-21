package com.neurorift.android.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Background sampling gate (issue #26). */
class SamplingGateTest {

    @Test
    fun `emissions flow while foreground`() {
        val gate = SamplingGate()
        assertTrue(gate.shouldEmit())
    }

    @Test
    fun `emissions stop while backgrounded`() {
        val gate = SamplingGate()
        gate.paused = true
        assertFalse(gate.shouldEmit())
        gate.paused = false
        assertTrue(gate.shouldEmit())
    }
}
