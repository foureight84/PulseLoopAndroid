package com.pulseloop.ui.components

import androidx.compose.ui.graphics.Color
import com.pulseloop.ring.RingConnectionState
import com.pulseloop.ui.theme.PulseColors

/**
 * Ported from DeviceHeroStatus (DeviceHeroCard.swift, iOS #49).
 * Pure, view-agnostic description of the ring's state for the Settings hero card.
 * All branching lives here so [DeviceHeroCard] stays declarative and this stays unit-testable
 * (Compose `Color` is plain JVM bytecode, so no Robolectric needed).
 */
data class DeviceHeroStatus(
    val title: String,
    val statusLine: String,
    val statusTint: Color,
    val batteryText: String?,
    val syncText: String?,
    val action: Action,
) {
    enum class Action {
        DISCONNECT,
        CONNECT,
        SET_UP,
        /** A connect attempt is in flight (connecting / reconnecting / scanning). */
        PENDING,
    }

    val actionTitle: String
        get() = when (action) {
            Action.DISCONNECT -> "Disconnect"
            Action.CONNECT -> "Connect"
            Action.SET_UP -> "Set up a ring"
            Action.PENDING -> "Connecting…"
        }

    /** The action button is inert while a connection attempt is in flight, so a tap can't
     *  cancel or restart an in-progress (re)connect. */
    val actionEnabled: Boolean get() = action != Action.PENDING

    companion object {
        /**
         * `connectedName` reflects the live connection (null unless connected); `knownName` is
         * the last stored device (survives disconnect). Separating them distinguishes "known but
         * disconnected" ([Action.CONNECT]) from "never paired" ([Action.SET_UP]).
         */
        fun make(
            state: RingConnectionState,
            connectedName: String?,
            knownName: String?,
            batteryPercent: Int?,
            lastSyncAt: Long?,
            now: Long,
        ): DeviceHeroStatus {
            val title = connectedName ?: knownName ?: "No ring connected"

            val statusLine: String
            val statusTint: Color
            when (state) {
                RingConnectionState.CONNECTED -> {
                    statusLine = "Connected"; statusTint = PulseColors.success
                }
                RingConnectionState.CONNECTING, RingConnectionState.RECONNECTING -> {
                    statusLine = "Connecting…"; statusTint = PulseColors.warning
                }
                RingConnectionState.SCANNING -> {
                    statusLine = "Searching…"; statusTint = PulseColors.warning
                }
                RingConnectionState.FAILED -> {
                    statusLine = "Connection failed"; statusTint = PulseColors.danger
                }
                RingConnectionState.IDLE, RingConnectionState.DISCONNECTED -> {
                    statusLine = if (knownName == null) "No ring paired" else "Disconnected"
                    statusTint = PulseColors.textSecondary
                }
            }

            val batteryText = batteryPercent?.let { "${it.coerceIn(0, 100)}%" }

            val syncText = lastSyncAt?.let { "Synced ${relativeShort(it, now)}" }

            val action = when (state) {
                RingConnectionState.CONNECTED -> Action.DISCONNECT
                RingConnectionState.CONNECTING,
                RingConnectionState.RECONNECTING,
                RingConnectionState.SCANNING -> Action.PENDING
                RingConnectionState.IDLE,
                RingConnectionState.DISCONNECTED,
                RingConnectionState.FAILED -> if (knownName != null) Action.CONNECT else Action.SET_UP
            }

            return DeviceHeroStatus(
                title = title, statusLine = statusLine, statusTint = statusTint,
                batteryText = batteryText, syncText = syncText, action = action,
            )
        }

        /** Short relative-past phrase (iOS `RelativeDateTimeFormatter` `.short` equivalent). */
        fun relativeShort(fromMillis: Long, nowMillis: Long): String {
            val seconds = ((nowMillis - fromMillis) / 1000).coerceAtLeast(0)
            return when {
                seconds < 60 -> "just now"
                seconds < 3_600 -> "${seconds / 60}m ago"
                seconds < 86_400 -> "${seconds / 3_600}h ago"
                else -> "${seconds / 86_400}d ago"
            }
        }
    }
}
