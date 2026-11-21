package com.neurorift.android.ui.update

import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.neurorift.android.R
import com.neurorift.android.update.UpdateState
import com.neurorift.android.update.UpdateViewModel

/**
 * Self-update banner (issue #20): a single chip shown at the top of the
 * Dashboard content whenever a release is available, downloading, or ready
 * to install. Tapping a Ready chip hands the verified APK to the system
 * installer; during download the progress is shown inline. Renders nothing
 * while idle / checking / unavailable, so the Dashboard diff stays minimal.
 */
@Composable
fun UpdateBanner(vm: UpdateViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    when (val s = state) {
        is UpdateState.Available -> AssistChip(
            onClick = {},
            label = { Text(stringResource(R.string.update_available, s.version)) },
        )

        is UpdateState.Downloading -> AssistChip(
            onClick = {},
            label = { Text(stringResource(R.string.update_download) + " " + progressText(s)) },
        )

        is UpdateState.Ready -> AssistChip(
            onClick = vm::install,
            label = {
                Text(
                    text = stringResource(R.string.update_ready),
                    color = MaterialTheme.colorScheme.primary,
                )
            },
        )

        else -> Unit
    }
}

/** Human-readable byte counters for the downloading chip. */
private fun progressText(s: UpdateState.Downloading): String {
    val total = if (s.totalBytes > 0) " / ${humanBytes(s.totalBytes)}" else ""
    return "(${humanBytes(s.bytesRead)}$total)"
}

private fun humanBytes(bytes: Long): String = when {
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000 -> "%.0f KB".format(bytes / 1_000.0)
    else -> "$bytes B"
}
