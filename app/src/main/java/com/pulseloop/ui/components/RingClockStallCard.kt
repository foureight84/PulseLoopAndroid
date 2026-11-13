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

/**
 * "Your ring's clock seems to have stopped" (issue #142), with the restart walkthrough the
 * reporter found works: power the ring off, put it on its charger to turn it back on, and let
 * PulseLoop reconnect and set the time. The charger step is the one people miss — without it the
 * ring just stays off — so the dialog keeps it on screen after the ring goes dark.
 *
 * The card clears itself once a later reading comes back stamped by a running clock.
 */
@Composable
fun RingClockStallCard(coordinator: RingSyncCoordinator?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { RingClockStallStore.load(context) }
    val suspectedAt by RingClockStallStore.suspectedAt.collectAsState()
    var step by remember { mutableStateOf(RestartStep.NONE) }
    if (suspectedAt == null && step == RestartStep.NONE) return

    if (suspectedAt != null) {
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
                    "It still connects, but it isn't recording heart rate or sleep on its own. " +
                        "Restarting the ring fixes this, and nothing stored on it is lost.",
                    style = MaterialTheme.typography.bodySmall,
                    color = PulseColors.textSecondary,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { step = RestartStep.CONFIRM }) { Text("Restart ring") }
                    TextButton(onClick = { RingClockStallStore.dismiss(context) }) { Text("Not now") }
                }
            }
        }
    }

    when (step) {
        RestartStep.NONE -> Unit
        RestartStep.CONFIRM -> AlertDialog(
            onDismissRequest = { step = RestartStep.NONE },
            title = { Text("Restart your ring") },
            text = {
                Text(
                    "PulseLoop will turn your ring off. You'll then put it on its charger to " +
                        "turn it back on, and PulseLoop will reconnect and set its time.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    step = if (coordinator?.restartRing() == true) RestartStep.CHARGE else RestartStep.NOT_CONNECTED
                }) { Text("Turn off ring") }
            },
            dismissButton = { TextButton(onClick = { step = RestartStep.NONE }) { Text("Cancel") } },
        )
        RestartStep.CHARGE -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Put your ring on its charger") },
            text = {
                Text(
                    "Your ring is turning off. Put it on its charger to turn it back on — it stays " +
                        "off until you do.\n\nOnce it's back on, PulseLoop reconnects and sets the " +
                        "time by itself. This warning clears when the next reading comes back " +
                        "with the right time.",
                )
            },
            confirmButton = { TextButton(onClick = { step = RestartStep.NONE }) { Text("Done") } },
        )
        RestartStep.NOT_CONNECTED -> AlertDialog(
            onDismissRequest = { step = RestartStep.NONE },
            title = { Text("Ring not connected") },
            text = {
                Text(
                    "PulseLoop needs to be connected to your ring to turn it off. Wait for it to " +
                        "connect and try again, or restart it from the ring's own app.",
                )
            },
            confirmButton = { TextButton(onClick = { step = RestartStep.NONE }) { Text("OK") } },
        )
    }
}

private enum class RestartStep { NONE, CONFIRM, CHARGE, NOT_CONNECTED }
