package com.pulseloop.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.data.entity.MeasurementEntity
import com.pulseloop.ring.MeasurementKind
import com.pulseloop.service.RingSyncCoordinator
import kotlinx.coroutines.delay

private enum class MeasurePhase { PREPARING, MEASURING, RESULT, ERROR }

/**
 * Ported from MeasurementSheet / MeasurementModal.swift.
 * Live measurement overlay for spot HR or SpO2 readings with ring animation.
 */
@Composable
fun MeasurementModal(
    kind: MeasurementKind,
    db: PulseLoopDatabase,
    coordinator: RingSyncCoordinator?,
    onDismiss: () -> Unit,
) {
    val color = when (kind) {
        MeasurementKind.HEART_RATE -> com.pulseloop.ui.theme.MetricColors.ZoneConcern
        MeasurementKind.SPO2 -> com.pulseloop.ui.theme.MetricColors.ZoneLow
        else -> MaterialTheme.colorScheme.primary
    }
    val name = when (kind) {
        MeasurementKind.HEART_RATE -> "Heart Rate"
        MeasurementKind.SPO2 -> "Blood Oxygen"
        else -> "Measurement"
    }
    val unit = when (kind) {
        MeasurementKind.HEART_RATE -> "bpm"
        MeasurementKind.SPO2 -> "%"
        else -> ""
    }
    val instruction = when (kind) {
        MeasurementKind.HEART_RATE -> "Keep your hand still and rest your wrist on a flat surface."
        MeasurementKind.SPO2 -> "Breathe normally. Keep the sensor pressed firmly to your skin."
        else -> "Stay still during the measurement."
    }

    var phase by remember { mutableStateOf(MeasurePhase.PREPARING) }
    var value by remember { mutableStateOf<Int?>(null) }

    val infiniteTransition = rememberInfiniteTransition()
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.85f, targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
    )

    // Run measurement
    LaunchedEffect(Unit) {
        delay(1200)
        phase = MeasurePhase.MEASURING

        val result: Int? = if (coordinator != null) {
            try {
                when (kind) {
                    MeasurementKind.HEART_RATE -> coordinator.measureHR()
                    MeasurementKind.SPO2 -> coordinator.measureSpO2()
                    else -> null
                }
            } catch (_: Exception) { null }
        } else {
            delay(if (kind == MeasurementKind.HEART_RATE) 2200 else 3000)
            val mockValue = when (kind) {
                MeasurementKind.HEART_RATE -> (62..86).random()
                MeasurementKind.SPO2 -> (96..99).random()
                else -> 0
            }
            db.measurementDao().insert(MeasurementEntity(
                kindRaw = kind.name,
                value = mockValue.toDouble(),
                unit = unit,
                timestamp = System.currentTimeMillis(),
                sourceRaw = "mock",
                confidenceRaw = "known",
            ))
            mockValue
        }

        if (result != null && result > 0) {
            value = result
            phase = MeasurePhase.RESULT
            delay(1300)
            onDismiss()
        } else {
            phase = MeasurePhase.ERROR
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
            Column {
                Text("MEASURING", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            TextButton(onClick = onDismiss) {
                Text(if (phase == MeasurePhase.MEASURING) "Finish" else "Cancel")
            }
        }

        Spacer(Modifier.weight(1f))

        if (phase == MeasurePhase.ERROR) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Surface(Modifier.size(80.dp), shape = CircleShape, color = com.pulseloop.ui.theme.MetricColors.ZoneConcern.copy(alpha = 0.12f)) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("!", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, color = com.pulseloop.ui.theme.MetricColors.ZoneConcern)
                    }
                }
                Text(
                    "Couldn't get a reading. Make sure the ring is snug and worn on your finger, then try again.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = onDismiss) { Text("Close") }
            }
        } else {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(240.dp)) {
                repeat(3) { i ->
                    Canvas(Modifier.fillMaxSize()) {
                        val r = size.minDimension / 2 * 0.8f
                        drawCircle(
                            color = color.copy(alpha = 0.3f * (1f - i * 0.25f)),
                            radius = r,
                            style = Stroke(width = 2f),
                        )
                    }
                }
                Surface(Modifier.size(220.dp), shape = CircleShape, color = color.copy(alpha = 0.12f)) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            if (value != null && phase != MeasurePhase.PREPARING) {
                                Text("$value", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold)
                                Text(unit.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else {
                                Text(
                                    if (phase == MeasurePhase.PREPARING) "READY" else "MEASURING",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            Text(
                when (phase) {
                    MeasurePhase.PREPARING -> instruction
                    MeasurePhase.MEASURING -> if (kind == MeasurementKind.SPO2) "Measuring SpO₂… keep your hand still." else "Measuring… stay still."
                    MeasurePhase.RESULT -> "Reading saved."
                    MeasurePhase.ERROR -> ""
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.weight(1f))

        if (phase == MeasurePhase.RESULT) {
            Surface(
                Modifier.fillMaxWidth().padding(bottom = 28.dp).clip(RoundedCornerShape(16.dp)),
                color = com.pulseloop.ui.theme.MetricColors.ZoneGood.copy(alpha = 0.12f),
            ) {
                Text("Saved", Modifier.fillMaxWidth().padding(vertical = 14.dp),
                    style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium,
                    color = com.pulseloop.ui.theme.MetricColors.ZoneGood, textAlign = TextAlign.Center)
            }
        }
    }
}
