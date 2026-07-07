package com.pulseloop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulseloop.ui.theme.PulseColors

/**
 * Ported from PairingComponents.swift + the shared button styles used by the pairing and
 * onboarding screens (iOS #48): capability chips, BLE signal dots, inline empty state,
 * and the primary/secondary action buttons.
 */

/** Full-width accent action button (iOS `PrimaryButton`). */
@Composable
fun PrimaryButton(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().height(52.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = PulseColors.accent,
            contentColor = Color.White,
        ),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Full-width quiet action button on a card background (iOS `SecondaryButton`). */
@Composable
fun SecondaryButton(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .border(1.dp, PulseColors.borderSubtle, RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = PulseColors.card,
            contentColor = PulseColors.textPrimary,
        ),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, Modifier.size(18.dp), tint = PulseColors.textSecondary)
            Spacer(Modifier.width(8.dp))
        }
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * A ring model's capability blurb as a centered row of capsule chips; the blurb string is
 * split on " · " (space-middot-space). Scrolls horizontally when it doesn't fit.
 */
@Composable
fun CapabilityChips(blurb: String, modifier: Modifier = Modifier) {
    val chips = blurb.split(" · ")
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        chips.forEach { chip ->
            Text(
                chip,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = PulseColors.textSecondary,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(PulseColors.cardSoft)
                    .border(1.dp, PulseColors.borderSubtle, CircleShape)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

/** Three small circles indicating BLE signal strength for a discovered ring. */
@Composable
fun SignalStrengthDots(rssi: Int, modifier: Modifier = Modifier) {
    val filledCount = when {
        rssi >= -65 -> 3
        rssi >= -80 -> 2
        else -> 1
    }
    val filledColor = when {
        rssi >= -65 -> PulseColors.success
        rssi >= -80 -> PulseColors.warning
        else -> PulseColors.danger
    }
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(3) { i ->
            Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(if (i < filledCount) filledColor else PulseColors.elevated),
            )
        }
    }
}

/** Small centered title + message placeholder (iOS `InlineEmptyState`). */
@Composable
fun InlineEmptyState(title: String, message: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            title,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = PulseColors.textPrimary,
            textAlign = TextAlign.Center,
        )
        Text(
            message,
            fontSize = 12.sp,
            color = PulseColors.textMuted,
            textAlign = TextAlign.Center,
        )
    }
}
