package com.pulseloop.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pulseloop.coach.config.CoachProviderMode
import com.pulseloop.coach.config.CoachProviderSettingsStore
import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.ring.WearableCapability
import com.pulseloop.settings.ApiKeyStore
import com.pulseloop.ui.components.DeviceHeroCard
import com.pulseloop.ui.components.SettingsRowItem
import com.pulseloop.ui.components.SettingsSection
import com.pulseloop.ui.theme.PulseColors

/**
 * Ported from SettingsView.swift (iOS #49 rehaul).
 * Top-level Settings: a hero ring-device card over grouped sections of navigation rows. Each
 * row pushes a focused detail screen (see SettingsSubScreens.kt); the old inline cards moved
 * onto those screens 1:1.
 */
@Composable
fun SettingsScreen(
    navController: androidx.navigation.NavController? = null,
    bleClient: com.pulseloop.ring.RingBLEClient? = null,
    coordinator: com.pulseloop.service.RingSyncCoordinator? = null,
) {
    val context = LocalContext.current
    val keyStore = remember { ApiKeyStore(context) }
    val providerStore = remember { CoachProviderSettingsStore(context) }
    val db = remember { PulseLoopDatabase.getInstance(context) }

    val bleState = bleClient?.state?.collectAsState()?.value
        ?: com.pulseloop.ring.RingBLEClient.BLEState()
    val storedDevice by db.deviceDao().currentFlow().collectAsState(initial = null)

    // Capabilities of the live device (preferred) or the last stored device, used to decide
    // whether device-specific rows appear (iOS MetricsService.activeCapabilities).
    val capabilities: Set<WearableCapability> =
        bleState.activeCapabilities.ifEmpty { storedDevice?.capabilities ?: emptySet() }

    // Preference-backed values are read per composition: returning from a sub-screen
    // recomposes this hub, so toggles made there are reflected immediately.
    val coachEnabled = keyStore.coachEnabled
    val developerUnlocked = keyStore.developerUnlocked

    // Provider-aware AI Coach summary — mirrors iOS `coachTrailing` (no Apple on-device
    // mode on Android; hosted providers show the selected model slug).
    val coachTrailing = if (!coachEnabled) "Off" else when (providerStore.providerMode) {
        CoachProviderMode.OFFLINE_STUB -> "Offline"
        CoachProviderMode.USER_GEMINI_KEY -> providerStore.geminiModel
        CoachProviderMode.USER_OPENROUTER_KEY -> providerStore.openRouterModel
        CoachProviderMode.BACKEND_PROXY -> "Backend proxy"
        else -> keyStore.model
    }
    val notificationsTrailing = if (keyStore.notificationsEnabled) "On" else "Off"

    fun navigate(route: String) {
        navController?.navigate(route)
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)

        DeviceHeroCard(
            bleState = bleState,
            storedDevice = storedDevice,
            lastSyncAt = coordinator?.lastSyncAt ?: storedDevice?.lastSyncAt,
            onOpenWearable = { navigate("settings/wearable") },
            onConnect = { bleClient?.connectLastKnown() },
            onSetUp = { navigate("pairing") },
        )

        // DEVICE — only rings that expose a configurable measurement interval (Colmi) declare
        // MEASUREMENT_INTERVAL, so the generic 56ff jring never shows this row.
        SettingsSection(
            title = "Device",
            rows = buildList {
                if (capabilities.contains(WearableCapability.MEASUREMENT_INTERVAL)) {
                    add(SettingsRowItem(Icons.Filled.Timer, PulseColors.spo2, "Measurement Frequency") {
                        navigate("settings/measurement")
                    })
                }
            },
        )

        // AI COACH — check-ins are a coach sub-feature, only shown once the coach is on.
        SettingsSection(
            title = "AI Coach",
            rows = buildList {
                add(SettingsRowItem(Icons.Filled.AutoAwesome, PulseColors.accent, "AI Coach", coachTrailing) {
                    navigate("settings/coach")
                })
                if (coachEnabled) {
                    add(SettingsRowItem(Icons.Filled.NotificationsActive, PulseColors.warning, "Coach Check-Ins", notificationsTrailing) {
                        navigate("settings/checkins")
                    })
                }
            },
        )

        // GENERAL
        SettingsSection(
            title = "General",
            rows = listOf(
                SettingsRowItem(Icons.Filled.AccountCircle, PulseColors.accent, "User Profile") {
                    navigate("settings/profile")
                },
            ),
        )

        // METRICS
        SettingsSection(
            title = "Metrics",
            rows = listOf(
                SettingsRowItem(Icons.Filled.TrackChanges, PulseColors.readiness, "Goals") {
                    navigate("settings/goals")
                },
            ),
        )

        // RESOURCES — Calibration is jring-only (Colmi rings measure neither BP nor blood
        // sugar); Developer is hidden until unlocked by tapping the version 7× in About.
        SettingsSection(
            title = "Resources",
            rows = buildList {
                if (capabilities.contains(WearableCapability.BLOOD_PRESSURE) ||
                    capabilities.contains(WearableCapability.BLOOD_SUGAR)
                ) {
                    add(SettingsRowItem(Icons.Filled.Tune, PulseColors.bloodPressure, "Calibration") {
                        navigate("settings/calibration")
                    })
                }
                if (developerUnlocked) {
                    add(SettingsRowItem(Icons.Filled.BugReport, PulseColors.danger, "Developer") {
                        navigate("debug")
                    })
                }
                add(SettingsRowItem(Icons.Filled.Info, PulseColors.textMuted, "About PulseLoop") {
                    navigate("settings/about")
                })
            },
        )

        Spacer(Modifier.height(32.dp))
    }
}
