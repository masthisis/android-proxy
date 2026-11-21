package com.neurorift.android.ui.cockpit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.neurorift.android.R
import com.neurorift.android.engine.StreamClient
import com.neurorift.android.ui.theme.ProtonViolet
import com.neurorift.android.ui.theme.ProtonVioletLight

/**
 * The live traffic graph card (issue #13) — Proton-styled, 1 Hz windowed
 * area chart: down (violet) + up (light violet), peak-normalized over the
 * 60-sample window. Draw cost stays constant: one path per series.
 */
@Composable
fun TrafficCard(
    history: List<StreamClient.TrafficTick>,
    up: Long,
    down: Long,
    memory: Long,
    modifier: Modifier = Modifier,
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.cockpit_traffic_title),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = "↓ " + ByteFormat.speed(down),
                            style = MaterialTheme.typography.titleMedium,
                            color = ProtonViolet,
                        )
                        Text(
                            text = "↑ " + ByteFormat.speed(up),
                            style = MaterialTheme.typography.titleMedium,
                            color = ProtonVioletLight,
                        )
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = stringResource(R.string.cockpit_memory),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = ByteFormat.bytes(memory),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            Canvas(modifier = Modifier.fillMaxWidth().height(110.dp)) {
                if (history.size < 2) {
                    // Baseline for an empty window.
                    drawLine(
                        color = ProtonViolet.copy(alpha = 0.25f),
                        start = Offset(0f, size.height),
                        end = Offset(size.width, size.height),
                        strokeWidth = 2f,
                    )
                    return@Canvas
                }
                val peak = history.maxOf { maxOf(it.up, it.down) }.coerceAtLeast(1L)
                val stepX = size.width / (history.size - 1)

                fun seriesPath(select: (StreamClient.TrafficTick) -> Long): Path {
                    val path = Path()
                    history.forEachIndexed { i, tick ->
                        val x = i * stepX
                        val y = size.height - (select(tick).toFloat() / peak * size.height * 0.92f)
                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    return path
                }

                // Down: violet line + faint area fill.
                val downPath = seriesPath { it.down }
                val downArea = Path().apply {
                    addPath(downPath)
                    lineTo(size.width, size.height)
                    lineTo(0f, size.height)
                    close()
                }
                drawPath(downArea, ProtonViolet.copy(alpha = 0.16f))
                drawPath(downPath, ProtonViolet, style = Stroke(width = 3f))

                // Up: light violet line only.
                drawPath(seriesPath { it.up }, ProtonVioletLight, style = Stroke(width = 3f))
            }
        }
    }
}
