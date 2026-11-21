package com.neurorift.android.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.neurorift.android.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Autostart receiver (issue #19): BOOT_COMPLETED (and package-replaced) —
 * when the persisted `startOnBoot` setting is on, starts the VPN service
 * after boot completes. The one-time VPN permission grant persists across
 * reboots, so `VpnService.prepare` normally returns null here; if not, the
 * user connects manually (a receiver must not launch dialogs).
 *
 * Android's system Always-on VPN takes precedence when the user enabled it
 * — the system itself restarts the tunnel, and a duplicate start here is a
 * no-op through the engine state machine (P5).
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val settings = SettingsStore.shared(context).get()
                if (settings.startOnBoot && VpnService.prepare(context) == null) {
                    TunService.start(context)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
