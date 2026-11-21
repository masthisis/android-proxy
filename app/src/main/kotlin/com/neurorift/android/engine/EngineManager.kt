package com.neurorift.android.engine

import com.neurorift.android.core.CoreBridge
import com.neurorift.android.profile.ProfileMeta
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Engine supervisor — the Android port of the Windows internal/engine
 * manager + runner responsibilities:
 *
 * - generates the runtime config (via [ConfigGen]) and applies it to the
 *   embedded core ([CoreBridge.applyConfig]),
 * - hot reloads via `PUT /configs?force=true` with a full restart fallback
 *   (restart = re-apply config; the core lives in-process, pitfall P2),
 * - hot-swaps providers after subscription updates (`PUT /providers/proxies`)
 *   without touching the tunnel,
 * - runs the stream loops (traffic/logs) while active,
 * - enforces the crash-loop guard: more than 5 apply failures inside 2 min
 *   → FAILED with detail + `core:crashloop` event.
 */
class EngineManager(
    private val coreHome: File,
    private val settingsProvider: () -> EngineSettings,
    private val profilesProvider: () -> List<ProfileMeta>,
    private val activeIdProvider: () -> String?,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {

    val restClient: MihomoRestClient =
        MihomoRestClient(settingsProvider().controllerPort) { settingsProvider().controllerSecret }

    val streams: StreamClient =
        StreamClient(settingsProvider().controllerPort) { settingsProvider().controllerSecret }

    private val applyFailureTimestamps = ArrayDeque<Long>()
    private val applyFailuresLock = Any()
    private val crashLoopFired = AtomicInteger(0)

    private var streamLoopsStarted = false

    // ---------------------------------------------------------------- paths --

    /** Path of the generated config file, mihomo-home relative. */
    fun configPath(): File = File(coreHome, "config.yaml")

    // ---------------------------------------------------------------- apply --

    /**
     * Generates + writes the config, applies it to the core and (re)starts
     * the stream loops. Called by the VPN layer after the tun fd is bound
     * and by profile switches.
     */
    suspend fun applyFromProfiles() = withContext(Dispatchers.IO) {
        val settings = settingsProvider()
        val yaml = ConfigGen.generate(
            settings = settings,
            profiles = profilesProvider(),
            activeId = activeIdProvider(),
            contentOf = ::readProfileContent,
        )
        writeConfig(yaml)
        val wasRunning = EngineHolder.status.value.state == EngineState.RUNNING
        if (!wasRunning) EngineHolder.transitionTo(EngineState.STARTING)
        try {
            CoreBridge.applyConfig(yaml)
            ensureStreamLoops()
            if (!wasRunning) EngineHolder.transitionTo(EngineState.RUNNING)
        } catch (e: Exception) {
            recordApplyFailure()
            EngineHolder.transitionTo(EngineState.FAILED, e.message)
            throw e
        }
    }

    /**
     * Hot reload: regenerate + `PUT /configs?force=true` (keeps selections
     * via store-selected); falls back to a full in-process re-apply when the
     * reload is rejected. Emits `groups:refresh` on success (Windows #40).
     */
    suspend fun hotReload(reason: String): Boolean = withContext(Dispatchers.IO) {
        val settings = settingsProvider()
        val yaml = ConfigGen.generate(
            settings = settings,
            profiles = profilesProvider(),
            activeId = activeIdProvider(),
            contentOf = ::readProfileContent,
        )
        writeConfig(yaml)
        val ok = restClient.putConfig(configPath().absolutePath)
        if (ok) {
            EventBus.emit(Events.GROUPS_REFRESH, reason)
        } else {
            try {
                CoreBridge.applyConfig(yaml)
                EventBus.emit(Events.GROUPS_REFRESH, reason)
            } catch (e: Exception) {
                recordApplyFailure()
                EngineHolder.transitionTo(EngineState.FAILED, e.message)
                return@withContext false
            }
        }
        true
    }

    /** Provider hot swap after a subscription update — no tunnel drop. */
    suspend fun refreshProvider(profileId: String): Boolean =
        restClient.providerRefresh(ConfigGen.providerName(profileId))

    /** Live mode patch (rule/global/direct). */
    suspend fun setMode(mode: String): Boolean = restClient.patchMode(mode)

    private fun readProfileContent(profile: ProfileMeta): String {
        val path = profile.rawPath ?: return ""
        val file = File(coreHome, path)
        return file.readText()
    }

    private fun writeConfig(yaml: String) {
        configPath().writeText(yaml)
    }

    // ---------------------------------------------------------------- loops --

    private fun ensureStreamLoops() {
        if (streamLoopsStarted) return
        streamLoopsStarted = true
        scope.launch { runCatching { streams.runTrafficLoop(scope) } }
        scope.launch { runCatching { streams.runLogLoop(scope) } }
    }

    // -------------------------------------------------------- crash loop guard --

    internal fun recordApplyFailure() {
        synchronized(applyFailuresLock) {
            val now = System.currentTimeMillis()
            applyFailureTimestamps.addLast(now)
            while (applyFailureTimestamps.size > CRASH_LOOP_MAX + 1) applyFailureTimestamps.removeFirst()
            val window = now - CRASH_LOOP_WINDOW_MS
            val recent = applyFailureTimestamps.count { it >= window }
            if (recent >= CRASH_LOOP_MAX && crashLoopFired.getAndIncrement() == 0) {
                EventBus.emit(
                    Events.CORE_CRASH_LOOP,
                    "config apply failed $recent times within ${CRASH_LOOP_WINDOW_MS / 1000}s",
                )
            }
        }
    }

    companion object {
        const val CRASH_LOOP_MAX = 5
        const val CRASH_LOOP_WINDOW_MS = 120_000L
    }
}

/**
 * Process-wide event bus: domain managers publish named events, ViewModels
 * collect them (CONTRACT.md event catalog). A SharedFlow with replay so a
 * screen joining late still sees the last event of each type.
 */
object EventBus {
    private val flow = kotlinx.coroutines.flow.MutableSharedFlow<Pair<String, Any?>>(
        replay = 16,
        extraBufferCapacity = 64,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )

    /** All events (name, payload). */
    val events = flow

    /** Publishes an event; never blocks the caller. */
    fun emit(name: String, payload: Any? = null) {
        flow.tryEmit(name to payload)
    }

    /** Collects only the named event, decoded through the given selector. */
    fun <T> on(name: String, scope: CoroutineScope, selector: (Any?) -> T) =
        events
            .onEach { (n, payload) -> if (n == name) selector(payload) }
            .launchIn(scope)
}
