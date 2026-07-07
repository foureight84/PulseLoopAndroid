package com.pulseloop.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Delay before a touch that starts on the chart is claimed for scrub/zoom. Within this
 * window a drag that passes touch-slop is treated as a parent-list scroll instead, so the
 * chart can sit inside a scrollable column without trapping vertical swipes.
 */
private const val CHART_TOUCH_DELAY_MS = 160L

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

private fun formatTick(value: Double): String {
    return if (value == value.toLong().toDouble()) value.toLong().toString()
    else "%.1f".format(value)
}

/**
 * Clean clock-time ticks for the X axis across the visible window [visStart, visEnd].
 * Picks a step (15 min … 7 days) that yields ~5 labels, aligns ticks to local
 * midnight, and returns a label formatter whose granularity matches the step
 * (e.g. "2 PM" for hourly, "2:30 PM" sub-hour, "Jun 28" for multi-day).
 */
private fun timeAxisTicks(visStart: Long, visEnd: Long, zone: ZoneId): Pair<List<Long>, (Long) -> String> {
    val rangeMs = (visEnd - visStart).coerceAtLeast(1L)
    val stepsMin = longArrayOf(15, 30, 60, 120, 180, 360, 720, 1440, 2880, 10080)
    val stepMin = stepsMin.firstOrNull { rangeMs / (it * 60_000L) <= 5 } ?: stepsMin.last()
    val stepMs = stepMin * 60_000L
    val pattern = when {
        stepMin >= 1440 -> "MMM d"
        stepMin < 60 -> "h:mm a"
        else -> "h a"
    }
    val fmt = DateTimeFormatter.ofPattern(pattern, Locale.getDefault())
    // Align ticks to local midnight so labels land on clean boundaries.
    val dayStart = Instant.ofEpochMilli(visStart).atZone(zone)
        .truncatedTo(ChronoUnit.DAYS).toInstant().toEpochMilli()
    val ticks = mutableListOf<Long>()
    var t = dayStart
    while (t < visStart) t += stepMs
    while (t <= visEnd) { ticks.add(t); t += stepMs }
    val formatter: (Long) -> String = { ms -> Instant.ofEpochMilli(ms).atZone(zone).format(fmt) }
    return ticks to formatter
}

// ──────────────────────── TrendChart ────────────────────────

