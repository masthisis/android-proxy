package com.neurorift.android.failover

import com.neurorift.android.engine.Events
import com.neurorift.android.engine.EventBus
import com.neurorift.android.engine.Groups
import com.neurorift.android.engine.ProxyService
import com.neurorift.android.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Probe surface the watchdog needs (issue #16). Kept as an interface so the
 * JVM tests drive a fake while production wraps [ProxyService] (the real
 * REST control plane) — [ProxyServiceProbe] in WatchdogRunner.kt.
 */
interface Probe {
    suspend fun catalog(): ProxyService.Catalog?
    suspend fun probe(node: String): Int?
    suspend fun select(group: String, node: String): Boolean
}

/**
 * One failover cycle result, carried on [Events.FAILOVER] and into
 * [FailoverWatchdog.events] (CONTRACT.md event catalog). [kind] is one of
 * [FailoverWatchdog.KIND_SWITCHED] / [KIND_PROBE_OK] / [KIND_PROBE_FAILED].
 */
data class FailoverEvent(
    val kind: String,
    val node: String?,
    val from: String?,
    val to: String?,
    val strikes: Int,
    val at: Long,
)

/** Immutable readback for the Test Center status card. */
data class FailoverSnapshot(
    val watching: Boolean,
    val group: String?,
    val current: String?,
    val strikes: Int,
    val lastEvent: FailoverEvent?,
)

/**
 * Node-health failover watchdog (issue #16) — the Android port of the
 * Windows failover service:
 *
 * - one tick = probe the primary group's current node through [Probe];
 * - a successful probe resets the strike counter (`probe-ok`);
 * - a failed probe counts a strike; once [Settings.Failover.strikes] are
 *   accumulated AND [Settings.Failover.cooldownSec] has elapsed since the
 *   last switch, the group moves to the member with the best known delay
 *   (catalog delay history, current node and DIRECT excluded) and a
 *   `switched` event is emitted;
 * - inside the cooldown the failure is only counted (`probe-failed`) —
 *   never switched, so a flapping node cannot thrash the selection.
 *
 * Pure state machine on top of the injected [Probe] + [now] clock: fully
 * deterministic under JVM tests with a virtual clock.
 */
class FailoverWatchdog(
    private val probe: Probe,
    private val settings: () -> Settings.Failover,
    private val now: () -> Long = System::currentTimeMillis,
) {

    companion object {
        /** Event kinds (Windows failover event parity). */
        const val KIND_SWITCHED = "switched"
        const val KIND_PROBE_OK = "probe-ok"
        const val KIND_PROBE_FAILED = "probe-failed"

        /** Non-node group members that must never win the candidate scan. */
        const val DIRECT = "DIRECT"
        const val REJECT = "REJECT"
    }

    /** Streamed per tick (replayed so a card joining late sees history). */
    val events = MutableSharedFlow<FailoverEvent>(
        replay = 16,
        extraBufferCapacity = 64,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )

    private val mutex = Mutex()
    private var ticker: Job? = null
    private var tickerScope: CoroutineScope? = null

    private var strikes = 0
    private var lastSwitchAtMs = 0L
    private var lastGroup: String? = null
    private var lastNode: String? = null
    private var lastEvent: FailoverEvent? = null

    // ------------------------------------------------------------- control --

    /**
     * Launches the ticker inside [scope]: one cycle per
     * [Settings.Failover.intervalSec], cancellable via [stop].
     */
    fun start(scope: CoroutineScope) {
        stop()
        tickerScope = scope
        ticker = scope.launch {
            while (isActive) {
                delay(settings().intervalSec * 1000L)
                runCatching { tick() }
            }
        }
    }

    /** Cancels the ticker; the state machine stays queryable via [snapshot]. */
    fun stop() {
        ticker?.cancel()
        ticker = null
        tickerScope = null
    }

    /** True while a ticker launched by [start] is still running. */
    fun isWatching(): Boolean = ticker?.isActive == true

    /**
     * Immediate one-shot cycle for the Test Center "Test now" button —
     * fire-and-forget in the ticker's scope (a fresh IO scope when the
     * ticker is not running); result rides [events] like every tick.
     */
    fun testNow() {
        val scope = ticker?.takeIf { it.isActive }?.let { tickerScope }
            ?: CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch { runCatching { tick() } }
    }

    // ------------------------------------------------------------ snapshot --

    /** Current state for the status card. */
    fun snapshot(): FailoverSnapshot = FailoverSnapshot(
        watching = isWatching(),
        group = lastGroup,
        current = lastNode,
        strikes = strikes,
        lastEvent = lastEvent,
    )

    // ---------------------------------------------------------------- tick --

    /**
     * One watchdog cycle. Serialized with a mutex so ticker ticks and
     * "Test now" never interleave strike counting; returns the emitted
     * event (null when the cycle could not run — catalog missing, group
     * unresolved).
     */
    suspend fun tick(): FailoverEvent? = mutex.withLock {
        runCatching { runTick() }.getOrNull()
    }

    private suspend fun runTick(): FailoverEvent? {
        val catalog = probe.catalog() ?: return null
        val group = primaryGroup(catalog) ?: return null
        val current = group.now
        if (current.isBlank()) return null

        lastGroup = group.name
        lastNode = current
        val cfg = settings()

        val delayMs = probe.probe(current)
        return if (delayMs != null) {
            strikes = 0
            emit(FailoverEvent(KIND_PROBE_OK, node = current, from = null, to = null, strikes = 0, at = now()))
        } else {
            strikes += 1
            val cooldownElapsed = (now() - lastSwitchAtMs) >= cfg.cooldownSec * 1000L
            if (strikes < cfg.strikes || !cooldownElapsed) {
                emit(
                    FailoverEvent(
                        kind = KIND_PROBE_FAILED,
                        node = current,
                        from = null,
                        to = null,
                        strikes = strikes,
                        at = now(),
                    ),
                )
            } else {
                val candidate = bestCandidate(catalog, group, current)
                if (candidate == null) {
                    emit(
                        FailoverEvent(
                            kind = KIND_PROBE_FAILED,
                            node = current,
                            from = null,
                            to = null,
                            strikes = strikes,
                            at = now(),
                        ),
                    )
                } else {
                    val switched = probe.select(group.name, candidate)
                    if (switched) {
                        lastSwitchAtMs = now()
                        val firedStrikes = strikes
                        strikes = 0
                        emit(
                            FailoverEvent(
                                kind = KIND_SWITCHED,
                                node = candidate,
                                from = current,
                                to = candidate,
                                strikes = firedStrikes,
                                at = now(),
                            ),
                        )
                    } else {
                        emit(
                            FailoverEvent(
                                kind = KIND_PROBE_FAILED,
                                node = current,
                                from = null,
                                to = null,
                                strikes = strikes,
                                at = now(),
                            ),
                        )
                    }
                }
            }
        }
    }

    private suspend fun emit(event: FailoverEvent): FailoverEvent {
        lastEvent = event
        events.tryEmit(event)
        EventBus.emit(Events.FAILOVER, event)
        return event
    }

    // ------------------------------------------------------------ helpers --

    /**
     * The primary selector: `🚀 Node Select` when present, else the first
     * visible Selector group (the hidden speed-test selector is not a
     * failover target).
     */
    private fun primaryGroup(catalog: ProxyService.Catalog): ProxyService.GroupInfo? =
        catalog.groups.firstOrNull { it.name == Groups.NODE_SELECT }
            ?: catalog.groups.firstOrNull { it.type == "Selector" && !it.hidden }

    /**
     * Member of [group] with the best (lowest) known delay from the
     * catalog's node history; the current node and DIRECT/REJECT pseudo
     * members are excluded. Nested groups are not plain nodes and never
     * carry a delay, so they are skipped implicitly.
     */
    private fun bestCandidate(
        catalog: ProxyService.Catalog,
        group: ProxyService.GroupInfo,
        current: String,
    ): String? = group.nodes
        .filter { it != current && it != DIRECT && it != REJECT }
        .mapNotNull { name -> catalog.nodes[name]?.delay?.takeIf { it > 0 }?.let { name to it } }
        .minByOrNull { it.second }
        ?.first
}
