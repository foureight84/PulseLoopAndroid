package com.pulseloop.coach.schema

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pulseloop.coach.orchestration.PendingAction
import com.pulseloop.coach.orchestration.PendingActionKind

/**
 * Ported from CoachActionCardView.swift.
 * Confirm/Cancel card for a PendingAction the coach proposed but hasn't run.
 */
@Composable
fun CoachActionCardView(
    action: PendingAction,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val isDestructive = action.kind == PendingActionKind.DELETE_ACTIVITY_SESSION
    val tone = if (isDestructive) Color(0xFFE53935) else Color(0xFFFFA726)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(tone.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Warning, null, tint = tone, modifier = Modifier.size(14.dp))
            Text(
                "Confirm action",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Text(action.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.weight(1f),
            ) {
                Text("Cancel")
            }
            Button(
                onClick = onConfirm,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = tone),
            ) {
                Text(action.confirmLabel)
            }
        }
    }
}
