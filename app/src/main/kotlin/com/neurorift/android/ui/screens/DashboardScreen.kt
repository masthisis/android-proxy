package com.neurorift.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.neurorift.android.AppInfo
import com.neurorift.android.R
import com.neurorift.android.engine.EngineHolder
import com.neurorift.android.engine.EngineState
import com.neurorift.android.vpn.TunService

/**
 * Dashboard base (issue #9): the VPN connect switch — the cockpit (#13)
 * builds the traffic graph, connections and logs around this. The switch is
 * wired to TunService with the one-time prepare dialog handled by the
 * activity callbacks passed in.
 */
@Composable
fun DashboardScreen(
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val status by EngineHolder.status.collectAsStateWithLifecycle()
    val connected = status.state == EngineState.RUNNING

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = when (status.state) {
                    EngineState.RUNNING -> stringResource(R.string.vpn_state_running)
                    EngineState.STARTING -> stringResource(R.string.vpn_state_starting)
                    EngineState.STOPPING -> stringResource(R.string.vpn_state_stopping)
                    EngineState.FAILED -> stringResource(R.string.vpn_state_failed)
                    EngineState.STOPPED -> stringResource(R.string.vpn_state_stopped)
                },
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(end = 12.dp),
            )
            Switch(
                checked = connected,
                onCheckedChange = { want -> if (want) onConnect() else onDisconnect() },
            )
        }
        status.detail?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Text(
            text = stringResource(R.string.placeholder_issue_hint, 13),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = AppInfo.fullVersion(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
