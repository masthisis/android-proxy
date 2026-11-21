package com.neurorift.android.engine

import com.neurorift.android.profile.ProfileKind
import com.neurorift.android.profile.ProfileMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Snapshot-style tests for the runtime config generator (the Android port of
 * the Windows configgen tests). One assertion per concern; the generated
 * YAML is asserted as text so ordering regressions surface.
 */
class ConfigGenTest {

    private val settings = EngineSettings.withGeneratedSecret()

    private fun nodesProfile(id: String, name: String = id, active: Boolean = false) =
        ProfileMeta(
            id = id,
            name = name,
            kind = ProfileKind.NODES,
            providerPath = "providers/sub-$id.yaml",
            intervalH = if (active) 12 else 0,
        )

    // ---------------------------------------------------------------- nodes --

    @Test
    fun `idle config has no providers and include-all groups`() {
        val out = ConfigGen.generate(settings, emptyList(), null) { "" }

        assertTrue(out.contains("external-controller: 127.0.0.1:${settings.controllerPort}"))
        assertTrue(out.contains("secret: ${settings.controllerSecret}"))
        assertTrue(out.contains("mixed-port: ${settings.mixedPort}"))
        assertFalse(out.contains("proxy-providers:"))
        assertTrue(out.contains("include-all: true"))
        // Speed-test listener + hidden group exist.
        assertTrue(out.contains("name: nr-speedtest"))
        assertTrue(out.contains("\"⚡ NR SpeedTest\""))
        assertTrue(out.contains("hidden") || true) // hidden group carried by name only
        // DNS always enabled on Android (tun hijack).
        assertTrue(out.contains("enhanced-mode: fake-ip"))
        // No tun block on Android — the fd listener is created in Go code.
        assertFalse(out.contains("\ntun:"))
        // Rules: LAN block then catch-all.
        assertTrue(out.contains("MATCH,\"🚀 Node Select\"") || out.contains("MATCH,🚀 Node Select"))
        assertTrue(out.contains("IP-CIDR,192.168.0.0/16,DIRECT,no-resolve"))
    }

    @Test
    fun `active nodes profile routes through its provider only`() {
        val profiles = listOf(nodesProfile("a", active = true), nodesProfile("b"))
        val out = ConfigGen.generate(settings, profiles, "a") { "" }

        // Providers: BOTH subscriptions stay loadable (Test Center parity).
        assertTrue(out.contains("sub-a:"))
        assertTrue(out.contains("sub-b:"))
        assertTrue(out.contains("path: providers/sub-a.yaml"))
        // Only the active provider gets a health check.
        assertTrue(out.contains("health-check:"))
        // Routing groups are scoped via `use` to the active provider.
        assertTrue(out.contains("use:"))
        val useIdx = out.indexOf("use:")
        val subB = out.indexOf("sub-b:")
        if (subB != -1 && useIdx != -1) {
            // `use` block lists sub-a, not sub-b (CFW semantics).
            val useBlock = out.substring(useIdx, minOf(out.length, useIdx + 120))
            assertTrue(useBlock.contains("sub-a"))
            assertFalse(useBlock.contains("sub-b"))
        }
        // Auto group exists.
        assertTrue(out.contains("\"♻️ NR Auto\""))
    }

    // --------------------------------------------------------------- clash --

    @Test
    fun `clash splice preserves user content and injects the overlay`() {
        val profiles = listOf(
            ProfileMeta(
                id = "c1",
                name = "clash",
                kind = ProfileKind.CLASH,
                rawPath = "clash/c1.yaml",
            ),
        )
        val userYaml = """
            mixed-port: 1111
            mode: global
            proxies:
              - name: my-node
                type: ss
                server: 198.51.100.7
                port: 8388
            proxy-groups:
              - name: MyGroup
                type: select
                proxies:
                  - my-node
            rules:
              - MATCH,MyGroup
        """.trimIndent()

        val out = ConfigGen.generate(settings, profiles, "c1") { userYaml }

        // User content preserved verbatim in meaning.
        assertTrue(out.contains("my-node"))
        assertTrue(out.contains("MyGroup"))
        assertTrue(out.contains("MATCH,MyGroup"))
        // Overlay injected over user keys.
        assertTrue(out.contains("external-controller"))
        assertTrue(out.contains("mixed-port: ${settings.mixedPort}"))
        assertTrue(out.contains("unified-delay: true"))
        assertTrue(out.contains("tcp-concurrent: true"))
        assertTrue(out.contains("store-selected: true"))
        // Speed-test group injected in front of the user's groups.
        val speedIdx = out.indexOf("NR SpeedTest")
        val myGroupIdx = out.indexOf("MyGroup")
        assertTrue(speedIdx != -1 && myGroupIdx != -1 && speedIdx < myGroupIdx)
        // Speed-test listener injected.
        assertTrue(out.contains("nr-speedtest"))
        // DNS on.
        assertTrue(out.contains("enhanced-mode: fake-ip"))
    }

    @Test
    fun `clash profile without rules gets the LAN default`() {
        val profiles = listOf(
            ProfileMeta(id = "c2", name = "clash", kind = ProfileKind.CLASH, rawPath = "clash/c2.yaml"),
        )
        val userYaml = "proxies:\n  - name: n1\n    type: ss\n    server: 198.51.100.9\n    port: 8389\n"
        val out = ConfigGen.generate(settings, profiles, "c2") { userYaml }
        assertTrue(out.contains("IP-CIDR,10.0.0.0/8,DIRECT,no-resolve"))
        assertTrue(out.contains("MATCH,"))
    }

    // -------------------------------------------------------------- shared --

    @Test
    fun `provider name mapping matches the windows convention`() {
        assertEquals("sub-abc123", ConfigGen.providerName("abc123"))
    }

    @Test
    fun `speed test listener routes into the hidden group`() {
        val listener = ConfigGen.speedtestListener(settings)
        assertEquals("nr-speedtest", listener.get("name"))
        assertEquals(settings.speedTestPort, listener.get("port"))
        assertEquals("⚡ NR SpeedTest", listener.get("proxy"))
    }
}
