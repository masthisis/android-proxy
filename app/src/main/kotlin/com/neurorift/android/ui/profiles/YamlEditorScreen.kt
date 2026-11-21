package com.neurorift.android.ui.profiles

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.neurorift.android.R

/**
 * Raw profile YAML editor (issue #17): a monospace editing surface for the
 * materialized profile file with local live validation (YamlLint) and an
 * atomic save that re-validates before writing and hot-reapplies the active
 * profile. NODES profiles render read-only — their provider file is owned
 * by the decode pipeline.
 */
@Composable
fun YamlEditorScreen(
    profileId: String,
    vm: YamlEditorViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()

    LaunchedEffect(profileId) { vm.load(profileId) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.yaml_editor_title),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = state.profileName,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        if (state.readOnly) {
            Text(
                text = stringResource(R.string.yaml_readonly),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        state.error?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }

        OutlinedTextField(
            value = state.content,
            onValueChange = vm::update,
            enabled = !state.readOnly && state.error == null,
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.fillMaxWidth().heightIn(min = 400.dp),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = vm::validate,
                enabled = !state.readOnly && state.error == null,
            ) {
                Text(stringResource(R.string.yaml_validate))
            }
            OutlinedButton(
                onClick = vm::save,
                enabled = !state.readOnly && state.error == null,
            ) {
                Text(stringResource(R.string.yaml_save))
            }
        }

        if (state.saved && state.issues.isEmpty()) {
            Text(
                text = stringResource(R.string.yaml_saved),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (state.validated && state.issues.isEmpty() && !state.saved) {
            Text(
                text = stringResource(R.string.yaml_no_issues),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        state.issues.forEach { issue ->
            Text(
                text = issue,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
