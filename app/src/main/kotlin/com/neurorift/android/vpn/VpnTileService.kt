package com.neurorift.android.vpn

import android.content.Intent
import android.net.VpnService
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.neurorift.android.MainActivity
import com.neurorift.android.engine.EngineHolder
import com.neurorift.android.engine.EngineState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Quick-settings connect/disconnect tile (issue #19) — the Android analogue
 * of the Windows tray quick controls. One tap = start the VPN; the tile
 * mirrors the engine state machine (active / inactive / unavailable while
 * the tunnel transitions).
 *
 * Permission-aware: VpnService.prepare needs a user dialog which a Tile
 * cannot show — when the VPN permission is not yet granted the tile deep
 * links into the app (Dashboard) instead of starting the service.
 */
class VpnTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var statusJob: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        refresh()
        // Keep the tile live while it is on screen.
        statusJob?.cancel()
        statusJob = scope.launch {
            EngineHolder.status.collect { refresh() }
        }
    }

    override fun onStopListening() {
        statusJob?.cancel()
        statusJob = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        when (EngineHolder.status.value.state) {
            EngineState.STOPPED, EngineState.FAILED -> {
                if (VpnService.prepare(this) == null) {
                    TunService.start(this)
                } else {
                    // Permission dialog must come from an activity.
                    unlockAndRun {
                        startActivity(
                            Intent(this, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }
            }
            EngineState.RUNNING -> TunService.stop(this)
            else -> Unit // STARTING/STOPPING are engine-owned
        }
        refresh()
    }

    private fun refresh() {
        val tile = qsTile ?: return
        tile.state = when (EngineHolder.status.value.state) {
            EngineState.RUNNING -> Tile.STATE_ACTIVE
            EngineState.STARTING, EngineState.STOPPING -> Tile.STATE_UNAVAILABLE
            else -> Tile.STATE_INACTIVE
        }
        tile.updateTile()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
