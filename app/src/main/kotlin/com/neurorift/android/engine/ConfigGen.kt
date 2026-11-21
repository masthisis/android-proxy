package com.neurorift.android.engine

import com.neurorift.android.engine.Groups.LISTENER_SPEED_TEST
import com.neurorift.android.engine.Groups.AUTO_SELECT
import com.neurorift.android.engine.Groups.NODE_SELECT
import com.neurorift.android.engine.Groups.SPEED_TEST
import com.neurorift.android.profile.ProfileKind
import com.neurorift.android.profile.ProfileMeta
import org.yaml.snakeyaml.Yaml as SnakeYaml

/**
 * The mihomo runtime-config generator — the Android port of the Windows
 * `internal/engine/configgen`, keeping the same two-shape strategy and the
 * same overlay guarantees (ARCHITECTURE.md §3/§4):
 *
 * - NODES profiles: app-owned config; every imported subscription is loaded
 *   as a file proxy-provider (so the Test Center can probe any of them),
 *   routing groups stay scoped to the active provider via `use`.
 * - CLASH/LOCAL profiles: the user document is parsed, the overlay is
 *   injected, everything the user wrote is preserved verbatim — a drop-in
 *   replacement for existing setups.
 *
 * Android deviation from Windows: there is NO `tun:` block — the tun
 * listener is created in Go code from the VpnService fd (ARCHITECTURE §4).
 * DNS stays enabled unconditionally: the tun DNS-hijack target needs it.
 */
object ConfigGen {

    /**
     * A single user-defined routing override (issue #17) — the neutral,
     * engine-side rule shape the settings layer maps its persisted
     * `routingOverrides` document onto. `type` is the lowercase mihomo rule
     * kind ("domain-suffix", "domain", "domain-keyword", "domain-regex",
     * "process-name"); `target` is a policy name (DIRECT/REJECT or a proxy
     * group). Kept engine-side so the dependency direction stays settings →
     * engine.
     */
    data class RoutingRule(
        val type: String,
        val pattern: String,
        val target: String,
    )

    /** Provider key for a profile id — mirrors the Windows providerName(). */
    fun providerName(profileID: String): String = "sub-$profileID"

    /**
     * Renders the runtime config for the given profiles; [activeId] selects the
     * routed profile (null = idle). [contentOf] resolves profile file
     * contents (raw YAML for CLASH/LOCAL; unused for NODES, whose provider
     * files are read by mihomo directly).
     *
     * [unionIds] (#23) lists the profiles joined into the union routing set:
     * their NODES providers are appended to the Node Select `use` list after
     * the primary, in registry order, so multiple subscriptions route as ONE
     * set. Members already materialized by [appProviders] only start being
     * referenced here — union providers get no health-check (only the active
     * provider keeps one) and CLASH-kind / unknown ids are ignored.
     */
    fun generate(
        settings: EngineSettings,
        profiles: List<ProfileMeta>,
        activeId: String?,
        unionIds: List<String> = emptyList(),
        overrides: List<RoutingRule> = emptyList(),
        contentOf: (ProfileMeta) -> String,
    ): String {
        val active = profiles.firstOrNull { it.id == activeId }
        return if (active != null && (active.kind == ProfileKind.CLASH || active.kind == ProfileKind.LOCAL)) {
            generateClash(settings, profiles, active, contentOf, overrides)
        } else {
            generateNodes(
                settings,
                profiles,
                active?.takeIf { it.kind == ProfileKind.NODES },
                overrides,
                unionIds,
            )
        }
    }

    // ---------------------------------------------------------------- nodes --

