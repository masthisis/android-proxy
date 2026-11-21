package com.neurorift.android.ui.cockpit

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.neurorift.android.engine.EngineHolder
import com.neurorift.android.engine.EngineRuntime
import com.neurorift.android.engine.EngineState
import com.neurorift.android.engine.MihomoRestClient
import com.neurorift.android.engine.ProxyService
import com.neurorift.android.engine.StreamClient
import com.neurorift.android.settings.Settings
import com.neurorift.android.settings.SettingsStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Live cockpit state (issue #13): the 1 Hz traffic window, the connections
 * snapshot poll, the log ring + batched stream and the rules list.
 *
 * Pause semantics (Windows #76 parity groundwork): [pause] cancels the
 * collectors, [resume] re-starts them; the Dashboard drives both from the
 * lifecycle so sampling and stream work stop while the screen is hidden.
 *
 * Issue #17 adds [addRoutingOverride] — the Connections quick-add persists
 * the rule through the settings store and hot-reloads a running tunnel.
 */
class CockpitViewModel(app: Application) : AndroidViewModel(app) {

    data class UiState(
        val history: TrafficHistory = TrafficHistory(),
        val up: Long = 0,
        val down: Long = 0,
        val memory: Long = 0,
        val connections: MihomoRestClient.ConnectionsSnapshot? = null,
        val logs: List<String> = emptyList(),
        val rules: List<String> = emptyList(),
        val mode: String? = null,
        val available: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var trafficJob: Job? = null
    private var connectionsJob: Job? = null
    private var logsJob: Job? = null

    private val streams: StreamClient?
        get() = EngineRuntime.manager?.streams

    private val rest: MihomoRestClient?
        get() = EngineRuntime.manager?.restClient

    init {
        resume()
        refreshRules()
    }

    // ------------------------------------------------------------ lifecycle --

    fun resume() {
        if (trafficJob?.isActive == true) return
        val engineStreams = streams ?: run {
            _state.value = _state.value.copy(available = false)
            return
        }
        _state.value = _state.value.copy(available = true)

        trafficJob = viewModelScope.launch {
            engineStreams.traffic.collect { tick ->
                val history = _state.value.history
                history.add(tick)
                _state.value = _state.value.copy(
                    history = history,
                    up = tick.up,
                    down = tick.down,
                    memory = tick.memory,
                )
            }
        }

        connectionsJob = viewModelScope.launch {
            val client = rest ?: return@launch
            while (isActive) {
                runCatching { client.connectionsSnapshot() }.onSuccess { snap ->
                    _state.value = _state.value.copy(connections = snap)
                }
                delay(CONNECTION_POLL_MS)
            }
        }

        // Seed the log tail from the engine ring, then follow the stream.
        engineStreams.logRing.snapshot().takeIf { it.isNotEmpty() }?.let { seed ->
            _state.value = _state.value.copy(logs = seed)
        }
        logsJob = viewModelScope.launch {
            engineStreams.logs.collect { batch -> appendLogs(batch) }
        }
    }

    fun pause() {
        trafficJob?.cancel()
        connectionsJob?.cancel()
        logsJob?.cancel()
        trafficJob = null
        connectionsJob = null
        logsJob = null
    }

    // ------------------------------------------------------------- actions --

    fun closeConnection(id: String) {
        viewModelScope.launch {
            rest?.closeConnection(id)
            // The 1 Hz poll refreshes the list.
        }
    }

    fun closeAllConnections() {
        viewModelScope.launch {
            rest?.closeAllConnections()
        }
    }

    fun clearLogs() {
        streams?.logRing?.clear()
        _state.value = _state.value.copy(logs = emptyList())
    }

    fun setMode(mode: String) {
        viewModelScope.launch {
            val svc = rest?.let {
                ProxyService(client = it, testUrlProvider = { EngineRuntime.manager!!.currentSettings().testUrl })
            }
            if (svc?.setMode(mode) == true) {
                _state.value = _state.value.copy(mode = mode)
            }
        }
    }

    /**
     * Quick-adds a routing override from the Connections screen (issue #17):
     * saves directly through the settings store (sanitize → persist) and,
     * when a tunnel is running, hot-reloads the generated config so the rule
     * lands without dropping the connection.
     */
    fun addRoutingOverride(pattern: String, type: String, target: String) {
        viewModelScope.launch {
            runCatching {
                val store = SettingsStore.shared(getApplication())
                val current = store.get()
                store.save(
                    current.copy(
                        routingOverrides = current.routingOverrides + Settings.RoutingOverride(
                            pattern = pattern,
                            type = type,
                            target = target,
                        ),
                    ),
                )
            }
            val mgr = EngineRuntime.manager ?: return@launch
            if (EngineHolder.status.value.state != EngineState.RUNNING) return@launch
            runCatching { mgr.hotReload("routing override") }
        }
    }

    fun refreshRules() {
        viewModelScope.launch {
            val client = rest ?: return@launch
            runCatching {
                _state.value = _state.value.copy(rules = client.rules(), mode = client.mode())
            }
        }
    }

    /** Appends a streamed log batch to the local tail (ring-capped). */
    fun appendLogs(batch: List<String>) {
        val combined = _state.value.logs + batch
        _state.value = _state.value.copy(
            logs = if (combined.size > LOG_TAIL_MAX) combined.takeLast(LOG_TAIL_MAX) else combined,
        )
    }

    companion object {
        const val CONNECTION_POLL_MS = 1_000L
        const val LOG_TAIL_MAX = 500
    }
}
