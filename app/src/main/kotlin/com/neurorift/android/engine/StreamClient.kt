package com.neurorift.android.engine

import java.io.IOException
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Chunked stream consumers for the mihomo streaming endpoints — the Android
 * port of the Windows internal/engine/streams.go coalescing rules (PITFALL
 * P1): /traffic, /logs and /memory are infinite streams; they must never be
 * buffered wholesale and must never inherit a request timeout (Windows bug
 * #39). Samples are coalesced before they reach the UI: traffic 1 Hz, logs
 * 0.5 Hz batches with a bounded ring (≤500 lines), memory folded into the
 * traffic tick.
 */
class StreamClient(
    private val port: Int,
    private val secretProvider: () -> String,
) {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Dedicated no-timeout client: the streaming endpoints never end until
     * the core stops; the read timeout must not fire (pitfall P1).
     */
    private val streamClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(Duration.ofSeconds(5))
        .readTimeout(Duration.ZERO)
        .writeTimeout(Duration.ofSeconds(10))
        .build()

    private val baseUrl get() = "http://127.0.0.1:$port"

    private fun streamRequest(path: String): Request = Request.Builder()
        .url(baseUrl + path)
        .header("Authorization", "Bearer ${secretProvider()}")
        .build()

    // ---------------------------------------------------------------- flows --

    /** UI emissions gate — paused while the app is backgrounded (#26). */
    val gate = SamplingGate()

    /** 1 Hz coalesced traffic ticks (bytes/s + core memory). */
    val traffic: kotlinx.coroutines.flow.MutableSharedFlow<TrafficTick> =
        kotlinx.coroutines.flow.MutableSharedFlow(replay = 1, extraBufferCapacity = 4)

    /** Batched core log lines (0.5 Hz flush, ring ≤500). */
    val logs: kotlinx.coroutines.flow.MutableSharedFlow<List<String>> =
        kotlinx.coroutines.flow.MutableSharedFlow(replay = 1, extraBufferCapacity = 16)

    /** In-memory ring of recent log lines for the Logs screen (mirrors Windows). */
    val logRing = LogRing(capacity = 500)

    // ---------------------------------------------------------------- loops --

    /**
     * Consumes /traffic + /memory and emits 1 Hz ticks until the caller's
     * scope is cancelled. Runs inside [scope] on Dispatchers.IO.
     */
    suspend fun runTrafficLoop(scope: CoroutineScope) = withContext(Dispatchers.IO) {
        val up = AtomicLong(0)
        val down = AtomicLong(0)
        val memory = AtomicLong(0)

        val readers = listOf(
            scope.launch(Dispatchers.IO) {
                streamLines("/traffic") { line ->
                    val obj = runCatching { json.decodeFromString<JsonObject>(line) }.getOrNull()
                        ?: return@streamLines
                    obj.long("up")?.let { up.set(it) }
                    obj.long("down")?.let { down.set(it) }
                }
            },
            scope.launch(Dispatchers.IO) {
                streamLines("/memory") { line ->
                    val obj = runCatching { json.decodeFromString<JsonObject>(line) }.getOrNull()
                        ?: return@streamLines
                    obj.long("inuse")?.let { memory.set(it) }
                }
            },
        )

        // 1 Hz coalescing cadence — identical to the Windows client. While
        // the app is backgrounded the gate skips UI emissions (issue #26):
        // the atomics keep updating so resume is instantaneous.
        while (isActive) {
            if (gate.shouldEmit()) {
                traffic.emit(TrafficTick(up = up.get(), down = down.get(), memory = memory.get()))
                delay(1_000)
            } else {
                delay(BACKGROUND_DELAY_MS)
            }
        }
        readers.forEach { it.cancel() }
    }

    /** Consumes /logs; batches at 0.5 Hz into [logs] + the ring. */
    suspend fun runLogLoop(scope: CoroutineScope) = withContext(Dispatchers.IO) {
        val batch = ArrayDeque<String>()
        val lock = Any()

        val reader = scope.launch(Dispatchers.IO) {
            streamLines("/logs") { line ->
                val obj = runCatching { json.decodeFromString<JsonObject>(line) }.getOrNull()
                    ?: return@streamLines
                val payload = obj.str("payload") ?: return@streamLines
                val level = obj.str("type") ?: "info"
                val formatted = "[$level] $payload"
                // Lock-guarded batch (Windows #69 regression: the batch is
                // written by the stream goroutine and drained by the flusher —
                // shared state is always synchronized).
                synchronized(lock) {
                    batch.addLast(formatted)
                    if (batch.size > MAX_PENDING) batch.removeFirst()
                }
                logRing.add(formatted)
            }
        }

        while (isActive) {
            delay(FLUSH_MS)
            val chunk = synchronized(lock) {
                if (batch.isEmpty()) null else {
                    val copy = batch.toList()
                    batch.clear()
                    copy
                }
            }
            if (chunk != null) {
                // Ring already updated by the reader; the UI emit is gated
                // while backgrounded (issue #26).
                if (gate.shouldEmit()) logs.emit(chunk)
            }
        }
        reader.cancel()
    }

    /** Shared line-reading loop: chunked, per-line callbacks, no buffering. */
    private suspend fun streamLines(path: String, onLine: (String) -> Unit) {
        while (true) {
            try {
                streamClient.newCall(streamRequest(path)).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        delay(RETRY_MS)
                        return
                    }
                    val source = resp.body.source()
                    while (true) {
                        val line = source.readUtf8Line() ?: return
                        if (line.isNotBlank()) onLine(line.trim())
                    }
                }
            } catch (_: IOException) {
                // Core gone / VPN stopped — retry with backoff; the owner
                // cancels this loop when the tunnel shuts down.
            }
            delay(RETRY_MS)
        }
    }

    data class TrafficTick(val up: Long, val down: Long, val memory: Long)

    companion object {
        private const val RETRY_MS = 2_000L
        private const val FLUSH_MS = 500L
        private const val MAX_PENDING = 200
        const val BACKGROUND_DELAY_MS = 5_000L
    }
}

/**
 * Bounded ring buffer for log lines — the UI reads a snapshot; writers append
 * under a lock. Capacity mirrors the Windows client (≤500).
 */
class LogRing(private val capacity: Int) {
    private val lock = Any()
    private val lines = ArrayDeque<String>()

    fun add(line: String) {
        synchronized(lock) {
            lines.addLast(line)
            while (lines.size > capacity) lines.removeFirst()
        }
    }

    fun snapshot(): List<String> = synchronized(lock) { lines.toList() }

    fun clear() = synchronized(lock) { lines.clear() }

    fun size(): Int = synchronized(lock) { lines.size }
}

/** JSON helpers shared by the stream decoders. */
internal fun JsonObject.long(key: String): Long? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.toLongOrNull()

internal fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
