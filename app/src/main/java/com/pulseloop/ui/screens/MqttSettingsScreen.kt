package com.pulseloop.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.work.ExistingPeriodicWorkPolicy
import com.pulseloop.R
import com.pulseloop.coach.local.LocalEndpoint
import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.data.entity.DeviceEntity
import com.pulseloop.mqtt.MqttDiscovery
import com.pulseloop.mqtt.MqttExporter
import com.pulseloop.mqtt.MqttLivePublisher
import com.pulseloop.mqtt.MqttPrefsStore
import com.pulseloop.service.RingSyncWorker
import com.pulseloop.ui.components.DeviceHeroStatus
import com.pulseloop.ui.theme.PulseColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val DEFAULT_PLAIN_PORT = "1883"
private const val DEFAULT_TLS_PORT = "8883"

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MqttSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { MqttPrefsStore.get(context) }
    val initialPrefs = remember { store.current }
    val prefs by store.state.collectAsState()

    var host by remember { mutableStateOf(initialPrefs.host) }
    var portText by remember { mutableStateOf(initialPrefs.port.toString()) }
    var useTls by remember { mutableStateOf(initialPrefs.useTls) }
    var username by remember { mutableStateOf(initialPrefs.username) }
    var password by remember { mutableStateOf("") }
    var savedPassword by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var discoveryPrefix by remember { mutableStateOf(initialPrefs.discoveryPrefix) }
    var baseTopic by remember { mutableStateOf(initialPrefs.baseTopic) }
    var deviceNameOverride by remember { mutableStateOf(initialPrefs.deviceNameOverride) }

    var pairedDevice by remember { mutableStateOf<DeviceEntity?>(null) }
    var busy by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var showRemoveDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val loadedPassword = withContext(Dispatchers.IO) { store.getPassword() }
        password = loadedPassword
        savedPassword = loadedPassword
        pairedDevice = withContext(Dispatchers.IO) {
            PulseLoopDatabase.getInstance(context).deviceDao().currentReal()
        }
    }

    fun draftPort(): Int = (portText.toIntOrNull() ?: if (useTls) 8883 else 1883).coerceIn(1, 65535)

    fun draftValid(): Boolean {
        val parsedPort = portText.toIntOrNull() ?: return false
        return host.trim().isNotEmpty() && parsedPort in 1..65535
    }

    fun saveDraftPrefs() {
        val port = draftPort()
        portText = port.toString()
        val trimmedHost = host.trim()
        val trimmedUser = username.trim()
        val trimmedPrefix = discoveryPrefix.trim()
        val trimmedBase = baseTopic.trim()
        val trimmedDevice = deviceNameOverride.trim()
        val connectionChanged = prefs.host != trimmedHost ||
            prefs.port != port ||
            prefs.useTls != useTls ||
            prefs.username != trimmedUser ||
            savedPassword != password

        store.update {
            it.copy(
                host = trimmedHost,
                port = port,
                useTls = useTls,
                username = trimmedUser,
                discoveryPrefix = trimmedPrefix,
                baseTopic = trimmedBase,
                deviceNameOverride = trimmedDevice,
            )
        }
        store.setPassword(password)
        savedPassword = password
        if (connectionChanged) MqttLivePublisher.resetPool()
    }

    fun launchAction(saveFirst: Boolean = true, block: suspend () -> Pair<String, Boolean>) {
        if (saveFirst) saveDraftPrefs()
        scope.launch {
            busy = true
            feedback = null
            feedback = try {
                withContext(Dispatchers.IO) { block() }
            } finally {
                busy = false
            }
        }
    }

    val draftPrefs = prefs.copy(
        discoveryPrefix = discoveryPrefix,
        baseTopic = baseTopic,
        deviceNameOverride = deviceNameOverride,
    )
    val topicPreview = pairedDevice?.let { device ->
        MqttDiscovery.stateTopic(
            draftPrefs.normalizedBaseTopic,
            MqttDiscovery.resolveDeviceIdentity(device, draftPrefs),
        )
    } ?: "${draftPrefs.normalizedBaseTopic}/${MqttDiscovery.sanitizeSlug(deviceNameOverride.ifBlank { "ring" })}/state"

    val hasDraftChanges = host.trim() != prefs.host ||
        draftPort() != prefs.port ||
        useTls != prefs.useTls ||
        username.trim() != prefs.username ||
        password != savedPassword ||
        discoveryPrefix.trim() != prefs.discoveryPrefix ||
        baseTopic.trim() != prefs.baseTopic ||
        deviceNameOverride.trim() != prefs.deviceNameOverride

    SettingsSubScreen(title = stringResource(R.string.mqtt_title), onBack = onBack) {
        Text(
            text = stringResource(R.string.mqtt_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = PulseColors.textSecondary,
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.mqtt_enable_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Switch(
                        checked = prefs.enabled,
                        onCheckedChange = { enable ->
                            if (enable && draftValid()) saveDraftPrefs()
                            store.update { it.copy(enabled = enable) }
                            if (!enable) MqttLivePublisher.resetPool()
                        },
                    )
                }
                val isReady = prefs.enabled && draftValid()
                Text(
                    text = stringResource(
                        when {
                            isReady -> R.string.mqtt_status_enabled
                            prefs.enabled -> R.string.mqtt_status_incomplete
                            else -> R.string.mqtt_status_disabled
                        }
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = when {
                        isReady -> PulseColors.success
                        prefs.enabled -> PulseColors.warning
                        else -> PulseColors.textSecondary
                    },
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = stringResource(R.string.mqtt_broker_section),
                    style = MaterialTheme.typography.titleMedium,
                )
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text(stringResource(R.string.mqtt_host_label)) },
                    placeholder = { Text(stringResource(R.string.mqtt_host_placeholder)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (!useTls && host.trim().isNotBlank() && !LocalEndpoint.isPrivateHost(host.trim())) {
                    Text(
                        text = stringResource(R.string.mqtt_cleartext_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = PulseColors.warning,
                    )
                }
                OutlinedTextField(
                    value = portText,
                    onValueChange = { portText = it.filter(Char::isDigit).take(5) },
                    label = { Text(stringResource(R.string.mqtt_port_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.mqtt_tls_label))
                    Switch(
                        checked = useTls,
                        onCheckedChange = { next ->
                            useTls = next
                            val nextDefault = if (next) DEFAULT_TLS_PORT else DEFAULT_PLAIN_PORT
                            val oldDefault = if (next) DEFAULT_PLAIN_PORT else DEFAULT_TLS_PORT
                            if (portText == oldDefault || portText.isBlank()) {
                                portText = nextDefault
                            }
                        },
                    )
                }
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text(stringResource(R.string.mqtt_username_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.mqtt_password_label)) },
                    singleLine = true,
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                imageVector = if (passwordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = stringResource(R.string.action_toggle_visibility),
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = discoveryPrefix,
                    onValueChange = { discoveryPrefix = it },
                    label = { Text(stringResource(R.string.mqtt_discovery_prefix_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = baseTopic,
                    onValueChange = { baseTopic = it },
                    label = { Text(stringResource(R.string.mqtt_base_topic_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = deviceNameOverride,
                    onValueChange = { deviceNameOverride = it },
                    label = { Text(stringResource(R.string.mqtt_device_name_label)) },
                    placeholder = { Text(stringResource(R.string.mqtt_device_name_placeholder)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = stringResource(R.string.mqtt_topic_preview, topicPreview),
                    style = MaterialTheme.typography.bodySmall,
                    color = PulseColors.textSecondary,
                )
                Button(
                    onClick = {
                        saveDraftPrefs()
                        feedback = context.getString(R.string.mqtt_saved_feedback) to false
                    },
                    enabled = !busy && hasDraftChanges,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.mqtt_save_button))
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.mqtt_sync_section),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.mqtt_sync_interval_label),
                    style = MaterialTheme.typography.bodyMedium,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(15, 30, 60, 120).forEach { mins ->
                        FilterChip(
                            selected = prefs.syncIntervalMinutes == mins,
                            onClick = {
                                store.update { it.copy(syncIntervalMinutes = mins) }
                                RingSyncWorker.schedule(context, mins, ExistingPeriodicWorkPolicy.UPDATE)
                            },
                            label = {
                                Text(
                                    stringResource(
                                        when (mins) {
                                            15 -> R.string.mqtt_interval_15m
                                            30 -> R.string.mqtt_interval_30m
                                            60 -> R.string.mqtt_interval_1h
                                            else -> R.string.mqtt_interval_2h
                                        }
                                    )
                                )
                            },
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.mqtt_sync_interval_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = PulseColors.textSecondary,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(
                            text = stringResource(R.string.mqtt_live_streaming_title),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = stringResource(R.string.mqtt_live_streaming_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = PulseColors.textSecondary,
                        )
                    }
                    Switch(
                        checked = prefs.liveStreamingEnabled,
                        onCheckedChange = { next ->
                            store.update { it.copy(liveStreamingEnabled = next) }
                            if (!next) MqttLivePublisher.resetPool()
                        },
                    )
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.mqtt_actions_section),
                    style = MaterialTheme.typography.titleMedium,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = {
                            launchAction {
                                when (val res = MqttExporter.testConnection(context)) {
                                    is MqttExporter.Result.Success ->
                                        context.getString(R.string.mqtt_test_success) to false
                                    is MqttExporter.Result.Failure ->
                                        res.reason to true
                                    is MqttExporter.Result.Skipped ->
                                        context.getString(R.string.mqtt_status_incomplete) to true
                                }
                            }
                        },
                        enabled = !busy && draftValid(),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.mqtt_test_connection))
                    }
                    OutlinedButton(
                        onClick = {
                            launchAction {
                                when (val res = MqttExporter.publishNow(context)) {
                                    is MqttExporter.Result.Success ->
                                        context.getString(R.string.mqtt_publish_success, res.sensorCount) to false
                                    is MqttExporter.Result.Failure ->
                                        res.reason to true
                                    is MqttExporter.Result.Skipped ->
                                        context.getString(R.string.mqtt_status_incomplete) to true
                                }
                            }
                        },
                        enabled = !busy && draftValid(),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.mqtt_publish_now))
                    }
                }
                OutlinedButton(
                    onClick = { showRemoveDialog = true },
                    enabled = !busy && draftValid(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.mqtt_remove_ha))
                }
                if (busy) {
                    Text(
                        text = stringResource(R.string.mqtt_testing_progress),
                        style = MaterialTheme.typography.bodySmall,
                        color = PulseColors.textSecondary,
                    )
                }
                feedback?.let { (message, isError) ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isError) PulseColors.danger else PulseColors.success,
                    )
                }
                val lastPublishAt = prefs.lastPublishAt
                if (lastPublishAt != null) {
                    Text(
                        text = stringResource(
                            R.string.mqtt_last_publish,
                            DeviceHeroStatus.relativeShort(lastPublishAt, System.currentTimeMillis()),
                            prefs.lastPublishSummary ?: "",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = PulseColors.textSecondary,
                    )
                }
                val lastError = prefs.lastError
                if (lastError != null) {
                    Text(
                        text = stringResource(R.string.mqtt_last_error, lastError),
                        style = MaterialTheme.typography.bodySmall,
                        color = PulseColors.danger,
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
    }

    if (showRemoveDialog) {
        AlertDialog(
            onDismissRequest = { showRemoveDialog = false },
            title = { Text(stringResource(R.string.mqtt_remove_confirm_title)) },
            text = { Text(stringResource(R.string.mqtt_remove_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRemoveDialog = false
                        launchAction(saveFirst = false) {
                            when (val res = MqttExporter.clearRetainedTopics(context)) {
                                is MqttExporter.Result.Failure -> res.reason to true
                                else -> context.getString(R.string.mqtt_removed_feedback) to false
                            }
                        }
                    },
                ) {
                    Text(stringResource(R.string.mqtt_remove_confirm_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveDialog = false }) {
                    Text(stringResource(R.string.mqtt_cancel))
                }
            },
        )
    }
}
