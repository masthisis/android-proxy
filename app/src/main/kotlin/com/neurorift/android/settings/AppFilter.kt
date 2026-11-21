package com.neurorift.android.settings

/**
 * Split-tunneling application filter (issue #14): pure decision logic so the
 * VPN builder wiring is JVM-testable. Mirrors v2rayNG's per-app proxy
 * semantics — allowlist = ONLY listed apps go through the tunnel; denylist =
 * listed apps bypass it; off = everything routes.
 */
object AppFilter {

    data class Decision(val allowedApps: List<String>, val disallowedApps: List<String>)

    /**
     * Computes the addAllowedApplication / addDisallowedApplication sets for
     * the VPN builder.
     */
    fun decide(mode: String, apps: List<String>): Decision = when (mode) {
        "allowlist" -> Decision(allowedApps = apps, disallowedApps = emptyList())
        "denylist" -> Decision(allowedApps = emptyList(), disallowedApps = apps)
        else -> Decision(allowedApps = emptyList(), disallowedApps = emptyList())
    }
}