/**
 * Enhanced line chart for the Vital Detail screen — gradient fill under the line,
 * gridlines, value (Y) axis labels, aligned time (X) axis labels, and touch interaction:
 *   • single-finger drag → scrub: a guide line + tooltip showing the value and time
 *     at the touched point;
 *   • two-finger pinch / pan → zoom into and pan across the time domain.
 *
 * For BP, use [secondary] to overlay a second series with [colorSecondary] and show
 * [legendPrimary]/[legendSecondary] labels.
 *
 * @param timestamps optional epoch-ms per point (parallel to [points]); when present the
 *   scrub tooltip shows a precise time via [tooltipTimeFormatter].
 * @param valueFormatter formats Y-axis + tooltip values (default: integer / 1-dp).
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
    timestamps: List<Long> = emptyList(),
    valueFormatter: (Double) -> String = ::formatTick,
    tooltipTimeFormatter: ((Long) -> String)? = null,
    interactive: Boolean = true,
) {
    if (points.isEmpty()) return

    val all = points + secondary
    val min = all.min()
    val max = all.max()
    val range = if (max == min) 1.0 else max - min
    // Zone-color the primary line only for single-series metrics (not BP's dual lines).
    val zoneColoring = thresholds != null && secondary.isEmpty()
    val n = points.size

    // Time domain: when we have a timestamp per point, plot on a real time axis so the
    // x positions and labels reflect actual clock time (not even index spacing). The full
    // domain is the data's time span (e.g. the last 24h); pinch zooms into it.
    val useTime = timestamps.size == n && n >= 1
    val tMin = if (useTime) timestamps.min() else 0L
    val tMax = if (useTime) timestamps.max() else 0L
    val tFullStart = if (useTime && tMax > tMin) tMin else tMin - 1_800_000L
    val tFullEnd = if (useTime && tMax > tMin) tMax else tMax + 1_800_000L
    val fullRangeMs = (tFullEnd - tFullStart).coerceAtLeast(1L)
    val zone = ZoneId.systemDefault()

    val textMeasurer = rememberTextMeasurer()
    val axisColor = MaterialTheme.colorScheme.onSurfaceVariant
    val surfaceColor = MaterialTheme.colorScheme.surface
    val onSurfaceColor = MaterialTheme.colorScheme.onSurface
    val outlineColor = MaterialTheme.colorScheme.outline
    val gridColor = axisColor.copy(alpha = 0.15f)

    // Zoom/pan window normalized to [0,1] over the time domain. windowSize = 1/zoom.
    // Min visible window is 30 min; index fallback caps by point count.
    val minWindowMs = 30 * 60 * 1000L
    val maxZoom = if (useTime) (fullRangeMs.toFloat() / minWindowMs).coerceIn(1f, 24f)
        else if (n > 2) minOf(8f, n / 2f) else 1f
    var zoom by remember(points) { mutableStateOf(1f) }
    var windowStart by remember(points) { mutableStateOf(0f) }
    // Scrub touch x in px; null = no tooltip. Plot bounds shared with the gesture handler.
    var scrubX by remember(points) { mutableStateOf<Float?>(null) }
    var plotLeftPx by remember { mutableStateOf(0f) }
    var plotRightPx by remember { mutableStateOf(0f) }

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

        val canvasModifier = Modifier.fillMaxWidth().height(220.dp).let { base ->
            if (!interactive) base else base.pointerInput(points) {
                awaitEachGesture {
                    val first = awaitFirstDown(requireUnconsumed = false)
                    // Disambiguate scroll vs. scrub: wait briefly before claiming the gesture.
                    // A second finger claims immediately (zoom); a drag past touch-slop or a
                    // quick lift yields to the parent list; staying put past the delay scrubs.
                    val slop = viewConfiguration.touchSlop
                    var claimed = false
                    withTimeoutOrNull(CHART_TOUCH_DELAY_MS) {
                        while (true) {
                            val ev = awaitPointerEvent()
                            if (ev.changes.count { it.pressed } >= 2) { claimed = true; return@withTimeoutOrNull }
                            val ch = ev.changes.firstOrNull { it.id == first.id }
                            if (ch == null || !ch.pressed) return@withTimeoutOrNull
                            if ((ch.position - first.position).getDistanceSquared() > slop * slop) return@withTimeoutOrNull
                        }
                    } ?: run { claimed = true }   // timed out with the finger held still → scrub
                    if (!claimed) return@awaitEachGesture
                    scrubX = first.position.x
                    var multiTouch = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressedCount = event.changes.count { it.pressed }
                        if (pressedCount == 0) break
                        if (pressedCount >= 2 && maxZoom > 1f) {
                            // Pinch-zoom + pan, focal-stable around the gesture centroid.
                            multiTouch = true
                            scrubX = null
                            val plotW = (plotRightPx - plotLeftPx).coerceAtLeast(1f)
                            val oldWindow = 1f / zoom
                            val focal = ((event.calculateCentroid().x - plotLeftPx) / plotW).coerceIn(0f, 1f)
                            val focalData = windowStart + focal * oldWindow
                            val newZoom = (zoom * event.calculateZoom()).coerceIn(1f, maxZoom)
                            val newWindow = 1f / newZoom
                            var newStart = focalData - focal * newWindow
                            newStart -= (event.calculatePan().x / plotW) * newWindow
                            windowStart = newStart.coerceIn(0f, (1f - newWindow).coerceAtLeast(0f))
                            zoom = newZoom
                            event.changes.forEach { it.consume() }
                        } else if (pressedCount == 1) {
                            val change = event.changes.first { it.pressed }
                            // First single-finger frame right after a pinch: clear the flag and
                            // skip one frame so the tooltip doesn't jump on release; subsequent
                            // moves scrub normally, so you can pinch then drag without lifting.
                            if (multiTouch) multiTouch = false
                            else scrubX = change.position.x
                            change.consume()
                        }
                    }
                    scrubX = null  // hide tooltip when the finger lifts
                }
            }
        }

        Canvas(modifier = canvasModifier) {
            // iOS #35 look: value labels sit trailing (right), so the gutter moves there.
            val gutter = 40.dp.toPx()
            val leftPad = 12.dp.toPx()
            val topPad = 10.dp.toPx()
            val bottomPad = 22.dp.toPx()
            val w = size.width
            val h = size.height
            val plotLeft = leftPad
            val plotRight = w - gutter
            val plotTop = topPad
            val plotBottom = h - bottomPad
            val plotW = (plotRight - plotLeft).coerceAtLeast(1f)
            val plotH = (plotBottom - plotTop).coerceAtLeast(1f)
            plotLeftPx = plotLeft
            plotRightPx = plotRight

            val windowSize = 1f / zoom
            val winStart = windowStart.coerceIn(0f, (1f - windowSize).coerceAtLeast(0f))

            // Visible time window (time domain only).
            val visStart = tFullStart + (winStart.toDouble() * fullRangeMs).toLong()
            val visEnd = tFullStart + ((winStart + windowSize).toDouble() * fullRangeMs).toLong()
            val visRangeMs = (visEnd - visStart).coerceAtLeast(1L)

            fun xForTime(t: Long): Float =
                plotLeft + ((t - visStart).toFloat() / visRangeMs.toFloat()) * plotW
            fun xForFrac(p: Float): Float = plotLeft + ((p - winStart) / windowSize) * plotW
            fun xForIndex(i: Int): Float =
                if (useTime) xForTime(timestamps[i])
                else xForFrac(if (n <= 1) 0f else i.toFloat() / (n - 1))
            fun xForSecondary(j: Int): Float =
                if (useTime && secondary.size == timestamps.size) xForTime(timestamps[j])
                else xForFrac(if (secondary.size <= 1) 0f else j.toFloat() / (secondary.size - 1))
            fun yForValue(v: Double): Float =
                plotTop + plotH * (1f - ((v - min) / range).toFloat())
            fun indexForX(x: Float): Int {
                if (!useTime) {
                    val frac = winStart + ((x - plotLeft) / plotW).coerceIn(0f, 1f) * windowSize
                    return (frac * (n - 1)).roundToInt().coerceIn(0, n - 1)
                }
                val t = visStart + (((x - plotLeft) / plotW).coerceIn(0f, 1f) * visRangeMs).toLong()
                var best = 0; var bestDist = Long.MAX_VALUE
                timestamps.forEachIndexed { i, ts ->
                    val d = abs(ts - t)
                    if (d < bestDist) { bestDist = d; best = i }
                }
                return best
            }

            val labelStyle = TextStyle(fontSize = 10.sp, color = axisColor)

            // No zone-band background: the iOS detail chart plots on a clean card, with the
            // zone-split line + the REFERENCE ZONES card carrying the range context.

            // Quiet axes (iOS #35): ≤3 faint horizontal gridlines with trailing value labels.
            val gridCount = 3
            for (g in 0..gridCount) {
                val y = plotTop + plotH * g / gridCount
                drawLine(gridColor, Offset(plotLeft, y), Offset(plotRight, y), 1f)
                val value = max - (max - min) * g / gridCount
                val tl = textMeasurer.measure(valueFormatter(value), labelStyle)
                drawText(tl, topLeft = Offset(plotRight + 6.dp.toPx(), y - tl.size.height / 2f))
            }

            // Series — clipped to the plot rect so zoomed-out points don't bleed over the axes.
            // The primary line is drawn zone-split and gap-broken (iOS #35): each sample pair
            // splits at zone boundaries so a piece never spans two zones, and runs separated
            // by more than the range-tuned max gap break instead of bridging.
            val maxGapMs = if (useTime) ZoneLineSplitter.maxGapMs(fullRangeMs) else Long.MAX_VALUE
            val primaryOffsets = points.mapIndexed { i, v -> Offset(xForIndex(i), yForValue(v)) }
            val secondaryOffsets = secondary.mapIndexed { i, v -> Offset(xForSecondary(i), yForValue(v)) }
            clipRect(plotLeft, plotTop, plotRight, plotBottom) {
                drawTrendSeries(
                    secondaryOffsets, secondary, colorSecondary, false, thresholds, min, max,
                    plotTop, plotBottom, if (useTime) timestamps else emptyList(), maxGapMs,
                )
                drawTrendSeries(
                    primaryOffsets, points, color, zoneColoring, thresholds, min, max,
                    plotTop, plotBottom, if (useTime) timestamps else emptyList(), maxGapMs,
                )
                // Current-value marker at the last primary point
                primaryOffsets.lastOrNull()?.let { o ->
                    val c = if (zoneColoring) thresholds?.zoneFor(points.last())?.color ?: color else color
                    drawCircle(Color.White, 7f, o)
                    drawCircle(c, 5f, o)
                }
            }

            // X-axis time labels — real clock times at clean intervals across the visible window.
            if (useTime) {
                val (ticks, fmt) = timeAxisTicks(visStart, visEnd, zone)
                var lastRight = -Float.MAX_VALUE
                for (t in ticks) {
                    val text = fmt(t)
                    val tl = textMeasurer.measure(text, labelStyle)
                    val cx = xForTime(t)
                    val tx = (cx - tl.size.width / 2f).coerceIn(plotLeft, plotRight - tl.size.width)
                    if (tx <= lastRight + 6.dp.toPx()) continue  // avoid overlap
                    lastRight = tx + tl.size.width
                    drawText(tl, topLeft = Offset(tx, plotBottom + 4.dp.toPx()))
                }
            } else {
                val slotCount = (plotW / 64.dp.toPx()).toInt().coerceIn(2, 6)
                var lastDrawnIdx = -1
                for (s in 0..slotCount) {
                    val frac = s.toFloat() / slotCount
                    val idx = ((winStart + frac * windowSize) * (n - 1)).roundToInt().coerceIn(0, n - 1)
                    if (idx == lastDrawnIdx) continue
                    lastDrawnIdx = idx
                    val text = labels.getOrNull(idx) ?: continue
                    if (text.isEmpty()) continue
                    val tl = textMeasurer.measure(text, labelStyle)
                    val tx = (xForIndex(idx) - tl.size.width / 2f).coerceIn(plotLeft, plotRight - tl.size.width)
                    drawText(tl, topLeft = Offset(tx, plotBottom + 4.dp.toPx()))
                }
            }

            // Scrub guide line + tooltip
            val sx = scrubX
            if (sx != null && n > 0) {
                val idx = indexForX(sx.coerceIn(plotLeft, plotRight))
                val px = xForIndex(idx).coerceIn(plotLeft, plotRight)
                val py = yForValue(points[idx])
                // Vertical guide
                drawLine(outlineColor.copy(alpha = 0.6f), Offset(px, plotTop), Offset(px, plotBottom), 1.5f)
                // Highlighted point(s)
                drawCircle(Color.White, 7f, Offset(px, py))
                drawCircle(if (zoneColoring) thresholds?.zoneFor(points[idx])?.color ?: color else color, 5f, Offset(px, py))
                val secVal = secondary.getOrNull(idx)
                if (secVal != null) {
                    val py2 = yForValue(secVal)
                    drawCircle(Color.White, 6f, Offset(px, py2))
                    drawCircle(colorSecondary, 4f, Offset(px, py2))
                }

                // Tooltip text: value (bold) + time (muted)
                val valueText = if (secVal != null)
                    "${valueFormatter(points[idx])} / ${valueFormatter(secVal)}"
                else valueFormatter(points[idx])
                val timeText = timestamps.getOrNull(idx)?.let { tooltipTimeFormatter?.invoke(it) }
                    ?: labels.getOrNull(idx) ?: ""
                val valStyle = TextStyle(fontSize = 13.sp, color = onSurfaceColor, fontWeight = FontWeight.Bold)
                val timeStyle = TextStyle(fontSize = 10.sp, color = axisColor)
                val valTl = textMeasurer.measure(valueText, valStyle)
                val timeTl = if (timeText.isNotEmpty()) textMeasurer.measure(timeText, timeStyle) else null
                val padPx = 8.dp.toPx()
                val gap = 2.dp.toPx()
                val contentW = maxOf(valTl.size.width, timeTl?.size?.width ?: 0).toFloat()
                val contentH = valTl.size.height + (timeTl?.let { it.size.height + gap } ?: 0f)
                val boxW = contentW + padPx * 2
                val boxH = contentH + padPx * 1.2f
                val boxLeft = (px - boxW / 2f).coerceIn(plotLeft, plotRight - boxW)
                val boxTop = if (py - boxH - 8.dp.toPx() >= plotTop) py - boxH - 8.dp.toPx() else py + 8.dp.toPx()
                drawRoundRect(
                    color = surfaceColor,
                    topLeft = Offset(boxLeft, boxTop),
                    size = Size(boxW, boxH),
                    cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx()),
                )
                drawRoundRect(
                    color = outlineColor.copy(alpha = 0.4f),
                    topLeft = Offset(boxLeft, boxTop),
                    size = Size(boxW, boxH),
                    cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx()),
                    style = Stroke(width = 1f),
                )
                drawText(valTl, topLeft = Offset(boxLeft + padPx, boxTop + padPx * 0.6f))
                timeTl?.let {
                    drawText(it, topLeft = Offset(boxLeft + padPx, boxTop + padPx * 0.6f + valTl.size.height + gap))
                }
            }
        }

        // Scroll slider + zoom-reset — shown only while zoomed in. The slider pans the
        // visible window across the time domain; reset returns to the full (unzoomed) view.
        if (interactive && zoom > 1f) {
            val sliderWindow = 1f / zoom
            val maxStart = (1f - sliderWindow).coerceAtLeast(0f)
            if (maxStart > 0f) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Slider(
                        value = windowStart.coerceIn(0f, maxStart),
                        onValueChange = { windowStart = it.coerceIn(0f, maxStart) },
                        valueRange = 0f..maxStart,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { zoom = 1f; windowStart = 0f }) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = "Reset zoom",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Draws one trend series in the iOS #35 style: a zone-split, gap-broken line whose pieces
 * are each colored by the single zone they lie in (no vertical-gradient approximation),
 * plus per-point dots when sparse. Non-zone series (e.g. BP's dual lines) keep a flat
 * color with a soft under-fill. Offsets are pre-projected screen coordinates; callers
 * should [clipRect] to the plot bounds.
 */
