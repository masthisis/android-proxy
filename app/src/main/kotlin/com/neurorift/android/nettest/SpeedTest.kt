package com.neurorift.android.nettest

import com.neurorift.android.engine.EngineSettings
import com.neurorift.android.engine.EventBus
import com.neurorift.android.engine.Events
import com.neurorift.android.engine.Groups
import com.neurorift.android.engine.MihomoRestClient
import java.net.InetSocketAddress
import java.net.Proxy
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Throughput math shared by the speed-test engine and the Test Center UI.
 * Pure and JVM-testable: Mbps = bytes * 8 (bits) / seconds / 1_000_000.
 */
object SpeedMath {

    /**
     * Megabits per second for [bytes] transferred over [millis] milliseconds.
     * Zero (never negative/NaN) for a zero-byte transfer or a zero interval.
     */
    fun mbps(bytes: Long, millis: Long): Double {
        if (bytes <= 0 || millis <= 0) return 0.0
        val bitsPerSecond = bytes * 8.0 * 1_000.0 / millis
        return bitsPerSecond / 1_000_000.0
    }
}

/** One live progress tick of a running speed test (phase: download | upload). */
data class SpeedProgress(
    val node: String,
    val phase: String,
    val mbps: Double,
    val bytesDone: Long,
)

/** Final speed-test outcome for one node — also persisted by [HistoryStore]. */
@Serializable
data class SpeedDone(
    val node: String,
    val downMbps: Double,
    val upMbps: Double,
    val at: Long,
)

/** What [SpeedTest.run] returns to its caller (and the VM surfaces inline). */
data class SpeedResult(
    val node: String,
    val downMbps: Double,
    val upMbps: Double,
    val secondsElapsed: Double,
)

/**
 * Throughput tester (issue #15) — the Android port of the Windows test
 * engine, driven through the hidden `⚡ NR SpeedTest` selector:
 *
 * 1. select the node into the hidden group (`PUT /proxies/<group>`) so the
 *    dedicated listener routes through it (ConfigGen wires listener
 *    `nr-speedtest` on `speedTestPort` → selector → node),
 * 2. DOWNLOAD phase: [streams] parallel GETs of the download URL THROUGH the
 *    local listener proxy, reading until the byte cap or the seconds cap,
 * 3. UPLOAD phase (opt-in): parallel POSTs of random chunks to the upload
 *    URL through the same listener until cap/seconds,
 * 4. Mbps via [SpeedMath.mbps]; progress ticks every 500 ms on [progress]
 *    and the app EventBus ([Events.SPEED_PROGRESS]); the final result lands
 *    on [results] + [Events.SPEED_DONE].
 *
 * One test at a time ([runMutex] — Windows parity); [cancel] flips a
 * volatile flag, cancels the run scope and aborts the in-flight calls, and
 * the workers re-check the flag between every chunk read.
 *
 * The URL/port fields come from [EngineSettings]; the Windows `test.*` knobs
 * (streams 1..16, byte cap, seconds cap, upload toggle) are constructor
 * params so the persisted [com.neurorift.android.settings.Settings.TestConfig]
 * can feed them without widening the engine settings surface.
 */
