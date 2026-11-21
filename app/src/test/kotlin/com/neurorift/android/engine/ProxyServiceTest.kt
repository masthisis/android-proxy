package com.neurorift.android.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ProxyService contract tests (issue #12): /proxies catalog parsing (group
 * detection, hidden flag, delay history) and the group-delay batch endpoint
 * against MockWebServer — Windows service_proxy.go parity pinned on the JVM.
 */
class ProxyServiceTest {

    private lateinit var server: MockWebServer
    private lateinit var client: MihomoRestClient
    private lateinit var service: ProxyService

    private val proxiesBody = """
    {
      "proxies": {
        "🚀 Node Select": {
          "type": "Selector",
          "now": "alpha",
          "all": ["alpha", "beta", "DIRECT"],
          "history": []
        },
        "♻️ NR Auto": {
          "type": "URLTest",
          "now": "beta",
          "all": ["alpha", "beta"],
          "history": []
        },
        "⚡ NR SpeedTest": {
          "type": "Selector",
          "now": "beta",
          "all": ["alpha", "beta"],
          "history": []
        },
        "alpha": {
          "type": "Vless",
          "history": [{"time": "t1", "delay": 150}]
        },
        "beta": {
          "type": "Shadowsocks",
          "history": [{"time": "t0", "delay": 250}, {"time": "t1", "delay": 420}]
        },
        "DIRECT": {
          "type": "Direct",
          "history": []
        }
      }
    }
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = MihomoRestClient(server.port) { "test-secret" }
        service = ProxyService(client, testUrlProvider = { "https://www.gstatic.com/generate_204" })
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `catalog parses groups and nodes from the proxies body`() {
        val catalog = service.parseCatalog(proxiesBody)

        // Three selector/url-test groups; the hidden flag marks only the
        // speed-test selector.
        assertEquals(listOf("🚀 Node Select", "♻️ NR Auto", "⚡ NR SpeedTest"), catalog.groups.map { it.name })
        assertTrue(catalog.groups[0].nodes.contains("alpha"))
        assertEquals("alpha", catalog.groups[0].now)
        assertFalse(catalog.groups[0].hidden)
        assertTrue(catalog.groups[2].hidden)

        // Nodes carry the LAST history delay; DIRECT has no history.
        assertEquals(150, catalog.nodes["alpha"]?.delay)
        assertEquals(420, catalog.nodes["beta"]?.delay)
        assertNull(catalog.nodes["DIRECT"]?.delay)
        assertEquals("Selector", catalog.groups[0].type)
        assertEquals("Shadowsocks", catalog.nodes["beta"]?.type)
    }

    @Test
    fun `groupDelay parses the batch delay map`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setBody("""{"alpha": 180, "beta": 512}""")
                .setHeader("Content-Type", "application/json"),
        )
        val delays = client.groupDelay("🚀 Node Select", "https://test/204", 5000)
        assertEquals(mapOf("alpha" to 180, "beta" to 512), delays)

        val recorded = server.takeRequest()
        assertTrue(recorded.path!!.startsWith("/group/"))
        assertTrue(recorded.path!!.contains("/delay"))
        assertTrue(recorded.path!!.contains("timeout=5000"))
        assertEquals("Bearer test-secret", recorded.getHeader("Authorization"))
    }

    @Test
    fun `testGroup reports unreachable members as null and emits results`() = runBlocking {
        val emitted = mutableListOf<ProxyService.LatencyResult>()
        val collector = CoroutineScope(Dispatchers.Default).launch {
            service.latencyResults.collect { emitted.add(it) }
        }
        // Deterministic subscription before the emission (replay = 0).
        service.latencyResults.subscriptionCount.first { it >= 1 }

        server.enqueue(
            MockResponse().setBody("""{"alpha": 120}""").setHeader("Content-Type", "application/json"),
        )
        val results = service.testGroup("g", listOf("alpha", "beta"))
        assertEquals(120, results["alpha"])
        assertNull(results["beta"])
        delay(200)

        assertTrue(emitted.isNotEmpty())
        assertEquals("g", emitted.first().group)
        assertEquals(2, emitted.first().results.size)
        collector.cancel()
    }

    @Test
    fun `testNode probes through the hidden speed-test selector`() = runBlocking {
        // select PUT, then the delay GET
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(
            MockResponse().setBody("""{"delay": "233"}""").setHeader("Content-Type", "application/json"),
        )
        val delay = service.testNode("alpha")
        assertEquals(233, delay)

        val selectRequest = server.takeRequest()
        assertEquals("PUT", selectRequest.method)
        assertTrue(selectRequest.path!!.startsWith("/proxies/"))
        val delayRequest = server.takeRequest()
        assertTrue(delayRequest.path!!.contains("/delay"))
    }

    @Test
    fun `mode round-trips through the configs endpoint`() = runBlocking {
        server.enqueue(
            MockResponse().setBody("""{"mode":"global","port":7890}""").setHeader("Content-Type", "application/json"),
        )
        assertEquals("global", service.mode())
        server.takeRequest()

        server.enqueue(MockResponse().setResponseCode(204))
        assertTrue(service.setMode("rule"))
        val patch = server.takeRequest()
        assertEquals("PATCH", patch.method)
        assertEquals("/configs", patch.path)
    }

    @Test
    fun `catalog of an empty proxies body yields an empty catalog`() {
        val catalog = service.parseCatalog("""{"proxies":{}}""")
        assertNotNull(catalog)
        assertTrue(catalog.groups.isEmpty())
        assertTrue(catalog.nodes.isEmpty())
    }
}
