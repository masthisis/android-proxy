package com.neurorift.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.neurorift.android.R

/**
 * The routing-override editor dialog (issue #17) — shared by the Settings
 * "Routing" section and the Connections quick-add. Follows the
 * ProfilesScreen dialog style (AlertDialog + OutlinedTextField + TextButton).
 *
 * [initialPattern]/[initialType]/[initialTarget] pre-fill the fields (the
 * Connections quick-add passes the tapped host with domain-suffix → DIRECT);
 * [onConfirm] receives the trimmed values.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RoutingOverrideDialog(
    title: String,
    initialPattern: String = "",
    initialType: String = RoutingTypes.DEFAULT,
    initialTarget: String = RoutingTypes.DIRECT,
    onDismiss: () -> Unit,
    onConfirm: (pattern: String, type: String, target: String) -> Unit,
) {
    var pattern by remember { mutableStateOf(initialPattern) }
    var type by remember { mutableStateOf(initialType) }
    var target by remember { mutableStateOf(initialTarget) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = pattern,
                    onValueChange = { pattern = it },
                    label = { Text(stringResource(R.string.routing_pattern)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = stringResource(R.string.routing_type),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RoutingTypes.TYPES.forEach { option ->
                        FilterChip(
                            selected = type == option,
                            onClick = { type = option },
                            label = { Text(option) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                            ),
                        )
                    }
                }
                OutlinedTextField(
                    value = target,
                    onValueChange = { target = it },
                    label = { Text(stringResource(R.string.routing_target)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(pattern.trim(), type, target.trim()) },
                enabled = pattern.isNotBlank() && target.isNotBlank(),
            ) { Text(stringResource(R.string.routing_add)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.profiles_cancel)) }
        },
    )
}

/** Override rule kinds offered by the dialog (CONTRACT.md settings document). */
object RoutingTypes {
    const val DIRECT = "DIRECT"
    const val DEFAULT = "domain-suffix"
    val TYPES = listOf("domain-suffix", "domain", "domain-keyword", "process-name")
}