class SpeedTest(
    private val client: MihomoRestClient,
    private val settingsProvider: () -> EngineSettings,
    /** Parallel connections per phase — clamped to 1..16 (Windows parity). */
    private val streams: Int = DEFAULT_STREAMS,
    /** Byte cap in MB for a full run (no override). */
    private val maxMb: Int = DEFAULT_MAX_MB,
    /** Seconds cap per phase. */
    private val maxSeconds: Int = DEFAULT_MAX_SECONDS,
    /** Whether the upload phase runs after the download phase. */
    private val uploadTest: Boolean = DEFAULT_UPLOAD_TEST,
) {

    val progress = MutableSharedFlow<SpeedProgress>(extraBufferCapacity = 64)
    val results = MutableSharedFlow<SpeedDone>(extraBufferCapacity = 8)

    private val runMutex = Mutex()

    @Volatile
    private var cancelled = false

    /** Scope of the in-flight run; cancelled by [cancel], rebuilt per run. */
    private var runScope: CoroutineScope? = null

    private val inFlight = mutableListOf<Call>()

    /** True when the current/last run was aborted by [cancel]. */
    val isCancelled: Boolean get() = cancelled

    /**
     * Runs the full sequence for [node]. One test at a time — concurrent
     * callers queue on the run mutex. [maxBytesOverride] replaces the
     * settings byte cap (the UI presets); totals accumulate across streams.
     */
    suspend fun run(node: String, maxBytesOverride: Long? = null): SpeedResult = runMutex.withLock {
        cancelled = false
        val settings = settingsProvider()
        val byteCap = maxBytesOverride ?: maxMb.toLong() * BYTES_PER_MB
        val secondsCap = maxSeconds.coerceAtLeast(1)

        if (!client.selectProxy(Groups.SPEED_TEST, node)) {
            throw IllegalStateException("could not route the speed test through ${Groups.SPEED_TEST}")
        }

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        runScope = scope
        val startedAt = System.currentTimeMillis()

        val down = runPhase(scope, node, PHASE_DOWNLOAD, settings.speedTestUrl, byteCap, secondsCap, upload = false)
        val up = if (uploadTest) {
            runPhase(scope, node, PHASE_UPLOAD, settings.speedUploadUrl, byteCap, secondsCap, upload = true)
        } else {
            0L to 0L
        }
        scope.cancel()

        val result = SpeedResult(
            node = node,
            downMbps = SpeedMath.mbps(down.first, down.second),
            upMbps = SpeedMath.mbps(up.first, up.second),
            secondsElapsed = (System.currentTimeMillis() - startedAt) / 1000.0,
        )
        val done = SpeedDone(node, result.downMbps, result.upMbps, System.currentTimeMillis())
        results.tryEmit(done)
        EventBus.emit(Events.SPEED_DONE, done)
        result
    }

    /** Aborts the running test: flag + scope cancel + socket-level cancel. */
    fun cancel() {
        cancelled = true
        runScope?.cancel()
        synchronized(inFlight) { inFlight.toList() }.forEach { it.cancel() }
    }

    // --------------------------------------------------------------- phases --

    /**
     * One phase (download or upload): [streams] parallel workers sharing an
     * atomic byte counter, a 500 ms progress emitter, a deadline and the
     * shared byte cap. Returns (totalBytes, elapsedMillis) — partials are
     * kept when cancelled (the caller decides what to record).
     */
    private suspend fun runPhase(
        scope: CoroutineScope,
        node: String,
        phase: String,
        url: String,
        byteCap: Long,
        secondsCap: Int,
        upload: Boolean,
    ): Pair<Long, Long> {
        val settings = settingsProvider()
        val http = phaseHttpClient(settings.speedTestPort)
        val total = AtomicLong(0L)
        val deadlineNanos = System.nanoTime() + secondsCap * NANOS_PER_SECOND
        val startedAt = System.currentTimeMillis()

        val progressJob = scope.launch {
            while (isActive && !cancelled) {
                val tick = SpeedProgress(
                    node = node,
                    phase = phase,
                    mbps = SpeedMath.mbps(total.get(), System.currentTimeMillis() - startedAt),
                    bytesDone = total.get(),
                )
                progress.tryEmit(tick)
                EventBus.emit(Events.SPEED_PROGRESS, tick)
                delay(PROGRESS_INTERVAL_MS)
            }
        }

        val workers = (0 until streams.coerceIn(1, MAX_STREAMS)).map {
            scope.async {
                if (upload) {
                    uploadWorker(http, url, total, byteCap, deadlineNanos)
                } else {
                    downloadWorker(http, url, total, byteCap, deadlineNanos)
                }
            }
        }
        try {
            workers.awaitAll()
        } catch (e: CancellationException) {
            // cancel() killed the scope — keep the partial totals below.
        }
        progressJob.cancel()
        return total.get() to (System.currentTimeMillis() - startedAt)
    }

    /**
     * Streams the download URL through the local listener proxy, counting
     * bytes across all streams until the cap, the deadline or a cancelled
     * flag checked between every chunk read.
     */
    private fun downloadWorker(http: OkHttpClient, url: String, total: AtomicLong, byteCap: Long, deadlineNanos: Long) {
        val request = Request.Builder().url(url).build()
        val call = http.newCall(request)
        register(call)
        try {
            call.execute().use { resp ->
                val input = resp.body?.byteStream() ?: return
                val buffer = ByteArray(CHUNK_BYTES)
                while (!cancelled && System.nanoTime() < deadlineNanos && total.get() < byteCap) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total.addAndGet(read.toLong())
                }
            }
        } catch (e: Exception) {
            // Cancelled call or transport error — the totals so far stand.
        } finally {
            deregister(call)
        }
    }

    /**
     * Posts random chunks to the upload URL through the same listener proxy
     * until cap/deadline/cancel; bytes count once the request completes.
     */
    private fun uploadWorker(http: OkHttpClient, url: String, total: AtomicLong, byteCap: Long, deadlineNanos: Long) {
        val random = java.security.SecureRandom()
        val chunk = ByteArray(UPLOAD_CHUNK_BYTES)
        while (!cancelled && System.nanoTime() < deadlineNanos && total.get() < byteCap) {
            random.nextBytes(chunk)
            val request = Request.Builder()
                .url(url)
                .post(chunk.toRequestBody(null))
                .build()
            val call = http.newCall(request)
            register(call)
            try {
                call.execute().use { resp -> resp.body?.bytes() }
                total.addAndGet(chunk.size.toLong())
            } catch (e: Exception) {
                // Cancelled call or transport error — stop this worker.
                break
            } finally {
                deregister(call)
            }
        }
    }

    private fun phaseHttpClient(port: Int): OkHttpClient = OkHttpClient.Builder()
        .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(LOOPBACK, port)))
        .connectTimeout(Duration.ofSeconds(10))
        .readTimeout(Duration.ofSeconds(10))
        .writeTimeout(Duration.ofSeconds(10))
        .build()

    private fun register(call: Call) {
        synchronized(inFlight) { inFlight.add(call) }
    }

    private fun deregister(call: Call) {
        synchronized(inFlight) { inFlight.remove(call) }
    }

    companion object {
        const val PHASE_DOWNLOAD = "download"
        const val PHASE_UPLOAD = "upload"

        /** Windows `test.speedStreams` default, clamped to 1..16. */
        const val DEFAULT_STREAMS = 4
        const val DEFAULT_MAX_MB = 100
        const val DEFAULT_MAX_SECONDS = 15
        const val DEFAULT_UPLOAD_TEST = true

        const val MAX_STREAMS = 16
        const val BYTES_PER_MB = 1_000_000L
        const val CHUNK_BYTES = 64 * 1024
        const val UPLOAD_CHUNK_BYTES = 1024 * 1024
        const val PROGRESS_INTERVAL_MS = 500L
        const val NANOS_PER_SECOND = 1_000_000_000L
        const val LOOPBACK = "127.0.0.1"
    }
}
