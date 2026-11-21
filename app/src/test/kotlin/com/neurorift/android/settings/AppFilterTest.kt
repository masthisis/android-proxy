package com.neurorift.android.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/** Split-tunneling decision table (issue #14). */
class AppFilterTest {

    @Test
    fun `allowlist routes only the listed apps`() {
        val d = AppFilter.decide("allowlist", listOf("com.a", "com.b"))
        assertEquals(listOf("com.a", "com.b"), d.allowedApps)
        assertEquals(0, d.disallowedApps.size)
    }

    @Test
    fun `denylist bypasses the listed apps`() {
        val d = AppFilter.decide("denylist", listOf("com.a"))
        assertEquals(0, d.allowedApps.size)
        assertEquals(listOf("com.a"), d.disallowedApps)
    }

    @Test
    fun `off routes everything`() {
        val d = AppFilter.decide("off", listOf("com.a"))
        assertEquals(0, d.allowedApps.size)
        assertEquals(0, d.disallowedApps.size)
    }
}
