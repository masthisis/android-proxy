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
import androidx.compose.material.icons.filled.CallMerge
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Http
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
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
import com.neurorift.android.profile.ProfileHeaders
import com.neurorift.android.profile.ProfileKind
import com.neurorift.android.profile.ProfileManager
import com.neurorift.android.profile.ProfileViewModel
import com.neurorift.android.profile.ScheduleMath
import java.text.DateFormat
import java.util.Date

/**
 * Profiles screen (issue #11): list + add-from-URL + clipboard import +
 * activate + refresh + delete. Windows Profiles.vue parity on a phone form
 * factor; sync status + error badge per profile.
 *
 * Issue #17: an edit action per card opens the raw YAML editor for the
 * profile. Issue #18: Update All action with an outcome summary row and a
 * per-card "next update" time for interval-managed subscriptions.
 * Issue #23: a union toggle per card joins a subscription into the union
 * routing set (multiple subscriptions active as ONE routing set). Issue
 * #24: the Add dialog gains an optional User-Agent + custom `Name: Value`
 * headers; a "Headers…" entry per card opens the same dialog in edit mode
 * (pre-filled, save → setFetchHeaders).
 */
@Composable
fun ProfilesScreen(
    onEditProfile: (String) -> Unit = {},
    vm: ProfileViewModel = viewModel(),
) {
    val profiles by vm.profiles.collectAsState()
    val progress by vm.progress.collectAsState()
    val summary by vm.summary.collectAsState()
    var showAdd by remember { mutableStateOf(false) }
    // #24: non-null → the reused dialog opens in header-edit mode for it.
    var editingHeaders by remember { mutableStateOf<ProfileManager.ProfileRecord?>(null) }
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
            // --- Update All (#18) -------------------------------------------
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 8.dp),
            ) {
                FilledTonalButton(
                    onClick = { vm.updateAll() },
                    enabled = profiles.isNotEmpty(),
                ) {
                    Text(stringResource(R.string.profiles_update_all))
                }
            }
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
            summary?.let { s ->
                Text(
                    text = stringResource(R.string.profiles_summary, s.updated, s.failed, s.skipped),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
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
                        onToggleUnion = { vm.setUnionActive(record.id, !record.unionActive) },
                        onRefresh = { vm.refresh(record.id) },
                        onHeaders = { editingHeaders = record },
                        onEdit = { onEditProfile(record.id) },
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
            onAdd = { url, name, userAgent, headers ->
                showAdd = false
                vm.addFromUrl(url, name, userAgent = userAgent, extraHeaders = headers)
            },
        )
    }
    editingHeaders?.let { record ->
        AddProfileDialog(
            editRecord = record,
            onDismiss = { editingHeaders = null },
            onSaveHeaders = { userAgent, headers ->
                editingHeaders = null
                // The UA field wins over any User-Agent line in the headers
                // text; an empty UA falls back to the app default.
                val merged = headers
                    .filterKeys { !it.equals("User-Agent", ignoreCase = true) }
                    .toMutableMap()
                userAgent.takeIf { it.isNotEmpty() }?.let { merged["User-Agent"] = it }
                vm.setFetchHeaders(record.id, merged)
            },
        )
    }
}

@Composable
private fun nextUpdateLabel(record: ProfileManager.ProfileRecord): String? {
    if (record.intervalH <= 0 || record.lastSyncAt <= 0) return null
    val next = Date(ScheduleMath.nextUpdateAt(record.lastSyncAt, record.intervalH))
    return stringResource(
        R.string.profiles_next_update,
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(next),
    )
}

@Composable
private fun ProfileCard(
    record: ProfileManager.ProfileRecord,
    active: Boolean,
    onActivate: () -> Unit,
    onToggleUnion: () -> Unit,
    onRefresh: () -> Unit,
    onHeaders: () -> Unit,
    onEdit: () -> Unit,
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
                // Union member chip (#23): additional provider source routed
                // as one set with the active profile.
                if (record.unionActive) {
                    Text(
                        text = stringResource(R.string.profiles_union),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                }
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
                    // Next scheduled sync for interval-managed subscriptions (#18).
                    nextUpdateLabel(record),
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
                // Union toggle (#23): joins/leaves the subscription union
                // routing set — NODES-kind profiles only (providers union).
                if (record.kind == ProfileKind.NODES) {
                    IconButton(onClick = onToggleUnion) {
                        Icon(
                            Icons.Filled.CallMerge,
                            contentDescription = stringResource(R.string.profiles_union_toggle),
                            tint = if (record.unionActive) {
                                MaterialTheme.colorScheme.tertiary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.profiles_refresh))
                }
                // Headers… entry (#24): opens the shared dialog in edit mode.
                IconButton(onClick = onHeaders) {
                    Icon(Icons.Filled.Http, contentDescription = stringResource(R.string.profiles_edit_headers))
                }
                IconButton(onClick = onEdit) {
                    Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.yaml_editor_title))
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

/** The profile's stored User-Agent (case-insensitive lookup) for pre-fill. */
private fun userAgentOf(headers: Map<String, String>): String =
    headers.entries.firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }?.value ?: ""

/** The stored custom headers as `Name: Value` lines (UA shown in its own field). */
private fun headersTextOf(headers: Map<String, String>): String =
    headers.filterKeys { !it.equals("User-Agent", ignoreCase = true) }
        .entries.joinToString("\n") { "${it.key}: ${it.value}" }

/**
 * One dialog reused for add and header-edit (#24): in add mode it imports
 * from a URL with an optional User-Agent + custom headers; in edit mode
 * ([editRecord] != null) the same fields are pre-filled from the record's
 * fetchHeaders and confirmed through [onSaveHeaders] (setFetchHeaders).
 */
@Composable
private fun AddProfileDialog(
    editRecord: ProfileManager.ProfileRecord? = null,
    onDismiss: () -> Unit,
    onAdd: ((url: String, name: String?, userAgent: String, headers: Map<String, String>) -> Unit)? = null,
    onSaveHeaders: (userAgent: String, headers: Map<String, String>) -> Unit = { _, _ -> },
) {
    var url by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var userAgent by remember { mutableStateOf(editRecord?.fetchHeaders?.let { userAgentOf(it) } ?: "") }
    var headers by remember { mutableStateOf(editRecord?.fetchHeaders?.let { headersTextOf(it) } ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (editRecord == null) R.string.profiles_add_url else R.string.profiles_edit_headers,
                ),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (editRecord == null) {
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
                OutlinedTextField(
                    value = userAgent,
                    onValueChange = { userAgent = it },
                    label = { Text(stringResource(R.string.profiles_user_agent)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = headers,
                    onValueChange = { headers = it },
                    label = { Text(stringResource(R.string.profiles_headers)) },
                    placeholder = { Text(stringResource(R.string.profiles_headers_hint)) },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val ua = userAgent.trim()
                    val parsed = ProfileHeaders.parse(headers.lines())
                    if (editRecord == null) {
                        onAdd?.invoke(url.trim(), name.trim().ifEmpty { null }, ua, parsed)
                    } else {
                        onSaveHeaders(ua, parsed)
                    }
                },
                enabled = editRecord != null ||
                    url.startsWith("http://") || url.startsWith("https://"),
            ) {
                Text(
                    stringResource(
                        if (editRecord == null) R.string.profiles_add else R.string.profiles_headers_save,
                    ),
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.profiles_cancel)) }
        },
    )
}
