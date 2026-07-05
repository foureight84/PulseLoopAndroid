package com.pulseloop.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.pulseloop.coach.orchestration.CoachOrchestrator
import com.pulseloop.coach.openai.OpenAIResponsesClient
import com.pulseloop.coach.tools.*
import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.ring.RingBLEClient
import com.pulseloop.service.*
import com.pulseloop.coach.summaries.CoachSummaryCoordinator
import com.pulseloop.settings.ApiKeyStore
import com.pulseloop.ui.screens.*
import com.pulseloop.ui.theme.PulseLoopTheme
import com.pulseloop.ui.viewmodels.*

/**
 * Root composable — ported from PulseLoopApp.swift + RootViews.swift.
 * Wires BLE, persistence, coach, and all ViewModels at app startup.
 */
@Composable
fun PulseLoopApp() {
    PulseLoopTheme {
        val context = LocalContext.current

        // ── Singletons ───────────────────────────────────────────────────
        val db = remember { PulseLoopDatabase.getInstance(context) }
        val bleClient = remember { RingBLEClient(context) }
        val apiKeyStore = remember { ApiKeyStore(context) }
        val coordinator = remember { RingSyncCoordinator(bleClient, db, apiKeyStore) }
        val gpsRecorder = remember { GpsRouteRecorder(context) }
        val liveWorkout = remember { LiveWorkoutManager(coordinator, db, gpsRecorder, context) }
        val persistence = remember { EventPersistenceSubscriber(db) }
        val summaryCoordinator = remember { CoachSummaryCoordinator(db, apiKeyStore) }

        // ── Coach wiring ─────────────────────────────────────────────────
        val coachOrchestrator = remember {
            val apiKey = apiKeyStore.apiKey
            val flags = CoachFeatureFlags(
                coachEnabled = apiKeyStore.coachEnabled && apiKey.isNotEmpty(),
                webSearchEnabled = apiKeyStore.webSearchEnabled,
                writeToolsEnabled = false,  // safe default
                liveMeasurementsEnabled = true,
                model = apiKeyStore.model.ifEmpty { "gpt-5.4" },
            )
            val client = OpenAIResponsesClient(apiKey)
            val registry = ToolRegistry(flags)
            val toolContext = ToolExecutionContext(
                db = db,
                flags = flags,
                coordinator = coordinator,
            )
            CoachOrchestrator(client, registry, flags, toolContext)
        }

        // ── ViewModels ───────────────────────────────────────────────────
        val todayVM = remember { TodayViewModel(db, apiKeyStore) }
        val vitalsVM = remember { VitalsViewModel(db, apiKeyStore) }
        val sleepVM = remember { SleepViewModel(db) }
        val activityVM = remember { ActivityViewModel(db) }
        val coachVM = remember { CoachViewModel(db, coachOrchestrator) }

        // ── Start services (one-shot on composition) ─────────────────────
        LaunchedEffect(Unit) {
            // Wire onConnected → run the startup/history sync only (matches iOS). Connecting
            // does NOT force a measurement; the ring's own periodic monitoring is pulled in via
            // history, and on-demand readings come from the Vitals "Measure" button.
            bleClient.onConnected = { coordinator.runStartupSequence() }

            // Wire firmware read → persist to DB
            bleClient.onFirmwareRead = { fw ->
                kotlinx.coroutines.runBlocking {
                    val dev = db.deviceDao().current()
                    // Standard DIS (2A26/2A28) firmware is only a fallback. The official app
                    // displays the custom-protocol device-info version ("…V138"), not the DIS
                    // string, so never overwrite a value that already carries the "V" version.
                    if (dev != null && (dev.firmwareVersion.isNullOrBlank() || !dev.firmwareVersion!!.contains("V"))) {
                        db.deviceDao().upsert(dev.copy(firmwareVersion = fw, updatedAt = System.currentTimeMillis()))
                    }
                }
            }

            // Start services
            persistence.start()
            coordinator.start()
            summaryCoordinator.start()

            // Stale-state guard: a persisted "CONNECTED"/"CONNECTING" must not survive a
            // process restart — the live GATT is gone, so the views would otherwise show a
            // false "Connected". Reset until a real connection re-confirms it.
            db.deviceDao().current()?.let { dev ->
                if (dev.stateRaw == "CONNECTED" || dev.stateRaw == "CONNECTING") {
                    db.deviceDao().upsert(dev.copy(stateRaw = "DISCONNECTED", updatedAt = System.currentTimeMillis()))
                }
            }

            // Auto-reconnect to last-known ring if any
            if (bleClient.hasPermissions()) {
                bleClient.connectLastKnown()
            }

            // Schedule periodic background sync (matches official app behavior)
            RingSyncWorker.schedule(context)

            // NOTE: demo data is never auto-seeded. A clean install starts empty so the UI
            // reflects only real ring data. Demo data is seeded exclusively via the
            // confirmation-gated "Reseed Demo Data" button in Settings.
        }

        // ── Auto-reconnect on return to foreground ───────────────────────
        // When the phone wakes from idle, the OS may have silently torn down the GATT
        // (Doze) without autoConnect recovering it. Re-attempt the link on every
        // foreground transition so the user never has to force-close the app to reconnect.
        // The first ON_START is handled by the LaunchedEffect above, so skip it here.
        val lifecycleOwner = LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner) {
            var isFirstStart = true
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_START) {
                    if (isFirstStart) {
                        isFirstStart = false
                    } else {
                        bleClient.reconnectIfNeeded()
                    }
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }

        // ── Navigation ───────────────────────────────────────────────────
        val navController = rememberNavController()
        val tabs = listOf(
            Tab("today", "Today", Icons.Filled.Today, Icons.Outlined.Today),
            Tab("vitals", "Vitals", Icons.Filled.Favorite, Icons.Outlined.FavoriteBorder),
            Tab("sleep", "Sleep", Icons.Filled.Bedtime, Icons.Outlined.Bedtime),
            Tab("activity", "Activity", Icons.Filled.DirectionsRun, Icons.Outlined.DirectionsRun),
            Tab("coach", "Coach", Icons.Filled.AutoAwesome, Icons.Outlined.AutoAwesome),
        )

        // ── Self-update: release-only, throttled to once/day. Surfaces a dialog when a
        // newer GitHub release is published; Settings also has a manual "Check for updates".
        var pendingUpdate by remember { mutableStateOf<com.pulseloop.update.UpdateInfo?>(null) }
        LaunchedEffect(Unit) {
            pendingUpdate = (com.pulseloop.update.UpdateChecker.check(context)
                as? com.pulseloop.update.UpdateCheckResult.UpdateAvailable)?.info
        }

        Scaffold(
            bottomBar = {
                NavigationBar {
                    val navBackStackEntry by navController.currentBackStackEntryAsState()
                    val currentDestination = navBackStackEntry?.destination
                    tabs.forEach { tab ->
                        val selected = currentDestination?.hierarchy?.any { it.route == tab.route } == true
                        NavigationBarItem(
                            icon = {
                                Icon(
                                    if (selected) tab.selectedIcon else tab.unselectedIcon,
                                    contentDescription = tab.label,
                                )
                            },
                            label = { Text(tab.label) },
                            selected = selected,
                            onClick = {
                                if (selected) return@NavigationBarItem
                                // Pop everything above the start destination but keep it.
                                // launchSingleTop jumps back to the existing tab instance.
                                navController.navigate(tab.route) {
                                    popUpTo(navController.graph.startDestinationId)
                                    launchSingleTop = true
                                }
                            },
                        )
                    }
                }
            },
        ) { padding ->
            NavHost(
                navController = navController,
                startDestination = "today",
                modifier = Modifier.padding(padding),
            ) {
                composable("today") { TodayScreen(navController, todayVM, coordinator) }
                composable("vitals") { VitalsScreen(navController = navController, viewModel = vitalsVM, coordinator = coordinator) }
                composable("sleep") { SleepScreen(navController = navController, viewModel = sleepVM) }
                composable("activity") { ActivityScreen(navController = navController, viewModel = activityVM) }
                composable("coach") { CoachScreen(navController = navController, viewModel = coachVM) }
                composable("settings") { SettingsScreen(navController, bleClient, coordinator) }
                composable("debug") { DebugScreen(onBack = { navController.popBackStack() }) }
                composable("vitals/{metric}") { backStackEntry ->
                    val metric = backStackEntry.arguments?.getString("metric") ?: return@composable
                    VitalDetailScreen(
                        metric = metric,
                        onBack = { navController.popBackStack() },
                        db = db,
                        apiKeyStore = apiKeyStore,
                    )
                }
                composable("onboarding") { OnboardingScreen(onComplete = { navController.navigate("pairing") }) }
                composable("record") {
                    val workoutState = liveWorkout.state.collectAsState().value
                    RecordScreen(
                        activityName = workoutState.activeSession?.type ?: "Workout",
                        elapsedSeconds = workoutState.elapsedSeconds,
                        distanceMeters = workoutState.distanceMeters,
                        heartRate = workoutState.latestHeartRate,
                        spO2 = workoutState.latestSpO2,
                        isPaused = workoutState.isPaused,
                        hrZone = workoutState.hrZone,
                        onPause = {
                            workoutState.activeSession?.let { kotlinx.coroutines.runBlocking { liveWorkout.pause(it) } }
                        },
                        onResume = {
                            workoutState.activeSession?.let { kotlinx.coroutines.runBlocking { liveWorkout.resume(it) } }
                        },
                        onFinish = {
                            workoutState.activeSession?.let {
                                kotlinx.coroutines.runBlocking { liveWorkout.finish(it) }
                                navController.popBackStack()
                            }
                        },
                    )
                }
                composable("pairing") {
                    PairingScreen(
                        bleClient = bleClient,
                        onConnected = { navController.popBackStack() },
                    )
                }
            }
        }

        pendingUpdate?.let { info ->
            com.pulseloop.update.UpdateDialog(info) { pendingUpdate = null }
        }
    }
}

private data class Tab(
    val route: String,
    val label: String,
    val selectedIcon: androidx.compose.ui.graphics.vector.ImageVector,
    val unselectedIcon: androidx.compose.ui.graphics.vector.ImageVector,
)
