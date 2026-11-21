package com.neurorift.android.nettest

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * File-backed Test Center history (issue #15) — the Android port of the
 * Windows test history store:
 *
 * - [HistoryDoc.speedResults]: latest [SpeedDone] per node (latest-wins),
 * - [HistoryDoc.latencySamples]: per-node delay ring capped at
 *   [LATENCY_RING_CAP] samples (oldest dropped).
 *
 * Persisted as JSON at `<dataDir>/nettest-history.json` with the same
 * atomic tmp+rename write as the profile registry; a corrupt document
 * resets to empty instead of crashing the screen. All access is
 * synchronized — record calls come from service coroutines and the UI.
 */
class HistoryStore(dataDir: File) {

    @Serializable
    data class HistoryDoc(
        val speedResults: Map<String, SpeedDone> = emptyMap(),
        val latencySamples: Map<String, List<Int>> = emptyMap(),
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val file = File(dataDir, FILE_NAME)

    @Volatile
    private var doc: HistoryDoc = HistoryDoc()

    init {
        load()
    }

    // ---------------------------------------------------------------- load --

    private fun load() {
        if (!file.exists()) return
        val decoded = runCatching { json.decodeFromString<HistoryDoc>(file.readText()) }.getOrNull()
        if (decoded != null) {
            doc = decoded
        } else {
            // Corrupt file → reset to empty and rewrite it clean.
            doc = HistoryDoc()
            runCatching { persist() }
        }
    }

    // --------------------------------------------------------------- record --

    /** Stores the latest speed result for the node (latest-wins). */
    @Synchronized
    fun recordSpeed(done: SpeedDone) {
        doc = doc.copy(speedResults = doc.speedResults + (done.node to done))
        runCatching { persist() }
    }

    /** Appends one latency sample to the node's ring (capped, oldest dropped). */
    @Synchronized
    fun recordLatency(node: String, delayMs: Int) {
        val ring = (doc.latencySamples[node] ?: emptyList()) + delayMs
        val capped = if (ring.size > LATENCY_RING_CAP) ring.takeLast(LATENCY_RING_CAP) else ring
        doc = doc.copy(latencySamples = doc.latencySamples + (node to capped))
        runCatching { persist() }
    }

    // -------------------------------------------------------------- queries --

    @Synchronized
    fun snapshot(): HistoryDoc = doc

    @Synchronized
    fun speedHistory(): Map<String, SpeedDone> = doc.speedResults

    @Synchronized
    fun latencyHistory(): Map<String, List<Int>> = doc.latencySamples

    // ------------------------------------------------------------- persist --

    private fun persist() {
        file.parentFile?.mkdirs()
        // Atomic write: temp file + rename (ProfileManager.persist parity).
        val tmp = File(file.parentFile, FILE_NAME + ".tmp")
        tmp.writeText(json.encodeToString(HistoryDoc.serializer(), doc))
        if (!tmp.renameTo(file)) {
            file.writeText(tmp.readText())
            tmp.delete()
        }
    }

    companion object {
        const val FILE_NAME = "nettest-history.json"

        /** Delay samples kept per node for the sparkline (Windows parity). */
        const val LATENCY_RING_CAP = 50
    }
}
