package com.pulseloop.strava

import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.data.entity.ActivitySessionEntity
import com.pulseloop.ring.MeasurementKind
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType

/**
 * Uploads finished workouts to Strava as TCX, falling back to a manual activity when a session has
 * nothing emittable. Ported from StravaUploadService.swift (iOS #100).
 */
object StravaUploader {

    private const val API_BASE = "https://www.strava.com/api/v3"
    private const val TAG = "StravaUploader"
    private const val MAX_POLL_ATTEMPTS = 15
    private const val POLL_INTERVAL_MS = 2_000L
    private const val SPORT_FIX_RETRY_MS = 3_000L
    /** How far back the automatic pass will look. Older sessions are manual-upload only. */
    private const val AUTO_SCAN_LIMIT = 20

    private val json = Json { ignoreUnknownKeys = true }

    /** Outcome of one upload. Failures carry a message the settings screen can show. */
    sealed interface Result {
        data class Success(val activityId: Long) : Result
        data class Failure(val message: String) : Result
    }

    suspend fun upload(
        session: ActivitySessionEntity,
        db: PulseLoopDatabase,
        tokenStore: StravaTokenStore,
    ): Result {
        val tokens = tokenStore.get() ?: return Result.Failure("Not connected to Strava.")
        val name = activityName(session)

        val gpsPoints = db.activityGpsPointDao().forSession(session.id)
        // Android has no per-session ActivitySample link table (iOS's `ActivityRepository.samples`);
        // the shared `measurements` table windowed to the session is the equivalent here, and is
        // how ActivityAggregates.recompute reads a workout's HR too.
        val hrEnd = session.endedAt ?: System.currentTimeMillis()
        val hrSamples = db.measurementDao().range(MeasurementKind.HEART_RATE.name, session.startedAt, hrEnd)

        // Pause *intervals* would let us drop trackpoints recorded while paused, but Android never
        // writes the activity_events table, so there are none to read. `totalPauseSeconds` is
        // maintained, and the builder already subtracts it from TotalTimeSeconds.
        val tcx = StravaTCXBuilder.build(session, gpsPoints, hrSamples, pauseIntervals = emptyList())

        return if (tcx != null) {
            uploadTcx(session, tcx, name, tokens, tokenStore)
        } else {
            // No accepted GPS route and no HR: a TCX would carry an empty <Track>, which is
            // schema-invalid and gets rejected. Create a bare manual activity so the workout still
            // lands — it carries the exact sport_type, so no follow-up update is needed.
            createManualActivity(session, name, tokens, tokenStore)
        }
    }

    private suspend fun uploadTcx(
        session: ActivitySessionEntity,
        tcx: String,
        name: String,
        tokens: StravaTokens,
        tokenStore: StravaTokenStore,
    ): Result {
        val body = okhttp3.MultipartBody.Builder()
            .setType(okhttp3.MultipartBody.FORM)
            .addFormDataPart(
                "file", "${session.id}.tcx",
                okhttp3.RequestBody.create("application/xml".toMediaType(), tcx.toByteArray(Charsets.UTF_8)),
            )
            .addFormDataPart("data_type", "tcx")
            .addFormDataPart("name", name)
            .addFormDataPart("external_id", session.id)
            .apply {
                session.notes?.takeIf { it.isNotBlank() }?.let { addFormDataPart("description", it) }
                if (!session.useGps) addFormDataPart("trainer", "1")
            }
            .build()

        val response = StravaAuth.authenticatedRequest(tokens, tokenStore, "POST", "$API_BASE/uploads", body)
        val respBody = response.body?.string().orEmpty()

        if (response.code == 429) return Result.Failure("Strava rate limit reached. Try again later.")
        if (response.code == 409) {
            val dupId = parseDuplicate(respBody)
            return if (dupId != null) {
                fixSportType(dupId, session.type, tokens, tokenStore)
                Result.Success(dupId)
            } else {
                Result.Failure("Already on Strava.")
            }
        }
        if (!response.isSuccessful) {
            return Result.Failure("Strava upload failed (HTTP ${response.code}): ${respBody.take(200)}")
        }

        val initial = runCatching { json.decodeFromString<UploadStatus>(respBody) }.getOrNull()
            ?: return Result.Failure("Unexpected response from Strava.")

        return when (val outcome = pollUpload(initial, tokens, tokenStore)) {
            is Result.Success -> {
                fixSportType(outcome.activityId, session.type, tokens, tokenStore)
                outcome
            }
            is Result.Failure -> outcome
        }
    }

