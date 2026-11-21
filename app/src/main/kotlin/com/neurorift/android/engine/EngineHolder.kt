package com.neurorift.android.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Holds the engine status for the whole app process (single VPN at a time,
 * pitfall P5). Later issues (#10) grow this into the full engine manager
 * with the config generator and REST control plane; the state surface
 * published here is the contract that stays.
 */
object EngineHolder {

    private val _status = MutableStateFlow(EngineStatus.STOPPED)

    /** Live engine status; the UI collects this as `coreStatus`. */
    val status: StateFlow<EngineStatus> = _status.asStateFlow()

    fun publish(next: EngineStatus) {
        _status.value = next
    }

    /** Applies a transition; returns the new status. Throws on illegal moves. */
    fun transitionTo(state: EngineState, detail: String? = null): EngineStatus {
        val current = _status.value
        val newState = transition(current.state, state)
        val next = EngineStatus(
            state = newState,
            detail = detail ?: current.detail.takeIf { newState == current.state },
            startedAtMs = if (newState == EngineState.RUNNING) System.currentTimeMillis() else current.startedAtMs,
        )
        _status.value = next
        return next
    }

    /** Test hook: resets to stopped. */
    fun reset() {
        _status.value = EngineStatus.STOPPED
    }
}
