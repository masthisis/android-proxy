package com.neurorift.android.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Settings validation clamps (issue #14) — every bounded field is pinned to
 * match the Windows validation behaviour.
 */
class SettingsValidatorTest {

    @Test
    fun `ports clamp into the valid range`() {
        val fixed = SettingsValidator.sanitize(
            Settings(ports = Settings.Ports(mixed = 80, controller = 99999, speedTest = -5)),
        ).settings
        assertTrue(fixed.ports.mixed in SettingsValidator.PORT_MIN..SettingsValidator.PORT_MAX)
        assertTrue(fixed.ports.controller in SettingsValidator.PORT_MIN..SettingsValidator.PORT_MAX)
        assertTrue(fixed.ports.speedTest in SettingsValidator.PORT_MIN..SettingsValidator.PORT_MAX)
    }

    @Test
    fun `port conflicts resolve by bumping later entries`() {
        val fixed = SettingsValidator.sanitize(
            Settings(ports = Settings.Ports(mixed = 7897, controller = 7897, speedTest = 7898)),
        ).settings
        assertNotEquals(fixed.ports.controller, fixed.ports.mixed)
        assertNotEquals(fixed.ports.speedTest, fixed.ports.mixed)
        assertNotEquals(fixed.ports.speedTest, fixed.ports.controller)
    }

    @Test
    fun `test fields clamp`() {
        val fixed = SettingsValidator.sanitize(
            Settings(
                test = Settings.TestConfig(
                    timeoutMs = 1,
                    speedStreams = 64,
                    speedMaxSeconds = 0,
                    speedMaxMb = 99999,
                ),
            ),
        ).settings
        assertEquals(SettingsValidator.TIMEOUT_MIN, fixed.test.timeoutMs)
        assertEquals(16, fixed.test.speedStreams)
        assertEquals(SettingsValidator.SPEED_MAX_SECONDS_MIN, fixed.test.speedMaxSeconds)
        assertEquals(SettingsValidator.SPEED_MAX_MB_MAX, fixed.test.speedMaxMb)
    }

    @Test
    fun `failover clamps interval strikes and cooldown`() {
        val fixed = SettingsValidator.sanitize(
            Settings(failover = Settings.Failover(intervalSec = 1, strikes = 99, cooldownSec = -3, timeoutMs = 1)),
        ).settings
        assertEquals(SettingsValidator.FAILOVER_INTERVAL_MIN, fixed.failover.intervalSec)
        assertTrue(fixed.failover.strikes in 1..SettingsValidator.STRIKES_MAX)
        assertEquals(0, fixed.failover.cooldownSec)
        assertTrue(fixed.failover.timeoutMs >= SettingsValidator.TIMEOUT_MIN)
    }

    @Test
    fun `tun mtu clamps and unknown stack falls back`() {
        val fixed = SettingsValidator.sanitize(
            Settings(tun = Settings.Tun(stack = "weird", mtu = 100)),
        ).settings
        assertEquals("gvisor", fixed.tun.stack)
        assertEquals(SettingsValidator.MTU_MIN, fixed.tun.mtu)
    }

    @Test
    fun `enum-like fields fall back to defaults`() {
        val fixed = SettingsValidator.sanitize(
            Settings(language = "de", mode = "chaos", splitTunneling = Settings.SplitTunneling(mode = "x")),
        ).settings
        assertEquals("system", fixed.language)
        assertEquals("rule", fixed.mode)
        assertEquals("off", fixed.splitTunneling.mode)
    }

    @Test
    fun `controller secret is never overwritten`() {
        val input = Settings(controllerSecret = "attacker-set-secret")
        val fixed = SettingsValidator.sanitize(input).settings
        assertEquals(input.controllerSecret, fixed.controllerSecret)
    }

    @Test
    fun `default document stays untouched`() {
        val fixed = SettingsValidator.sanitize(Settings()).settings
        // The secret is random per instance — compare with it normalized.
        assertEquals(Settings(controllerSecret = fixed.controllerSecret), fixed)
        assertTrue(fixed.ports.mixed in SettingsValidator.PORT_MIN..SettingsValidator.PORT_MAX)
    }

    @Test
    fun `empty nameservers and blank apps are dropped`() {
        val fixed = SettingsValidator.sanitize(
            Settings(
                dns = Settings.Dns(nameservers = listOf("", "1.1.1.1")),
                splitTunneling = Settings.SplitTunneling(apps = listOf("a", "a", " ")),
            ),
        ).settings
        assertEquals(listOf("1.1.1.1"), fixed.dns.nameservers)
        assertEquals(listOf("a"), fixed.splitTunneling.apps)
    }

    // ------------------------------------------------------ routing overrides --

    @Test
    fun `routing overrides clamp to the two-hundred cap`() {
        val many = (0 until 201).map {
            Settings.RoutingOverride(pattern = "p$it", type = "domain-suffix", target = "DIRECT")
        }
        val result = SettingsValidator.sanitize(Settings(routingOverrides = many))
        assertEquals(200, result.settings.routingOverrides.size)
        assertTrue(result.fixes.contains("routingOverrides.count"))
        assertEquals("p0", result.settings.routingOverrides.first().pattern)
        assertEquals("p199", result.settings.routingOverrides.last().pattern)
    }

    @Test
    fun `routing overrides trim fields and drop blank entries`() {
        val fixed = SettingsValidator.sanitize(
            Settings(
                routingOverrides = listOf(
                    Settings.RoutingOverride(pattern = "  ok.example  ", type = "DOMAIN", target = " DIRECT "),
                    Settings.RoutingOverride(pattern = "   ", type = "domain-suffix", target = "DIRECT"),
                    Settings.RoutingOverride(pattern = "bad.example", type = "domain-suffix", target = "   "),
                ),
            ),
        ).settings
        assertEquals(1, fixed.routingOverrides.size)
        assertEquals("ok.example", fixed.routingOverrides[0].pattern)
        assertEquals("DIRECT", fixed.routingOverrides[0].target)
        // The uppercase kind is normalized into the lowercase known set.
        assertEquals("domain", fixed.routingOverrides[0].type)
    }

    @Test
    fun `routing overrides unknown type falls back to domain-suffix`() {
        val fixed = SettingsValidator.sanitize(
            Settings(
                routingOverrides = listOf(
                    Settings.RoutingOverride(pattern = "x.example", type = "ip-cidr", target = "DIRECT"),
                ),
            ),
        ).settings
        assertEquals("domain-suffix", fixed.routingOverrides.single().type)
        assertEquals("x.example", fixed.routingOverrides.single().pattern)
    }
}
