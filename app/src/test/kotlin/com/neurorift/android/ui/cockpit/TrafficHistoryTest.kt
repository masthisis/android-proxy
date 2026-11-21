package com.neurorift.android.ui.cockpit

import com.neurorift.android.engine.StreamClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure cockpit logic (issue #13): the 60-sample windowing and byte
 * formatting shared by the graph legend and the connections table.
 */
class TrafficHistoryTest {

    private fun tick(up: Long, down: Long, memory: Long = 0) =
        StreamClient.TrafficTick(up = up, down = down, memory = memory)

    @Test
    fun `window keeps the newest 60 samples`() {
        val history = TrafficHistory(capacity = 60)
        repeat(75) { i -> history.add(tick(up = i.toLong(), down = 0)) }

        assertEquals(60, history.size)
        val snapshot = history.snapshot()
        // Oldest 15 dropped; the first retained sample is #15.
        assertEquals(15L, snapshot.first().up)
        assertEquals(74L, snapshot.last().up)
    }

    @Test
    fun `max covers both directions across the window`() {
        val history = TrafficHistory()
        history.add(tick(up = 10, down = 900))
        history.add(tick(up = 1000, down = 200))
        assertEquals(1000L, history.maxBytesPerSecond())
    }

    @Test
    fun `peak of an empty window is zero`() {
        assertEquals(0L, TrafficHistory().maxBytesPerSecond())
    }

    @Test
    fun `byte formatting climbs the units ladder`() {
        assertEquals("0 B", ByteFormat.bytes(0))
        assertEquals("512 B", ByteFormat.bytes(512))
        assertEquals("1.5 KB", ByteFormat.bytes(1536))
        assertEquals("2.0 MB", ByteFormat.bytes((2 * 1024 * 1024).toLong()))
        assertTrue(ByteFormat.speed(2048).endsWith("/s"))
        assertEquals("2.0 KB/s", ByteFormat.speed(2048))
    }

    @Test
    fun `negative values clamp to zero bytes`() {
        assertEquals("0 B", ByteFormat.bytes(-1))
    }
}
