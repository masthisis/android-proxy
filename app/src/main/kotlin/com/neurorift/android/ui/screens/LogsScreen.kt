package com.neurorift.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.neurorift.android.R
import com.neurorift.android.ui.cockpit.CockpitViewModel

/**
 * Core logs view of the live cockpit (issue #13): batched core stream +
 * in-memory tail (≤500), level filter chips (all / warning / error),
 * copy-to-clipboard, clear.
 */
@Composable
fun LogsScreen(vm: CockpitViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    var levelFilter by remember { mutableStateOf("all") }
    val clipboard = LocalClipboardManager.current
    val listState = rememberLazyListState()

    val filtered = state.logs.filter { line ->
        when (levelFilter) {
            "warning" -> line.startsWith("[warning]") || line.startsWith("[error]")
            "error" -> line.startsWith("[error]")
            else -> true
        }
    }

    // Newest entries stay in view while the stream flows.
    LaunchedEffect(filtered.size) {
        if (filtered.isNotEmpty()) listState.animateScrollToItem(filtered.size - 1)
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {

        Row(verticalAlignment = Alignment.CenterVertically) {
            listOf("all", "warning", "error").forEach { level ->
                FilterChip(
                    selected = levelFilter == level,
                    onClick = { levelFilter = level },
                    label = { Text(level) },
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                IconButton(onClick = {
                    clipboard.setText(AnnotatedString(filtered.joinToString("\n")))
                }) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = stringResource(R.string.logs_copy))
                }
                IconButton(onClick = { vm.clearLogs() }) {
                    Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.logs_clear))
                }
            }
        }

        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            items(filtered) { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = when {
                        line.startsWith("[error]") -> MaterialTheme.colorScheme.error
                        line.startsWith("[warning]") -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(vertical = 1.dp),
                )
            }
            if (filtered.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.logs_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
            }
        }
    }
}
