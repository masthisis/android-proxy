package com.neurorift.android.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [parseAtom] tests (issue #20): the fixed GitHub releases-atom sample
 * parses to the entries newest-first (document order) with tag, link and
 * updated extracted per entry.
 */
class AtomParseTest {

    /** Shape of https://github.com/masthisis/android-proxy/releases.atom (trimmed). */
    private val sampleXml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <feed xmlns="http://www.w3.org/2005/Atom" xmlns:media="http://search.yahoo.com/mrss/">
          <id>tag:github.com,2008:https://github.com/masthisis/android-proxy/releases</id>
          <updated>2025-06-02T10:00:00Z</updated>
          <link rel="self" href="https://github.com/masthisis/android-proxy/releases"/>
          <entry>
            <id>tag:github.com,2008:Repository/123456/v1.2.0</id>
            <updated>2025-06-02T10:00:00Z</updated>
            <link rel="alternate" type="text/html" href="https://github.com/masthisis/android-proxy/releases/tag/v1.2.0"/>
            <title>NeuroRift for Android v1.2.0</title>
            <author>
              <name>masthisis</name>
            </author>
            <media:thumbnail height="30" width="30" url="https://avatars.example/u/123?s=60&amp;v=4"/>
            <content type="html">&lt;p&gt;New release.&lt;/p&gt;</content>
          </entry>
          <entry>
            <id>tag:github.com,2008:Repository/123456/v1.1.1</id>
            <updated>2025-05-20T08:30:00Z</updated>
            <link rel="alternate" type="text/html" href="https://github.com/masthisis/android-proxy/releases/tag/v1.1.1"/>
            <title>NeuroRift for Android v1.1.1</title>
            <author>
              <name>masthisis</name>
            </author>
            <content type="html">&lt;p&gt;Patch.&lt;/p&gt;</content>
          </entry>
          <entry>
            <id>tag:github.com,2008:Repository/123456/v1.1.0-rc.1</id>
            <updated>2025-05-12T06:00:00Z</updated>
            <link rel="alternate" type="text/html" href="https://github.com/masthisis/android-proxy/releases/tag/v1.1.0-rc.1"/>
            <title>NeuroRift for Android v1.1.0-rc.1</title>
            <author>
              <name>masthisis</name>
            </author>
            <content type="html">&lt;p&gt;Candidate.&lt;/p&gt;</content>
          </entry>
        </feed>
    """.trimIndent()

    @Test
    fun `entries parse newest first in document order`() {
        val releases = parseAtom(sampleXml)
        assertEquals(3, releases.size)
        assertEquals(listOf("v1.2.0", "v1.1.1", "v1.1.0-rc.1"), releases.map { it.tag })
    }

    @Test
    fun `links and timestamps are extracted`() {
        val releases = parseAtom(sampleXml)
        assertEquals(
            "https://github.com/masthisis/android-proxy/releases/tag/v1.2.0",
            releases[0].link,
        )
        assertEquals("2025-06-02T10:00:00Z", releases[0].updated)
        assertEquals("2025-05-12T06:00:00Z", releases[2].updated)
    }

    @Test
    fun `html content and foreign namespaces do not break parsing`() {
        // Namespaced media:thumbnail and escaped HTML sit inside the entries —
        // the parser must still return clean tag/link/updated triples.
        val releases = parseAtom(sampleXml)
        assertTrue(releases.all { it.link.contains("/releases/tag/") })
    }

    @Test
    fun `empty feed yields no releases`() {
        val empty = """
            <?xml version="1.0" encoding="UTF-8"?>
            <feed xmlns="http://www.w3.org/2005/Atom">
              <id>tag:github.com,2008:releases</id>
              <updated>2025-01-01T00:00:00Z</updated>
            </feed>
        """.trimIndent()
        assertEquals(0, parseAtom(empty).size)
    }

    @Test
    fun `picked release beats the running version after filtering`() {
        // The check() filter+pick logic, pinned with the pure pieces: tags
        // containing '-' are prereleases, Semver picks the newest above current.
        val releases = parseAtom(sampleXml)
        val eligible = releases.filter { !it.tag.contains('-') }
        val newest = eligible.firstOrNull { Semver.isNewer(Semver.stripV(it.tag), "1.1.0") }
        assertEquals("v1.2.0", newest?.tag)
    }
}
