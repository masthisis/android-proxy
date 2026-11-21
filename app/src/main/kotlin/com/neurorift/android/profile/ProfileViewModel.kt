package com.neurorift.android.profile

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.neurorift.android.engine.EngineHolder
import com.neurorift.android.engine.EngineRuntime
import com.neurorift.android.engine.EngineState
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
            ).also { shared = it }
    }

    private val manager: ProfileManager = manager(getApplication())

    private val _profiles = MutableStateFlow(manager.list())
    val profiles: StateFlow<List<ProfileManager.ProfileRecord>> = _profiles.asStateFlow()

    private val _progress = MutableStateFlow<ProfileManager.SubProgress?>(null)
    val progress: StateFlow<ProfileManager.SubProgress?> = _progress.asStateFlow()

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
            runCatching { manager.addFromUrl(url, name) }
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

    fun remove(id: String) {
        manager.remove(id)
        refreshList()
    }
}