    private fun generateNodes(
        settings: EngineSettings,
        profiles: List<ProfileMeta>,
        active: ProfileMeta?,
        overrides: List<RoutingRule>,
        unionIds: List<String> = emptyList(),
    ): String {
        val root = OMap()
            .set("mode", settings.mode)
            .set("mixed-port", settings.mixedPort)
            .set("allow-lan", settings.allowLan)
            .set("bind-address", if (settings.allowLan) "*" else "127.0.0.1")
            .set("ipv6", true)
            .set("log-level", settings.logLevel)
            .set("external-controller", "127.0.0.1:${settings.controllerPort}")
            .set("secret", settings.controllerSecret)
            .set("unified-delay", true)
            .set("tcp-concurrent", true)
            .set("geodata-mode", false)
            .set("geo-auto-update", true)
            .set("geo-update-interval", 168)
            .set("geox-url", geoMirrors())
            .set("profile", OMap().set("store-selected", true).set("store-fake-ip", true))
            .set("listeners", listOf(speedtestListener(settings)))

        root.set("dns", dnsBlock(settings))

        val providers = appProviders(settings, profiles, active?.id)
        if (providers.len() > 0) root.set("proxy-providers", providers)

        val groups = mutableListOf<Any>(
            OMap().set("name", SPEED_TEST).set("type", "select").set("include-all", true),
        )
        if (active != null) {
            // Union routing set (#23): the primary provider first, then the
            // union members in registry order (NODES kinds only, primary
            // excluded — it is already first).
            val useProviders = buildList {
                add(providerName(active.id))
                for (profile in profiles) {
                    if (profile.id != active.id && profile.kind == ProfileKind.NODES &&
                        profile.id in unionIds
                    ) {
                        add(providerName(profile.id))
                    }
                }
            }
            groups.add(
                OMap()
                    .set("name", NODE_SELECT)
                    .set("type", "select")
                    .set("proxies", listOf("DIRECT"))
                    .set("use", useProviders),
            )
            groups.add(
                OMap()
                    .set("name", AUTO_SELECT)
                    .set("type", "url-test")
                    .set("use", listOf(providerName(active.id)))
                    .set("interval", 300)
                    .set("tolerance", 50)
                    .set("lazy", true),
            )
        } else {
            groups.add(OMap().set("name", NODE_SELECT).set("type", "select").set("include-all", true))
            groups.add(
                OMap()
                    .set("name", AUTO_SELECT)
                    .set("type", "url-test")
                    .set("include-all", true)
                    .set("interval", 300)
                    .set("tolerance", 50)
                    .set("lazy", true),
            )
        }
        root.set("proxy-groups", groups)

        // Rule order: LAN direct rules first (never shadowed), then the
        // user's high-priority overrides (issue #17), then the catch-all.
        root.set("rules", lanRules() + overrideRules(overrides) + "MATCH,$NODE_SELECT")
        return Yaml.dump(root)
    }

    // ---------------------------------------------------------------- clash --

    private fun generateClash(
        settings: EngineSettings,
        profiles: List<ProfileMeta>,
        active: ProfileMeta,
        contentOf: (ProfileMeta) -> String,
        overrides: List<RoutingRule>,
    ): String {
        val raw = contentOf(active)
        val loaded = SnakeYaml().load<Any>(raw)
        @Suppress("UNCHECKED_CAST")
        val doc = loaded as? Map<String, Any?>
            ?: throw IllegalArgumentException("clash profile root is not a mapping")

        // Overlay — injected (never appended blind): user keys are replaced by
        // the overlay values exactly like the Windows splice.
        doc as MutableMap<String, Any?>
        doc["mode"] = settings.mode
        doc["mixed-port"] = settings.mixedPort
        doc["log-level"] = settings.logLevel
        doc["external-controller"] = "127.0.0.1:${settings.controllerPort}"
        doc["secret"] = settings.controllerSecret
        doc["allow-lan"] = settings.allowLan
        doc["bind-address"] = if (settings.allowLan) "*" else "127.0.0.1"
        doc["unified-delay"] = true
        doc["tcp-concurrent"] = true
        doc["profile"] = linkedMapOf<String, Any?>(
            "store-selected" to true,
            "store-fake-ip" to true,
        )

        // Android: DNS stays on (tun hijack needs it); nameservers follow the
        // settings.
        doc["dns"] = dnsBlock(settings).asJavaMap()

        // Inject the hidden speed-test group in front of the user's groups.
        @Suppress("UNCHECKED_CAST")
        val userGroups = (doc["proxy-groups"] as? List<Any?>)?.toMutableList() ?: mutableListOf()
        doc["proxy-groups"] = listOf<Any>(
            OMap().set("name", SPEED_TEST).set("type", "select").set("include-all", true).asJavaMap(),
        ) + userGroups

        // Inject the speed-test listener (merged with user listeners).
        @Suppress("UNCHECKED_CAST")
        val userListeners = (doc["listeners"] as? List<Any?>)?.toMutableList() ?: mutableListOf()
        doc["listeners"] = listOf<Any>(speedtestListener(settings).asJavaMap()) + userListeners

        // Every app provider stays loadable (Test Center parity, Windows #42);
        // user-defined providers are never overwritten.
        val providers = appProviders(settings, profiles, active.id)
        if (providers.len() > 0) {
            @Suppress("UNCHECKED_CAST")
            val existing = (doc["proxy-providers"] as? Map<String, Any?>)?.toMutableMap()
                ?: linkedMapOf<String, Any?>()
            
            for (key in providers.keys()) {
                if (!existing.containsKey(key)) existing[key] = (providers.get(key) as? OMap)?.asJavaMap() ?: providers.get(key)
            }
            // Note: existing values stay snakeyaml-native; injected ones are
            // converted via asJavaMap inside the providers map.
            doc["proxy-providers"] = existing
        }

        // Overrides ride after the user's own rules (issue #17); with an
        // empty user list the LAN block + catch-all MATCH fallback applies.
        val rules = doc["rules"] as? List<Any?>
        doc["rules"] = if (rules.isNullOrEmpty()) {
            lanRules() + overrideRules(overrides) + "MATCH,$NODE_SELECT"
        } else {
            rules + overrideRules(overrides)
        }

        return org.yaml.snakeyaml.DumperOptions().let { options ->
            options.defaultFlowStyle = org.yaml.snakeyaml.DumperOptions.FlowStyle.BLOCK
            options.isAllowUnicode = true
            SnakeYaml(options).dump(doc)
        }
    }

