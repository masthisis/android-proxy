package com.neurorift.android.nettest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SpeedMath] contract tests (issue #15): the pure Mbps conversion the
 * speed-test engine and the Test Center UI both rely on.
 */
class SpeedMathTest {

    @Test
    fun `12500000 bytes in 10 seconds is 10 Mbps`() {
        assertEquals(10.0, SpeedMath.mbps(12_500_000, 10_000), 1e-9)
    }

    @Test
    fun `1250000 bytes in 1 second is 10 Mbps`() {
        assertEquals(10.0, SpeedMath.mbps(1_250_000, 1_000), 1e-9)
    }

    @Test
    fun `100 MB in 8 seconds is 100 Mbps`() {
        assertEquals(100.0, SpeedMath.mbps(100_000_000, 8_000), 1e-9)
    }

    @Test
    fun `zero bytes yield zero`() {
        assertEquals(0.0, SpeedMath.mbps(0, 10_000), 0.0)
        assertEquals(0.0, SpeedMath.mbps(-5, 10_000), 0.0)
    }

    @Test
    fun `zero or negative interval yields zero`() {
        assertEquals(0.0, SpeedMath.mbps(12_500_000, 0), 0.0)
        assertEquals(0.0, SpeedMath.mbps(12_500_000, -1), 0.0)
    }

    @Test
    fun `sub-second intervals scale linearly`() {
        // 12.5 MB in 500 ms = 200 Mbps.
        assertEquals(200.0, SpeedMath.mbps(12_500_000, 500), 1e-9)
    }

    @Test
    fun `huge transfers stay finite (no overflow)`() {
        val bytes = Long.MAX_VALUE / 8
        val mbps = SpeedMath.mbps(bytes, 1)
        assertTrue(mbps > 0.0)
        assertTrue(mbps.isFinite())
    }

    @Test
    fun `fractional Mbps keep precision`() {
        // 1_562_500 bytes over 10_000 ms = 1.25 Mbps exactly.
        assertEquals(1.25, SpeedMath.mbps(1_562_500, 10_000), 1e-9)
    }
}
