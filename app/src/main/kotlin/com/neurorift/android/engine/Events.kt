package com.neurorift.android.engine

/**
 * Event names published on the app-wide SharedFlow bus — the single source
 * of truth, mirroring the Windows client's internal/nr constants so
 * docs/CONTRACT.md stays conceptually identical across both codebases.
 */
object Events {
    const val CORE_STATUS = "core:status"
    const val PROFILES_CHANGED = "profiles:changed"
    const val SUB_PROGRESS = "sub:progress"
    const val LATENCY_RESULTS = "latency:results"
    const val SPEED_PROGRESS = "speed:progress"
    const val SPEED_DONE = "speed:done"
    const val FAILOVER = "failover:event"
    const val TRAFFIC = "net:traffic"
    const val CONNECTIONS = "net:connections"
    const val LOGS_CORE = "core:logs"
    const val SETTINGS_CHANGED = "settings:changed"
    const val APP_UPDATE = "app:update"
    const val CORE_CRASH_LOOP = "core:crashloop"
    const val GROUPS_REFRESH = "groups:refresh"
}

/**
 * Group / listener names shared with the Windows client (identical strings
 * so generated configs and the Test Center behave the same).
 */
object Groups {
    /** Hidden selector used by the speed-test engine. */
    const val SPEED_TEST = "⚡ NR SpeedTest"

    /** Primary selector for "nodes" style profiles. */
    const val NODE_SELECT = "🚀 Node Select"

    /** url-test group for "nodes" style profiles. */
    const val AUTO_SELECT = "♻️ NR Auto"

    /** Dedicated inbound used by the throughput tester. */
    const val LISTENER_SPEED_TEST = "nr-speedtest"
}

/** Subscription phases for `sub:progress`. */
object SubPhase {
    const val FETCHING = "fetching"
    const val PARSING = "parsing"
    const val WRITING = "writing"
    const val DONE = "done"
    const val ERROR = "error"
}
