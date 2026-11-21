package com.neurorift.android.ui.cockpit

import com.neurorift.android.engine.StreamClient

/**
 * Windowed ring of 1 Hz traffic samples for the cockpit graph (issue #13):
 * capped at [capacity] points (60 = one minute), oldest dropped. Pure class
 * so the windowing is JVM-testable.
 */
class TrafficHistory(private val capacity: Int = 60) {
    private val points = ArrayDeque<StreamClient.TrafficTick>()

    val size: Int get() = points.size

    fun add(tick: StreamClient.TrafficTick) {
        points.addLast(tick)
        while (points.size > capacity) points.removeFirst()
    }

    fun snapshot(): List<StreamClient.TrafficTick> = points.toList()

    /** Peak of either direction across the window (graph normalization). */
    fun maxBytesPerSecond(): Long =
        if (points.isEmpty()) 0L else points.maxOf { maxOf(it.up, it.down) }

    fun clear() = points.clear()
}

/**
 * Human byte formatting shared by the graph legend and the connections
 * table. Pure, locale-stable (dot decimal separator).
 */
object ByteFormat {
    fun speed(bytesPerSecond: Long): String = bytes(bytesPerSecond) + "/s"

    fun bytes(value: Long): String {
        if (value < 0) return "0 B"
        val units = listOf("B", "KB", "MB", "GB", "TB")
        var v = value.toDouble()
        var unit = 0
        while (v >= 1024.0 && unit < units.lastIndex) {
            v /= 1024.0
            unit++
        }
        return if (unit == 0) "${v.toInt()} ${units[unit]}" else String.format("%.1f %s", v, units[unit])
    }
}
