package com.neurorift.android.ui.test

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.geometry.Offset
import com.neurorift.android.R
import com.neurorift.android.nettest.SpeedDone
import com.neurorift.android.nettest.SpeedTest
import com.neurorift.android.ui.cockpit.ByteFormat
import com.neurorift.android.ui.theme.LoadHigh
import com.neurorift.android.ui.theme.LoadLow
import com.neurorift.android.ui.theme.LoadMedium
import com.neurorift.android.ui.theme.MaintenanceGray
import com.neurorift.android.ui.theme.ProtonViolet
import java.util.Locale

/**
 * Test Center (issue #15) in the Proton visual language (issue #34):
 * byte-cap preset chips (violet), search + sort + test-all controls, node
 * cards with delay chips (Windows NodeChip palette), last speed readout,
 * per-node latency sparklines, a live progress card with cancel and the
 * "connect first" state while the engine is down.
 */
@Composable
fun TestScreen(vm: TestCenterViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()

    if (!state.available) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.test_center_need_vpn),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.test_center_need_vpn_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {

        Text(
            text = stringResource(R.string.test_center_title),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 4.dp),
        )

        // --- Byte-cap preset chips -----------------------------------------
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PresetChip(
                label = stringResource(R.string.test_center_preset_100),
                selected = state.presetBytes == TestCenterViewModel.PRESET_100_MB,
                onClick = { vm.setPreset(TestCenterViewModel.PRESET_100_MB) },
            )
            PresetChip(
                label = stringResource(R.string.test_center_preset_250),
                selected = state.presetBytes == TestCenterViewModel.PRESET_250_MB,
                onClick = { vm.setPreset(TestCenterViewModel.PRESET_250_MB) },
            )
            PresetChip(
                label = stringResource(R.string.test_center_preset_500),
                selected = state.presetBytes == TestCenterViewModel.PRESET_500_MB,
                onClick = { vm.setPreset(TestCenterViewModel.PRESET_500_MB) },
            )
            PresetChip(
                label = stringResource(R.string.test_center_preset_max),
                selected = state.presetBytes == null,
                onClick = { vm.setPreset(null) },
            )
        }

        Spacer(Modifier.height(8.dp))

        // --- Search + sort + test-all --------------------------------------
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = state.search,
                onValueChange = vm::setSearch,
                placeholder = { Text(stringResource(R.string.test_center_search)) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = {
                vm.setSort(
                    if (state.sort == TestCenterViewModel.SortMode.DELAY) {
                        TestCenterViewModel.SortMode.NAME
                    } else {
                        TestCenterViewModel.SortMode.DELAY
                    },
                )
            }) {
                Icon(
                    imageVector = if (state.sort == TestCenterViewModel.SortMode.DELAY) {
                        Icons.Filled.ArrowDownward
                    } else {
                        Icons.Filled.ArrowUpward
                    },
                    contentDescription = stringResource(R.string.proxies_sort_toggle),
                )
            }
            IconButton(
                onClick = { vm.testAllNodesLatency() },
                enabled = !state.testing,
            ) {
                if (state.testing) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Filled.Bolt, contentDescription = stringResource(R.string.test_center_test_all))
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

        // --- Live run: progress card / last result -------------------------
        when (val run = state.run) {
            is TestCenterViewModel.SpeedRun.Running -> ProgressCard(run, onCancel = vm::cancelSpeedTest)
            is TestCenterViewModel.SpeedRun.Done -> ResultCard(run)
            TestCenterViewModel.SpeedRun.Idle -> Unit
        }

        Spacer(Modifier.height(8.dp))

        FailoverCard()

        // --- Node cards -----------------------------------------------------
        val search = state.search.trim()
        val rows = state.nodes.keys
            .filter { search.isEmpty() || it.contains(search, ignoreCase = true) }
            .let { list ->
                when (state.sort) {
                    TestCenterViewModel.SortMode.DELAY -> list.sortedBy { name ->
                        state.latency[name]?.let { d -> if (d > 0) d else Int.MAX_VALUE } ?: Int.MAX_VALUE
                    }
                    TestCenterViewModel.SortMode.NAME -> list.sorted()
                }
            }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
            items(rows, key = { it }) { name ->
                NodeRow(
                    name = name,
                    delay = state.latency[name] ?: state.nodes[name]?.delay,
                    last = state.speedHistory[name],
                    samples = state.latencySamples[name].orEmpty(),
                    running = (state.run as? TestCenterViewModel.SpeedRun.Running)?.node == name,
                    onRunSpeed = { vm.runSpeedTest(name, state.presetBytes) },
                    onProbeLatency = { vm.testNodeLatency(name) },
                )
            }
            if (rows.isEmpty() && !state.loading) {
                item {
                    Text(
                        text = stringResource(R.string.test_center_empty),
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
private fun PresetChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
        ),
    )
}

