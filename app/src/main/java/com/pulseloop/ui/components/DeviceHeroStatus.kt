package com.pulseloop.ui.components

import com.pulseloop.R
import com.pulseloop.util.L10n
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
        /** A connect attempt is in flight (connecting / scanning). */
        PENDING,
    }

    val actionTitle: String
        get() = when (action) {
            Action.DISCONNECT -> L10n.get(R.string.wearable_disconnect)
            Action.CONNECT -> L10n.get(R.string.hero_connect)
            Action.SET_UP -> L10n.get(R.string.hero_set_up)
            Action.PENDING -> L10n.get(R.string.pill_connecting)
        }

    /** The action button is inert while a single connection attempt is in flight, so a tap
     *  can't cancel or restart it. Automatic recovery (RECONNECTING) can run for minutes, so it
     *  offers Disconnect instead (issue #124). */
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
            val title = connectedName ?: knownName ?: L10n.get(R.string.hero_no_ring)

            val statusLine: String
            val statusTint: Color
            when (state) {
                RingConnectionState.CONNECTED -> {
                    statusLine = L10n.get(R.string.status_connected); statusTint = PulseColors.success
                }
                RingConnectionState.CONNECTING -> {
                    statusLine = L10n.get(R.string.pill_connecting); statusTint = PulseColors.warning
                }
                RingConnectionState.RECONNECTING -> {
                    statusLine = L10n.get(R.string.pill_reconnecting); statusTint = PulseColors.warning
                }
                RingConnectionState.SCANNING -> {
                    statusLine = L10n.get(R.string.hero_searching); statusTint = PulseColors.warning
                }
                RingConnectionState.FAILED -> {
                    statusLine = L10n.get(R.string.hero_connection_failed); statusTint = PulseColors.danger
                }
                RingConnectionState.IDLE, RingConnectionState.DISCONNECTED -> {
                    statusLine = if (knownName == null) L10n.get(R.string.wearable_none_paired) else L10n.get(R.string.pill_disconnected)
                    statusTint = PulseColors.textSecondary
                }
            }

            val batteryText = batteryPercent?.let { "${it.coerceIn(0, 100)}%" }

            val syncText = lastSyncAt?.let { L10n.get(R.string.hero_synced, relativeShort(it, now)) }

            val action = when (state) {
                RingConnectionState.CONNECTED,
                RingConnectionState.RECONNECTING -> Action.DISCONNECT
                RingConnectionState.CONNECTING,
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
                seconds < 60 -> L10n.get(R.string.ago_just_now)
                seconds < 3_600 -> L10n.get(R.string.ago_minutes, seconds / 60)
                seconds < 86_400 -> L10n.get(R.string.ago_hours, seconds / 3_600)
                else -> L10n.get(R.string.ago_days, seconds / 86_400)
            }
        }
    }
}
