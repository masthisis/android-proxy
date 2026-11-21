package com.neurorift.android

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import com.neurorift.android.ui.navigation.NeuroRiftNavGraph
import com.neurorift.android.ui.theme.NeuroRiftTheme
import com.neurorift.android.vpn.TunService

/**
 * Single-activity host for the Compose navigation shell (issue #9). Owns the
 * one-time VPN permission dialog: the Dashboard's connect switch calls
 * [prepareAndStart], which launches the system dialog when needed.
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
                NeuroRiftNavGraph(
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
