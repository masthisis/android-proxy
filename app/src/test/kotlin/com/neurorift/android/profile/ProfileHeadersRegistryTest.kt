package com.neurorift.android.profile

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Registry persistence tests for the per-profile fetch headers (issue #24):
 * [ProfileManager.setFetchHeaders] round-trips through the atomic
 * `profiles.json` — a fresh manager instance on the same data dir reads the
 * same sanitized header map back. The User-Agent captured at import time
 * persists the same way.
 */
class ProfileHeadersRegistryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** Minimal share-link list — decodes to a NODES-kind profile. */
    private fun nodesContent(seed: String): String =
        "vless://b831381d-6324-4d53-ad4f-8cda48b30811@h1.example.com:1#$seed\n" +
            "vless://b831381d-6324-4d53-ad4f-8cda48b30811@h2.example.com:2#$seed-2\n"

    private fun manager(dir: File): ProfileManager =
        ProfileManager(dataDir = dir, engine = { true })

    @Test
    fun `setFetchHeaders persists through the registry json`() = runBlocking {
        val dir = tmp.newFolder("registry")
        val mgr = manager(dir)
        val record = mgr.addFromText(nodesContent("A"), name = "Sub A")

        assertTrue(mgr.setFetchHeaders(record.id, mapOf("X-Sub-Auth" to "token123")))
        assertEquals(mapOf("X-Sub-Auth" to "token123"), mgr.list().first().fetchHeaders)

        val again = manager(dir)
        assertEquals(mapOf("X-Sub-Auth" to "token123"), again.list().first().fetchHeaders)
    }

    @Test
    fun `stored headers are sanitized on write`() = runBlocking {
        val dir = tmp.newFolder("registry-2")
        val mgr = manager(dir)
        val record = mgr.addFromText(nodesContent("A"), name = "Sub A")

        assertTrue(
            mgr.setFetchHeaders(
                record.id,
                mapOf(
                    "Host" to "evil.example.com",
                    "Connection" to "close",
                    "X-Kept" to "yes",
                    "" to "no name",
                    "X-Blank" to "",
                ),
            ),
        )
        assertEquals(mapOf("X-Kept" to "yes"), mgr.list().first().fetchHeaders)
    }

    @Test
    fun `import-time user agent persists as the User-Agent header`() = runBlocking {
        val dir = tmp.newFolder("registry-3")
        val mgr = manager(dir)
        val withUa = mgr.addFromText(nodesContent("A"), name = "Sub A", userAgent = "Custom/9.9")
        val plain = mgr.addFromText(nodesContent("B"), name = "Sub B")

        assertEquals("Custom/9.9", withUa.fetchHeaders["User-Agent"])
        assertFalse(plain.fetchHeaders.containsKey("User-Agent"))

        val again = manager(dir)
        assertEquals(
            "Custom/9.9",
            again.list().first { it.id == withUa.id }.fetchHeaders["User-Agent"],
        )
    }

    @Test
    fun `unknown profile ids are a no-op`() {
        val dir = tmp.newFolder("registry-4")
        val mgr = manager(dir)
        assertFalse(mgr.setFetchHeaders("ghost", mapOf("X-Any" to "v")))
        assertTrue(mgr.list().isEmpty())
    }
}
