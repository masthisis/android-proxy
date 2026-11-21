package com.neurorift.android.failover

import com.neurorift.android.engine.EngineManager
import com.neurorift.android.engine.ProxyService
import com.neurorift.android.settings.Settings
import kotlinx.coroutines.CoroutineScope

/**
 * Process-wide handle to the live watchdog (issue #16) — the pattern of
 * [com.neurorift.android.engine.EngineRuntime]: the VPN layer installs the
 * instance when the tunnel comes up; the Test Center card reads it without
 * service plumbing. Null while failover is disabled or the VPN is down.
 */
object FailoverRuntime {
    @Volatile
    var watchdog: FailoverWatchdog? = null
}

/**
 * Production wiring for the failover watchdog (issue #16): builds a
 * [ProxyService]-backed probe against the live [EngineManager] control
 * plane, honours `settings.failover` (enable / interval / timeout /
 * strikes / cooldown) and owns the start/stop lifecycle so TunService
 * stays a two-line integration.
 */
object WatchdogRunner {

    @Volatile
    private var active: FailoverWatchdog? = null

    /**
     * Stops any previous watchdog, then (when failover is enabled) starts a
     * new one in [scope] probing through [manager].
     */
    fun start(scope: CoroutineScope, manager: EngineManager, failoverProvider: () -> Settings.Failover) {
        stop()
        val cfg = runCatching { failoverProvider() }.getOrNull() ?: return
        if (!cfg.enable) return
        val service = ProxyService(
            client = manager.restClient,
            testUrlProvider = { manager.currentSettings().testUrl },
            timeoutMs = cfg.timeoutMs,
        )
        val watchdog = FailoverWatchdog(
            probe = ProxyServiceProbe(service),
            settings = failoverProvider,
        )
        watchdog.start(scope)
        active = watchdog
        FailoverRuntime.watchdog = watchdog
    }

    /** Cancels the ticker and clears the [FailoverRuntime] handle. */
    fun stop() {
        active?.stop()
        active = null
        FailoverRuntime.watchdog = null
    }
}

/**
 * The real probe: the watchdog's probe path goes through the hidden
 * speed-test selector (`ProxyService.testNode`) exactly like the Test
 * Center — provider members are not directly addressable — and switches
 * the primary group via `PUT /proxies/<group>` (`store-selected` keeps the
 * choice across hot reloads). Every call is failure-isolated: a transport
 * error counts as a failed probe, never an exception escape.
 */
class ProxyServiceProbe(private val service: ProxyService) : Probe {
    override suspend fun catalog(): ProxyService.Catalog? =
        runCatching { service.catalog() }.getOrNull()

    override suspend fun probe(node: String): Int? =
        runCatching { service.testNode(node) }.getOrNull()

    override suspend fun select(group: String, node: String): Boolean =
        runCatching { service.select(group, node) }.getOrDefault(false)
}
