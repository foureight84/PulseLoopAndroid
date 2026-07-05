package com.pulseloop.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * One ring's inputs. `value == null` means the metric is unavailable → track only (no arc).
 * Ported from `ActivityRing` in DesignSystem/Components.swift.
 */
data class ActivityRing(
    val value: Double?,
    val goal: Double,
    val color: Color,
) {
    /** Clamped 0…1 progress; safe against null value and zero/negative goal. */
    val progress: Double
        get() {
            val v = value ?: return 0.0
            if (goal <= 0) return 0.0
            return (v / goal).coerceIn(0.0, 1.0)
        }
}

/**
 * Apple-Fitness-style concentric progress rings, ported from `ActivityRingsView` in
 * DesignSystem/Components.swift. Outer→inner in the order passed in. Each ring draws a muted
 * background track plus a rounded-cap progress arc starting at 12 o'clock, moving clockwise,
 * visually capped at a full circle (the numeric value elsewhere still shows real over-100% totals).
 */
@Composable
fun ActivityRings(
    /** Outer ring first. Typically [steps, distance, calories]. */
    rings: List<ActivityRing>,
    modifier: Modifier = Modifier,
    size: Dp = 116.dp,
    strokeWidth: Dp = 10.dp,
    /** Gap between concentric rings. */
    ringSpacing: Dp = 5.dp,
    trackColor: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f),
) {
    Canvas(modifier.size(size)) {
        val strokePx = strokeWidth.toPx()
        val spacingPx = ringSpacing.toPx()
        val stroke = Stroke(width = strokePx, cap = StrokeCap.Round)

        rings.forEachIndexed { index, ring ->
            // Each inner ring shrinks by one stroke + gap on every side, matching the iOS inset.
            val inset = index * (strokePx + spacingPx)
            // Inset by half the stroke so the stroke stays inside this ring's bounds.
            val diameter = this.size.minDimension - inset * 2 - strokePx
            if (diameter <= 0) return@forEachIndexed
            val topLeft = Offset(
                (this.size.width - diameter) / 2,
                (this.size.height - diameter) / 2,
            )
            val arcSize = Size(diameter, diameter)

            // Muted full-circle track.
            drawArc(
                color = trackColor,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = stroke,
            )
            // Progress arc from 12 o'clock, clockwise.
            val sweep = (360.0 * ring.progress).toFloat()
            if (sweep > 0f) {
                drawArc(
                    color = ring.color,
                    startAngle = -90f,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = stroke,
                )
            }
        }
    }
}
