package com.pulseloop.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulseloop.data.entity.SleepStageBlockEntity
import com.pulseloop.ui.theme.MetricColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * A hypnogram: a stepped band showing which parts of the night were awake /
 * light / deep, on a real clock axis. Deep sits lowest, awake highest, matching
 * the convention that deeper sleep is "further down".
 *
 * Stages come straight from sleep_stage_blocks. For an HR-derived estimate only
 * DEEP/LIGHT (and AWAKE when present) appear — REM isn't inferable from HR alone,
 * so the legend only shows stages actually in the data. Honest by omission.
 */
@Composable
fun SleepHypnogram(
    blocks: List<SleepStageBlockEntity>,
    modifier: Modifier = Modifier,
) {
    if (blocks.isEmpty()) return
    val sorted = blocks.sortedBy { it.startAt }
    val spanStart = sorted.first().startAt
    val spanEnd = sorted.maxOf { it.startAt + it.durationMinutes * 60_000L }
    val spanMs = (spanEnd - spanStart).coerceAtLeast(1L).toFloat()

    // Row per stage, top→bottom = lightest→deepest. Only rows present in the data.
    val order = listOf("AWAKE", "REM", "LIGHT", "DEEP")
    val present = order.filter { name -> sorted.any { it.stageRaw.equals(name, true) } }
    val rowOf = present.withIndex().associate { (i, s) -> s to i }

    val awakeColor = MetricColors.ZoneElevated
    val remColor = MetricColors.ZoneNormal
    val lightColor = MetricColors.ZoneLow
    val deepColor = MaterialTheme.colorScheme.primary
    fun colorFor(stage: String): Color = when (stage.uppercase()) {
        "AWAKE" -> awakeColor
        "REM" -> remColor
        "DEEP" -> deepColor
        else -> lightColor
    }

    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant

    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height((present.size * 34).dp)) {
            val rowH = size.height / present.size
            val xFor = { t: Long -> ((t - spanStart) / spanMs) * size.width }
            // faint row separators
            for (i in 0..present.size) {
                val y = i * rowH
                drawLine(gridColor, Offset(0f, y), Offset(size.width, y), 1f)
            }
            // stage bars, one rounded segment per block on its stage's row
            for (b in sorted) {
                val row = rowOf[b.stageRaw.uppercase()] ?: rowOf.entries.firstOrNull {
                    it.key.equals("LIGHT", true)
                }?.value ?: continue
                val x0 = xFor(b.startAt)
                val x1 = xFor(b.startAt + b.durationMinutes * 60_000L)
                val pad = rowH * 0.22f
                drawRoundRect(
                    color = colorFor(b.stageRaw),
                    topLeft = Offset(x0 + 1f, row * rowH + pad),
                    size = Size((x1 - x0 - 2f).coerceAtLeast(2f), rowH - pad * 2),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f, 4f),
                )
            }
        }
        // clock axis: start · midpoint · end
        val fmt = DateTimeFormatter.ofPattern("h:mm a")
        val zone = ZoneId.systemDefault()
        fun clock(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).format(fmt)
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(clock(spanStart), style = MaterialTheme.typography.labelSmall, color = labelColor)
            Text(clock(spanStart + (spanEnd - spanStart) / 2), style = MaterialTheme.typography.labelSmall, color = labelColor)
            Text(clock(spanEnd), style = MaterialTheme.typography.labelSmall, color = labelColor)
        }
        // legend — only stages actually present
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            present.forEach { stage ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Canvas(Modifier.size(10.dp)) {
                        drawRoundRect(colorFor(stage),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(3f, 3f))
                    }
                    Spacer(Modifier.width(5.dp))
                    Text(stage.lowercase().replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.labelSmall, color = labelColor)
                }
            }
        }
    }
}
