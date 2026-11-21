package com.neurorift.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppInfoTest {

    @Test
    fun `fullVersion includes brand and fallback when init was skipped`() {
        val full = AppInfo.fullVersion()
        assertEquals("NeuroRift for Android unknown", full)
    }

    @Test
    fun `brand constants match the Windows sibling conventions`() {
        assertEquals("NeuroRift", AppInfo.APP_NAME)
        assertEquals("masthisis/android-proxy", AppInfo.GITHUB_REPO)
        assertTrue(AppInfo.BRAND.contains("Android"))
    }
}
