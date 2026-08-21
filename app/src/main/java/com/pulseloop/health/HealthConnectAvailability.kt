package com.pulseloop.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient

/**
 * Availability of the Health Connect provider on this device, as the settings screen needs it.
 *
 * Health Connect ships in AOSP from Android 14; on older devices it is the separate
 * `com.google.android.apps.healthdata` app from the Play Store, so "absent" and "needs an
 * update" are distinct, actionable states (install deep link vs update prompt) rather than one
 * broken toggle (docs/health-connect-integration.md §2, caveat 1).
 *
 * Wraps `HealthConnectClient.getSdkStatus` (official get-started guide; 1.1.0 constants:
 * SDK_UNAVAILABLE = 1, SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED = 2, SDK_AVAILABLE = 3).
 */
enum class HealthConnectAvailability {
    AVAILABLE,
    PROVIDER_UPDATE_REQUIRED,
    UNAVAILABLE,
}

object HealthConnectSdk {
    fun availability(context: Context): HealthConnectAvailability =
        when (HealthConnectClient.getSdkStatus(context.applicationContext)) {
            HealthConnectClient.SDK_AVAILABLE -> HealthConnectAvailability.AVAILABLE
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> HealthConnectAvailability.PROVIDER_UPDATE_REQUIRED
            else -> HealthConnectAvailability.UNAVAILABLE
        }
}
