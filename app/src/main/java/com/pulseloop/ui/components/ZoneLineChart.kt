package com.pulseloop.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulseloop.service.MetricZone
import com.pulseloop.service.VitalSample
import com.pulseloop.ui.theme.PulseColors
import com.pulseloop.ui.viewmodels.ReferenceBand
import java.time.Instant
import java.time.ZoneId

/**
 * The iOS #35 card chart (DesignSystem ZoneLineChart): a smooth thick line whose segments are
 * colored by the zone each value falls in (split exactly at zone boundaries), over translucent
 * reference bands and dashed rule lines, with quiet right-side y labels and bottom time labels.
 * Pure rendering — zones/domain/bands arrive pre-computed in [com.pulseloop.ui.viewmodels.VitalCardState].
 *
 * This complements (does NOT replace) the interactive TrendChart on the detail screens — the
 * PR #5 scrub/zoom machinery stays untouched there.
 */
@Composable
fun ZoneLineChart(
    samples: List<VitalSample>,
    zones: List<MetricZone>,
    yDomain: ClosedFloatingPointRange<Double>,
    accent: Color,
    modifier: Modifier = Modifier,
    referenceBands: List<ReferenceBand> = emptyList(),
    dashedRules: List<Double> = emptyList(),
    showPoints: Boolean = false,
    showAxes: Boolean = true,
    height: Dp = 120.dp,
    /**
     * The gap that breaks the line, iOS `ChartSampleBuilder.maxGap(for: range)`. Card charts always
     * pass `.twentyFourHours` on iOS (90 min) regardless of the span actually plotted — that's what
     * turns sparse multi-day series (daily HRV/temp) into the scatter of isolated dots.
     */
    maxGapMs: Long = 90 * 60_000L,
) {
    if (samples.size < 2) return
    val minTs = samples.first().timestampMs
    val maxTs = samples.last().timestampMs
    val span = (maxTs - minTs).coerceAtLeast(1)
    val domainSpan = (yDomain.endInclusive - yDomain.start).coerceAtLeast(0.001)

    fun zoneColor(value: Double): Color = zones.firstOrNull {
        (it.lower ?: Double.NEGATIVE_INFINITY) <= value && value < (it.upper ?: Double.POSITIVE_INFINITY)
    }?.colorToken?.toColor() ?: accent

    // Zone boundaries inside the domain — the split points for per-segment coloring.
    val thresholds = zones.flatMap { listOfNotNull(it.lower, it.upper) }
        .filter { it > yDomain.start && it < yDomain.endInclusive }
        .distinct().sorted()

    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().height(if (showAxes) height - 16.dp else height)) {
            Canvas(Modifier.weight(1f).fillMaxHeight()) {
                fun x(ts: Long) = (ts - minTs).toFloat() / span * size.width
                fun y(v: Double) = size.height * (1f - ((v - yDomain.start) / domainSpan).toFloat().coerceIn(0f, 1f))

                // Reference bands behind everything.
                referenceBands.forEach { band ->
                    val top = y(band.upper)
                    val bottom = y(band.lower)
                    drawRect(
                        color = band.colorToken.toColor().copy(alpha = band.opacity),
                        topLeft = Offset(0f, top),
                        size = Size(size.width, (bottom - top).coerceAtLeast(0f)),
                    )
                }
                // Dashed rules.
                dashedRules.forEach { rule ->
                    val ry = y(rule)
                    drawLine(
                        color = PulseColors.textMuted.copy(alpha = 0.5f),
                        start = Offset(0f, ry), end = Offset(size.width, ry),
                        strokeWidth = 1.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())),
                    )
                }
                // Zone-colored line, broken across data gaps.
                val runs = ZoneLineSplitter.segmentsByGap(samples.map { it.timestampMs }, maxGapMs)
                runs.forEach { run ->
                    // A gap-isolated single sample would otherwise draw nothing — sparse series
                    // (daily HRV/temp, fatigue) must still show their readings.
                    if (run.first == run.last) {
                        val s = samples[run.first]
                        drawCircle(
                            color = zoneColor(s.value),
                            radius = 2.dp.toPx(),
                            center = Offset(x(s.timestampMs), y(s.value)),
                        )
                    }
                    for (i in (run.first + 1)..run.last) {
                        val a = samples[i - 1]
                        val b = samples[i]
                        ZoneLineSplitter.split(
                            a.timestampMs.toDouble(), a.value,
                            b.timestampMs.toDouble(), b.value,
                            thresholds,
                        ).forEach { (p0, p1) ->
                            val mid = (p0.value + p1.value) / 2
                            drawLine(
                                color = zoneColor(mid),
                                start = Offset(x(p0.x.toLong()), y(p0.value)),
                                end = Offset(x(p1.x.toLong()), y(p1.value)),
                                strokeWidth = 3.dp.toPx(),   // iOS StrokeStyle(lineWidth: 3)
                                cap = StrokeCap.Round,
                            )
                        }
                    }
                }
                // Sample dots (SpO₂-style scatter emphasis) — iOS PointMark symbolSize 30 ≈ 6pt Ø.
                if (showPoints) {
                    samples.forEach { s ->
                        drawCircle(
                            color = zoneColor(s.value),
                            radius = 3.dp.toPx(),
                            center = Offset(x(s.timestampMs), y(s.value)),
                        )
                    }
                }
            }
            if (showAxes) {
                Column(
                    Modifier.width(34.dp).fillMaxHeight().padding(start = 6.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                    horizontalAlignment = Alignment.End,
                ) {
                    listOf(yDomain.endInclusive, (yDomain.start + yDomain.endInclusive) / 2, yDomain.start).forEach {
                        Text("${it.toInt()}", fontSize = 9.sp, color = PulseColors.textMuted)
                    }
                }
            }
        }
        if (showAxes) {
            Row(
                Modifier.fillMaxWidth().padding(end = 34.dp, top = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                listOf(minTs, (minTs + maxTs) / 2, maxTs).forEach {
                    Text(timeLabel(it, span), fontSize = 9.sp, color = PulseColors.textMuted)
                }
            }
        }
    }
}

/** Hour-of-day labels for day-scale spans, day/month for anything longer (iOS VitalsAxisFormat). */
private fun timeLabel(ts: Long, spanMs: Long): String {
    val t = Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault())
    if (spanMs > 48 * 3600_000L) {
        return "${t.dayOfMonth} ${t.month.name.take(3).lowercase().replaceFirstChar { it.uppercase() }}"
    }
    val h = t.hour % 12
    val displayH = if (h == 0) 12 else h
    return "$displayH ${if (t.hour < 12) "AM" else "PM"}"
}
