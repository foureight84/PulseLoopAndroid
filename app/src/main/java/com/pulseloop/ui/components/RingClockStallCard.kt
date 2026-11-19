package com.pulseloop.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pulseloop.R
import com.pulseloop.service.RingClockStallStore
import com.pulseloop.service.RingSyncCoordinator
import com.pulseloop.ui.theme.PulseColors

/** Where the restart walkthrough is (issue #142). Held by the screen, not the card, so the
 *  "put it on the charger" step survives the warning clearing and the card scrolling away. */
enum class RingRestartStep { NONE, CONFIRM, CHARGE, NOT_CONNECTED, UNSUPPORTED }

/**
 * "Your ring's clock seems to have stopped" (issue #142). The ring still connects but logs nothing
 * on its own, which otherwise looks exactly like a ring that wasn't worn.
 *
 * [canRestart] is false only when a connected ring has said it can't be powered off from the app;
 * while disconnected the button stays, and the dialog explains it needs a connection.
 */
@Composable
fun RingClockStallCard(canRestart: Boolean, onRestart: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Card(
        modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = PulseColors.card),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.stall_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = PulseColors.warning,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(
                    if (canRestart) R.string.stall_body_can_restart else R.string.stall_body_manual_restart,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = PulseColors.textSecondary,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (canRestart) Button(onClick = onRestart) { Text(stringResource(R.string.stall_restart_action)) }
                TextButton(onClick = { RingClockStallStore.dismiss(context) }) { Text(stringResource(R.string.action_not_now)) }
            }
        }
    }
}

/**
 * The restart walkthrough the reporter found works: power the ring off, put it on its charger to
 * turn it back on, and let PulseLoop reconnect and set the time. The charger step is the one people
 * miss — without it the ring just stays off — so it stays on screen after the ring goes dark.
 *
 * Sending the power-off forgets the stall evidence: the warning comes back by itself if the next
 * readings still arrive stamped by a stopped clock.
 */
@Composable
fun RingRestartDialog(
    step: RingRestartStep,
    onStep: (RingRestartStep) -> Unit,
    coordinator: RingSyncCoordinator?,
) {
    val context = LocalContext.current
    when (step) {
        RingRestartStep.NONE -> Unit
        RingRestartStep.CONFIRM -> AlertDialog(
            onDismissRequest = { onStep(RingRestartStep.NONE) },
            title = { Text(stringResource(R.string.stall_confirm_title)) },
            text = { Text(stringResource(R.string.stall_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    onStep(
                        when (coordinator?.restartRing()) {
                            RingSyncCoordinator.RestartResult.SENT -> {
                                RingClockStallStore.reset(context)
                                RingRestartStep.CHARGE
                            }
                            RingSyncCoordinator.RestartResult.UNSUPPORTED -> RingRestartStep.UNSUPPORTED
                            else -> RingRestartStep.NOT_CONNECTED
                        },
                    )
                }) { Text(stringResource(R.string.stall_turn_off_action)) }
            },
            dismissButton = { TextButton(onClick = { onStep(RingRestartStep.NONE) }) { Text(stringResource(R.string.action_cancel)) } },
        )
        RingRestartStep.CHARGE -> AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.stall_charge_title)) },
            text = { Text(stringResource(R.string.stall_charge_body)) },
            confirmButton = { TextButton(onClick = { onStep(RingRestartStep.NONE) }) { Text(stringResource(R.string.action_done)) } },
        )
        RingRestartStep.NOT_CONNECTED -> AlertDialog(
            onDismissRequest = { onStep(RingRestartStep.NONE) },
            title = { Text(stringResource(R.string.stall_not_connected_title)) },
            text = { Text(stringResource(R.string.stall_not_connected_body)) },
            confirmButton = { TextButton(onClick = { onStep(RingRestartStep.NONE) }) { Text(stringResource(R.string.action_ok)) } },
        )
        RingRestartStep.UNSUPPORTED -> AlertDialog(
            onDismissRequest = { onStep(RingRestartStep.NONE) },
            title = { Text(stringResource(R.string.stall_unsupported_title)) },
            text = { Text(stringResource(R.string.stall_unsupported_body)) },
            confirmButton = { TextButton(onClick = { onStep(RingRestartStep.NONE) }) { Text(stringResource(R.string.action_ok)) } },
        )
    }
}
