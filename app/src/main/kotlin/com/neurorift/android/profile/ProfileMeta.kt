package com.neurorift.android.profile

/**
 * Minimal profile metadata the engine consumes. The full registry
 * (subscription fetch, parsing, provider materialization, Room persistence)
 * lands with issue #11 — this is the boundary the engine depends on, so the
 * config generator is testable without the network pipeline.
 *
 * The two kinds mirror the Windows UX architecture (ARCHITECTURE.md §3):
 * - NODES: a materialized mihomo file proxy-provider (hot-swappable without
 *   a core restart — updates poke PUT /providers/proxies/<name>).
 * - CLASH/LOCAL: a full user-written Clash YAML, spliced verbatim with the
 *   app overlay injected.
 */
enum class ProfileKind {
    /** URI list / proxies-only YAML, materialized as a file provider. */
    NODES,

    /** Full Clash YAML from a subscription. */
    CLASH,

    /** Full Clash YAML imported from a local file / text. */
    LOCAL,
}

data class ProfileMeta(
    val id: String,
    val name: String,
    val kind: ProfileKind,
    /** mihomo home-relative path of the materialized provider file (NODES). */
    val providerPath: String? = null,
    /** mihomo home-relative path of the raw Clash YAML (CLASH/LOCAL). */
    val rawPath: String? = null,
    /** Subscription update interval in hours; 0 = manual. */
    val intervalH: Int = 0,
    /** Epoch millis of the last successful sync. */
    val lastSyncAt: Long = 0L,
)
