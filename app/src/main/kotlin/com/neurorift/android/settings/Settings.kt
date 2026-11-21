package com.neurorift.android.settings

import com.neurorift.android.engine.SecretGenerator
import kotlinx.serialization.Serializable

/**
 * The settings document (issue #14) — the compatibility surface with the
 * Windows client (CONTRACT.md "Settings document"). Every field carries the
 * Windows default; [SettingsValidator.sanitize] enforces the same clamps.
 * New keys are migration-safe: defaults apply for missing JSON keys.
 */
@Serializable
data class Settings(
    val language: String = "system",
    val startOnBoot: Boolean = false,
    val allowLan: Boolean = false,
    val mode: String = "rule",
    val ports: Ports = Ports(),
    val test: TestConfig = TestConfig(),
    val failover: Failover = Failover(),
    val dns: Dns = Dns(),
    val tun: Tun = Tun(),
    val subscription: SubscriptionDefaults = SubscriptionDefaults(),
    val log: LogLevels = LogLevels(),
    val updates: Updates = Updates(),
    val routingOverrides: List<RoutingOverride> = emptyList(),
    val splitTunneling: SplitTunneling = SplitTunneling(),
    val bypassDomains: List<String> = emptyList(),
    /** Auto-generated once; read-only in practice (never overwritten by input). */
    val controllerSecret: String = SecretGenerator.generate(),
) {
    @Serializable
    data class Ports(
        val mixed: Int = 7897,
        val controller: Int = 9097,
        val speedTest: Int = 19097,
    )

    @Serializable
    data class TestConfig(
        val url: String = "https://www.gstatic.com/generate_204",
        val timeoutMs: Int = 5000,
        val speedUrl: String = "https://speed.cloudflare.com/__down?bytes=25000000",
        val speedUploadUrl: String = "https://speed.cloudflare.com/__up",
        val speedStreams: Int = 4,
        val speedMaxSeconds: Int = 15,
        val speedMaxMb: Int = 100,
        val uploadTest: Boolean = true,
    )

    @Serializable
    data class Failover(
        val enable: Boolean = true,
        val intervalSec: Int = 60,
        val timeoutMs: Int = 5000,
        val strikes: Int = 3,
        val cooldownSec: Int = 120,
    )

    @Serializable
    data class Dns(
        val override: Boolean = true,
        val nameservers: List<String> = listOf("223.5.5.5", "1.1.1.1"),
    )

    @Serializable
    data class Tun(
        val stack: String = "gvisor",
        val mtu: Int = 9000,
    )

    @Serializable
    data class SubscriptionDefaults(
        val defaultIntervalH: Int = 24,
        val updateViaProxy: Boolean = false,
    )

    @Serializable
    data class LogLevels(
        val appLevel: String = "info",
        val coreLevel: String = "info",
    )

    @Serializable
    data class Updates(
        val autoCheck: Boolean = true,
        val includePrerelease: Boolean = false,
    )

    @Serializable
    data class RoutingOverride(
        val pattern: String,
        val type: String = "DOMAIN-SUFFIX",
        val target: String = "DIRECT",
    )

    @Serializable
    data class SplitTunneling(
        val mode: String = "off",
        val apps: List<String> = emptyList(),
    )
}
