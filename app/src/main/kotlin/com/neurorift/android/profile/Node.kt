package com.neurorift.android.profile

/**
 * A parsed proxy node in mihomo-proxy-map form: the raw YAML map that will
 * land inside `proxies:` or a provider file, exactly like the Windows
 * internal/profile Node.Raw. Insertion order matters for stable diffs, so
 * [raw] is a LinkedHashMap.
 */
data class Node(
    val name: String,
    val type: String,
    val raw: LinkedHashMap<String, Any?>,
)

/** Result of decoding a subscription document. */
data class DecodeResult(
    val nodes: List<Node>,
    /** Human-readable warnings: skipped entries, format quirks (never silent — Windows bug-fix parity). */
    val warnings: List<String>,
    /** "links" | "clash" | "clash-proxies" | "sing-box" */
    val format: String,
)

/** Parse error for a single link. */
class LinkParseException(message: String, cause: Throwable? = null) : Exception(message, cause)
