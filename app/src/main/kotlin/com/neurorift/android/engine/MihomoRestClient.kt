package com.neurorift.android.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.URLEncoder
import java.time.Duration

/**
 * Mihomo external-controller REST client — the Android control plane,
 * mirroring the Windows internal/engine/api.go surface (CONTRACT.md):
 *
 * - request/response endpoints use a short-timeout client,
 * - the STREAM endpoints (/traffic, /logs, /memory) are consumed by
 *   [StreamClient] with a dedicated **no-read-timeout** client (pitfall P1:
 *   the Windows #39 bug — a shared timeout killed live streams every 30 s —
 *   must never return).
 *
 * Every call authenticates with the controller secret (`Authorization:
 * Bearer <secret>`); the controller binds 127.0.0.1 only.
 */
class MihomoRestClient(
    private val port: Int,
    private val secretProvider: () -> String,
) {

    private val json = Json { ignoreUnknownKeys = true }

    /** Short-timeout client for request/response endpoints. */
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(Duration.ofSeconds(5))
        .readTimeout(Duration.ofSeconds(10))
        .writeTimeout(Duration.ofSeconds(10))
        .build()

    private val baseUrl get() = "http://127.0.0.1:$port"

    private fun jstr(value: String): String = JsonPrimitive(value).toString()

    private fun request(path: String, method: String = "GET", body: String? = null): Request {
        val builder = Request.Builder()
            .url(baseUrl + path)
            .header("Authorization", "Bearer ${secretProvider()}")
        when (method) {
            "GET" -> builder.get()
            "DELETE" -> builder.delete()
            else -> builder.method(method, (body ?: "{}").toRequestBody("application/json".toMediaType()))
        }
        return builder.build()
    }

    /**
     * Executes a request and returns (httpCode, bodyText). The body is read
     * INSIDE the use block — returning the Response itself would hand back a
     * closed body (the exact bug class behind Windows #39's dead streams).
     */
    private suspend fun call(request: Request): Result<Pair<Int, String>?> = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) null else resp.code to resp.body.string()
            }
        }
    }

    // ---------------------------------------------------------------- status --

    suspend fun version(): String? =
        call(request("/version")).getOrNull()?.second?.let { body ->
            runCatching {
                json.decodeFromString<JsonObject>(body)["version"]?.jsonPrimitive?.content
            }.getOrNull()
        }

    // ---------------------------------------------------------------- configs --

    /** Live mode patch (rule/global/direct) — never reloads the core. */
    suspend fun patchMode(mode: String): Boolean =
        call(request("/configs", "PATCH", """{"mode":${jstr(mode)}}"""))
            .getOrNull() != null

    /** Hot config reload (profile switch / update); force bypasses caching. */
    suspend fun putConfig(path: String): Boolean =
        call(request("/configs?force=true", "PUT", """{"path":${jstr(path)}}"""))
            .getOrNull() != null

    // ---------------------------------------------------------------- proxies --

    suspend fun proxies(): JsonObject? =
        call(request("/proxies")).getOrNull()?.second?.let { body ->
            runCatching { json.decodeFromString<JsonObject>(body) }.getOrNull()
        }

    suspend fun selectProxy(group: String, node: String): Boolean =
        call(
            request(
                "/proxies/" + URLEncoder.encode(group, "UTF-8"),
                "PUT",
                """{"name":${jstr(node)}}""",
            ),
        ).getOrNull() != null

    /** Hot provider swap — subscription updates never restart the core. */
    suspend fun providerRefresh(name: String): Boolean =
        call(request("/providers/proxies/" + URLEncoder.encode(name, "UTF-8"), "PUT"))
            .getOrNull() != null

    suspend fun delay(node: String, url: String, timeoutMs: Int): Int? =
        call(
            request(
                "/proxies/" + URLEncoder.encode(node, "UTF-8") +
                    "/delay?url=" + URLEncoder.encode(url, "UTF-8") +
                    "&timeout=" + timeoutMs,
            ),
        ).getOrNull()?.second?.let { body ->
            runCatching {
                json.decodeFromString<JsonObject>(body)["delay"]?.jsonPrimitive?.content?.toIntOrNull()
            }.getOrNull()
        }

    /**
     * Batch URL test of a whole group — mihomo probes every member
     * concurrently and answers a map {node: delayMs}; unreachable nodes are
     * omitted (issue #12 batch test path).
     */
    suspend fun groupDelay(group: String, url: String, timeoutMs: Int): Map<String, Int> =
        call(
            request(
                "/group/" + URLEncoder.encode(group, "UTF-8") +
                    "/delay?url=" + URLEncoder.encode(url, "UTF-8") +
                    "&timeout=" + timeoutMs,
            ),
        ).getOrNull()?.second?.let { body ->
            runCatching {
                val obj = json.decodeFromString<JsonObject>(body)
                obj.entries.mapNotNull { (name, value) ->
                    (value as? JsonPrimitive)?.content?.toIntOrNull()?.let { name to it }
                }.toMap()
            }.getOrDefault(emptyMap())
        } ?: emptyMap()

    /** Current live mode ("rule" | "global" | "direct") or null. */
    suspend fun mode(): String? =
        call(request("/configs")).getOrNull()?.second?.let { body ->
            runCatching {
                json.decodeFromString<JsonObject>(body)["mode"]?.jsonPrimitive?.content
            }.getOrNull()
        }

    suspend fun rules(): List<String> =
        call(request("/rules")).getOrNull()?.second?.let { body ->
            runCatching {
                val obj = json.decodeFromString<JsonObject>(body)
                obj["rules"]?.jsonArray?.mapNotNull { rule ->
                    val r = rule.jsonObject
                    // Empty payloads (MATCH rules) must not produce ',,'.
                    listOfNotNull(r.str("type"), r.str("payload"), r.str("proxy"))
                        .filter { it.isNotBlank() }
                        .joinToString(",")
                } ?: emptyList()
            }.getOrDefault(emptyList())
        } ?: emptyList()

    // ------------------------------------------------------------ connections --

    suspend fun closeConnection(id: String): Boolean =
        call(request("/connections/" + URLEncoder.encode(id, "UTF-8"), "DELETE")).getOrNull() != null

    suspend fun closeAllConnections(): Boolean =
        call(request("/connections", "DELETE")).getOrNull() != null

    suspend fun connectionsSnapshot(): ConnectionsSnapshot? =
        call(request("/connections")).getOrNull()?.second?.let { body ->
            parseConnections(body)
        }

    fun parseConnections(body: String): ConnectionsSnapshot? = runCatching {
        val obj = json.decodeFromString<JsonObject>(body)
        val rows = obj["connections"]?.jsonArray?.map { item ->
            val c = item.jsonObject
            val meta = c["metadata"]?.jsonObject
            ConnectionRow(
                id = c.str("id") ?: "",
                host = listOfNotNull(
                    meta?.str("destinationIP"),
                    meta?.str("destinationPort"),
                ).joinToString(":"),
                rule = listOfNotNull(c.str("rule"), c.str("rulePayload"))
                    .filter { it.isNotBlank() }
                    .joinToString(" "),
                chains = c["chains"]?.jsonArray
                    ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p !== JsonNull }?.content }
                    ?.asReversed()
                    ?.joinToString(" → ")
                    .orEmpty(),
                up = c["upload"]?.jsonPrimitive?.longOrNull ?: 0,
                down = c["download"]?.jsonPrimitive?.longOrNull ?: 0,
                start = c.str("start") ?: "",
            )
        } ?: emptyList()
        ConnectionsSnapshot(
            downloadTotal = obj["downloadTotal"]?.jsonPrimitive?.longOrNull ?: 0,
            uploadTotal = obj["uploadTotal"]?.jsonPrimitive?.longOrNull ?: 0,
            rows = rows,
        )
    }.getOrNull()

    data class ConnectionRow(
        val id: String,
        val host: String,
        val rule: String,
        val chains: String,
        val up: Long,
        val down: Long,
        val start: String,
    )

    data class ConnectionsSnapshot(
        val downloadTotal: Long,
        val uploadTotal: Long,
        val rows: List<ConnectionRow>,
    )

    /** Null-safe string extraction: JSON null and missing keys both yield null. */
    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

    companion object {
        /** Exposed for tests: parse a /proxies response body. */
        fun parseJsonForTests(body: String): JsonObject =
            Json { ignoreUnknownKeys = true }.decodeFromString(body)
    }
}

/** Thrown by callers that prefer exceptions on transport errors. */
class RestException(message: String, cause: Throwable? = null) : IOException(message, cause)
