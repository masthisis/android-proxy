package com.neurorift.android.profile

import com.neurorift.android.core.CoreBridge
import com.neurorift.android.engine.ConfigGen
import com.neurorift.android.engine.EngineHolder
import com.neurorift.android.engine.EngineState
import com.neurorift.android.engine.Events
import com.neurorift.android.engine.EventBus
import com.neurorift.android.engine.SubPhase
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

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
    )

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val registryFile = File(dataDir, "profiles.json")
    private val providersDir = File(dataDir, "providers").apply { mkdirs() }
    private val clashDir = File(dataDir, "clash").apply { mkdirs() }
    private val lock = Mutex()
    private val registry = mutableListOf<ProfileRecord>()
    private var loaded = false
    private var activeId: String? = null
    private val fetchClient = OkHttpClient.Builder()
        .connectTimeout(java.time.Duration.ofSeconds(15))
        .readTimeout(java.time.Duration.ofSeconds(30))
        .build()

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

    /** Import from a subscription URL; fetch happens direct (via-proxy arrives with #14 settings). */
    suspend fun addFromUrl(url: String, name: String? = null): ProfileRecord = withContext(Dispatchers.IO) {
        emitProgress(null, SubPhase.FETCHING, url)
        val body = fetch(url)
        addFromText(body, name, sourceUrl = url)
    }

    suspend fun addFromText(
        content: String,
        name: String? = null,
        sourceUrl: String? = null,
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
        return fetchClient.newCall(request).execute().use { resp ->
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
    suspend fun update(profileId: String, engineManager: suspend () -> Boolean): Boolean =
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
