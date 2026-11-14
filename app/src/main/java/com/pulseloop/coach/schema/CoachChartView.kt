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
import androidx.compose.ui.res.stringResource
import com.pulseloop.R

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
    val color = chartColor(
        chart,
        heartWords = stringResource(R.string.chart_words_heart),
        heartUnits = stringResource(R.string.chart_words_heart_unit),
        oxygenWords = stringResource(R.string.chart_words_oxygen),
        sleepWords = stringResource(R.string.chart_words_sleep),
        stepWords = stringResource(R.string.chart_words_steps),
    ) ?: MaterialTheme.colorScheme.primary

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF0F141F))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            chart.title.ifEmpty { stringResource(R.string.chart_x_vs_y, chart.xLabel, chart.yLabel) },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (chart.points.isEmpty()) {
            Box(Modifier.fillMaxWidth().height(60.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.chart_no_data), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

/**
 * The series colour, picked from the chart's own title and axis label. The coach writes those in
 * the app's language, so the translation's words for each metric (comma-separated string
 * resources) are matched as well as the English ones, which the model also uses. In English the
 * resources repeat the English words, so the colours are what they always were.
 */
internal fun chartColor(
    chart: CoachChart,
    heartWords: String = "",
    heartUnits: String = "",
    oxygenWords: String = "",
    sleepWords: String = "",
    stepWords: String = "",
): Color? {
    fun words(extra: String, vararg english: String) =
        english.toList() + extra.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    fun CharSequence.hasAny(list: List<String>) = list.any { contains(it, ignoreCase = true) }
    val title = chart.title
    val y = chart.yLabel
    return when {
        title.hasAny(words(heartWords, "HR")) || y.hasAny(words(heartUnits, "bpm")) -> Color(0xFFE53935)
        title.hasAny(words(oxygenWords, "SpO2")) || y.contains("%") -> Color(0xFF1E88E5)
        title.hasAny(words(sleepWords, "sleep")) -> Color(0xFF7E57C2)
        title.hasAny(words(stepWords, "step")) -> Color(0xFF4CAF50)
        else -> null
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
