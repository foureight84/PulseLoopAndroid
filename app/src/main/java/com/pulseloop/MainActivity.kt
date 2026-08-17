package com.pulseloop

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.pulseloop.health.HealthConnectPermissionReconcile
import com.pulseloop.notifications.CoachNotifications
import com.pulseloop.strava.StravaAuth
import com.pulseloop.strava.StravaTokenStore
import com.pulseloop.ui.PulseLoopApp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Single-activity host for the PulseLoop Compose UI.
 * Requests all required runtime permissions on startup:
 *   - Android 12+: BLUETOOTH_SCAN + BLUETOOTH_CONNECT
 *   - Android < 12: ACCESS_FINE_LOCATION (for BLE scanning)
 *   - Android 13+: POST_NOTIFICATIONS
 */
class MainActivity : ComponentActivity() {

    // Request multiple permissions at once on Android 12+
    private val blePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val granted = results.values.all { it }
        if (granted) {
            // Permissions granted — services can start
            CoachNotifications.schedule(this)
        }
    }

    // Single permission request for older Android or notification-only
    private val singlePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            CoachNotifications.schedule(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        CoachNotifications.createChannel(this)
        com.pulseloop.notifications.BatteryNotifications.createChannel(this)
        requestAllPermissions()

        setContent {
            PulseLoopApp()
        }

        // Handle Strava redirect on cold start (app launched from browser callback).
        handleStravaRedirect(intent)
    }

    override fun onResume() {
        super.onResume()
        // Re-request on resume in case user granted in Settings
        requestAllPermissions()
        if (hasAllBlePermissions() && hasNotificationPermission()) {
            CoachNotifications.schedule(this)
        }
        // Health Connect Phase 6: detect out-of-band grant/revocation on every foreground return
        // and reset the affected export watermarks (auto on grow; a full revocation is surfaced on
        // the settings screen). No-op unless the export is enabled with a stored grant.
        HealthConnectPermissionReconcile.onAppStart(this, lifecycleScope)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleStravaRedirect(intent)
    }

    /**
     * Strava OAuth callback (`pulseloop://localhost/strava-auth?…`). Reached via `onNewIntent` for
     * the usual case, and via `onCreate` when the browser round-trip outlived our process.
     *
     * Failures are recorded in the token store rather than dropped, so the Strava settings screen
     * can say what went wrong — a silent return leaves the user staring at a "Connect" button that
     * appears to do nothing.
     */
    private fun handleStravaRedirect(intent: Intent) {
        val uri = intent.data ?: return
        if (uri.scheme != "pulseloop") return
        if (!StravaAuth.isConfigured) return
        val store = StravaTokenStore(this)

        // Strava returns `error=access_denied` when the user declines on the consent screen.
        uri.getQueryParameter("error")?.takeIf { it.isNotBlank() }?.let { error ->
            store.takePendingAuthState()
            store.saveLastError(
                if (error == "access_denied") "Strava authorization was declined." else "Strava denied authorization: $error"
            )
            return
        }

        // CSRF: the state we generated must come back. One-shot, cleared either way.
        if (!StravaAuth.validateState(store, uri.getQueryParameter("state"))) {
            store.saveLastError("Strava authorization could not be verified. Please try connecting again.")
            return
        }

        // The consent screen lets the user untick "Upload your activities". Catch it here rather
        // than letting every future upload fail with an opaque 401.
        if (!StravaAuth.grantedScopeIncludesWrite(uri.getQueryParameter("scope"))) {
            store.saveLastError("Strava did not grant upload permission. Reconnect and keep \"Upload your activities\" checked.")
            return
        }

        val code = uri.getQueryParameter("code")?.takeIf { it.isNotBlank() } ?: run {
            store.saveLastError("Strava returned an unexpected authorization response.")
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                store.save(StravaAuth.exchangeCode(code))
                store.clearLastError()
            } catch (e: Exception) {
                // The code may have expired, or the configured secrets are wrong.
                store.saveLastError("Could not complete the Strava sign-in: ${e.message ?: "unknown error"}")
            }
        }
    }

    // ── Permission checks ──────────────────────────────────────────────

    fun hasAllBlePermissions(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    } else {
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else true
    }

    // ── Permission requests ─────────────────────────────────────────────

    private fun requestAllPermissions() {
        val missing = mutableListOf<String>()

        // BLE / Location — GPS tracking needs location on all API levels
        if (!hasFineLocation()) missing.add(Manifest.permission.ACCESS_FINE_LOCATION)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!hasBleScan()) missing.add(Manifest.permission.BLUETOOTH_SCAN)
            if (!hasBleConnect()) missing.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        // Notifications (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationPermission()) {
            missing.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (missing.isNotEmpty()) {
            if (missing.size == 1) {
                singlePermissionLauncher.launch(missing.first())
            } else {
                blePermissionLauncher.launch(missing.toTypedArray())
            }
        } else {
            // All granted — schedule notifications
            CoachNotifications.schedule(this)
        }
    }

    private fun hasBleScan() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.BLUETOOTH_SCAN
    ) == PackageManager.PERMISSION_GRANTED

    private fun hasBleConnect() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.BLUETOOTH_CONNECT
    ) == PackageManager.PERMISSION_GRANTED

    private fun hasFineLocation() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
}
