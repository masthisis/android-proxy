package com.neurorift.android.profile

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure parser tests for the per-profile subscription fetch headers
 * (issue #24): `Name: Value` lines → ordered map, with trimming, blank-line
 * / no-colon tolerance, forbidden hop-by-hop header drops and last-wins
 * duplicates.
 */
class ProfileHeadersTest {

    @Test
    fun `normal lines parse into an ordered map`() {
        val headers = ProfileHeaders.parse(
            listOf(
                "X-Sub-Auth: token123",
                "Accept: application/yaml",
            ),
        )
        assertEquals(
            linkedMapOf("X-Sub-Auth" to "token123", "Accept" to "application/yaml"),
            headers,
        )
    }

    @Test
    fun `names and values are trimmed of spaces`() {
        val headers = ProfileHeaders.parse(listOf("  X-Token :   abc 123  "))
        assertEquals(mapOf("X-Token" to "abc 123"), headers)
    }

    @Test
    fun `blank lines and lines without a colon are ignored`() {
        val headers = ProfileHeaders.parse(
            listOf(
                "",
                "   ",
                "not a header line",
                ": no name",
                "X-Ok: yes",
            ),
        )
        assertEquals(mapOf("X-Ok" to "yes"), headers)
    }

    @Test
    fun `forbidden hop-by-hop headers are dropped`() {
        val headers = ProfileHeaders.parse(
            listOf(
                "Host: evil.example.com",
                "content-length: 9999",
                "Connection: close",
                "X-Keep: yes",
            ),
        )
        assertEquals(mapOf("X-Keep" to "yes"), headers)
    }

    @Test
    fun `blank names and values are dropped`() {
        val headers = ProfileHeaders.parse(listOf("X-Empty:   ", "X-Ok: v"))
        assertEquals(mapOf("X-Ok" to "v"), headers)
    }

    @Test
    fun `a repeated name keeps the last value`() {
        val headers = ProfileHeaders.parse(listOf("X-Token: first", "X-Token: second"))
        assertEquals(mapOf("X-Token" to "second"), headers)
    }

    @Test
    fun `sanitize mirrors the line rules for direct map input`() {
        val headers = ProfileHeaders.sanitize(
            mapOf(
                " host " to "gone.example.com",
                "X-Trim" to "  v ",
                "" to "x",
                "X-Blank" to "",
                "X-Kept" to "yes",
            ),
        )
        assertEquals(mapOf("X-Trim" to "v", "X-Kept" to "yes"), headers)
    }
}
