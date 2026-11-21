package com.neurorift.android.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Engine supervisor unit tests: crash-loop guard (Windows parity: 5 apply
 * failures / 2 min fire `core:crashloop` exactly once), config file writes,
 * hot reload refresh events, and the bounded log ring. The applier is
 * injected (recorder) so the tests never touch the real AAR's JNI classes,
 * which cannot load on the plain JVM.
 */
class EngineManagerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val applied = mutableListOf<String>()

    @Before
    fun resetState() {
        applied.clear()
        EngineHolder.reset()
    }

    private fun manager(): EngineManager =
        EngineManager(
            coreHome = tmp.newFolder("neurorift-core"),
            settingsProvider = { EngineSettings.withGeneratedSecret() },
            profilesProvider = { emptyList() },
            activeIdProvider = { null },
            applier = { applied.add(it) },
        )

    @Test
    fun `applyFromProfiles writes the config file and reaches RUNNING`() = runBlocking {
        val mgr = manager()
        mgr.applyFromProfiles()
        val config = mgr.configPath()
        assertTrue(config.exists())
        assertTrue(config.readText().contains("external-controller"))
        assertTrue("applier saw the config", applied.isNotEmpty())
        assertEquals(EngineState.RUNNING, EngineHolder.status.value.state)
    }

    @Test
    fun `crash loop guard fires once after the threshold`() = runBlocking {
        val mgr = manager()
        repeat(5) { mgr.recordApplyFailure() }
        // Guard fires at exactly the threshold, and only once.
        repeat(3) { mgr.recordApplyFailure() }
        val crashEvents = EventBus.events.replayCache.filter { it.first == Events.CORE_CRASH_LOOP }
        assertEquals(1, crashEvents.size)
        assertTrue((crashEvents[0].second as String).contains("config apply failed"))
    }

    @Test
    fun `hot reload writes config and emits groups refresh`() = runBlocking {
        val mgr = manager()
        mgr.applyFromProfiles()
        // The stub REST call returns no server -> fallback re-apply path
        // still emits the refresh event.
        mgr.hotReload("profile-switch")
        val refreshes = EventBus.events.replayCache.filter { it.first == Events.GROUPS_REFRESH }
        assertTrue(refreshes.any { it.second == "profile-switch" })
    }

    @Test
    fun `config path lives inside the core home`() {
        val mgr = manager()
        assertTrue(mgr.configPath().canonicalPath.startsWith(tmp.root.canonicalPath))
    }

    @Test
    fun `log ring is bounded`() {
        val ring = LogRing(capacity = 5)
        repeat(10) { ring.add("line-$it") }
        val snap = ring.snapshot()
        assertEquals(5, snap.size)
        assertEquals("line-5", snap.first())
        assertEquals("line-9", snap.last())
        ring.clear()
        assertEquals(0, ring.size())
    }

    @Test
    fun `event bus replays the latest events`() = runBlocking {
        EventBus.emit(Events.TRAFFIC, "probe")
        // Replay buffer delivers past events to a fresh collector; filter for
        // this event type (older events from other tests stay in the buffer).
        val (name, payload) = EventBus.events.filter { it.first == Events.TRAFFIC }.first()
        assertEquals(Events.TRAFFIC, name)
        assertEquals("probe", payload)
    }
}
