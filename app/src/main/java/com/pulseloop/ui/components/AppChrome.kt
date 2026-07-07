package com.pulseloop.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulseloop.ring.RingConnectionState
import com.pulseloop.ui.theme.PulseColors
import java.util.Calendar

/**
 * Fixed top header shared by every tab — ported from RootViews.swift `AppHeader`:
 * small uppercase brand over a time-based greeting on the left; the connection-status
 * pill plus a settings gear on the right.
 */
@Composable
fun AppHeader(
    state: RingConnectionState,
    batteryPercent: Int?,
    onOpenSettings: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // No opaque background: the caller layers this header on the glass
            // top bar (hazeEffect in PulseLoopApp), which supplies the material.
            .padding(start = 20.dp, end = 8.dp, top = 2.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "PULSELOOP",
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 1.2.sp,
                color = PulseColors.textMuted,
            )
            Text(
                greetingForHour(),
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = PulseColors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        ConnectionStatusPill(
            state = state,
            batteryPercent = batteryPercent,
            modifier = Modifier.clickable(onClick = onOpenSettings),
        )
        IconButton(onClick = onOpenSettings) {
            Icon(Icons.Outlined.Settings, "Settings", tint = PulseColors.textSecondary)
        }
    }
}

/**
 * Colored-dot + label capsule describing the BLE connection state.
 * Ported from RootViews.swift `ConnectionStatusPill`, including the
 * "Disconnected, not Searching…" verdict for idle background scanning (iOS #41).
 */
@Composable
fun ConnectionStatusPill(
    state: RingConnectionState,
    batteryPercent: Int?,
    modifier: Modifier = Modifier,
) {
    val isPulsing = state == RingConnectionState.CONNECTING || state == RingConnectionState.RECONNECTING
    val dotColor = when (state) {
        RingConnectionState.CONNECTED -> PulseColors.success
        RingConnectionState.CONNECTING, RingConnectionState.RECONNECTING -> PulseColors.accent
        else -> PulseColors.danger  // scanning/failed/idle/disconnected all read "not connected"
    }
    val label = when (state) {
        RingConnectionState.CONNECTED ->
            if (batteryPercent != null && batteryPercent > 0) "Connected · $batteryPercent%" else "Connected"
        RingConnectionState.CONNECTING, RingConnectionState.RECONNECTING -> "Connecting…"
        RingConnectionState.FAILED -> "Sync failed"
        else -> "Disconnected"
    }
    val pulse by rememberInfiniteTransition(label = "pill").animateFloat(
        initialValue = 1f,
        targetValue = if (isPulsing) 0.35f else 1f,
        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
        label = "pillDot",
    )

    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(PulseColors.card)
            .border(1.dp, PulseColors.borderSubtle, CircleShape)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .size(8.dp)
                .alpha(if (isPulsing) pulse else 1f)
                .clip(CircleShape)
                .background(dotColor),
        )
        Text(
            label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = PulseColors.textSecondary,
            maxLines = 1,
        )
    }
}

/** Time-of-day greeting, mirroring RootViews.swift `greetingForHour()`. */
fun greetingForHour(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 5..11 -> "Good morning"
    in 12..16 -> "Good afternoon"
    in 17..21 -> "Good evening"
    else -> "Good night"
}

/**
 * The iOS card eyebrow: colored dot + letterspaced ALL-CAPS label, with an optional
 * trailing slot (e.g. "Updated 5m ago", a legend, or a chip). Matches VitalCard's
 * header so hand-built tiles read identically to the vitals cards.
 */
@Composable
fun CardEyebrow(
    label: String,
    dotColor: Color,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(dotColor),
        )
        Text(
            label.uppercase(),
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 1.sp,
            color = PulseColors.textSecondary,
        )
        Spacer(Modifier.weight(1f))
        trailing?.invoke()
    }
}

/** Uppercase letterspaced section header used between card groups (RootViews `SectionHeader`). */
@Composable
fun SectionHeaderCaps(title: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            title.uppercase(),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.8.sp,
            color = PulseColors.textSecondary,
        )
        Spacer(Modifier.weight(1f))
        action?.invoke()
    }
}
