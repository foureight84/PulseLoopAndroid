package com.pulseloop.widgets

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.glance.appwidget.updateAll
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.data.entity.UserGoalEntity
import com.pulseloop.service.MetricKind
import com.pulseloop.service.SleepScore
import com.pulseloop.service.VitalsThresholdEngine
import com.pulseloop.settings.ApiKeyStore
import com.pulseloop.settings.UnitConverter
import com.pulseloop.settings.UnitSystem
import com.pulseloop.ui.components.ZonePalette
import com.pulseloop.ui.components.buildSleepStageSegments
import com.pulseloop.ui.components.toColor
import com.pulseloop.ui.viewmodels.VitalCardState
import com.pulseloop.ui.viewmodels.VitalsCardFactory
import com.pulseloop.ui.viewmodels.VitalsViewModel
import com.pulseloop.ui.viewmodels.toCardInputs
import com.pulseloop.util.Formats
import com.pulseloop.util.TimeUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

/**
 * Publishes the home-screen-widget snapshot, ported from
 * PulseLoop/Services/WidgetSnapshotPublisher.swift: projects the same prepared tile state the
 * Today page renders ([VitalsViewModel.buildState] + [VitalsCardFactory] cards + the activity/sleep
 * tile derivations) into the serializable [WidgetSnapshot], writes it atomically into the app's
 * files dir, and updates the three Glance widgets.
 *
 * Refresh discipline (iOS parity):
 * - Ring data changes ride the persistence pipeline ([com.pulseloop.service.EventPersistenceSubscriber]
 *   invokes [publishDebounced], 2 s) so a burst of history batches produces one snapshot.
 * - App foreground/background edges publish directly (ProcessLifecycleOwner in
 *   [com.pulseloop.PulseLoopApplication]) — catches goal/unit/profile edits.
 * - Settings saves and demo reseeds call [publish] explicitly.
 * - [WidgetRefreshWorker] republishes every ~30 min so staleness/day-rollover appears without the
 *   app opening (Android has no WidgetKit timeline to schedule a midnight entry with).
 * - Content-hash skip: the snapshot is serialized with `generatedAt` fixed to 0 and compared to
 *   the last publish; an unchanged rebuild skips both the file write and the widget update.
 */
object WidgetSnapshotPublisher {

    private const val TAG = "WidgetSnapshot"
    private const val DEBOUNCE_MS = 2_000L
    /** Chart payloads carry at most this many points — plenty for a 56 dp axis-less chart. */
    private const val MAX_CHART_POINTS = 48

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val publishMutex = Mutex()
    private var debounceJob: Job? = null

    /** Hash of the last snapshot's content (excluding `generatedAt`); in-memory like iOS. */
    @Volatile
    private var lastContentHash: Int? = null

    /** Fire-and-forget publish (Settings saves, demo reseed, lifecycle edges). */
    fun publish(context: Context) {
        val app = context.applicationContext
        scope.launch { publishNow(app) }
    }

