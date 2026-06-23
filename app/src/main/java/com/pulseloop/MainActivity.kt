package com.pulseloop

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.pulseloop.notifications.CoachNotifications
import com.pulseloop.ui.PulseLoopApp

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
        requestAllPermissions()

        setContent {
            PulseLoopApp()
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-request on resume in case user granted in Settings
        requestAllPermissions()
        if (hasAllBlePermissions() && hasNotificationPermission()) {
            CoachNotifications.schedule(this)
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
