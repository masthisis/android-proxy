package com.neurorift.android.ui.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.neurorift.android.AppInfo
import com.neurorift.android.R
import com.neurorift.android.engine.EngineHolder
import com.neurorift.android.engine.EngineState
import com.neurorift.android.profile.ProfileViewModel
import com.neurorift.android.ui.cockpit.CockpitViewModel
import com.neurorift.android.ui.cockpit.TrafficCard
import com.neurorift.android.ui.theme.ProtonViolet
import com.neurorift.android.ui.theme.DisconnectedPink
import com.neurorift.android.ui.update.UpdateBanner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/**
 * Proton-style Home (issue #34) grown into the live cockpit (issue #13):
 * status headline + big circular connect button, the 1 Hz traffic graph
 * card, quick access to Connections/Logs/Rules, and the active-profile
 * card. Stream sampling pauses with the lifecycle (Windows #76 groundwork).
 */
@Composable
fun DashboardScreen(
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onOpenConnections: () -> Unit = {},
    onOpenLogs: () -> Unit = {},
    onOpenRules: () -> Unit = {},
) {
    val status by EngineHolder.status.collectAsStateWithLifecycle()
    val profileVm: ProfileViewModel = viewModel()
    val profiles by profileVm.profiles.collectAsStateWithLifecycle()
    val activeName = profiles.firstOrNull { it.id == profileVm.activeId }?.name
    val cockpit: CockpitViewModel = viewModel()

    // Lifecycle-aware sampling: traffic/connections/log collectors only run
    // while the screen is visible (issue #13 acceptance criterion).
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> cockpit.resume()
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> cockpit.pause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val cockpitState by cockpit.state.collectAsStateWithLifecycle()

    val connected = status.state == EngineState.RUNNING
    val transitioning = status.state == EngineState.STARTING || status.state == EngineState.STOPPING

    // State colors — Proton semantics: violet idle, green connected, red failed.
    val accent: Color = when (status.state) {
        EngineState.RUNNING -> MaterialTheme.colorScheme.tertiary
        EngineState.FAILED -> MaterialTheme.colorScheme.error
        else -> ProtonViolet
    }

    // Pulse while the tunnel is starting/stopping (Proton connecting animation).
    val pulse = rememberInfiniteTransition(label = "connectPulse")
    val pulseScale by pulse.animateFloat(
        label = "pulseScale",
        initialValue = 0.94f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
    )
    val pulseAlpha by pulse.animateFloat(
        label = "pulseAlpha",
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
    )
    val buttonScale = if (transitioning) pulseScale else 1f
    val buttonAlpha = if (transitioning) pulseAlpha else 1f

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(32.dp))

        // --- Status headline ----------------------------------------------
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(
                        color = when (status.state) {
                            EngineState.RUNNING -> MaterialTheme.colorScheme.tertiary
                            EngineState.FAILED -> MaterialTheme.colorScheme.error
                            EngineState.STOPPED -> DisconnectedPink
                            else -> ProtonViolet
                        },
                        shape = CircleShape,
                    ),
            )
            Text(
                text = when (status.state) {
                    EngineState.RUNNING -> stringResource(R.string.vpn_state_running)
                    EngineState.STARTING -> stringResource(R.string.vpn_state_starting)
                    EngineState.STOPPING -> stringResource(R.string.vpn_state_stopping)
                    EngineState.FAILED -> stringResource(R.string.vpn_state_failed)
                    EngineState.STOPPED -> stringResource(R.string.home_disconnected)
                },
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(start = 10.dp),
            )
        }

        // --- Self-update banner (#20): only rendered while relevant --------
        UpdateBanner()

        Spacer(Modifier.height(40.dp))

        // --- The big Proton connect button --------------------------------
        val label = if (connected) {
            stringResource(R.string.vpn_action_disconnect)
        } else {
            stringResource(R.string.vpn_action_connect)
        }
        Box(
            modifier = Modifier
                .size(220.dp)
                .scale(buttonScale)
                .graphicsLayer { alpha = buttonAlpha }
                .background(
                    brush = androidx.compose.ui.graphics.Brush.radialGradient(
                        colors = listOf(accent, accent.copy(alpha = 0.82f)),
                    ),
                    shape = CircleShape,
                )
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            // Only respond while the state machine allows a user action;
            // STARTING/STOPPING are engine-owned (Windows parity).
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        when (status.state) {
                            EngineState.STOPPED, EngineState.FAILED -> onConnect()
                            EngineState.RUNNING -> onDisconnect()
                            else -> Unit
                        }
                    },
            )
            Icon(
                imageVector = Icons.Filled.PowerSettingsNew,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(64.dp),
            )
        }

        Spacer(Modifier.height(12.dp))

        Text(
            text = when {
                status.detail != null -> status.detail!!
                connected -> stringResource(R.string.home_connected_hint)
                else -> stringResource(R.string.home_idle_hint)
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (status.state == EngineState.FAILED) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )

        Spacer(Modifier.height(24.dp))

        // --- Live traffic (only meaningful while the engine runs) ---------
        if (status.state == EngineState.RUNNING) {
            TrafficCard(
                history = cockpitState.history.snapshot(),
                up = cockpitState.up,
                down = cockpitState.down,
                memory = cockpitState.memory,
            )
            Spacer(Modifier.height(12.dp))
        }

        // --- Quick access to the cockpit views ----------------------------
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(
                onClick = onOpenConnections,
                label = { Text(stringResource(R.string.nav_connections)) },
            )
            AssistChip(
                onClick = onOpenLogs,
                label = { Text(stringResource(R.string.nav_logs)) },
            )
            AssistChip(
                onClick = onOpenRules,
                label = { Text(stringResource(R.string.nav_rules)) },
            )
        }

        Spacer(Modifier.height(16.dp))

        // --- Active profile card ------------------------------------------
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.home_active_profile),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = activeName ?: stringResource(R.string.home_no_profile),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (activeName == null) {
                        DisconnectedPink
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = AppInfo.fullVersion(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

