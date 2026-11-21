package com.neurorift.android.failover

import com.neurorift.android.engine.Groups
import com.neurorift.android.engine.ProxyService
import com.neurorift.android.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Failover watchdog state machine tests (issue #16) — pure JVM with a
 * virtual clock and a scriptable fake probe, mirroring the Windows
 * failover service semantics:
 *
 * - strikes accumulate on consecutive failures and reset on success,
 * - the switch fires only after the strikes threshold AND the cooldown
 *   have both elapsed (no switch inside the cooldown),
 * - the switch picks the group member with the best known delay and emits
 *   a complete `switched` event on the watchdog's own flow.
 */
class WatchdogTest {

    /** Scriptable probe: canned catalog + per-node probe outcomes. */
    private class FakeProbe : Probe {
        var catalog: ProxyService.Catalog? = null
        val probeResults = linkedMapOf<String, Int?>()
        val selections = mutableListOf<Pair<String, String>>()
        var selectOk = true

        override suspend fun catalog(): ProxyService.Catalog? = catalog
        override suspend fun probe(node: String): Int? = probeResults[node]
        override suspend fun select(group: String, node: String): Boolean {
            selections.add(group to node)
            return selectOk
        }
    }

    private fun catalog(
        now: String,
        delays: Map<String, Int?>,
        members: List<String> = listOf("alpha", "beta", "gamma", "DIRECT"),
    ): ProxyService.Catalog {
        val nodes = delays.map { (name, delay) ->
            name to ProxyService.NodeInfo(name = name, type = "Vless", delay = delay)
        }.toMap()
        return ProxyService.Catalog(
            groups = listOf(
                ProxyService.GroupInfo(
                    name = Groups.NODE_SELECT,
                    type = "Selector",
                    now = now,
                    nodes = members,
                    hidden = false,
                ),
            ),
            nodes = nodes,
        )
    }

    private fun settings(
        strikes: Int = 3,
        cooldownSec: Int = 120,
        intervalSec: Int = 15,
    ) = Settings.Failover(
        enable = true,
        intervalSec = intervalSec,
        timeoutMs = 5000,
        strikes = strikes,
        cooldownSec = cooldownSec,
    )

    @Test
    fun `strikes accumulate on consecutive failures without switching`() = runBlocking {
        val probe = FakeProbe()
        probe.catalog = catalog(now = "alpha", delays = mapOf("alpha" to null, "beta" to 120, "gamma" to 250))
        probe.probeResults["alpha"] = null
        var clock = 0L
        val watchdog = FailoverWatchdog(probe, { settings(strikes = 3, cooldownSec = 120) }) { clock }

        val first = watchdog.tick() // strike 1 — probe-failed event, no switch
        assertEquals(FailoverWatchdog.KIND_PROBE_FAILED, first!!.kind)
        assertEquals(1, first.strikes)
        val second = watchdog.tick() // strike 2
        assertEquals(2, second!!.strikes)
        val event = watchdog.tick() // strike 3
        assertEquals(FailoverWatchdog.KIND_PROBE_FAILED, event!!.kind)
        assertEquals(3, event.strikes)

        // Cooldown never elapsed (clock stuck at 0): counted, never switched.
        assertTrue(probe.selections.isEmpty())
        assertEquals(3, watchdog.snapshot().strikes)
        assertEquals("alpha", watchdog.snapshot().current)
        assertTrue(
            watchdog.events.replayCache.all { it.kind == FailoverWatchdog.KIND_PROBE_FAILED },
        )
    }

    @Test
    fun `success resets the strike counter and emits probe-ok`() = runBlocking {
        val probe = FakeProbe()
        probe.catalog = catalog(now = "alpha", delays = mapOf("alpha" to null, "beta" to 120))
        probe.probeResults["alpha"] = null
        var clock = 0L
        val watchdog = FailoverWatchdog(probe, { settings(strikes = 3) }) { clock }

        watchdog.tick()
        watchdog.tick()
        assertEquals(2, watchdog.snapshot().strikes)

        probe.probeResults["alpha"] = 180 // node recovers
        val event = watchdog.tick()
        assertEquals(FailoverWatchdog.KIND_PROBE_OK, event!!.kind)
        assertEquals(0, watchdog.snapshot().strikes)
        assertNull(event.from)
        assertNull(event.to)
        assertEquals("alpha", event.node)
    }

    @Test
    fun `switch happens only after strikes threshold and cooldown elapsed`() = runBlocking {
        val probe = FakeProbe()
        probe.catalog = catalog(now = "alpha", delays = mapOf("alpha" to null, "beta" to 120, "gamma" to 250))
        probe.probeResults["alpha"] = null
        var clock = 50_000L // inside the 120 s cooldown (lastSwitchAtMs = 0)
        val watchdog = FailoverWatchdog(probe, { settings(strikes = 2, cooldownSec = 120) }) { clock }

        watchdog.tick() // strike 1
        watchdog.tick() // strike 2 — threshold reached, cooldown NOT elapsed
        assertTrue(probe.selections.isEmpty())
        assertEquals(2, watchdog.snapshot().strikes)
        assertTrue(
            watchdog.events.replayCache.all { it.kind == FailoverWatchdog.KIND_PROBE_FAILED },
        )

        clock = 200_000L // cooldown long over now
        val event = watchdog.tick() // strike 3 — gate opens
        assertEquals(FailoverWatchdog.KIND_SWITCHED, event!!.kind)
        assertEquals(1, probe.selections.size)
    }

    @Test
    fun `switch selects the member with best known delay and emits a complete event`() = runBlocking {
        val probe = FakeProbe()
        // beta has the best delay; alpha (current) is dead; DIRECT excluded.
        probe.catalog = catalog(
            now = "alpha",
            delays = mapOf("alpha" to null, "beta" to 120, "gamma" to 250, "DIRECT" to null),
        )
        probe.probeResults["alpha"] = null
        var clock = 500_000L // cooldown elapsed from the start (lastSwitchAtMs = 0)
        val watchdog = FailoverWatchdog(probe, { settings(strikes = 1, cooldownSec = 120) }) { clock }

        val event = watchdog.tick()
        assertEquals(FailoverWatchdog.KIND_SWITCHED, event!!.kind)
        assertEquals("alpha", event.from)
        assertEquals("beta", event.to)
        assertEquals("beta", event.node)
        assertEquals(1, event.strikes)
        assertEquals(clock, event.at)
        assertEquals(listOf(Groups.NODE_SELECT to "beta"), probe.selections)

        // Switch resets the strikes and starts a fresh cooldown.
        assertEquals(0, watchdog.snapshot().strikes)
        assertEquals(FailoverWatchdog.KIND_SWITCHED, watchdog.snapshot().lastEvent?.kind)

        // A second immediate failure cannot switch again inside the cooldown.
        clock += 1_000L
        probe.catalog = catalog(now = "beta", delays = mapOf("alpha" to 999, "beta" to null, "gamma" to 250))
        probe.probeResults.clear()
        probe.probeResults["beta"] = null
        watchdog.tick()
        assertEquals(1, probe.selections.size) // no second switch during cooldown
    }

    @Test
    fun `start launches a cancellable ticker and snapshot reflects it`() {
        val probe = FakeProbe()
        probe.catalog = catalog(now = "alpha", delays = mapOf("alpha" to 100))
        probe.probeResults["alpha"] = 100
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val watchdog = FailoverWatchdog(probe, { settings(intervalSec = 15) }) { 0L }

        watchdog.start(scope)
        assertTrue(watchdog.isWatching())
        watchdog.stop()
        assertTrue(!watchdog.isWatching())
        scope.cancel()
    }
}
