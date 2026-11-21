package com.neurorift.android.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the pure YAML lint used by the raw profile editor + save path
 * (issue #17): valid documents pass silently, broken documents report a
 * positioned fatal issue.
 */
class YamlLintTest {

    @Test
    fun `valid document has no issues`() {
        val doc = """
            proxies:
              - name: n1
                type: ss
                server: 198.51.100.7
                port: 8388
            rules:
              - MATCH,DIRECT
        """.trimIndent()
        assertTrue(YamlLint.lint(doc).isEmpty())
    }

    @Test
    fun `broken document reports a fatal issue with a line number`() {
        val issues = YamlLint.lint("foo: [unclosed")
        assertEquals(1, issues.size)
        val issue = issues.single()
        assertTrue(issue.fatal)
        assertTrue(issue.line >= 1)
        assertTrue(issue.message.isNotBlank())
        assertEquals("line ${issue.line}: ${issue.message}", YamlLint.format(issue))
    }

    @Test
    fun `non-mapping root is reported as fatal`() {
        val issues = YamlLint.lint("- just\n- a\n- list\n")
        assertEquals(1, issues.size)
        val issue = issues.single()
        assertTrue(issue.fatal)
        assertEquals(1, issue.line)
    }

    @Test
    fun `empty document is clean`() {
        assertTrue(YamlLint.lint("").isEmpty())
    }
}
