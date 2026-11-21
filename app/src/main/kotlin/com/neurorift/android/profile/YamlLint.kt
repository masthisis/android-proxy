package com.neurorift.android.profile

import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.error.MarkedYAMLException

/**
 * Local YAML lint for the raw profile editor (issue #17) — a pure function
 * with no Android dependencies so the JVM test suite pins it directly.
 *
 * The same lint guards [ProfileManager.updateRaw] (validate BEFORE writing)
 * and the editor's "Validate" button, so the on-screen preview and the
 * actual write path can never disagree.
 */
object YamlLint {

    /** One lint finding; [line]/[column] are 1-based. */
    data class Issue(
        val line: Int,
        val column: Int,
        val message: String,
        val fatal: Boolean,
    )

    /**
     * Parses [content] with snakeyaml and reports the first fatal problem as
     * a positioned issue; a syntactically valid document yields an empty
     * list. A non-mapping root is reported too — a Clash profile that lost
     * its top-level map would break [com.neurorift.android.engine.ConfigGen]
     * splicing.
     */
    fun lint(content: String): List<Issue> {
        val loaded = runCatching { Yaml().load<Any>(content) }
            .getOrElse { e -> return listOf(fatalIssue(e)) }
        if (loaded != null && loaded !is Map<*, *>) {
            return listOf(
                Issue(line = 1, column = 1, message = "root is not a mapping", fatal = true),
            )
        }
        return emptyList()
    }

    /** Renders an issue the way the editor shows it: "line X: <message>". */
    fun format(issue: Issue): String = "line ${issue.line}: ${issue.message}"

    private fun fatalIssue(e: Throwable): Issue {
        val marked = e as? MarkedYAMLException
        val mark = marked?.problemMark
        val message = marked?.problem ?: e.message ?: "invalid YAML"
        return if (mark != null) {
            Issue(line = mark.line + 1, column = mark.column + 1, message = message, fatal = true)
        } else {
            Issue(line = 1, column = 1, message = message, fatal = true)
        }
    }
}
