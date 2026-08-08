package com.pulseloop.strava

import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.data.entity.ActivitySessionEntity
import com.pulseloop.ring.MeasurementKind
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType

object StravaUploader {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun upload(
        session: ActivitySessionEntity,
        db: PulseLoopDatabase,
        tokenStore: StravaTokenStore,
    ): Long? {
        val tokens = tokenStore.get() ?: return null
        val gpsPoints = db.activityGpsPointDao().forSession(session.id)
        val hrSamples = db.measurementDao().range(MeasurementKind.HEART_RATE.name, session.startedAt, session.endedAt ?: System.currentTimeMillis())

        val tcx = StravaTCXBuilder.build(session, gpsPoints, hrSamples)
        val tcxBytes = tcx.toByteArray(Charsets.UTF_8)

        val body = okhttp3.MultipartBody.Builder()
            .setType(okhttp3.MultipartBody.FORM)
            .addFormDataPart("file", "activity.tcx", okhttp3.RequestBody.create("application/xml".toMediaType(), tcxBytes))
            .addFormDataPart("data_type", "tcx")
            .addFormDataPart("external_id", session.id)
            .build()

        val response = StravaAuth.authenticatedRequest(tokens, tokenStore, "POST", "https://www.strava.com/api/v3/uploads", body)
        val respBody = response.body?.string() ?: return null

        if (response.code == 409) {
            // Duplicate — parse existing activity ID from error message
            val dupPattern = Regex("duplicate of (?:activity )?(\\d+)")
            val dupId = dupPattern.find(respBody)?.groupValues?.get(1)?.toLongOrNull()
            if (dupId != null) {
                fixSportType(dupId, session.type, tokens, tokenStore)
                return dupId
            }
            return null
        }

        if (!response.isSuccessful) return null

        val uploadResp = json.decodeFromString<UploadResponse>(respBody)
        return if (uploadResp.activity_id != null) {
            pollUntilDone(uploadResp.id, tokens, tokenStore)
            fixSportType(uploadResp.activity_id, session.type, tokens, tokenStore)
            uploadResp.activity_id
        } else {
            pollUploadId(uploadResp.id, tokens, tokenStore)
        }
    }

    private suspend fun pollUploadId(uploadId: Long, tokens: StravaTokens, tokenStore: StravaTokenStore): Long? {
        repeat(15) {
            kotlinx.coroutines.delay(2000)
            val response = StravaAuth.authenticatedRequest(tokens, tokenStore, "GET", "https://www.strava.com/api/v3/uploads/$uploadId")
            val body = response.body?.string() ?: return@repeat
            val status = json.decodeFromString<UploadStatus>(body)
            if (status.activity_id != null) return status.activity_id
            if (status.error?.contains("duplicate", ignoreCase = true) == true) {
                val dupPattern = Regex("duplicate of (?:activity )?(\\d+)")
                return dupPattern.find(status.error)?.groupValues?.get(1)?.toLongOrNull()
            }
        }
        return null
    }

    private suspend fun pollUntilDone(uploadId: Long, tokens: StravaTokens, tokenStore: StravaTokenStore) {
        repeat(15) {
            kotlinx.coroutines.delay(2000)
            val response = StravaAuth.authenticatedRequest(tokens, tokenStore, "GET", "https://www.strava.com/api/v3/uploads/$uploadId")
            val body = response.body?.string() ?: return@repeat
            try {
                val status = json.decodeFromString<UploadStatus>(body)
                if (status.activity_id != null) return
                if (status.error?.isNotBlank() == true && status.activity_id == null) return@repeat
            } catch (_: Exception) {
                return@repeat
            }
        }
    }

    private suspend fun fixSportType(activityId: Long, type: String, tokens: StravaTokens, tokenStore: StravaTokenStore) {
        val stravaType = StravaSportMapping.toStravaType(type)
        if (stravaType == null || type == "run" || type == "cycle") return
        val jsonBody = """{"sport_type":"$stravaType"}"""
        val body = okhttp3.RequestBody.create("application/json".toMediaType(), jsonBody)
        repeat(2) {
            val response = StravaAuth.authenticatedRequest(tokens, tokenStore, "PUT", "https://www.strava.com/api/v3/activities/$activityId", body)
            if (response.isSuccessful) return
            kotlinx.coroutines.delay(3000)
        }
    }

    suspend fun uploadAuto(db: PulseLoopDatabase, tokenStore: StravaTokenStore) {
        val sessions = db.activitySessionDao().recent(20).filter { it.statusRaw == "finished" && it.stravaActivityId == null }
        for (session in sessions) {
            val activityId = upload(session, db, tokenStore)
            if (activityId != null) {
                db.activitySessionDao().upsert(session.copy(stravaActivityId = activityId))
            } else {
                break
            }
        }
    }

    @Serializable
    private data class UploadResponse(val id: Long, val activity_id: Long? = null)

    @Serializable
    private data class UploadStatus(val activity_id: Long? = null, val error: String? = null)
}
