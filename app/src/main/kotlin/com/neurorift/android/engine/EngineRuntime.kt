package com.neurorift.android.engine

/**
 * Process-wide handle to the live [EngineManager] (issue #12). The VPN layer
 * creates the manager when the tunnel comes up; screens read it through this
 * registry instead of threading references through composables. Null while
 * the VPN is down — the UI renders a "connect first" state.
 */
object EngineRuntime {
    @Volatile
    var manager: EngineManager? = null
}
