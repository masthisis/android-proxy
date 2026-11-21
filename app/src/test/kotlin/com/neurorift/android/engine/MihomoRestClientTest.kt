package com.neurorift.android.engine

import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
 * REST client contract tests against MockWebServer: request shapes, secret
 * auth header, and response parsing. The client is pointed at the mock
 * server's port; the secret is fixed.
 */
class MihomoRestClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: MihomoRestClient
    private val secret = "test-secret-32-chars-aaaaaaaaaaaaaa"

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = MihomoRestClient(server.port) { secret }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `version returns the core version`() = runBlocking {
        server.enqueue(
            MockResponse().setBody("""{"version":"mihomo v1.19.34"}""").setHeader("Content-Type", "application/json"),
        )
        assertEquals("mihomo v1.19.34", client.version())

        val recorded = server.takeRequest()
        assertEquals("/version", recorded.path)
        assertEquals("Bearer $secret", recorded.getHeader("Authorization"))
    }

    @Test
    fun `patchMode sends PATCH with mode body`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))
        assertTrue(client.patchMode("global"))

        val recorded = server.takeRequest()
        assertEquals("PATCH", recorded.method)
        assertEquals("/configs", recorded.path)
        assertTrue(recorded.body.readUtf8().contains("global"))
    }

    @Test
    fun `putConfig hits the force reload endpoint`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))
        assertTrue(client.putConfig("/data/config.yaml"))

        val recorded = server.takeRequest()
        assertEquals("PUT", recorded.method)
        assertEquals("/configs?force=true", recorded.path)
        assertTrue(recorded.body.readUtf8().contains("/data/config.yaml"))
    }

    @Test
    fun `selectProxy sends the node name`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))
        assertTrue(client.selectProxy("🚀 Node Select", "node-a"))

        val recorded = server.takeRequest()
        assertTrue(recorded.path!!.startsWith("/proxies/"))
        assertTrue(recorded.body.readUtf8().contains("node-a"))
    }

    @Test
    fun `providerRefresh hits the provider endpoint`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))
        assertTrue(client.providerRefresh("sub-abc"))

        val recorded = server.takeRequest()
        assertEquals("PUT", recorded.method)
        assertTrue(recorded.path!!.startsWith("/providers/proxies/"))
    }

    @Test
    fun `error responses yield null or false instead of throwing`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        assertNull(client.version())
        server.enqueue(MockResponse().setResponseCode(500))
        assertFalse(client.patchMode("rule"))
    }

    @Test
    fun `connections parsing handles full snapshots and empty bodies`() {
        val body = """
            {"downloadTotal":1024,"uploadTotal":2048,"connections":[
              {"id":"abc","upload":10,"download":20,"start":"2026-11-21T10:00:00Z",
               "chains":["A","🚀 Node Select"],"rule":"DOMAIN-SUFFIX","rulePayload":"example.com",
               "metadata":{"destinationIP":"198.51.100.5","destinationPort":"443"}}
            ]}
        """.trimIndent()
        val snapshot = client.parseConnections(body)
        assertNotNull(snapshot)
        assertEquals(1024, snapshot!!.downloadTotal)
        assertEquals(1, snapshot.rows.size)
        assertEquals("198.51.100.5:443", snapshot.rows[0].host)
        assertEquals("DOMAIN-SUFFIX example.com", snapshot.rows[0].rule)
        // Chains reversed: the active node first.
        assertEquals("🚀 Node Select → A", snapshot.rows[0].chains)
        // Empty connections list.
        val empty = client.parseConnections("""{"downloadTotal":0,"uploadTotal":0,"connections":[]}""")
        assertEquals(0, empty!!.rows.size)
    }

    @Test
    fun `rules parsing joins type payload proxy`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"rules":[{"type":"DOMAIN-SUFFIX","payload":"example.com","proxy":"DIRECT"},
                    {"type":"MATCH","payload":"","proxy":"🚀 Node Select"}]}""",
            ),
        )
        val rules = client.rules()
        assertEquals(2, rules.size)
        assertEquals("DOMAIN-SUFFIX,example.com,DIRECT", rules[0])
        assertEquals("MATCH,🚀 Node Select", rules[1])
    }

    @Test
    fun `json parse helper is stable`() {
        val obj = MihomoRestClient.parseJsonForTests("""{"version":"x"}""").jsonObject
        val version = obj["version"]?.jsonPrimitive?.content
        assertEquals("x", version)
    }
}
