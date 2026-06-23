package com.pulseloop.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.pulseloop.data.entity.ActivityGpsPointEntity

/**
 * Ported from WorkoutMapView.swift.
 * Route map for a workout — a Canvas-drawn GPS polyline with start/end markers.
 * Uses pure Compose Canvas (no external Google Maps dependency).
 * Falls back to a placeholder when there are fewer than 2 points.
 */
@Composable
fun WorkoutMapView(
    points: List<ActivityGpsPointEntity>,
    modifier: Modifier = Modifier,
    height: Int = 200,
) {
    if (points.size < 2) {
        // Placeholder
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(height.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xFF1A1F2E).copy(alpha = 0.4f)),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("No route yet", style = MaterialTheme.typography.labelMedium, color = Color.White)
                Text("Move outdoors to start tracking your route.", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.6f))
            }
        }
    } else {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(height.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xFF0F141F)),
        ) {
            Canvas(Modifier.fillMaxSize().padding(8.dp)) {
                val w = size.width; val h = size.height
                val lats = points.map { it.latitude }
                val lons = points.map { it.longitude }
                val minLat = lats.min(); val maxLat = lats.max()
                val minLon = lons.min(); val maxLon = lons.max()
                val latR = if (maxLat == minLat) 0.001 else maxLat - minLat
                val lonR = if (maxLon == minLon) 0.001 else maxLon - minLon

                fun toScreen(lat: Double, lon: Double): Offset {
                    val x = kotlin.math.max(0f, ((lon - minLon) / lonR * (w - 32f) + 16f).toFloat())
                    val y = kotlin.math.max(0f, (h - ((lat - minLat) / latR * (h - 32f) + 16f)).toFloat())
                    return androidx.compose.ui.geometry.Offset(x, y)
                }

                // Draw polyline
                val path = Path()
                points.forEachIndexed { i, pt ->
                    val p = toScreen(pt.latitude, pt.longitude)
                    if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                }
                drawPath(
                    path = path,
                    brush = Brush.linearGradient(
                        listOf(Color(0xFF4FC3F7), Color(0xFF1E88E5)),
                    ),
                    style = Stroke(width = 4f, cap = StrokeCap.Round),
                )

                // Start marker
                val start = toScreen(points.first().latitude, points.first().longitude)
                drawCircle(Color(0xFF4CAF50), 6f, start)
                drawCircle(Color.White, 5f, start)

                // End marker (pulsing dot)
                val end = toScreen(points.last().latitude, points.last().longitude)
                drawCircle(Color(0xFF1E88E5), 8f, end)
                drawCircle(Color(0xFF1E88E5).copy(alpha = 0.25f), 12f, end)

                // Point count overlay
            }

            // Info overlay
            val accuracy = points.last().horizontalAccuracy
            val infoText = if (accuracy != null) "±${accuracy.toInt()}m · ${points.size} pts" else "${points.size} pts"
            Text(
                infoText,
                modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.7f),
            )
        }
    }
}
