package com.neurorift.android

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Build
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.neurorift.android.engine.EngineHolder
import com.neurorift.android.engine.EngineState
import com.neurorift.android.ui.theme.NeuroRiftTheme
import com.neurorift.android.vpn.TunService

/**
 * Temporary VPN control surface while the real app shell lands in issue #9.
 * The prepare → start flow shown here is the permanent one; the switch moves
 * into the Dashboard then.
 */
class MainActivity : ComponentActivity() {

    private val vpnPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                TunService.start(this)
            }
        }

    private val notificationsPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationsPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            NeuroRiftTheme {
                MainScaffold(
                    onConnect = { prepareAndStart() },
                    onDisconnect = { TunService.stop(this) },
                )
            }
        }
    }

    private fun prepareAndStart() {
        val intent: Intent? = VpnService.prepare(this)
        if (intent != null) {
            vpnPermission.launch(intent)
        } else {
            TunService.start(this)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScaffold(onConnect: () -> Unit, onDisconnect: () -> Unit) {
    val status by EngineHolder.status.collectAsStateWithLifecycle()
    val connected = status.state == EngineState.RUNNING

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.app_name)) })
        },
    ) { padding ->
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            ) {
                Text(
                    text = stringResource(R.string.bootstrap_welcome),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    text = AppInfo.fullVersion(),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (connected) {
                            stringResource(R.string.vpn_state_running)
                        } else {
                            stringResource(R.string.vpn_state_stopped)
                        },
                        modifier = Modifier.padding(end = 12.dp),
                    )
                    Switch(
                        checked = connected,
                        onCheckedChange = { want ->
                            if (want) onConnect() else onDisconnect()
                        },
                    )
                }
            }
        }
    }
}
