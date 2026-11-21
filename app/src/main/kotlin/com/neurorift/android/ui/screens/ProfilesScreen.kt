package com.neurorift.android.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.neurorift.android.R
import com.neurorift.android.profile.ProfileManager
import com.neurorift.android.profile.ProfileViewModel
import java.text.DateFormat
import java.util.Date

/**
 * Profiles screen (issue #11): list + add-from-URL + clipboard import +
 * activate + refresh + delete. Windows Profiles.vue parity on a phone form
 * factor; sync status + error badge per profile.
 */
@Composable
fun ProfilesScreen(vm: ProfileViewModel = viewModel()) {
    val profiles by vm.profiles.collectAsState()
    val progress by vm.progress.collectAsState()
    var showAdd by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current

    Scaffold(
        floatingActionButton = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FloatingActionButton(
                    onClick = {
                        clipboard.getText()?.toString()?.takeIf { it.isNotBlank() }?.let { vm.importClipboard(it) }
                    },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ) {
                    Icon(Icons.Filled.ContentPaste, contentDescription = stringResource(R.string.profiles_import_clipboard))
                }
                FloatingActionButton(
                    onClick = { showAdd = true },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.profiles_add_url))
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            progress?.let {
                Text(
                    text = "${it.phase}: ${it.detail}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (it.phase == "error") {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(profiles, key = { it.id }) { record ->
                    ProfileCard(
                        record = record,
                        active = record.id == vm.activeId,
                        onActivate = { vm.activate(record.id) },
                        onRefresh = { vm.refresh(record.id) },
                        onDelete = { vm.remove(record.id) },
                    )
                }
                if (profiles.isEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.profiles_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 32.dp),
                        )
                    }
                }
            }
        }
    }

    if (showAdd) {
        AddProfileDialog(
            onDismiss = { showAdd = false },
            onAdd = { url, name ->
                showAdd = false
                vm.addFromUrl(url, name)
            },
        )
    }
}

@Composable
private fun ProfileCard(
    record: ProfileManager.ProfileRecord,
    active: Boolean,
    onActivate: () -> Unit,
    onRefresh: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        onClick = onActivate,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        border = if (active) {
            BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
        } else {
            null
        },
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = record.name,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (active) {
                    Text(
                        text = stringResource(R.string.profiles_active),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Text(
                text = listOfNotNull(
                    record.kind.name.lowercase(),
                    "${record.nodeCount} " + stringResource(R.string.profiles_nodes),
                    record.lastSyncAt.takeIf { it > 0 }?.let {
                        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                            .format(Date(it))
                    },
                    record.lastError?.let { "⚠ $it" },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = if (record.lastError != null) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Row(modifier = Modifier.align(Alignment.End)) {
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.profiles_refresh))
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = stringResource(R.string.profiles_delete),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun AddProfileDialog(onDismiss: () -> Unit, onAdd: (String, String?) -> Unit) {
    var url by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.profiles_add_url)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(R.string.profiles_url_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.profiles_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onAdd(url.trim(), name.trim().ifEmpty { null }) },
                enabled = url.startsWith("http://") || url.startsWith("https://"),
            ) { Text(stringResource(R.string.profiles_add)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.profiles_cancel)) }
        },
    )
}
