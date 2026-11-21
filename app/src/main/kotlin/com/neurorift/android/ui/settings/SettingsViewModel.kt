package com.neurorift.android.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.neurorift.android.engine.EngineHolder
import com.neurorift.android.engine.EngineRuntime
import com.neurorift.android.settings.Settings
import com.neurorift.android.settings.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Settings surface state (issue #14): the loaded document, the edit buffer
 * and the save pipeline (sanitize → persist → engine re-apply when running).
 */
class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val store = SettingsStore.shared(app)

    private val _settings = MutableStateFlow<Settings?>(null)
    val settings: StateFlow<Settings?> = _settings.asStateFlow()

    /** Snackbar text: sanitized-result summary (fix count) or error. */
    private val _savedMessage = MutableStateFlow<String?>(null)
    val savedMessage: StateFlow<String?> = _savedMessage.asStateFlow()

    private val _coreVersion = MutableStateFlow<String?>(null)
    val coreVersion: StateFlow<String?> = _coreVersion.asStateFlow()

    init {
        viewModelScope.launch { _settings.value = store.get() }
        viewModelScope.launch {
            EngineRuntime.manager?.let { _coreVersion.value = it.restClient.version() }
        }
    }

    fun save(next: Settings) {
        viewModelScope.launch {
            val saved = store.save(next)
            _settings.value = saved.settings
            _savedMessage.value = if (saved.fixes.isEmpty()) {
                null
            } else {
                "fixed: " + saved.fixes.distinct().joinToString(", ")
            }
            reapplyEngine()
        }
    }

    fun clearMessage() {
        _savedMessage.value = null
    }

    /**
     * Windows parity: the engine re-applies automatically after a settings
     * save. A running tunnel hot-reloads the generated config; port changes
     * take full effect on the next tunnel start.
     */
    private suspend fun reapplyEngine() {
        val mgr = EngineRuntime.manager ?: return
        if (EngineHolder.status.value.state == com.neurorift.android.engine.EngineState.RUNNING) {
            mgr.hotReload("settings changed")
        }
    }
}
