package com.pulseloop.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
                "Your ring's clock seems to have stopped",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = PulseColors.warning,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (canRestart) {
                    "It still connects, but it isn't recording heart rate or sleep on its own. " +
                        "Restarting the ring fixes this, and nothing stored on it is lost."
                } else {
                    "It still connects, but it isn't recording heart rate or sleep on its own. " +
                        "Turn it off and on again from the ring's own app or its charger."
                },
                style = MaterialTheme.typography.bodySmall,
                color = PulseColors.textSecondary,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (canRestart) Button(onClick = onRestart) { Text("Restart ring") }
                TextButton(onClick = { RingClockStallStore.dismiss(context) }) { Text("Not now") }
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
            title = { Text("Restart your ring") },
            text = {
                Text(
                    "PulseLoop will turn your ring off. You'll then put it on its charger to " +
                        "turn it back on, and PulseLoop will reconnect and set its time.",
                )
            },
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
                }) { Text("Turn off ring") }
            },
            dismissButton = { TextButton(onClick = { onStep(RingRestartStep.NONE) }) { Text("Cancel") } },
        )
        RingRestartStep.CHARGE -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Put your ring on its charger") },
            text = {
                Text(
                    "Your ring is turning off. Put it on its charger to turn it back on — it stays " +
                        "off until you do.\n\nOnce it's back on, PulseLoop reconnects and sets the " +
                        "time by itself. If the clock still doesn't run, this warning will come back.",
                )
            },
            confirmButton = { TextButton(onClick = { onStep(RingRestartStep.NONE) }) { Text("Done") } },
        )
        RingRestartStep.NOT_CONNECTED -> AlertDialog(
            onDismissRequest = { onStep(RingRestartStep.NONE) },
            title = { Text("Ring not connected") },
            text = {
                Text(
                    "PulseLoop needs to be connected to your ring to turn it off. Wait for it to " +
                        "connect and try again.",
                )
            },
            confirmButton = { TextButton(onClick = { onStep(RingRestartStep.NONE) }) { Text("OK") } },
        )
        RingRestartStep.UNSUPPORTED -> AlertDialog(
            onDismissRequest = { onStep(RingRestartStep.NONE) },
            title = { Text("Can't turn this ring off") },
            text = {
                Text("This ring can't be turned off from PulseLoop. Restart it from its own app or its charger.")
            },
            confirmButton = { TextButton(onClick = { onStep(RingRestartStep.NONE) }) { Text("OK") } },
        )
    }
}
