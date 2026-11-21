package com.neurorift.android.profile

import com.neurorift.android.core.CoreBridge
import com.neurorift.android.engine.ConfigGen
import com.neurorift.android.engine.EngineHolder
import com.neurorift.android.engine.EngineState
import com.neurorift.android.engine.Events
import com.neurorift.android.engine.EventBus
import com.neurorift.android.engine.SubPhase
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * HTTP proxy endpoint subscription fetches are routed through when the
 * `subscription.updateViaProxy` setting is on (#18) — the app's own local
 * mixed inbound, so subscription traffic follows the tunnel like everything
 * else.
 */
data class ProxyConfig(val host: String, val port: Int)

/**
 * Subscription/profile registry and materializer — the Android port of the
 * Windows internal/profile manager. Responsibilities:
 *
 * - import from URL / clipboard / text / file with [Decode] auto-detection,
 * - persist the registry as atomic JSON (`profiles.json`, DataStore-grade),
 * - materialize NODES-kind subscriptions as file proxy-providers
 *   (`providers/sub-<id>.yaml`) and CLASH/LOCAL raw YAML (`clash/<id>.yaml`),
 * - per-profile interval + last-sync bookkeeping,
 * - `sub:progress` events (fetching/parsing/writing/done/error) with the
 *   node-count + warnings surfaced in the done-phase detail (never silent),
 * - remove + revert engine when the active profile is removed.
 *
 * The engine hot-applies via EngineManager (#10) — the manager never touches
 * the core process itself.
 */
class ProfileManager(
    private val dataDir: File,
    private val engine: suspend (reason: String) -> Boolean,
    /** Returns the proxy for subscription fetches, or null to fetch direct (#18). */
    private val proxyProvider: (() -> ProxyConfig?)? = null,
) {

    @Serializable
    data class ProfileRecord(
        val id: String,
        val name: String,
        val kind: ProfileKind,
        val providerPath: String? = null,
        val rawPath: String? = null,
        val sourceUrl: String? = null,
        val intervalH: Int = 0,
        val lastSyncAt: Long = 0L,
        val lastError: String? = null,
        val nodeCount: Int = 0,
        /** #23: member of the union routing set (additional provider sources). */
        val unionActive: Boolean = false,
    )

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val registryFile = File(dataDir, "profiles.json")
    private val providersDir = File(dataDir, "providers").apply { mkdirs() }
    private val clashDir = File(dataDir, "clash").apply { mkdirs() }
    private val lock = Mutex()
    private val registry = mutableListOf<ProfileRecord>()
    private var loaded = false
    private var activeId: String? = null

    @Volatile
    private var directClient: OkHttpClient? = null

    @Volatile
    private var proxyClient: OkHttpClient? = null

    @Volatile
    private var proxyClientKey: String? = null

    /**
     * Fetch client for subscription downloads — direct by default, through
     * the configured HTTP proxy when [proxyProvider] supplies one (cached
     * per host:port so a settings change rebuilds it lazily).
     */
    private fun fetchClient(): OkHttpClient {
        val config = proxyProvider?.invoke()
        if (config == null) {
            return directClient ?: OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(15))
                .readTimeout(Duration.ofSeconds(30))
                .build()
                .also { directClient = it }
        }
        val key = "${config.host}:${config.port}"
        proxyClient?.takeIf { proxyClientKey == key }?.let { return it }
        val client = OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(15))
            .readTimeout(Duration.ofSeconds(30))
            .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(config.host, config.port)))
            .build()
        proxyClient = client
        proxyClientKey = key
        return client
    }

    // ---------------------------------------------------------------- store --

    fun load() {
        if (loaded) return
        if (registryFile.exists()) {
            runCatching {
                val doc = json.decodeFromString<RegistryDoc>(registryFile.readText())
                registry.clear()
                registry.addAll(doc.profiles)
                activeId = doc.activeId
            }
        }
        loaded = true
    }

    @Serializable
    private data class RegistryDoc(val profiles: List<ProfileRecord>, val activeId: String?)

    private fun save() {
        registryFile.parentFile?.mkdirs()
        registryFile.writeText(json.encodeToString(RegistryDoc(registry.toList(), activeId)))
    }

    private fun persist() {
        // Atomic write: temp file + rename (Windows xjson parity).
        val tmp = File(registryFile.parentFile, "profiles.json.tmp")
        tmp.writeText(json.encodeToString(RegistryDoc(registry.toList(), activeId)))
        if (!tmp.renameTo(registryFile)) {
            registryFile.writeText(tmp.readText())
            tmp.delete()
        }
    }

    // ----------------------------------------------------------------- list --

    fun list(): List<ProfileRecord> {
        load()
        return registry.toList()
    }

    fun active(): ProfileRecord? {
        load()
        return registry.firstOrNull { it.id == activeId }
    }

    fun meta(): List<ProfileMeta> {
        load()
        return registry.map {
            ProfileMeta(
                id = it.id,
                name = it.name,
                kind = it.kind,
                providerPath = it.providerPath,
                rawPath = it.rawPath,
                intervalH = it.intervalH,
                lastSyncAt = it.lastSyncAt,
            )
        }
    }

    fun activeId(): String? {
        load()
        return activeId
    }

    fun nodeNames(profileId: String): List<String> {
        load()
        val record = registry.firstOrNull { it.id == profileId } ?: return emptyList()
        val path = record.providerPath ?: record.rawPath ?: return emptyList()
        val file = File(dataDir, path)
        if (!file.exists()) return emptyList()
        return runCatching {
            when (record.kind) {
                ProfileKind.NODES -> {
                    val text = file.readText()
                    Decode.decode(text).nodes.map { it.name }
                }

                else -> {
                    val text = file.readText()
                    val doc = org.yaml.snakeyaml.Yaml().load<Any>(text)
                    @Suppress("UNCHECKED_CAST")
                    (doc as? Map<String, Any?>)?.get("proxies")?.let { proxies ->
                        (proxies as? List<Map<String, Any?>>)?.mapNotNull { it["name"] as? String } ?: emptyList()
                    } ?: emptyList()
                }
            }
        }.getOrDefault(emptyList())
    }

    // ---------------------------------------------------------------- import --

    /**
     * Import from a subscription URL. [intervalH] applies the
     * `subscription.defaultIntervalH` setting at import time (#18) so the
     * auto-update scheduler owns the profile from day one.
     */
    suspend fun addFromUrl(
        url: String,
        name: String? = null,
        intervalH: Int = 0,
    ): ProfileRecord = withContext(Dispatchers.IO) {
        emitProgress(null, SubPhase.FETCHING, url)
        val body = fetch(url)
        addFromText(body, name, sourceUrl = url, intervalH = intervalH)
    }

    suspend fun addFromText(
        content: String,
        name: String? = null,
        sourceUrl: String? = null,
        intervalH: Int = 0,
    ): ProfileRecord = withContext(Dispatchers.IO) {
        emitProgress(null, SubPhase.PARSING, name ?: "content")
        val result = Decode.decode(content)
        if (result.format == "unknown" && result.nodes.isEmpty()) {
            emitProgress(null, SubPhase.ERROR, "unrecognized content")
            throw IllegalArgumentException("unrecognized subscription content")
        }
        lock.withLock {
            load()
            val id = newId()
            val kind = when (result.format) {
                "clash" -> ProfileKind.CLASH
                "clash-proxies" -> ProfileKind.NODES
                else -> ProfileKind.NODES
            }
            val record = ProfileRecord(
                id = id,
                name = name?.ifEmpty { null } ?: defaultName(result.format, id),
                kind = kind,
                providerPath = if (kind == ProfileKind.NODES) "providers/sub-$id.yaml" else null,
                rawPath = if (kind != ProfileKind.NODES) "clash/$id.yaml" else null,
                sourceUrl = sourceUrl,
                // URL imports are in sync at import time — the scheduler's
                // "never synced => due" rule must not re-fetch them (#18).
                intervalH = intervalH.coerceIn(0, 168),
                lastSyncAt = if (sourceUrl.isNullOrEmpty()) 0L else System.currentTimeMillis(),
                nodeCount = result.nodes.size,
            )
            writeProfileFile(record, content)
            registry.add(record)
            if (activeId == null && result.nodes.isNotEmpty()) activeId = id
            persist()
            emitProgress(id, SubPhase.WRITING, "${result.nodes.size} nodes")
            // Done-phase detail surfaces warnings — never silent (v1.8 parity).
            val detail = buildString {
                append("${result.nodes.size} nodes")
                if (result.warnings.isNotEmpty()) {
                    append(", ${result.warnings.size} skipped")
                }
            }
            emitProgress(id, SubPhase.DONE, detail)
            applyEngine("profile-added")
            record
        }
    }

    /** Local imports never hit the network — same pipeline as text. */
    suspend fun addFromLocalFile(fileName: String, content: String): ProfileRecord =
        addFromText(content, name = fileName)

    private suspend fun fetch(url: String): String {
        val request = Request.Builder().url(url)
            .header("User-Agent", "NeuroRift-Android/1.0")
            .build()
        return fetchClient().newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalArgumentException("subscription fetch failed: HTTP ${resp.code}")
            resp.body.string()
        }
    }

    // ------------------------------------------------------------- lifecycle --

    fun remove(profileId: String): Boolean {
        load()
        val record = registry.firstOrNull { it.id == profileId } ?: return false
        record.providerPath?.let { File(dataDir, it).delete() }
        record.rawPath?.let { File(dataDir, it).delete() }
        lock.let {
            registry.removeAll { it.id == profileId }
            if (activeId == profileId) {
                activeId = registry.firstOrNull()?.id
            }
            persist()
        }
        emitChanged()
        applyEngine("profile-removed")
        return true
    }

    fun setActive(profileId: String): Boolean {
        load()
        if (registry.none { it.id == profileId }) return false
        activeId = profileId
        persist()
        emitChanged()
        applyEngine("profile-activated")
        return true
    }

    /**
     * Union membership toggle (#23): marks a profile as part of the union
     * routing set — its provider joins the active profile's Node Select
     * `use` list so multiple subscriptions route as ONE set (Windows
     * parity). [activeId] stays the PRIMARY profile; union members are
     * additional provider sources. Persists + emits `profiles:changed`; a
     * running tunnel hot-reloads the regenerated config (same pattern as
     * [setActive]). Unknown ids are a no-op.
     */
    fun setUnionActive(profileId: String, active: Boolean): Boolean {
        load()
        val idx = registry.indexOfFirst { it.id == profileId }
        if (idx < 0) return false
        if (registry[idx].unionActive == active) return true
        registry[idx] = registry[idx].copy(unionActive = active)
        persist()
        emitChanged()
        applyEngine("profile-union-changed")
        return true
    }

    /**
     * Ids of the profiles currently flagged as union members, in registry
     * order (ConfigGen filters them to NODES kinds and excludes the
     * primary — engine/unionIds plumbing for issue #23).
     */
    fun unionIds(): List<String> {
        load()
        return registry.filter { it.unionActive }.map { it.id }
    }

    fun rename(profileId: String, name: String): Boolean {
        load()
        val idx = registry.indexOfFirst { it.id == profileId }
        if (idx < 0) return false
        registry[idx] = registry[idx].copy(name = name)
        persist()
        emitChanged()
        return true
    }

    fun setInterval(profileId: String, hours: Int): Boolean {
        load()
        val idx = registry.indexOfFirst { it.id == profileId }
        if (idx < 0) return false
        registry[idx] = registry[idx].copy(intervalH = hours.coerceIn(0, 168))
        persist()
        emitChanged()
        return true
    }

    /**
     * Refreshes the subscription content in place: fetch -> decode -> rewrite
     * the materialized file -> hot provider swap (PUT /providers/proxies) or
     * engine hot reload — connections never drop (ARCHITECTURE §3).
     */
    suspend fun update(profileId: String, engineManager: suspend () -> Boolean = { true }): Boolean =
        withContext(Dispatchers.IO) {
            load()
            val record = registry.firstOrNull { it.id == profileId }
            if (record == null) {
                emitProgress(profileId, SubPhase.ERROR, "unknown profile")
                return@withContext false
            }
            try {
                emitProgress(profileId, SubPhase.FETCHING, record.name)
                val url = record.sourceUrl
                if (url.isNullOrEmpty()) {
                    // LOCAL / clipboard imports have nothing to refresh.
                    emitProgress(profileId, SubPhase.ERROR, "no source URL")
                    return@withContext false
                }
                val content = fetch(url)
                emitProgress(profileId, SubPhase.PARSING, record.name)
                val result = Decode.decode(content)
                if (result.nodes.isEmpty() && record.kind == ProfileKind.NODES) {
                    throw IllegalArgumentException("subscription returned no nodes")
                }

                emitProgress(profileId, SubPhase.WRITING, "${result.nodes.size} nodes")
                lock.withLock {
                    writeProfileFile(record, content)
                    val idx = registry.indexOfFirst { it.id == profileId }
                    if (idx >= 0) {
                        registry[idx] = registry[idx].copy(
                            lastSyncAt = System.currentTimeMillis(),
                            lastError = null,
                            nodeCount = result.nodes.size,
                        )
                        persist()
                    }
                }
                val swapped = engineManager()
                val detail = buildString {
                    append("${result.nodes.size} nodes")
                    if (!swapped) append(", core not running (applied on next start)")
                    if (result.warnings.isNotEmpty()) append(", ${result.warnings.size} skipped")
                }
                emitProgress(profileId, SubPhase.DONE, detail)
                emitChanged()
                true
            } catch (e: Exception) {
                lock.withLock {
                    val idx = registry.indexOfFirst { it.id == profileId }
                    if (idx >= 0) {
                        registry[idx] = registry[idx].copy(lastError = e.message)
                        persist()
                    }
                }
                emitProgress(profileId, SubPhase.ERROR, e.message ?: "update failed")
                false
            }
        }

    /**
     * Outcome of [updateAll] (issue #18) — the Update All summary row counts.
     * Partial per-profile results fold together through [plus].
     */
    data class UpdateSummary(
        val updated: Int = 0,
        val failed: Int = 0,
        val skipped: Int = 0,
        val errors: List<String> = emptyList(),
    ) {
        /** Folds two partial summaries (counts add, error lists concatenate). */
        operator fun plus(other: UpdateSummary): UpdateSummary = UpdateSummary(
            updated = updated + other.updated,
            failed = failed + other.failed,
            skipped = skipped + other.skipped,
            errors = errors + other.errors,
        )
    }

    /**
     * Update All (issue #18): refreshes every URL-sourced profile in
     * parallel with a bounded concurrency of 4, through the regular
     * [update] pipeline (fetch → decode → materialize → sub:progress →
     * profiles:changed). Profiles without a source URL are counted as
     * skipped — local content has nothing to fetch. Returns the folded
     * [UpdateSummary]; the last failure detail of each failed profile is
     * carried in [UpdateSummary.errors] (it is also persisted per profile
     * as `lastError`).
     */
    suspend fun updateAll(): UpdateSummary = withContext(Dispatchers.IO) {
        load()
        val candidates = registry.toList()
        if (candidates.isEmpty()) return@withContext UpdateSummary()
        val gate = Semaphore(4)
        val partials = coroutineScope {
            candidates.map { record ->
                async {
                    gate.withPermit {
                        if (record.sourceUrl.isNullOrEmpty()) {
                            UpdateSummary(skipped = 1)
                        } else {
                            val ok = runCatching { update(record.id) }.getOrElse { false }
                            if (ok) {
                                UpdateSummary(updated = 1)
                            } else {
                                UpdateSummary(
                                    failed = 1,
                                    errors = listOf(
                                        registry.firstOrNull { it.id == record.id }?.lastError
                                            ?: "${record.name}: update failed",
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        }.awaitAll()
        val summary = partials.reduce { acc, part -> acc + part }
        // One final, whole-run progress tick + a registry refresh so the UI
        // summary row and the per-card sync states settle together.
        emitProgress(
            null,
            SubPhase.DONE,
            "updated ${summary.updated}, failed ${summary.failed}, skipped ${summary.skipped}",
        )
        emitChanged()
        summary
    }

    // ----------------------------------------------------------------- files --

    private fun writeProfileFile(record: ProfileRecord, content: String) {
        val target = when (record.kind) {
            ProfileKind.NODES -> record.providerPath?.let { File(dataDir, it) }
            else -> record.rawPath?.let { File(dataDir, it) }
        } ?: return
        target.parentFile?.mkdirs()
        // NODES kind: materialize as a mihomo proxies-only provider file.
        target.writeText(
            if (record.kind == ProfileKind.NODES) materializeProvider(content) else content,
        )
    }

    /**
     * Materializes arbitrary decoded content into a mihomo proxies-only
     * provider file (YAML with a `proxies:` list).
     */
    private fun materializeProvider(content: String): String {
        val result = Decode.decode(content)
        val proxies = result.nodes.map { it.raw }
        if (result.format == "clash-proxies") return content
        val builder = StringBuilder()
        builder.append("proxies:\n")
        for (proxy in proxies) {
            val yaml = com.neurorift.android.engine.Yaml.dump(
                com.neurorift.android.engine.OMap().apply {
                    for ((k, v) in proxy) set(k, v)
                },
            )
            // Indent the emitted single-node map under a list dash.
            builder.append("  - ").append(yaml.trim().replace("\n", "\n    ")).append("\n")
        }
        return builder.toString()
    }

    // ------------------------------------------------------------- raw editor --

    /**
     * Raw profile file content for the YAML editor (issue #17): the
     * materialized Clash YAML (CLASH/LOCAL) or the provider file (NODES,
     * read-only view). Null for unknown profiles / missing files.
     */
    fun getRaw(profileId: String): String? {
        load()
        val record = registry.firstOrNull { it.id == profileId } ?: return null
        val path = when (record.kind) {
            ProfileKind.NODES -> record.providerPath
            else -> record.rawPath
        } ?: return null
        val file = File(dataDir, path)
        if (!file.exists()) return null
        return runCatching { file.readText() }.getOrNull()
    }

    /**
     * Validates + atomically rewrites the profile's raw YAML (issue #17).
     * Validation runs BEFORE the write ([YamlLint]); nothing is persisted on
     * a failing document. Only CLASH/LOCAL profiles are editable — NODES
     * profiles are materialized provider files owned by the decode pipeline.
     *
     * Returns an empty list on success, otherwise the validation issues as
     * "line X: <problem>" strings. After a successful write the ACTIVE
     * profile triggers the engine callback (guarded on a running tunnel) so
     * the change hot-applies without dropping the connection.
     */
    suspend fun updateRaw(profileId: String, content: String): List<String> =
        withContext(Dispatchers.IO) {
            load()
            val record = registry.firstOrNull { it.id == profileId }
            if (record == null) return@withContext listOf("unknown profile")
            if (record.kind == ProfileKind.NODES) {
                return@withContext listOf("nodes-kind profiles are read-only (provider file)")
            }
            val rawPath = record.rawPath
                ?: return@withContext listOf("profile has no raw file")
            val issues = YamlLint.lint(content)
            if (issues.isNotEmpty()) {
                return@withContext issues.map { YamlLint.format(it) }
            }
            lock.withLock {
                val file = File(dataDir, rawPath)
                file.parentFile?.mkdirs()
                // Atomic write: temp file + rename (Windows xjson parity).
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.writeText(content)
                if (!tmp.renameTo(file)) {
                    file.writeText(tmp.readText())
                    tmp.delete()
                }
            }
            if (record.id == activeId) applyEngine("profile raw edit")
            emptyList()
        }

    // -------------------------------------------------------------- events --

    private fun emitProgress(profileId: String?, phase: String, detail: String) {
        EventBus.emit(Events.SUB_PROGRESS, SubProgress(profileId, phase, detail))
    }

    private fun emitChanged() {
        EventBus.emit(Events.PROFILES_CHANGED, null)
    }

    private fun applyEngine(reason: String) {
        // Engine re-apply is best-effort: when the VPN is not running the
        // config write still happened; the next start picks everything up.
        // Called from suspend contexts and sync mutators alike — the engine
        // lambda is suspend, so launch on the manager's own dispatcher.
        if (EngineHolder.status.value.state != EngineState.RUNNING) return
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            runCatching { engine(reason) }
        }
    }

    private fun newId(): String {
        val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        return buildString {
            repeat(10) { append(chars.random()) }
        }
    }

    private fun defaultName(format: String, id: String): String = when (format) {
        "clash" -> "Clash profile"
        else -> "Subscription"
    }

    data class SubProgress(val profileId: String?, val phase: String, val detail: String)

    companion object {
        /** Core home — providers live under <filesDir>/neurorift/core. */
        fun dataDir(context: android.content.Context): File = CoreBridge.coreHome(context)
    }
}
