package com.pulseloop.coach.schema

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulseloop.ui.components.SimpleLineChart

/**
 * Ported from CoachResponseView.swift.
 * Renders a decoded CoachResponse as the assistant bubble's content:
 * title, summary, bullets, embedded chart, safety notes, sources, follow-up chips.
 */
@Composable
fun CoachResponseView(
    response: CoachResponse,
    onChipTap: ((String) -> Unit)? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Title
        if (response.title.isNotEmpty()) {
            Text(
                text = response.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        // Summary
        if (response.summary.isNotEmpty()) {
            Text(
                text = response.summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        // Bullets
        if (response.bullets.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                response.bullets.forEach { bullet ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
                        Text("•", color = MaterialTheme.colorScheme.primary)
                        Text(bullet, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        // Chart (rendered in CoachChartView)
        if (response.chart != null) {
            CoachChartView(chart = response.chart!!)
        }

        // Safety note
        response.safetyNote?.takeIf { it.isNotEmpty() }?.let { note ->
            NoteRow(icon = { Icon(Icons.Filled.Warning, null, tint = Color(0xFFFFA726)) }, text = note, tone = Color(0xFFFFA726))
        }

        // Data quality note
        response.dataQualityNote?.takeIf { it.isNotEmpty() }?.let { note ->
            NoteRow(icon = { Icon(Icons.Filled.Info, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }, text = note, tone = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        // Sources
        if (response.sources.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    "SOURCES",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                response.sources.forEach { source ->
                    Text(
                        "${source.title} — ${source.publisher}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        // Follow-up chips
        if (response.followUpChips.isNotEmpty()) {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                response.followUpChips.forEach { chip ->
                    SuggestionChip(
                        onClick = { onChipTap?.invoke(chip) },
                        label = { Text(chip, fontSize = 12.sp) },
                    )
                }
            }
        }
    }
}

@Composable
private fun NoteRow(
    icon: @Composable () -> Unit,
    text: String,
    tone: Color,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(tone.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        icon()
        Text(text, style = MaterialTheme.typography.bodySmall, color = tone)
    }
}
