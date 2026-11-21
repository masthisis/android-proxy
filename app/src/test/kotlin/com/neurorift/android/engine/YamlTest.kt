package com.neurorift.android.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the ordered YAML emitter (pitfall P12): generated configs must be
 * stable, insertion-ordered and always parseable by mihomo.
 */
class YamlTest {

    @Test
    fun `top level scalars keep insertion order`() {
        val root = OMap().set("mode", "rule").set("mixed-port", 7897).set("allow-lan", false)
        val out = Yaml.dump(root)
        assertEquals(
            "mode: rule\n" +
                "mixed-port: 7897\n" +
                "allow-lan: false\n",
            out,
        )
    }

    @Test
    fun `nested maps and lists render in block style`() {
        val root = OMap()
            .set(
                "listeners",
                listOf<Any>(
                    OMap()
                        .set("name", "nr-speedtest")
                        .set("type", "mixed")
                        .set("port", 19097),
                ),
            )
        val out = Yaml.dump(root)
        assertEquals(
            "listeners:\n" +
                "  - name: nr-speedtest\n" +
                "    type: mixed\n" +
                "    port: 19097\n",
            out,
        )
    }

    @Test
    fun `emoji and colon-bearing strings are quoted`() {
        val root = OMap().set("name", "🚀 Node Select").set("note", "a: b")
        val out = Yaml.dump(root)
        assertTrue(out.contains("\"🚀 Node Select\""))
        assertTrue(out.contains("\"a: b\""))
    }

    @Test
    fun `boolean-like and number-like strings are quoted`() {
        val root = OMap()
            .set("a", "true")
            .set("b", "123")
            .set("c", "null")
            .set("d", "0.5")
            .set("e", "plain")
        val out = Yaml.dump(root)
        assertTrue(out.contains("a: \"true\""))
        assertTrue(out.contains("b: \"123\""))
        assertTrue(out.contains("c: \"null\""))
        assertTrue(out.contains("d: \"0.5\""))
        assertTrue(out.contains("e: plain"))
    }

    @Test
    fun `set overwrites in place without reordering`() {
        val root = OMap().set("a", 1).set("b", 2).set("a", 10)
        val out = Yaml.dump(root)
        assertEquals("a: 10\nb: 2\n", out)
    }
}
