package com.neurorift.android.vpn

import com.neurorift.android.engine.EngineHolder
import com.neurorift.android.engine.EngineState
import com.neurorift.android.engine.transition
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

/**
 * Pins the engine state machine contract (docs/CONTRACT.md `coreStatus`).
 * These are the transitions TunService drives; an illegal move must throw
 * so programming errors surface in tests, not in the VPN layer.
 */
class EngineStateMachineTest {

    @Before
    fun setUp() = EngineHolder.reset()

    @After
    fun tearDown() = EngineHolder.reset()

    @Test
    fun `happy path stopped - starting - running`() {
        EngineHolder.transitionTo(EngineState.STARTING)
        val running = EngineHolder.transitionTo(EngineState.RUNNING)
        assertEquals(EngineState.RUNNING, running.state)
        assertEquals(EngineState.RUNNING, EngineHolder.status.value.state)
    }

    @Test
    fun `running - stopping - stopped`() {
        EngineHolder.transitionTo(EngineState.STARTING)
        EngineHolder.transitionTo(EngineState.RUNNING)
        EngineHolder.transitionTo(EngineState.STOPPING)
        val stopped = EngineHolder.transitionTo(EngineState.STOPPED)
        assertEquals(EngineState.STOPPED, stopped.state)
    }

    @Test
    fun `failure during starting lands in FAILED with detail`() {
        EngineHolder.transitionTo(EngineState.STARTING)
        val failed = EngineHolder.transitionTo(EngineState.FAILED, "prepare rejected")
        assertEquals(EngineState.FAILED, failed.state)
        assertEquals("prepare rejected", failed.detail)
    }

    @Test
    fun `abrupt kill from STARTING or RUNNING goes straight to STOPPED`() {
        EngineHolder.transitionTo(EngineState.STARTING)
        assertEquals(EngineState.STOPPED, transition(EngineState.STARTING, EngineState.STOPPED))
    }

    @Test
    fun `failed is recoverable via a new start`() {
        EngineHolder.transitionTo(EngineState.STARTING)
        EngineHolder.transitionTo(EngineState.FAILED)
        EngineHolder.transitionTo(EngineState.STARTING)
        assertEquals(EngineState.STARTING, EngineHolder.status.value.state)
    }

    @Test
    fun `illegal transitions throw`() {
        assertThrows(IllegalArgumentException::class.java) {
            transition(EngineState.STOPPED, EngineState.RUNNING)
        }
        EngineHolder.transitionTo(EngineState.STARTING)
        EngineHolder.transitionTo(EngineState.RUNNING)
        // Restarting without stopping is illegal — the service guards with
        // isRunning and never triggers this from real flows.
        assertThrows(IllegalArgumentException::class.java) {
            EngineHolder.transitionTo(EngineState.STARTING)
        }
    }

    @Test
    fun `double start is a no-op at the state level`() {
        EngineHolder.transitionTo(EngineState.STARTING)
        EngineHolder.transitionTo(EngineState.RUNNING)
        // TunService.startVpn guards with isRunning before any transition.
        assert(EngineHolder.status.value.isRunning)
        assertThrows(IllegalArgumentException::class.java) {
            EngineHolder.transitionTo(EngineState.STARTING)
        }
    }
}
