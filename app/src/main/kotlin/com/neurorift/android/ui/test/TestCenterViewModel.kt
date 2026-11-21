package com.neurorift.android.ui.test

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.neurorift.android.engine.EngineRuntime
import com.neurorift.android.engine.Groups
import com.neurorift.android.engine.ProxyService
import com.neurorift.android.nettest.HistoryStore
import com.neurorift.android.nettest.SpeedDone
import com.neurorift.android.nettest.SpeedTest
import com.neurorift.android.profile.ProfileManager
import com.neurorift.android.settings.Settings
import com.neurorift.android.settings.SettingsStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Test Center state (issue #15): the node catalog + latency map fed by
 * [ProxyService] (same pattern as the Proxies dashboard), per-node speed
 * runs through the hidden `⚡ NR SpeedTest` selector with live progress,
 * byte-cap presets, latency probes and the file-backed [HistoryStore].
 * The service exists only while the tunnel runs — [UiState.available]
 * drives the "connect first" state.
 */
class TestCenterViewModel(app: Application) : AndroidViewModel(app) {

    enum class SortMode { DELAY, NAME }

    /** Lifecycle of one speed run surfaced to the screen. */
    sealed class SpeedRun {
        object Idle : SpeedRun()

        data class Running(
            val node: String,
            val phase: String,
            val mbps: Double,
            val bytesDone: Long,
            val bytesCap: Long,
        ) : SpeedRun()

        data class Done(val node: String, val downMbps: Double, val upMbps: Double) : SpeedRun()
    }

    data class UiState(
        val available: Boolean = false,
        val loading: Boolean = false,
        val nodes: Map<String, ProxyService.NodeInfo> = emptyMap(),
        val latency: Map<String, Int?> = emptyMap(),
        val search: String = "",
        val sort: SortMode = SortMode.DELAY,
        /** Batch latency probe in flight (test-all). */
        val testing: Boolean = false,
        val error: String? = null,
        val run: SpeedRun = SpeedRun.Idle,
        val speedHistory: Map<String, SpeedDone> = emptyMap(),
        val latencySamples: Map<String, List<Int>> = emptyMap(),
        /** Byte cap for speed runs; null = "Max" (the settings cap). */
        val presetBytes: Long? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val history = HistoryStore(ProfileManager.dataDir(app))
    private var speedTest: SpeedTest? = null
    private var speedJob: Job? = null

    private val service: ProxyService?
        get() = EngineRuntime.manager?.let { mgr ->
            ProxyService(
                client = mgr.restClient,
                testUrlProvider = { mgr.currentSettings().testUrl },
            )
        }

    init {
        // Windows bug #40 regression class: refetch after every hot reload /
        // profile switch (groups:refresh), same as the Proxies dashboard.
        service?.observeGroupsRefresh(viewModelScope) { refresh() }
        refresh()
        loadHistory()
    }

    // ------------------------------------------------------------ catalog --

    fun refresh() {
        val svc = service ?: run {
            _state.value = _state.value.copy(available = false, loading = false)
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = _state.value.nodes.isEmpty(), available = true)
            runCatching { svc.catalog() }
                .onSuccess { catalog ->
                    if (catalog != null) {
                        _state.value = _state.value.copy(
                            loading = false,
                            error = null,
                            nodes = catalog.nodes,
                        )
                    } else {
                        _state.value = _state.value.copy(loading = false, error = "controller unreachable")
                    }
                }
                .onFailure { e ->
                    _state.value = _state.value.copy(loading = false, error = e.message)
                }
        }
    }

    fun setSearch(query: String) {
        _state.value = _state.value.copy(search = query)
    }

    fun setSort(mode: SortMode) {
        _state.value = _state.value.copy(sort = mode)
    }

    /** null = "Max" preset — the SpeedTest falls back to the settings cap. */
    fun setPreset(bytes: Long?) {
        _state.value = _state.value.copy(presetBytes = bytes)
    }

    // ------------------------------------------------------------ latency --

    /** Single-node delay probe (the bolt button). */
    fun testNodeLatency(node: String) {
        val svc = service ?: return
        viewModelScope.launch {
            runCatching { svc.testNode(node) }
                .onSuccess { delay -> applyLatency(mapOf(node to delay)) }
                .onFailure { e -> _state.value = _state.value.copy(error = e.message) }
        }
    }

    /** Batch-tests every node through the hidden include-all selector. */
    fun testAllNodesLatency() {
        val svc = service ?: return
        val all = _state.value.nodes.keys.toList()
        if (all.isEmpty()) return
        viewModelScope.launch {
            _state.value = _state.value.copy(testing = true)
            runCatching { svc.testGroup(Groups.SPEED_TEST, all) }
                .onSuccess { results -> applyLatency(results) }
                .onFailure { e -> _state.value = _state.value.copy(error = e.message) }
            _state.value = _state.value.copy(testing = false)
        }
    }

    private fun applyLatency(results: Map<String, Int?>) {
        _state.value = _state.value.copy(latency = _state.value.latency + results)
        results.forEach { (node, delay) ->
            if (delay != null && delay > 0) history.recordLatency(node, delay)
        }
        _state.value = _state.value.copy(latencySamples = history.latencyHistory())
    }

    // ------------------------------------------------------------- speed --

    /**
     * Runs a multi-stream speed test on [node]: select into the hidden
     * group, download (+ upload) through the dedicated listener, collect
     * the 500 ms progress ticks, record the result in the history store.
     */
    fun runSpeedTest(node: String, presetBytes: Long?) {
        val mgr = EngineRuntime.manager ?: run {
            _state.value = _state.value.copy(available = false)
            return
        }
        if (speedJob?.isActive == true) return
        speedJob = viewModelScope.launch {
            val cfg = runCatching { testConfig() }.getOrDefault(Settings.TestConfig())
            val cap = presetBytes ?: cfg.speedMaxMb.toLong() * SpeedTest.BYTES_PER_MB
            val tester = SpeedTest(
                client = mgr.restClient,
                settingsProvider = { mgr.currentSettings() },
                streams = cfg.speedStreams,
                maxMb = cfg.speedMaxMb,
                maxSeconds = cfg.speedMaxSeconds,
                uploadTest = cfg.uploadTest,
            )
            speedTest = tester
            _state.value = _state.value.copy(
                run = SpeedRun.Running(node, SpeedTest.PHASE_DOWNLOAD, 0.0, 0L, cap),
            )
            val collector = launch {
                tester.progress.collect { tick ->
                    _state.value = _state.value.copy(
                        run = SpeedRun.Running(
                            tick.node, tick.phase, tick.mbps, tick.bytesDone, cap,
                        ),
                    )
                }
            }
            runCatching { tester.run(node, presetBytes) }
                .onSuccess { result ->
                    // Cancelled runs keep partial speeds out of the history.
                    if (!tester.isCancelled) {
                        history.recordSpeed(
                            SpeedDone(result.node, result.downMbps, result.upMbps, System.currentTimeMillis()),
                        )
                    }
                    _state.value = _state.value.copy(
                        run = SpeedRun.Done(result.node, result.downMbps, result.upMbps),
                        speedHistory = history.speedHistory(),
                        error = null,
                    )
                }
                .onFailure { e ->
                    _state.value = _state.value.copy(run = SpeedRun.Idle, error = e.message)
                }
            collector.cancel()
            speedTest = null
        }
    }

    fun cancelSpeedTest() {
        speedTest?.cancel()
    }

    /** Live readback of the run mutex state (screen disables double taps). */
    val isSpeedRunning: Boolean get() = speedJob?.isActive == true

    private suspend fun testConfig(): Settings.TestConfig =
        SettingsStore.shared(getApplication()).get().test

    private fun loadHistory() {
        _state.value = _state.value.copy(
            speedHistory = history.speedHistory(),
            latencySamples = history.latencyHistory(),
        )
    }

    companion object {
        /** Byte-cap presets (MB → bytes), matching SpeedMath's decimal MB. */
        val PRESET_100_MB: Long = 100L * SpeedTest.BYTES_PER_MB
        val PRESET_250_MB: Long = 250L * SpeedTest.BYTES_PER_MB
        val PRESET_500_MB: Long = 500L * SpeedTest.BYTES_PER_MB
    }
}