@Composable
private fun ProgressCard(
    run: TestCenterViewModel.SpeedRun.Running,
    onCancel: () -> Unit,
) {
    val fraction = if (run.bytesCap > 0) {
        (run.bytesDone.toDouble() / run.bytesCap).toFloat().coerceIn(0f, 1f)
    } else {
        0f
    }
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.test_center_running),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = run.node,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (run.phase == SpeedTest.PHASE_UPLOAD) "↑ " else "↓ ",
                    style = MaterialTheme.typography.titleMedium,
                    color = ProtonViolet,
                )
                Text(
                    text = String.format(Locale.ROOT, "%.1f Mbps", run.mbps),
                    style = MaterialTheme.typography.titleMedium,
                    color = ProtonViolet,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "${ByteFormat.bytes(run.bytesDone)} / ${ByteFormat.bytes(run.bytesCap)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onCancel) {
                    Text(stringResource(R.string.test_center_cancel))
                }
            }
        }
    }
}

@Composable
private fun ResultCard(run: TestCenterViewModel.SpeedRun.Done) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(16.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = run.node,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.test_center_run_speed),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = speedSummary(run.downMbps, run.upMbps),
                style = MaterialTheme.typography.bodyMedium,
                color = ProtonViolet,
            )
        }
    }
}

@Composable
private fun NodeRow(
    name: String,
    delay: Int?,
    last: SpeedDone?,
    samples: List<Int>,
    running: Boolean,
    onRunSpeed: () -> Unit,
    onProbeLatency: () -> Unit,
) {
    var expanded by remember(name) { mutableStateOf(false) }
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Tap the node = run the speed test on it.
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onRunSpeed,
                        ),
                ) {
                    Text(
                        text = name,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = last?.let { speedSummary(it.downMbps, it.upMbps) } ?: "",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (last != null) ProtonViolet else MaintenanceGray,
                    )
                }
                IconButton(onClick = onProbeLatency, modifier = Modifier.size(28.dp)) {
                    Icon(
                        imageVector = Icons.Filled.Bolt,
                        contentDescription = stringResource(R.string.proxies_test_node),
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { expanded = !expanded }, modifier = Modifier.size(28.dp)) {
                    Icon(
                        imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                }
                if (running) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                }
                DelayChip(delay)
            }
            if (expanded) {
                Spacer(Modifier.height(8.dp))
                LatencySparkline(samples)
            }
        }
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

/** Small violet polyline over the node's latency ring (TrafficCard pattern). */
@Composable
private fun LatencySparkline(samples: List<Int>) {
    Canvas(modifier = Modifier.fillMaxWidth().height(40.dp)) {
        if (samples.size < 2) {
            drawLine(
                color = ProtonViolet.copy(alpha = 0.25f),
                start = Offset(0f, size.height),
                end = Offset(size.width, size.height),
                strokeWidth = 2f,
            )
            return@Canvas
        }
        val peak = samples.max().coerceAtLeast(1).toFloat()
        val stepX = size.width / (samples.size - 1)
        val path = Path()
        samples.forEachIndexed { i, sample ->
            val x = i * stepX
            val y = size.height - (sample.toFloat() / peak * size.height * 0.92f)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, ProtonViolet, style = Stroke(width = 3f))
    }
}

/** "↓ 12.3 Mbps ↑ 4.5 Mbps" — locale-stable dot decimals (ByteFormat style). */
private fun speedSummary(downMbps: Double, upMbps: Double): String =
    "↓ " + String.format(Locale.ROOT, "%.1f Mbps", downMbps) +
        " ↑ " + String.format(Locale.ROOT, "%.1f Mbps", upMbps)
