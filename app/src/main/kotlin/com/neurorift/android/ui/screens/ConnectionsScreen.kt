package com.neurorift.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.neurorift.android.R
import com.neurorift.android.ui.cockpit.ByteFormat
import com.neurorift.android.ui.cockpit.CockpitViewModel
import com.neurorift.android.ui.theme.MaintenanceGray

/**
 * Connections view of the live cockpit (issue #13): virtualized 1 Hz
 * snapshot list — host, rule + chain, up/down totals, per-connection close
 * and a confirmed close-all.
 */
@Composable
fun ConnectionsScreen(vm: CockpitViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    var confirmCloseAll by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {

        // Session totals + close-all
        Row(verticalAlignment = Alignment.CenterVertically) {
            val snap = state.connections
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "↓ " + ByteFormat.bytes(snap?.downloadTotal ?: 0),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "↑ " + ByteFormat.bytes(snap?.uploadTotal ?: 0),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { confirmCloseAll = true }) {
                Icon(
                    Icons.Filled.DeleteSweep,
                    contentDescription = stringResource(R.string.connections_close_all),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(state.connections?.rows ?: emptyList(), key = { it.id }) { row ->
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = row.host,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = listOf(row.rule, row.chains)
                                    .filter { it.isNotBlank() }
                                    .joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = "↓ " + ByteFormat.bytes(row.down),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaintenanceGray,
                            )
                            Text(
                                text = "↑ " + ByteFormat.bytes(row.up),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaintenanceGray,
                            )
                        }
                        IconButton(onClick = { vm.closeConnection(row.id) }) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = stringResource(R.string.connections_close_one),
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
            if ((state.connections?.rows?.size ?: 0) == 0) {
                item {
                    Text(
                        text = stringResource(R.string.connections_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
            }
        }
    }

    if (confirmCloseAll) {
        AlertDialog(
            onDismissRequest = { confirmCloseAll = false },
            title = { Text(stringResource(R.string.connections_close_all)) },
            text = { Text(stringResource(R.string.connections_close_all_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    vm.closeAllConnections()
                    confirmCloseAll = false
                }) { Text(stringResource(R.string.connections_close_all_confirm_yes)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmCloseAll = false }) { Text(stringResource(R.string.profiles_cancel)) }
            },
        )
    }
}
