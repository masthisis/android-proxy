package com.neurorift.android

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.neurorift.android.ui.navigation.NeuroRiftNavGraph
import com.neurorift.android.ui.theme.NeuroRiftTheme
import com.neurorift.android.vpn.TunService

/**
 * Single-activity host for the Compose navigation shell (issue #9). Owns the
 * one-time VPN permission dialog: the Dashboard's connect button calls
 * [prepareAndStart], which launches the system dialog when needed.
 *
 * Issue #14: the persisted language setting applies per-app on API 33+ via
 * the platform locale manager (system/en/fa; "system" clears the override).
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
        applyPersistedLocale()
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

    private fun applyPersistedLocale() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        // Async read (no disk IO on the main thread); the per-app locale
        // manager applies the change system-wide once loaded.
        lifecycleScope.launch {
            runCatching {
                val language = com.neurorift.android.settings.SettingsStore
                    .shared(this@MainActivity).get().language
                val manager = getSystemService(android.app.LocaleManager::class.java) ?: return@launch
                val current = manager.applicationLocales
                val target = if (language == "system") {
                    if (current.isEmpty) return@launch
                    LocaleList.getEmptyLocaleList()
                } else {
                    if (current.toLanguageTags() == language) return@launch
                    LocaleList.forLanguageTags(language)
                }
                manager.applicationLocales = target
            }
        }
    }
}
