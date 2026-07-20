package com.pulseloop.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
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
import com.pulseloop.ui.components.AppHeader
import com.pulseloop.ui.screens.*
import com.pulseloop.ui.theme.PulseLoopTheme
import com.pulseloop.ui.viewmodels.*
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import dev.chrisbanes.haze.rememberHazeState

/** Grace period after the app is backgrounded before the live ring link is dropped for battery.
 *  Long enough that a quick app-switch (glance at a notification) doesn't thrash the connection. */
private const val BACKGROUND_DISCONNECT_GRACE_MS = 45_000L

/**
 * Root composable — ported from PulseLoopApp.swift + RootViews.swift.
 * Wires BLE, persistence, coach, and all ViewModels at app startup.
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun PulseLoopApp() {
    PulseLoopTheme {
        val context = LocalContext.current

        // ── Singletons ───────────────────────────────────────────────────
        val db = remember { PulseLoopDatabase.getInstance(context) }
        val bleClient = remember { RingBLEClient(context) }
        val apiKeyStore = remember { ApiKeyStore(context) }
        val coordinator = remember { RingSyncCoordinator(bleClient, db, apiKeyStore) }
        val gpsRecorder = remember { GpsRouteRecorder(context, db) }
        val liveWorkout = remember { LiveWorkoutManager(coordinator, db, gpsRecorder, context) }
        val persistence = remember {
            // Every persisted ring-sync batch republishes the widget snapshot (debounced 2 s),
            // mirroring the iOS PulseDataChange → WidgetSnapshotPublisher pipeline.
            EventPersistenceSubscriber(db) {
                com.pulseloop.widgets.WidgetSnapshotPublisher.publishDebounced(context)
            }
        }
        val batteryAlerts = remember { com.pulseloop.service.BatteryAlertMonitor(context) }
        val providerStore = remember { com.pulseloop.coach.config.CoachProviderSettingsStore(context) }
        val summaryCoordinator = remember { CoachSummaryCoordinator(db, apiKeyStore, providerStore) }

        // ── Coach wiring ─────────────────────────────────────────────────
        // Both the client AND the feature flags are resolved per turn through
        // CoachClientResolver (OpenAI / Gemini / OpenRouter per the provider
        // settings), so key/model/provider changes take effect on the next turn
        // without rebuilding the orchestrator — a frozen flags snapshot would
        // keep coachEnabled=false after a key is pasted, or send a stale model
        // slug to a newly selected provider, until process restart.
        val coachOrchestrator = remember {
            CoachOrchestrator(
                com.pulseloop.coach.config.CoachClientResolver.clientFactory(providerStore, apiKeyStore),
                flagsProvider = {
                    val resolution = com.pulseloop.coach.config.CoachClientResolver.resolve(providerStore, apiKeyStore)
                    val providerSettings = providerStore.snapshot()
                    CoachFeatureFlags(
                        coachEnabled = apiKeyStore.coachEnabled && resolution.key != null,
                        webSearchEnabled = apiKeyStore.webSearchEnabled,
                        writeToolsEnabled = false,  // safe default
                        liveMeasurementsEnabled = true,
                        model = com.pulseloop.coach.config.CoachClientResolver.activeModel(
                            providerSettings, apiKeyStore.model,
                        ),
                        settings = com.pulseloop.coach.config.CoachClientResolver.coachSettings(providerSettings),
                        providerMode = providerSettings.providerMode,
                    )
                },
                toolContextFactory = { flags ->
                    ToolExecutionContext(
                        db = db,
                        flags = flags,
                        coordinator = coordinator,
                    )
                },
            )
        }

        // ── ViewModels ───────────────────────────────────────────────────
        val todayVM = remember { TodayViewModel(db, apiKeyStore) }
        val vitalsVM = remember { VitalsViewModel(db, apiKeyStore) }
        val sleepVM = remember { SleepViewModel(db) }
        val activityVM = remember { ActivityViewModel(db) }
        val weatherContextService = remember { com.pulseloop.coach.context.WeatherContextService(context) }
        val coachVM = remember {
            CoachViewModel(
                db, coachOrchestrator,
                attachmentPayloads = { refs ->
                    com.pulseloop.coach.attachments.CoachAttachmentStore.payloads(context, refs)
                },
                environmentSnapshot = { weatherContextService.snapshot() },
            )
        }

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
            batteryAlerts.start()
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

        // ── Connection lifecycle on foreground/background ────────────────
        // ON_START: re-attempt the link. The OS may have torn the GATT down during Doze
        // (autoConnect=false won't recover it), so reconnect on every foreground so the user
        // never has to force-close to reconnect. The first ON_START is handled by the
        // LaunchedEffect above, so skip it here.
        // ON_STOP: drop the live link a short grace period after backgrounding so the ring can
        // sleep (a held-open link keeps the ~17mAh ring awake and blocks its advertising). This
        // is a TRANSIENT disconnect — it does NOT set the user stay-off flag, so ON_START silently
        // reconnects; the 30-min RingSyncWorker covers data while backgrounded. Skipped entirely
        // while a workout is in progress, since that needs the live stream. The grace period is
        // cancelled if the app returns to the foreground first, so quick app-switches don't thrash.
        val lifecycleOwner = LocalLifecycleOwner.current
        val bgDisconnectScope = rememberCoroutineScope()
        DisposableEffect(lifecycleOwner) {
            var isFirstStart = true
            var bgDisconnectJob: Job? = null
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> {
                        bgDisconnectJob?.cancel(); bgDisconnectJob = null
                        if (isFirstStart) isFirstStart = false else bleClient.reconnectIfNeeded()
                    }
                    Lifecycle.Event.ON_STOP -> {
                        if (liveWorkout.state.value.activeSession != null) return@LifecycleEventObserver
                        bgDisconnectJob?.cancel()
                        // Default dispatcher so the timer fires while backgrounded (a UI-frame
                        // clock would stall until the next foreground frame).
                        bgDisconnectJob = bgDisconnectScope.launch(Dispatchers.Default) {
                            delay(BACKGROUND_DISCONNECT_GRACE_MS)
                            if (liveWorkout.state.value.activeSession == null) bleClient.disconnect()
                        }
                    }
                    else -> {}
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose {
                bgDisconnectJob?.cancel()
                lifecycleOwner.lifecycle.removeObserver(observer)
            }
        }

        // ── Navigation ───────────────────────────────────────────────────
        // Tab order + icons mirror iOS MainTab (AppTheme.swift): Today (circle.circle),
        // Vitals (heart), Activity (waveform.path.ecg), Sleep (moon), Coach (sparkles).
        val navController = rememberNavController()

        // First-launch gate (iOS #48): show the onboarding flow until it's completed.
        // Existing installs that already have a completed profile or a paired ring are
        // marked complete silently so an app update never re-onboards them.
        LaunchedEffect(Unit) {
            if (!apiKeyStore.onboardingCompleted) {
                val profileDone = db.userProfileDao().get()?.onboardingCompleted == true
                val hasRing = db.deviceDao().current() != null || bleClient.hasLastKnownRing
                if (profileDone || hasRing) {
                    apiKeyStore.onboardingCompleted = true
                } else {
                    navController.navigate("onboarding") { launchSingleTop = true }
                }
            }
        }

        val tabs = listOf(
            Tab("today", "Today", Icons.Filled.Adjust, Icons.Outlined.Adjust),
            Tab("vitals", "Vitals", Icons.Filled.Favorite, Icons.Outlined.FavoriteBorder),
            Tab("activity", "Activity", Icons.Filled.MonitorHeart, Icons.Outlined.MonitorHeart),
            Tab("sleep", "Sleep", Icons.Filled.Bedtime, Icons.Outlined.Bedtime),
            Tab("coach", "Coach", Icons.Filled.AutoAwesome, Icons.Outlined.AutoAwesome),
        )

        // ── Self-update: release-only. Checked on every return to foreground (ON_START also
        // fires on cold start — lifecycle observers replay up to the current state), so a
        // long-running app notices a new release without a force-close. UpdateChecker's
        // 15-min throttle + ETag caching bound the GitHub traffic; remembering the dismissed
        // versionCode keeps the frequent checks from re-nagging about the same release within
        // a session. Settings also has a manual "Check for updates".
        var pendingUpdate by remember { mutableStateOf<com.pulseloop.update.UpdateInfo?>(null) }
        // rememberSaveable so a dismissed release stays dismissed across config changes
        // (rotation) — plain remember would reset and let the throttle-expired foreground
        // check re-nag about the same version.
        var dismissedUpdateCode by rememberSaveable { mutableStateOf<Int?>(null) }
        val updateScope = rememberCoroutineScope()
        val updateLifecycleOwner = LocalLifecycleOwner.current
        DisposableEffect(updateLifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_START) {
                    updateScope.launch {
                        val info = (com.pulseloop.update.UpdateChecker.check(context)
                            as? com.pulseloop.update.UpdateCheckResult.UpdateAvailable)?.info
                        if (info != null && info.versionCode != dismissedUpdateCode) {
                            pendingUpdate = info
                        }
                    }
                }
            }
            updateLifecycleOwner.lifecycle.addObserver(observer)
            onDispose { updateLifecycleOwner.lifecycle.removeObserver(observer) }
        }

        val isLandscape =
            LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
        val tabRoutes = remember(tabs) { tabs.map { it.route }.toSet() }

        // iOS-style frosted tab bar (.ultraThinMaterial parity): the scrollable tabs
        // render UNDER the bar (hazeSource on the NavHost) and the bar blurs whatever
        // is behind it. Real RenderEffect blur on Android 12+, scrim fallback below.
        // Custom style instead of HazeMaterials.ultraThin: the material's tint over
        // the near-black palette reads almost opaque; a lighter tint + a faint white
        // lift keeps the blurred content visible like the iOS bar.
        val hazeState = rememberHazeState()
        val glassStyle = dev.chrisbanes.haze.HazeStyle(
            backgroundColor = com.pulseloop.ui.theme.PulseColors.background,
            tints = listOf(
                dev.chrisbanes.haze.HazeTint(com.pulseloop.ui.theme.PulseColors.background.copy(alpha = 0.42f)),
                dev.chrisbanes.haze.HazeTint(Color.White.copy(alpha = 0.04f)),
            ),
            blurRadius = 24.dp,
            noiseFactor = 0.02f,
        )

        Scaffold(
            containerColor = com.pulseloop.ui.theme.PulseColors.background,
            topBar = {
                // Shared iOS-style header (PULSELOOP eyebrow + greeting + status pill) on tab
                // routes only; pushed screens (settings, details, pairing) bring their own bars.
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val route = navBackStackEntry?.destination?.route
                val onTabRoute = route in tabRoutes
                if (onTabRoute && isLandscape) {
                    // Edge-to-edge draws content under the transparent status bar. In landscape the
                    // tabs render no full header, so without a backdrop scrolled content (chat
                    // bubbles, charts) bleeds through the status bar. Keep a status-bar-height glass
                    // strip so the status bar stays legible on every tab — matching the landscape
                    // bottom bar's glass — while content still scrolls under it.
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .hazeEffect(state = hazeState, style = glassStyle)
                            .windowInsetsPadding(WindowInsets.statusBars),
                    )
                }
                if (onTabRoute && !isLandscape) {
                    Column(
                        Modifier
                            // iOS-style scroll-under: blur strongest at the status bar,
                            // fading to transparent at the header's bottom edge.
                            .hazeEffect(state = hazeState, style = glassStyle) {
                                progressive = dev.chrisbanes.haze.HazeProgressive.verticalGradient(
                                    startIntensity = 1f,
                                    endIntensity = 0f,
                                )
                            }
                            .windowInsetsPadding(WindowInsets.statusBars),
                    ) {
                        if (route == "coach") {
                            // Coach keeps its bespoke avatar header, rendered here as the same
                            // frosted glass bar the other tabs use so the chat scrolls under it
                            // (owner choice, issue #22).
                            var showCoachUsage by remember { mutableStateOf(false) }
                            CoachHeader(
                                onNewChat = { coachVM.newConversation() },
                                onOpenUsage = { showCoachUsage = true },
                            )
                            if (showCoachUsage) {
                                var usageData by remember {
                                    mutableStateOf<Pair<com.pulseloop.data.entity.CoachConversationEntity, List<com.pulseloop.data.entity.CoachMessageEntity>>?>(null)
                                }
                                LaunchedEffect(Unit) { usageData = coachVM.usageSnapshot() }
                                usageData?.let { (convo, messages) ->
                                    val providerSettings = providerStore.snapshot()
                                    com.pulseloop.ui.screens.CoachUsageSheet(
                                        conversation = convo,
                                        messages = messages,
                                        fallbackModel = com.pulseloop.coach.config.CoachClientResolver.activeModel(
                                            providerSettings, apiKeyStore.model,
                                        ),
                                        fallbackProviderMode = providerSettings.providerMode,
                                        onDismiss = { showCoachUsage = false },
                                    )
                                }
                            }
                        } else {
                            val ble by bleClient.state.collectAsState()
                            val storedDevice by db.deviceDao().currentFlow()
                                .collectAsState(initial = null)
                            // Prefer live BLE state; fall back to the stored device so demo data
                            // shows its ring instead of a permanently blank pill (RootViews.swift).
                            val liveActive = ble.connectionState in setOf(
                                com.pulseloop.ring.RingConnectionState.CONNECTED,
                                com.pulseloop.ring.RingConnectionState.CONNECTING,
                                com.pulseloop.ring.RingConnectionState.RECONNECTING,
                                com.pulseloop.ring.RingConnectionState.SCANNING,
                            )
                            AppHeader(
                                state = if (liveActive) ble.connectionState
                                    else storedDevice?.state ?: ble.connectionState,
                                batteryPercent = ble.batteryPercent ?: storedDevice?.batteryPercent,
                                onOpenSettings = { navController.navigate("settings") },
                            )
                            // Thin sync-progress accent under the greeting while history streams in.
                            val syncPct = coordinator.syncProgress.collectAsState().value
                            if (syncPct != null) {
                                LinearProgressIndicator(
                                    progress = { (syncPct.coerceIn(0, 100)) / 100f },
                                    modifier = Modifier.fillMaxWidth().height(2.dp),
                                )
                            }
                        }
                    }
                }
            },
            bottomBar = {
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentDestination = navBackStackEntry?.destination
                fun isSelected(tab: Tab) =
                    currentDestination?.hierarchy?.any { it.route == tab.route } == true
                val onTab: (Tab) -> Unit = { tab ->
                    // Pop everything above the start destination but keep it;
                    // launchSingleTop jumps back to the existing tab instance.
                    if (!isSelected(tab)) navController.navigate(tab.route) {
                        popUpTo(navController.graph.startDestinationId)
                        launchSingleTop = true
                    }
                }
                if (currentDestination?.route == "onboarding") {
                    // The onboarding flow is full-screen chrome of its own — no tab bar.
                } else if (isLandscape) {
                    // Landscape: a compact icon-only bar at ~half the standard height,
                    // so the short viewport keeps more room for content.
                    Surface(
                        color = Color.Transparent,
                        modifier = Modifier.hazeEffect(state = hazeState, style = glassStyle),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .windowInsetsPadding(NavigationBarDefaults.windowInsets)
                                .height(48.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            tabs.forEach { tab ->
                                val selected = isSelected(tab)
                                IconButton(onClick = { onTab(tab) }) {
                                    Icon(
                                        if (selected) tab.selectedIcon else tab.unselectedIcon,
                                        contentDescription = tab.label,
                                        tint = if (selected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                } else {
                    Column {
                        HorizontalDivider(thickness = 1.dp, color = com.pulseloop.ui.theme.PulseColors.borderSubtle)
                        NavigationBar(
                            containerColor = Color.Transparent,
                            modifier = Modifier.hazeEffect(state = hazeState, style = glassStyle),
                        ) {
                            tabs.forEach { tab ->
                                val selected = isSelected(tab)
                                NavigationBarItem(
                                    icon = {
                                        Icon(
                                            if (selected) tab.selectedIcon else tab.unselectedIcon,
                                            contentDescription = tab.label,
                                        )
                                    },
                                    label = { Text(tab.label) },
                                    selected = selected,
                                    onClick = { onTab(tab) },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = com.pulseloop.ui.theme.PulseColors.textPrimary,
                                        selectedTextColor = com.pulseloop.ui.theme.PulseColors.textPrimary,
                                        unselectedIconColor = com.pulseloop.ui.theme.PulseColors.textMuted,
                                        unselectedTextColor = com.pulseloop.ui.theme.PulseColors.textMuted,
                                        indicatorColor = com.pulseloop.ui.theme.PulseColors.accentSoft,
                                    ),
                                )
                            }
                        }
                    }
                }
            },
        ) { padding ->
            // The four scrollable tabs draw UNDER the glass top/bottom bars (they get the
            // bar heights as extra content padding instead); every other route keeps the
            // bars as an opaque floor/ceiling via this per-route padding wrapper.
            val topPad = padding.calculateTopPadding()
            val barPad = padding.calculateBottomPadding()
            fun androidx.navigation.NavGraphBuilder.paddedComposable(
                route: String,
                content: @Composable (androidx.navigation.NavBackStackEntry) -> Unit,
            ) = composable(route) { entry ->
                Box(Modifier.fillMaxSize().padding(padding)) { content(entry) }
            }
            NavHost(
                navController = navController,
                startDestination = "today",
                modifier = Modifier.hazeSource(hazeState),
            ) {
                composable("today") { TodayScreen(navController, todayVM, coordinator, vitalsVM, sleepVM, topBarPadding = topPad, bottomBarPadding = barPad) }
                composable("vitals") { VitalsScreen(navController = navController, viewModel = vitalsVM, coordinator = coordinator, topBarPadding = topPad, bottomBarPadding = barPad) }
                composable("sleep") { SleepScreen(navController = navController, viewModel = sleepVM, onOpen = { coordinator.syncSleepNow() }, topBarPadding = topPad, bottomBarPadding = barPad) }
                composable("activity") { ActivityScreen(navController = navController, viewModel = activityVM, liveWorkout = liveWorkout, topBarPadding = topPad, bottomBarPadding = barPad) }
                paddedComposable("activity_detail/{id}") { backStackEntry ->
                    val id = backStackEntry.arguments?.getString("id") ?: return@paddedComposable
                    WorkoutSummaryScreen(sessionId = id, onBack = { navController.popBackStack() })
                }
                paddedComposable("log_past_activity") {
                    LogPastActivityScreen(
                        onBack = { navController.popBackStack() },
                        onSaved = { id ->
                            navController.navigate("activity_detail/$id") {
                                popUpTo("activity")
                            }
                        },
                    )
                }
                // Coach draws full-bleed under the glass top/bottom bars (like the other tabs), so
                // the chat frosts under its header and the composer can clear the keyboard itself.
                composable("coach") {
                    CoachScreen(
                        navController = navController,
                        viewModel = coachVM,
                        topBarPadding = topPad,
                        bottomBarPadding = barPad,
                    )
                }
                paddedComposable("settings") { SettingsScreen(navController, bleClient, coordinator) }
                // Settings detail screens (iOS #49): each hub row pushes one of these.
                paddedComposable("settings/wearable") {
                    WearableSettingsScreen(
                        bleClient = bleClient,
                        coordinator = coordinator,
                        onAddRing = { navController.navigate("pairing") },
                        onBack = { navController.popBackStack() },
                    )
                }
                paddedComposable("settings/measurement") {
                    MeasurementSettingsScreen(coordinator, onBack = { navController.popBackStack() })
                }
                paddedComposable("settings/coach") {
                    CoachSettingsScreen(onBack = { navController.popBackStack() })
                }
                paddedComposable("settings/checkins") {
                    CheckInsSettingsScreen(onBack = { navController.popBackStack() })
                }
                paddedComposable("settings/profile") {
                    ProfileSettingsScreen(coordinator, onBack = { navController.popBackStack() })
                }
                paddedComposable("settings/physiology") {
                    PhysiologySettingsScreen(onBack = { navController.popBackStack() })
                }
                paddedComposable("settings/calibration") {
                    CalibrationSettingsScreen(coordinator, onBack = { navController.popBackStack() })
                }
                paddedComposable("settings/goals") {
                    GoalsSettingsScreen(coordinator, onBack = { navController.popBackStack() })
                }
                paddedComposable("settings/privacy") {
                    PrivacyDataSettingsScreen(onBack = { navController.popBackStack() })
                }
                paddedComposable("settings/about") {
                    AboutSettingsScreen(
                        onOpenDebug = { navController.navigate("debug") },
                        onBack = { navController.popBackStack() },
                    )
                }
                paddedComposable("debug") { DebugScreen(onBack = { navController.popBackStack() }) }
                paddedComposable("vitals/{metric}") { backStackEntry ->
                    val metric = backStackEntry.arguments?.getString("metric") ?: return@paddedComposable
                    VitalDetailScreen(
                        metric = metric,
                        onBack = { navController.popBackStack() },
                        db = db,
                        apiKeyStore = apiKeyStore,
                    )
                }
                paddedComposable("onboarding") {
                    // The flow contains its own pairing step (iOS #48), so completion goes
                    // straight to the main app instead of the standalone pairing route.
                    OnboardingScreen(
                        db = db,
                        bleClient = bleClient,
                        apiKeyStore = apiKeyStore,
                        coordinator = coordinator,
                        onFinished = {
                            navController.navigate("today") {
                                popUpTo("onboarding") { inclusive = true }
                            }
                        },
                    )
                }
                paddedComposable("record") {
                    val workoutState = liveWorkout.state.collectAsState().value
                    RecordScreen(
                        activityName = workoutState.activeSession?.type ?: "Workout",
                        elapsedSeconds = workoutState.elapsedSeconds,
                        distanceMeters = workoutState.distanceMeters,
                        heartRate = workoutState.latestHeartRate,
                        spO2 = workoutState.latestSpO2,
                        isPaused = workoutState.isPaused,
                        useGps = workoutState.activeSession?.useGps ?: true,
                        hrZone = workoutState.hrZone,
                        units = apiKeyStore.resolvedUnitSystem,
                        onPause = {
                            workoutState.activeSession?.let { kotlinx.coroutines.runBlocking { liveWorkout.pause(it) } }
                        },
                        onResume = {
                            workoutState.activeSession?.let { kotlinx.coroutines.runBlocking { liveWorkout.resume(it) } }
                        },
                        onFinish = {
                            workoutState.activeSession?.let {
                                kotlinx.coroutines.runBlocking { liveWorkout.finish(it) }
                                // Land on the summary (iOS RecordSummaryView), replacing the live screen.
                                navController.navigate("activity_detail/${it.id}") {
                                    popUpTo("record") { inclusive = true }
                                }
                            }
                        },
                    )
                }
                paddedComposable("pairing") {
                    PairingScreen(
                        bleClient = bleClient,
                        onConnected = { navController.popBackStack() },
                    )
                }
            }
        }

        pendingUpdate?.let { info ->
            com.pulseloop.update.UpdateDialog(info) {
                // Remember what was dismissed: foreground checks now run every ON_START, and
                // without this the same release would re-prompt on every app switch.
                dismissedUpdateCode = info.versionCode
                pendingUpdate = null
            }
        }
    }
}

private data class Tab(
    val route: String,
    val label: String,
    val selectedIcon: androidx.compose.ui.graphics.vector.ImageVector,
    val unselectedIcon: androidx.compose.ui.graphics.vector.ImageVector,
)
