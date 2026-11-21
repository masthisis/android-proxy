package com.neurorift.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.neurorift.android.R
import com.neurorift.android.ui.cockpit.CockpitViewModel

/**
 * Rules view of the live cockpit (issue #13): `GET /rules` grouped by rule
 * type (DOMAIN-SUFFIX / GEOIP / MATCH …) with search, plus the live mode
 * segmented control (PATCH /configs — no reload, Windows parity).
 */
@Composable
fun RulesScreen(vm: CockpitViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    var search by remember { mutableStateOf("") }
    var modeState by remember(state.mode) { mutableStateOf(state.mode ?: "rule") }

    val query = search.trim()
    val filtered = state.rules.filter { it.contains(query, ignoreCase = true) }

    // Group by rule type — the first CSV segment of each rule string.
    val grouped = remember(filtered) {
        filtered.groupBy { it.substringBefore(',').uppercase() }
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {

        // Live mode control — patches /configs without a reload.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("rule", "global", "direct").forEach { mode ->
                FilterChip(
                    selected = modeState == mode,
                    onClick = {
                        modeState = mode
                        vm.setMode(mode)
                    },
                    label = { Text(mode.replaceFirstChar { it.uppercase() }) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                )
            }
        }

        OutlinedTextField(
            value = search,
            onValueChange = { search = it },
            placeholder = { Text(stringResource(R.string.rules_search)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        )

        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxSize()) {
            grouped.forEach { (type, rules) ->
                item(key = "header-$type") {
                    Text(
                        text = type,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                    )
                }
                items(rules, key = { it }) { rule ->
                    Text(
                        text = rule,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(vertical = 1.dp),
                    )
                }
            }
            if (grouped.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.rules_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
            }
        }
    }
}
