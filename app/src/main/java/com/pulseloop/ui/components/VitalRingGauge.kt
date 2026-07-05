package com.pulseloop.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulseloop.service.MetricZone
import kotlin.math.cos
import kotlin.math.sin

// Custom gauge for vitals, ported from DesignSystem/VitalRingGauge.swift: a 270° open-bottom arc
// (gap at the bottom) with the metric's zones drawn as colored arc segments, a value arc, and a
// marker centered on the stroke. Built on Canvas rather than a library gauge because the multi-zone
// track needs bespoke rendering. Colors resolve through `VitalColorToken`/zones so a gauge matches
// its chart and legend exactly.

/**
 * Shared gauge geometry: a 270° sweep starting bottom-left, leaving a 90° gap centered at the
 * bottom. `0°` is at 3 o'clock and angles increase clockwise (Canvas convention).
 */
private object GaugeGeometry {
    /** Start at 135° (bottom-left); sweep 270° clockwise to 405° (bottom-right). */
    const val START_ANGLE = 135f
    const val SWEEP = 270f

    /** The on-screen angle (degrees) for a 0…1 fraction along the arc. */
    fun angle(fraction: Double): Float = START_ANGLE + SWEEP * fraction.coerceIn(0.0, 1.0).toFloat()
}

/**
 * A 270° gauge: muted zone arcs in the track, a bright value arc, a marker dot centered on the
 * stroke, and a center stack (value / unit / status). The value lives only here (the card chrome
 * does not repeat it — pass `showsValueRow = false` to [VitalCard]).
 */
@Composable
fun VitalRingGauge(
    value: Double,
    domain: ClosedFloatingPointRange<Double>,
    zones: List<MetricZone>,
    valueColor: Color,
    centerValue: String,
    modifier: Modifier = Modifier,
    centerUnit: String? = null,
    centerStatus: String? = null,
    subtitle: String? = null,
    size: Dp = 200.dp,
    lineWidth: Dp = 16.dp,
    trackColor: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f),
    markerColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    fun fraction(v: Double): Double {
        val span = domain.endInclusive - domain.start
        if (span <= 0) return 0.0
        return ((v - domain.start) / span).coerceIn(0.0, 1.0)
    }

    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            val strokePx = lineWidth.toPx()
            // Inset the arc radius by half the stroke so the stroke fits inside the frame (no
            // clipped ends), and so the marker can sit on the exact same centerline.
            val inset = strokePx / 2
            val arcTopLeft = Offset(inset, inset)
            val arcSize = Size(this.size.width - strokePx, this.size.height - strokePx)
            val radius = this.size.minDimension / 2 - inset
            val center = Offset(this.size.width / 2, this.size.height / 2)

            fun tipCenter(fraction: Double): Offset {
                val a = Math.toRadians(GaugeGeometry.angle(fraction).toDouble())
                return Offset(
                    center.x + (radius * cos(a)).toFloat(),
                    center.y + (radius * sin(a)).toFloat(),
                )
            }

            // Track (the full 270° arc), rounded so both sweep ends read as rounded under the zones.
            drawArc(
                color = trackColor,
                startAngle = GaugeGeometry.START_ANGLE,
                sweepAngle = GaugeGeometry.SWEEP,
                useCenter = false,
                topLeft = arcTopLeft,
                size = arcSize,
                style = Stroke(strokePx, cap = StrokeCap.Round),
            )

            // Muted zone arcs — every zone is butt-capped at its exact boundaries so interior joins
            // are clean straight edges (orange meets red with no rounding between them).
            for (zone in zones) {
                val lower = fraction(zone.lower ?: domain.start)
                val upper = fraction(zone.upper ?: domain.endInclusive)
                if (upper > lower) {
                    drawArc(
                        color = zone.colorToken.toColor().copy(alpha = 0.32f),
                        startAngle = GaugeGeometry.angle(lower),
                        sweepAngle = (upper - lower).toFloat() * GaugeGeometry.SWEEP,
                        useCenter = false,
                        topLeft = arcTopLeft,
                        size = arcSize,
                        style = Stroke(strokePx, cap = StrokeCap.Butt),
                    )
                }
            }

            // Rounded outer tips ONLY at the two sweep ends, so the colored track has rounded ends
            // to match the track without rounding any interior boundary. A 180° filled wedge at the
            // tip renders as a semicircular cap bulging tangentially outward past the sweep end.
            zones.firstOrNull()?.let { drawRoundTip(tipCenter(0.0), 0.0, strokePx, it.colorToken.toColor().copy(alpha = 0.32f)) }
            zones.lastOrNull()?.let { drawRoundTip(tipCenter(1.0), 1.0, strokePx, it.colorToken.toColor().copy(alpha = 0.32f)) }

            // Value arc from the start up to the current value (rounded leading tip looks intentional).
            val valueFraction = fraction(value)
            if (valueFraction > 0) {
                drawArc(
                    color = valueColor,
                    startAngle = GaugeGeometry.START_ANGLE,
                    sweepAngle = valueFraction.toFloat() * GaugeGeometry.SWEEP,
                    useCenter = false,
                    topLeft = arcTopLeft,
                    size = arcSize,
                    style = Stroke(strokePx, cap = StrokeCap.Round),
                )
            }

            // Marker on the arc stroke centerline — the SAME radius the inset arcs are drawn at, at
            // the same angle basis, so it sits dead-center on the line instead of slightly inside.
            drawCircle(
                color = markerColor,
                radius = strokePx * 0.55f / 2,
                center = tipCenter(valueFraction),
            )
        }

        // Center stack: value / unit / status / subtitle, scaled off the gauge size like iOS.
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                centerValue,
                fontSize = (size.value * 0.30f).sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (centerUnit != null) {
                Text(
                    centerUnit,
                    fontSize = (size.value * 0.08f).sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (centerStatus != null) {
                Text(
                    centerStatus.uppercase(),
                    fontSize = (size.value * 0.08f).sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.sp,
                    color = valueColor,
                )
            }
            if (subtitle != null) {
                Text(
                    subtitle,
                    fontSize = (size.value * 0.065f).sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * A rounded tip at a sweep end (fraction 0 or 1): a filled 180° wedge whose flat edge sits exactly
 * on the zone's butt end and bulges only *outward* (tangentially past the end), so it rounds the
 * end without overlapping back into the zone.
 */
private fun DrawScope.drawRoundTip(tip: Offset, fraction: Double, strokePx: Float, color: Color) {
    val endAngle = GaugeGeometry.angle(fraction)
    // Bulge direction: tangentially outward past the sweep end — before the start (fraction 0) the
    // tangent is endAngle − 90°, past the end (fraction 1) it is endAngle + 90°.
    val bulge = if (fraction >= 0.5) endAngle + 90f else endAngle - 90f
    drawArc(
        color = color,
        startAngle = bulge - 90f,
        sweepAngle = 180f,
        useCenter = true,
        topLeft = Offset(tip.x - strokePx / 2, tip.y - strokePx / 2),
        size = Size(strokePx, strokePx),
        style = Fill,
    )
}
