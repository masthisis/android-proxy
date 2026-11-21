package com.neurorift.android.settings

/**
 * Validation clamps for the settings document (issue #14) — the Android
 * mirror of the Windows internal/settings validation. [sanitize] returns the
 * corrected document; every bounded field is clamped, enum-like fields fall
 * back to their default on unknown values, port conflicts are resolved by
 * bumping the later entries, and the controller secret is preserved
 * (auto-generated, read-only).
 */
object SettingsValidator {

    const val PORT_MIN = 1024
    const val PORT_MAX = 65535
    const val MTU_MIN = 1280
    const val MTU_MAX = 9000
    const val TIMEOUT_MIN = 1000
    const val TIMEOUT_MAX = 30_000
    const val SPEED_STREAMS_MAX = 16
    const val SPEED_MAX_SECONDS_MIN = 5
    const val SPEED_MAX_SECONDS_MAX = 600
    const val SPEED_MAX_MB_MAX = 1024
    const val FAILOVER_INTERVAL_MIN = 15
    const val STRIKES_MAX = 10
    const val ROUTING_OVERRIDES_MAX = 200

    private val LANGUAGES = setOf("system", "en", "fa")
    private val MODES = setOf("rule", "global", "direct")
    private val TUN_STACKS = setOf("system", "gvisor", "mixed")
    private val LOG_LEVELS = setOf("debug", "info", "warning", "error", "silent")
    private val SPLIT_MODES = setOf("allowlist", "denylist", "off")
    private val ROUTING_TYPES = setOf("domain-suffix", "domain", "domain-keyword", "domain-regex", "process-name")

    /** Result of a save: the sanitized document + count of applied fixes. */
    data class Sanitized(val settings: Settings, val fixes: List<String>)

    fun sanitize(input: Settings): Sanitized {
        val fixes = mutableListOf<String>()
        var s = input

        // --- enum-like fields ---------------------------------------------
        if (s.language !in LANGUAGES) {
            fixes.add("language")
            s = s.copy(language = "system")
        }
        if (s.mode !in MODES) {
            fixes.add("mode")
            s = s.copy(mode = "rule")
        }
        if (s.tun.stack !in TUN_STACKS) {
            fixes.add("tun.stack")
            s = s.copy(tun = s.tun.copy(stack = "gvisor"))
        }
        if (s.log.appLevel !in LOG_LEVELS) {
            fixes.add("log.appLevel")
            s = s.copy(log = s.log.copy(appLevel = "info"))
        }
        if (s.log.coreLevel !in LOG_LEVELS) {
            fixes.add("log.coreLevel")
            s = s.copy(log = s.log.copy(coreLevel = "info"))
        }
        if (s.splitTunneling.mode !in SPLIT_MODES) {
            fixes.add("splitTunneling.mode")
            s = s.copy(splitTunneling = s.splitTunneling.copy(mode = "off"))
        }

        // --- bounded fields -------------------------------------------------
        val mixed = s.ports.mixed.coerceIn(PORT_MIN, PORT_MAX).also {
            if (it != s.ports.mixed) fixes.add("ports.mixed")
        }
        val controller = s.ports.controller.coerceIn(PORT_MIN, PORT_MAX).also {
            if (it != s.ports.controller) fixes.add("ports.controller")
        }
        val speedTest = s.ports.speedTest.coerceIn(PORT_MIN, PORT_MAX).also {
            if (it != s.ports.speedTest) fixes.add("ports.speedTest")
        }

        // Port conflict resolution: keep the first, bump later ones (+1
        // wrapping inside bounds) until all three are distinct.
        var m = mixed
        var c = controller
        var t = speedTest
        if (c == m) c = bump(c, fixes, "ports.controller")
        if (t == m || t == c) {
            t = bump(t, fixes, "ports.speedTest")
            if (t == m) t = bump(t, fixes, "ports.speedTest")
            if (t == c) t = bump(t, fixes, "ports.speedTest")
        }
        s = s.copy(ports = Settings.Ports(mixed = m, controller = c, speedTest = t))

        s = clamp(s) { it.copy(test = it.test.copy(timeoutMs = it.test.timeoutMs.coerceIn(TIMEOUT_MIN, TIMEOUT_MAX))) }
        s = clamp(s) { it.copy(test = it.test.copy(speedStreams = it.test.speedStreams.coerceIn(1, SPEED_STREAMS_MAX))) }
        s = clamp(s) { it.copy(test = it.test.copy(speedMaxSeconds = it.test.speedMaxSeconds.coerceIn(SPEED_MAX_SECONDS_MIN, SPEED_MAX_SECONDS_MAX))) }
        s = clamp(s) { it.copy(test = it.test.copy(speedMaxMb = it.test.speedMaxMb.coerceIn(1, SPEED_MAX_MB_MAX))) }
        s = clamp(s) { it.copy(failover = it.failover.copy(intervalSec = it.failover.intervalSec.coerceAtLeast(FAILOVER_INTERVAL_MIN))) }
        s = clamp(s) { it.copy(failover = it.failover.copy(timeoutMs = it.failover.timeoutMs.coerceIn(TIMEOUT_MIN, TIMEOUT_MAX))) }
        s = clamp(s) { it.copy(failover = it.failover.copy(strikes = it.failover.strikes.coerceIn(1, STRIKES_MAX))) }
        s = clamp(s) { it.copy(failover = it.failover.copy(cooldownSec = it.failover.cooldownSec.coerceAtLeast(0))) }
        s = clamp(s) { it.copy(tun = it.tun.copy(mtu = it.tun.mtu.coerceIn(MTU_MIN, MTU_MAX))) }
        s = clamp(s) { it.copy(subscription = it.subscription.copy(defaultIntervalH = it.subscription.defaultIntervalH.coerceIn(0, 168))) }

        // --- list hygiene ---------------------------------------------------
        s = s.copy(
            dns = s.dns.copy(nameservers = s.dns.nameservers.filter { it.isNotBlank() }),
            bypassDomains = s.bypassDomains.map { it.trim() }.filter { it.isNotEmpty() },
            splitTunneling = s.splitTunneling.copy(apps = s.splitTunneling.apps.filter { it.isNotBlank() }.distinct()),
        )

        // Routing overrides (#17): trim pattern/target, drop blank entries,
        // normalize the type into the known set and cap the list size.
        val overrides = s.routingOverrides.mapNotNull { override ->
            val pattern = override.pattern.trim()
            val target = override.target.trim()
            if (pattern.isEmpty() || target.isEmpty()) return@mapNotNull null
            val type = override.type.trim().lowercase()
            override.copy(
                pattern = pattern,
                target = target,
                type = if (type in ROUTING_TYPES) type else "domain-suffix",
            )
        }
        if (overrides.size > ROUTING_OVERRIDES_MAX) {
            fixes.add("routingOverrides.count")
        }
        s = s.copy(routingOverrides = overrides.take(ROUTING_OVERRIDES_MAX))

        return Sanitized(settings = s, fixes = fixes)
    }

    private inline fun clamp(s: Settings, fix: (Settings) -> Settings): Settings = fix(s)

    private fun bump(port: Int, fixes: MutableList<String>, field: String): Int {
        fixes.add(field)
        return if (port >= PORT_MAX) PORT_MIN else port + 1
    }
}
