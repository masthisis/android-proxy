package com.neurorift.android.profile

/**
 * Per-profile subscription fetch headers (issue #24) — pure parse/sanitize
 * helpers shared by the Add dialog field, the per-profile "Headers…" editor
 * and the [ProfileManager] fetch paths (addFromUrl / update / updateAll all
 * ride the same shared fetch client).
 */
object ProfileHeaders {

    /** Hop-by-hop / reserved headers a user may never override. */
    private val forbidden = setOf("host", "content-length", "connection")

    private fun isForbidden(name: String): Boolean = name.trim().lowercase() in forbidden

    /**
     * Parses `Name: Value` lines into an insertion-ordered map. Blank lines
     * and lines without a colon are ignored; names/values are trimmed; blank
     * names/values and forbidden hop-by-hop headers are dropped; a repeated
     * name keeps the last value.
     */
    fun parse(lines: List<String>): Map<String, String> {
        val raw = LinkedHashMap<String, String>()
        for (line in lines) {
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            raw[line.substring(0, idx).trim()] = line.substring(idx + 1).trim()
        }
        return sanitize(raw)
    }

    /** Trims, drops blank names/values and forbidden hop-by-hop headers. */
    fun sanitize(headers: Map<String, String>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for ((name, value) in headers) {
            val n = name.trim()
            val v = value.trim()
            if (n.isEmpty() || v.isEmpty() || isForbidden(n)) continue
            out[n] = v
        }
        return out
    }
}
