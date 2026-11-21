package com.neurorift.android.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [Semver] ordering tests (issue #20): the triple comparison, the v-strip,
 * prereleases sorting below their own plain version, and the prerelease
 * identifier rules that decide which GitHub release wins.
 */
class SemverTest {

    @Test
    fun `plain ordering of the numeric triple`() {
        assertTrue(Semver.isNewer("1.2.4", "1.2.3"))
        assertTrue(Semver.isNewer("1.3.0", "1.2.9"))
        assertTrue(Semver.isNewer("2.0.0", "1.9.9"))
        assertFalse(Semver.isNewer("1.2.3", "1.2.10"))
        assertFalse(Semver.isNewer("1.2.3", "1.2.3"))
    }

    @Test
    fun `leading v is stripped on both sides`() {
        assertEquals(0, Semver.compare("v1.2.3", "1.2.3"))
        assertEquals(0, Semver.compare("V1.2.3", "v1.2.3"))
        assertTrue(Semver.isNewer("v1.3.0", "v1.2.3"))
    }

    @Test
    fun `prerelease sorts below its own plain version`() {
        assertTrue(Semver.isNewer("1.2.3", "1.2.3-rc.1"))
        assertFalse(Semver.isNewer("1.2.3-rc.1", "1.2.3"))
        assertTrue(Semver.isNewer("1.2.4-rc.1", "1.2.3"))
    }

    @Test
    fun `prerelease identifiers compare numerically then lexically`() {
        assertTrue(Semver.isNewer("1.2.3-rc.2", "1.2.3-rc.1"))
        // Numeric identifiers compare as numbers, not strings ("9" < "10").
        assertTrue(Semver.isNewer("1.2.3-rc.10", "1.2.3-rc.9"))
        // Alphanumeric: "alpha" < "beta" < "rc".
        assertTrue(Semver.isNewer("1.2.3-rc.1", "1.2.3-beta.4"))
        assertTrue(Semver.isNewer("1.2.3-beta.1", "1.2.3-alpha.9"))
        // Numeric identifiers rank below alphanumeric ones.
        assertTrue(Semver.isNewer("1.2.3-alpha", "1.2.3-1"))
        // More identifiers win when the shared prefix is equal.
        assertTrue(Semver.isNewer("1.2.3-rc.1.1", "1.2.3-rc.1"))
    }

    @Test
    fun `equal versions compare equal`() {
        assertEquals(0, Semver.compare("1.2.3", "1.2.3"))
        assertEquals(0, Semver.compare("1.2.3", "v1.2.3"))
        assertEquals(0, Semver.compare("1.2.3-rc.1+build.7", "1.2.3-rc.1"))
        assertEquals(0, Semver.compare("1.2.3+meta", "1.2.3"))
    }

    @Test
    fun `compare returns a usable three-way sign`() {
        assertTrue(Semver.compare("1.2.3", "1.2.4") < 0)
        assertTrue(Semver.compare("1.2.5", "1.2.4") > 0)
        assertEquals(0, Semver.compare("2.10.0", "v2.10.0"))
    }
}
