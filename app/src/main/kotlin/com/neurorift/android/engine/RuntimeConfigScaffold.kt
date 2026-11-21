package com.neurorift.android.engine

import java.security.SecureRandom

/**
 * Minimal runtime config used by the VPN layer (#8) so the core can start
 * end-to-end before the full config generator lands (#10). Issue #10
 * replaces this with the complete generator (providers, hidden speed-test
 * group, routing) — the overlay keys (controller, secret, ports,
 * store-selected, unified-delay, tcp-concurrent) already match the final
 * shape documented in docs/ARCHITECTURE.md §4.
 */
object RuntimeConfigScaffold {

    const val MIXED_PORT = 7897
    const val CONTROLLER_PORT = 9097
    const val VPN_ADDR4 = "172.19.0.2"
    const val VPN_PREFIX4 = 30
    const val VPN_ADDR6 = "fdfe:dcba:9876::2"
    const val VPN_PREFIX6 = 126

    /** Gateway prefixes handed to mihomo's tun listener (mirrors the VPN builder). */
    const val TUN_GATEWAY = "172.19.0.1/30,fdfe:dcba:9876::1/126"

    /** DNS hijack target = the VPN's own address (addDnsServer uses it too). */
    const val TUN_DNS = VPN_ADDR4

    /** Default tun stack (CMFA-proven on Android). Setting arrives with #14. */
    const val TUN_STACK = "gvisor"

    /** Generates the controller secret once per process; persisted in #10/#14. */
    fun generateSecret(): String {
        val chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        val random = SecureRandom()
        return buildString { repeat(32) { append(chars[random.nextInt(chars.length)]) } }
    }

    /** The idle config: DIRECT-only routing, controller enabled. */
    fun idleConfig(secret: String): String = """
        # NeuroRift runtime config (scaffold — replaced by the full generator in #10)
        mixed-port: $MIXED_PORT
        external-controller: 127.0.0.1:$CONTROLLER_PORT
        secret: $secret
        mode: rule
        log-level: info
        unified-delay: true
        tcp-concurrent: true
        profile:
          store-selected: true
          store-fake-ip: true
        dns:
          enable: true
          listen: 0.0.0.0:1053
          enhanced-mode: fake-ip
          fake-ip-range: 198.18.0.1/16
          fake-ip-filter: ["*.lan", "*.local"]
          nameserver:
            - 223.5.5.5
            - 1.1.1.1
        proxy-groups:
          - name: "🚀 Node Select"
            type: select
            proxies:
              - DIRECT
        rules:
          - MATCH,DIRECT
    """.trimIndent()
}
