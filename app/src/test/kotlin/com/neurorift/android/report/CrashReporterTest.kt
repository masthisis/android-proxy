package com.neurorift.android.report

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * CrashReporter tests (issue #25) — pure JVM: redaction (controller secret,
 * Bearer authorizations, subscription URLs), the ~4000-char truncation cap
 * and the write → pending → clear bundle lifecycle. Device info never
 * enters the redaction logic (it is composed at the install call site), so
 * no android.* classes are touched here.
 */
class CrashReporterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val secret = "Kq2xJm3vTp8wZbRnYhLc9sVd5fGu7Ae1"

    private fun reporter(): CrashReporter =
        CrashReporter(tmp.root, secretProvider = { secret })

    // ---------------------------------------------------------- redaction --

    @Test
    fun `redact removes the controller secret`() {
        val r = reporter()
        val text = "token=Kq2xJm3vTp8wZbRnYhLc9sVd5fGu7Ae1 in a log line"
        assertEquals("token=<secret-redacted> in a log line", r.redact(text))
    }

    @Test
    fun `redact strips bearer authorizations`() {
        val r = reporter()
        val text = "Authorization: Bearer AbCdEf0123456789AbCdEf — next"
        assertTrue(!r.redact(text).contains("AbCdEf0123456789AbCdEf"))
        assertTrue(r.redact(text).contains("<redacted>"))
        // Short tokens stay (below the 16-char threshold).
        assertEquals("Bearer short", r.redact("Bearer short"))
    }

    @Test
    fun `redact replaces subscription urls with a placeholder`() {
        val r = reporter()
        val text = "fetched https://sub.example.com/token/abc?x=1 ok\nGET http://10.0.0.1:9090/proxies"
        val out = r.redact(text)
        assertFalse(out.contains("sub.example.com"))
        assertFalse(out.contains("10.0.0.1:9090"))
        assertEquals(2, Regex("<url-redacted>").findAll(out).count())
        assertTrue(out.contains(" ok"))
    }

    @Test
    fun `redact handles a null or empty secret provider`() {
        val r = CrashReporter(tmp.root, secretProvider = { null })
        assertEquals("no secret here", r.redact("no secret here"))
    }

    @Test
    fun `redact applies urls before the secret literal`() {
        val r = reporter()
        // URL first — an https endpoint is never partially left behind.
        val out = r.redact("https://provider.example.com/$secret/sub")
        assertFalse(out.contains("example.com"))
        assertFalse(out.contains(secret))
    }

    // -------------------------------------------------------- truncation --

    @Test
    fun `truncate caps long reports and keeps short ones intact`() {
        val r = reporter()
        val short = "small report"
        assertEquals(short, r.truncate(short))
        val long = "x".repeat(CrashReporter.MAX_REPORT_CHARS + 500)
        val capped = r.truncate(long)
        assertTrue(capped.length <= CrashReporter.MAX_REPORT_CHARS + "<truncated>".length + 1)
        assertTrue(capped.endsWith("<truncated>"))
    }

    // --------------------------------------------------- bundle lifecycle --

    @Test
    fun `pending report lifecycle write pending clear`() {
        val r = reporter()
        assertNull(r.pendingReport())

        r.writeReport(r.redact("crash with secret=$secret and https://sub.example.com/url"))
        val pending = r.pendingReport()
        assertNotNull(pending)
        assertFalse(pending!!.contains(secret))
        assertFalse(pending.contains("sub.example.com"))
        assertTrue(pending.contains("crash with secret="))

        r.clear()
        assertNull(r.pendingReport())
        assertFalse(File(tmp.root, CrashReporter.FILE_NAME).exists())
    }

    @Test
    fun `pending report truncates an oversized bundle on read`() {
        val r = reporter()
        r.writeReport("y".repeat(10_000))
        val pending = r.pendingReport()
        assertNotNull(pending)
        assertTrue(pending!!.length < 10_000)
        assertTrue(pending.endsWith("<truncated>"))
    }

    // ---------------------------------------------------------- the report --

    @Test
    fun `buildReport carries thread stacktrace and device info`() {
        val r = reporter()
        val boom = IllegalStateException("boom")
        val thread = Thread.currentThread()
        val report = r.buildReport(
            thread = thread,
            throwable = boom,
            version = "NeuroRift for Android 1.0.0",
            sdkInt = 34,
            model = "Pixel 8",
        )
        assertTrue(report.contains("NeuroRift for Android 1.0.0"))
        assertTrue(report.contains("Pixel 8 (API 34)"))
        assertTrue(report.contains("thread: ${thread.name}"))
        assertTrue(report.contains("java.lang.IllegalStateException: boom"))
    }
}
