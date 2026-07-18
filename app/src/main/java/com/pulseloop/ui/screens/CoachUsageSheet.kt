package com.pulseloop.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulseloop.coach.config.CoachProviderMode
import com.pulseloop.data.entity.CoachConversationEntity
import com.pulseloop.data.entity.CoachMessageEntity
import com.pulseloop.ui.theme.PulseColors
import java.text.NumberFormat
import java.util.Locale

/**
 * Ported from [CoachUsageSheet] in CoachUsageSheet.swift (iOS #65).
 * Per-conversation token/cost transparency: provider + model(s), total input/
 * output tokens, and total cost, with a per-message breakdown. Tolerates
 * all-nil accounting (older conversations, turns before #65b landed) by
 * rendering "—".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoachUsageSheet(
    conversation: CoachConversationEntity?,
    messages: List<CoachMessageEntity>,
    fallbackModel: String,
    fallbackProviderMode: CoachProviderMode,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val accounted = remember(messages) {
        messages.filter { it.inputTokens != null || it.outputTokens != null || it.costUSD != null }
    }
    val totalInputTokens = (conversation?.totalInputTokens ?: 0).takeIf { it > 0 }
        ?: messages.sumOf { it.inputTokens ?: 0 }
    val totalOutputTokens = (conversation?.totalOutputTokens ?: 0).takeIf { it > 0 }
        ?: messages.sumOf { it.outputTokens ?: 0 }
    val totalCost = (conversation?.totalCostUSD ?: 0.0).takeIf { it > 0 }
        ?: messages.mapNotNull { it.costUSD }.takeIf { it.isNotEmpty() }?.sum()
    val models = remember(messages) {
        val seen = LinkedHashSet<String>()
        messages.forEach { it.modelUsed?.takeIf { m -> m.isNotEmpty() }?.let(seen::add) }
        if (seen.isEmpty()) listOf(fallbackModel) else seen.toList()
    }
    val providerLabel = messages.firstNotNullOfOrNull { it.providerUsed }
        ?.let { CoachProviderMode.fromRaw(it).label }
        ?: fallbackProviderMode.label
    val showsCostNote = fallbackProviderMode == CoachProviderMode.OFFLINE_STUB ||
        accounted.any { (it.inputTokens != null || it.outputTokens != null) && it.costUSD == null }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = PulseColors.background) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Usage & cost", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = PulseColors.textPrimary)
            SummaryCard(providerLabel, models, totalInputTokens, totalOutputTokens, totalCost)
            if (showsCostNote) CostNote()
            if (accounted.isNotEmpty()) BreakdownCard(accounted)
        }
    }
}

@Composable
private fun SummaryCard(
    providerLabel: String, models: List<String>,
    totalInputTokens: Int, totalOutputTokens: Int, totalCost: Double?,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(PulseColors.card)
            .padding(horizontal = 16.dp),
    ) {
        UsageRow("Provider", providerLabel)
        Divider()
        UsageRow(if (models.size > 1) "Models" else "Model", models.joinToString(", "))
        Divider()
        UsageRow("Input tokens", formatTokens(totalInputTokens), mono = true)
        Divider()
        UsageRow("Output tokens", formatTokens(totalOutputTokens), mono = true)
        Divider()
        UsageRow("Estimated cost", totalCost?.let { "$" + "%.4f".format(it) } ?: "—", mono = true)
    }
}

@Composable
private fun CostNote() {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(PulseColors.textMuted.copy(alpha = 0.10f))
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(Icons.Filled.Info, null, tint = PulseColors.textMuted, modifier = Modifier.size(14.dp))
        Text(
            "Cost estimates aren't available for on-device or custom models.",
            fontSize = 12.sp, color = PulseColors.textMuted,
        )
    }
}

@Composable
private fun BreakdownCard(accounted: List<CoachMessageEntity>) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(PulseColors.card)
            .padding(16.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Per-message breakdown", fontSize = 13.sp, fontWeight = FontWeight.Medium,
                color = PulseColors.textPrimary,
            )
            Text(if (expanded) "Hide" else "Show", fontSize = 12.sp, color = PulseColors.textSecondary)
        }
        if (expanded) {
            Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                accounted.forEach { MessageBreakdownRow(it) }
            }
        }
    }
}

@Composable
private fun MessageBreakdownRow(message: CoachMessageEntity) {
    val preview = message.body.replace("\n", " ").take(30)
    val tokens = "${message.inputTokens ?: 0} in · ${message.outputTokens ?: 0} out"
    val cost = message.costUSD?.let { "$" + "%.4f".format(it) } ?: "—"
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(preview.ifEmpty { "(no text)" }, fontSize = 12.sp, color = PulseColors.textPrimary, maxLines = 1)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(tokens, fontSize = 11.sp, color = PulseColors.textMuted)
            Text(cost, fontSize = 11.sp, color = PulseColors.textMuted)
        }
    }
}

@Composable
private fun UsageRow(label: String, value: String, mono: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 14.sp, color = PulseColors.textSecondary)
        Text(value, fontSize = 14.sp, color = PulseColors.textPrimary)
    }
}

@Composable
private fun Divider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(PulseColors.borderSubtle))
}

private fun formatTokens(value: Int): String = NumberFormat.getNumberInstance(Locale.US).format(value)
