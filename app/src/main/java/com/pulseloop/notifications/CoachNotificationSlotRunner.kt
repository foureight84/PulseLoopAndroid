package com.pulseloop.notifications

import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.pulseloop.coach.config.CoachSleepSyncGate
import com.pulseloop.coach.config.CoachVarietyHints
import com.pulseloop.coach.context.WeatherContextService
import com.pulseloop.coach.openai.OpenAIResponsesClient
import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.data.dao.CoachNotificationRecordDao
import com.pulseloop.data.entity.CoachNotificationRecordEntity
import com.pulseloop.data.entity.DeviceEntity
import com.pulseloop.ring.PulseEvent
import com.pulseloop.ring.PulseEventBus
import com.pulseloop.ring.RingBLEClient
import com.pulseloop.ring.RingConnectionState
import com.pulseloop.service.loadPersistedMeasurementSettings
import com.pulseloop.service.loadPersistedUserProfile
import com.pulseloop.settings.ApiKeyStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The result of one due-slot attempt — ported from the `Outcome` cases of
 * CoachNotificationService.swift (iOS #94). The two results this port's data
 * trigger exists to recover are [SkippedStaleData] and [SkippedNoSleepData]:
 * neither is recorded, so a slot skipped for lack of data can still be
 * delivered by a later trigger (a completed sync, a +45min retry, or the next
 * periodic run). [SkippedDuplicate] is the other half of the contract: it is
 * what stops that later trigger from double-sending once a slot HAS fired.
 */
sealed class CoachNotificationOutcome {
    /** A check-in was delivered for [slot]. */
    data class Sent(val slot: CoachNotificationSlot) : CoachNotificationOutcome()
    /** Outside every slot window (and not forced) — silence, not an error. */
    data object SkippedNoSlot : CoachNotificationOutcome()
    /** A record for (dateKey, slotRaw) already exists, or a run is in flight. */
    data object SkippedDuplicate : CoachNotificationOutcome()
    /** Coach master toggle or the check-in opt-in is off. */
    data object SkippedDisabled : CoachNotificationOutcome()
    /** Morning slot due but last night's sleep hasn't fully synced (not recorded). */
    data object SkippedNoSleepData : CoachNotificationOutcome()
    /** Slot due but the data is stale and couldn't be refreshed in time (not recorded). */
    data object SkippedStaleData : CoachNotificationOutcome()
    /** Empty store under [CoachStaleDataPolicy.SEND_WITH_LAST_KNOWN]. */
    data object SkippedNoData : CoachNotificationOutcome()
}

/**
 * What to do when a pre-notification sync can't produce fresh data in time —
 * ported from `StaleDataPolicy` (iOS #94). [SKIP] stays quiet and leaves the
 * slot unrecorded so the data trigger (iOS #94) can fire it the moment a
 * background sync completes — a check-in built on this morning's numbers at 8pm
 * is worse than a late one. [SEND_WITH_LAST_KNOWN] (the pre-#94 behavior) sends
 * anyway; kept for tests and as an escape hatch.
 */
enum class CoachStaleDataPolicy { SKIP, SEND_WITH_LAST_KNOWN }

/**
 * The slice of [ApiKeyStore] the due-slot run reads, snapshotted at run start
 * (iOS `settingsStore.settings`). A plain value so the runner — and the data
 * trigger's pre-check — can be unit-tested without EncryptedSharedPreferences.
 */
data class CoachCheckinSettings(
    val coachEnabled: Boolean,
    val notificationsEnabled: Boolean,
    val apiKey: String,
    val model: String,
    val morningHour: Int = 8,
    val eveningHour: Int = 20,
)

/**
 * The single shared runner for the daily check-in due slot — ported from the
 * body of CoachNotificationService.runDueSlot (iOS #94).
 *
 * Both entry points call this SAME code, which is what keeps them from
 * double-sending: the 24h WorkManager worker (Android's analog of the iOS
 * BGTask) and [CoachNotificationDataTrigger] (runs the slot when a full history
 * sync completes). Gating order mirrors the iOS service exactly:
 *
 * 1. static in-flight guard → [CoachNotificationOutcome.SkippedDuplicate]
 * 2. slot window (force or fallbackToForcedSlot falls back to [CoachNotificationSlot.forcedSlot])
 *    → [CoachNotificationOutcome.SkippedNoSlot]
 * 3. dedupe: a record exists for (dateKey, slotRaw) → SkippedDuplicate
 * 4. coach + check-in opt-in enabled → SkippedDisabled
 * 5. morning && last night's sleep not fully synced → SkippedNoSleepData
 *    (NOT recorded, so a later trigger can fire it)
 * 6. freshness: ensureFreshData reports whether the store now holds recent data;
 *    stale + policy SKIP → SkippedStaleData (NOT recorded — this is the exact
 *    outcome the data trigger exists to recover); stale + SEND_WITH_LAST_KNOWN
 *    → proceed only if a latest measurement exists, else SkippedNoData
 * 7. generate (AI, deterministic fallback), record, deliver → Sent
 *
 * The dedupe in (3) is what makes worker + data trigger safe to both call it:
 * once a slot is recorded for (dateKey, slotRaw), every later same-day attempt
 * from either entry point is SkippedDuplicate.
 */
class CoachNotificationSlotRunner(
    private val settings: () -> CoachCheckinSettings,
    private val recordDao: CoachNotificationRecordDao,
    /** End timestamp of the most recent sleep session (iOS sleepDataSynced's session leg). */
    private val latestSleepSessionEndAt: suspend () -> Long? = { null },
    /** The current device row's lastFullSyncAt stamp (iOS sleepDataSynced's sync leg). */
    private val currentDeviceFullSyncAt: suspend () -> Long? = { null },
    /** Newest measurement of any kind — iOS `latestMeasurementTimestamp()`. */
    private val latestMeasurementTimestamp: suspend () -> Long? = { null },
    private val staleDataPolicy: CoachStaleDataPolicy = CoachStaleDataPolicy.SKIP,
    /** The freshness stage (iOS ensureFreshData): bounded connect-and-sync, then a
     *  re-check. Returns whether the store now holds recent data. */
    private val ensureFreshData: suspend (Long) -> Boolean,
    /** Packet build + generation (AI, deterministic fallback) for [slot] at [now]. */
    private val generate: suspend (CoachNotificationSlot, Long) -> CoachNotificationContent,
    /** Delivery (local notification in production). */
    private val deliver: (String, String) -> Unit,
    /** iOS #65's +45min retry: fired when the morning slot is blocked on sleep data. */
    private val onSleepRetryNeeded: () -> Unit = {},
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    suspend fun runDueSlot(
        force: Boolean = false,
        fallbackToForcedSlot: Boolean = false,
        now: Long = clock(),
    ): CoachNotificationOutcome {
        if (!runInFlight.compareAndSet(false, true)) return CoachNotificationOutcome.SkippedDuplicate
        var resolvedSlot: CoachNotificationSlot? = null
        try {
            val s = settings()
            val hour = hourOf(now)

            // (2) Slot window — iOS: current() ?: (force ? forcedSlot(now) : nil). A sync that
            // completes outside a window is SkippedNoSlot (silence). The data trigger relies on
            // this strictness: it only ever fires the slot that is actually due. The periodic
            // worker passes fallbackToForcedSlot because Android's 24h WorkManager periodic fires
            // wherever the cycle lands (it is NOT scheduled inside a slot window the way iOS's
            // CoachNotificationScheduler is), so without the fallback an out-of-window fire would
            // silently drop the day's check-in. The dedupe below still makes it safe to combine
            // with the trigger (no double-send).
            val slot = CoachNotificationSlot.current(hour, s.morningHour, s.eveningHour)
                ?: if (force || fallbackToForcedSlot) CoachNotificationSlot.forcedSlot(hour)
                else return CoachNotificationOutcome.SkippedNoSlot
            resolvedSlot = slot

            // (3) Per-day/slot dedupe (iOS isDuplicate): a record for (dateKey, slotRaw)
            // means this slot already fired today — from the worker or the data trigger.
            if (!force && recordDao.existsForDateKeyAndSlot(dateKeyFor(now), slotRaw(slot))) {
                return CoachNotificationOutcome.SkippedDuplicate
            }

            // (4) Enabled gate — iOS: force || flags.coachEnabled.
            if (!force && (!s.coachEnabled || !s.notificationsEnabled)) {
                return CoachNotificationOutcome.SkippedDisabled
            }

            // Coach is on but no API key: the user still opted into check-ins, so fall back
            // to the generic scripted text (pre-extraction worker behavior, kept). Recorded
            // like any other delivery so the data trigger can't double-send the same slot a
            // few minutes later — the pre-extraction worker skipped this record, which was
            // exactly how a second send could slip through.
            if (s.apiKey.isBlank()) {
                deliver(GENERIC_TITLE, GENERIC_BODY)
                recordDao.insert(
                    CoachNotificationRecordEntity(
                        title = GENERIC_TITLE,
                        body = GENERIC_BODY,
                        dateKey = dateKeyFor(now),
                        slotRaw = slotRaw(slot),
                    )
                )
                return CoachNotificationOutcome.Sent(slot)
            }

            // (5) Morning-only (iOS #65): don't fire until last night's sleep has fully
            // synced — otherwise the check-in leads with partial/absent sleep. Skip WITHOUT
            // recording so a +45min retry (or the data trigger) can fire it once synced.
            if (!force && slot == CoachNotificationSlot.MORNING && !sleepDataSynced(now)) {
                onSleepRetryNeeded()
                return CoachNotificationOutcome.SkippedNoSleepData
            }

            // (6) Sync-before-notify (iOS #61c/#94): a bounded, best-effort refresh. Stale
            // data that can't be refreshed in time → skip WITHOUT recording (a check-in
            // built on yesterday's numbers is worse than a late one), so the data trigger
            // or a retry fires the slot once fresh data lands.
            if (!force) {
                val fresh = ensureFreshData(now)
                if (!fresh) {
                    if (staleDataPolicy == CoachStaleDataPolicy.SKIP) {
                        return CoachNotificationOutcome.SkippedStaleData
                    }
                    // sendWithLastKnown: proceed, but never with a totally empty store.
                    if (latestMeasurementTimestamp() == null) return CoachNotificationOutcome.SkippedNoData
                }
            }

            // (7) Generate (AI, deterministic fallback), record, deliver.
            val notification = generate(slot, now)
            recordDao.insert(
                CoachNotificationRecordEntity(
                    title = notification.title,
                    body = notification.body,
                    dateKey = dateKeyFor(now),
                    slotRaw = slotRaw(slot),
                )
            )
            deliver(notification.title, notification.body)
            return CoachNotificationOutcome.Sent(slot)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Ultimate fallback — pre-extraction worker behavior: even when everything
            // else blows up, the user still gets the generic check-in.
            runCatching { deliver(GENERIC_TITLE, GENERIC_BODY) }
            return CoachNotificationOutcome.Sent(resolvedSlot ?: CoachNotificationSlot.forcedSlot(hourOf(now)))
        } finally {
            runInFlight.set(false)
        }
    }

    /**
     * Ported from CoachNotificationService.sleepDataSynced (iOS #65). Whether last
     * night's sleep is safe to summarize in the morning check-in. See
     * [CoachSleepSyncGate.sleepDataSynced] for the exact rule; this only reads the
     * two timestamps it needs.
     */
    private suspend fun sleepDataSynced(now: Long): Boolean =
        CoachSleepSyncGate.sleepDataSynced(latestSleepSessionEndAt(), currentDeviceFullSyncAt(), now)

    companion object {
        /** The generic scripted check-in (pre-extraction worker fallback text). */
        const val GENERIC_TITLE = "PulseLoop Coach"
        const val GENERIC_BODY = "Good morning! Sync your ring and check your vitals to start the day."

        /**
         * iOS `CoachNotificationService.runInFlight` — process-wide, because each entry
         * point (the periodic worker, the sync-completion data trigger) builds its own
         * runner, and generation awaits for seconds — plenty of room for a second entry
         * to pass the dedupe check before the first one records.
         */
        private val runInFlight = AtomicBoolean(false)

        /**
         * A stable per-day dedupe key: the local-timezone epoch day of [nowMillis]
         * (Android's analog of iOS's `yyyy-MM-dd` string dateKey — an INTEGER is what
         * the dedupe query and its index want).
         */
        fun dateKeyFor(nowMillis: Long): Long =
            Instant.ofEpochMilli(nowMillis).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()

        /** The stored slot name, lowercase — iOS's `slot.rawValue`. */
        fun slotRaw(slot: CoachNotificationSlot): String = slot.name.lowercase()

        /** Local hour-of-day of [nowMillis] — feeds the slot window. */
        fun hourOf(nowMillis: Long): Int =
            Instant.ofEpochMilli(nowMillis).atZone(ZoneId.systemDefault()).hour

        /**
         * Production runner shared by the worker and the data trigger: real settings,
         * Room DAOs, the BLE freshness stage, LLM generation (deterministic fallback),
         * and local-notification delivery.
         */
        fun forContext(context: Context): CoachNotificationSlotRunner {
            val appContext = context.applicationContext
            val keyStore = ApiKeyStore(appContext)
            val db = PulseLoopDatabase.getInstance(appContext)
            val engine = CoachSlotProductionEngine(appContext, keyStore, db)
            return CoachNotificationSlotRunner(
                settings = {
                    CoachCheckinSettings(
                        coachEnabled = keyStore.coachEnabled,
                        notificationsEnabled = keyStore.notificationsEnabled,
                        apiKey = keyStore.apiKey,
                        model = keyStore.model,
                        morningHour = keyStore.morningHour,
                        eveningHour = keyStore.eveningHour,
                    )
                },
                recordDao = db.coachNotificationRecordDao(),
                latestSleepSessionEndAt = { db.sleepSessionDao().recent(1).firstOrNull()?.endAt },
                currentDeviceFullSyncAt = { db.deviceDao().current()?.lastFullSyncAt },
                latestMeasurementTimestamp = { db.measurementDao().latestTimestamp() },
                ensureFreshData = engine::ensureFreshData,
                generate = engine::generate,
                deliver = engine::deliver,
                onSleepRetryNeeded = { CoachNotifications.scheduleSleepRetry(appContext) },
            )
        }
    }
}

/**
 * The production halves of the runner — everything Android-specific that the pure
 * gate logic in [CoachNotificationSlotRunner] delegates: the bounded
 * connect-and-sync freshness stage (moved from the pre-extraction
 * CoachNotificationWorker), packet build + LLM generation, and local-notification
 * delivery. Kept as a private class so the runner stays unit-testable with fakes.
 */
private class CoachSlotProductionEngine(
    private val context: Context,
    private val keyStore: ApiKeyStore,
    private val db: PulseLoopDatabase,
) {

    /**
     * Ported from CoachNotificationService.ensureFreshData (iOS #61c), now reporting
     * freshness (iOS `return hasRecentData(now:)`) so the runner can apply the #94
     * stale-data skip. This run always owns a private [RingBLEClient] (unlike the
     * foreground [com.pulseloop.service.RingSyncCoordinator]), so there's no "sync
     * already in flight" to await — only connect-and-sync, bounded by
     * [SYNC_WAIT_TIMEOUT_MS] so a stale link can never hang the run past its own
     * budget. Skips outright when no real ring is paired, or the last completed sync
     * is still fresh.
     *
     * There was once a STALE_DATA_WINDOW_MS (1h) window here, added for iOS #94. It
     * could never be false: it was evaluated only *after* the 3h RECENT_DATA_WINDOW_MS
     * early-return above, so `now - latestMeasurementAt` was already ≥ 3h by the time
     * it ran. Wiring it into the foreground check therefore deleted that guard
     * outright, letting this run open a second transient GATT client while the
     * foreground app held the link — the exact thing the comment below says iOS never
     * does. iOS #94's real contribution is [CoachNotificationDataTrigger]: it runs
     * the due slot when a sync *completes*, so a slot skipped for stale data is
     * delivered a few minutes later instead of being lost.
     */
    suspend fun ensureFreshData(now: Long): Boolean {
        val device = db.deviceDao().currentReal() ?: return hasRecentData(null, now)
        val fresh = device.lastFullSyncAt?.let { now - it < FRESH_SYNC_WINDOW_MS } ?: false
        if (fresh) return true

        // iOS `hasRecentData`: a fresh *live* measurement is as good as a completed sync
        // (covers jring, which streams samples continuously rather than running a paged
        // history sync) — skip the forced connect + full runStartup iOS deliberately
        // avoids paying at every check-in.
        val latestMeasurementAt = db.measurementDao().latestTimestamp()
        if (latestMeasurementAt != null && now - latestMeasurementAt < RECENT_DATA_WINDOW_MS) return true

        // iOS branches on the app's *shared* coordinator and never opens a second
        // client: when the ring is already connected (the foreground app holding the
        // link — its CONNECTED event is what stamps this state), just give any
        // in-flight sync a bounded chance to land. Opening our own GATT here would wipe
        // the sleep tables under the user (the CONNECTED event rebuilds them),
        // duplicate every decode into the shared bus, and interleave two sync
        // engines' history commands on one link.
        if (device.stateRaw == "CONNECTED") {
            awaitSyncDone()
            return hasRecentData(device, now)
        }
        if (isAppForeground()) return hasRecentData(device, now)

        val bleClient = RingBLEClient(context, transientOwner = true)
        if (!bleClient.hasPermissions()) {
            // destroy(), not just drop the reference: the client's init-started
            // connection watchdog would otherwise keep firing into permission-less
            // connect attempts.
            bleClient.destroy()
            return hasRecentData(device, now)
        }

        val measurementSettings = loadPersistedMeasurementSettings(db)
        val profileValues = loadPersistedUserProfile(db, keyStore)

        try {
            withTimeoutOrNull(SYNC_WAIT_TIMEOUT_MS) {
                val doneSignal = async {
                    PulseEventBus.events.filterIsInstance<PulseEvent.SyncProgress>().first { it.stage == "done" }
                }
                bleClient.onConnected = {
                    val engine = bleClient.syncEngine
                    engine?.setMeasurementSettings(measurementSettings)
                    profileValues?.let { engine?.setUserProfile(it) }
                    engine?.runStartup()
                }
                bleClient.connectLastKnown()
                while (!doneSignal.isCompleted && !isAppForeground()) delay(500)
                if (isAppForeground()) {
                    doneSignal.cancel()
                    return@withTimeoutOrNull
                }
                doneSignal.await()
            }
        } finally {
            // destroy(), not disconnect(): the client's connection watchdog (started in
            // init) survives disconnect() and re-attaches the ring ~15s after the run
            // exits — re-firing onConnected → a full runStartup, then holding the ring
            // with no UI.
            val releasedConnection = bleClient.destroy()
            if (releasedConnection && !isAppForeground()) {
                PulseEventBus.publishBlocking(
                    PulseEvent.DeviceStateChanged(
                        RingConnectionState.DISCONNECTED,
                        null,
                    )
                )
            }
        }
        // Re-read the device row: the sync we just ran may have stamped lastFullSyncAt
        // while we were waiting (EventPersistenceSubscriber writes it on "done").
        return hasRecentData(db.deviceDao().currentReal(), now)
    }

    /**
     * iOS `hasRecentData` — the store holds data inside the freshness window (3h): a
     * completed full sync, or a recent live measurement (covers streaming rings).
     * Gates on lastFullSyncAt, NOT lastSyncAt — the latter is re-stamped on every bare
     * CONNECT before any data streams (iOS #61c's freshness-gate fix).
     */
    private suspend fun hasRecentData(device: DeviceEntity?, now: Long): Boolean {
        device?.lastFullSyncAt?.let { if (now - it < RECENT_DATA_WINDOW_MS) return true }
        val latest = db.measurementDao().latestTimestamp()
        return latest != null && now - latest < RECENT_DATA_WINDOW_MS
    }

    private suspend fun isAppForeground(): Boolean = withContext(Dispatchers.Main.immediate) {
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    }

    /** Give an in-flight sync (driven by whoever owns the live link) a bounded chance to finish. */
    private suspend fun awaitSyncDone() {
        withTimeoutOrNull(SYNC_WAIT_TIMEOUT_MS) {
            PulseEventBus.events.filterIsInstance<PulseEvent.SyncProgress>().first { it.stage == "done" }
        }
    }

    /**
     * Packet build + generation (moved from the pre-extraction worker). The weather
     * service degrades to a cached (or null) reading on its own when the app isn't
     * foregrounded — see WeatherContextService — so it's always safe to call from a
     * background entry point.
     */
    suspend fun generate(slot: CoachNotificationSlot, now: Long): CoachNotificationContent {
        val environment = WeatherContextService(context).snapshot()
        val packet = NotificationContextBuilder.build(slot, db, now, environment = environment)

        // Variety + anti-repeat (iOS #65): a deterministic per-day/slot coaching angle,
        // plus the last few delivered check-ins so the model doesn't repeat itself.
        // (The seed keeps the pre-extraction worker's exact "yyyy-MM-dd<slot>" shape so
        // the angle stream doesn't shift.)
        val angle = CoachVarietyHints.angle(localDateKey(now) + slot.name.lowercase())
        val recentTexts = db.coachNotificationRecordDao().recent(6).map { "${it.title} — ${it.body}" }

        // Generate via AI or fallback
        return try {
            generateWithAI(slot, packet, keyStore.apiKey, keyStore.model, angle, recentTexts)
        } catch (e: Exception) {
            scripted(slot, packet)
        }
    }

    /** Local "yyyy-MM-dd" of [nowMillis] — the pre-extraction worker's angle-seed shape. */
    private fun localDateKey(nowMillis: Long): String =
        Instant.ofEpochMilli(nowMillis).atZone(ZoneId.systemDefault()).toLocalDate().toString()

    fun deliver(title: String, body: String) {
        CoachNotifications.showNow(context, title, body)
    }

    private suspend fun generateWithAI(
        slot: CoachNotificationSlot,
        packet: NotificationContextPacket,
        apiKey: String,
        model: String,
        angle: String = "",
        recentTexts: List<String> = emptyList(),
    ): CoachNotificationContent {
        val client = OpenAIResponsesClient(apiKey)

        val input = JsonArray(listOf(
            JsonObject(mapOf(
                "role" to JsonPrimitive("system"),
                "content" to JsonPrimitive(NotificationPromptBuilder.systemPrompt(slot)),
            )),
            JsonObject(mapOf(
                "role" to JsonPrimitive("developer"),
                "content" to JsonPrimitive(NotificationPromptBuilder.developerMessage(packet, angle, recentTexts)),
            )),
        ))

        val schemaProps = JsonObject(mapOf(
            "title" to JsonObject(mapOf("type" to JsonPrimitive("string"), "maxLength" to JsonPrimitive(50))),
            "body" to JsonObject(mapOf("type" to JsonPrimitive("string"), "maxLength" to JsonPrimitive(160))),
        ))
        val schema = JsonObject(mapOf(
            "type" to JsonPrimitive("object"),
            "properties" to schemaProps,
            "required" to JsonArray(listOf(JsonPrimitive("title"), JsonPrimitive("body"))),
            "additionalProperties" to JsonPrimitive(false),
        ))
        val format = JsonObject(mapOf(
            "type" to JsonPrimitive("json_schema"),
            "name" to JsonPrimitive("coach_notification"),
            "schema" to schema,
            "strict" to JsonPrimitive(true),
        ))
        val text = JsonObject(mapOf("format" to format))

        val requestBody = JsonObject(mapOf(
            "model" to JsonPrimitive(model),
            "input" to input,
            "tools" to JsonArray(emptyList()),
            "text" to text,
        ))

        val response = client.send(requestBody.toString().toByteArray())
        val output = response.outputText
        return CoachNotificationContent.decodeFromJson(output)
            ?: scripted(slot, packet)
    }

    /** Deterministic fallback — ported from CoachNotificationGenerator.scripted(). */
    private fun scripted(slot: CoachNotificationSlot, packet: NotificationContextPacket): CoachNotificationContent {
        val name = packet.profileName?.let { ", $it" } ?: ""
        return when (slot) {
            CoachNotificationSlot.MORNING -> {
                val sleep = packet.latestSleep
                if (sleep != null) {
                    val h = sleep.totalMin / 60
                    val m = sleep.totalMin % 60
                    CoachNotificationContent(
                        title = "Good morning$name",
                        body = "You logged ${h}h ${m}m of sleep. Here's to a strong day — get moving when you can.",
                    )
                } else {
                    CoachNotificationContent(
                        title = "Good morning$name",
                        body = "Ready to start the day? Take a measurement and I'll help you plan it.",
                    )
                }
            }
            CoachNotificationSlot.EVENING -> {
                val steps = packet.today.steps
                if (steps != null) {
                    val goal = packet.goals.stepsDaily
                    val hit = if (steps >= goal) "You hit your $goal step goal — nice work." else "${goal - steps} steps to your goal."
                    CoachNotificationContent(
                        title = "Evening check-in",
                        body = "$steps steps today. $hit Time to start winding down.",
                    )
                } else {
                    CoachNotificationContent(
                        title = "Evening check-in",
                        body = "How did today feel? Sync your ring and I'll recap your day.",
                    )
                }
            }
        }
    }

    companion object {
        /** iOS #61c `syncWaitTimeout` — caps ensureFreshData so a stale BLE link can't hang the run. */
        private const val SYNC_WAIT_TIMEOUT_MS = 15_000L
        /** iOS #61c `hasFreshFullSync` — a completed sync within this window skips a new one. */
        private const val FRESH_SYNC_WINDOW_MS = 10 * 60_000L
        /** iOS `freshnessWindow` (3h) — a live measurement this recent counts as fresh data even
         *  without a completed full sync (covers rings that stream continuously). */
        private const val RECENT_DATA_WINDOW_MS = 3 * 60 * 60_000L
    }
}
