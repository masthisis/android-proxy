package com.neurorift.android.ui.proxies

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.neurorift.android.engine.EngineRuntime
import com.neurorift.android.engine.Groups
import com.neurorift.android.engine.ProxyService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Groups-dashboard state (issue #12): catalog + search + sort + latency
 * map, fed by [ProxyService] against the live engine. The service exists
 * only while the tunnel runs — [UiState.available] drives the
 * "connect first" state.
 */
class ProxiesViewModel : ViewModel() {

    enum class SortMode { DELAY, NAME }

    data class UiState(
        val available: Boolean = false,
        val loading: Boolean = false,
        val groups: List<ProxyService.GroupInfo> = emptyList(),
        val nodes: Map<String, ProxyService.NodeInfo> = emptyMap(),
        val latency: Map<String, Int?> = emptyMap(),
        val mode: String? = null,
        val search: String = "",
        val sort: SortMode = SortMode.DELAY,
        val testing: Boolean = false,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val service: ProxyService?
        get() = EngineRuntime.manager?.let { mgr ->
            ProxyService(
                client = mgr.restClient,
                testUrlProvider = { mgr.currentSettings().testUrl },
            )
        }

    init {
        // Windows bug #40 regression class: groups must refetch after every
        // config hot reload / profile switch (groups:refresh event).
        service?.observeGroupsRefresh(viewModelScope) { refresh() }
        refresh()
    }

    fun refresh() {
        val svc = service ?: run {
            _state.value = _state.value.copy(available = false, loading = false)
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = _state.value.groups.isEmpty(), available = true)
            runCatching { svc.catalog() }
                .onSuccess { catalog ->
                    if (catalog != null) {
                        _state.value = _state.value.copy(
                            loading = false,
                            error = null,
                            groups = catalog.groups,
                            nodes = catalog.nodes,
                            mode = svc.mode(),
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

    fun select(group: String, node: String) {
        val svc = service ?: return
        viewModelScope.launch {
            if (svc.select(group, node)) {
                // Refetch so `now` reflects the persisted selection.
                runCatching { svc.catalog() }.onSuccess { catalog ->
                    if (catalog != null) {
                        _state.value = _state.value.copy(groups = catalog.groups)
                    }
                }
            }
        }
    }

    fun testGroup(group: String, members: List<String>) {
        val svc = service ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(testing = true)
            runCatching { svc.testGroup(group, members) }
                .onSuccess { results -> applyLatency(results) }
                .onFailure { e -> _state.value = _state.value.copy(error = e.message) }
            _state.value = _state.value.copy(testing = false)
        }
    }

    fun testNode(node: String) {
        val svc = service ?: return
        viewModelScope.launch {
            runCatching { svc.testNode(node) }
                .onSuccess { delay -> applyLatency(mapOf(node to delay)) }
                .onFailure { e -> _state.value = _state.value.copy(error = e.message) }
        }
    }

    /** Batch-tests every node through the hidden include-all selector. */
    fun testAllNodes() {
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

    fun setMode(mode: String) {
        val svc = service ?: return
        viewModelScope.launch {
            if (svc.setMode(mode)) {
                _state.value = _state.value.copy(mode = mode)
            }
        }
    }

    private fun applyLatency(results: Map<String, Int?>) {
        _state.value = _state.value.copy(latency = _state.value.latency + results)
    }
}
