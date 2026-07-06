package com.pulseloop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulseloop.R
import com.pulseloop.data.entity.DeviceEntity
import com.pulseloop.ring.RingBLEClient
import com.pulseloop.ring.RingConnectionState
import com.pulseloop.ring.RingDeviceType
import com.pulseloop.ui.theme.PulseColors
import com.pulseloop.wearables.WearableModel

/**
 * Ported from DeviceHeroCard.swift (iOS #49).
 * Rich ring-connectivity card at the top of Settings. The card (or its trailing chevron) opens
 * the Wearable screen; a separate action button connects/sets up. The two are distinct controls
 * so both are reachable by touch and accessibility services.
 */
@Composable
fun DeviceHeroCard(
    bleState: RingBLEClient.BLEState,
    storedDevice: DeviceEntity?,
    lastSyncAt: Long?,
    onOpenWearable: () -> Unit,
    onConnect: () -> Unit,
    onSetUp: () -> Unit,
) {
    val deviceType = bleState.activeDeviceType ?: storedDevice?.deviceType
    val wearableModel = WearableModel.model(bleState.activeWearableModelID)
        ?: WearableModel.model(storedDevice?.wearableModelID)
    val battery = bleState.batteryPercent ?: storedDevice?.batteryPercent
    val connected = bleState.connectionState == RingConnectionState.CONNECTED
    val status = DeviceHeroStatus.make(
        state = bleState.connectionState,
        connectedName = if (connected) wearableModel?.displayName ?: bleState.activeDeviceType?.displayName else null,
        knownName = wearableModel?.displayName ?: deviceType?.displayName,
        batteryPercent = battery,
        lastSyncAt = lastSyncAt,
        now = System.currentTimeMillis(),
    )

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(PulseColors.card)
            .border(1.dp, PulseColors.borderSubtle, RoundedCornerShape(20.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenWearable),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            RingArtView(
                tint = PulseColors.info,
                size = 72f,
                imageRes = wearableModel?.imageRes ?: fallbackRingImage(deviceType),
            )

            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(
                    status.title,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = PulseColors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    status.statusLine,
                    fontSize = 13.sp,
                    color = status.statusTint,
                    maxLines = 1,
                )
                status.syncText?.let { syncText ->
                    Text(
                        syncText,
                        fontSize = 12.sp,
                        color = PulseColors.textSecondary,
                        maxLines = 1,
                    )
                }
            }

            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                status.batteryText?.let { batteryText ->
                    Row(
                        Modifier
                            .clip(CircleShape)
                            .background(PulseColors.success.copy(alpha = 0.12f))
                            .padding(horizontal = 9.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            Icons.Filled.BatteryFull,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = PulseColors.success,
                        )
                        Text(
                            batteryText,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = PulseColors.success,
                        )
                    }
                }
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = "Opens ring settings",
                    modifier = Modifier.size(20.dp),
                    tint = PulseColors.textMuted,
                )
            }
        }

        // The connected card is purely informational and opens Wearable settings, where
        // Disconnect lives. Setup/reconnect remain available here only when not connected.
        if (status.action != DeviceHeroStatus.Action.DISCONNECT) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Text(
                    status.actionTitle,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (status.actionEnabled) PulseColors.accent else PulseColors.textMuted,
                    modifier = Modifier
                        .heightIn(min = 44.dp)
                        .clip(CircleShape)
                        .background(if (status.actionEnabled) PulseColors.accentSoft else PulseColors.elevated)
                        .clickable(enabled = status.actionEnabled) {
                            when (status.action) {
                                DeviceHeroStatus.Action.CONNECT -> onConnect()
                                DeviceHeroStatus.Action.SET_UP -> onSetUp()
                                else -> {}
                            }
                        }
                        .padding(horizontal = 12.dp)
                        .wrapContentHeight(),
                )
            }
        }
    }
}

/**
 * Representative product image for the connected/known ring family — the connection only
 * reveals the family, not the exact model. Null falls back to the generic ring in RingArtView.
 */
private fun fallbackRingImage(type: RingDeviceType?): Int? = when (type) {
    RingDeviceType.JRING -> R.drawable.ring_jring
    RingDeviceType.COLMI_R02, null -> null
}
