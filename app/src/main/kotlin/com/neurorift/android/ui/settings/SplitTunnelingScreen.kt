package com.neurorift.android.ui.settings

import android.content.pm.ApplicationInfo
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.neurorift.android.R

/**
 * Split-tunneling app picker (issue #14, v2rayNG per-app proxy parity):
 * mode chips (off / allowlist / denylist) + searchable installed-app list
 * with checkboxes. Selection persists through the parent settings draft.
 */
@Composable
fun SplitTunnelingScreen(vm: SettingsViewModel = viewModel()) {
    val context = LocalContext.current
    val current = vm.settings.collectAsStateWithLifecycle().value ?: return
    var search by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(current.splitTunneling.apps.toSet()) }

    val apps: List<Pair<String, String>> = remember {
        context.packageManager.getInstalledPackages(0).map {
            val label = runCatching {
                context.packageManager.getApplicationLabel(it.applicationInfo!!).toString()
            }.getOrDefault(it.packageName)
            it.packageName to label
        }.sortedBy { it.second }
    }

    val query = search.trim()
    val filtered = apps.filter { (pkg, label) ->
        query.isEmpty() || label.contains(query, true) || pkg.contains(query, true)
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("off", "allowlist", "denylist").forEach { mode ->
                FilterChip(
                    selected = current.splitTunneling.mode == mode,
                    onClick = { vm.save(current.copy(splitTunneling = current.splitTunneling.copy(mode = mode))) },
                    label = { Text(mode) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = search,
            onValueChange = { search = it },
            placeholder = { Text(stringResource(R.string.split_search)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(8.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxSize()) {
            items(filtered, key = { it.first }) { (pkg, label) ->
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f).padding(start = 14.dp, top = 8.dp, bottom = 8.dp)) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = pkg,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Checkbox(
                            checked = pkg in selected,
                            onCheckedChange = { checked ->
                                selected = if (checked) selected + pkg else selected - pkg
                                vm.save(
                                    current.copy(
                                        splitTunneling = current.splitTunneling.copy(
                                            apps = selected.toList(),
                                        ),
                                    ),
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}
