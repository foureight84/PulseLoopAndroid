package com.pulseloop.ui.screens

import com.pulseloop.util.L10n
import androidx.compose.ui.res.pluralStringResource
import com.pulseloop.R
import androidx.compose.ui.res.stringResource
import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulseloop.coach.config.CoachProviderMode
import com.pulseloop.coach.config.CoachProviderSettingsStore
import com.pulseloop.coach.config.GeminiModel
import com.pulseloop.coach.config.LocalStructuredOutput
import com.pulseloop.coach.local.LocalCapabilityProbe
import com.pulseloop.coach.local.LocalEndpoint
import com.pulseloop.coach.config.MiniMaxModel
import com.pulseloop.coach.config.OpenRouterModel
import com.pulseloop.data.DemoDataSeeder
import com.pulseloop.data.PulseLoopDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.pulseloop.data.entity.UserGoalEntity
import com.pulseloop.health.HealthConnectAvailability
import com.pulseloop.health.HealthConnectPermissions
import com.pulseloop.health.HealthConnectPrefs
import com.pulseloop.health.HealthConnectExportWorker
import com.pulseloop.health.HealthConnectPermissionReconcile
import com.pulseloop.health.HealthConnectPrefsStore
import com.pulseloop.health.HealthConnectRemovalWorker
import com.pulseloop.health.HealthConnectWatermarks
import com.pulseloop.health.HealthConnectSdk
import com.pulseloop.notifications.CoachNotifications
import com.pulseloop.ring.MeasurementKind
import com.pulseloop.ring.RingBLEClient
import com.pulseloop.ring.RingConnectionState
import com.pulseloop.ring.RingDeviceType
import com.pulseloop.ring.WearableCapability
import com.pulseloop.service.GlucoseUnit
import com.pulseloop.service.MetricZone
import com.pulseloop.service.RingSyncCoordinator
import com.pulseloop.service.RingSyncWorker
import com.pulseloop.service.ScheduledSpo2Worker
import com.pulseloop.service.VitalColorToken
import com.pulseloop.service.VitalSample
import com.pulseloop.service.ZoneSeverity
import com.pulseloop.settings.ApiKeyStore
import com.pulseloop.settings.QuietHoursPrefs
import com.pulseloop.settings.ScheduledSpo2Prefs
import com.pulseloop.settings.UnitSystem
import com.pulseloop.settings.ZenModeListener
import com.pulseloop.ui.components.DeviceHeroStatus
import com.pulseloop.ui.components.ZoneLineChart
import com.pulseloop.ui.theme.PulseColors
import com.pulseloop.wearables.WearableModel
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Ported from the Views/Settings Swift screens (iOS #49 rehaul): the focused detail screens the new
 * Settings hub pushes. Each screen hosts the card content that previously lived inline on the
 * flat SettingsScreen, relocated 1:1 (internals unchanged except where the iOS diff changes them).
 */

// MARK: - Shared scaffold

/** Pushed-screen chrome shared by every settings detail screen: back arrow + title + scroll column. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSubScreen(
    title: String,
    onBack: () -> Unit,
    bottomOverlay: (@Composable BoxScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        containerColor = PulseColors.background,
        // The NavHost route wrapper (PulseLoopApp's paddedComposable) has ALREADY applied the
        // outer Scaffold's system-bar padding to this whole subtree. Consuming the insets a
        // second time here — once via contentWindowInsets, once via TopAppBar's own default —
        // is what produced the tall dead band above every settings title.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
                    }
                },
                windowInsets = WindowInsets(0, 0, 0, 0),
                colors = TopAppBarDefaults.topAppBarColors(containerColor = PulseColors.background),
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                content()
                Spacer(Modifier.height(24.dp))
            }
            bottomOverlay?.invoke(this)
        }
    }
}

// MARK: - AI Coach

/**
 * AI Coach detail screen: master toggle, provider/model/key configuration, tool toggles, and
 * the coach memory list — the old inline "AI Coach" + "Coach Memory" cards. iOS #49 adds the
 * enable-coach prompt: first enabling the coach offers to turn on daily check-ins (the OS
 * notification permission is requested only on "Enable").
 */
@Composable
fun CoachSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val keyStore = remember { ApiKeyStore(context) }
    val providerStore = remember { CoachProviderSettingsStore(context) }
    val db = remember { PulseLoopDatabase.getInstance(context) }
    val scope = rememberCoroutineScope()

    var apiKey by remember { mutableStateOf(keyStore.apiKey) }
    var apiKeyVisible by remember { mutableStateOf(false) }
    var selectedModel by remember { mutableStateOf(keyStore.model) }
    var coachEnabled by remember { mutableStateOf(keyStore.coachEnabled) }
    var webSearch by remember { mutableStateOf(keyStore.webSearchEnabled) }
    var writeTools by remember { mutableStateOf(keyStore.writeToolsEnabled) }
    var liveMeasurements by remember { mutableStateOf(keyStore.liveMeasurementsEnabled) }
    var environmentContext by remember { mutableStateOf(keyStore.enableEnvironmentContext) }
    var showMemory by remember { mutableStateOf(false) }

    // Location & weather (iOS #65d) — opt-in, requests ACCESS_COARSE_LOCATION only when
    // the user turns it on, mirroring CoachEnvironmentContextService.requestPermissionIfNeeded().
    val environmentPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Granted or not, the toggle stays on — WeatherContextService degrades to city-only
        // (or nothing) without a location fix rather than needing a second opt-out step.
    }
    fun setEnvironmentContext(enabled: Boolean) {
        environmentContext = enabled
        keyStore.enableEnvironmentContext = enabled
        if (enabled) environmentPermLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    // "Enable Coach Check-Ins?" prompt state (iOS #49 CoachSettingsSection).
    var askEnableCheckIns by remember { mutableStateOf(false) }
    var checkInPermissionDenied by remember { mutableStateOf(false) }
    val notifPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        keyStore.notificationsEnabled = granted
        checkInPermissionDenied = !granted
        if (granted) CoachNotifications.schedule(context)
    }
    fun enableCheckIns() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            keyStore.notificationsEnabled = true
            checkInPermissionDenied = false
            CoachNotifications.schedule(context)
        }
    }

    // Coach memories — loaded on composition via LaunchedEffect
    var memories by remember { mutableStateOf(emptyList<com.pulseloop.data.entity.CoachMemoryEntity>()) }
    LaunchedEffect(coachEnabled) {
        if (coachEnabled) {
            memories = db.coachMemoryDao().allRanked()
        }
    }

    // The curated OpenAI picks live in one place, [OpenAIModel]; the retired gpt-4o / gpt-4o-mini
    // / o4-mini are gone from it (issue #77), and a stored one reads as the default.
    val models = com.pulseloop.coach.config.OpenAIModel.entries.map { it.slug to it.blurb }

    SettingsSubScreen(title = stringResource(R.string.coach_title), onBack = onBack) {
        // AI Coach section — ported from CoachSettingsSection.swift
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(stringResource(R.string.coach_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                // Readiness differs by provider: the local mode is gated by the server address,
                // not a key, so the old key-only check told a correctly-configured user their
                // "API key" was missing.
                val isLocal = providerStore.providerMode == CoachProviderMode.LOCAL_OPENAI_COMPAT
                val ready = if (isLocal) providerStore.hasLocalBaseUrl else keyStore.apiKey.isNotBlank()
                val activeModel = if (isLocal) providerStore.localModel.ifBlank { context.getString(R.string.coach_local_server) }
                    else selectedModel
                Text(
                    if (coachEnabled && ready) stringResource(R.string.coach_status_active, activeModel)
                    else if (coachEnabled && isLocal) stringResource(R.string.coach_status_server_needed)
                    else if (coachEnabled) stringResource(R.string.coach_status_key_needed)
                    else stringResource(R.string.status_disabled),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))

                // Master toggle
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.coach_enable))
                    Switch(checked = coachEnabled, onCheckedChange = { enabled ->
                        coachEnabled = enabled; keyStore.coachEnabled = enabled
                        if (!enabled) {
                            CoachNotifications.cancel(context)
                        } else if (keyStore.notificationsEnabled) {
                            CoachNotifications.schedule(context)
                        } else {
                            // First time on: ask before enabling check-ins (OS permission only
                            // on "Enable") — iOS #49.
                            askEnableCheckIns = true
                        }
                    })
                }

                if (checkInPermissionDenied) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.coach_notifications_off),
                        style = MaterialTheme.typography.bodySmall,
                        color = PulseColors.danger,
                    )
                }

                if (coachEnabled) {
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))

                    // Provider picker — ported from CoachSettingsSection.swift (iOS #9/#22).
                    // Key/model/provider changes apply from the next message (the orchestrator
                    // resolves a fresh client per turn).
                    var providerMode by remember { mutableStateOf(providerStore.providerMode) }
                    var geminiKey by remember { mutableStateOf(providerStore.geminiApiKey) }
                    var geminiKeyVisible by remember { mutableStateOf(false) }
                    var orKey by remember { mutableStateOf(providerStore.openRouterApiKey) }
                    var orKeyVisible by remember { mutableStateOf(false) }
                    var minimaxKey by remember { mutableStateOf(providerStore.minimaxApiKey) }
                    var minimaxKeyVisible by remember { mutableStateOf(false) }
                    var geminiModel by remember { mutableStateOf(providerStore.geminiModel) }
                    var orModel by remember { mutableStateOf(providerStore.openRouterModel) }
                    var minimaxModel by remember { mutableStateOf(providerStore.minimaxModel) }
                    var orPrivacy by remember { mutableStateOf(providerStore.orPrivacyRouting) }
                    var orSort by remember { mutableStateOf(providerStore.orProviderSort) }
                    var reasoningEffort by remember { mutableStateOf(providerStore.reasoningEffort) }
                    // Bumped on every key save/remove so the model picker refetches the provider's
                    // list with the new key; the key fields' own state changes per keystroke.
                    var keySaves by remember { mutableIntStateOf(0) }
                    // The provider's live model list (CoachModelCatalog), for the Reasoning picker.
                    var liveListing by remember { mutableStateOf<com.pulseloop.coach.config.CoachModelCatalog.Result.Success?>(null) }
                    var imageInput by remember { mutableStateOf(providerStore.imageInputEnabled) }
                    // Local / self-hosted (docs/local-llm-coach.md).
                    var localBaseUrl by remember { mutableStateOf(providerStore.localBaseUrl) }
                    var localKey by remember { mutableStateOf(providerStore.localApiKey) }
                    var localKeyVisible by remember { mutableStateOf(false) }
                    var localModel by remember { mutableStateOf(providerStore.localModel) }
                    var localToolCalling by remember { mutableStateOf(providerStore.localToolCalling) }
                    var localStructured by remember { mutableStateOf(providerStore.localStructuredOutput) }
                    var localMaxTokens by remember { mutableStateOf(providerStore.localMaxTokens.takeIf { it > 0 }?.toString() ?: "") }
                    var localTimeout by remember { mutableStateOf(providerStore.localTimeoutSeconds.toString()) }
                    // Discovered via GET /v1/models; advisory, the typed slug always wins.
                    var localDiscovered by remember { mutableStateOf<List<String>>(emptyList()) }
                    var localProbeBusy by remember { mutableStateOf(false) }
                    var localProbeResult by remember { mutableStateOf<String?>(null) }
                    var localProbeNotes by remember { mutableStateOf<List<String>>(emptyList()) }
                    var localProbeOk by remember { mutableStateOf(false) }

                    val providerOptions = listOf(
                        CoachProviderMode.USER_OPENAI_KEY to "OpenAI",
                        CoachProviderMode.USER_GEMINI_KEY to "Google Gemini",
                        CoachProviderMode.USER_OPENROUTER_KEY to stringResource(R.string.coach_provider_openrouter),
                        CoachProviderMode.USER_MINIMAX_KEY to "MiniMax",
                        CoachProviderMode.LOCAL_OPENAI_COMPAT to stringResource(R.string.coach_provider_local),
                    )
                    val providerLabel = providerOptions.firstOrNull { it.first == providerMode }?.second ?: "OpenAI"

                    Text(stringResource(R.string.coach_provider), style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(4.dp))
                    var providerExpanded by remember { mutableStateOf(false) }
                    Box {
                        OutlinedButton(onClick = { providerExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                            Text(providerLabel, Modifier.weight(1f))
                            Icon(Icons.Filled.ArrowDropDown, null)
                        }
                        DropdownMenu(expanded = providerExpanded, onDismissRequest = { providerExpanded = false }) {
                            providerOptions.forEach { (mode, label) ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = { providerMode = mode; providerStore.providerMode = mode; providerExpanded = false },
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    // Reusable API-key field with visibility toggle + save/remove, matching the
                    // original OpenAI key field (iOS CoachSettingsSection keyField).
                    @Composable
                    fun KeyField(
                        label: String, value: String, visible: Boolean, saved: Boolean,
                        onValue: (String) -> Unit, onVisibility: () -> Unit, onSave: () -> Unit, onRemove: () -> Unit,
                    ) {
                        // Saving an API key gave no feedback (issue #22). Flash a brief "Saved ✓"
                        // confirmation after a save from either the button or the keyboard's Done.
                        var justSaved by remember { mutableStateOf(false) }
                        LaunchedEffect(justSaved) {
                            if (justSaved) { kotlinx.coroutines.delay(2000); justSaved = false }
                        }
                        val save = { onSave(); justSaved = true }
                        OutlinedTextField(
                            value = value,
                            onValueChange = onValue,
                            label = { Text(label) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            visualTransformation = if (visible) androidx.compose.ui.text.input.VisualTransformation.None
                                else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                            trailingIcon = {
                                IconButton(onClick = onVisibility) {
                                    Icon(
                                        if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                        contentDescription = stringResource(R.string.action_toggle_visibility)
                                    )
                                }
                            },
                            keyboardActions = KeyboardActions(onDone = { if (value.isNotBlank()) save() }),
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Button(onClick = save, enabled = value.isNotBlank(), modifier = Modifier.weight(1f)) {
                                Text(if (saved) stringResource(R.string.coach_key_update) else stringResource(R.string.coach_key_save))
                            }
                            if (saved) {
                                OutlinedButton(onClick = onRemove, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.action_remove)) }
                            }
                        }
                        if (justSaved) {
                            Text(
                                stringResource(R.string.status_saved_check),
                                style = MaterialTheme.typography.bodySmall,
                                color = PulseColors.success,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }

                    @Composable
                    fun ModelDropdown(label: String, selected: String, options: List<Pair<String, String>>, onPick: (String) -> Unit) {
                        Text(label, style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.height(4.dp))
                        var expanded by remember { mutableStateOf(false) }
                        Box {
                            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                                Text(selected, Modifier.weight(1f))
                                Icon(Icons.Filled.ArrowDropDown, null)
                            }
                            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                options.forEach { (slug, blurb) ->
                                    DropdownMenuItem(
                                        text = { Column { Text(slug); if (blurb.isNotEmpty()) Text(blurb, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } },
                                        onClick = { onPick(slug); expanded = false },
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }

                    when (providerMode) {
                        CoachProviderMode.USER_GEMINI_KEY -> {
                            CoachModelPicker(
                                provider = providerMode, providerName = "Gemini", selected = geminiModel,
                                presets = GeminiModel.entries.map { it.slug to it.blurb },
                                savedKey = keySaves.let { providerStore.geminiApiKey },
                                onPick = { geminiModel = it; providerStore.geminiModel = it },
                                onListing = { liveListing = it },
                            )
                            KeyField(
                                label = stringResource(R.string.coach_key_label, "Gemini"), value = geminiKey, visible = geminiKeyVisible,
                                saved = providerStore.hasGeminiKey,
                                onValue = { geminiKey = it }, onVisibility = { geminiKeyVisible = !geminiKeyVisible },
                                onSave = { providerStore.geminiApiKey = geminiKey; keySaves++ },
                                onRemove = { geminiKey = ""; providerStore.geminiApiKey = ""; keySaves++ },
                            )
                        }
                        CoachProviderMode.USER_OPENROUTER_KEY -> {
                            CoachModelPicker(
                                provider = providerMode, providerName = "OpenRouter", selected = orModel,
                                presets = OpenRouterModel.entries.map { it.slug to it.blurb },
                                savedKey = keySaves.let { providerStore.openRouterApiKey },
                                onPick = { orModel = it; providerStore.openRouterModel = it },
                                onListing = { liveListing = it },
                            )
                            // The OpenRouter catalog is huge and shifts often — any current
                            // vendor/model slug from openrouter.ai/models works here.
                            OutlinedTextField(
                                value = orModel,
                                onValueChange = { orModel = it; providerStore.openRouterModel = it },
                                label = { Text(stringResource(R.string.coach_custom_slug)) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                            )
                            Spacer(Modifier.height(8.dp))
                            KeyField(
                                label = stringResource(R.string.coach_key_label, "OpenRouter"), value = orKey, visible = orKeyVisible,
                                saved = providerStore.hasOpenRouterKey,
                                onValue = { orKey = it }, onVisibility = { orKeyVisible = !orKeyVisible },
                                onSave = { providerStore.openRouterApiKey = orKey; keySaves++ },
                                onRemove = { orKey = ""; providerStore.openRouterApiKey = ""; keySaves++ },
                            )
                            Spacer(Modifier.height(8.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(stringResource(R.string.coach_privacy_routing))
                                    Text(stringResource(R.string.coach_privacy_routing_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Switch(checked = orPrivacy, onCheckedChange = { orPrivacy = it; providerStore.orPrivacyRouting = it })
                            }
                            ModelDropdown(
                                stringResource(R.string.coach_route_by), orSort ?: "auto",
                                listOf("auto" to stringResource(R.string.coach_route_auto), "price" to stringResource(R.string.coach_route_price), "throughput" to stringResource(R.string.coach_route_throughput), "latency" to stringResource(R.string.coach_route_latency)),
                            ) { providerStore.orProviderSort = it.takeIf { s -> s != "auto" }; orSort = providerStore.orProviderSort }
                        }
                        CoachProviderMode.USER_MINIMAX_KEY -> {
                            CoachModelPicker(
                                provider = providerMode, providerName = "MiniMax", selected = minimaxModel,
                                presets = MiniMaxModel.entries.map { it.slug to it.blurb },
                                savedKey = keySaves.let { providerStore.minimaxApiKey },
                                onPick = { minimaxModel = it; providerStore.minimaxModel = it },
                                onListing = { liveListing = it },
                            )
                            KeyField(
                                label = stringResource(R.string.coach_key_label, "MiniMax"), value = minimaxKey, visible = minimaxKeyVisible,
                                saved = providerStore.hasMinimaxKey,
                                onValue = { minimaxKey = it }, onVisibility = { minimaxKeyVisible = !minimaxKeyVisible },
                                onSave = { providerStore.minimaxApiKey = minimaxKey; keySaves++ },
                                onRemove = { minimaxKey = ""; providerStore.minimaxApiKey = ""; keySaves++ },
                            )
                        }
                        CoachProviderMode.LOCAL_OPENAI_COMPAT -> {
                            // Any OpenAI-Chat-Completions-compatible server the user runs: Ollama,
                            // llama.cpp, vLLM, SGLang, LM Studio. No key required — see
                            // docs/local-llm-coach.md. The base URL is what gates readiness.
                            val urlProblem = LocalEndpoint.validate(localBaseUrl)
                                .takeIf { localBaseUrl.isNotBlank() }
                            OutlinedTextField(
                                value = localBaseUrl,
                                onValueChange = {
                                    localBaseUrl = it; providerStore.localBaseUrl = it
                                    localProbeResult = null; localProbeOk = false
                                    localProbeNotes = emptyList(); localDiscovered = emptyList()
                                },
                                label = { Text(stringResource(R.string.coach_server_address)) },
                                placeholder = { Text("http://192.168.1.50:11434") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                isError = urlProblem != null,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                            )
                            Text(
                                urlProblem?.let { LocalEndpoint.message(it) }
                                    ?: stringResource(R.string.coach_default_ports),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (urlProblem != null) MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp),
                            )

                            Spacer(Modifier.height(8.dp))
                            // One press does the whole setup: identifies the engine, lists models,
                            // and probes whether the server actually accepts `tools` and
                            // `response_format` — which depend on launch flags that no metadata
                            // endpoint exposes. The detected values are applied straight to the
                            // controls below, which stay editable.
                            Button(
                                onClick = {
                                    localProbeBusy = true; localProbeResult = null; localProbeNotes = emptyList()
                                    scope.launch {
                                        try {
                                            val report = LocalCapabilityProbe.run(
                                                baseUrl = localBaseUrl,
                                                apiKey = localKey,
                                                currentModel = localModel,
                                            )
                                            localDiscovered = report.models
                                            if (report.suggestedModel.isNotBlank()) {
                                                localModel = report.suggestedModel
                                                providerStore.localModel = localModel
                                            }
                                            // Only a probe that reached a verdict may overwrite
                                            // these. Detect is also how you refresh the model
                                            // list, so it gets pressed on a working setup — and
                                            // the safe defaults behind `suggested*` (tools ON,
                                            // structured OFF) would then undo a deliberate choice
                                            // rather than leave it alone. The notes say what was
                                            // skipped and why.
                                            if (report.toolCallingConclusive) {
                                                localToolCalling = report.suggestedToolCalling
                                                providerStore.localToolCalling = localToolCalling
                                            }
                                            if (report.structuredOutputConclusive) {
                                                localStructured = report.suggestedStructuredOutput
                                                providerStore.localStructuredOutput = localStructured
                                            }
                                            // Derived from the server's context window, minus a
                                            // reserve for the prompt — never a straight copy, or
                                            // `prompt + max_tokens` would exceed the context and
                                            // the server would reject the request. 0 means the
                                            // server reported no context window at all (a thin
                                            // proxy, an engine we can't identify, Ollama when
                                            // `/api/show` doesn't parse) — "not detected", which
                                            // is no reason to clear a value the user typed.
                                            if (report.suggestedMaxTokens > 0) {
                                                localMaxTokens = report.suggestedMaxTokens.toString()
                                                providerStore.localMaxTokens = report.suggestedMaxTokens
                                            }
                                            localProbeOk = true
                                            localProbeResult = report.summary
                                            localProbeNotes = report.notes
                                        } catch (e: LocalCapabilityProbe.Unreachable) {
                                            localProbeOk = false; localProbeResult = e.reason
                                        } catch (e: Exception) {
                                            localProbeOk = false
                                            localProbeResult = e.message ?: context.getString(R.string.coach_server_unreachable)
                                        }
                                        localProbeBusy = false
                                    }
                                },
                                enabled = localBaseUrl.isNotBlank() && urlProblem == null && !localProbeBusy,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(if (localProbeBusy) stringResource(R.string.coach_detecting) else stringResource(R.string.coach_detect)) }
                            if (localProbeBusy) {
                                Text(
                                    stringResource(R.string.coach_detect_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                            localProbeResult?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (localProbeOk) PulseColors.success else MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                            // Only non-empty when a probe was inconclusive or refused — the
                            // difference between "off because your server said no" and "off
                            // because we couldn't tell" is the whole point of showing it.
                            localProbeNotes.forEach { note ->
                                Text(
                                    "• $note",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }

                            Spacer(Modifier.height(8.dp))
                            if (localDiscovered.isNotEmpty()) {
                                ModelDropdown(
                                    stringResource(R.string.coach_model), localModel.ifBlank { context.getString(R.string.coach_choose_model) },
                                    localDiscovered.map { it to "" },
                                ) { localModel = it; providerStore.localModel = it }
                            }
                            // Always typeable: a router in front of the server can serve names
                            // /v1/models doesn't list, and llama.cpp ignores the field entirely.
                            OutlinedTextField(
                                value = localModel,
                                onValueChange = { localModel = it; providerStore.localModel = it },
                                label = { Text(stringResource(R.string.coach_model_name)) },
                                placeholder = { Text("qwen3:8b") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                            )
                            Spacer(Modifier.height(8.dp))

                            KeyField(
                                label = stringResource(R.string.coach_key_optional), value = localKey, visible = localKeyVisible,
                                saved = providerStore.hasLocalKey,
                                onValue = { localKey = it }, onVisibility = { localKeyVisible = !localKeyVisible },
                                onSave = { providerStore.localApiKey = localKey },
                                onRemove = { localKey = ""; providerStore.localApiKey = "" },
                            )
                            Text(
                                stringResource(R.string.coach_key_optional_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )

                            Spacer(Modifier.height(8.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(stringResource(R.string.coach_tool_calling))
                                    Text(
                                        stringResource(R.string.coach_tool_calling_desc),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Switch(checked = localToolCalling, onCheckedChange = {
                                    localToolCalling = it; providerStore.localToolCalling = it
                                })
                            }

                            Spacer(Modifier.height(8.dp))
                            ModelDropdown(
                                stringResource(R.string.coach_response_format), localStructured.label,
                                LocalStructuredOutput.entries.map { it.label to it.blurb },
                            ) { picked ->
                                val mode = LocalStructuredOutput.entries.first { it.label == picked }
                                localStructured = mode; providerStore.localStructuredOutput = mode
                            }

                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = localMaxTokens,
                                    onValueChange = {
                                        localMaxTokens = it.filter { c -> c.isDigit() }
                                        providerStore.localMaxTokens = localMaxTokens.toIntOrNull() ?: 0
                                    },
                                    label = { Text(stringResource(R.string.coach_max_tokens)) },
                                    placeholder = { Text("auto") },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                                )
                                OutlinedTextField(
                                    value = localTimeout,
                                    onValueChange = {
                                        localTimeout = it.filter { c -> c.isDigit() }
                                        localTimeout.toIntOrNull()?.let { v -> providerStore.localTimeoutSeconds = v }
                                    },
                                    label = { Text(stringResource(R.string.coach_timeout)) },
                                    modifier = Modifier
                                        .weight(1f)
                                        // The store clamps to 10..1800 on write, and a blank field
                                        // skips the write entirely, so the text can show a value
                                        // that was never stored (type "5", store holds 10) or an
                                        // empty box over a live setting. Reconciling per keystroke
                                        // would fight the user — "1" would jump to "10" — so read
                                        // the stored value back when the field loses focus.
                                        .onFocusChanged { focus ->
                                            if (!focus.isFocused)
                                                localTimeout = providerStore.localTimeoutSeconds.toString()
                                        },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                                )
                            }
                            Text(
                                stringResource(R.string.coach_tokens_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                        else -> {
                            // OpenAI (and legacy modes): the model picker + key field.
                            CoachModelPicker(
                                provider = CoachProviderMode.USER_OPENAI_KEY, providerName = "OpenAI", selected = selectedModel,
                                presets = models,
                                savedKey = keySaves.let { keyStore.apiKey },
                                onPick = { selectedModel = it; keyStore.model = it },
                                onListing = { liveListing = it },
                            )
                            KeyField(
                                label = stringResource(R.string.coach_key_label, "OpenAI"), value = apiKey, visible = apiKeyVisible,
                                saved = keyStore.apiKey.isNotBlank(),
                                onValue = { apiKey = it }, onVisibility = { apiKeyVisible = !apiKeyVisible },
                                onSave = { keyStore.apiKey = apiKey; keySaves++ },
                                onRemove = { apiKey = ""; keyStore.apiKey = ""; keySaves++ },
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.coach_key_storage_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    // Reasoning effort / Gemini thinking level (iOS #140). The levels differ per model
                    // (GPT-6 Sol rejects None, DeepSeek has no Medium), so the picker lists only what
                    // the selected model accepts, and shows "default" for a stored level it doesn't —
                    // matching CoachClientResolver.resolvedReasoningEffort, which won't send it.
                    val effortProvider = if (providerMode in setOf(
                            CoachProviderMode.USER_GEMINI_KEY, CoachProviderMode.USER_OPENROUTER_KEY,
                            CoachProviderMode.USER_MINIMAX_KEY, CoachProviderMode.LOCAL_OPENAI_COMPAT,
                        )) providerMode else CoachProviderMode.USER_OPENAI_KEY
                    val effortModel = when (effortProvider) {
                        CoachProviderMode.USER_GEMINI_KEY -> geminiModel
                        CoachProviderMode.USER_OPENROUTER_KEY -> orModel
                        CoachProviderMode.USER_OPENAI_KEY -> selectedModel
                        else -> ""
                    }
                    val liveEntry = liveListing?.find(effortModel)
                    val effortOptions = com.pulseloop.coach.config.ReasoningEfforts.options(effortProvider, effortModel, liveEntry)
                    if (effortOptions.isNotEmpty()) {
                        val shownEffort = com.pulseloop.coach.config.ReasoningEfforts.resolve(effortProvider, effortModel, reasoningEffort, liveEntry)
                        Spacer(Modifier.height(8.dp))
                        ModelDropdown(
                            stringResource(R.string.coach_reasoning_effort),
                            com.pulseloop.coach.config.ReasoningEffort.fromRaw(shownEffort)?.label ?: stringResource(R.string.coach_effort_default),
                            listOf("default" to stringResource(R.string.coach_effort_model_decides)) + effortOptions.map { it.rawValue to it.label },
                        ) { providerStore.reasoningEffort = it.takeIf { s -> s != "default" }; reasoningEffort = providerStore.reasoningEffort }
                    }

                    // Image attachments (iOS #31): gates the attach button in the coach chat.
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.coach_image_attachments))
                            Text(stringResource(R.string.coach_image_attachments_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = imageInput, onCheckedChange = { imageInput = it; providerStore.imageInputEnabled = it })
                    }

                    HorizontalDivider()

                    // Tool toggles. Neither MiniMax's compat endpoint nor any self-hosted engine
                    // has a hosted web search, so hide the toggle for both (each client drops the
                    // tool anyway — this is the UI half).
                    if (providerMode != CoachProviderMode.USER_MINIMAX_KEY &&
                        providerMode != CoachProviderMode.LOCAL_OPENAI_COMPAT) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.coach_web_search))
                                Text(stringResource(R.string.coach_web_search_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                // Free-tier Gemini keys can be refused Google Search grounding (HTTP 429)
                                // while every other call works; the coach then answers without it (issue #123).
                                if (providerMode == CoachProviderMode.USER_GEMINI_KEY) {
                                    Text(stringResource(R.string.coach_gemini_search_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Switch(checked = webSearch, onCheckedChange = {
                                webSearch = it; keyStore.webSearchEnabled = it
                            })
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.coach_write_actions))
                            Text(stringResource(R.string.coach_write_actions_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = writeTools, onCheckedChange = {
                            writeTools = it; keyStore.writeToolsEnabled = it
                        })
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.coach_live_measurements))
                            Text(stringResource(R.string.coach_live_measurements_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = liveMeasurements, onCheckedChange = {
                            liveMeasurements = it; keyStore.liveMeasurementsEnabled = it
                        })
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.coach_location_weather))
                            Text(stringResource(R.string.coach_location_weather_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = environmentContext, onCheckedChange = { setEnvironmentContext(it) })
                    }
                }
            }
        }

        // Coach Memory — ported from CoachSettingsSection memoryRow
        if (memories.isNotEmpty() && coachEnabled) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.coach_memory), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        TextButton(onClick = { showMemory = !showMemory }) {
                            Text(if (showMemory) stringResource(R.string.action_hide) else stringResource(R.string.action_show_count, memories.size))
                        }
                    }
                    if (showMemory) {
                        Spacer(Modifier.height(8.dp))
                        memories.forEach { memory ->
                            Card(
                                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            ) {
                                Row(Modifier.padding(12.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(memory.key, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                        Text(memory.value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                                    }
                                    IconButton(onClick = {
                                        scope.launch { db.coachMemoryDao().deleteByKey(memory.key) }
                                    }) {
                                        Icon(Icons.Filled.Delete, stringResource(R.string.action_delete), tint = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (askEnableCheckIns) {
        AlertDialog(
            onDismissRequest = { askEnableCheckIns = false },
            title = { Text(stringResource(R.string.checkins_enable_title)) },
            text = { Text(stringResource(R.string.checkins_enable_body)) },
            confirmButton = {
                TextButton(onClick = { askEnableCheckIns = false; enableCheckIns() }) { Text(stringResource(R.string.action_enable)) }
            },
            dismissButton = {
                TextButton(onClick = { askEnableCheckIns = false }) { Text(stringResource(R.string.action_not_now)) }
            },
        )
    }
}

// MARK: - Coach Check-Ins

/**
 * Coach Check-Ins detail screen (iOS NotificationsSettingsView): the old inline "Notifications"
 * card, retitled. The controls depend on the AI Coach being enabled, so when it's off they are
 * shown disabled with a hint. Enabling requests the notification permission (Android 13+).
 */
@Composable
fun CheckInsSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val keyStore = remember { ApiKeyStore(context) }
    val scope = rememberCoroutineScope()

    val coachEnabled = keyStore.coachEnabled
    var notificationEnabled by remember { mutableStateOf(keyStore.notificationsEnabled) }
    var notifPermissionDenied by remember { mutableStateOf(false) }

    val notifPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        notificationEnabled = granted
        keyStore.notificationsEnabled = granted
        notifPermissionDenied = !granted
        if (granted) CoachNotifications.schedule(context)
    }

    fun setNotifications(enabled: Boolean) {
        if (!enabled) {
            notificationEnabled = false
            keyStore.notificationsEnabled = false
            CoachNotifications.cancel(context)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            notificationEnabled = true
            keyStore.notificationsEnabled = true
            notifPermissionDenied = false
            CoachNotifications.schedule(context)
        }
    }

    // Ring battery alerts (iOS #61a) — independent of the coach toggle, its own POST_NOTIFICATIONS
    // gate. Default ON; delivery no-ops until the OS permission is granted.
    var batteryAlertsEnabled by remember { mutableStateOf(keyStore.batteryAlertsEnabled) }
    var batteryPermDenied by remember { mutableStateOf(false) }

    val batteryPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        batteryAlertsEnabled = granted
        keyStore.batteryAlertsEnabled = granted
        batteryPermDenied = !granted
    }

    fun setBatteryAlerts(enabled: Boolean) {
        if (!enabled) {
            batteryAlertsEnabled = false
            keyStore.batteryAlertsEnabled = false
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            batteryPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            batteryAlertsEnabled = true
            keyStore.batteryAlertsEnabled = true
            batteryPermDenied = false
        }
    }

    SettingsSubScreen(title = stringResource(R.string.settings_checkins), onBack = onBack) {
        if (!coachEnabled) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.checkins_coach_off), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.checkins_coach_off_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(stringResource(R.string.checkins_daily), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(12.dp))

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.checkins_daily_notifications))
                    Switch(
                        enabled = coachEnabled,
                        checked = notificationEnabled,
                        onCheckedChange = { setNotifications(it) },
                    )
                }

                if (notifPermissionDenied) {
                    Text(
                        stringResource(R.string.notifications_disabled_system),
                        style = MaterialTheme.typography.bodySmall,
                        color = PulseColors.danger,
                    )
                }

                if (notificationEnabled) {
                    Spacer(Modifier.height(8.dp))
                    // Morning time picker
                    var morningHour by remember { mutableStateOf(keyStore.morningHour) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.checkins_morning))
                        var amExpanded by remember { mutableStateOf(false) }
                        Box {
                            OutlinedButton(enabled = coachEnabled, onClick = { amExpanded = true }) {
                                Text(String.format("%02d:00", morningHour))
                            }
                            DropdownMenu(expanded = amExpanded, onDismissRequest = { amExpanded = false }) {
                                (0..<24).forEach { h ->
                                    DropdownMenuItem(
                                        text = { Text(String.format("%02d:00", h)) },
                                        onClick = { morningHour = h; keyStore.morningHour = h; amExpanded = false },
                                    )
                                }
                            }
                        }
                    }

                    // Evening time picker
                    var eveningHour by remember { mutableStateOf(keyStore.eveningHour) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.checkins_evening))
                        var pmExpanded by remember { mutableStateOf(false) }
                        Box {
                            OutlinedButton(enabled = coachEnabled, onClick = { pmExpanded = true }) {
                                Text(String.format("%02d:00", eveningHour))
                            }
                            DropdownMenu(expanded = pmExpanded, onDismissRequest = { pmExpanded = false }) {
                                (0..<24).forEach { h ->
                                    DropdownMenuItem(
                                        text = { Text(String.format("%02d:00", h)) },
                                        onClick = { eveningHour = h; keyStore.eveningHour = h; pmExpanded = false },
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        enabled = coachEnabled,
                        onClick = {
                            scope.launch {
                                CoachNotifications.showNow(
                                    context,
                                    context.getString(R.string.checkins_test_title),
                                    context.getString(R.string.checkins_test_body),
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.checkins_send_test))
                    }
                }
            }
        }

        // Ring battery alerts — independent of the coach toggle (iOS #61a puts this in its own
        // NotificationsSettingsView section): a local notification at <20% / <10%, once per day.
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(stringResource(R.string.battery_alerts_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.battery_alerts_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.battery_alerts_switch))
                    Switch(
                        checked = batteryAlertsEnabled,
                        onCheckedChange = { setBatteryAlerts(it) },
                    )
                }
                if (batteryPermDenied) {
                    Text(
                        stringResource(R.string.notifications_disabled_system),
                        style = MaterialTheme.typography.bodySmall,
                        color = PulseColors.danger,
                    )
                }
            }
        }
    }
}

// MARK: - User Profile

/**
 * User Profile detail screen: measurement units (folded in from the old standalone "Units"
 * card — iOS keeps units inside the profile editor) plus the shared ProfileEditor and save.
 * BP/glucose calibration moved to the capability-gated Calibration screen (iOS #49).
 */
@Composable
fun ProfileSettingsScreen(coordinator: RingSyncCoordinator?, onBack: () -> Unit) {
    val context = LocalContext.current
    val keyStore = remember { ApiKeyStore(context) }
    val db = remember { PulseLoopDatabase.getInstance(context) }
    val scope = rememberCoroutineScope()

    var profileDraft by remember { mutableStateOf<com.pulseloop.ui.onboarding.ProfileDraft?>(null) }
    var savedMsg by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val p = db.userProfileDao().get()
        profileDraft = com.pulseloop.ui.onboarding.ProfileDraft.from(
            p, existingUnits = keyStore.resolvedUnitSystem,
        )
    }

    SettingsSubScreen(title = stringResource(R.string.settings_profile), onBack = onBack) {
        Text(
            stringResource(R.string.profile_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        profileDraft?.let { draft ->
            // Shared editor (iOS #48) — same component as the onboarding Profile step, with
            // the units picker shown (the old Units card folded in). Unit changes persist to
            // the app-wide preference immediately, like the old card did.
            com.pulseloop.ui.onboarding.ProfileEditor(
                draft = draft,
                onDraftChange = { updated ->
                    if (updated.units != draft.units) {
                        keyStore.unitSystem = updated.units.name
                        // Widgets show unit-converted distance/temperature — refresh them.
                        com.pulseloop.widgets.WidgetSnapshotPublisher.publish(context)
                    }
                    profileDraft = updated
                    savedMsg = null
                },
                showUnits = true,
            )
            if (keyStore.unitSystem != null) {
                TextButton(onClick = {
                    keyStore.unitSystem = null
                    profileDraft = draft.copy(units = keyStore.resolvedUnitSystem)
                    com.pulseloop.widgets.WidgetSnapshotPublisher.publish(context)
                }) {
                    Text(stringResource(R.string.profile_reset_units), style = MaterialTheme.typography.labelSmall)
                }
            }

            Button(
                onClick = {
                    scope.launch {
                        val current = profileDraft ?: return@launch
                        val existing = db.userProfileDao().get()
                        val entity = current.applyTo(existing ?: com.pulseloop.data.entity.UserProfileEntity())
                        db.userProfileDao().upsert(entity)
                        // Keep pushing the stored BP calibration alongside the profile (the
                        // calibration values themselves are edited on the Calibration screen).
                        coordinator?.applyUserSettings(entity, keyStore.bpAdjustSystolic, keyStore.bpAdjustDiastolic)
                        // Profile (age/sex) shifts widget reference zones — republish.
                        com.pulseloop.widgets.WidgetSnapshotPublisher.publish(context)
                        savedMsg = if (coordinator?.isConnected == true)
                            context.getString(R.string.status_saved_sent) else context.getString(R.string.status_saved_pending)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.profile_save)) }
            savedMsg?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

// MARK: - Physiology

/**
 * Physiology detail screen (iOS #35 PhysiologySettingsView): optional inputs that tune the vitals
 * reference ranges (VitalsThresholdEngine). Everything is optional — the engine uses sensible
 * defaults when unset. Persisted to [ApiKeyStore] alongside units/calibration (Android keeps these
 * app-side prefs off the Room UserProfile). Saving republishes the widget snapshot so its zones
 * re-interpret without a new measurement (the values feed [com.pulseloop.service.UserPhysiologyProfile]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhysiologySettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val keyStore = remember { ApiKeyStore(context) }
    val imperial = keyStore.resolvedUnitSystem == UnitSystem.IMPERIAL

    var athleteMode by remember { mutableStateOf(false) }
    var altitudeText by remember { mutableStateOf("") }
    var betaBlockers by remember { mutableStateOf<Boolean?>(null) }
    var lungCondition by remember { mutableStateOf<Boolean?>(null) }
    var glucoseUnit by remember { mutableStateOf(GlucoseUnit.MGDL) }
    var savedMsg by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        athleteMode = keyStore.athleteMode
        betaBlockers = keyStore.usesBetaBlockers
        lungCondition = keyStore.hasKnownLungCondition
        glucoseUnit = keyStore.preferredGlucoseUnit
        keyStore.altitudeMeters?.let { m ->
            // Stored canonical metres; show in the display unit (iOS enters ft, stores m).
            altitudeText = (if (imperial) (m / 0.3048).roundToInt() else m.roundToInt()).toString()
        }
    }

    SettingsSubScreen(title = stringResource(R.string.settings_physiology), onBack = onBack) {
        Text(
            stringResource(R.string.physiology_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        PhysiologyCard(
            title = stringResource(R.string.physiology_fitness),
            footer = stringResource(R.string.physiology_fitness_footer),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.physiology_athlete_mode), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Switch(checked = athleteMode, onCheckedChange = { athleteMode = it; savedMsg = null })
            }
        }

        PhysiologyCard(
            title = stringResource(R.string.physiology_environment),
            footer = stringResource(R.string.physiology_environment_footer),
        ) {
            OutlinedTextField(
                value = altitudeText,
                onValueChange = { altitudeText = it.filter(Char::isDigit).take(5); savedMsg = null },
                label = { Text(if (imperial) stringResource(R.string.physiology_altitude_ft) else stringResource(R.string.physiology_altitude_m)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        PhysiologyCard(
            title = stringResource(R.string.physiology_health_context),
            footer = stringResource(R.string.physiology_health_context_footer),
        ) {
            TriStateRow(stringResource(R.string.physiology_beta_blockers), betaBlockers) { betaBlockers = it; savedMsg = null }
            Spacer(Modifier.height(12.dp))
            TriStateRow(stringResource(R.string.physiology_lung_condition), lungCondition) { lungCondition = it; savedMsg = null }
        }

        PhysiologyCard(title = stringResource(R.string.physiology_units), footer = null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.physiology_glucose_unit), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                SingleChoiceSegmentedButtonRow {
                    GlucoseUnit.entries.forEachIndexed { i, u ->
                        SegmentedButton(
                            selected = glucoseUnit == u,
                            onClick = { glucoseUnit = u; savedMsg = null },
                            shape = SegmentedButtonDefaults.itemShape(i, GlucoseUnit.entries.size),
                        ) { Text(stringResource(u.labelRes)) }
                    }
                }
            }
        }

        Button(
            onClick = {
                keyStore.athleteMode = athleteMode
                keyStore.usesBetaBlockers = betaBlockers
                keyStore.hasKnownLungCondition = lungCondition
                keyStore.preferredGlucoseUnit = glucoseUnit
                val entered = altitudeText.trim().toDoubleOrNull()
                keyStore.altitudeMeters =
                    if (entered != null && entered > 0) (if (imperial) entered * 0.3048 else entered) else null
                // Physiology shifts the vitals reference zones the widgets render — republish.
                com.pulseloop.widgets.WidgetSnapshotPublisher.publish(context)
                savedMsg = context.getString(R.string.status_saved_check)
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.action_save)) }
        savedMsg?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

/** A titled card with optional explanatory footer, matching iOS `SettingsGroup`. */
@Composable
private fun PhysiologyCard(title: String, footer: String?, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            content()
            if (footer != null) {
                Spacer(Modifier.height(8.dp))
                Text(footer, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Not set / No / Yes segmented control over a nullable Boolean, mirroring iOS `TriState`. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TriStateRow(title: String, value: Boolean?, onChange: (Boolean?) -> Unit) {
    val options = listOf<Pair<String, Boolean?>>(
        stringResource(R.string.tristate_not_set) to null,
        stringResource(R.string.tristate_no) to false,
        stringResource(R.string.tristate_yes) to true,
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        SingleChoiceSegmentedButtonRow {
            options.forEachIndexed { i, (label, v) ->
                SegmentedButton(
                    selected = value == v,
                    onClick = { onChange(v) },
                    shape = SegmentedButtonDefaults.itemShape(i, options.size),
                ) { Text(label) }
            }
        }
    }
}

// MARK: - Calibration

/**
 * Calibration detail screen (iOS CalibrationSettingsView): blood-pressure cuff calibration and
 * the app-side blood-sugar display offset, relocated from the old inline Profile card. The hub
 * only shows this for devices declaring BLOOD_PRESSURE / BLOOD_SUGAR (jring — Colmi rings
 * support neither).
 */
@Composable
fun CalibrationSettingsScreen(coordinator: RingSyncCoordinator?, onBack: () -> Unit) {
    val context = LocalContext.current
    val keyStore = remember { ApiKeyStore(context) }
    val db = remember { PulseLoopDatabase.getInstance(context) }
    val scope = rememberCoroutineScope()

    var bpSys by remember { mutableStateOf("") }
    var bpDia by remember { mutableStateOf("") }
    var glucoseRef by remember { mutableStateOf("") }
    var glucoseOffset by remember { mutableStateOf(0.0) }
    var glucoseMsg by remember { mutableStateOf<String?>(null) }
    var savedMsg by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        bpSys = keyStore.bpAdjustSystolic.takeIf { it > 0 }?.toString() ?: ""
        bpDia = keyStore.bpAdjustDiastolic.takeIf { it > 0 }?.toString() ?: ""
        glucoseOffset = keyStore.glucoseOffsetMgdl
        glucoseRef = keyStore.glucoseRefMgdl.takeIf { it > 0 }?.let { "%.0f".format(it) } ?: ""
    }

    SettingsSubScreen(title = stringResource(R.string.settings_calibration), onBack = onBack) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(stringResource(R.string.calibration_bp_title), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    stringResource(R.string.calibration_bp_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = bpSys, onValueChange = { bpSys = it.filter(Char::isDigit).take(3) },
                        label = { Text(stringResource(R.string.bp_systolic)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true, modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = bpDia, onValueChange = { bpDia = it.filter(Char::isDigit).take(3) },
                        label = { Text(stringResource(R.string.bp_diastolic)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true, modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        scope.launch {
                            val sysI = bpSys.toIntOrNull() ?: 0
                            val diaI = bpDia.toIntOrNull() ?: 0
                            keyStore.bpAdjustSystolic = sysI
                            keyStore.bpAdjustDiastolic = diaI
                            coordinator?.applyUserSettings(db.userProfileDao().get(), sysI, diaI)
                            savedMsg = if (coordinator?.isConnected == true)
                                context.getString(R.string.status_saved_sent) else context.getString(R.string.status_saved_pending)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.calibration_save)) }
                savedMsg?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(stringResource(R.string.calibration_glucose_title), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    stringResource(R.string.calibration_glucose_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = glucoseRef, onValueChange = { glucoseRef = it.filter(Char::isDigit).take(3) },
                        label = { Text(stringResource(R.string.calibration_glucose_reading)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true, modifier = Modifier.weight(1.4f),
                    )
                    Button(
                        onClick = {
                            scope.launch {
                                val ref = glucoseRef.toDoubleOrNull()
                                if (ref == null || ref <= 0) {
                                    glucoseMsg = context.getString(R.string.calibration_glucose_invalid)
                                    return@launch
                                }
                                val latestRaw = db.measurementDao().latest(MeasurementKind.BLOOD_SUGAR.name)
                                if (latestRaw == null) {
                                    glucoseMsg = context.getString(R.string.calibration_glucose_measure_first)
                                    return@launch
                                }
                                // Stored readings are raw (offset is applied only at display),
                                // so latestRaw is the ring's uncalibrated value.
                                keyStore.glucoseOffsetMgdl = ref - latestRaw
                                keyStore.glucoseRefMgdl = ref
                                glucoseOffset = keyStore.glucoseOffsetMgdl
                                glucoseMsg = context.getString(R.string.calibration_glucose_done, "%+.0f".format(glucoseOffset))
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.calibration_calibrate)) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (glucoseOffset != 0.0) stringResource(R.string.calibration_current_offset, "%+.0f".format(glucoseOffset)) else stringResource(R.string.calibration_none),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    if (glucoseOffset != 0.0) {
                        TextButton(onClick = {
                            keyStore.glucoseOffsetMgdl = 0.0
                            keyStore.glucoseRefMgdl = 0.0
                            glucoseOffset = 0.0
                            glucoseRef = ""
                            glucoseMsg = context.getString(R.string.calibration_cleared)
                        }) { Text(stringResource(R.string.action_reset)) }
                    }
                }
                glucoseMsg?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

// MARK: - Goals

/** Goals detail screen: the old inline "Goals" card (shared GoalEditor + save). */
@Composable
fun GoalsSettingsScreen(coordinator: RingSyncCoordinator?, onBack: () -> Unit) {
    val context = LocalContext.current
    val keyStore = remember { ApiKeyStore(context) }
    val db = remember { PulseLoopDatabase.getInstance(context) }
    val scope = rememberCoroutineScope()

    val goalUnits = keyStore.resolvedUnitSystem
    var goalDraft by remember { mutableStateOf<com.pulseloop.ui.onboarding.GoalDraft?>(null) }
    var goalSavedMsg by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        goalDraft = com.pulseloop.ui.onboarding.GoalDraft.from(db.userGoalDao().get(), goalUnits)
    }

    SettingsSubScreen(title = stringResource(R.string.settings_goals), onBack = onBack) {
        Text(
            stringResource(R.string.goals_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        goalDraft?.let { draft ->
            com.pulseloop.ui.onboarding.GoalEditor(
                draft = draft,
                onDraftChange = { goalDraft = it; goalSavedMsg = null },
                units = goalUnits,
                includeWeeklyWorkouts = true,
            )
            Button(
                onClick = {
                    scope.launch {
                        val existing = db.userGoalDao().get()
                        db.userGoalDao().upsert(
                            draft.applyTo(
                                existing ?: com.pulseloop.data.entity.UserGoalEntity(),
                                goalUnits,
                                includeWeeklyWorkouts = true,
                            ),
                        )
                        coordinator?.setGoal(draft.steps.toInt())
                        // Ring goals drive the widget's activity rings — republish.
                        com.pulseloop.widgets.WidgetSnapshotPublisher.publish(context)
                        goalSavedMsg = if (coordinator?.isConnected == true)
                            context.getString(R.string.status_saved_sent) else context.getString(R.string.status_saved)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.goals_save)) }
            goalSavedMsg?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

// MARK: - Measurement Frequency

/**
 * Measurement Frequency detail screen: the old inline "Measurement" card (iOS #19 content).
 * The hub only shows the row for rings declaring MEASUREMENT_INTERVAL (Colmi).
 */
@Composable
fun MeasurementSettingsScreen(coordinator: RingSyncCoordinator?, onBack: () -> Unit) {
    val context = LocalContext.current
    val db = remember { PulseLoopDatabase.getInstance(context) }
    val scope = rememberCoroutineScope()
    val device = db.deviceDao().currentFlow().collectAsState(initial = null).value
    val capabilities = device?.capabilities.orEmpty()
    // Both YCBT-protocol families get the raised bounds: `COLMI_SMART_HEALTH` rides the same
    // [com.pulseloop.ring.YCBTDriver], so its ring can carry the same settings (issue #103,
    // Albabit's `colmi-smarthealth` unit was capped at 5–60 until 2026-10-23).
    val isYcbtProtocol = device?.deviceType in setOf(RingDeviceType.YCBT, RingDeviceType.COLMI_SMART_HEALTH)
    val minimumInterval = if (isYcbtProtocol) 30 else 5
    // 120 min is the only interval beyond the vendor app's own menu (which stops at 60) that is
    // tested on hardware: the ring ACKed `01 0c 01 78` and read at 10:00 and 12:00 (issue #103,
    // 2026-10-22). Nothing longer until someone tries it, and YCBT-protocol rings only — it is
    // not the vendor app's behaviour, it is one ring's.
    val maximumHrInterval = if (isYcbtProtocol) 120 else 60
    val intervalSteps = ((maximumHrInterval - minimumInterval) / 5 - 1).coerceAtLeast(0)
    // Only offered where the ring's protocol actually carries a separate blood-oxygen interval —
    // elsewhere the slider moved, the value saved, and "Saved & sent to ring ✓" appeared over a
    // setting nothing on the wire could express (issue #66).
    val supportsSpo2Interval = device?.deviceType?.supportsSeparateSpo2Interval == true
    // The SpO₂ slider runs five minutes lower than the HR one (its floor is "follow heart rate"),
    // so it needs its own step count: reusing the HR one put ticks 5.45 min apart, none of which
    // the 5-minute snap could land on.
    val spo2IntervalSteps = ((60 - (minimumInterval - 5)) / 5 - 1).coerceAtLeast(0)
    // Same exclusion as [RingDeviceType.YCBT]: the YCBT wire format has no stress-monitor
    // setting ([com.pulseloop.ring.YCBTEncoder.monitorCommands] carries HR/BP/temp/SpO₂/HRV
    // only), so a toggle here would be a no-op the ring never sees — the capability bit alone
    // is not enough to offer it.
    val supportsStressSetting = WearableCapability.STRESS in capabilities &&
        !isYcbtProtocol

    var cfgHrEnabled by remember { mutableStateOf(true) }
    var cfgHrInterval by remember { mutableStateOf(5) }
    var cfgSpo2 by remember { mutableStateOf(true) }
    /** 0 = follow the heart-rate interval, which is what it always did (issue #66). */
    var cfgSpo2Interval by remember { mutableStateOf(0) }
    var cfgStress by remember { mutableStateOf(true) }
    var cfgHrv by remember { mutableStateOf(true) }
    var cfgTemp by remember { mutableStateOf(true) }
    val scheduledSpo2Prefs = remember { ScheduledSpo2Prefs(context) }
    val supportsScheduledSpo2 = ScheduledSpo2Prefs.isSupported(device)
    var cfgAppSpo2 by remember { mutableStateOf(false) }
    var cfgAppSpo2Interval by remember { mutableStateOf(ScheduledSpo2Prefs.DEFAULT_INTERVAL_MINUTES) }
    var cfgHideEstimatedSpo2 by remember { mutableStateOf(true) }
    var cfgLoaded by remember { mutableStateOf(false) }
    var cfgSavedMsg by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(device?.id, device?.capabilitiesRaw) {
        val id = device?.id ?: return@LaunchedEffect
        cfgLoaded = false
        val config = db.deviceMeasurementConfigDao().byDevice(id)
        cfgHrEnabled = config?.hrEnabled ?: true
        cfgHrInterval = (config?.hrIntervalMinutes ?: minimumInterval).coerceIn(minimumInterval, maximumHrInterval)
        cfgSpo2 = (config?.spo2Enabled ?: true) && WearableCapability.SPO2 in capabilities
        cfgSpo2Interval = (config?.spo2IntervalMinutes ?: 0).let {
            if (it <= 0) 0 else it.coerceIn(minimumInterval, 60)
        }
        cfgStress = (config?.stressEnabled ?: true) && supportsStressSetting
        cfgHrv = (config?.hrvEnabled ?: true) && WearableCapability.HRV in capabilities
        cfgTemp = (config?.temperatureEnabled ?: true) && WearableCapability.TEMPERATURE in capabilities
        cfgAppSpo2 = scheduledSpo2Prefs.enabled
        cfgAppSpo2Interval = scheduledSpo2Prefs.intervalMinutes
        cfgHideEstimatedSpo2 = scheduledSpo2Prefs.hideEstimated
        cfgLoaded = true
    }

    @Composable
    fun VitalToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(label)
            Switch(enabled = cfgLoaded, checked = checked, onCheckedChange = { onChange(it); cfgSavedMsg = null })
        }
    }

    SettingsSubScreen(title = stringResource(R.string.settings_measurement), onBack = onBack) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(stringResource(R.string.measurement_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.measurement_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))

                if (device == null) {
                    Text(
                        stringResource(R.string.measurement_pair_first),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    VitalToggle(stringResource(R.string.measurement_allday_hr), cfgHrEnabled) { cfgHrEnabled = it }
                    if (cfgHrEnabled) {
                        Text(
                            stringResource(R.string.measurement_every_min, cfgHrInterval),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Slider(
                            enabled = cfgLoaded,
                            value = cfgHrInterval.toFloat(),
                            onValueChange = { cfgHrInterval = ((it / 5).toInt() * 5).coerceIn(minimumInterval, maximumHrInterval); cfgSavedMsg = null },
                            valueRange = minimumInterval.toFloat()..maximumHrInterval.toFloat(),
                            steps = intervalSteps,
                        )
                    }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    if (WearableCapability.SPO2 in capabilities) {
                        VitalToggle(stringResource(R.string.measurement_spo2), cfgSpo2) { cfgSpo2 = it }
                        // The monitor command takes the same shape for every vital
                        // (`01 <key> {enable, interval}`), so blood oxygen always had an interval —
                        // it just wasn't surfaced and silently followed heart rate. On a ring
                        // running both monitors hourly that is most of the battery (issue #66).
                        if (cfgSpo2 && supportsSpo2Interval) {
                            Text(
                                // The blood-oxygen command keeps the 60-min ceiling whatever the
                                // heart-rate slider shows (no evidence past 60 on that key).
                                if (cfgSpo2Interval <= 0) stringResource(R.string.measurement_spo2_same_as_hr) else stringResource(R.string.measurement_every_min, cfgSpo2Interval),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Slider(
                                enabled = cfgLoaded,
                                // The bottom of the track is "follow heart rate" rather than a
                                // shorter interval than the ring will honour.
                                value = (if (cfgSpo2Interval <= 0) minimumInterval - 5 else cfgSpo2Interval).toFloat(),
                                onValueChange = {
                                    val stepped = (it / 5).toInt() * 5
                                    cfgSpo2Interval = if (stepped < minimumInterval) 0 else stepped.coerceAtMost(60)
                                    cfgSavedMsg = null
                                },
                                valueRange = (minimumInterval - 5).toFloat()..60f,
                                steps = spo2IntervalSteps,
                            )
                        }
                        // Issue #103: this ring's own SpO₂ is computed from heart rate, so the app
                        // can run the ring's real spot measurement on the schedule instead.
                        if (supportsScheduledSpo2) {
                            VitalToggle(stringResource(R.string.measurement_app_spo2), cfgAppSpo2) {
                                cfgAppSpo2 = it
                                cfgSavedMsg = null
                            }
                            if (cfgAppSpo2) {
                                Text(
                                    stringResource(R.string.measurement_app_spo2_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                // Independent of the ring's SpO₂ interval: the app starts these runs
                                // itself, so the ring's limits don't apply (issue #103). Each run is
                                // ~50 s of red LED, which is what 2–3 h spacing is for.
                                Text(
                                    if (cfgAppSpo2Interval == 60) stringResource(R.string.measurement_every_hour) else pluralStringResource(R.plurals.measurement_every_hours, cfgAppSpo2Interval / 60, cfgAppSpo2Interval / 60),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Slider(
                                    enabled = cfgLoaded,
                                    value = cfgAppSpo2Interval.toFloat(),
                                    onValueChange = {
                                        cfgAppSpo2Interval = ScheduledSpo2Prefs.sanitizeIntervalMinutes(kotlin.math.round(it / 60f).toInt() * 60)
                                        cfgSavedMsg = null
                                    },
                                    valueRange = 60f..180f,
                                    steps = 1,
                                )
                                VitalToggle(stringResource(R.string.measurement_hide_estimated), cfgHideEstimatedSpo2) {
                                    cfgHideEstimatedSpo2 = it
                                    cfgSavedMsg = null
                                }
                                Text(
                                    stringResource(R.string.measurement_hide_estimated_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    if (supportsStressSetting) {
                        VitalToggle(stringResource(R.string.metric_stress), cfgStress) { cfgStress = it }
                    }
                    if (WearableCapability.HRV in capabilities) {
                        VitalToggle(stringResource(R.string.abbr_hrv), cfgHrv) { cfgHrv = it }
                    }
                    if (WearableCapability.TEMPERATURE in capabilities) {
                        VitalToggle(stringResource(R.string.metric_temperature), cfgTemp) { cfgTemp = it }
                    }

                    Spacer(Modifier.height(12.dp))
                    Button(
                        enabled = cfgLoaded,
                        onClick = {
                            scope.launch {
                                db.deviceMeasurementConfigDao().upsert(
                                    com.pulseloop.data.entity.DeviceMeasurementConfigEntity(
                                        deviceId = device.id,
                                        hrIntervalMinutes = cfgHrInterval.coerceIn(minimumInterval, maximumHrInterval),
                                        hrEnabled = cfgHrEnabled,
                                        spo2Enabled = cfgSpo2 && WearableCapability.SPO2 in capabilities,
                                        spo2IntervalMinutes = if (!supportsSpo2Interval || cfgSpo2Interval <= 0) 0
                                            else cfgSpo2Interval.coerceIn(minimumInterval, 60),
                                        stressEnabled = cfgStress && supportsStressSetting,
                                        hrvEnabled = cfgHrv && WearableCapability.HRV in capabilities,
                                        temperatureEnabled = cfgTemp && WearableCapability.TEMPERATURE in capabilities,
                                        updatedAt = System.currentTimeMillis(),
                                    )
                                )
                                coordinator?.applyMeasurementSettings()
                                scheduledSpo2Prefs.enabled = cfgAppSpo2 && supportsScheduledSpo2
                                scheduledSpo2Prefs.intervalMinutes = cfgAppSpo2Interval
                                scheduledSpo2Prefs.hideEstimated = cfgHideEstimatedSpo2
                                ScheduledSpo2Worker.reschedule(context.applicationContext)
                                cfgSavedMsg = if (coordinator?.isConnected == true)
                                    context.getString(R.string.status_saved_sent) else context.getString(R.string.status_saved_pending)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.measurement_save)) }
                    cfgSavedMsg?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

// MARK: - Wearable

/**
 * The opt-in sleep trimming gate (issues #79, #83, #126): sleep the ring records outside the
 * wearer's window is trimmed on import. The rationale lives on [QuietHoursPrefs]; this is just the
 * controls — two independent switches (Bedtime/DND tracking, the clock schedule) and the schedule's
 * two ends, on the Wearable screen because it governs what a sync is allowed to store.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuietHoursCard() {
    val context = LocalContext.current
    val prefs = remember { QuietHoursPrefs(context) }
    var enabled by remember { mutableStateOf(prefs.enabled) }
    var gateByZenMode by remember {
        mutableStateOf(prefs.gateByZenMode && ZenModeListener.isAccessGranted(context))
    }
    var start by remember { mutableStateOf(prefs.startMinutes) }
    var end by remember { mutableStateOf(prefs.endMinutes) }
    // "start" | "end" while a picker is open.
    var picking by remember { mutableStateOf<String?>(null) }
    var showPermissionDialog by remember { mutableStateOf(false) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val granted = ZenModeListener.isAccessGranted(context)
                if (prefs.gateByZenMode && !granted) {
                    prefs.gateByZenMode = false
                    ZenModeListener.setEnabled(context, false)
                }
                gateByZenMode = prefs.gateByZenMode && granted
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.sleep_trimming), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                stringResource(R.string.sleep_trimming_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            // Independent of the schedule below (issue #126), so the Notification-access prompt
            // has to work from here with the schedule off.
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(
                        stringResource(R.string.sleep_track_bedtime),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        stringResource(R.string.sleep_track_bedtime_desc) + " " +
                            if (enabled) stringResource(R.string.sleep_track_bedtime_schedule) else stringResource(R.string.sleep_track_bedtime_whole),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = gateByZenMode,
                    onCheckedChange = { desired ->
                        if (desired) {
                            ZenModeListener.setEnabled(context, true)
                            if (ZenModeListener.isAccessGranted(context)) {
                                gateByZenMode = true
                                prefs.gateByZenMode = true
                            } else {
                                showPermissionDialog = true
                            }
                        } else {
                            gateByZenMode = false
                            prefs.gateByZenMode = false
                            ZenModeListener.setEnabled(context, false)
                        }
                    },
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(
                        stringResource(R.string.sleep_quiet_hours),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        if (gateByZenMode) stringResource(R.string.sleep_quiet_hours_zen)
                        else stringResource(R.string.sleep_quiet_hours_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = enabled, onCheckedChange = { enabled = it; prefs.enabled = it })
            }
            if (enabled) {
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { picking = "start" }, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.sleep_from, formatMinutesOfDay(start)))
                    }
                    OutlinedButton(onClick = { picking = "end" }, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.sleep_until, formatMinutesOfDay(end)))
                    }
                }
            }
        }
    }

    if (showPermissionDialog) {
        AlertDialog(
            onDismissRequest = { showPermissionDialog = false },
            title = { Text(stringResource(R.string.permission_required)) },
            text = {
                Text(
                    stringResource(R.string.sleep_zen_permission_body),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showPermissionDialog = false
                    try {
                        val intent = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                            android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                                .putExtra(
                                    android.provider.Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                                    ZenModeListener.component(context).flattenToString(),
                                )
                        } else {
                            android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                        }
                        context.startActivity(intent)
                        prefs.gateByZenMode = true
                    } catch (_: Exception) {}
                }) { Text(stringResource(R.string.action_open_settings)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showPermissionDialog = false
                    if (!prefs.gateByZenMode) ZenModeListener.setEnabled(context, false)
                }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    picking?.let { which ->
        val initial = if (which == "start") start else end
        val pickerState = rememberTimePickerState(
            initialHour = initial / 60,
            initialMinute = initial % 60,
            is24Hour = true,
        )
        AlertDialog(
            onDismissRequest = { picking = null },
            title = { Text(if (which == "start") stringResource(R.string.sleep_quiet_from) else stringResource(R.string.sleep_quiet_until)) },
            text = { TimePicker(state = pickerState) },
            confirmButton = {
                TextButton(onClick = {
                    val minutes = pickerState.hour * 60 + pickerState.minute
                    if (which == "start") { start = minutes; prefs.startMinutes = minutes }
                    else { end = minutes; prefs.endMinutes = minutes }
                    picking = null
                }) { Text(stringResource(R.string.action_done)) }
            },
            dismissButton = {
                TextButton(onClick = { picking = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

private fun formatMinutesOfDay(minutes: Int): String =
    "%02d:%02d".format(minutes / 60, minutes % 60)

/**
 * Wearable detail screen (iOS WearableSettingsView) — the hero card opens this. Connection
 * state, exact model name, firmware, sync/find/disconnect actions, plus the old inline "Ring"
 * card's forget + factory-reset management.
 */
@Composable
fun WearableSettingsScreen(
    bleClient: RingBLEClient?,
    coordinator: RingSyncCoordinator?,
    onAddRing: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val db = remember { PulseLoopDatabase.getInstance(context) }
    val scope = rememberCoroutineScope()

    val bleState = bleClient?.state?.collectAsState()?.value ?: RingBLEClient.BLEState()
    // Observe the device row reactively so the connection status reflects live BLE state
    // changes (connect / disconnect / reconnect) in real time.
    val device by db.deviceDao().currentFlow().collectAsState(initial = null)
    val isConnected = bleState.connectionState == RingConnectionState.CONNECTED
    val supportsFactoryReset = bleState.activeCapabilities
        .contains(com.pulseloop.ring.WearableCapability.FACTORY_RESET)
    val supportsFindDevice = bleState.activeCapabilities
        .contains(com.pulseloop.ring.WearableCapability.FIND_DEVICE)
    var showFactoryReset by remember { mutableStateOf(false) }
    var resetting by remember { mutableStateOf(false) }
    var resetStatus by remember { mutableStateOf("") }

    // Exact model preferred; falls back to the family / advertised-name heuristic.
    val wearableDisplayName = WearableModel.model(bleState.activeWearableModelID)?.displayName
        ?: WearableModel.model(device?.wearableModelID)?.displayName
        ?: com.pulseloop.ring.ringModelLabel(device?.advertisedName ?: device?.name, device?.deviceType)

    val battery = bleState.batteryPercent ?: device?.batteryPercent
    val lastSyncedLabel = (coordinator?.lastSyncAt ?: device?.lastSyncAt)
        ?.let { DeviceHeroStatus.relativeShort(it, System.currentTimeMillis()) }
        ?: stringResource(R.string.status_not_yet)

SettingsSubScreen(title = stringResource(R.string.settings_wearable), onBack = onBack) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Bluetooth, null, Modifier.size(18.dp),
                        tint = if (isConnected) PulseColors.success else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (isConnected) stringResource(R.string.wearable_connected_status, wearableDisplayName, battery ?: 0)
                    else device?.let { stringResource(R.string.wearable_last_seen, wearableDisplayName) } ?: stringResource(R.string.wearable_none_paired),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                // Firmware version — show just the trailing version (e.g. "V138"), matching
                // the official app, which displays only the last segment of "003A002AV138".
                if (device?.firmwareVersion != null) {
                    val rawFw = device!!.firmwareVersion!!
                    val fwDisplay = if (rawFw.contains("V")) "V" + rawFw.substringAfterLast("V") else rawFw
                    Text(stringResource(R.string.wearable_firmware, fwDisplay), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
                } else if (device != null) {
                    Text(stringResource(R.string.wearable_firmware_reading), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
                }
                if (device != null) {
                    Text(stringResource(R.string.wearable_last_synced, lastSyncedLabel), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
                }

                if (device == null) {
                    // No ring yet — route into the redesigned pairing screen (iOS #48).
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onAddRing, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.Add, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.wearable_add_ring))
                    }
                }

                if (isConnected) {
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = { coordinator?.syncNow() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.Sync, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.wearable_sync_now))
                    }
                    if (supportsFindDevice) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { coordinator?.findRing() },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Filled.NotificationsActive, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.wearable_find_ring))
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { bleClient?.userDisconnect() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.Close, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.wearable_disconnect))
                    }
                } else if (bleState.connectionState == RingConnectionState.RECONNECTING) {
                    // Automatic recovery can scan for minutes; let the user stop it (issue #124).
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = { bleClient?.userDisconnect() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.Close, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.wearable_disconnect))
                    }
                } else if (bleClient?.hasLastKnownRing == true) {
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = { bleClient.userConnect() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.Refresh, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.wearable_reconnect))
                    }
                }
            }
        }

        if (device != null) {
            BatteryHistorySection(db)
        }

        if (device != null) {
            QuietHoursCard()
        }

        if (device != null) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.wearable_management), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.wearable_forget_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                // Cancel background sync before unpair
                                RingSyncWorker.cancel(context)
                                // Send ring-side unbind, then disconnect & clear DB
                                if (coordinator != null && isConnected) {
                                    // Runs on the coordinator's long-lived scope, so the row is
                                    // cleared even if the user leaves this screen meanwhile.
                                    // Clearing it emits null through currentFlow(), which
                                    // updates the UI reactively.
                                    coordinator.forgetRing {
                                        db.deviceDao().clear()
                                    }
                                } else {
                                    bleClient?.forget()
                                    db.deviceDao().clear()
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) {
                        Icon(Icons.Filled.DeleteForever, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.wearable_forget))
                    }

                    // Factory reset — destructive, ring-side wipe. Only for connected rings that
                    // support it (Colmi). Syncs latest history first so nothing is lost.
                    if (isConnected && supportsFactoryReset) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.wearable_factory_reset_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(
                            enabled = !resetting,
                            onClick = { showFactoryReset = true },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        ) {
                            Icon(Icons.Filled.Refresh, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(if (resetting) (resetStatus.ifEmpty { stringResource(R.string.wearable_resetting) }) else stringResource(R.string.wearable_factory_reset))
                        }
                    }
                }
            }
        }
    }

    if (showFactoryReset) {
        AlertDialog(
            onDismissRequest = { showFactoryReset = false },
            title = { Text(stringResource(R.string.wearable_factory_reset_title)) },
            text = {
                Text(
                    stringResource(R.string.wearable_factory_reset_body)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showFactoryReset = false
                    resetting = true
                    resetStatus = context.getString(R.string.wearable_syncing_latest)
                    RingSyncWorker.cancel(context)
                    // onCleared runs on the coordinator's long-lived scope: the reset
                    // takes up to ~30s, and the device row must be cleared even if the
                    // user navigates away (which cancels this screen's own scope).
                    coordinator?.factoryResetRing(
                        onProgress = { resetStatus = it },
                    ) {
                        db.deviceDao().clear()
                        resetting = false
                    }
                }) { Text(stringResource(R.string.wearable_reset_ring), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showFactoryReset = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

/** 24h/7d windows for the battery drainage chart (iOS #61b — only these two ranges). */
private enum class BatteryHistoryRange(val label: String, val spanMs: Long) {
    DAY("24h", 24 * 3600_000L),
    WEEK("7d", 7 * 24 * 3600_000L),
}

/** Fixed 0–100 zone coloring for the battery line — mirrors iOS `colorForValue`
 *  (≤20 danger, ≤50 warning, else success). Zone boundaries use an exclusive upper bound
 *  (`ZoneLineChart` matches `lower <= value < upper`), so 21/51 sit just above each cut. */
private val batteryZones get() = listOf(
    MetricZone("critical", L10n.get(R.string.zone_critical), null, 21.0, ZoneSeverity.CRITICAL, VitalColorToken.Red, L10n.get(R.string.battery_zone_critical)),
    MetricZone("low", L10n.get(R.string.zone_low), 21.0, 51.0, ZoneSeverity.WATCH, VitalColorToken.Amber, L10n.get(R.string.battery_zone_low)),
    MetricZone("good", L10n.get(R.string.zone_good), 51.0, null, ZoneSeverity.OPTIMAL, VitalColorToken.Mint, L10n.get(R.string.battery_zone_good)),
)

/**
 * Battery drainage history (iOS #61b `BatteryHistorySection`): a 24h/7d segmented range picker over
 * a fixed 0–100 [ZoneLineChart]. Reads [com.pulseloop.data.entity.BatterySampleEntity] rows written by
 * [com.pulseloop.service.EventPersistenceSubscriber]'s throttled battery log.
 */
@Composable
private fun BatteryHistorySection(db: PulseLoopDatabase) {
    var range by remember { mutableStateOf(BatteryHistoryRange.DAY) }
    // Reactive on the device row so a fresh battery reading refetches the window without a manual pull.
    val device by db.deviceDao().currentFlow().collectAsState(initial = null)
    var samples by remember { mutableStateOf<List<VitalSample>>(emptyList()) }

    LaunchedEffect(range, device?.batteryPercent, device?.updatedAt) {
        val end = System.currentTimeMillis()
        val start = end - range.spanMs
        samples = db.batterySampleDao().samplesBetween(start, end)
            .map { VitalSample(timestampMs = it.timestamp, value = it.percent.toDouble()) }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.battery_history), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                SingleChoiceSegmentedButtonRow {
                    BatteryHistoryRange.entries.forEachIndexed { i, r ->
                        SegmentedButton(
                            selected = range == r,
                            onClick = { range = r },
                            shape = SegmentedButtonDefaults.itemShape(i, BatteryHistoryRange.entries.size),
                        ) { Text(r.label) }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            if (samples.size < 2) {
                Text(
                    stringResource(R.string.battery_history_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                ZoneLineChart(
                    samples = samples,
                    zones = batteryZones,
                    yDomain = 0.0..100.0,
                    accent = PulseColors.success,
                    height = 160.dp,
                    // Day boundaries on the 7 d view, six-hour marks on 24 h (issue #65).
                    verticalGridlines = com.pulseloop.service.BatteryProjection.gridlines(
                        startMs = samples.first().timestampMs,
                        endMs = samples.last().timestampMs,
                    ),
                )
                Spacer(Modifier.height(10.dp))
                BatteryRuntimeEstimate(samples)
            }
        }
    }
}

// MARK: - Privacy & Data

/** Destructive data-action options for the Privacy & Data screen. */
private sealed class ResetAction {
    data object UnpairRing : ResetAction()
    data object ResetAppData : ResetAction()
    data object UnpairAndReset : ResetAction()

    val title: String get() = when (this) {
        is UnpairRing -> L10n.get(R.string.reset_unpair_title)
        is ResetAppData -> L10n.get(R.string.reset_app_title)
        is UnpairAndReset -> L10n.get(R.string.reset_both_title)
    }
    val message: String get() = when (this) {
        is UnpairRing -> L10n.get(R.string.reset_unpair_body)
        is ResetAppData -> L10n.get(R.string.reset_app_body)
        is UnpairAndReset -> L10n.get(R.string.reset_both_body)
    }
    val confirmLabel: String get() = when (this) {
        is UnpairRing -> L10n.get(R.string.reset_unpair_confirm)
        is ResetAppData -> L10n.get(R.string.reset_app_confirm)
        is UnpairAndReset -> L10n.get(R.string.reset_both_confirm)
    }
}

/**
 * Privacy & Data detail screen (iOS PrivacyDataView): grouped sections for diagnostics export,
 * destructive App-data reset actions (Unpair Ring, Reset App Data, Unpair & Reset),
 * data backup (export/import as JSON), and demo-data controls. Reflects the app's
 * transparency/privacy ethos — everything here is local and explicit.
 */
@Composable
fun PrivacyDataSettingsScreen(
    onBack: () -> Unit,
    coordinator: RingSyncCoordinator? = null,
    bleClient: RingBLEClient? = null,
    onNavigateToOnboarding: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showSeedDialog by remember { mutableStateOf(false) }
    var showClearDialog by remember { mutableStateOf(false) }

    /** Which destructive App-data action is awaiting confirmation. */
    var pendingReset by remember { mutableStateOf<ResetAction?>(null) }

    // Data export/import state.
    var exportInProgress by remember { mutableStateOf(false) }
    var importInProgress by remember { mutableStateOf(false) }
    var showImportConfirm by remember { mutableStateOf(false) }
    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var showImportSuccess by remember { mutableStateOf(false) }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            pendingImportUri = uri
            showImportConfirm = true
        }
    }

    fun clearAllPreferences() {
        listOf("pulseloop_secure", "ring_ble", "pulseloop_prefs").forEach { name ->
            context.getSharedPreferences(name, 0).edit().clear().apply()
        }
        // Strava OAuth tokens live in their own EncryptedSharedPreferences file, so the loop above
        // misses them — a "Reset App Data" that leaves the account connected is not a reset. iOS
        // deletes the equivalent Keychain entry alongside the coach API keys for the same reason.
        runCatching { com.pulseloop.strava.StravaTokenStore(context).clear() }
    }

    fun performUnpair() {
        scope.launch {
            RingSyncWorker.cancel(context)
            coordinator?.forgetRing { PulseLoopDatabase.getInstance(context).deviceDao().clear() }
                ?: run {
                    bleClient?.forget()
                    PulseLoopDatabase.getInstance(context).deviceDao().clear()
                }
        }
    }

    fun performResetAppData() {
        scope.launch {
            PulseLoopDatabase.getInstance(context).nukeAllTables()
            clearAllPreferences()
            onNavigateToOnboarding()
        }
    }

    fun performUnpairAndReset() {
        scope.launch {
            RingSyncWorker.cancel(context)
            if (coordinator != null) {
                coordinator.forgetRing { }
                PulseLoopDatabase.getInstance(context).nukeAllTables()
            } else {
                bleClient?.forget()
                PulseLoopDatabase.getInstance(context).nukeAllTables()
            }
            clearAllPreferences()
            onNavigateToOnboarding()
        }
    }

    SettingsSubScreen(title = stringResource(R.string.settings_privacy), onBack = onBack) {
        // Data Backup — export/import full app data as JSON.
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(stringResource(R.string.privacy_backup), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.privacy_backup_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            exportInProgress = true
                            scope.launch {
                                try {
                                    val db = PulseLoopDatabase.getInstance(context)
                                    val uri = com.pulseloop.data.DataArchiveService.exportToFile(context, db)
                                    if (uri != null) {
                                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                            type = "application/json"
                                            putExtra(Intent.EXTRA_STREAM, uri)
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        context.startActivity(Intent.createChooser(shareIntent, context.getString(R.string.privacy_export_chooser)))
                                    }
                                } catch (_: Exception) {
                                    statusMessage = context.getString(R.string.privacy_export_failed)
                                }
                                exportInProgress = false
                            }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !exportInProgress,
                    ) {
                        if (exportInProgress) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(if (exportInProgress) stringResource(R.string.privacy_exporting) else stringResource(R.string.privacy_export_all))
                    }
                    OutlinedButton(
                        onClick = {
                            filePicker.launch(arrayOf("application/json"))
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !importInProgress,
                    ) {
                        if (importInProgress) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(if (importInProgress) stringResource(R.string.privacy_importing) else stringResource(R.string.privacy_import))
                    }
                }
            }
        }

        // App data — destructive reset/restore actions.
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(stringResource(R.string.privacy_app_data), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.privacy_app_data_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = { pendingReset = ResetAction.UnpairRing },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Icon(Icons.Filled.BluetoothDisabled, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.reset_unpair_confirm))
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { pendingReset = ResetAction.ResetAppData },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Icon(Icons.Filled.DeleteForever, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.reset_app_confirm))
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { pendingReset = ResetAction.UnpairAndReset },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Icon(Icons.Filled.Warning, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.reset_both_confirm))
                }
            }
        }

        // Demo data — Android-only (iOS seeds via the Simulator's SeedData).
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(stringResource(R.string.privacy_demo), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.privacy_demo_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = { showSeedDialog = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.privacy_demo_reseed))
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { showClearDialog = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.privacy_demo_clear), color = PulseColors.danger)
                }
            }
        }

        // Diagnostics export.
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                var maskSensitive by remember { mutableStateOf(true) }
                Text(stringResource(R.string.privacy_diagnostics), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.privacy_diagnostics_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.privacy_anonymize), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            if (maskSensitive)
                                stringResource(R.string.privacy_anonymize_on)
                            else
                                stringResource(R.string.privacy_anonymize_off),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (maskSensitive) MaterialTheme.colorScheme.onSurfaceVariant
                                    else MaterialTheme.colorScheme.error,
                        )
                    }
                    Switch(checked = maskSensitive, onCheckedChange = { maskSensitive = it })
                }
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        scope.launch {
                            val db = PulseLoopDatabase.getInstance(context)
                            try {
                                val intent = com.pulseloop.diagnostics.DiagnosticsExporter
                                    .shareIntent(context, db, mask = maskSensitive)
                                context.startActivity(intent)
                            } catch (_: Exception) {}
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.Share, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (maskSensitive) stringResource(R.string.privacy_export_diag) else stringResource(R.string.privacy_export_diag_full))
                }
            }
        }
    }

    // Confirmation/alert dialogs — mutually exclusive via when chain (prevent stacking).
    when {
        statusMessage != null -> {
            val msg = statusMessage!!
            AlertDialog(
                onDismissRequest = { statusMessage = null },
                title = { Text(stringResource(R.string.dialog_error)) },
                text = { Text(msg) },
                confirmButton = {
                    TextButton(onClick = { statusMessage = null }) { Text(stringResource(R.string.action_ok)) }
                },
            )
        }

        showImportSuccess -> AlertDialog(
            onDismissRequest = { showImportSuccess = false },
            title = { Text(stringResource(R.string.privacy_import_done)) },
            text = { Text(stringResource(R.string.privacy_import_done_body)) },
            confirmButton = {
                TextButton(onClick = { showImportSuccess = false }) { Text(stringResource(R.string.action_ok)) }
            },
        )

        showImportConfirm -> AlertDialog(
            onDismissRequest = { showImportConfirm = false; pendingImportUri = null },
            title = { Text(stringResource(R.string.privacy_replace_title)) },
            text = {
                Text(stringResource(R.string.privacy_replace_body))
            },
            confirmButton = {
                TextButton(onClick = {
                    showImportConfirm = false
                    val uri = pendingImportUri ?: return@TextButton
                    pendingImportUri = null
                    importInProgress = true
                    scope.launch {
                        try {
                            val db = PulseLoopDatabase.getInstance(context)
                            com.pulseloop.data.DataArchiveService.importFile(context, uri, db)
                            showImportSuccess = true
                        } catch (_: Exception) {
                            statusMessage = context.getString(R.string.privacy_import_failed)
                        }
                        importInProgress = false
                    }
                }) {
                    Text(stringResource(R.string.privacy_delete_and_import), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showImportConfirm = false; pendingImportUri = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )

        pendingReset != null -> {
            val action = pendingReset!!
            AlertDialog(
                onDismissRequest = { pendingReset = null },
                title = { Text(action.title) },
                text = { Text(action.message) },
                confirmButton = {
                    TextButton(onClick = {
                        pendingReset = null
                        when (action) {
                            is ResetAction.UnpairRing -> performUnpair()
                            is ResetAction.ResetAppData -> performResetAppData()
                            is ResetAction.UnpairAndReset -> performUnpairAndReset()
                        }
                    }) {
                        Text(action.confirmLabel, color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingReset = null }) { Text(stringResource(R.string.action_cancel)) }
                },
            )
        }

        showSeedDialog -> AlertDialog(
            onDismissRequest = { showSeedDialog = false },
            title = { Text(stringResource(R.string.privacy_reseed_title)) },
            text = { Text(stringResource(R.string.privacy_reseed_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showSeedDialog = false
                    scope.launch {
                        DemoDataSeeder.seed(PulseLoopDatabase.getInstance(context))
                        com.pulseloop.widgets.WidgetSnapshotPublisher.publish(context)
                    }
                }) { Text(stringResource(R.string.privacy_reseed)) }
            },
            dismissButton = {
                TextButton(onClick = { showSeedDialog = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )

        showClearDialog -> AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text(stringResource(R.string.privacy_clear_demo_title)) },
            text = {
                Text(
                    stringResource(R.string.privacy_clear_demo_body),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showClearDialog = false
                    scope.launch {
                        DemoDataSeeder.clear(PulseLoopDatabase.getInstance(context))
                        com.pulseloop.widgets.WidgetSnapshotPublisher.publish(context)
                    }
                }) { Text(stringResource(R.string.action_clear), color = PulseColors.danger) }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

// MARK: - About

/**
 * About detail screen (iOS AboutSettingsView): version (7 quick taps unlock Developer options,
 * with escalating haptics + progress ring), app description, update checker, source link,
 * and license. Demo data + diagnostics moved to the Privacy & Data screen (iOS hub parity).
 */
@Composable
fun AboutSettingsScreen(onOpenDebug: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val keyStore = remember { ApiKeyStore(context) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current

    var versionTapCount by remember { mutableIntStateOf(0) }
    var lastVersionTap by remember { mutableLongStateOf(0L) }
    var developerUnlocked by remember { mutableStateOf(keyStore.developerUnlocked) }
    var toast by remember { mutableStateOf<String?>(null) }
    var toastToken by remember { mutableIntStateOf(0) }

    // 0…1 progress toward unlock, used to build an accent tint/border as taps accumulate.
    val tapProgress = if (developerUnlocked) 0f
        else (versionTapCount.coerceAtMost(DEVELOPER_TAP_THRESHOLD - 1).toFloat() / (DEVELOPER_TAP_THRESHOLD - 1))

    fun showToast(message: String) {
        toastToken += 1
        toast = message
    }
    LaunchedEffect(toastToken) {
        if (toast != null) {
            delay(1_600)
            toast = null
        }
    }

    fun registerVersionTap() {
        val now = System.currentTimeMillis()
        // Android-style: 7 quick taps unlock; a >2s pause between taps resets the count.
        if (lastVersionTap > 0 && now - lastVersionTap > 2_000) versionTapCount = 0
        lastVersionTap = now

        if (developerUnlocked) {
            showToast(context.getString(R.string.about_dev_already))
            return
        }

        versionTapCount += 1
        // Escalating haptics: light for the first taps, heavier close to the unlock.
        haptics.performHapticFeedback(
            if (versionTapCount > 3) HapticFeedbackType.LongPress else HapticFeedbackType.TextHandleMove
        )
        val remaining = DEVELOPER_TAP_THRESHOLD - versionTapCount
        if (remaining <= 0) {
            developerUnlocked = true
            keyStore.developerUnlocked = true
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            showToast(context.getString(R.string.about_dev_enabled))
            onOpenDebug()
        } else if (remaining <= 3) {
            showToast(context.resources.getQuantityString(R.plurals.about_dev_steps, remaining, remaining))
        }
    }

    SettingsSubScreen(
        title = stringResource(R.string.settings_about),
        onBack = onBack,
        bottomOverlay = {
            toast?.let { message ->
                Text(
                    message,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = PulseColors.textPrimary,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 28.dp)
                        .clip(CircleShape)
                        .background(PulseColors.elevated)
                        .border(1.dp, PulseColors.borderSubtle, CircleShape)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        },
    ) {
        // Version row: tap 7× to unlock Developer options; a tint/border builds with progress.
        Card(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable { registerVersionTap() }
                .border(1.5.dp, PulseColors.accent.copy(alpha = tapProgress * 0.9f), RoundedCornerShape(12.dp)),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(PulseColors.accent.copy(alpha = tapProgress * 0.22f))
                    .padding(16.dp),
            ) {
                Text(stringResource(R.string.about_version), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "PulseLoop v${com.pulseloop.BuildConfig.VERSION_NAME} (${com.pulseloop.BuildConfig.VERSION_CODE})",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("PulseLoop", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.about_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                com.pulseloop.update.CheckForUpdatesButton()
            }
        }

        // Project — source link + license (iOS AboutSettingsView "Project" section).
        Card(
            Modifier.fillMaxWidth().clickable {
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(REPO_URL)))
                } catch (_: Exception) {
                    // No browser available — nothing to open.
                }
            },
        ) {
            Row(Modifier.padding(16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.about_source), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        REPO_URL.removePrefix("https://"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(Icons.Filled.OpenInNew, null, Modifier.size(16.dp), tint = PulseColors.textMuted)
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(stringResource(R.string.about_license), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.about_license_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

    }
}

private const val DEVELOPER_TAP_THRESHOLD = 7
private const val REPO_URL = "https://github.com/foureight84/PulseLoop"

// MARK: - Nutrition

@Composable
fun NutritionSettingsScreen(onBack: () -> Unit, onNavigateToNutrition: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { PulseLoopDatabase.getInstance(context) }
    var goal by remember { mutableStateOf<UserGoalEntity?>(null) }
    var goalLoaded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { goal = db.userGoalDao().get(); goalLoaded = true }

    SettingsSubScreen(title = stringResource(R.string.settings_nutrition), onBack = onBack) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.nutrition_enable), fontWeight = FontWeight.Medium)
                        Text(stringResource(R.string.nutrition_enable_desc), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = goal?.nutritionEnabled == true,
                        enabled = goalLoaded,
                        onCheckedChange = { enabled ->
                            val g = goal ?: return@Switch
                            scope.launch {
                                goal = g.copy(nutritionEnabled = enabled).also { db.userGoalDao().upsert(it) }
                            }
                        },
                    )
                }
            }
        }

        if (goal?.nutritionEnabled == true) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.nutrition_daily_goals), fontWeight = FontWeight.SemiBold)
                    NutrientStepper(stringResource(R.string.nutrition_calories_kcal), goal?.intakeCalories ?: 2000.0, 50.0, 100.0, 10000.0) { v ->
                        scope.launch { goal = goal?.copy(intakeCalories = v)?.also { db.userGoalDao().upsert(it) } }
                    }
                    NutrientStepper(stringResource(R.string.nutrition_protein_g), goal?.intakeProteinG ?: 150.0, 5.0, 0.0, 500.0) { v ->
                        scope.launch { goal = goal?.copy(intakeProteinG = v)?.also { db.userGoalDao().upsert(it) } }
                    }
                    NutrientStepper(stringResource(R.string.nutrition_carbs_g), goal?.intakeCarbsG ?: 250.0, 5.0, 0.0, 800.0) { v ->
                        scope.launch { goal = goal?.copy(intakeCarbsG = v)?.also { db.userGoalDao().upsert(it) } }
                    }
                    NutrientStepper(stringResource(R.string.nutrition_fat_g), goal?.intakeFatG ?: 65.0, 5.0, 0.0, 300.0) { v ->
                        scope.launch { goal = goal?.copy(intakeFatG = v)?.also { db.userGoalDao().upsert(it) } }
                    }
                    OutlinedButton(onClick = {
                        val g = goal ?: return@OutlinedButton
                        scope.launch {
                            val cal = g.intakeCalories ?: 2000.0
                            goal = g.copy(intakeProteinG = cal * 0.30 / 4.0, intakeCarbsG = cal * 0.40 / 4.0, intakeFatG = cal * 0.30 / 9.0)
                                .also { db.userGoalDao().upsert(it) }
                        }
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.nutrition_balance_macros))
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.nutrition_quick_access), fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = onNavigateToNutrition,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.nutrition_open_log))
                    }
                }
            }
        }
    }
}

@Composable
private fun NutrientStepper(label: String, value: Double, step: Double, min: Double, max: Double, onChange: (Double) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { if (value > min) onChange((value - step).coerceAtLeast(min)) }) { Text("−", fontSize = 18.sp) }
            Text("${value.roundToInt()}", fontWeight = FontWeight.Medium)
            TextButton(onClick = { if (value < max) onChange((value + step).coerceAtMost(max)) }) { Text("+", fontSize = 18.sp) }
        }
    }
}

// MARK: - Strava

@Composable
fun StravaSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tokenStore = remember { com.pulseloop.strava.StravaTokenStore(context) }
    val isConfigured = com.pulseloop.strava.StravaAuth.isConfigured
    var isConnected by remember { mutableStateOf(tokenStore.isConnected) }
    var athleteName by remember { mutableStateOf(tokenStore.get()?.athleteName) }
    var isUploading by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    // The OAuth callback lands in MainActivity, which writes tokens (or a failure message) into
    // the store. Poll for either for 60 s after entering the screen, then stop.
    LaunchedEffect(Unit) {
        val deadline = System.currentTimeMillis() + 60_000L
        while (System.currentTimeMillis() < deadline) {
            tokenStore.lastError()?.let { error ->
                statusMessage = error
                tokenStore.clearLastError()
                return@LaunchedEffect
            }
            val tokens = tokenStore.get()
            if (tokens != null && !isConnected) {
                isConnected = true
                athleteName = tokens.athleteName
                statusMessage = null
                return@LaunchedEffect
            }
            kotlinx.coroutines.delay(1000)
        }
    }

    SettingsSubScreen(title = "Strava", onBack = onBack) {
        if (!isConfigured) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.strava_not_configured), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.strava_not_configured_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            return@SettingsSubScreen
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(stringResource(R.string.strava_integration), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Text(
                    if (isConnected) stringResource(R.string.strava_connected_as, athleteName ?: "")
                    else stringResource(R.string.strava_connect_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                if (isConnected) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                isUploading = true
                                scope.launch {
                                    try {
                                        val db = PulseLoopDatabase.getInstance(context)
                                        val count = com.pulseloop.strava.StravaUploader.uploadAuto(db, tokenStore)
                                        // uploadAuto records why it stopped, if it stopped.
                                        statusMessage = tokenStore.lastError()?.also { tokenStore.clearLastError() }
                                            ?: when (count) {
                                                0 -> context.getString(R.string.strava_nothing_new)
                                                else -> context.resources.getQuantityString(R.plurals.strava_uploaded, count, count)
                                            }
                                    } catch (e: Exception) {
                                        statusMessage = context.getString(R.string.strava_upload_failed, e.message ?: context.getString(R.string.error_unknown))
                                    }
                                    isUploading = false
                                }
                            },
                            modifier = Modifier.weight(1f),
                            enabled = !isUploading,
                        ) {
                            Text(if (isUploading) stringResource(R.string.status_syncing) else stringResource(R.string.wearable_sync_now))
                        }
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    // Revoke on Strava's side first, so PulseLoop also disappears
                                    // from the athlete's "My Apps" list — clearing local tokens
                                    // alone leaves the authorization live forever.
                                    tokenStore.get()?.let {
                                        runCatching { com.pulseloop.strava.StravaAuth.deauthorize(it) }
                                    }
                                    tokenStore.clear()
                                    isConnected = false
                                    athleteName = null
                                    statusMessage = context.getString(R.string.strava_disconnected)
                                }
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        ) {
                            Text(stringResource(R.string.wearable_disconnect))
                        }
                    }
                } else {
                    Button(
                        onClick = {
                            statusMessage = null
                            tokenStore.clearLastError()
                            // Implicit ACTION_VIEW on /oauth/mobile/authorize: the Strava app takes
                            // it when installed, the browser otherwise (Strava's Android docs).
                            val authUrl = com.pulseloop.strava.StravaAuth.generateAuthUrl(tokenStore)
                            val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(authUrl))
                            try {
                                context.startActivity(intent)
                                statusMessage = context.getString(R.string.strava_authorize_browser)
                            } catch (_: Exception) {
                                statusMessage = context.getString(R.string.error_open_browser)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.strava_connect))
                    }
                }
            }
        }

        if (isConnected) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.strava_auto_upload), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.strava_auto_upload_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        statusMessage?.let { msg ->
            Card(
                Modifier.fillMaxWidth(),
                // containerColor alone leaves contentColor Unspecified, so the Text falls back to
                // LocalContentColor (dark) and renders near-invisible on this dark card.
                colors = CardDefaults.cardColors(
                    containerColor = PulseColors.cardSoft,
                    contentColor = PulseColors.textPrimary,
                ),
            ) {
                Text(msg, modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

// MARK: - Health Connect

/**
 * Health Connect export settings — the Android analogue of iOS' Apple Health settings
 * (docs/health-connect-integration.md, Phase 0/1). Availability, permissions, and preferences;
 * the Phase 1 worker owns the actual export. Granting permissions and answering the backfill
 * dialog both enqueue the (debounced, hard-gated) export worker.
 */
@Composable
fun HealthConnectSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { HealthConnectPrefsStore.get(context) }
    val prefs by store.prefs.collectAsState()
    // Re-read on every ON_RESUME (review pass 5): "Install / Update Health Connect" sends the
    // user to Play and back, and a `remember {}` snapshot kept rendering the unavailable card —
    // with LaunchedEffect(availability) never re-firing — until they left the screen and
    // returned. Keying on it makes the whole screen recover by itself.
    val lifecycleOwner = LocalLifecycleOwner.current
    var availability by remember { mutableStateOf(HealthConnectSdk.availability(context)) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) availability = HealthConnectSdk.availability(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val granted = prefs.lastGrantedPermissions.toSet()
    var statusMessage by remember { mutableStateOf<String?>(null) }
    // Any leftover export state is what "Remove PulseLoop data" would actually clear.
    val hasExportState = prefs.lastSyncAt != null ||
        HealthConnectWatermarks.Key.values().any { store.currentWatermarks.get(it) != null }
    // Backfill dialog derived from persisted state (review MINOR): a rotation or process death
    // while it is up would otherwise destroy a `remember`ed flag WITHOUT running
    // onDismissRequest, stranding enabled=true + backfillChoice=NOT_ASKED — the hard-gated
    // "Connected but nothing exports" state with no re-offer. Deriving it the way the revocation
    // offer does makes recreation re-show it and makes the explicit dismiss redundant. Also gated
    // on Health Connect being AVAILABLE (review pass 3): the old imperative flag could only be set
    // from the permission-launcher callback, reachable only in the AVAILABLE branch, so a
    // device whose provider is uninstalled / needs an update never saw this dialog on top of the
    // "needs an update" card.
    val showBackfillDialog = availability == HealthConnectAvailability.AVAILABLE &&
        prefs.enabled && prefs.isConnected &&
        prefs.backfillChoice == HealthConnectPrefs.BackfillChoice.NOT_ASKED
    // Phase 6: full-revocation offer + "remove PulseLoop data" confirmation.
    var showRevokeResetDialog by remember { mutableStateOf(false) }
    var showRemoveDialog by remember { mutableStateOf(false) }

    // Master toggle → Health Connect permission sheet. Partial grants are first-class:
    // any granted permission counts as connected.
    val permissionLauncher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { grantedPermissions ->
        val got = HealthConnectPermissionReconcile.storedSetOf(grantedPermissions).toSet()
        // Phase 6: diff against the previously stored grant. A grow resets the watermarks of the
        // groups the new permissions belong to, so the newly grantable types backfill their history
        // (their shared group watermark is already ahead of their rows). A full revoke offers a
        // watermark reset below.
        val outcome = HealthConnectPermissionReconcile.reconcile(
            store.current.lastGrantedPermissions.toSet(), got, store,
        )
        store.update {
            it.copy(
                enabled = got.isNotEmpty(),
                lastGrantedPermissions = got.sorted(),
                revocationOfferDismissed = if (got.isEmpty()) it.revocationOfferDismissed else false,
            )
        }
        statusMessage = if (got.isEmpty()) {
            context.getString(R.string.hc_no_permissions)
        } else {
            context.getString(R.string.hc_connected_count, got.size, HealthConnectPermissions.all.size)
        }
        // First-enable backfill dialog: the derived showBackfillDialog above now turns true from
        // the persisted state (enabled + connected + NOT_ASKED) this callback just set, so there
        // is nothing to set imperatively. While NOT_ASKED the Phase 1 worker's hard gate keeps the
        // export from running, so the enqueue below is a no-op until the choice is made.
        // Grant trigger (Phase 1, extended in Phase 6): a grant — including a grow-reset — is the
        // moment an export should attempt to run. Debounced 15 s + REPLACE, so cheap on re-grants.
        if (got.isNotEmpty()) HealthConnectExportWorker.enqueue(context)
        // hadSync guard (mirrors the state-based offer below): only offer a reset when there is
        // prior export state to actually reset. A grant-then-immediate-revoke with no export yet
        // has empty watermarks + null lastSyncAt, so the offer would be a no-op with misleading
        // "re-exports your history" wording.
        if (outcome.allRevoked && !store.current.revocationOfferDismissed) {
            val cur = store.current
            val hadSync = cur.lastSyncAt != null ||
                HealthConnectWatermarks.Key.values().any { store.currentWatermarks.get(it) != null }
            if (hadSync) showRevokeResetDialog = true
        }
    }

    // Phase 6: on open, diff the live granted set against the stored one to catch out-of-band
    // changes (a revocation in system Settings, a grant made elsewhere). A grow resets the affected
    // watermarks and enqueues a pass. (onAppStart does the same on activity resume; this is the
    // safety net for a change that landed after resume, and the home of the revocation offer.)
    LaunchedEffect(availability) {
        if (availability != HealthConnectAvailability.AVAILABLE) return@LaunchedEffect
        val last = store.current.lastGrantedPermissions.toSet()
        val client = runCatching { HealthConnectClient.getOrCreate(context) }.getOrNull() ?: return@LaunchedEffect
        val live = runCatching { client.permissionController.getGrantedPermissions() }.getOrNull() ?: return@LaunchedEffect
        val granted = HealthConnectPermissionReconcile.storedSetOf(live).toSet()
        val outcome = HealthConnectPermissionReconcile.reconcile(last, granted, store)
        if (granted != last) store.update { it.copy(lastGrantedPermissions = granted.sorted()) }
        if (outcome.grewGroups.isNotEmpty()) {
            HealthConnectExportWorker.enqueue(context)
            // A re-grant (grow) clears the one-shot revocation-offer flag so a future full
            // revocation offers again.
            if (store.current.revocationOfferDismissed) store.update { it.copy(revocationOfferDismissed = false) }
        }
        // Full-revocation offer (Gadgetbridge pattern) — state-based, not diff-based: onAppStart
        // may already have reconciled and stored the (now empty) live set before this screen
        // opened, making the diff above empty-to-empty so allRevoked never fires. Offer when the
        // master is on, nothing is granted now, a prior sync exists (something to reset), and the
        // user has not already dismissed the offer.
        val cur = store.current
        val hadSync = cur.lastSyncAt != null ||
            HealthConnectWatermarks.Key.values().any { store.currentWatermarks.get(it) != null }
        if (cur.enabled && granted.isEmpty() && hadSync && !cur.revocationOfferDismissed) {
            showRevokeResetDialog = true
        }
    }

    var currentRealDevice by remember { mutableStateOf<com.pulseloop.data.entity.DeviceEntity?>(null) }
    LaunchedEffect(Unit) {
        currentRealDevice = withContext(Dispatchers.IO) {
            PulseLoopDatabase.getInstance(context).deviceDao().currentReal()
        }
    }
    val isYcbtDevice = currentRealDevice?.deviceType in setOf(
        com.pulseloop.ring.RingDeviceType.YCBT,
        com.pulseloop.ring.RingDeviceType.COLMI_SMART_HEALTH,
    )

    SettingsSubScreen(title = "Health Connect", onBack = onBack) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Health Connect", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.hc_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))

                when (availability) {
                    HealthConnectAvailability.UNAVAILABLE -> {
                        Text(
                            stringResource(R.string.hc_unavailable),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { openHealthConnectPlayStore(context) }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.hc_install))
                        }
                    }

                    HealthConnectAvailability.PROVIDER_UPDATE_REQUIRED -> {
                        Text(
                            stringResource(R.string.hc_needs_update),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { openHealthConnectPlayStore(context) }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.hc_update))
                        }
                    }

                    HealthConnectAvailability.AVAILABLE -> {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.hc_export), style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    if (prefs.isConnected) stringResource(R.string.status_connected) else stringResource(R.string.status_not_connected),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = prefs.enabled,
                                onCheckedChange = { on ->
                                    if (on) {
                                        permissionLauncher.launch(HealthConnectPermissions.all)
                                    } else {
                                        store.update { it.copy(enabled = false) }
                                    }
                                },
                            )
                        }
                        // Always reachable (review pass 5). Two states have no other way out:
                        // once every permission is revoked the app can no longer delete what it
                        // wrote (deletion needs WRITE), and after two denials the platform stops
                        // showing the permission sheet at all, so the master switch silently does
                        // nothing. Both are resolved in the Health Connect app.
                        Spacer(Modifier.height(8.dp))
                        TextButton(
                            onClick = { openHealthConnectApp(context) },
                            modifier = Modifier.align(Alignment.Start),
                        ) { Text(stringResource(R.string.hc_open)) }
                        if (!prefs.isConnected) {
                            Text(
                                stringResource(R.string.hc_manage_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        if (availability == HealthConnectAvailability.AVAILABLE && prefs.isConnected) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.hc_data_types), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.hc_data_types_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    val rows = listOf(
                        HealthConnectPermissions.DataTypeRow.HEART_RATE to stringResource(R.string.metric_heart_rate),
                        HealthConnectPermissions.DataTypeRow.OXYGEN_SATURATION to stringResource(R.string.hc_type_spo2),
                        HealthConnectPermissions.DataTypeRow.HEART_RATE_VARIABILITY to stringResource(R.string.hc_type_hrv),
                        HealthConnectPermissions.DataTypeRow.BODY_TEMPERATURE to stringResource(R.string.hc_type_body_temp),
                        HealthConnectPermissions.DataTypeRow.SLEEP to stringResource(R.string.metric_sleep),
                        HealthConnectPermissions.DataTypeRow.STEPS_AND_ACTIVITY to stringResource(R.string.hc_type_steps),
                        HealthConnectPermissions.DataTypeRow.WORKOUTS to stringResource(R.string.metric_workouts),
                        HealthConnectPermissions.DataTypeRow.NUTRITION to stringResource(R.string.settings_nutrition),
                        // Phase 5 (beyond iOS).
                        HealthConnectPermissions.DataTypeRow.BLOOD_PRESSURE to stringResource(R.string.metric_blood_pressure),
                        HealthConnectPermissions.DataTypeRow.BLOOD_GLUCOSE to stringResource(R.string.hc_type_glucose),
                        HealthConnectPermissions.DataTypeRow.RESPIRATORY_RATE to stringResource(R.string.metric_respiratory_rate),
                        HealthConnectPermissions.DataTypeRow.VO2_MAX to stringResource(R.string.metric_vo2max),
                        HealthConnectPermissions.DataTypeRow.RESTING_HEART_RATE to stringResource(R.string.metric_resting_hr),
                    )
                    for ((row, label) in rows) {
                        val isRespRateOnYcbt = row == HealthConnectPermissions.DataTypeRow.RESPIRATORY_RATE && isYcbtDevice
                        val rowPerms = HealthConnectPermissions.permissionsForRow(row)
                        val rowGranted = !isRespRateOnYcbt && (rowPerms.isEmpty() || rowPerms.any { it in granted })
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(label, style = MaterialTheme.typography.bodyMedium)
                                if (isRespRateOnYcbt) {
                                    Text(
                                        stringResource(R.string.hc_resp_derived),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                } else if (!rowGranted) {
                                    Text(
                                        stringResource(R.string.hc_awaiting_permission),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            Switch(
                                checked = if (isRespRateOnYcbt) false else prefs.toggleFor(row),
                                enabled = rowGranted,
                                onCheckedChange = { on ->
                                    store.update { p -> p.withToggleFor(row, on) }
                                    // Re-enabling a VITALS kind must backfill the readings recorded while it
                                    // was off: the VITALS group watermark is driven by the *enabled* kinds
                                    // and advances past a disabled kind's rows, so without this reset the
                                    // off-period data sits below the watermark and never re-selects. This is
                                    // the toggle-side equivalent of the permission grow-reset. The single-
                                    // type groups (sleep/activity/workouts/nutrition/resting HR) freeze their
                                    // watermark while off and backfill on re-enable, so they need no reset.
                                    if (on && HealthConnectPermissionReconcile.groupFor(row) == HealthConnectWatermarks.Key.VITALS) {
                                        store.resetWatermarks(setOf(HealthConnectWatermarks.Key.VITALS))
                                        HealthConnectExportWorker.enqueue(context)
                                    }
                                },
                            )
                        }
                        if (row == HealthConnectPermissions.DataTypeRow.HEART_RATE_VARIABILITY) {
                            // Plan §7 open item: the rings' HRV metric is vendor-undocumented, and
                            // Health Connect has no SDNN type — we export it as RMSSD.
                            Text(
                                stringResource(R.string.hc_hrv_note),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

        }

        // Shown while there is anything to report or remove — deliberately NOT gated on
        // isConnected (review pass 5). Revoking every permission in system Settings used to hide
        // this whole section, which is exactly when a user wants to clear what was already
        // exported; the card now stays, and says plainly what it can and cannot do without
        // permissions.
        if (availability == HealthConnectAvailability.AVAILABLE && (prefs.isConnected || hasExportState)) {
            Card(
                Modifier.fillMaxWidth(),
                // containerColor alone leaves contentColor Unspecified, so the Text falls back to
                // LocalContentColor (dark) and renders near-invisible on this dark card.
                colors = CardDefaults.cardColors(
                    containerColor = PulseColors.cardSoft,
                    contentColor = PulseColors.textPrimary,
                ),
            ) {
                val syncedAt = prefs.lastSyncAt
                Text(
                    if (syncedAt != null) {
                        // The label promises a time, so show one (review pass 5): the summary
                        // alone read as "Last sync: skipped: …" with no indication of when.
                        stringResource(R.string.hc_last_sync, DeviceHeroStatus.relativeShort(syncedAt, System.currentTimeMillis()), prefs.lastSyncSummary ?: stringResource(R.string.status_unknown))
                    } else {
                        stringResource(R.string.hc_no_export_yet)
                    },
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            // Phase 6: "Remove PulseLoop data from Health Connect" (iOS removeAllExportedData parity).
            // Deletes only the records this app wrote, then clears every watermark + the last-sync
            // stamp so the export state matches an empty Health store.
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.hc_remove_exported), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.hc_remove_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (!prefs.isConnected) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.hc_remove_no_permission),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    val removing = prefs.removalStatus == HealthConnectPrefs.REMOVAL_IN_PROGRESS
                    Button(
                        onClick = { showRemoveDialog = true },
                        enabled = !removing,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (removing) stringResource(R.string.status_removing) else stringResource(R.string.hc_remove_data)) }
                }
            }
        }

        // Removal runs in a worker, so its outcome arrives here through the store rather than
        // through this screen's own state — it survives navigating away and process death.
        prefs.removalStatus?.takeIf { it != HealthConnectPrefs.REMOVAL_IN_PROGRESS }?.let { msg ->
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = PulseColors.cardSoft,
                    contentColor = PulseColors.textPrimary,
                ),
            ) {
                Row(
                    Modifier.padding(16.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(msg, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { store.update { it.copy(removalStatus = null) } }) { Text(stringResource(R.string.action_dismiss)) }
                }
            }
        }

        statusMessage?.let { msg ->
            Card(
                Modifier.fillMaxWidth(),
                // containerColor alone leaves contentColor Unspecified, so the Text falls back to
                // LocalContentColor (dark) and renders near-invisible on this dark card.
                colors = CardDefaults.cardColors(
                    containerColor = PulseColors.cardSoft,
                    contentColor = PulseColors.textPrimary,
                ),
            ) {
                Text(msg, modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }

    if (showBackfillDialog) {
        AlertDialog(
            // Dismissing (back / outside tap) is a Cancel: iOS's equivalent sets masterEnabled =
            // false. Turning the master off flips the derived showBackfillDialog above back to
            // false (it no longer holds enabled + connected + NOT_ASKED), which also keeps the
            // on-screen state honest — re-enabling re-offers the dialog.
            onDismissRequest = { store.update { it.copy(enabled = false) } },
            title = { Text(stringResource(R.string.hc_backfill_title)) },
            // No "change this later" promise: there is no UI to change backfillChoice short of
            // "Remove PulseLoop data", which resets it to NOT_ASKED and re-offers this dialog.
            text = { Text(stringResource(R.string.hc_backfill_body)) },
            confirmButton = {
                TextButton(onClick = {
                    store.update { it.copy(backfillChoice = HealthConnectPrefs.BackfillChoice.EXPORT_ALL) }
                    HealthConnectExportWorker.enqueue(context) // answer the gate → run the pass
                }) { Text(stringResource(R.string.hc_backfill_all)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    store.update { it.copy(backfillChoice = HealthConnectPrefs.BackfillChoice.EXPORT_NEW_ONLY) }
                    HealthConnectExportWorker.enqueue(context) // stamps watermarks, exports nothing
                }) { Text(stringResource(R.string.hc_backfill_new)) }
            },
        )
    }

    // Phase 6: full-revocation offer (Gadgetbridge HealthConnectResetDialogFragment pattern). A
    // later re-grant is a "grow" from empty, so the automatic grow-reset re-exports regardless;
    // this lets the user reset immediately and clears the last-sync stamp too.
    if (showRevokeResetDialog) {
        AlertDialog(
            onDismissRequest = { showRevokeResetDialog = false; store.update { it.copy(revocationOfferDismissed = true) } },
            title = { Text(stringResource(R.string.hc_revoked_title)) },
            text = { Text(stringResource(R.string.hc_revoked_body)) },
            confirmButton = {
                TextButton(onClick = {
                    store.clearWatermarks()
                    store.update { it.copy(lastSyncAt = null, lastSyncSummary = null, revocationOfferDismissed = true) }
                    showRevokeResetDialog = false
                    statusMessage = context.getString(R.string.hc_export_reset_done)
                }) { Text(stringResource(R.string.hc_reset_export)) }
            },
            dismissButton = {
                TextButton(onClick = { showRevokeResetDialog = false; store.update { it.copy(revocationOfferDismissed = true) } }) { Text(stringResource(R.string.action_not_now)) }
            },
        )
    }

    // Phase 6: "Remove PulseLoop data" confirmation. Deletion is per record type over the full
    // time range (only types still granted), then all watermarks + the last-sync stamp clear.
    if (showRemoveDialog) {
        AlertDialog(
            onDismissRequest = { showRemoveDialog = false },
            title = { Text(stringResource(R.string.hc_remove_title)) },
            text = { Text(stringResource(R.string.hc_remove_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showRemoveDialog = false
                    // Enqueued, not launched in this screen's scope (review pass 5): a back-press
                    // mid-delete used to cancel the removal between record types, leaving some
                    // deleted, the rest alive, and the watermarks never cleared — unrepairable
                    // from a write-only client. The worker reports back via prefs.removalStatus.
                    HealthConnectRemovalWorker.enqueue(context)
                }) { Text(stringResource(R.string.action_remove)) }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveDialog = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

/**
 * Opens PulseLoop's own page inside the Health Connect app — permissions plus Health Connect's
 * own "delete app data". This is the way out of the two states PulseLoop cannot fix itself: after
 * the platform stops showing the permission sheet (two denials) the master switch silently does
 * nothing, and once every permission is revoked the app can no longer delete what it wrote
 * (deletion needs WRITE). The client builds the right intent for both the API 34+ platform module
 * and the pre-34 APK; if it cannot be resolved at all, fall back to the store listing.
 */
private fun openHealthConnectApp(context: Context) {
    try {
        context.startActivity(HealthConnectClient.getHealthConnectManageDataIntent(context))
    } catch (_: Exception) {
        openHealthConnectPlayStore(context)
    }
}

private fun openHealthConnectPlayStore(context: Context) {
    // API < 34: Health Connect is a separate APK from Play (plan §2, caveat 1).
    val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.apps.healthdata"))
    val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=com.google.android.apps.healthdata"))
    try {
        context.startActivity(market)
    } catch (_: Exception) {
        // Devices without Play (e.g. F-Droid) at least get the web listing.
        runCatching { context.startActivity(web) }
    }
}


/**
 * "About 1d 16h left — draining 2.4 %/h", or an honest silence (issue #65).
 *
 * Fitted over the current discharge run rather than the whole window, because a week of history
 * almost always contains a charge — see [com.pulseloop.service.BatteryProjection]. Shown as an
 * estimate, in those words, because that is what it is: a straight line through past drain, which
 * says nothing about a day spent measuring more often than the last one.
 */
@Composable
private fun BatteryRuntimeEstimate(samples: List<VitalSample>) {
    val estimate = remember(samples) {
        com.pulseloop.service.BatteryProjection.estimate(
            samples.map { com.pulseloop.service.BatteryProjection.Sample(it.timestampMs, it.value) }
        )
    }
    if (estimate == null) {
        Text(
            stringResource(R.string.battery_runtime_not_enough),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Column {
        Text(
            stringResource(R.string.battery_runtime_left, estimate.label),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            stringResource(R.string.battery_runtime_rate, "%.1f".format(estimate.percentPerHour)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
