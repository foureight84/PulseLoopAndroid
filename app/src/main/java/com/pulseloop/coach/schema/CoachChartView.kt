package com.pulseloop.coach.schema

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Ported from CoachChartView.swift.
 * Renders a CoachChart (line, bar, scatter) using Compose Canvas.
 * Data is already embedded by the prepare_chart tool.
 */
@Composable
fun CoachChartView(
    chart: CoachChart,
    modifier: Modifier = Modifier,
    height: Int = 170,
) {
    val color = when {
        chart.title.contains("HR", ignoreCase = true) || chart.yLabel.contains("bpm", ignoreCase = true) -> Color(0xFFE53935)
        chart.title.contains("SpO2", ignoreCase = true) || chart.yLabel.contains("%", ignoreCase = true) -> Color(0xFF1E88E5)
        chart.title.contains("sleep", ignoreCase = true) -> Color(0xFF7E57C2)
        chart.title.contains("step", ignoreCase = true) -> Color(0xFF4CAF50)
        else -> MaterialTheme.colorScheme.primary
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF0F141F))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            chart.title.ifEmpty { "${chart.xLabel} vs ${chart.yLabel}" },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (chart.points.isEmpty()) {
            Box(Modifier.fillMaxWidth().height(60.dp), contentAlignment = Alignment.Center) {
                Text("No data to plot for this range.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            when (chart.chartType.lowercase()) {
                "line" -> LineChartPlot(chart, color, height)
                "bar" -> BarChartPlot(chart, color, height)
                "scatter" -> ScatterPlot(chart, color, height)
                else -> LineChartPlot(chart, color, height)
            }
        }
    }
}

// ── Plot Composables ──────────────────────────────────────────────────────

@Composable
private fun LineChartPlot(chart: CoachChart, color: Color, height: Int) {
    val values = chart.points.map { it.yValue }
    Canvas(modifier = Modifier.fillMaxWidth().height(height.dp)) {
        if (values.isEmpty()) return@Canvas
        val w = size.width; val h = size.height
        val min = values.min(); val max = values.max()
        val range = if (max - min < 0.01) 1.0 else max - min
        val pad = 12f
        val stepX = if (values.size > 1) (w - pad * 2) / (values.size - 1) else 0f

        val path = Path()
        values.forEachIndexed { i, v ->
            val x = if (values.size == 1) w / 2 else pad + i * stepX
            val y = pad + (h - pad * 2) * (1f - ((v - min) / range).toFloat())
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            drawCircle(color, 3f, Offset(x, y))
        }
        drawPath(path, color, style = Stroke(width = 2f, cap = StrokeCap.Round))
    }
}

@Composable
private fun BarChartPlot(chart: CoachChart, color: Color, height: Int) {
    val values = chart.points.map { it.yValue }
    Canvas(modifier = Modifier.fillMaxWidth().height(height.dp)) {
        if (values.isEmpty()) return@Canvas
        val w = size.width; val h = size.height
        val max = values.max()
        if (max == 0.0) return@Canvas
        val pad = 8f
        val barWidth = (w - pad * 2) / values.size * 0.7f
        val gap = (w - pad * 2) / values.size * 0.3f

        values.forEachIndexed { i, v ->
            val x = pad + i * (barWidth + gap)
            val barHeight = (h - pad * 2) * (v / max).toFloat()
            drawRect(color.copy(alpha = 0.85f), Offset(x, h - pad - barHeight), Size(barWidth, barHeight))
        }
    }
}

@Composable
private fun ScatterPlot(chart: CoachChart, color: Color, height: Int) {
    val values = chart.points.map { it.yValue }
    Canvas(modifier = Modifier.fillMaxWidth().height(height.dp)) {
        if (values.isEmpty()) return@Canvas
        val w = size.width; val h = size.height
        val min = values.min(); val max = values.max()
        val range = if (max - min < 0.01) 1.0 else max - min
        val pad = 12f
        val stepX = if (values.size > 1) (w - pad * 2) / (values.size - 1) else 0f

        values.forEachIndexed { i, v ->
            val x = if (values.size == 1) w / 2 else pad + i * stepX
            val y = pad + (h - pad * 2) * (1f - ((v - min) / range).toFloat())
            drawCircle(color, 6f, Offset(x, y))
        }
    }
}
