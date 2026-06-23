package com.pulseloop.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.dp

/**
 * Ported from RingArtView.swift.
 * A stylized, asset-free rendering of a smart ring for the pairing carousel:
 * a thick gradient band (torus) with a soft highlight and inner shadow.
 */
@Composable
fun RingArtView(
    tint: Color,
    modifier: Modifier = Modifier,
    size: Float = 180f,
) {
    val band = size * 0.16f

    Box(
        modifier = modifier.size(size.dp),
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        // Soft glow behind the ring
        Box(
            modifier = Modifier
                .size((size * 0.95f).dp)
                .clip(CircleShape)
                .background(tint.copy(alpha = 0.18f))
                .blur((size * 0.12f).dp),
        )

        // Main band with gradient
        Canvas(Modifier.size(size.dp)) {
            // Outer band
            drawCircle(
                brush = Brush.sweepGradient(
                    listOf(
                        tint.copy(alpha = 0.55f),
                        tint,
                        Color.White.copy(alpha = 0.85f),
                        tint,
                        tint.copy(alpha = 0.55f),
                    ),
                ),
                radius = size / 2,
                style = Stroke(width = band),
            )

            // Inner edge shadow for depth
            drawCircle(
                color = Color.Black.copy(alpha = 0.35f),
                radius = (size - band * 0.9f) / 2,
                style = Stroke(width = band * 0.18f),
            )
        }

        // Top highlight sweep
        Canvas(Modifier.size(size.dp)) {
            val highlightSize = (size - band * 0.4f) / 2
            drawCircle(
                color = Color.White.copy(alpha = 0.7f),
                radius = highlightSize,
                style = Stroke(width = band * 0.3f, cap = StrokeCap.Round),
                // Draw only a portion (top arc)
            )
        }
    }
}
