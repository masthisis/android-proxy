package com.neurorift.android.update

/**
 * Semantic-version comparison for release tags (issue #20) — pure Kotlin so
 * the ordering is pinned by JVM tests. Handles the shapes the project's
 * release workflow produces (`v1.2.3`, `1.2.3`, `1.2.3-rc.1`, optional build
 * metadata after `+`).
 *
 * SemVer rules implemented: numeric major.minor.patch triple comparison,
 * leading `v` stripped, a prerelease sorts BELOW its own plain version, and
 * prerelease identifiers compare per the spec (numeric identifiers compare
 * numerically and rank below alphanumeric ones).
 */
object Semver {

    /** Compares [a] to [b]; negative / zero / positive like [Comparable]. */
    fun compare(a: String, b: String): Int {
        val (aCore, aPre) = split(a)
        val (bCore, bPre) = split(b)
        for (i in 0 until 3) {
            val ai = aCore.getOrNull(i) ?: 0
            val bi = bCore.getOrNull(i) ?: 0
            if (ai != bi) return ai.compareTo(bi)
        }
        return when {
            aPre == null && bPre == null -> 0
            // No prerelease == the plain release — sorts above any prerelease.
            aPre == null -> 1
            bPre == null -> -1
            else -> comparePrerelease(aPre, bPre)
        }
    }

    /** True when [candidate] is a strictly newer release than [current]. */
    fun isNewer(candidate: String, current: String): Boolean = compare(candidate, current) > 0

    /** Strips a leading `v` / `V` from a release tag ("v1.2.3" -> "1.2.3"). */
    fun stripV(tag: String): String = tag.trim().removePrefix("v").removePrefix("V")

    /** Splits "1.2.3-rc.1+meta" into the numeric triple and the prerelease ids. */
    private fun split(version: String): Pair<List<Int>, List<String>?> {
        val cleaned = stripV(version).substringBefore('+')
        val parts = cleaned.split('-', limit = 2)
        val core = parts[0].split('.').map { segment ->
            segment.filter { it.isDigit() }.ifEmpty { "0" }.toInt()
        }
        val pre = parts.getOrNull(1)?.takeIf { it.isNotEmpty() }?.split('.')
        return core to pre
    }

    private fun comparePrerelease(a: List<String>, b: List<String>): Int {
        for (i in 0 until minOf(a.size, b.size)) {
            val ai = a[i]
            val bi = b[i]
            val an = ai.toIntOrNull()
            val bn = bi.toIntOrNull()
            val cmp = when {
                an != null && bn != null -> an.compareTo(bn)
                an != null -> -1 // numeric identifiers rank below alphanumeric
                bn != null -> 1
                else -> ai.compareTo(bi)
            }
            if (cmp != 0) return cmp
        }
        // All shared identifiers equal — the longer list sorts higher.
        return a.size.compareTo(b.size)
    }
}
