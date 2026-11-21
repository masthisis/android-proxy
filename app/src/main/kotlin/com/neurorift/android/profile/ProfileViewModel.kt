package com.neurorift.android.profile

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.neurorift.android.engine.EngineHolder
import com.neurorift.android.engine.EngineRuntime
import com.neurorift.android.engine.EngineState
import com.neurorift.android.profile.ProfileManager
import com.neurorift.android.profile.ProxyConfig
import com.neurorift.android.settings.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Bridges [ProfileManager] to the Profiles screen. The manager is created
 * once per process (the registry file lives in the core home dir).
 */
class ProfileViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        @Volatile
        private var shared: ProfileManager? = null

        fun manager(context: Context): ProfileManager =
            shared ?: ProfileManager(
                dataDir = ProfileManager.dataDir(context),
                engine = { reason ->
                    // Engine hot-apply callback: the manager owns no engine
                    // reference — a running tunnel hot-reloads the generated
                    // config; with the VPN down the apply happens on the
                    // next start (issue #10, #17).
                    val mgr = EngineRuntime.manager
                    if (mgr != null && EngineHolder.status.value.state == EngineState.RUNNING) {
                        mgr.hotReload(reason)
                    } else {
                        true
                    }
                },
                proxyProvider = {
                    // updateViaProxy (#18): subscription fetches ride the
                    // app's own mixed inbound so they follow the tunnel.
                    val settings = SettingsStore.shared(context.applicationContext).getBlocking()
                    if (settings.subscription.updateViaProxy) {
                        ProxyConfig(host = "127.0.0.1", port = settings.ports.mixed)
                    } else {
                        null
                    }
                },
            ).also { shared = it }
    }

    private val manager: ProfileManager = manager(getApplication())

    private val _profiles = MutableStateFlow(manager.list())
    val profiles: StateFlow<List<ProfileManager.ProfileRecord>> = _profiles.asStateFlow()

    private val _progress = MutableStateFlow<ProfileManager.SubProgress?>(null)
    val progress: StateFlow<ProfileManager.SubProgress?> = _progress.asStateFlow()

    /** Update All outcome (issue #18) — null until the first run settles. */
    private val _summary = MutableStateFlow<ProfileManager.UpdateSummary?>(null)
    val summary: StateFlow<ProfileManager.UpdateSummary?> = _summary.asStateFlow()

    var activeId: String? = manager.activeId()
        private set

    init {
        // Registry + progress events refresh the UI (profiles:changed,
        // sub:progress — CONTRACT.md).
        com.neurorift.android.engine.EventBus.on(
            com.neurorift.android.engine.Events.SUB_PROGRESS,
            viewModelScope,
        ) { _progress.value = it as? ProfileManager.SubProgress }
        com.neurorift.android.engine.EventBus.on(
            com.neurorift.android.engine.Events.PROFILES_CHANGED,
            viewModelScope,
        ) { refreshList() }
    }

    private fun refreshList() {
        _profiles.value = manager.list()
        activeId = manager.activeId()
    }

    fun addFromUrl(url: String, name: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            // New URL imports inherit the subscription.defaultIntervalH
            // default so the auto-update scheduler owns them (#18).
            val interval = runCatching {
                SettingsStore.shared(getApplication()).get().subscription.defaultIntervalH
            }.getOrDefault(0)
            runCatching { manager.addFromUrl(url, name, intervalH = interval) }
                .onSuccess { refreshList() }
        }
    }

    fun importClipboard(content: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { manager.addFromText(content) }
                .onSuccess { refreshList() }
        }
    }

    fun activate(id: String) {
        manager.setActive(id)
        refreshList()
    }

    fun refresh(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            manager.update(id) {
                // Hot provider swap through the engine when running.
                true
            }
            refreshList()
        }
    }

    /** Update All (#18): parallel refresh of every URL-sourced profile. */
    fun updateAll() {
        viewModelScope.launch(Dispatchers.IO) {
            _summary.value = manager.updateAll()
            refreshList()
        }
    }

    fun remove(id: String) {
        manager.remove(id)
        refreshList()
    }
}
