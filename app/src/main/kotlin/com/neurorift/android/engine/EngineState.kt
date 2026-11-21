package com.neurorift.android.engine

/**
 * Engine state machine — mirrors the Windows client's engine status
 * (`stopped → starting → running ⇄ updating`, `failed` terminal).
 *
 * Pure Kotlin so the transitions are unit-testable without Android.
 */
enum class EngineState {
    STOPPED,
    STARTING,
    RUNNING,
    STOPPING,
    FAILED,
}

/**
 * Snapshot of the engine status, published as `coreStatus` (CONTRACT.md).
 */
data class EngineStatus(
    val state: EngineState,
    val detail: String? = null,
    val startedAtMs: Long = 0L,
) {
    val isRunning: Boolean get() = state == EngineState.RUNNING
    val isTransitioning: Boolean
        get() = state == EngineState.STARTING || state == EngineState.STOPPING

    companion object {
        val STOPPED = EngineStatus(EngineState.STOPPED)
    }
}

/**
 * Valid transitions of the engine state machine. Anything else is a bug —
 * tests pin this table.
 */
val validTransitions: Map<EngineState, Set<EngineState>> = mapOf(
    EngineState.STOPPED to setOf(EngineState.STARTING),
    // STARTING -> STOPPED covers abrupt service kills by the system.
    EngineState.STARTING to setOf(EngineState.RUNNING, EngineState.STOPPING, EngineState.STOPPED, EngineState.FAILED),
    EngineState.RUNNING to setOf(EngineState.STOPPING, EngineState.STOPPED, EngineState.FAILED),
    EngineState.STOPPING to setOf(EngineState.STOPPED, EngineState.FAILED),
    // FAILED is recoverable by an explicit user action (stop request resets).
    EngineState.FAILED to setOf(EngineState.STARTING, EngineState.STOPPED),
)

/**
 * Tiny reducer for the state machine; throws on illegal transitions so
 * programming errors surface in tests instead of in the VPN layer.
 */
fun transition(current: EngineState, next: EngineState): EngineState {
    val allowed = validTransitions[current]
        ?: error("no transitions defined for $current")
    require(next in allowed) { "illegal engine transition $current -> $next" }
    return next
}
