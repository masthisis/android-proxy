package com.neurorift.android.update

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Bridges [UpdateService] to the Settings screen and the Dashboard banner
 * (issue #20). The service is a process-wide singleton; this ViewModel only
 * adds the coroutine dispatching.
 */
class UpdateViewModel(app: Application) : AndroidViewModel(app) {

    private val service = UpdateService.shared(app)

    val state: StateFlow<UpdateState> = service.state

    /** Settings "Check now" — bypasses the 6 h auto-check guard. */
    fun checkNow() {
        viewModelScope.launch { runCatching { service.check() } }
    }

    /** Downloads the currently available release (Settings + banner path). */
    fun download() {
        val current = state.value as? UpdateState.Available ?: return
        viewModelScope.launch { runCatching { service.download(current.version) } }
    }

    /** Starts the system installer for the verified APK. */
    fun install() {
        val ready = state.value as? UpdateState.Ready ?: return
        runCatching { service.install(ready.file) }
    }

    /** Banner hand-off: a tap on a Ready banner installs directly. */
    fun install(file: File) {
        runCatching { service.install(file) }
    }
}