private fun DrawScope.drawTrendSeries(
    offsets: List<Offset>,
    values: List<Double>,
    seriesColor: Color,
    zoneColored: Boolean,
    thresholds: MetricThresholds?,
    dataMin: Double,
    dataMax: Double,
    plotTop: Float,
    plotBottom: Float,
    timestamps: List<Long> = emptyList(),
    maxGapMs: Long = Long.MAX_VALUE,
) {
    if (offsets.isEmpty()) return
    val lineWidth = 3f

    val runs = if (timestamps.size == offsets.size) {
        ZoneLineSplitter.segmentsByGap(timestamps, maxGapMs)
    } else listOf(offsets.indices)

    if (!(zoneColored && thresholds != null)) {
        // Flat-color series: soft under-fill + line, drawn per gap-run.
        for (run in runs) {
            val runOffsets = offsets.slice(run)
            if (runOffsets.size == 1) {
                drawCircle(seriesColor, 4f, runOffsets[0])
                continue
            }
            val fill = Path()
            runOffsets.forEachIndexed { i, o -> if (i == 0) fill.moveTo(o.x, o.y) else fill.lineTo(o.x, o.y) }
            fill.lineTo(runOffsets.last().x, plotBottom)
            fill.lineTo(runOffsets.first().x, plotBottom)
            fill.close()
            drawPath(
                fill,
                brush = Brush.verticalGradient(
                    colors = listOf(seriesColor.copy(alpha = 0.18f), seriesColor.copy(alpha = 0.02f)),
                ),
            )
            val line = Path()
            runOffsets.forEachIndexed { i, o -> if (i == 0) line.moveTo(o.x, o.y) else line.lineTo(o.x, o.y) }
            drawPath(line, seriesColor, style = Stroke(width = lineWidth, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    } else {
        // Zone-split line: each sample pair splits at every zone boundary it crosses, so a
        // drawn piece never spans two zones and its midpoint lands squarely in its zone.
        val boundaries = thresholds.zones.map { it.start }.drop(1).sorted()
        val yPerValue = if (dataMax == dataMin) 0f else (plotBottom - plotTop) / (dataMax - dataMin).toFloat()
        fun yFor(v: Double): Float = plotBottom - ((v - dataMin).toFloat() * yPerValue)
        for (run in runs) {
            if (run.last == run.first) {
                // A lone reading in its window still deserves a visible dot.
                val i = run.first
                drawCircle(thresholds.zoneFor(values[i])?.color ?: seriesColor, 4f, offsets[i])
                continue
            }
            for (i in run.first until run.last) {
                val pieces = ZoneLineSplitter.split(
                    offsets[i].x.toDouble(), values[i],
                    offsets[i + 1].x.toDouble(), values[i + 1],
                    boundaries,
                )
                for ((start, end) in pieces) {
                    val mid = (start.value + end.value) / 2
                    drawLine(
                        color = thresholds.zoneFor(mid)?.color ?: seriesColor,
                        start = Offset(start.x.toFloat(), yFor(start.value)),
                        end = Offset(end.x.toFloat(), yFor(end.value)),
                        strokeWidth = lineWidth,
                        cap = StrokeCap.Round,
                    )
                }
            }
        }
    }

    // Per-point dots only when sparse — at high density they merge into a blob and
    // just obscure the line (e.g. a month of raw samples).
    if (offsets.size <= 60) {
        offsets.forEachIndexed { i, o ->
            val dotColor = if (zoneColored) thresholds?.zoneFor(values[i])?.color ?: seriesColor else seriesColor
            drawCircle(dotColor, 3f, o)
        }
    }
}
