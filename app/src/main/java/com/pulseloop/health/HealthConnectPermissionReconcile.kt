package com.pulseloop.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Permission → export-watermark reconciliation (docs/health-connect-integration.md §4 Phase 6).
 *
 * Each export group has ONE watermark but SEVERAL independently grantable record types (VITALS
 * covers hr/spo2/hrv/temp + the Phase 5 glucose/resp/vo2/bp; ACTIVITY covers steps/energy/distance;
 * WORKOUTS covers exercise + route). Granting a subset advances the shared watermark past every
 * historical row, so a type granted *later* never backfills its history — the DAO selects on
 * `updatedAt > watermark`, and the watermark is already ahead. The fix is to reset the affected
 * group's watermark when the granted set *grows*, so the newly grantable types re-select from
 * null and backfill (idempotent upsert under the same clientRecordIds).
 *
 * The Phase 5 kinds (glucose/resp/vo2/bp) share the VITALS watermark, so they map to VITALS here:
 * on an upgrading install the one VITALS reset also backfills the `.name`-fixed legacy hr/spo2/hrv/
 * temp rows. SLEEP / NUTRITION / RESTING_HR are single-permission groups whose watermark starts
 * null and backfills on first grant, so a grow-reset on them is a no-op — mapping them anyway keeps
 * the table uniform and makes a revoke→re-grant re-export them too (safe: idempotent).
 *
 * Revocation is the other direction: we store [HealthConnectPrefs.lastGrantedPermissions] and, on
 * app start and settings-screen open, diff against the live granted set. A mid-pass
 * [SecurityException] in the worker means the same — the next reconcile corrects the stored set.
 * A full revocation is surfaced to the user as an offer to clear the watermarks (Gadgetbridge's
 * `HealthConnectResetDialogFragment` pattern) so a later re-grant re-exports; the grow-reset above
 * is the correctness backstop that makes any re-grant backfill regardless.
 */
object HealthConnectPermissionReconcile {

    private val V = HealthConnectWatermarks.Key.VITALS
    private val S = HealthConnectWatermarks.Key.SLEEP
    private val A = HealthConnectWatermarks.Key.ACTIVITY
    private val W = HealthConnectWatermarks.Key.WORKOUTS
    private val N = HealthConnectWatermarks.Key.NUTRITION
    private val R = HealthConnectWatermarks.Key.RESTING_HR

    /**
     * Every write permission → the export group whose watermark it advances. Keyed by the concrete
     * permission string (`HealthPermission.getWritePermission(RecordClass)`) so it can never drift
     * from [HealthConnectPermissions]. All 16 requested permissions are covered.
     */
    val PERMISSION_GROUP: Map<String, HealthConnectWatermarks.Key> = mapOf(
        // VITALS — one shared watermark across all eight measurement kinds.
        HealthConnectPermissions.heartRate.first() to V,
        HealthConnectPermissions.oxygenSaturation.first() to V,
        HealthConnectPermissions.heartRateVariability.first() to V,
        HealthConnectPermissions.bodyTemperature.first() to V,
        HealthConnectPermissions.bloodGlucose.first() to V,
        HealthConnectPermissions.respiratoryRate.first() to V,
        HealthConnectPermissions.vo2Max.first() to V,
        HealthConnectPermissions.bloodPressure.first() to V,
        // SLEEP.
        HealthConnectPermissions.sleep.first() to S,
        // ACTIVITY — three independently grantable metrics, one watermark.
        HealthConnectPermissions.steps.first() to A,
        HealthConnectPermissions.activeCalories.first() to A,
        HealthConnectPermissions.distance.first() to A,
        // WORKOUTS — session + embedded route, one watermark.
        HealthConnectPermissions.exercise.first() to W,
        HealthConnectPermissions.exerciseRoute.first() to W,
        // NUTRITION + RESTING_HR — single-permission groups (null-and-backfill-on-grant).
        HealthConnectPermissions.nutrition.first() to N,
        HealthConnectPermissions.restingHeartRate.first() to R,
    )

    /** The distinct watermark groups a set of permissions belongs to. */
    fun groupsFor(permissions: Collection<String>): Set<HealthConnectWatermarks.Key> =
        permissions.mapNotNull { PERMISSION_GROUP[it] }.toSet()

    /** What changed between two granted sets, after the automatic grow-reset has been applied. */
    data class Outcome(
        /** Newly granted permissions. */
        val grew: Set<String>,
        /** The groups whose watermarks were reset because [grew] is non-empty. */
        val grewGroups: Set<HealthConnectWatermarks.Key>,
        /** Permissions that were revoked. */
        val revoked: Set<String>,
        /** True when nothing is granted now but something was before — the settings screen offers
         *  a watermark reset so a later re-grant re-exports. */
        val allRevoked: Boolean,
    )

    /**
     * Diff [previous] against [current] and, when the set grew, reset the watermarks of the groups
     * the new permissions belong to. Does NOT store [current] — the caller persists
     * `lastGrantedPermissions` (keeps this testable and single-responsibility). Idempotent:
     * a no-op when nothing grew.
     */
    fun reconcile(
        previous: Set<String>,
        current: Set<String>,
        store: HealthConnectPrefsStore,
    ): Outcome {
        val grew = current - previous
        val revoked = previous - current
        val grewGroups = if (grew.isEmpty()) emptySet() else groupsFor(grew)
        if (grewGroups.isNotEmpty()) store.resetWatermarks(grewGroups)
        return Outcome(grew, grewGroups, revoked, current.isEmpty() && previous.isNotEmpty())
    }

    /**
     * App-start hook (plan: "on app start … diff against getGrantedPermissions()"). Guarded so the
     * common not-applicable path never touches the client: only runs when the export is enabled,
     * a grant was previously stored, and the provider is available. On a grow it resets the
     * affected watermarks and enqueues a pass; a full revocation is left for the settings screen to
     * surface (no UI here). [scope] is the caller's lifecycle scope so the work cancels with it.
     */
    fun onAppStart(context: Context, scope: CoroutineScope) {
        val appContext = context.applicationContext
        val store = HealthConnectPrefsStore.get(appContext)
        val prefs = store.current
        if (!prefs.enabled || prefs.lastGrantedPermissions.isEmpty()) return
        if (HealthConnectSdk.availability(appContext) != HealthConnectAvailability.AVAILABLE) return
        scope.launch {
            val client = runCatching { HealthConnectClient.getOrCreate(appContext) }.getOrNull() ?: return@launch
            val granted = runCatching { client.permissionController.getGrantedPermissions() }
                .getOrNull() ?: return@launch
            val outcome = reconcile(prefs.lastGrantedPermissions.toSet(), granted.toSet(), store)
            store.update {
                it.copy(
                    lastGrantedPermissions = granted.toList().sorted(),
                    // A grow re-opens the one-shot revocation offer (matching the settings/launcher
                    // paths), so a later full revocation can still surface it even when this grow
                    // was detected out-of-band here.
                    revocationOfferDismissed =
                        if (outcome.grewGroups.isEmpty()) it.revocationOfferDismissed else false,
                )
            }
            if (outcome.grewGroups.isNotEmpty()) HealthConnectExportWorker.enqueue(appContext)
        }
    }
}
