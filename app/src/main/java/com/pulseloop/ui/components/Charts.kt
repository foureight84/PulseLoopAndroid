package com.pulseloop.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Builds hard-edged vertical-gradient color stops from a metric's threshold zones,
 * mapped into the chart's visible `[dataMin, dataMax]` value range.
 *
 * Offsets are for `Brush.verticalGradient`: 0f = top of the plot (max value),
 * 1f = bottom (min value). Hard edges come from duplicating the offset at each
 * in-range zone boundary (lower-zone color then upper-zone color at the same fraction).
 */
fun zoneGradientStops(dataMin: Double, dataMax: Double, th: MetricThresholds): Array<Pair<Float, Color>> {
    val range = dataMax - dataMin
    if (range <= 1e-6) {
        val c = th.zoneFor(dataMin)?.color ?: Color.Gray
        return arrayOf(0f to c, 1f to c)
    }
    // Boundaries strictly inside the visible range, high → low (top → bottom).
    val boundaries = th.zones
        .flatMap { listOf(it.start, it.end) }
        .distinct()
        .filter { it > dataMin + 1e-6 && it < dataMax - 1e-6 }
        .sortedDescending()

    fun colorAt(v: Double): Color = th.zoneFor(v)?.color ?: Color.Gray
    val eps = range * 1e-4

    val stops = mutableListOf<Pair<Float, Color>>()
    stops.add(0f to colorAt(dataMax - eps))            // top = max value
    for (b in boundaries) {
        val f = ((dataMax - b) / range).toFloat().coerceIn(0f, 1f)
        stops.add(f to colorAt(b + eps))               // just above boundary (upper zone)
        stops.add(f to colorAt(b - eps))               // just below boundary (lower zone) → hard edge
    }
    stops.add(1f to colorAt(dataMin + eps))            // bottom = min value
    return stops.toTypedArray()
}

/**
 * Simple line chart composable — draws a polyline from data points.
 * No external charting library needed.
 *
 * When [thresholds] is provided, the line + dots are colored by each value's zone
 * (matching the metric's threshold legend) instead of the flat [color].
 */
@Composable
fun SimpleLineChart(
    points: List<Double>,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    lineWidth: Float = 2f,
    showDots: Boolean = true,
    thresholds: MetricThresholds? = null,
) {
    if (points.isEmpty()) return
    val min = points.min()
    val max = points.max()
    val range = if (max == min) 1.0 else max - min

    Canvas(modifier = modifier.fillMaxWidth().height(100.dp)) {
        val w = size.width
        val h = size.height
        val pad = 8f
        val stepX = (w - pad * 2) / maxOf(1, points.size - 1)
        val top = pad
        val bottom = h - pad

        val lineBrush = thresholds?.let {
            Brush.verticalGradient(
                colorStops = zoneGradientStops(min, max, it),
                startY = top,
                endY = bottom,
            )
        }

        val path = Path()
        points.forEachIndexed { i, value ->
            val x = pad + i * stepX
            val y = pad + (h - pad * 2) * (1f - ((value - min) / range).toFloat())
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)

            if (showDots) {
                val dotColor = thresholds?.zoneFor(value)?.color ?: color
                drawCircle(dotColor, 3f, Offset(x, y))
            }
        }
        if (lineBrush != null) {
            drawPath(path, brush = lineBrush, style = Stroke(width = lineWidth, cap = StrokeCap.Round))
        } else {
            drawPath(path, color, style = Stroke(width = lineWidth, cap = StrokeCap.Round))
        }
    }
}

/**
 * Dual-series line chart — draws two polylines on a shared Y-scale so paired
 * series (e.g. systolic over diastolic blood pressure) stay vertically aligned.
 */
@Composable
fun SimpleDualLineChart(
    seriesA: List<Double>,
    seriesB: List<Double>,
    colorA: Color,
    colorB: Color,
    modifier: Modifier = Modifier,
    lineWidth: Float = 2f,
    showDots: Boolean = true,
) {
    if (seriesA.isEmpty() && seriesB.isEmpty()) return
    // Shared scale across both series so the two lines are comparable.
    val all = seriesA + seriesB
    val min = all.min()
    val max = all.max()
    val range = if (max == min) 1.0 else max - min

    Canvas(modifier = modifier.fillMaxWidth().height(100.dp)) {
        val w = size.width
        val h = size.height
        val pad = 8f

        fun drawSeries(points: List<Double>, color: Color) {
            if (points.isEmpty()) return
            val stepX = (w - pad * 2) / maxOf(1, points.size - 1)
            val path = Path()
            points.forEachIndexed { i, value ->
                val x = pad + i * stepX
                val y = pad + (h - pad * 2) * (1f - ((value - min) / range).toFloat())
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                if (showDots) drawCircle(color, 3f, Offset(x, y))
            }
            drawPath(path, color, style = Stroke(width = lineWidth, cap = StrokeCap.Round))
        }

        drawSeries(seriesA, colorA)
        drawSeries(seriesB, colorB)
    }
}

