package com.pulseloop.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pulseloop.ring.RingBLEClient
import com.pulseloop.ring.RingDeviceType
import kotlinx.coroutines.flow.collectLatest

/**
 * Friendly model label for the pairing badge.
 *
 * The whole Colmi/Yawell family (R02, R03, R06, R07, R09, R10, R11, R12, H59…) is served by
 * a single driver whose [RingDeviceType] is [RingDeviceType.COLMI_R02], so its `displayName`
 * would mislabel e.g. an R10 as "Colmi R02". Derive the real model from the advertised BLE
 * name instead ("COLMI R10_1203" → "Colmi R10", "R02_AB12" → "Colmi R02"), falling back to
 * the family display name when the name carries no recognizable model token.
 */
private fun ringModelLabel(name: String, deviceType: RingDeviceType): String {
    if (deviceType != RingDeviceType.COLMI_R02) return deviceType.displayName
    val token = name.trim()
        .removePrefix("COLMI ").removePrefix("Colmi ")
        .substringBefore('_')
        .trim()
    // Colmi model tokens look like R02 / R10 / R11C / H59 — letter(s) + digits (+ optional letter).
    return if (token.matches(Regex("^[A-Za-z]{1,2}[0-9]{2,3}[A-Za-z]?$"))) {
        "Colmi ${token.uppercase()}"
    } else {
        deviceType.displayName
    }
}

/**
 * Pairing screen — scan for nearby rings and connect.
 * Ported from PairingView.swift.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairingScreen(
    bleClient: RingBLEClient,
    onConnected: () -> Unit,
) {
    val state by bleClient.state.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        if (state.connectionState != com.pulseloop.ring.RingConnectionState.SCANNING &&
            state.connectionState != com.pulseloop.ring.RingConnectionState.CONNECTED) {
            bleClient.startScanning()
        }
    }

    // Navigate away on connect
    LaunchedEffect(state.connectionState) {
        if (state.connectionState == com.pulseloop.ring.RingConnectionState.CONNECTED) {
            onConnected()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Pair Ring") },
                navigationIcon = {
                    IconButton(onClick = {
                        bleClient.stopScanning()
                        onConnected()
                    }) {
                        Icon(Icons.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    if (state.connectionState == com.pulseloop.ring.RingConnectionState.SCANNING) {
                        IconButton(onClick = { bleClient.startScanning() }) {
                            Icon(Icons.Filled.Refresh, "Refresh")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // Connection status
            when (state.connectionState) {
                com.pulseloop.ring.RingConnectionState.SCANNING -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Scanning for rings…",
                        Modifier.padding(16.dp, 8.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                com.pulseloop.ring.RingConnectionState.CONNECTING -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Connecting…",
                        Modifier.padding(16.dp, 8.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                else -> {}
            }

            // Error
            state.lastError?.let { error ->
                Card(
                    onClick = {},
                    modifier = Modifier.fillMaxWidth().padding(16.dp, 8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                ) {
                    Text(error, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }

            if (!state.isBluetoothReady && state.connectionState == com.pulseloop.ring.RingConnectionState.IDLE) {
                // Bluetooth off
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Filled.BluetoothDisabled, "Bluetooth off", modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(16.dp))
                        Text("Bluetooth is disabled", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Text("Enable Bluetooth to find your ring", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(24.dp))
                        Button(onClick = { bleClient.startScanning() }) {
                            Icon(Icons.Filled.Refresh, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Retry")
                        }
                    }
                }
            } else if (state.discovered.isEmpty() && state.connectionState == com.pulseloop.ring.RingConnectionState.SCANNING) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Filled.BluetoothSearching, "Scanning", modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(16.dp))
                        Text("Looking for rings nearby…", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Text("Make sure your ring is charged and nearby", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                // Ring list
                Text(
                    "Nearby Devices",
                    Modifier.padding(16.dp, 12.dp, 16.dp, 4.dp),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                LazyColumn(Modifier.fillMaxSize()) {
                    items(state.discovered) { ring ->
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                            onClick = { bleClient.connectTo(ring.id) },
                        ) {
                            Row(
                                Modifier.padding(16.dp).fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        ring.name,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Medium,
                                    )
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (ring.isLikelyRing) {
                                            Icon(Icons.Filled.CheckCircle, "Ring", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                                            Spacer(Modifier.width(4.dp))
                                            Text("PulseLoop compatible", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                                        }
                                    }
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text("${ring.rssi} dBm", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    ring.deviceType?.let {
                                        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small) {
                                            Text(ringModelLabel(ring.name, it), Modifier.padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
