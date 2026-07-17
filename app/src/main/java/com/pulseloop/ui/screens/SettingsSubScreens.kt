package com.pulseloop.ui.screens

import android.Manifest
import android.content.Intent
import android.os.Build
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import com.pulseloop.coach.config.MiniMaxModel
import com.pulseloop.coach.config.OpenRouterModel
import com.pulseloop.data.DemoDataSeeder
import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.notifications.CoachNotifications
import com.pulseloop.ring.MeasurementKind
import com.pulseloop.ring.RingBLEClient
import com.pulseloop.ring.RingConnectionState
import com.pulseloop.service.GlucoseUnit
import com.pulseloop.service.RingSyncCoordinator
import com.pulseloop.service.RingSyncWorker
import com.pulseloop.settings.ApiKeyStore
import com.pulseloop.settings.UnitSystem
import com.pulseloop.ui.components.DeviceHeroStatus
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
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
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
    var showMemory by remember { mutableStateOf(false) }

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

    val models = listOf("gpt-5.4", "gpt-4o", "gpt-4o-mini", "o4-mini")

    SettingsSubScreen(title = "AI Coach", onBack = onBack) {
        // AI Coach section — ported from CoachSettingsSection.swift
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("AI Coach", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    if (coachEnabled && keyStore.apiKey.isNotBlank()) "Active — ${selectedModel}"
                    else if (coachEnabled) "API key needed"
                    else "Disabled",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))

                // Master toggle
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Enable AI Coach")
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
                        "Notifications are off for PulseLoop. Turn them on in Android Settings to get check-ins.",
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
                    var imageInput by remember { mutableStateOf(providerStore.imageInputEnabled) }

                    val providerOptions = listOf(
                        CoachProviderMode.USER_OPENAI_KEY to "OpenAI",
                        CoachProviderMode.USER_GEMINI_KEY to "Google Gemini",
                        CoachProviderMode.USER_OPENROUTER_KEY to "OpenRouter (100+ models)",
                        CoachProviderMode.USER_MINIMAX_KEY to "MiniMax",
                    )
                    val providerLabel = providerOptions.firstOrNull { it.first == providerMode }?.second ?: "OpenAI"

                    Text("Provider", style = MaterialTheme.typography.labelMedium)
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
                                        contentDescription = "Toggle visibility"
                                    )
                                }
                            },
                            keyboardActions = KeyboardActions(onDone = { if (value.isNotBlank()) save() }),
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Button(onClick = save, enabled = value.isNotBlank(), modifier = Modifier.weight(1f)) {
                                Text(if (saved) "Update Key" else "Save Key")
                            }
                            if (saved) {
                                OutlinedButton(onClick = onRemove, modifier = Modifier.weight(1f)) { Text("Remove") }
                            }
                        }
                        if (justSaved) {
                            Text(
                                "Saved ✓",
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
                            ModelDropdown("Model", geminiModel, GeminiModel.entries.map { it.slug to it.blurb }) {
                                geminiModel = it; providerStore.geminiModel = it
                            }
                            KeyField(
                                label = "Gemini API Key", value = geminiKey, visible = geminiKeyVisible,
                                saved = providerStore.hasGeminiKey,
                                onValue = { geminiKey = it }, onVisibility = { geminiKeyVisible = !geminiKeyVisible },
                                onSave = { providerStore.geminiApiKey = geminiKey },
                                onRemove = { geminiKey = ""; providerStore.geminiApiKey = "" },
                            )
                        }
                        CoachProviderMode.USER_OPENROUTER_KEY -> {
                            ModelDropdown("Model", orModel, OpenRouterModel.entries.map { it.slug to it.blurb }) {
                                orModel = it; providerStore.openRouterModel = it
                            }
                            // The OpenRouter catalog is huge and shifts often — any current
                            // vendor/model slug from openrouter.ai/models works here.
                            OutlinedTextField(
                                value = orModel,
                                onValueChange = { orModel = it; providerStore.openRouterModel = it },
                                label = { Text("Custom model slug (vendor/model)") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                            )
                            Spacer(Modifier.height(8.dp))
                            KeyField(
                                label = "OpenRouter API Key", value = orKey, visible = orKeyVisible,
                                saved = providerStore.hasOpenRouterKey,
                                onValue = { orKey = it }, onVisibility = { orKeyVisible = !orKeyVisible },
                                onSave = { providerStore.openRouterApiKey = orKey },
                                onRemove = { orKey = ""; providerStore.openRouterApiKey = "" },
                            )
                            Spacer(Modifier.height(8.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("Privacy routing")
                                    Text("Only providers that don't retain data", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Switch(checked = orPrivacy, onCheckedChange = { orPrivacy = it; providerStore.orPrivacyRouting = it })
                            }
                            ModelDropdown(
                                "Route by", orSort ?: "auto",
                                listOf("auto" to "Balanced (default)", "price" to "Cheapest first", "throughput" to "Fastest first", "latency" to "Lowest latency first"),
                            ) { providerStore.orProviderSort = it.takeIf { s -> s != "auto" }; orSort = providerStore.orProviderSort }
                        }
                        CoachProviderMode.USER_MINIMAX_KEY -> {
                            ModelDropdown("Model", minimaxModel, MiniMaxModel.entries.map { it.slug to it.blurb }) {
                                minimaxModel = it; providerStore.minimaxModel = it
                            }
                            KeyField(
                                label = "MiniMax API Key", value = minimaxKey, visible = minimaxKeyVisible,
                                saved = providerStore.hasMinimaxKey,
                                onValue = { minimaxKey = it }, onVisibility = { minimaxKeyVisible = !minimaxKeyVisible },
                                onSave = { providerStore.minimaxApiKey = minimaxKey },
                                onRemove = { minimaxKey = ""; providerStore.minimaxApiKey = "" },
                            )
                        }
                        else -> {
                            // OpenAI (and legacy modes): the original model picker + key field.
                            ModelDropdown("Model", selectedModel, models.map { it to "" }) {
                                selectedModel = it; keyStore.model = it
                            }
                            KeyField(
                                label = "OpenAI API Key", value = apiKey, visible = apiKeyVisible,
                                saved = keyStore.apiKey.isNotBlank(),
                                onValue = { apiKey = it }, onVisibility = { apiKeyVisible = !apiKeyVisible },
                                onSave = { keyStore.apiKey = apiKey },
                                onRemove = { apiKey = ""; keyStore.apiKey = "" },
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Stored securely in Android Keystore. Never leaves your device except to call the model. Provider changes apply from your next message.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Spacer(Modifier.height(8.dp))
                    ModelDropdown(
                        "Reasoning effort", reasoningEffort ?: "default",
                        listOf("default" to "Model decides (default)", "low" to "Fastest", "medium" to "Balanced", "high" to "Deepest — more tokens"),
                    ) { providerStore.reasoningEffort = it.takeIf { s -> s != "default" }; reasoningEffort = providerStore.reasoningEffort }

                    // Image attachments (iOS #31): gates the attach button in the coach chat.
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Image attachments")
                            Text("Attach photos to coach messages", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = imageInput, onCheckedChange = { imageInput = it; providerStore.imageInputEnabled = it })
                    }

                    HorizontalDivider()

                    // Tool toggles. MiniMax's compat endpoint has no hosted web search, so hide
                    // the toggle for it (the client drops the tool anyway — this is the UI half).
                    if (providerMode != CoachProviderMode.USER_MINIMAX_KEY) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Web Search")
                                Text("Uses additional tokens", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(checked = webSearch, onCheckedChange = {
                                webSearch = it; keyStore.webSearchEnabled = it
                            })
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Write Actions")
                            Text("Set goals, log, edit data", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = writeTools, onCheckedChange = {
                            writeTools = it; keyStore.writeToolsEnabled = it
                        })
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Live Measurements")
                            Text("Trigger real-time ring readings", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = liveMeasurements, onCheckedChange = {
                            liveMeasurements = it; keyStore.liveMeasurementsEnabled = it
                        })
                    }
                }
            }
        }

        // Coach Memory — ported from CoachSettingsSection memoryRow
        if (memories.isNotEmpty() && coachEnabled) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Coach Memory", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        TextButton(onClick = { showMemory = !showMemory }) {
                            Text(if (showMemory) "Hide" else "Show (${memories.size})")
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
                                        Icon(Icons.Filled.Delete, "Delete", tint = MaterialTheme.colorScheme.error)
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
            title = { Text("Enable Coach Check-Ins?") },
            text = { Text("Get a daily check-in from your coach. You can change this anytime in Coach Check-Ins.") },
            confirmButton = {
                TextButton(onClick = { askEnableCheckIns = false; enableCheckIns() }) { Text("Enable") }
            },
            dismissButton = {
                TextButton(onClick = { askEnableCheckIns = false }) { Text("Not now") }
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

    SettingsSubScreen(title = "Coach Check-Ins", onBack = onBack) {
        if (!coachEnabled) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("AI Coach is off", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Enable the AI Coach to change these — daily check-ins are written by the coach from your recent trends.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Daily check-ins", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(12.dp))

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Daily Check-in Notifications")
                    Switch(
                        enabled = coachEnabled,
                        checked = notificationEnabled,
                        onCheckedChange = { setNotifications(it) },
                    )
                }

                if (notifPermissionDenied) {
                    Text(
                        "Notifications are disabled for PulseLoop in Android Settings.",
                        style = MaterialTheme.typography.bodySmall,
                        color = PulseColors.danger,
                    )
                }

                if (notificationEnabled) {
                    Spacer(Modifier.height(8.dp))
                    // Morning time picker
                    var morningHour by remember { mutableStateOf(keyStore.morningHour) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Morning")
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
                        Text("Evening")
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
                                    "PulseLoop Coach",
                                    "This is a test check-in notification.",
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Send Test Check-in Now")
                    }
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

    SettingsSubScreen(title = "User Profile", onBack = onBack) {
        Text(
            "Your age, sex, height and weight let the ring compute accurate calories (and, on " +
                "56ff/Jring rings, blood sugar). Blood pressure is measured directly by the sensor.",
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
                    Text("Reset units to auto-detect", style = MaterialTheme.typography.labelSmall)
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
                            "Saved & sent to ring ✓" else "Saved — will sync on next connect"
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save profile") }
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

    SettingsSubScreen(title = "Physiology", onBack = onBack) {
        Text(
            "Optional refinements to how your vitals are interpreted. Leave anything unset and " +
                "PulseLoop uses sensible defaults.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        PhysiologyCard(
            title = "Fitness",
            footer = "Treats a low resting heart rate as a sign of fitness rather than a concern, and relaxes the low-HR threshold.",
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Athlete mode", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Switch(checked = athleteMode, onCheckedChange = { athleteMode = it; savedMsg = null })
            }
        }

        PhysiologyCard(
            title = "Environment",
            footer = "Above ~2000 m, normal blood-oxygen readings run lower. We use this to avoid false low-oxygen warnings.",
        ) {
            OutlinedTextField(
                value = altitudeText,
                onValueChange = { altitudeText = it.filter(Char::isDigit).take(5); savedMsg = null },
                label = { Text(if (imperial) "Typical altitude (ft)" else "Typical altitude (m)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        PhysiologyCard(
            title = "Health context",
            footer = "Optional. Both can change what's expected for your heart rate or oxygen, so we adjust labels instead of alarming.",
        ) {
            TriStateRow("Beta-blockers", betaBlockers) { betaBlockers = it; savedMsg = null }
            Spacer(Modifier.height(12.dp))
            TriStateRow("Known lung condition", lungCondition) { lungCondition = it; savedMsg = null }
        }

        PhysiologyCard(title = "Units", footer = null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Glucose unit", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                SingleChoiceSegmentedButtonRow {
                    GlucoseUnit.entries.forEachIndexed { i, u ->
                        SegmentedButton(
                            selected = glucoseUnit == u,
                            onClick = { glucoseUnit = u; savedMsg = null },
                            shape = SegmentedButtonDefaults.itemShape(i, GlucoseUnit.entries.size),
                        ) { Text(u.label) }
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
                savedMsg = "Saved ✓"
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Save") }
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
    val options = listOf<Pair<String, Boolean?>>("Not set" to null, "No" to false, "Yes" to true)
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

    SettingsSubScreen(title = "Calibration", onBack = onBack) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Blood pressure calibration", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "Optional. Enter a recent cuff reading so the ring offsets its values to match.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = bpSys, onValueChange = { bpSys = it.filter(Char::isDigit).take(3) },
                        label = { Text("Systolic") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true, modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = bpDia, onValueChange = { bpDia = it.filter(Char::isDigit).take(3) },
                        label = { Text("Diastolic") },
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
                                "Saved & sent to ring ✓" else "Saved — will sync on next connect"
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Save calibration") }
                savedMsg?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Blood sugar calibration", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "Optional. The ring estimates glucose from your profile, not a real sensor. " +
                        "Enter a recent lab/meter reading (mg/dL) to offset the displayed value to match.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = glucoseRef, onValueChange = { glucoseRef = it.filter(Char::isDigit).take(3) },
                        label = { Text("Reading (mg/dL)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true, modifier = Modifier.weight(1.4f),
                    )
                    Button(
                        onClick = {
                            scope.launch {
                                val ref = glucoseRef.toDoubleOrNull()
                                if (ref == null || ref <= 0) {
                                    glucoseMsg = "Enter a valid mg/dL reading"
                                    return@launch
                                }
                                val latestRaw = db.measurementDao().latest(MeasurementKind.BLOOD_SUGAR.name)
                                if (latestRaw == null) {
                                    glucoseMsg = "Take a blood sugar measurement first, then calibrate"
                                    return@launch
                                }
                                // Stored readings are raw (offset is applied only at display),
                                // so latestRaw is the ring's uncalibrated value.
                                keyStore.glucoseOffsetMgdl = ref - latestRaw
                                keyStore.glucoseRefMgdl = ref
                                glucoseOffset = keyStore.glucoseOffsetMgdl
                                glucoseMsg = "Calibrated ✓ (offset %+.0f mg/dL)".format(glucoseOffset)
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("Calibrate") }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (glucoseOffset != 0.0) "Current offset: %+.0f mg/dL".format(glucoseOffset) else "No calibration set",
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
                            glucoseMsg = "Calibration cleared"
                        }) { Text("Reset") }
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

    SettingsSubScreen(title = "Goals", onBack = onBack) {
        Text(
            "Daily and weekly targets behind the activity rings.",
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
                            "Saved & sent to ring ✓" else "Saved"
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save goals") }
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

    var cfgHrEnabled by remember { mutableStateOf(true) }
    var cfgHrInterval by remember { mutableStateOf(5) }
    var cfgSpo2 by remember { mutableStateOf(true) }
    var cfgStress by remember { mutableStateOf(true) }
    var cfgHrv by remember { mutableStateOf(true) }
    var cfgTemp by remember { mutableStateOf(true) }
    var cfgLoaded by remember { mutableStateOf(false) }
    var cfgSavedMsg by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(device?.id) {
        val id = device?.id ?: return@LaunchedEffect
        db.deviceMeasurementConfigDao().byDevice(id)?.let { c ->
            cfgHrEnabled = c.hrEnabled; cfgHrInterval = c.hrIntervalMinutes
            cfgSpo2 = c.spo2Enabled; cfgStress = c.stressEnabled
            cfgHrv = c.hrvEnabled; cfgTemp = c.temperatureEnabled
        }
        cfgLoaded = true
    }

    @Composable
    fun VitalToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(label)
            Switch(enabled = cfgLoaded, checked = checked, onCheckedChange = { onChange(it); cfgSavedMsg = null })
        }
    }

    SettingsSubScreen(title = "Measurement Frequency", onBack = onBack) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Measurement", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "How often the ring measures in the background. Shorter heart-rate intervals give finer history at the cost of battery.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))

                if (device == null) {
                    Text(
                        "Pair a ring to configure its all-day measurements.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    VitalToggle("All-day heart rate", cfgHrEnabled) { cfgHrEnabled = it }
                    if (cfgHrEnabled) {
                        Text(
                            "Every $cfgHrInterval min",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Slider(
                            enabled = cfgLoaded,
                            value = cfgHrInterval.toFloat(),
                            onValueChange = { cfgHrInterval = ((it / 5).toInt() * 5).coerceIn(5, 60); cfgSavedMsg = null },
                            valueRange = 5f..60f,
                            steps = 10,  // 5-minute stops: 5, 10, …, 60
                        )
                    }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    VitalToggle("Blood oxygen (SpO₂)", cfgSpo2) { cfgSpo2 = it }
                    VitalToggle("Stress", cfgStress) { cfgStress = it }
                    VitalToggle("HRV", cfgHrv) { cfgHrv = it }
                    VitalToggle("Temperature", cfgTemp) { cfgTemp = it }

                    Spacer(Modifier.height(12.dp))
                    Button(
                        enabled = cfgLoaded,
                        onClick = {
                            scope.launch {
                                db.deviceMeasurementConfigDao().upsert(
                                    com.pulseloop.data.entity.DeviceMeasurementConfigEntity(
                                        deviceId = device.id,
                                        hrIntervalMinutes = cfgHrInterval,
                                        hrEnabled = cfgHrEnabled,
                                        spo2Enabled = cfgSpo2,
                                        stressEnabled = cfgStress,
                                        hrvEnabled = cfgHrv,
                                        temperatureEnabled = cfgTemp,
                                        updatedAt = System.currentTimeMillis(),
                                    )
                                )
                                coordinator?.applyMeasurementSettings()
                                cfgSavedMsg = if (coordinator?.isConnected == true)
                                    "Saved & sent to ring ✓" else "Saved — will sync on next connect"
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Save measurement settings") }
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
        ?: "Not yet"

    SettingsSubScreen(title = "Wearable", onBack = onBack) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Bluetooth, null, Modifier.size(18.dp),
                        tint = if (isConnected) PulseColors.success else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (isConnected) "Connected — $wearableDisplayName · ${battery ?: 0}%"
                        else device?.let { "Last seen: $wearableDisplayName" } ?: "No ring paired",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                // Firmware version — show just the trailing version (e.g. "V138"), matching
                // the official app, which displays only the last segment of "003A002AV138".
                if (device?.firmwareVersion != null) {
                    val rawFw = device!!.firmwareVersion!!
                    val fwDisplay = if (rawFw.contains("V")) "V" + rawFw.substringAfterLast("V") else rawFw
                    Text("Firmware: $fwDisplay", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
                } else if (device != null) {
                    Text("Firmware: reading… (connect ring to read)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
                }
                if (device != null) {
                    Text("Last synced: $lastSyncedLabel", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
                }

                if (device == null) {
                    // No ring yet — route into the redesigned pairing screen (iOS #48).
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onAddRing, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.Add, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Add a ring")
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
                        Text("Sync now")
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { coordinator?.findRing() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.NotificationsActive, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Find ring")
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { bleClient?.userDisconnect() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.Close, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Disconnect")
                    }
                } else if (bleClient?.hasLastKnownRing == true &&
                    bleState.connectionState != RingConnectionState.RECONNECTING
                ) {
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = { bleClient.userConnect() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.Refresh, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Reconnect last ring")
                    }
                }
            }
        }

        if (device != null) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Ring management", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Forget unbinds the ring and removes it from the app so it can be paired " +
                            "elsewhere. The app stays connected automatically the rest of the time.",
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
                        Text("Forget Ring")
                    }

                    // Factory reset — destructive, ring-side wipe. Only for connected rings that
                    // support it (Colmi). Syncs latest history first so nothing is lost.
                    if (isConnected && supportsFactoryReset) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Factory reset erases all data stored on the ring itself and resets " +
                                "it to factory state. Your synced data in the app is kept.",
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
                            Text(if (resetting) (resetStatus.ifEmpty { "Resetting…" }) else "Factory Reset Ring")
                        }
                    }
                }
            }
        }
    }

    if (showFactoryReset) {
        AlertDialog(
            onDismissRequest = { showFactoryReset = false },
            title = { Text("Factory reset ring?") },
            text = {
                Text(
                    "This erases all data stored on the ring and resets it to factory " +
                        "state. We'll sync its latest data into the app first, but this " +
                        "can't be undone. The ring will need to be re-paired afterward."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showFactoryReset = false
                    resetting = true
                    resetStatus = "Syncing latest data…"
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
                }) { Text("Reset ring", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showFactoryReset = false }) { Text("Cancel") }
            },
        )
    }
}

// MARK: - Privacy & Data

/**
 * Privacy & Data detail screen (iOS PrivacyDataView): demo-data controls (reseed + clear,
 * moved here from About) and the diagnostics export with its anonymization opt-out.
 * The mask toggle deliberately defaults ON for every visit and is never persisted off —
 * an unmasked export (full BLE frames, health values) is a one-shot, explicit choice.
 */
@Composable
fun PrivacyDataSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showSeedDialog by remember { mutableStateOf(false) }
    var showClearDialog by remember { mutableStateOf(false) }

    SettingsSubScreen(title = "Privacy & Data", onBack = onBack) {
        // Demo data — Android-only (iOS seeds via the Simulator's SeedData).
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Demo Data", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Explore the app without a ring. Seeds activity, HR, SpO2, sleep, and a coach conversation.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = { showSeedDialog = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Reseed Demo Data")
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { showClearDialog = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Clear Demo Data", color = PulseColors.danger)
                }
            }
        }

        // Diagnostics export — same exporter the Developer screen uses, surfaced here so
        // sharing an anonymized log for a bug report doesn't require the 7-tap unlock.
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                var maskSensitive by remember { mutableStateOf(true) }
                Text("Diagnostics", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Export app, device, and wearable logs as JSON for bug reports.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Anonymize export", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            if (maskSensitive)
                                "Removes health values, ring serial & MAC addresses. Keeps models, opcodes & errors."
                            else
                                "OFF — includes full unmasked BLE frames and health values (for protocol debugging only).",
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
                    Text(if (maskSensitive) "Export Diagnostics" else "Export Full (Unmasked)")
                }
            }
        }
    }

    if (showSeedDialog) {
        AlertDialog(
            onDismissRequest = { showSeedDialog = false },
            title = { Text("Reseed Demo Data?") },
            text = { Text("This will replace all existing demo data. Your synced ring data — including sleep history — will not be affected.") },
            confirmButton = {
                TextButton(onClick = {
                    showSeedDialog = false
                    scope.launch {
                        DemoDataSeeder.seed(PulseLoopDatabase.getInstance(context))
                        // Freshly seeded demo data should show up on the widgets too.
                        com.pulseloop.widgets.WidgetSnapshotPublisher.publish(context)
                    }
                }) { Text("Reseed") }
            },
            dismissButton = {
                TextButton(onClick = { showSeedDialog = false }) { Text("Cancel") }
            },
        )
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Clear Demo Data?") },
            text = {
                Text(
                    "Removes all demo measurements, activity, sleep, and the demo device. " +
                    "Synced ring data is not affected.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showClearDialog = false
                    scope.launch {
                        DemoDataSeeder.clear(PulseLoopDatabase.getInstance(context))
                        com.pulseloop.widgets.WidgetSnapshotPublisher.publish(context)
                    }
                }) { Text("Clear", color = PulseColors.danger) }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("Cancel") }
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
            showToast("Developer options are already enabled.")
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
            showToast("You are now a developer!")
            onOpenDebug()
        } else if (remaining <= 3) {
            showToast("You're $remaining step${if (remaining == 1) "" else "s"} away from Developer options.")
        }
    }

    SettingsSubScreen(
        title = "About",
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
                Text("Version", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
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
                    "An LLM-native health app that turns a cheap Bluetooth smart ring into a real, " +
                        "conversational health tracker. It talks to the ring directly over Bluetooth — no " +
                        "vendor cloud, no account — and layers an AI coach on top of your own data.",
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
                    Text("Source on GitHub", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
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
                Text("License", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Open-source health tracker. Ported from iOS.\nCC BY 4.0 · Free to share and " +
                        "adapt, including commercially, with appropriate credit.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

    }
}

private const val DEVELOPER_TAP_THRESHOLD = 7
private const val REPO_URL = "https://github.com/foureight84/PulseLoop"
