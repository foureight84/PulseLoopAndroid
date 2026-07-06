package com.pulseloop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulseloop.ui.theme.PulseColors

/**
 * Ported from SettingsComponents.swift (iOS #49).
 * One tappable Settings category row plus the grouped-section card that hosts them.
 */
data class SettingsRowItem(
    val icon: ImageVector,
    val tint: Color,
    val title: String,
    val trailingValue: String? = null,
    val action: () -> Unit,
)

/**
 * An uppercase section header plus a rounded card wrapping its rows with hairline dividers.
 * Renders nothing when [rows] is empty, so a fully gated-off group leaves no empty header.
 */
@Composable
fun SettingsSection(title: String, rows: List<SettingsRowItem>) {
    if (rows.isEmpty()) return
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            title.uppercase(),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp,
            color = PulseColors.textSecondary,
            modifier = Modifier.padding(start = 16.dp),
        )

        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(PulseColors.card)
                .border(1.dp, PulseColors.borderSubtle, RoundedCornerShape(20.dp)),
        ) {
            rows.forEachIndexed { index, row ->
                SettingsRow(row)
                if (index < rows.size - 1) {
                    HorizontalDivider(
                        color = PulseColors.borderSubtle,
                        thickness = 0.5.dp,
                        modifier = Modifier.padding(start = 64.dp), // align with the row's text column
                    )
                }
            }
        }
    }
}

/** A single row: tinted icon in a rounded box, title, optional trailing value, chevron. */
@Composable
fun SettingsRow(item: SettingsRowItem) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()

    Row(
        Modifier
            .fillMaxWidth()
            .background(if (pressed) PulseColors.elevated else Color.Transparent)
            .clickable(interactionSource = interactionSource, indication = null, onClick = item.action)
            .heightIn(min = 52.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(item.tint.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(item.icon, contentDescription = null, Modifier.size(18.dp), tint = item.tint)
        }

        Text(
            item.title,
            fontSize = 16.sp,
            color = PulseColors.textPrimary,
        )

        Spacer(Modifier.weight(1f))

        item.trailingValue?.let { trailing ->
            Text(
                trailing,
                fontSize = 14.sp,
                color = PulseColors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = PulseColors.textMuted,
        )
    }
}