    /**
     * Polls Strava's async processing until it yields an activity id. Checks the initial response
     * first, so an already-terminal upload never sleeps.
     */
    private suspend fun pollUpload(
        initial: UploadStatus,
        tokens: StravaTokens,
        tokenStore: StravaTokenStore,
    ): Result {
        var status = initial
        var attempts = 0
        while (true) {
            terminalResult(status)?.let { return it }
            if (++attempts > MAX_POLL_ATTEMPTS) {
                return Result.Failure("Timed out waiting for Strava to process the upload.")
            }
            kotlinx.coroutines.delay(POLL_INTERVAL_MS)
            val response = StravaAuth.authenticatedRequest(
                tokens, tokenStore, "GET", "$API_BASE/uploads/${status.id}",
            )
            val body = response.body?.string() ?: continue
            status = runCatching { json.decodeFromString<UploadStatus>(body) }.getOrNull() ?: continue
        }
    }

    /** null = still processing. A duplicate resolves to the existing activity — it *is* on Strava. */
    private fun terminalResult(status: UploadStatus): Result? {
        val error = status.error
        if (!error.isNullOrEmpty()) {
            if (error.contains("duplicate", ignoreCase = true)) {
                val dupId = parseDuplicate(error)
                return if (dupId != null) Result.Success(dupId) else Result.Failure("Already on Strava.")
            }
            return Result.Failure("Strava could not process the upload: $error")
        }
        return status.activity_id?.let { Result.Success(it) }
    }

