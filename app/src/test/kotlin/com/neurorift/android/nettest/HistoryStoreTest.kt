package com.neurorift.android.nettest

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [HistoryStore] contract tests (issue #15): latest-wins speed results, the
 * 50-sample latency ring, atomic persistence across instance recreation and
 * the corrupt-file reset.
 */
class HistoryStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun done(node: String, down: Double, at: Long) = SpeedDone(node, down, 4.5, at)

    @Test
    fun `recordSpeed keeps the latest result per node`() {
        val store = HistoryStore(tmp.root)
        store.recordSpeed(done("alpha", 10.0, 1_000))
        store.recordSpeed(done("alpha", 42.5, 2_000))
        store.recordSpeed(done("beta", 7.0, 3_000))

        val speeds = store.speedHistory()
        assertEquals(2, speeds.size)
        assertEquals(42.5, speeds["alpha"]!!.downMbps, 1e-9)
        assertEquals(2_000, speeds["alpha"]!!.at)
        assertEquals(7.0, speeds["beta"]!!.downMbps, 1e-9)
    }

    @Test
    fun `latency ring is capped at 50 samples with oldest dropped`() {
        val store = HistoryStore(tmp.root)
        repeat(HistoryStore.LATENCY_RING_CAP + 10) { i ->
            store.recordLatency("alpha", 100 + i)
        }

        val ring = store.latencyHistory()["alpha"]!!
        assertEquals(HistoryStore.LATENCY_RING_CAP, ring.size)
        assertEquals(110, ring.first()) // samples 100..109 dropped
        assertEquals(159, ring.last())
    }

    @Test
    fun `latency rings are independent per node`() {
        val store = HistoryStore(tmp.root)
        store.recordLatency("alpha", 120)
        store.recordLatency("beta", 300)
        store.recordLatency("alpha", 130)

        assertEquals(listOf(120, 130), store.latencyHistory()["alpha"])
        assertEquals(listOf(300), store.latencyHistory()["beta"])
    }

    @Test
    fun `persistence survives instance recreation`() {
        val store = HistoryStore(tmp.root)
        store.recordSpeed(done("alpha", 12.3, 5_000))
        store.recordSpeed(done("beta", 88.8, 6_000))
        store.recordLatency("alpha", 150)
        store.recordLatency("alpha", 210)

        val recreated = HistoryStore(tmp.root)
        assertEquals(store.snapshot(), recreated.snapshot())
        assertEquals(12.3, recreated.speedHistory()["alpha"]!!.downMbps, 1e-9)
        assertEquals(listOf(150, 210), recreated.latencyHistory()["alpha"])
    }

    @Test
    fun `recorded history is serialized to the json file`() {
        val store = HistoryStore(tmp.root)
        store.recordSpeed(done("alpha", 12.3, 5_000))

        val file = File(tmp.root, HistoryStore.FILE_NAME)
        assertTrue(file.exists())
        val obj = Json.parseToJsonElement(file.readText()).jsonObject
        assertEquals(
            "12.3",
            obj["speedResults"]!!.jsonObject["alpha"]!!.jsonObject["downMbps"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `corrupt file resets to an empty doc and keeps working`() {
        val file = File(tmp.root, HistoryStore.FILE_NAME)
        file.writeText("{ this is not json !!!")

        val store = HistoryStore(tmp.root)
        assertTrue(store.snapshot().speedResults.isEmpty())
        assertTrue(store.snapshot().latencySamples.isEmpty())

        // The store stays usable and rewrites the file atomically.
        store.recordLatency("alpha", 99)
        store.recordSpeed(done("alpha", 5.0, 7_000))
        assertEquals(5.0, store.speedHistory()["alpha"]!!.downMbps, 1e-9)

        val recreated = HistoryStore(tmp.root)
        assertEquals(store.snapshot(), recreated.snapshot())
    }

    @Test
    fun `no tmp file survives a successful persist`() {
        val store = HistoryStore(tmp.root)
        store.recordSpeed(done("alpha", 12.3, 5_000))

        assertFalse(File(tmp.root, HistoryStore.FILE_NAME + ".tmp").exists())
        assertTrue(File(tmp.root, HistoryStore.FILE_NAME).exists())
    }

    @Test
    fun `empty store yields empty snapshot`() {
        val store = HistoryStore(tmp.root)
        assertEquals(HistoryStore.HistoryDoc(), store.snapshot())
    }
}
