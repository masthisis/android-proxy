package com.neurorift.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.neurorift.android.AppInfo
import com.neurorift.android.R
import com.neurorift.android.settings.Settings

/**
 * Settings surface (issue #14): grouped sections mirroring the Windows
 * Settings view (General / Mode / Ports / Test / DNS / TUN / Subscription /
 * Log / Updates / Split tunneling / About). Edits buffer locally; Save runs
 * the sanitize → persist → engine re-apply pipeline.
 */
@Composable
fun SettingsScreen(
    onOpenSplitTunneling: () -> Unit = {},
    vm: SettingsViewModel = viewModel(),
) {
    val current = vm.settings.collectAsStateWithLifecycle().value
    val message by vm.savedMessage.collectAsStateWithLifecycle()
    val coreVersion by vm.coreVersion.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            vm.clearMessage()
        }
    }

    // Edit buffer — null until the persisted doc arrives.
    var draft by rememberSaveable(current == null) { mutableStateOf(current ?: Settings()) }

    if (current == null) return

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionCard(stringResource(R.string.settings_section_general)) {
                ChipRow(
                    options = listOf("system", "en", "fa"),
                    selected = draft.language,
                    onSelect = { draft = draft.copy(language = it) },
                )
                SwitchRow(
                    label = stringResource(R.string.settings_start_on_boot),
                    checked = draft.startOnBoot,
                    onChange = { draft = draft.copy(startOnBoot = it) },
                )
                SwitchRow(
                    label = stringResource(R.string.settings_allow_lan),
                    checked = draft.allowLan,
                    onChange = { draft = draft.copy(allowLan = it) },
                )
            }

            SectionCard(stringResource(R.string.settings_section_mode)) {
                ChipRow(
                    options = listOf("rule", "global", "direct"),
                    selected = draft.mode,
                    onSelect = { draft = draft.copy(mode = it) },
                )
            }

            SectionCard(stringResource(R.string.settings_section_ports)) {
                PortField(
                    label = stringResource(R.string.settings_port_mixed),
                    value = draft.ports.mixed,
                    onChange = { draft = draft.copy(ports = draft.ports.copy(mixed = it)) },
                )
                PortField(
                    label = stringResource(R.string.settings_port_controller),
                    value = draft.ports.controller,
                    onChange = { draft = draft.copy(ports = draft.ports.copy(controller = it)) },
                )
                PortField(
                    label = stringResource(R.string.settings_port_speedtest),
                    value = draft.ports.speedTest,
                    onChange = { draft = draft.copy(ports = draft.ports.copy(speedTest = it)) },
                )
            }

            SectionCard(stringResource(R.string.settings_section_test)) {
                TextField(
                    label = stringResource(R.string.settings_test_url),
                    value = draft.test.url,
                    onChange = { draft = draft.copy(test = draft.test.copy(url = it)) },
                )
                PortField(
                    label = stringResource(R.string.settings_test_timeout),
                    value = draft.test.timeoutMs,
                    onChange = { draft = draft.copy(test = draft.test.copy(timeoutMs = it)) },
                )
                PortField(
                    label = stringResource(R.string.settings_speed_streams),
                    value = draft.test.speedStreams,
                    onChange = { draft = draft.copy(test = draft.test.copy(speedStreams = it)) },
                )
                PortField(
                    label = stringResource(R.string.settings_speed_max_mb),
                    value = draft.test.speedMaxMb,
                    onChange = { draft = draft.copy(test = draft.test.copy(speedMaxMb = it)) },
                )
                SwitchRow(
                    label = stringResource(R.string.settings_upload_test),
                    checked = draft.test.uploadTest,
                    onChange = { draft = draft.copy(test = draft.test.copy(uploadTest = it)) },
                )
            }

            SectionCard(stringResource(R.string.settings_section_dns)) {
                SwitchRow(
                    label = stringResource(R.string.settings_dns_override),
                    checked = draft.dns.override,
                    onChange = { draft = draft.copy(dns = draft.dns.copy(override = it)) },
                )
                TextField(
                    label = stringResource(R.string.settings_dns_nameservers),
                    value = draft.dns.nameservers.joinToString(", "),
                    onChange = { text ->
                        draft = draft.copy(
                            dns = draft.dns.copy(nameservers = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }),
                        )
                    },
                )
            }

            SectionCard(stringResource(R.string.settings_section_tun)) {
                ChipRow(
                    options = listOf("system", "gvisor", "mixed"),
                    selected = draft.tun.stack,
                    onSelect = { draft = draft.copy(tun = draft.tun.copy(stack = it)) },
                )
                PortField(
                    label = stringResource(R.string.settings_tun_mtu),
                    value = draft.tun.mtu,
                    onChange = { draft = draft.copy(tun = draft.tun.copy(mtu = it)) },
                )
            }

            SectionCard(stringResource(R.string.settings_section_subscription)) {
                PortField(
                    label = stringResource(R.string.settings_sub_interval),
                    value = draft.subscription.defaultIntervalH,
                    onChange = { draft = draft.copy(subscription = draft.subscription.copy(defaultIntervalH = it)) },
                )
                SwitchRow(
                    label = stringResource(R.string.settings_sub_via_proxy),
                    checked = draft.subscription.updateViaProxy,
                    onChange = { draft = draft.copy(subscription = draft.subscription.copy(updateViaProxy = it)) },
                )
            }

            SectionCard(stringResource(R.string.settings_section_log)) {
                ChipRow(
                    options = listOf("debug", "info", "warning", "error", "silent"),
                    selected = draft.log.coreLevel,
                    onSelect = { draft = draft.copy(log = draft.log.copy(coreLevel = it)) },
                )
            }

            SectionCard(stringResource(R.string.settings_section_updates)) {
                SwitchRow(
                    label = stringResource(R.string.settings_updates_auto),
                    checked = draft.updates.autoCheck,
                    onChange = { draft = draft.copy(updates = draft.updates.copy(autoCheck = it)) },
                )
                SwitchRow(
                    label = stringResource(R.string.settings_updates_prerelease),
                    checked = draft.updates.includePrerelease,
                    onChange = { draft = draft.copy(updates = draft.updates.copy(includePrerelease = it)) },
                )
            }

            SectionCard(stringResource(R.string.settings_section_split)) {
                ChipRow(
                    options = listOf("off", "allowlist", "denylist"),
                    selected = draft.splitTunneling.mode,
                    onSelect = { draft = draft.copy(splitTunneling = draft.splitTunneling.copy(mode = it)) },
                )
                OutlinedButton(onClick = onOpenSplitTunneling, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(R.string.settings_split_pick) +
                            " (${draft.splitTunneling.apps.size})",
                    )
                }
            }

            SectionCard(stringResource(R.string.settings_section_about)) {
                Text(
                    text = "NeuroRift ${AppInfo.fullVersion()}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = coreVersion?.let { "mihomo core: $it" }
                        ?: stringResource(R.string.settings_core_offline),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "Android ${android.os.Build.VERSION.SDK_INT}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { vm.save(draft) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.settings_save))
            }
            Spacer(Modifier.height(24.dp))
        }

        SnackbarHost(hostState = snackbar)
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            content()
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ChipRow(options: List<String>, selected: String, onSelect: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        options.forEach { option ->
            FilterChip(
                selected = selected == option,
                onClick = { onSelect(option) },
                label = { Text(option) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                ),
            )
        }
    }
}

@Composable
private fun PortField(label: String, value: Int, onChange: (Int) -> Unit) {
    var text by remember(label) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            text = input
            input.toIntOrNull()?.let(onChange)
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun TextField(label: String, value: String, onChange: (String) -> Unit) {
    var text by remember(label) { mutableStateOf(value) }
    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            text = input
            onChange(input)
        },
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}
