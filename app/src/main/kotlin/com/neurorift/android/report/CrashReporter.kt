package com.neurorift.android.report

import com.neurorift.android.AppInfo
import android.os.Build
import java.io.File

/**
 * Automatic problem reports (issue #25) — the Android port of the Windows
 * crash-bundle flow:
 *
 * - [install] registers a default uncaught-exception handler that writes a
 *   REDACTED `crash-latest.txt` (stack trace + app version + device info +
 *   thread name) and then delegates to the previous handler,
 * - [pendingReport] surfaces the bundle to the UI (MainActivity dialog),
 * - [clear] dismisses it.
 *
 * Redaction ([redact]) is pure — no android.* calls — so JVM tests pin it:
 * the controller secret, any `Bearer <token>` authorization and every
 * subscription URL are stripped before the report ever touches disk.
 * Device info is composed at the call site ([install]) and passed through
 * [buildReport] parameters, keeping this file testable on the plain JVM.
 */
class CrashReporter(
    private val dataDir: File,
    private val secretProvider: () -> String? = { null },
) {

    companion object {
        /** Bundle file inside [dataDir]. */
        const val FILE_NAME = "crash-latest.txt"

        /** [pendingReport] truncates the bundle to roughly this size. */
        const val MAX_REPORT_CHARS = 4000

        /** Body cap for the pre-filled GitHub issue URL (browser-safe). */
        const val MAX_URL_BODY_CHARS = 2000

        private const val SECRET_PLACEHOLDER = "<secret-redacted>"
        private const val URL_PLACEHOLDER = "<url-redacted>"
        private const val REDACTED_PLACEHOLDER = "<redacted>"
        private const val TRUNCATED_MARKER = "\n<truncated>"

        /** `Authorization: Bearer <long token>` → token stripped. */
        private val BEARER = Regex("Bearer [A-Za-z0-9]{16,}")

        /** Any http(s) URL — subscription endpoints never leave the device. */
        private val URL = Regex("https?://\\S+")
    }

    // ------------------------------------------------------------ install --

    /**
     * Installs the redacting handler on top of [defaultHandler] (pass
     * `Thread.getDefaultUncaughtExceptionHandler()`). The bundle is written
     * best-effort — a failing write never swallows the crash delegation.
     */
    fun install(defaultHandler: Thread.UncaughtExceptionHandler?) {
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                writeReport(
                    redact(
                        buildReport(
                            thread = thread,
                            throwable = throwable,
                            version = AppInfo.fullVersion(),
                            sdkInt = Build.VERSION.SDK_INT,
                            model = Build.MODEL,
                        ),
                    ),
                )
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    // ------------------------------------------------------------- files --

    /** The pending redacted report, or null when no bundle exists. */
    fun pendingReport(): String? {
        val file = File(dataDir, FILE_NAME)
        if (!file.exists()) return null
        return runCatching { truncate(file.readText()) }.getOrNull()
    }

    /** Deletes the bundle (dialog "Dismiss"). */
    fun clear() {
        runCatching { File(dataDir, FILE_NAME).delete() }
    }

    /** Best-effort bundle write (public for JVM tests + future reporters). */
    fun writeReport(text: String) {
        runCatching {
            dataDir.mkdirs()
            File(dataDir, FILE_NAME).writeText(text)
        }
    }

    // ---------------------------------------------------------- redaction --

    /**
     * Pure redaction: strips the controller secret (exact literal), every
     * `Bearer <token>` authorization and every http(s) URL. Order matters —
     * URLs first (a URL could embed a token segment), then Bearer headers,
     * then the exact secret literal.
     */
    fun redact(text: String): String {
        var out = URL.replace(text, URL_PLACEHOLDER)
        out = BEARER.replace(out, REDACTED_PLACEHOLDER)
        val secret = secretProvider()
        if (!secret.isNullOrBlank()) {
            out = out.replace(secret, SECRET_PLACEHOLDER)
        }
        return out
    }

    /** Hard cap so a huge crash dump cannot flood the UI or an issue URL. */
    fun truncate(text: String, max: Int = MAX_REPORT_CHARS): String =
        if (text.length <= max) text else text.take(max) + TRUNCATED_MARKER

    // ------------------------------------------------------------ report --

    /**
     * The report body — pure (all device fields arrive as parameters):
     * thread name, stack trace, app identity and device info.
     */
    fun buildReport(
        thread: Thread,
        throwable: Throwable,
        version: String,
        sdkInt: Int,
        model: String,
    ): String = buildString {
        appendLine("NeuroRift automatic crash report")
        appendLine("app: $version")
        appendLine("device: $model (API $sdkInt)")
        appendLine("thread: ${thread.name}")
        appendLine()
        appendLine(throwable.stackTraceToString())
    }
}
