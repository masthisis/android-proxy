package com.neurorift.android.ui.screens

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.neurorift.android.R
import com.neurorift.android.engine.Groups
import com.neurorift.android.engine.ProxyService
import com.neurorift.android.ui.proxies.ProxiesViewModel
import com.neurorift.android.ui.theme.LoadHigh
import com.neurorift.android.ui.theme.LoadLow
import com.neurorift.android.ui.theme.LoadMedium
import com.neurorift.android.ui.theme.MaintenanceGray

/**
 * Groups dashboard (issue #12) in the Proton visual language (issue #34):
 * mode chips, search + sort controls, group cards with per-node delay chips
 * colored by the Windows NodeChip palette (<200 green, <500 orange, else
 * red; untested gray). Hidden internal groups (speed-test selector) collapse
 * by default.
 */
@Composable
fun ProxiesScreen(vm: ProxiesViewModel = viewModel()) {
    val state by vm.state.collectAsState()

    if (!state.available) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.proxies_need_vpn),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.proxies_need_vpn_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {

        // --- Mode chips (rule / global / direct) --------------------------
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("rule", "global", "direct").forEach { mode ->
                FilterChip(
                    selected = state.mode == mode,
                    onClick = { vm.setMode(mode) },
                    label = { Text(mode.replaceFirstChar { it.uppercase() }) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // --- Search + sort + test-all ------------------------------------
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = state.search,
                onValueChange = vm::setSearch,
                placeholder = { Text(stringResource(R.string.proxies_search)) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = {
                vm.setSort(
                    if (state.sort == ProxiesViewModel.SortMode.DELAY) {
                        ProxiesViewModel.SortMode.NAME
                    } else {
                        ProxiesViewModel.SortMode.DELAY
                    },
                )
            }) {
                Icon(
                    imageVector = if (state.sort == ProxiesViewModel.SortMode.DELAY) {
                        Icons.Filled.ArrowDownward
                    } else {
                        Icons.Filled.ArrowUpward
                    },
                    contentDescription = stringResource(R.string.proxies_sort_toggle),
                )
            }
            IconButton(
                onClick = { vm.testAllNodes() },
                enabled = !state.testing,
            ) {
                if (state.testing) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Filled.Bolt, contentDescription = stringResource(R.string.proxies_test_all))
                }
            }
        }

        state.error?.let {
            Text(
                text = it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 6.dp),
            )
        }

        Spacer(Modifier.height(8.dp))

        // --- Group cards ---------------------------------------------------
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(state.groups, key = { it.name }) { group ->
                GroupCard(
                    group = group,
                    state = state,
                    vm = vm,
                )
            }
            if (state.groups.isEmpty() && !state.loading) {
                item {
                    Text(
                        text = stringResource(R.string.proxies_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun GroupCard(
    group: ProxyService.GroupInfo,
    state: ProxiesViewModel.UiState,
    vm: ProxiesViewModel,
) {
    var expanded by remember(group.name) { mutableStateOf(!group.hidden) }
    val search = state.search.trim()
    val members = group.nodes
        .filter { search.isEmpty() || it.contains(search, ignoreCase = true) }
        .let { list ->
            when (state.sort) {
                ProxiesViewModel.SortMode.DELAY -> list.sortedBy { name ->
                    state.latency[name]?.let { d -> if (d > 0) d else Int.MAX_VALUE } ?: Int.MAX_VALUE
                }
                ProxiesViewModel.SortMode.NAME -> list.sorted()
            }
        }

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {

            // Header: name + type + current + actions
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = group.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = listOfNotNull(
                            group.type,
                            group.now.takeIf { it.isNotBlank() }?.let { "→ $it" },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(
                    onClick = { vm.testGroup(group.name, group.nodes) },
                    enabled = !state.testing,
                ) {
                    Icon(Icons.Filled.Bolt, contentDescription = stringResource(R.string.proxies_test_group))
                }
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = null,
                    )
                }
            }

            if (expanded) {
                Spacer(Modifier.height(8.dp))
                members.forEach { name ->
                    NodeRow(
                        name = name,
                        delay = state.latency[name] ?: state.nodes[name]?.delay,
                        selected = group.now == name,
                        onSelect = { vm.select(group.name, name) },
                        onTest = { vm.testNode(name) },
                    )
                }
            }
        }
    }
}

@Composable
private fun NodeRow(
    name: String,
    delay: Int?,
    selected: Boolean,
    onSelect: () -> Unit,
    onTest: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    ) {
        androidx.compose.material3.RadioButton(
            selected = selected,
            onClick = onSelect,
            modifier = Modifier.size(32.dp),
        )
        Text(
            text = name,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .clickableNoRipple(onSelect),
        )
        IconButton(onClick = onTest, modifier = Modifier.size(28.dp)) {
            Icon(
                imageVector = Icons.Filled.Bolt,
                contentDescription = stringResource(R.string.proxies_test_node),
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DelayChip(delay)
    }
}

@Composable
private fun DelayChip(delay: Int?) {
    val (color, text) = when {
        delay == null || delay <= 0 -> MaintenanceGray to "—"
        delay < 200 -> LoadLow to "${delay}ms"
        delay < 500 -> LoadMedium to "${delay}ms"
        else -> LoadHigh to "${delay}ms"
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = color,
        modifier = Modifier.padding(start = 8.dp),
    )
}

/** Ripple-free row click (Windows-parity ergonomics, Proton look). */
private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier =
    androidx.compose.ui.composed {
        clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClick,
        )
    }
