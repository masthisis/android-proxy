package com.neurorift.android.ui.test

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.neurorift.android.R
import com.neurorift.android.engine.EventBus
import com.neurorift.android.engine.Events
import com.neurorift.android.failover.FailoverEvent
import com.neurorift.android.failover.FailoverRuntime
import com.neurorift.android.failover.FailoverSnapshot
import com.neurorift.android.failover.FailoverWatchdog
import com.neurorift.android.ui.theme.MaintenanceGray
import com.neurorift.android.ui.theme.ProtonViolet
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Failover status card (issue #16) in the Test Center — Proton style:
 * state label (Watching / Off), current node, strike counter, last switch
 * line, a small event log (last 5 events) and a "Test now" button that
 * runs one immediate watchdog cycle through the shared [FailoverRuntime]
 * handle. The card renders an Off state (VPN down / failover disabled)
 * instead of hiding, so the feature stays discoverable.
 */
@Composable
fun FailoverCard() {
    var snapshot by remember { mutableStateOf<FailoverSnapshot?>(FailoverRuntime.watchdog?.snapshot()) }
    var log by remember { mutableStateOf<List<FailoverEvent>>(emptyList()) }

    LaunchedEffect(Unit) {
        // Failover events ride the app-wide bus; the card keeps the last few.
        launch {
            EventBus.events.collect { (name, payload) ->
                if (name == Events.FAILOVER && payload is FailoverEvent) {
                    log = (listOf(payload) + log).take(EVENT_LOG_SIZE)
                }
            }
        }
        // The watchdog instance swaps on reconnect — poll the runtime handle.
        while (true) {
            snapshot = FailoverRuntime.watchdog?.snapshot()
            delay(SNAPSHOT_POLL_MS)
        }
    }

    val watching = snapshot?.watching == true
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.failover_title),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(if (watching) R.string.failover_watching else R.string.failover_off),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (watching) MaterialTheme.colorScheme.tertiary else MaintenanceGray,
                )
            }
            snapshot?.current?.let { node ->
                Text(
                    text = node,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = stringResource(R.string.failover_strikes, snapshot?.strikes ?: 0),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            snapshot?.lastEvent
                ?.takeIf { it.kind == FailoverWatchdog.KIND_SWITCHED }
                ?.let { event ->
                    Text(
                        text = stringResource(R.string.failover_last_switch, event.from ?: "—", event.to ?: "—"),
                        style = MaterialTheme.typography.bodySmall,
                        color = ProtonViolet,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            if (log.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                log.forEach { event ->
                    Text(
                        text = eventLine(event),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { FailoverRuntime.watchdog?.testNow() }, enabled = watching) {
                    Text(stringResource(R.string.failover_test_now))
                }
            }
        }
    }
}

/** One log line per failover event kind (Windows failover event parity). */
@Composable
private fun eventLine(event: FailoverEvent): String = when (event.kind) {
    FailoverWatchdog.KIND_SWITCHED ->
        stringResource(R.string.failover_event_switched, event.from ?: "—", event.to ?: "—")
    FailoverWatchdog.KIND_PROBE_OK ->
        stringResource(R.string.failover_event_ok, event.node ?: "—")
    else ->
        stringResource(R.string.failover_event_failed, event.node ?: "—", event.strikes)
}

private const val EVENT_LOG_SIZE = 5
private const val SNAPSHOT_POLL_MS = 1_000L
