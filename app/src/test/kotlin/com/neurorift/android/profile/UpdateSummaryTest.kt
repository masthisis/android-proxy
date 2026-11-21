package com.neurorift.android.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ProfileManager.UpdateSummary] fold tests (issue #18): Update All merges
 * the bounded-parallel per-profile partials — counts add up, error details
 * concatenate in profile order.
 */
class UpdateSummaryTest {

    @Test
    fun `merging partial failures adds counts and concatenates errors`() {
        val a = ProfileManager.UpdateSummary(updated = 2, failed = 1, errors = listOf("alpha: HTTP 403"))
        val b = ProfileManager.UpdateSummary(failed = 1, skipped = 1, errors = listOf("beta: timeout"))

        val merged = a + b

        assertEquals(2, merged.updated)
        assertEquals(2, merged.failed)
        assertEquals(1, merged.skipped)
        assertEquals(listOf("alpha: HTTP 403", "beta: timeout"), merged.errors)
    }

    @Test
    fun `folding many partials is associative`() {
        val parts = listOf(
            ProfileManager.UpdateSummary(updated = 1),
            ProfileManager.UpdateSummary(failed = 1, errors = listOf("e1")),
            ProfileManager.UpdateSummary(skipped = 1),
            ProfileManager.UpdateSummary(updated = 1, failed = 1, errors = listOf("e2", "e3")),
        )

        val folded = parts.reduce { acc, part -> acc + part }
        val rightFolded = parts.reduceRight { part, acc -> part + acc }

        assertEquals(2, folded.updated)
        assertEquals(2, folded.failed)
        assertEquals(1, folded.skipped)
        assertEquals(listOf("e1", "e2", "e3"), folded.errors)
        assertEquals(folded, rightFolded)
    }

    @Test
    fun `empty summaries fold into the identity element`() {
        val empty = ProfileManager.UpdateSummary()
        val summary = ProfileManager.UpdateSummary(updated = 3, failed = 1, errors = listOf("x"))

        assertEquals(summary, empty + summary)
        assertEquals(summary, summary + empty)
    }

    @Test
    fun `empty run produces an all-zero summary`() {
        val summary = ProfileManager.UpdateSummary()
        assertEquals(0, summary.updated)
        assertEquals(0, summary.failed)
        assertEquals(0, summary.skipped)
        assertTrue(summary.errors.isEmpty())
    }
}
