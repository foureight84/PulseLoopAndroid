package com.pulseloop.update

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.pulseloop.BuildConfig
import kotlinx.coroutines.launch

/**
 * "Update available" dialog: shows the changelog, then downloads the APK with a progress bar
 * and hands it to the system installer. Reused by the on-launch check and the Settings button.
 */
@Composable
fun UpdateDialog(info: UpdateInfo, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var downloading by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!downloading) onDismiss() },
        title = { Text("Update available — ${info.versionName}") },
        text = {
            Column {
                if (downloading) {
                    Text("Downloading… ${(progress * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                } else {
                    Text(info.changelog, style = MaterialTheme.typography.bodySmall)
                    error?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !downloading,
                onClick = {
                    // Require the per-app "install unknown apps" grant first.
                    if (!ApkInstaller.canInstall(context)) {
                        context.startActivity(ApkInstaller.installPermissionIntent(context))
                        return@TextButton
                    }
                    downloading = true
                    error = null
                    scope.launch {
                        val file = UpdateChecker.download(context, info) { progress = it }
                        downloading = false
                        if (file != null) {
                            ApkInstaller.install(context, file)
                            onDismiss()
                        } else {
                            error = "Download failed. Please try again."
                        }
                    }
                },
            ) { Text(if (downloading) "Downloading…" else "Update") }
        },
        dismissButton = {
            TextButton(enabled = !downloading, onClick = onDismiss) { Text("Later") }
        },
    )
}

/**
 * Self-contained "Check for updates" control for the Settings screen: a button that runs a
 * forced check and shows the result (up-to-date / error / the update dialog). On a debug or
 * non-release build, self-update isn't possible, so it shows a short explanation instead.
 */
@Composable
fun CheckForUpdatesButton(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var update by remember { mutableStateOf<UpdateInfo?>(null) }

    Column(modifier) {
        if (!UpdateChecker.isSupported()) {
            Text(
                "Self-update is available in the release build only " +
                    "(current: ${BuildConfig.VERSION_NAME} / ${BuildConfig.APPLICATION_ID}).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }
        OutlinedButton(
            enabled = !checking,
            onClick = {
                checking = true
                status = null
                scope.launch {
                    val result = UpdateChecker.check(context, force = true)
                    checking = false
                    if (result != null) update = result else status = "You're on the latest version (${BuildConfig.VERSION_NAME})."
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (checking) "Checking…" else "Check for updates") }
        status?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    update?.let { UpdateDialog(it) { update = null } }
}
