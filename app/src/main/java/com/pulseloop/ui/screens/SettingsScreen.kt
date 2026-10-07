package com.pulseloop.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
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
import android.widget.Toast
import com.pulseloop.coach.config.CoachProviderMode
import com.pulseloop.coach.config.CoachProviderSettingsStore
import com.pulseloop.health.HealthConnectAvailability
import com.pulseloop.health.HealthConnectSdk
import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.ring.WearableCapability
import com.pulseloop.settings.ApiKeyStore
import com.pulseloop.settings.StepSourcePrefs
import com.pulseloop.ui.components.DeviceHeroCard
import com.pulseloop.ui.components.SettingsRowItem
import com.pulseloop.ui.components.SettingsSection
import com.pulseloop.ui.theme.PulseColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    val stepSourcePrefs = remember { StepSourcePrefs(context) }
    val providerStore = remember { CoachProviderSettingsStore(context) }
    val db = remember { PulseLoopDatabase.getInstance(context) }
    val scope = rememberCoroutineScope()

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
    var stepSource by remember { mutableStateOf(stepSourcePrefs.stepSource) }

    // In-flight "switch to Phone" attempt. Cancelled before each new attempt so a
    // rapid Ring → Phone → Ring toggle cannot have an older attempt's async HC
    // checks land after the user's latest intent and re-persist "phone".
    var switchJob by remember { mutableStateOf<Job?>(null) }

    // Provider-aware AI Coach summary — mirrors iOS `coachTrailing` (no Apple on-device
    // mode on Android; hosted providers show the selected model slug).
    val coachTrailing = if (!coachEnabled) "Off" else when (providerStore.providerMode) {
        CoachProviderMode.OFFLINE_STUB -> "Offline"
        CoachProviderMode.USER_GEMINI_KEY -> providerStore.geminiModel
        CoachProviderMode.USER_OPENROUTER_KEY -> providerStore.openRouterModel
        CoachProviderMode.USER_MINIMAX_KEY -> providerStore.minimaxModel
        // Local: the model name, or just "Local" for a server (llama.cpp) that ignores the field.
        CoachProviderMode.LOCAL_OPENAI_COMPAT ->
            providerStore.localModel.ifBlank { "Local" }
        CoachProviderMode.BACKEND_PROXY -> "Backend proxy"
        else -> keyStore.model
    }
    val notificationsTrailing = if (keyStore.notificationsEnabled) "On" else "Off"

    // The Health Connect READ_STEPS permission is requested only from the Step Source
    // row below, on the user's explicit action. MainActivity deliberately does not
    // prompt for it on launch or on foreground return — this is the single entry
    // point, so a user who declines is not asked again until they touch the toggle
    // once more. If the dialog is declined, the toggle reverts to "Ring" so the UI
    // never claims a source the app cannot actually read from.
    val healthConnectPermissionLauncher = rememberLauncherForActivityResult(
        androidx.health.connect.client.PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        if (com.pulseloop.PhoneStepManager.READ_STEPS_PERMISSION in granted) {
            // Granted — now it is safe to persist "phone" and run the backfill. A6: the
            // preference is written only after permission is confirmed, so an interrupted
            // flow cannot leave the app claiming a source it can't read from.
            stepSourcePrefs.stepSource = StepSourcePrefs.SOURCE_PHONE
            scope.launch(Dispatchers.IO) {
                try {
                    com.pulseloop.PhoneStepManager(context).refreshHistoricalDays()
                } catch (_: Exception) {
                    // A failed backfill just leaves the ring's data in place for
                    // historical days. Nothing user-visible to report.
                }
            }
        } else {
            // Declined. Do NOT persist "phone". Revert the UI toggle so it doesn't claim
            // a source the app cannot read from.
            stepSource = StepSourcePrefs.SOURCE_RING
        }
    }

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
            // userConnect(), not connectLastKnown(): a manual Disconnect persists a stay-off flag
            // that connectLastKnown() honours by returning immediately, so this button did nothing
            // at all in exactly the situation it exists for (issue #72). userConnect clears the
            // flag first — the user tapping Connect *is* the intent the flag was waiting for.
            onConnect = { bleClient?.userConnect() },
            onDisconnect = { bleClient?.userDisconnect() },
            onSetUp = { navigate("pairing") },
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

        // GENERAL — Physiology sits under User Profile (iOS SettingsView General group), feeding the
        // optional inputs that tune the vitals reference ranges. Measurement Frequency sits under
        // Physiology — only rings that expose a configurable measurement interval (Colmi) declare
        // MEASUREMENT_INTERVAL, so the generic 56ff jring never shows this row (iOS #74: dropped the
        // old ring-only "Device" section for this since the row had no other members).
        SettingsSection(
            title = "General",
            rows = buildList {
                add(SettingsRowItem(Icons.Filled.AccountCircle, PulseColors.accent, "User Profile") {
                    navigate("settings/profile")
                })
                add(SettingsRowItem(Icons.Filled.MonitorHeart, PulseColors.hrv, "Physiology") {
                    navigate("settings/physiology")
                })
                if (capabilities.contains(WearableCapability.MEASUREMENT_INTERVAL)) {
                    add(SettingsRowItem(Icons.Filled.Timer, PulseColors.spo2, "Measurement Frequency") {
                        navigate("settings/measurement")
                    })
                }
            },
        )

        // METRICS
        SettingsSection(
            title = "Metrics",
            rows = listOf(
                SettingsRowItem(Icons.Filled.TrackChanges, PulseColors.readiness, "Goals") {
                    navigate("settings/goals")
                },
                SettingsRowItem(Icons.Filled.RestaurantMenu, PulseColors.calories, "Nutrition") {
                    navigate("settings/nutrition")
                },
            ),
        )

        // STEP SOURCE — switching to "Phone" backfills the last 30 days of step totals
        // from Health Connect, so past days show the phone's numbers rather than the
        // ring's. The permission for Health Connect READ_STEPS is requested here, on the
        // user's explicit action, and only when it isn't already granted. The backfill
        // itself is fire-and-forget: the persistence layer receives PhoneStepsUpdate
        // events and writes each day's row; the Activity screen's Flows pick up the new
        // rows automatically.
        SettingsSection(
            title = "Step Source",
            rows = listOf(
                SettingsRowItem(
                    icon = Icons.Filled.Timeline,
                    tint = PulseColors.accent,
                    title = "Data Source",
                    trailingValue = if (stepSource == StepSourcePrefs.SOURCE_PHONE) "Phone" else "Ring"
                ) {
                    val newSource = if (stepSource == StepSourcePrefs.SOURCE_RING) StepSourcePrefs.SOURCE_PHONE else StepSourcePrefs.SOURCE_RING
                    stepSource = newSource  // optimistic UI; persisted only once proven valid
                    // Cancel any previous in-flight attempt: the user's latest tap is the
                    // only intent that matters, and a stale attempt must not persist
                    // "phone" after the user has since flipped back to Ring.
                    switchJob?.cancel()
                    if (newSource == StepSourcePrefs.SOURCE_RING) {
                        // Ring is always safe — no external dependency to verify.
                        stepSourcePrefs.stepSource = StepSourcePrefs.SOURCE_RING
                    } else {
                        // Switching to Phone. A6: verify HC availability and permission
                        // BEFORE persisting. The old flow saved "phone" first and reverted
                        // on failure, so an interrupted run left the app claiming a source
                        // it couldn't read from, which then dropped the ring's live totals.
                        switchJob = scope.launch {
                            val availability = withContext(Dispatchers.IO) {
                                HealthConnectSdk.availability(context)
                            }
                            if (availability != HealthConnectAvailability.AVAILABLE) {
                                Toast.makeText(
                                    context,
                                    when (availability) {
                                        HealthConnectAvailability.PROVIDER_UPDATE_REQUIRED ->
                                            "Health Connect needs an update before phone steps can be used."
                                        else ->
                                            "Health Connect isn't available on this device."
                                    },
                                    Toast.LENGTH_LONG,
                                ).show()
                                stepSource = StepSourcePrefs.SOURCE_RING // revert the optimistic flip
                                return@launch
                            }
                            val hasPermission = withContext(Dispatchers.IO) {
                                com.pulseloop.PhoneStepManager.hasStepsPermission(context)
                            }
                            if (hasPermission) {
                                // Re-check intent immediately before persisting: the user
                                // may have flipped back to Ring while the permission query
                                // was in flight, and cancellation is cooperative — this
                                // coroutine can reach here with the cancel flag set.
                                if (stepSource != StepSourcePrefs.SOURCE_PHONE) return@launch
                                // Already granted — persist and backfill now.
                                stepSourcePrefs.stepSource = StepSourcePrefs.SOURCE_PHONE
                                withContext(Dispatchers.IO) {
                                    try {
                                        com.pulseloop.PhoneStepManager(context).refreshHistoricalDays()
                                    } catch (_: Exception) {
                                        // A failed backfill just leaves the ring's data
                                        // in place for historical days.
                                    }
                                }
                            } else {
                                // Not granted — ask. The launcher's callback persists
                                // "phone" only once permission is confirmed.
                                healthConnectPermissionLauncher.launch(
                                    setOf(com.pulseloop.PhoneStepManager.READ_STEPS_PERMISSION)
                                )
                            }
                        }
                    }
                }
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
                add(SettingsRowItem(Icons.Filled.Shield, PulseColors.success, "Privacy & Data") {
                    navigate("settings/privacy")
                })
                add(SettingsRowItem(Icons.Filled.TrendingUp, PulseColors.calories, "Strava") {
                    navigate("settings/strava")
                })
                add(SettingsRowItem(Icons.Filled.Sync, PulseColors.success, "Health Connect") {
                    navigate("settings/health-connect")
                })
                add(SettingsRowItem(Icons.Filled.Info, PulseColors.textMuted, "About PulseLoop") {
                    navigate("settings/about")
                })
            },
        )

        Spacer(Modifier.height(32.dp))
    }
}
