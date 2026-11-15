package com.pulseloop.update

import com.pulseloop.R
import com.pulseloop.util.L10n
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.pulseloop.ui.components.MarkdownLite
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
        title = { Text(stringResource(R.string.update_available, info.versionName)) },
        text = {
            Column {
                if (downloading) {
                    Text(stringResource(R.string.update_downloading_pct, (progress * 100).toInt()), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                } else {
                    MarkdownLite(
                        info.changelog,
                        modifier = Modifier
                            .heightIn(max = 320.dp)
                            .verticalScroll(rememberScrollState()),
                        // Theme colors — the dialog surface follows light/dark, unlike the
                        // coach's fixed dark cards, so the fixed PulseColors defaults would
                        // be near-invisible on a light dialog.
                        headingColor = MaterialTheme.colorScheme.onSurface,
                        bodyColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        bulletColor = MaterialTheme.colorScheme.primary,
                        boldColor = MaterialTheme.colorScheme.onSurface,
                    )
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
                            error = L10n.get(R.string.update_download_failed)
                        }
                    }
                },
            ) { Text(if (downloading) stringResource(R.string.update_downloading) else stringResource(R.string.update_action)) }
        },
        dismissButton = {
            TextButton(enabled = !downloading, onClick = onDismiss) { Text(stringResource(R.string.action_later)) }
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
                stringResource(R.string.update_release_only, BuildConfig.VERSION_NAME, BuildConfig.APPLICATION_ID),
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
                    when (val result = UpdateChecker.check(context, force = true)) {
                        is UpdateCheckResult.UpdateAvailable -> update = result.info
                        is UpdateCheckResult.Failed ->
                            status = L10n.get(R.string.update_check_failed, result.reason)
                        else -> status = L10n.get(R.string.update_latest, BuildConfig.VERSION_NAME)
                    }
                    checking = false
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (checking) stringResource(R.string.update_checking) else stringResource(R.string.update_check)) }
        status?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    update?.let { UpdateDialog(it) { update = null } }
}
