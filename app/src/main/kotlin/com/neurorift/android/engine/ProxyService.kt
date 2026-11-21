package com.neurorift.android.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Proxy service behind the Proxies dashboard (issue #12) — the Android
 * port of the Windows service_proxy.go surface:
 *
 * - [catalog]: /proxies parsed into groups (Selector/URLTest/Fallback/
 *   LoadBalance) + flat node info with last delay history,
 * - [select]: switch a selector group (store-selected keeps it across hot
 *   reloads),
 * - [testGroup]: one-shot batch URL test via `/group/<name>/delay`
 *   (mihomo probes members concurrently),
 * - [testNode]: single probe through the hidden speed-test selector —
 *   provider nodes are probed via `select + group delay` (the documented
 *   Windows pitfall: provider members are not directly addressable),
 * - [setMode]/[mode]: live rule/global/direct patch (PATCH /configs),
 * - [latencyResults]: streamed per batch (group, results, at) and
 * - groups:refresh events from [EventBus] re-trigger catalog fetches after
 *   profile switches / config hot reloads (Windows bug #40 regression).
 */
class ProxyService(
    private val client: MihomoRestClient,
    private val testUrlProvider: () -> String,
    private val timeoutMs: Int = DEFAULT_TIMEOUT_MS,
) {

    // ------------------------------------------------------------- catalog --

    data class NodeInfo(
        val name: String,
        val type: String,
        /** Last measured delay (ms); null = never tested / unreachable. */
        val delay: Int?,
    )

    data class GroupInfo(
        val name: String,
        val type: String,
        val now: String,
        val nodes: List<String>,
        /** Hidden internal groups (speed-test selector) collapse by default. */
        val hidden: Boolean,
    )

    data class Catalog(
        val groups: List<GroupInfo>,
        val nodes: Map<String, NodeInfo>,
    )

    /** One latency batch result (Windows `latencyResults` event parity). */
    data class LatencyResult(val group: String, val results: Map<String, Int?>, val at: Long)

    /** Streamed after every single or batch test — the UI updates in place. */
    val latencyResults = MutableSharedFlow<LatencyResult>(extraBufferCapacity = 16)

    private val json = Json { ignoreUnknownKeys = true }

    private val groupTypes = setOf("Selector", "URLTest", "Fallback", "LoadBalance")

    /**
     * Parses a /proxies response body into the catalog. Pure so JVM tests
     * pin the group detection, hidden flag and delay-history extraction.
     */
    fun parseCatalog(body: String): Catalog = runCatching {
        val root = json.decodeFromString<JsonObject>(body)
        val proxies = root["proxies"]?.jsonObject ?: return Catalog(emptyList(), emptyMap())
        val groups = mutableListOf<GroupInfo>()
        val nodes = linkedMapOf<String, NodeInfo>()
        for ((name, value) in proxies) {
            val obj = value as? JsonObject ?: continue
            val type = obj.str("type") ?: continue
            val history = obj["history"] as? kotlinx.serialization.json.JsonArray
            val delay = history?.lastOrNull()
                ?.let { (it as? JsonObject)?.str("delay")?.toIntOrNull() }
            if (type in groupTypes) {
                val members = (obj["all"] as? kotlinx.serialization.json.JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p !== JsonNull }?.content }
                    ?: emptyList()
                groups.add(
                    GroupInfo(
                        name = name,
                        type = type,
                        now = obj.str("now") ?: "",
                        nodes = members,
                        hidden = name == Groups.SPEED_TEST,
                    ),
                )
            } else {
                nodes[name] = NodeInfo(name = name, type = type, delay = delay)
            }
        }
        Catalog(groups = groups, nodes = nodes)
    }.getOrDefault(Catalog(emptyList(), emptyMap()))

    suspend fun catalog(): Catalog? {
        val body = client.proxies()?.toString() ?: return null
        return parseCatalog(body)
    }

    // -------------------------------------------------------------- actions --

    /** Switches a selector to [node]; selection persists via store-selected. */
    suspend fun select(group: String, node: String): Boolean =
        client.selectProxy(group, node)

    /**
     * Batch-tests every member of [group] with one mihomo call; results are
     * streamed on [latencyResults] (unreachable members map to null).
     */
    suspend fun testGroup(group: String, members: List<String>): Map<String, Int?> {
        val url = testUrlProvider()
        val reached = client.groupDelay(group, url, timeoutMs)
        val results: Map<String, Int?> = members.associateWith { reached[it] }
        latencyResults.emit(LatencyResult(group = group, results = results, at = System.currentTimeMillis()))
        return results
    }

    /**
     * Single-node probe through the hidden speed-test selector: select the
     * node there, then delay-test the group itself (probe mechanism from
     * the Windows pitfall catalog — provider members are not addressable).
     */
    suspend fun testNode(node: String): Int? {
        if (!client.selectProxy(Groups.SPEED_TEST, node)) return null
        val delay = client.delay(Groups.SPEED_TEST, testUrlProvider(), timeoutMs)
        latencyResults.emit(
            LatencyResult(group = Groups.SPEED_TEST, results = mapOf(node to delay), at = System.currentTimeMillis()),
        )
        return delay
    }

    // ---------------------------------------------------------------- mode --

    suspend fun setMode(mode: String): Boolean = client.patchMode(mode)

    suspend fun mode(): String? = client.mode()

    // ------------------------------------------------------------- refresh --

    /**
     * Refetch callback on `groups:refresh` (profile switch / hot reload) —
     * the Windows #40 regression class: stale group state after reloads.
     */
    fun observeGroupsRefresh(scope: CoroutineScope, onRefresh: suspend () -> Unit) {
        EventBus.on(Events.GROUPS_REFRESH, scope) { onRefresh() }
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

    companion object {
        const val DEFAULT_TIMEOUT_MS = 5000
    }
}
