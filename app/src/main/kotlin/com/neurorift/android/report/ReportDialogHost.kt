package com.neurorift.android.report

import android.content.Intent
import android.net.Uri
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.neurorift.android.AppInfo
import com.neurorift.android.R
import java.net.URLEncoder

/**
 * Crash-report dialog host (issue #25): when a redacted
 * `crash-latest.txt` bundle is pending, MainActivity offers to open a
 * pre-filled GitHub issue (`masthisis/android-proxy`, body = the truncated
 * redacted report) or dismiss (bundle deleted). Self-contained — one
 * composable call in MainActivity keeps that diff minimal.
 */
@Composable
fun CrashReportDialogHost() {
    val context = LocalContext.current
    val reporter = remember { CrashReporter(context.filesDir) }
    var pending by remember { mutableStateOf<String?>(reporter.pendingReport()) }
    val report = pending ?: return

    AlertDialog(
        onDismissRequest = {
            reporter.clear()
            pending = null
        },
        title = { Text(stringResource(R.string.report_crash_title)) },
        text = { Text(stringResource(R.string.report_crash_body)) },
        confirmButton = {
            TextButton(onClick = {
                if (openGitHubIssue(context, report)) {
                    reporter.clear()
                    pending = null
                }
            }) {
                Text(stringResource(R.string.report_crash_open))
            }
        },
        dismissButton = {
            TextButton(onClick = {
                reporter.clear()
                pending = null
            }) {
                Text(stringResource(R.string.report_crash_dismiss))
            }
        },
    )
}

/** Opens a pre-filled GitHub issue; false when no browser is available. */
private fun openGitHubIssue(context: android.content.Context, report: String): Boolean =
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse(gitHubIssueUrl(report))
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    }.isSuccess

/** Pre-filled `issues/new` URL; body capped for browser safety. */
fun gitHubIssueUrl(report: String): String {
    val body = report.take(CrashReporter.MAX_URL_BODY_CHARS)
    val title = URLEncoder.encode("Crash report (auto)", "UTF-8")
    val encodedBody = URLEncoder.encode(body, "UTF-8")
    return "https://github.com/${AppInfo.GITHUB_REPO}/issues/new?title=$title&body=$encodedBody"
}
