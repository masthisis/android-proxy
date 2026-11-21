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
 * Registry persistence tests for the subscription union (issue #23):
 * [ProfileManager.setUnionActive] round-trips through the atomic
 * `profiles.json` — a fresh manager instance on the same data dir reads the
 * same union membership back, in registry order. [activeId] semantics stay
 * untouched (union members are additional provider sources, never primary).
 */
class ProfileUnionRegistryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** Minimal share-link list — decodes to a NODES-kind profile. */
    private fun nodesContent(seed: String): String =
        "vless://b831381d-6324-4d53-ad4f-8cda48b30811@h1.example.com:1#$seed\n" +
            "vless://b831381d-6324-4d53-ad4f-8cda48b30811@h2.example.com:2#$seed-2\n"

    private fun manager(dir: File): ProfileManager =
        ProfileManager(dataDir = dir, engine = { true })

    @Test
    fun `setUnionActive persists through the registry json`() = runBlocking {
        val dir = tmp.newFolder("registry")
        val mgr = manager(dir)
        val a = mgr.addFromText(nodesContent("A"), name = "Sub A")
        val b = mgr.addFromText(nodesContent("B"), name = "Sub B")
        val c = mgr.addFromText(nodesContent("C"), name = "Sub C")

        assertTrue(mgr.setUnionActive(c.id, true))
        assertTrue(mgr.setUnionActive(b.id, true))
        // Registry order, not toggle order.
        assertEquals(listOf(b.id, c.id), mgr.unionIds())
        // activeId untouched — the first import stays primary.
        assertEquals(a.id, mgr.activeId())
        assertFalse(mgr.list().first { it.id == a.id }.unionActive)

        val again = manager(dir)
        assertEquals(listOf(b.id, c.id), again.unionIds())
        assertTrue(again.list().first { it.id == b.id }.unionActive)
        assertTrue(again.list().first { it.id == c.id }.unionActive)
    }

    @Test
    fun `leaving the union clears the flag and unknown ids are a no-op`() = runBlocking {
        val dir = tmp.newFolder("registry-2")
        val mgr = manager(dir)
        val a = mgr.addFromText(nodesContent("A"), name = "Sub A")

        assertTrue(mgr.setUnionActive(a.id, true))
        assertEquals(listOf(a.id), mgr.unionIds())
        assertTrue(mgr.setUnionActive(a.id, false))
        assertEquals(emptyList<String>(), mgr.unionIds())
        // Unknown profile ids never flip anything.
        assertFalse(mgr.setUnionActive("ghost", true))
        assertEquals(emptyList<String>(), mgr.unionIds())

        val again = manager(dir)
        assertEquals(emptyList<String>(), again.unionIds())
        assertEquals(a.id, again.activeId())
    }
}