    /** Debounced publish for the ring-sync persistence pipeline (one snapshot per sync burst). */
    fun publishDebounced(context: Context) {
        val app = context.applicationContext
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(DEBOUNCE_MS)
            publishNow(app)
        }
    }

    /**
     * Build + write + update. When the content is unchanged the write and widget update are
     * skipped (keeping the honest "as of" time of the data still shown); [forceWidgetUpdate]
     * overrides the update skip so the periodic worker can re-render staleness/rollover chrome.
     */
    suspend fun publishNow(context: Context, forceWidgetUpdate: Boolean = false) {
        // Nothing on any home screen → nobody reads the snapshot; skip the full state
        // rebuild (a Room query sweep + card factory + JSON encode) that would otherwise
        // run on every foreground/background edge and sync burst. Same gate as
        // [WidgetRefreshWorker]. Drop the content hash so the first publish after a
        // widget is (re)placed writes and renders fresh instead of being skip-matched.
        if (!hasAnyWidget(context)) {
            lastContentHash = null
            return
        }
        publishMutex.withLock {
            try {
                val db = PulseLoopDatabase.getInstance(context)
                val keyStore = ApiKeyStore(context)
                val snapshot = buildSnapshot(db, keyStore)

                // Content comparison with a fixed timestamp (iOS `.distantPast` trick).
                val hash = WidgetSnapshotCodec.encode(snapshot.copy(generatedAt = 0)).hashCode()
                if (hash != lastContentHash) {
                    if (WidgetSnapshotStore.write(context, snapshot)) {
                        lastContentHash = hash
                        updateAllWidgets(context)
                        return
                    }
                }
                if (forceWidgetUpdate) updateAllWidgets(context)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to publish widget snapshot", e)
            }
        }
    }

    suspend fun updateAllWidgets(context: Context) {
        try {
            PulseActivityWidget().updateAll(context)
            PulseMetricWidget().updateAll(context)
            PulseDualMetricWidget().updateAll(context)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update widgets", e)
        }
    }

    /** Whether any of the three PulseLoop widgets is currently placed on a home screen. */
    fun hasAnyWidget(context: Context): Boolean {
        val manager = AppWidgetManager.getInstance(context)
        return listOf(
            PulseActivityWidgetReceiver::class.java,
            PulseMetricWidgetReceiver::class.java,
            PulseDualMetricWidgetReceiver::class.java,
        ).any { manager.getAppWidgetIds(ComponentName(context, it)).isNotEmpty() }
    }

    // ─────────────────────────── Snapshot assembly ───────────────────────────

    /**
     * Mirrors the Today screen's rebuild exactly — [VitalsViewModel.buildState] for the series
     * fetches/offsets/capabilities, [toCardInputs] + [VitalsCardFactory.card] for the card state,
     * [buildSleepStageSegments] + [SleepScore.calculate] for the sleep tile, and the Today
     * activity tile's unit conversion/formatting — so a widget tile is pixel- and label-identical
     * to its Today tile at publish time.
     */
    suspend fun buildSnapshot(db: PulseLoopDatabase, keyStore: ApiKeyStore?): WidgetSnapshot {
        val nowMs = System.currentTimeMillis()
        val units = keyStore?.resolvedUnitSystem ?: UnitSystem.METRIC
        val vitals = VitalsViewModel.buildState(db, keyStore)
        val inputs = vitals.toCardInputs(units)

        val metrics = mutableMapOf<String, WidgetMetricPayload>()
        for (kind in MetricKind.entries) {
            val card = VitalsCardFactory.card(kind, inputs, vitals.profile)
            var payload = metricPayload(kind, card)
            if (kind == MetricKind.BLOOD_PRESSURE) {
                // Same values/zones the Today BP tile reads (offsets already applied in the state).
                payload = payload.copy(
                    systolic = vitals.bpSystolic?.toDouble(),
                    diastolic = vitals.bpDiastolic?.toDouble(),
                    systolicZones = VitalsThresholdEngine
                        .zones(MetricKind.BLOOD_PRESSURE, vitals.profile)
                        .map(WidgetZonePayload::from),
                    diastolicZones = VitalsThresholdEngine.diastolicReferenceZones()
                        .map(WidgetZonePayload::from),
                )
            }
            metrics[kind.widgetKey] = payload
        }

        return WidgetSnapshot(
            generatedAt = nowMs / 1000L,
            dayStart = TimeUtil.startOfTodayLocal() / 1000L,
            activity = activityPayload(db, units),
            sleep = sleepPayload(db),
            metrics = metrics,
        )
    }

    /** Replicates the Today activity tile's value derivations (unit conversion, formatting). */
    private suspend fun activityPayload(db: PulseLoopDatabase, units: UnitSystem): WidgetActivityPayload {
        val today = try { db.activityDailyDao().byDay(TimeUtil.startOfTodayLocal()) } catch (_: Exception) { null }
        val goal = try { db.userGoalDao().get() } catch (_: Exception) { null }

        val steps = today?.steps
        val calories = today?.calories
        val distanceDisplay = today?.distanceMeters?.let { UnitConverter.distance(it, units) }

        return WidgetActivityPayload(
            steps = steps?.toDouble(),
            stepsGoal = (goal?.steps ?: UserGoalEntity.DEFAULT_STEPS).toDouble(),
            distanceDisplay = distanceDisplay,
            distanceGoalDisplay = UnitConverter.distance(goal?.distanceMeters ?: UserGoalEntity.DEFAULT_DISTANCE_METERS, units),
            distanceUnitLabel = UnitConverter.distanceUnit(units).uppercase(),
            calories = calories,
            caloriesGoal = (goal?.calories ?: UserGoalEntity.DEFAULT_CALORIES).toDouble(),
            stepsText = steps?.let { Formats.count(it) },
            distanceText = distanceDisplay?.let { Formats.distance(it) },
            caloriesText = calories?.let { Formats.count(it.toInt()) },
        )
    }

    /** Replicates the Today sleep tile's derivations (reference night, stage bar, score). */
    private suspend fun sleepPayload(db: PulseLoopDatabase): WidgetSleepPayload? {
        val reference = TimeUtil.referenceNightLocal()
        val night = try {
            db.sleepSessionDao().byDay(reference)?.takeIf { it.totalMinutes > 0 }
        } catch (_: Exception) { null } ?: return null
        val blocks = try { db.sleepStageBlockDao().forSession(night.id) } catch (_: Exception) { emptyList() }
        val score = SleepScore.calculate(night, blocks).score
        return WidgetSleepPayload(
            durationText = Formats.hoursMinutes(night.totalMinutes),
            score = score,
            segments = buildSleepStageSegments(blocks).map {
                WidgetSleepPayload.Segment(it.minutes, colorToHex(it.color), it.label)
            },
        )
    }

    /**
     * Projects one factory-built card into the wire payload, baking the zone list's value→color
     * mapping into a step function and downsampling the chart series to ≤ [MAX_CHART_POINTS].
     * The card's zones already carry the HRV baseline case and the temperature/glucose
     * display-unit conversion, so the baked thresholds match the payload samples' units.
     */
    private fun metricPayload(kind: MetricKind, card: VitalCardState): WidgetMetricPayload {
        val accent = ZonePalette.accent(kind)
        val thresholds = WidgetColorSteps.thresholds(card.zones)
        val fallbackValue = card.samples.lastOrNull()?.value ?: card.yDomain.start
        val intervalHexes = WidgetColorSteps.intervalColorHexes(card.zones, thresholds, accent, fallbackValue)
        val downsampled = MetricDownsampler.bucketAverage(card.samples, MAX_CHART_POINTS)

        return WidgetMetricPayload(
            kind = kind.widgetKey,
            title = card.title,
            valueText = card.valueText,
            unitText = card.unitText,
            statusText = card.statusText,
            statusColorHex = colorToHex(card.statusToken.toColor()),
            isEmpty = card.isEmpty,
            samples = downsampled.map { WidgetSamplePayload(it.timestampMs / 1000L, it.value) },
            yLower = card.yDomain.start,
            yUpper = card.yDomain.endInclusive,
            referenceBands = card.referenceBands.map {
                WidgetBandPayload(it.lower, it.upper, it.colorToken.tokenString, it.opacity.toDouble())
            },
            dashedRules = card.dashedRules,
            thresholds = thresholds,
            intervalColorHexes = intervalHexes,
            zones = card.zones.map(WidgetZonePayload::from),
        )
    }
}

/**
 * Periodic widget refresh: republishes the snapshot (content-hash-gated) and re-renders the
 * widgets so the staleness footer and the midnight day-rollover appear without the app opening.
 * Unique periodic work, KEEP, ~30 min — scheduled from [com.pulseloop.PulseLoopApplication].
 */
class WidgetRefreshWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // Nothing on any home screen → nothing to render; skip the DB work entirely.
        if (!WidgetSnapshotPublisher.hasAnyWidget(applicationContext)) return Result.success()
        WidgetSnapshotPublisher.publishNow(applicationContext, forceWidgetUpdate = true)
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "widget_snapshot_refresh"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<WidgetRefreshWorker>(
                30, TimeUnit.MINUTES,
                15, TimeUnit.MINUTES,
            ).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
