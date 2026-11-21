package com.neurorift.android.ui.profiles

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.neurorift.android.profile.ProfileKind
import com.neurorift.android.profile.ProfileManager
import com.neurorift.android.profile.ProfileViewModel
import com.neurorift.android.profile.YamlLint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Raw profile YAML editor state (issue #17): loads the materialized profile
 * file through [ProfileManager.getRaw], runs [YamlLint.lint] locally for the
 * Validate button and persists through [ProfileManager.updateRaw] (which
 * re-validates before the atomic write and hot-reapplies the active profile
 * on a running tunnel).
 */
class YamlEditorViewModel(app: Application) : AndroidViewModel(app) {

    data class UiState(
        val profileId: String? = null,
        val profileName: String = "",
        val content: String = "",
        val loaded: Boolean = false,
        /** NODES profiles are materialized provider files — view-only. */
        val readOnly: Boolean = false,
        /** True after Validate/Save ran, so the clean-result text can show. */
        val validated: Boolean = false,
        val issues: List<String> = emptyList(),
        val saved: Boolean = false,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val manager: ProfileManager
        get() = ProfileViewModel.manager(getApplication())

    /** Loads the raw document + record for the routed profile id. */
    fun load(profileId: String) {
        if (_state.value.profileId == profileId && _state.value.loaded) return
        viewModelScope.launch(Dispatchers.IO) {
            val record = manager.list().firstOrNull { it.id == profileId }
            _state.value = UiState(
                profileId = profileId,
                profileName = record?.name ?: profileId,
                content = manager.getRaw(profileId) ?: "",
                loaded = true,
                readOnly = record?.kind == ProfileKind.NODES,
                error = if (record == null) "unknown profile" else null,
            )
        }
    }

    fun update(content: String) {
        _state.value = _state.value.copy(content = content, validated = false, issues = emptyList(), saved = false)
    }

    /** Local lint only — nothing is written (same lint as the save path). */
    fun validate() {
        val current = _state.value
        viewModelScope.launch(Dispatchers.Default) {
            val issues = YamlLint.lint(current.content).map { YamlLint.format(it) }
            _state.value = _state.value.copy(validated = true, issues = issues, saved = false)
        }
    }

    /** Persists through the manager (validate → atomic write → engine). */
    fun save() {
        val profileId = _state.value.profileId ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val issues = manager.updateRaw(profileId, _state.value.content)
            _state.value = _state.value.copy(validated = true, issues = issues, saved = issues.isEmpty())
        }
    }
}
