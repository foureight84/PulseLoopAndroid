package com.pulseloop.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.pulseloop.coach.config.CoachProviderMode
import com.pulseloop.coach.config.CoachProviderSettingsStore
import com.pulseloop.coach.config.GeminiModel
import com.pulseloop.coach.config.OpenRouterModel
import com.pulseloop.data.DemoDataSeeder
import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.notifications.CoachNotifications
import com.pulseloop.ring.MeasurementKind
import com.pulseloop.service.RingSyncWorker
import com.pulseloop.settings.ApiKeyStore
import com.pulseloop.settings.UnitSystem
import kotlinx.coroutines.launch

/**
 * Ported from SettingsView.swift + CoachSettingsSection.swift.
 * Settings screen: API key, model selection, provider mode, write tools,
 * live measurements, coach memory list, notifications, demo data.
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
    val scope = rememberCoroutineScope()

    var apiKey by remember { mutableStateOf(keyStore.apiKey) }
    var apiKeyVisible by remember { mutableStateOf(false) }
    var selectedModel by remember { mutableStateOf(keyStore.model) }
    var coachEnabled by remember { mutableStateOf(keyStore.coachEnabled) }
    var webSearch by remember { mutableStateOf(keyStore.webSearchEnabled) }
    var writeTools by remember { mutableStateOf(keyStore.writeToolsEnabled) }
    var liveMeasurements by remember { mutableStateOf(keyStore.liveMeasurementsEnabled) }
    var notificationEnabled by remember { mutableStateOf(keyStore.notificationsEnabled) }
    var showSeedDialog by remember { mutableStateOf(false) }
    var showMemory by remember { mutableStateOf(false) }

    // Coach memories — loaded on composition via LaunchedEffect
    val db = remember { PulseLoopDatabase.getInstance(context) }
    var memories by remember { mutableStateOf(emptyList<com.pulseloop.data.entity.CoachMemoryEntity>()) }
    LaunchedEffect(coachEnabled) {
        if (coachEnabled) {
            memories = db.coachMemoryDao().allRanked()
        }
    }

    val models = listOf("gpt-5.4", "gpt-4o", "gpt-4o-mini", "o4-mini")

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)

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
                    Switch(checked = coachEnabled, onCheckedChange = {
                        coachEnabled = it; keyStore.coachEnabled = it
                        if (!it) {
                            CoachNotifications.cancel(context)
                            keyStore.notificationsEnabled = false
                            notificationEnabled = false
                        }
                    })
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
                    var geminiModel by remember { mutableStateOf(providerStore.geminiModel) }
                    var orModel by remember { mutableStateOf(providerStore.openRouterModel) }
                    var orPrivacy by remember { mutableStateOf(providerStore.orPrivacyRouting) }
                    var orSort by remember { mutableStateOf(providerStore.orProviderSort) }
                    var reasoningEffort by remember { mutableStateOf(providerStore.reasoningEffort) }
                    var imageInput by remember { mutableStateOf(providerStore.imageInputEnabled) }

                    val providerOptions = listOf(
                        CoachProviderMode.USER_OPENAI_KEY to "OpenAI",
                        CoachProviderMode.USER_GEMINI_KEY to "Google Gemini",
                        CoachProviderMode.USER_OPENROUTER_KEY to "OpenRouter (100+ models)",
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
                            keyboardActions = KeyboardActions(onDone = { onSave() }),
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = onSave, enabled = value.isNotBlank(), modifier = Modifier.weight(1f)) {
                                Text(if (saved) "Update Key" else "Save Key")
                            }
                            if (saved) {
                                OutlinedButton(onClick = onRemove, modifier = Modifier.weight(1f)) { Text("Remove") }
                            }
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
                        "Reasoning effort", reasoningEffort ?: "medium",
                        listOf("low" to "Fastest", "medium" to "Balanced (default)", "high" to "Deepest — more tokens"),
                    ) { providerStore.reasoningEffort = it; reasoningEffort = it }

                    // Image attachments (iOS #31): gates the attach button in the coach chat.
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Image attachments")
                            Text("Attach photos to coach messages", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = imageInput, onCheckedChange = { imageInput = it; providerStore.imageInputEnabled = it })
                    }

                    HorizontalDivider()

                    // Tool toggles
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Web Search")
                            Text("Uses additional tokens", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = webSearch, onCheckedChange = {
                            webSearch = it; keyStore.webSearchEnabled = it
                        })
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

        // Notifications section — ported from CoachSettingsSection notificationsSection
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Notifications", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(12.dp))

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Daily Check-in Notifications")
                    Switch(checked = notificationEnabled, onCheckedChange = { enabled ->
                        notificationEnabled = enabled
                        keyStore.notificationsEnabled = enabled
                        if (enabled) {
                            CoachNotifications.schedule(context)
                        } else {
                            CoachNotifications.cancel(context)
                        }
                    })
                }

                if (notificationEnabled) {
                    Spacer(Modifier.height(8.dp))
                    // Morning time picker
                    var morningHour by remember { mutableStateOf(keyStore.morningHour) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Morning")
                        var amExpanded by remember { mutableStateOf(false) }
                        Box {
                            OutlinedButton(onClick = { amExpanded = true }) {
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
                            OutlinedButton(onClick = { pmExpanded = true }) {
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

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Units", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                var useImperial by remember { mutableStateOf(keyStore.resolvedUnitSystem == UnitSystem.IMPERIAL) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Use Imperial units")
                        Text(
                            if (keyStore.unitSystem == null) "Auto-detected: ${keyStore.resolvedUnitSystem.label}"
                            else "Manual override",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = useImperial, onCheckedChange = {
                        useImperial = it
                        keyStore.unitSystem = if (it) UnitSystem.IMPERIAL.name else UnitSystem.METRIC.name
                    })
                }
                if (keyStore.unitSystem != null) {
                    TextButton(onClick = {
                        keyStore.unitSystem = null
                        useImperial = keyStore.resolvedUnitSystem == UnitSystem.IMPERIAL
                    }) {
                        Text("Reset to auto-detect", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        // Profile — feeds the ring's blood-sugar (profile-derived) and calorie algorithms
        // (setUserInfo 0x02). BP is a direct sensor reading, calibrated separately below.
        // Optional BP calibration via setBPAdjust (0x33). Only applicable to 56ff/Jring
        // rings; Colmi rings don't support blood pressure or blood sugar at all.
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                val device = db.deviceDao().currentFlow().collectAsState(initial = null)
                val isColmi = device.value?.deviceType == com.pulseloop.ring.RingDeviceType.COLMI_R02

                Text("Profile", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))

                if (isColmi) {
                    Text(
                        "Your Colmi ring measures heart rate, SpO\u2082, HRV, stress, temperature, and sleep directly from its sensors. Profile, blood pressure calibration, and blood sugar calibration are only needed for 56ff/Jring rings — your Colmi ring doesn't support blood pressure or blood sugar.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                Text(
                    "Your age, sex, height and weight let the ring compute accurate blood sugar and calories. Blood pressure is measured directly by the sensor.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))

                // Shared editor (iOS #48) — same component as the onboarding Profile step.
                // Units live in the dedicated Units card above, so hide that section here.
                var profileDraft by remember { mutableStateOf<com.pulseloop.ui.onboarding.ProfileDraft?>(null) }
                var bpSys by remember { mutableStateOf("") }
                var bpDia by remember { mutableStateOf("") }
                var glucoseRef by remember { mutableStateOf("") }
                var glucoseOffset by remember { mutableStateOf(0.0) }
                var glucoseMsg by remember { mutableStateOf<String?>(null) }
                var savedMsg by remember { mutableStateOf<String?>(null) }

                LaunchedEffect(Unit) {
                    val p = db.userProfileDao().get()
                    profileDraft = com.pulseloop.ui.onboarding.ProfileDraft.from(
                        p, existingUnits = keyStore.resolvedUnitSystem,
                    )
                    bpSys = keyStore.bpAdjustSystolic.takeIf { it > 0 }?.toString() ?: ""
                    bpDia = keyStore.bpAdjustDiastolic.takeIf { it > 0 }?.toString() ?: ""
                    glucoseOffset = keyStore.glucoseOffsetMgdl
                    glucoseRef = keyStore.glucoseRefMgdl.takeIf { it > 0 }?.let { "%.0f".format(it) } ?: ""
                }

                profileDraft?.let { draft ->
                    com.pulseloop.ui.onboarding.ProfileEditor(
                        draft = draft,
                        onDraftChange = { profileDraft = it },
                        showUnits = false,
                    )
                }

                Spacer(Modifier.height(16.dp))
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

                Spacer(Modifier.height(16.dp))
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

                Spacer(Modifier.height(12.dp))
                Button(
                    enabled = profileDraft != null,
                    onClick = {
                        scope.launch {
                            val draft = profileDraft ?: return@launch
                            val existing = db.userProfileDao().get()
                            val entity = draft.applyTo(existing ?: com.pulseloop.data.entity.UserProfileEntity())
                            db.userProfileDao().upsert(entity)
                            val sysI = bpSys.toIntOrNull() ?: 0
                            val diaI = bpDia.toIntOrNull() ?: 0
                            keyStore.bpAdjustSystolic = sysI
                            keyStore.bpAdjustDiastolic = diaI
                            coordinator?.applyUserSettings(entity, sysI, diaI)
                            savedMsg = if (coordinator?.isConnected == true)
                                "Saved & sent to ring ✓" else "Saved — will sync on next connect"
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Save profile") }
                savedMsg?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
                } // end else (!isColmi)
            }
        }

        // Goals — the shared GoalEditor (iOS #48), same targets as the onboarding Goals step
        // plus the weekly workouts slider.
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Goals", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Daily and weekly targets behind the activity rings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))

                val goalUnits = keyStore.resolvedUnitSystem
                var goalDraft by remember { mutableStateOf<com.pulseloop.ui.onboarding.GoalDraft?>(null) }
                var goalSavedMsg by remember { mutableStateOf<String?>(null) }
                LaunchedEffect(Unit) {
                    goalDraft = com.pulseloop.ui.onboarding.GoalDraft.from(db.userGoalDao().get(), goalUnits)
                }
                goalDraft?.let { draft ->
                    com.pulseloop.ui.onboarding.GoalEditor(
                        draft = draft,
                        onDraftChange = { goalDraft = it; goalSavedMsg = null },
                        units = goalUnits,
                        includeWeeklyWorkouts = true,
                    )
                    Spacer(Modifier.height(12.dp))
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
                                goalSavedMsg = if (coordinator?.isConnected == true)
                                    "Saved & sent to ring ✓" else "Saved"
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Save goals") }
                    goalSavedMsg?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }

        // Measurement section — ported from MeasurementSettingsView.swift (iOS #19).
        // Per-device all-day measurement config: HR sampling interval + per-vital toggles,
        // persisted in Room and pushed to the ring on Save (or on the next connect handshake).
        // Only rings that declare MEASUREMENT_INTERVAL (Colmi) show this; jring has no such control.
        run {
            val device = db.deviceDao().currentFlow().collectAsState(initial = null).value
            if (device?.capabilities?.contains(com.pulseloop.ring.WearableCapability.MEASUREMENT_INTERVAL) != true) return@run

            var cfgHrEnabled by remember { mutableStateOf(true) }
            var cfgHrInterval by remember { mutableStateOf(5) }
            var cfgSpo2 by remember { mutableStateOf(true) }
            var cfgStress by remember { mutableStateOf(true) }
            var cfgHrv by remember { mutableStateOf(true) }
            var cfgTemp by remember { mutableStateOf(true) }
            var cfgLoaded by remember { mutableStateOf(false) }
            var cfgSavedMsg by remember { mutableStateOf<String?>(null) }

            LaunchedEffect(device.id) {
                db.deviceMeasurementConfigDao().byDevice(device.id)?.let { c ->
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

        // Ring — connection management & unpair
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Ring", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                // Observe the device row reactively so the connection status reflects
                // live BLE state changes (connect / disconnect / reconnect) in real time
                // rather than a value frozen at screen-open.
                val device = db.deviceDao().currentFlow().collectAsState(initial = null)
                val isConnected = device.value?.stateRaw == "CONNECTED"
                val bleState = bleClient?.state?.collectAsState()
                val supportsFactoryReset = bleState?.value?.activeCapabilities
                    ?.contains(com.pulseloop.ring.WearableCapability.FACTORY_RESET) == true
                var showFactoryReset by remember { mutableStateOf(false) }
                var resetting by remember { mutableStateOf(false) }
                var resetStatus by remember { mutableStateOf("") }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Bluetooth, null, Modifier.size(18.dp),
                        tint = if (isConnected) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (isConnected) "Connected — ${com.pulseloop.ring.ringModelLabel(device.value?.name, device.value?.deviceType)} · ${device.value?.batteryPercent ?: 0}%"
                        else device.value?.let { "Last seen: ${com.pulseloop.ring.ringModelLabel(it.name, it.deviceType)}" } ?: "No ring paired",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                // Firmware version — show just the trailing version (e.g. "V138"), matching
                // the official app, which displays only the last segment of "003A002AV138".
                if (device.value?.firmwareVersion != null) {
                    val rawFw = device.value!!.firmwareVersion!!
                    val fwDisplay = if (rawFw.contains("V")) "V" + rawFw.substringAfterLast("V") else rawFw
                    Text("Firmware: $fwDisplay", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
                } else if (device.value != null) {
                    Text("Firmware: reading… (connect ring to read)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
                }
                if (device.value == null) {
                    // No ring yet — route into the redesigned pairing screen (iOS #48).
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { navController?.navigate("pairing") },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.Add, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Add a ring")
                    }
                }
                if (device.value != null) {
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
        }

        // Demo data
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Demo Data", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Explore the app without a ring. Seeds 7 days of activity, HR, SpO2, sleep, and a coach conversation.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { showSeedDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Reseed Demo Data")
                }
            }
        }

        // Developer
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Developer", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Text("View raw ring data, BLE packets, database stats, and export diagnostics.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = { navController?.navigate("debug") },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.BugReport, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Open Debug View")
                }
            }
        }

        // About
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("About", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "PulseLoop v${com.pulseloop.BuildConfig.VERSION_NAME} (${com.pulseloop.BuildConfig.VERSION_CODE})",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "Open-source health tracker. Ported from iOS.\nCC BY 4.0 · github.com/foureight84/PulseLoop",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                com.pulseloop.update.CheckForUpdatesButton()
            }
        }

        Spacer(Modifier.height(32.dp))
    }

    // Seed confirmation dialog
    if (showSeedDialog) {
        AlertDialog(
            onDismissRequest = { showSeedDialog = false },
            title = { Text("Reseed Demo Data?") },
            text = { Text("This will replace all existing demo data. Your synced ring data will not be affected.") },
            confirmButton = {
                TextButton(onClick = {
                    showSeedDialog = false
                    scope.launch {
                        DemoDataSeeder.seed(PulseLoopDatabase.getInstance(context))
                    }
                }) { Text("Reseed") }
            },
            dismissButton = {
                TextButton(onClick = { showSeedDialog = false }) { Text("Cancel") }
            },
        )
    }
}