    // -------------------------------------------------------------- shared --

    /** File proxy-provider for EVERY nodes profile (all stay testable). */
    fun appProviders(
        settings: EngineSettings,
        profiles: List<ProfileMeta>,
        activeId: String?,
    ): OMap {
        val providers = OMap()
        for (profile in profiles) {
            if (profile.kind != ProfileKind.NODES) continue
            val path = profile.providerPath ?: continue
            val provider = OMap()
                .set("type", "file")
                .set("path", path)
                .set("interval", 60)
            if (profile.id == activeId) {
                provider.set(
                    "health-check",
                    OMap()
                        .set("enable", true)
                        .set("url", settings.testUrl)
                        .set("interval", 300),
                )
            }
            providers.set(providerName(profile.id), provider)
        }
        return providers
    }

    fun speedtestListener(settings: EngineSettings): OMap = OMap()
        .set("name", LISTENER_SPEED_TEST)
        .set("type", "mixed")
        .set("listen", "127.0.0.1")
        .set("port", settings.speedTestPort)
        .set("proxy", SPEED_TEST)

    /**
     * Android DNS block: always enabled (tun hijack), fake-ip, the VPN DNS
     * address as listener so the hijack resolves inside mihomo.
     */
    fun dnsBlock(settings: EngineSettings): OMap = OMap()
        .set("enable", true) // never disabled on Android: the tun hijack needs it
        .set("listen", "0.0.0.0:1053")
        .set("ipv6", true)
        .set("enhanced-mode", "fake-ip")
        .set("fake-ip-range", "198.18.0.1/16")
        .set(
            "fake-ip-filter",
            listOf("*.lan", "*.local", "*.localdomain", "time.*.com", "ntp.*.com", "+.market.xiaomi.com"),
        )
        .set("default-nameserver", listOf("1.1.1.1", "8.8.8.8"))
        .set("nameserver", settings.dnsNameservers)

    fun geoMirrors(): OMap {
        val base = "https://testingcf.jsdelivr.net/gh/MetaCubeX/meta-rules-dat@release/"
        return OMap()
            .set("geoip", base + "geoip-lite.dat")
            .set("geosite", base + "geosite.dat")
            .set("mmdb", base + "country-lite.mmdb")
            .set("asn", base + "GeoLite2-ASN.mmdb")
    }

    /** LAN stays direct; identical to the Windows lanRules(). */
    fun lanRules(): List<String> = listOf(
        "IP-CIDR,127.0.0.0/8,DIRECT,no-resolve",
        "IP-CIDR,10.0.0.0/8,DIRECT,no-resolve",
        "IP-CIDR,172.16.0.0/12,DIRECT,no-resolve",
        "IP-CIDR,192.168.0.0/16,DIRECT,no-resolve",
        "IP-CIDR,169.254.0.0/16,DIRECT,no-resolve",
        "IP-CIDR,224.0.0.0/4,DIRECT,no-resolve",
        "IP-CIDR6,fe80::/10,DIRECT,no-resolve",
    )

    /**
     * Maps the persisted routing overrides (issue #17) onto mihomo rule
     * lines, `TYPE,pattern,target`. Both the raw lowercase kind and the
     * already-uppercase mihomo spelling are accepted; unknown kinds fall
     * back to DOMAIN-SUFFIX. Blank patterns/targets never render.
     */
    fun overrideRules(overrides: List<RoutingRule>): List<String> =
        overrides.mapNotNull { rule ->
            val pattern = rule.pattern.trim()
            val target = rule.target.trim()
            if (pattern.isEmpty() || target.isEmpty()) return@mapNotNull null
            "${mihomoRuleType(rule.type)},$pattern,$target"
        }

    /** Lowercase kind -> mihomo rule type; unknown values become DOMAIN-SUFFIX. */
    fun mihomoRuleType(type: String): String = when (type.trim().lowercase()) {
        "domain-suffix" -> "DOMAIN-SUFFIX"
        "domain" -> "DOMAIN"
        "domain-keyword" -> "DOMAIN-KEYWORD"
        "domain-regex" -> "DOMAIN-REGEX"
        "process-name" -> "PROCESS-NAME"
        else -> "DOMAIN-SUFFIX"
    }
}
