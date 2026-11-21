package com.neurorift.android.engine

/**
 * Engine-facing settings subset (issue #10). The full settings document with
 * persistence lands with #14; the engine consumes this data class and the
 * generated config always mirrors the keys documented in
 * docs/ARCHITECTURE.md §4 (identical to the Windows overlay).
 */
data class EngineSettings(
    val mixedPort: Int = 7897,
    val controllerPort: Int = 9097,
    val speedTestPort: Int = 19097,
    val controllerSecret: String,
    val allowLan: Boolean = false,
    val mode: String = "rule",
    val logLevel: String = "info",
    val appLogLevel: String = "info",
    val tunStack: String = "gvisor",
    val tunMtu: Int = 9000,
    val testUrl: String = "https://www.gstatic.com/generate_204",
    val speedTestUrl: String = "https://speed.cloudflare.com/__down?bytes=25000000",
    val speedUploadUrl: String = "https://speed.cloudflare.com/__up",
    val dnsOverride: Boolean = true,
    val dnsNameservers: List<String> = listOf("223.5.5.5", "1.1.1.1"),
    /** User routing overrides injected into every generated config (#17). */
    val routingOverrides: List<ConfigGen.RoutingRule> = emptyList(),
) {
    companion object {
        /** Gateway prefixes handed to mihomo's tun listener (mirrors the VPN builder). */
        const val TUN_GATEWAY = "172.19.0.1/30,fdfe:dcba:9876::1/126"

        /** DNS hijack target = the VPN's own address (addDnsServer uses it too). */
        const val TUN_DNS = "172.19.0.2"

        /** Default tun stack (CMFA-proven on Android); user setting in #14. */
        const val DEFAULT_TUN_STACK = "gvisor"

        /** Default set with a fresh random secret (per-#14 persistence later). */
        fun withGeneratedSecret(): EngineSettings =
            EngineSettings(controllerSecret = SecretGenerator.generate())
    }
}

/**
 * Random 32-char controller secret — generated once and kept for the process
 * lifetime (persistence arrives with #14). Bind 127.0.0.1 only (ARCHITECTURE
 * §10).
 */
object SecretGenerator {
    private const val ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

    fun generate(length: Int = 32): String {
        val random = java.security.SecureRandom()
        return buildString { repeat(length) { append(ALPHABET[random.nextInt(ALPHABET.length)]) } }
    }
}