    private suspend fun createManualActivity(
        session: ActivitySessionEntity,
        name: String,
        tokens: StravaTokens,
        tokenStore: StravaTokenStore,
    ): Result {
        val ended = session.endedAt ?: session.startedAt
        val elapsed = maxOf(0L, ((ended - session.startedAt) / 1000.0 - session.totalPauseSeconds).toLong())
        val form = okhttp3.FormBody.Builder()
            .add("name", name)
            .add("sport_type", StravaSportMapping.toStravaType(session.type))
            .add("start_date_local", localIso8601(session.startedAt))
            .add("elapsed_time", elapsed.toString())
            .apply {
                session.notes?.takeIf { it.isNotBlank() }?.let { add("description", it) }
                session.distanceMeters?.let { add("distance", it.toString()) }
                if (!session.useGps) add("trainer", "1")
            }
            .build()

        val response = StravaAuth.authenticatedRequest(tokens, tokenStore, "POST", "$API_BASE/activities", form)
        val body = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            return Result.Failure("Strava rejected the activity (HTTP ${response.code}): ${body.take(200)}")
        }
        val summary = runCatching { json.decodeFromString<ActivitySummary>(body) }.getOrNull()
            ?: return Result.Failure("Unexpected response from Strava.")
        return Result.Success(summary.id)
    }

    /**
     * TCX can only carry Running/Biking/Other, so everything else needs a follow-up `sport_type`
     * update. Strava re-derives the type from the file shortly after processing and can overwrite
     * an immediate update, so verify what it reports back and retry once. Best-effort — the upload
     * already succeeded, so a final failure is only logged.
     */
    private suspend fun fixSportType(
        activityId: Long,
        type: String,
        tokens: StravaTokens,
        tokenStore: StravaTokenStore,
    ) {
        if (!StravaSportMapping.needsSportTypeFix(type)) return
        val desired = StravaSportMapping.toStravaType(type)
        val body = okhttp3.RequestBody.create("application/json".toMediaType(), """{"sport_type":"$desired"}""")
        for (attempt in 1..2) {
            if (attempt > 1) kotlinx.coroutines.delay(SPORT_FIX_RETRY_MS)
            val response = StravaAuth.authenticatedRequest(
                tokens, tokenStore, "PUT", "$API_BASE/activities/$activityId", body,
            )
            val raw = response.body?.string()
            if (!response.isSuccessful) {
                android.util.Log.w(TAG, "sport_type update failed (attempt $attempt): HTTP ${response.code}")
                continue
            }
            val applied = raw?.let { runCatching { json.decodeFromString<ActivitySummary>(it).sport_type }.getOrNull() }
            // null = unparseable response; assume the 2xx meant it stuck.
            if (applied == null || applied == desired) return
            android.util.Log.w(TAG, "sport_type fix attempt $attempt: Strava reports $applied, wanted $desired")
        }
    }

    /**
     * The automatic pass. Uploads every not-yet-uploaded finished session that ended after
     * auto-upload was switched on, oldest first. Returns how many landed.
     *
     * Two things it deliberately does *not* do:
     *  - **Back-fill history.** Sessions that finished before the account was connected are skipped
     *    (iOS's `automaticSince`). Without that, connecting Strava would push up to 20 old workouts
     *    to a public feed the moment it was enabled.
     *  - **Skip past a failure.** A failing session stops the pass so it is retried next time,
     *    rather than being silently left behind while newer ones go up (iOS's contiguous-advance
     *    watermark).
     */
    suspend fun uploadAuto(db: PulseLoopDatabase, tokenStore: StravaTokenStore): Int {
        if (!tokenStore.isConnected) return 0
        val since = tokenStore.autoUploadSince() ?: return 0
        var uploaded = 0
        val pending = db.activitySessionDao().recent(AUTO_SCAN_LIMIT)
            .filter { it.statusRaw == "finished" && it.stravaActivityId == null && it.endedAt != null }
            .sortedBy { it.endedAt }

        for (session in pending) {
            if ((session.endedAt ?: 0L) < since) continue   // predates the connection
            when (val result = upload(session, db, tokenStore)) {
                is Result.Success -> {
                    db.activitySessionDao().upsert(session.copy(stravaActivityId = result.activityId))
                    uploaded++
                }
                is Result.Failure -> {
                    android.util.Log.w(TAG, "auto upload stopped at ${session.id}: ${result.message}")
                    tokenStore.saveLastError(result.message)
                    return uploaded
                }
            }
        }
        return uploaded
    }

    /** Strava-style time-of-day name, e.g. "Morning Run" — deliberately unbranded, matching iOS. */
    fun activityName(session: ActivitySessionEntity): String {
        val hour = java.util.Calendar.getInstance()
            .apply { timeInMillis = session.startedAt }
            .get(java.util.Calendar.HOUR_OF_DAY)
        val period = when (hour) {
            in 4..10 -> "Morning"
            in 11..13 -> "Lunch"
            in 14..17 -> "Afternoon"
            in 18..21 -> "Evening"
            else -> "Night"
        }
        return "$period ${StravaSportMapping.displayLabel(session.type)}"
    }

    /**
     * Extracts the existing activity id from a duplicate error, e.g.
     * "workout.tcx is a duplicate of activity 123456".
     */
    internal fun parseDuplicate(message: String): Long? =
        Regex("duplicate of (?:activity )?(\\d+)", RegexOption.IGNORE_CASE)
            .find(message)?.groupValues?.get(1)?.toLongOrNull()

    /** Local wall-clock with the device's UTC offset, as Strava's `start_date_local` expects. */
    private fun localIso8601(epochMs: Long): String {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", java.util.Locale.US)
        return sdf.format(java.util.Date(epochMs))
    }

    @Serializable
    private data class UploadStatus(
        val id: Long,
        val activity_id: Long? = null,
        val error: String? = null,
        val status: String? = null,
    )

    @Serializable
    private data class ActivitySummary(val id: Long, val sport_type: String? = null)
}