/**
 * Metric card with embedded mini sparkline — ported from MiniSparkline in DesignSystem.
 */
@Composable
fun MetricWithSparkline(
    label: String,
    value: String,
    unit: String,
    sparkline: List<Double>,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    androidx.compose.material3.Card(modifier) {
        Column(Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            androidx.compose.foundation.layout.Spacer(Modifier.height(4.dp))
            androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.Bottom) {
                Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                androidx.compose.foundation.layout.Spacer(Modifier.width(4.dp))
                Text(unit, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 2.dp))
            }
            if (sparkline.isNotEmpty()) {
                androidx.compose.foundation.layout.Spacer(Modifier.height(8.dp))
                SimpleLineChart(points = sparkline, modifier = Modifier.fillMaxWidth(), color = color, showDots = false)
            }
        }
    }
}

/** Small color swatch + label, used as an inline chart legend (e.g. Systolic / Diastolic). */
@Composable
fun LegendDot(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(color, CircleShape))
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ──────────────────────── ThresholdBar ────────────────────────

/**
 * Color-coded horizontal bar that shows where a value sits across good → average →
 * concerning zones. Renders each zone as a proportional segment with a marker at the
 * current value.
 */
@Composable
fun ThresholdBar(
    value: Double?,
    thresholds: MetricThresholds,
    modifier: Modifier = Modifier,
    showTicks: Boolean = true,
    overrideZone: ThresholdZone? = null,
) {
    val textMeasurer = rememberTextMeasurer()
    val barHeight = 14.dp
    val tickStyle = TextStyle(fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val zone = overrideZone ?: (value?.let { thresholds.zoneFor(it) })
    val zoneLabel = zone?.label

    Column(modifier = modifier.fillMaxWidth()) {
        // The bar
        Canvas(modifier = Modifier.fillMaxWidth().height(barHeight)) {
            val w = size.width
            val h = size.height
            val totalRange = thresholds.displayMax - thresholds.displayMin
            if (totalRange <= 0) return@Canvas

            // Draw each zone segment
            thresholds.zones.forEach { z ->
                val left = ((z.start - thresholds.displayMin) / totalRange * w).toFloat()
                val right = ((z.end - thresholds.displayMin) / totalRange * w).toFloat()
                val segWidth = (right - left).coerceAtLeast(0f)
                if (segWidth > 0f) {
                    drawRoundRect(
                        color = z.color,
                        topLeft = Offset(left, 0f),
                        size = Size(segWidth, h),
                        cornerRadius = CornerRadius(h / 2f, h / 2f),
                    )
                }
            }

            // Draw the value marker — white pill with a subtle dark border so it
            // stands out against any zone color
            if (value != null) {
                val clamped = ((value - thresholds.displayMin) / totalRange).coerceIn(0.0, 1.0)
                val markerX = (clamped * w).toFloat()
                val pillW = 5.dp.toPx()
                val pillH = h + 6.dp.toPx()
                val pillTop = -3.dp.toPx()
                // Border
                drawRoundRect(
                    color = Color.Black.copy(alpha = 0.3f),
                    topLeft = Offset(markerX - pillW / 2f - 0.5f, pillTop - 0.5f),
                    size = Size(pillW + 1f, pillH + 1f),
                    cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx()),
                )
                // Fill
                drawRoundRect(
                    color = Color.White,
                    topLeft = Offset(markerX - pillW / 2f, pillTop),
                    size = Size(pillW, pillH),
                    cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx()),
                )
            }
        }

        // Tick labels
        if (showTicks) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = formatTick(thresholds.displayMin),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (zoneLabel != null) {
                    Text(
                        text = zoneLabel,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = zone?.color ?: MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = formatTick(thresholds.displayMax),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun formatTick(value: Double): String {
    return if (value == value.toLong().toDouble()) value.toLong().toString()
    else "%.1f".format(value)
}

// ──────────────────────── TrendChart ────────────────────────

/**
 * Enhanced line chart for the Vital Detail screen — adds gradient fill under the line,
 * gridlines, x-axis labels, and value-axis min/max annotations.
 *
 * For BP, use [secondary] to overlay a second series with [colorSecondary] and show
 * [legendPrimary]/[legendSecondary] labels.
 */
@Composable
fun TrendChart(
    points: List<Double>,
    labels: List<String>,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    secondary: List<Double> = emptyList(),
    colorSecondary: Color = Color(0xFFB39DDB),
    legendPrimary: String? = null,
    legendSecondary: String? = null,
    thresholds: MetricThresholds? = null,
) {
    if (points.isEmpty()) return

    val all = points + secondary
    val min = all.min()
    val max = all.max()
    val range = if (max == min) 1.0 else max - min
    // Zone-color the primary line only for single-series metrics (not BP's dual lines).
    val zoneColoring = thresholds != null && secondary.isEmpty()

    Column(modifier = modifier) {
        // Legend row (for dual-series)
        if (legendPrimary != null && legendSecondary != null) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.padding(bottom = 4.dp),
            ) {
                LegendDot(legendPrimary, color)
                LegendDot(legendSecondary, colorSecondary)
            }
        }

        Canvas(modifier = Modifier.fillMaxWidth().height(200.dp)) {
            val w = size.width
            val h = size.height
            val pad = 32f
            val topPad = 12f
            val bottomPad = 24f

            // Gridlines
            val gridCount = 4
            for (i in 0..gridCount) {
                val y = topPad + (h - topPad - bottomPad) * i / gridCount
                drawLine(
                    color = Color.LightGray.copy(alpha = 0.3f),
                    start = Offset(pad, y),
                    end = Offset(w - pad, y),
                    strokeWidth = 1f,
                )
            }

            // Value axis annotations (min/max)
            // (simplified — just show the extremes)

            fun drawSeries(series: List<Double>, seriesColor: Color, zoneColored: Boolean = false) {
                if (series.isEmpty()) return
                val stepX = (w - pad * 2) / maxOf(1, series.size - 1)
                val lineTop = topPad
                val lineBottom = h - bottomPad

                // Gradient fill under the line (kept single-color for readability)
                val fillPath = Path()
                series.forEachIndexed { i, value ->
                    val x = pad + i * stepX
                    val y = topPad + (h - topPad - bottomPad) * (1f - ((value - min) / range).toFloat())
                    if (i == 0) fillPath.moveTo(x, y) else fillPath.lineTo(x, y)
                }
                val lastX = pad + (series.size - 1) * stepX
                fillPath.lineTo(lastX, h - bottomPad)
                fillPath.lineTo(pad, h - bottomPad)
                fillPath.close()

                drawPath(
                    path = fillPath,
                    brush = Brush.verticalGradient(
                        colors = listOf(seriesColor.copy(alpha = 0.25f), seriesColor.copy(alpha = 0.02f)),
                    ),
                )

                val lineBrush = if (zoneColored && thresholds != null) {
                    Brush.verticalGradient(
                        colorStops = zoneGradientStops(min, max, thresholds),
                        startY = lineTop,
                        endY = lineBottom,
                    )
                } else null

                // The line itself
                val linePath = Path()
                series.forEachIndexed { i, value ->
                    val x = pad + i * stepX
                    val y = topPad + (h - topPad - bottomPad) * (1f - ((value - min) / range).toFloat())
                    if (i == 0) linePath.moveTo(x, y) else linePath.lineTo(x, y)
                    val dotColor = if (zoneColored) thresholds?.zoneFor(value)?.color ?: seriesColor else seriesColor
                    drawCircle(dotColor, 3f, Offset(x, y))
                }
                if (lineBrush != null) {
                    drawPath(linePath, brush = lineBrush, style = Stroke(width = 2f, cap = StrokeCap.Round))
                } else {
                    drawPath(linePath, seriesColor, style = Stroke(width = 2f, cap = StrokeCap.Round))
                }

                // Current-value marker: larger highlighted dot at the last point
                if (series.size >= 2) {
                    val lastIdx = series.lastIndex
                    val lastX = pad + lastIdx * stepX
                    val lastY = topPad + (h - topPad - bottomPad) * (1f - ((series[lastIdx] - min) / range).toFloat())
                    val lastColor = if (zoneColored) thresholds?.zoneFor(series[lastIdx])?.color ?: seriesColor else seriesColor
                    // White ring
                    drawCircle(Color.White, 7f, Offset(lastX, lastY))
                    // Colored inner dot
                    drawCircle(lastColor, 5f, Offset(lastX, lastY))
                }
            }

            drawSeries(secondary, colorSecondary)
            drawSeries(points, color, zoneColored = zoneColoring)
        }

        // X-axis labels
        if (labels.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                // Show a subset of labels to avoid crowding
                val labelInterval = maxOf(1, labels.size / 5)
                labels.forEachIndexed { i, label ->
                    if (i % labelInterval == 0 || i == labels.lastIndex) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    } else {
                        Spacer(Modifier.width(1.dp))
                    }
                }
            }
        }
    }
}
