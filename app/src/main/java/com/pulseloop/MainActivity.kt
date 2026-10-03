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

    private val blePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val granted = results.values.all { it }
        if (granted) {
            CoachNotifications.schedule(this)
        }
    }

    private val singlePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            CoachNotifications.schedule(this)
        }
    }

    private val healthConnectPermissionLauncher = registerForActivityResult(
        androidx.health.connect.client.PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        if (granted.isNotEmpty()) {
            refreshPhoneSteps()
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

        handleStravaRedirect(intent)
    }

    override fun onResume() {
        super.onResume()
        requestAllPermissions()
        refreshPhoneSteps()
        if (hasAllBlePermissions() && hasNotificationPermission()) {
            CoachNotifications.schedule(this)
        }
        HealthConnectPermissionReconcile.onAppStart(this, lifecycleScope)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleStravaRedirect(intent)
    }

    private fun handleStravaRedirect(intent: Intent) {
        val uri = intent.data ?: return
        if (uri.scheme != "pulseloop") return
        if (!StravaAuth.isConfigured) return
        val store = StravaTokenStore(this)

        uri.getQueryParameter("error")?.takeIf { it.isNotBlank() }?.let { error ->
            store.takePendingAuthState()
            store.saveLastError(
                if (error == "access_denied") "Strava authorization was declined." else "Strava denied authorization: $error"
            )
            return
        }

        if (!StravaAuth.validateState(store, uri.getQueryParameter("state"))) {
            store.saveLastError("Strava authorization could not be verified. Please try connecting again.")
            return
        }

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
                store.saveLastError("Could not complete the Strava sign-in: ${e.message ?: "unknown error"}")
            }
        }
    }

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

    private fun requestAllPermissions() {
        val missing = mutableListOf<String>()

        if (!hasFineLocation()) missing.add(Manifest.permission.ACCESS_FINE_LOCATION)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !hasActivityRecognition()) {
            missing.add(Manifest.permission.ACTIVITY_RECOGNITION)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!hasBleScan()) missing.add(Manifest.permission.BLUETOOTH_SCAN)
            if (!hasBleConnect()) missing.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

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

    private fun hasActivityRecognition() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.ACTIVITY_RECOGNITION
    ) == PackageManager.PERMISSION_GRANTED

    private fun refreshPhoneSteps() {
        val apiKeyStore = com.pulseloop.settings.ApiKeyStore(this)
        val source = apiKeyStore.stepSource
        if (source != "phone") return

        lifecycleScope.launch(Dispatchers.IO) {
            val manager = PhoneStepManager(this@MainActivity)
            if (!manager.refresh()) {
                requestHealthConnectReadSteps()
                return@launch
            }
            manager.refreshHistoricalDays()
        }
    }

    private fun requestHealthConnectReadSteps() {
        val readSteps = androidx.health.connect.client.permission.HealthPermission
            .getReadPermission(androidx.health.connect.client.records.StepsRecord::class)
        lifecycleScope.launch(Dispatchers.Main) {
            try {
                healthConnectPermissionLauncher.launch(setOf(readSteps))
            } catch (_: Exception) {
            }
        }
    }
}