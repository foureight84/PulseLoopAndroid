package com.pulseloop.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.sqlite.db.SimpleSQLiteQuery
import com.pulseloop.BuildConfig
import com.pulseloop.data.entity.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DataArchiveService {

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    private val dateFmt = SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US)

    private fun insertRaw(db: PulseLoopDatabase, table: String, cv: ContentValues): Long =
        db.openHelper.writableDatabase.insert(table, SQLiteDatabase.CONFLICT_NONE, cv)

    suspend fun exportArchive(db: PulseLoopDatabase): PulseArchive = withContext(Dispatchers.Default) {
        val counts = mutableMapOf<String, Int>()

        fun <T> collect(table: String, mapper: (Cursor) -> T): List<T> {
            val result = mutableListOf<T>()
            db.openHelper.readableDatabase.query(SimpleSQLiteQuery("SELECT * FROM $table ORDER BY rowid ASC")).use { cursor ->
                while (cursor.moveToNext()) {
                    result.add(mapper(cursor))
                }
            }
            counts[table] = result.size
            return result
        }

        PulseArchive(
            formatVersion = 1,
            exportedAt = System.currentTimeMillis(),
            appVersion = BuildConfig.VERSION_NAME,
            counts = counts,
            devices = collect("devices") { c ->
                DeviceDTO(
                    id = c.str("id"), name = c.str("name"), advertisedName = c.strOrNull("advertisedName"),
                    peripheralIdentifier = c.strOrNull("peripheralIdentifier"), bleAddressHint = c.strOrNull("bleAddressHint"),
                    batteryPercent = c.intOrNull("batteryPercent"), stateRaw = c.str("stateRaw"),
                    deviceTypeRaw = c.str("deviceTypeRaw"), wearableModelID = c.strOrNull("wearableModelID"),
                    capabilitiesRaw = c.str("capabilitiesRaw"), lastConnectedAt = c.longOrNull("lastConnectedAt"),
                    lastDisconnectedAt = c.longOrNull("lastDisconnectedAt"), lastSyncAt = c.longOrNull("lastSyncAt"),
                    lastFullSyncAt = c.longOrNull("lastFullSyncAt"), firmwareVersion = c.strOrNull("firmwareVersion"),
                    createdAt = c.long("createdAt"), updatedAt = c.long("updatedAt"),
                )
            },
            measurements = collect("measurements") { c ->
                MeasurementDTO(
                    id = c.str("id"), kindRaw = c.str("kindRaw"), value = c.dbl("value"),
                    unit = c.str("unit"), timestamp = c.long("timestamp"), sourceRaw = c.str("sourceRaw"),
                    confidenceRaw = c.str("confidenceRaw"), activitySessionId = c.strOrNull("activitySessionId"),
                    rawPacketId = c.strOrNull("rawPacketId"), createdAt = c.long("createdAt"),
                )
            },
            activityDaily = collect("activity_daily") { c ->
                ActivityDailyDTO(
                    id = c.str("id"), date = c.long("date"), steps = c.int_("steps"),
                    calories = c.dbl("calories"), distanceMeters = c.dbl("distanceMeters"),
                    activeMinutes = c.int_("activeMinutes"), source = c.str("source"),
                    syncedAt = c.longOrNull("syncedAt"), createdAt = c.long("createdAt"),
                    updatedAt = c.long("updatedAt"),
                )
            },
            activityBuckets = collect("activity_buckets") { c ->
                ActivityBucketDTO(
                    startEpoch = c.long("startEpoch"), date = c.long("date"),
                    steps = c.int_("steps"), distanceMeters = c.dbl("distanceMeters"),
                    source = c.str("source"), updatedAt = c.long("updatedAt"),
                )
            },
            batterySamples = collect("battery_samples") { c ->
                BatterySampleDTO(
                    id = c.str("id"), percent = c.int_("percent"),
                    timestamp = c.long("timestamp"), createdAt = c.long("createdAt"),
                )
            },
            deviceMeasurementConfigs = collect("device_measurement_configs") { c ->
                DeviceMeasurementConfigDTO(
                    deviceId = c.str("deviceId"), hrIntervalMinutes = c.int_("hrIntervalMinutes"),
                    hrEnabled = c.bool("hrEnabled"), spo2Enabled = c.bool("spo2Enabled"),
                    stressEnabled = c.bool("stressEnabled"), hrvEnabled = c.bool("hrvEnabled"),
                    temperatureEnabled = c.bool("temperatureEnabled"), updatedAt = c.long("updatedAt"),
                )
            },
            activitySessions = collect("activity_sessions") { c ->
                ActivitySessionDTO(
                    id = c.str("id"), type = c.str("type"), statusRaw = c.str("statusRaw"),
                    startedAt = c.long("startedAt"), endedAt = c.longOrNull("endedAt"),
                    totalPauseSeconds = c.dbl("totalPauseSeconds"), calories = c.dblOrNull("calories"),
                    distanceMeters = c.dblOrNull("distanceMeters"), avgHeartRate = c.dblOrNull("avgHeartRate"),
                    minHeartRate = c.dblOrNull("minHeartRate"), maxHeartRate = c.dblOrNull("maxHeartRate"),
                    avgSpO2 = c.dblOrNull("avgSpO2"), latestSpO2 = c.dblOrNull("latestSpO2"),
                    notes = c.strOrNull("notes"), useGps = c.bool("useGps"),
                    perceivedEffort = c.strOrNull("perceivedEffort"), gpsPointCount = c.int_("gpsPointCount"),
                    rejectedGpsPointCount = c.int_("rejectedGpsPointCount"),
                    hrPollCount = c.int_("hrPollCount"), hrPollFailureCount = c.int_("hrPollFailureCount"),
                    spo2PollCount = c.int_("spo2PollCount"), spo2PollFailureCount = c.int_("spo2PollFailureCount"),
                    liveActivityID = c.strOrNull("liveActivityID"),
                    lastSensorPollAt = c.longOrNull("lastSensorPollAt"),
                    lastGpsPointAt = c.longOrNull("lastGpsPointAt"),
                    createdAt = c.long("createdAt"), updatedAt = c.long("updatedAt"),
                )
            },
            activityGpsPoints = collect("activity_gps_points") { c ->
                ActivityGpsPointDTO(
                    id = c.str("id"), sessionId = c.str("sessionId"),
                    latitude = c.dbl("latitude"), longitude = c.dbl("longitude"),
                    altitude = c.dblOrNull("altitude"), horizontalAccuracy = c.dblOrNull("horizontalAccuracy"),
                    speed = c.dblOrNull("speed"), course = c.dblOrNull("course"),
                    timestamp = c.long("timestamp"), accepted = c.bool("accepted"),
                    rejectionReason = c.strOrNull("rejectionReason"),
                )
            },
            activityEvents = collect("activity_events") { c ->
                ActivityEventDTO(
                    id = c.str("id"), sessionId = c.str("sessionId"), kind = c.str("kind"),
                    timestamp = c.long("timestamp"), payloadJSON = c.strOrNull("payloadJSON"),
                )
            },
            activitySamples = collect("activity_samples") { c ->
                ActivitySampleDTO(
                    id = c.str("id"), sessionId = c.str("sessionId"),
                    measurementId = c.strOrNull("measurementId"), kind = c.str("kind"),
                    value = c.dbl("value"), unit = c.str("unit"), timestamp = c.long("timestamp"),
                    source = c.str("source"), confidenceRaw = c.str("confidenceRaw"),
                )
            },
            activitySensorPolls = collect("activity_sensor_polls") { c ->
                ActivitySensorPollDTO(
                    id = c.str("id"), sessionId = c.str("sessionId"), timestamp = c.long("timestamp"),
                    kind = c.str("kind"), status = c.str("status"), value = c.dblOrNull("value"),
                    errorMessage = c.strOrNull("errorMessage"),
                )
            },
            sleepSessions = collect("sleep_sessions") { c ->
                SleepSessionDTO(
                    id = c.str("id"), date = c.long("date"), startAt = c.long("startAt"),
                    endAt = c.long("endAt"), totalMinutes = c.int_("totalMinutes"),
                    score = c.intOrNull("score"), syncedAt = c.longOrNull("syncedAt"),
                    sourceRaw = c.str("sourceRaw"), createdAt = c.long("createdAt"),
                    updatedAt = c.long("updatedAt"),
                )
            },
            sleepStageBlocks = collect("sleep_stage_blocks") { c ->
                SleepStageBlockDTO(
                    id = c.str("id"), sessionId = c.str("sessionId"), startAt = c.long("startAt"),
                    startMinute = c.int_("startMinute"), durationMinutes = c.int_("durationMinutes"),
                    stageRaw = c.str("stageRaw"),
                )
            },
            coachConversations = collect("coach_conversations") { c ->
                CoachConversationDTO(
                    id = c.str("id"), title = c.str("title"), createdAt = c.long("createdAt"),
                    updatedAt = c.long("updatedAt"), totalInputTokens = c.int_("totalInputTokens"),
                    totalOutputTokens = c.int_("totalOutputTokens"), totalCostUSD = c.dbl("totalCostUSD"),
                )
            },
            coachMessages = collect("coach_messages") { c ->
                CoachMessageDTO(
                    id = c.str("id"), conversationId = c.str("conversationId"),
                    role = c.str("role"), body = c.str("body"),
                    cardsJSON = c.strOrNull("cardsJSON"), pendingActionJSON = c.strOrNull("pendingActionJSON"),
                    attachmentsJson = c.strOrNull("attachmentsJson"), createdAt = c.long("createdAt"),
                    inputTokens = c.intOrNull("inputTokens"), outputTokens = c.intOrNull("outputTokens"),
                    costUSD = c.dblOrNull("costUSD"), modelUsed = c.strOrNull("modelUsed"),
                    providerUsed = c.strOrNull("providerUsed"),
                )
            },
            coachMemories = collect("coach_memories") { c ->
                CoachMemoryDTO(
                    id = c.str("id"), key = c.str("key"), value = c.str("value"),
                    memoryType = c.str("memoryType"), importance = c.int_("importance"),
                    expiresAt = c.longOrNull("expiresAt"), sourceMessageId = c.strOrNull("sourceMessageId"),
                    isUserEditable = c.bool("isUserEditable"), createdAt = c.long("createdAt"),
                    updatedAt = c.long("updatedAt"),
                )
            },
            coachToolCalls = collect("coach_tool_calls") { c ->
                CoachToolCallDTO(
                    id = c.str("id"), conversationId = c.str("conversationId"),
                    messageId = c.strOrNull("messageId"), toolName = c.str("toolName"),
                    inputJSON = c.strOrNull("inputJSON"), outputJSON = c.strOrNull("outputJSON"),
                    label = c.str("label"), statusRaw = c.str("statusRaw"),
                    sequence = c.int_("sequence"), createdAt = c.long("createdAt"),
                )
            },
            userProfiles = collect("user_profiles") { c ->
                UserProfileDTO(
                    id = c.str("id"), name = c.strOrNull("name"), age = c.intOrNull("age"),
                    sex = c.strOrNull("sex"), heightCm = c.dblOrNull("heightCm"),
                    weightKg = c.dblOrNull("weightKg"), onboardingCompleted = c.bool("onboardingCompleted"),
                    baselineCompleted = c.bool("baselineCompleted"),
                    createdAt = c.long("createdAt"), updatedAt = c.long("updatedAt"),
                )
            },
            userGoals = collect("user_goals") { c ->
                UserGoalDTO(
                    id = c.str("id"), steps = c.int_("steps"),
                    distanceMeters = c.dbl("distanceMeters"), calories = c.int_("calories"),
                    sleepMinutes = c.int_("sleepMinutes"), activeMinutes = c.int_("activeMinutes"),
                    workoutsPerWeek = c.int_("workoutsPerWeek"), updatedAt = c.long("updatedAt"),
                )
            },
            rawPackets = collect("raw_packets") { c ->
                RawPacketDTO(
                    id = c.str("id"), timestamp = c.long("timestamp"),
                    directionRaw = c.str("directionRaw"), commandId = c.int_("commandId"),
                    hexPayload = c.str("hexPayload"), decodedKind = c.strOrNull("decodedKind"),
                    decodedJSON = c.strOrNull("decodedJSON"), confidenceRaw = c.str("confidenceRaw"),
                    createdAt = c.long("createdAt"),
                )
            },
            derivedUpdates = collect("derived_updates") { c ->
                DerivedUpdateDTO(
                    id = c.str("id"), timestamp = c.long("timestamp"), kind = c.str("kind"),
                    entityType = c.str("entityType"), entityId = c.str("entityId"),
                    payloadJSON = c.strOrNull("payloadJSON"),
                )
            },
            coachSummaries = collect("coach_summaries") { c ->
                CoachSummaryDTO(
                    id = c.str("id"), kind = c.str("kind"), scopeKey = c.str("scopeKey"),
                    title = c.str("title"), body = c.str("body"),
                    chipsJSON = c.strOrNull("chipsJSON"), conversationId = c.strOrNull("conversationId"),
                    dataSignature = c.str("dataSignature"), createdAt = c.long("createdAt"),
                    updatedAt = c.long("updatedAt"),
                )
            },
            wearableLogs = collect("wearable_logs") { c ->
                WearableLogDTO(
                    id = c.str("id"), timestamp = c.long("timestamp"),
                    event = "${c.str("categoryRaw")}/${c.str("levelRaw")}: ${c.str("message")}",
                    detail = c.strOrNull("metadataJSON"),
                    deviceId = c.strOrNull("deviceTypeRaw"),
                    categoryRaw = c.str("categoryRaw"),
                    levelRaw = c.str("levelRaw"),
                )
            },
            coachNotificationRecords = collect("coach_notification_records") { c ->
                CoachNotificationRecordDTO(
                    id = c.str("id"), title = c.str("title"), body = c.str("body"),
                    createdAt = c.long("createdAt"),
                )
            },
        )
    }

    suspend fun exportToFile(context: Context, db: PulseLoopDatabase): Uri? = withContext(Dispatchers.Default) {
        val archive = exportArchive(db)
        val jsonStr = json.encodeToString(PulseArchive.serializer(), archive)
        val name = "pulseloop-export-${dateFmt.format(Date())}.json"
        val file = java.io.File(context.cacheDir, name)
        file.writeText(jsonStr)
        androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    suspend fun importFile(context: Context, uri: Uri, db: PulseLoopDatabase): PulseArchive = withContext(Dispatchers.Default) {
        val stream = context.contentResolver.openInputStream(uri)
            ?: throw java.io.IOException("Cannot open file")
        val jsonStr = stream.bufferedReader().use { it.readText() }
        val archive = json.decodeFromString(PulseArchive.serializer(), jsonStr)

        // Wrap the entire restore in a single transaction so a process kill mid-import
        // leaves the original database intact (no data loss) rather than permanently empty.
        val writableDb = db.openHelper.writableDatabase
        writableDb.beginTransaction()
        try {
            writableDb.execSQL("PRAGMA foreign_keys = OFF")
            for (table in listOf(
                "devices", "measurements", "activity_daily", "activity_buckets",
                "battery_samples", "device_measurement_configs", "activity_sessions",
                "activity_gps_points", "activity_events", "activity_samples",
                "activity_sensor_polls", "sleep_sessions", "sleep_stage_blocks",
                "coach_conversations", "coach_messages", "coach_memories",
                "coach_tool_calls", "user_profiles", "user_goals",
                "raw_packets", "derived_updates", "coach_summaries",
                "wearable_logs", "coach_notification_records",
                "meal_entries", "food_products",
            )) {
                writableDb.execSQL("DELETE FROM $table")
            }

            for (d in archive.devices) {
                db.deviceDao().upsert(DeviceEntity(
                    id = d.id, name = d.name, advertisedName = d.advertisedName,
                    peripheralIdentifier = d.peripheralIdentifier, bleAddressHint = d.bleAddressHint,
                    batteryPercent = d.batteryPercent, stateRaw = d.stateRaw,
                    deviceTypeRaw = d.deviceTypeRaw, wearableModelID = d.wearableModelID,
                    capabilitiesRaw = d.capabilitiesRaw, lastConnectedAt = d.lastConnectedAt,
                    lastDisconnectedAt = d.lastDisconnectedAt, lastSyncAt = d.lastSyncAt,
                    lastFullSyncAt = d.lastFullSyncAt, firmwareVersion = d.firmwareVersion,
                    createdAt = d.createdAt, updatedAt = d.updatedAt,
                ))
            }
            for (m in archive.measurements) {
                db.measurementDao().insert(MeasurementEntity(
                    id = m.id, kindRaw = m.kindRaw, value = m.value, unit = m.unit,
                    timestamp = m.timestamp, sourceRaw = m.sourceRaw,
                    confidenceRaw = m.confidenceRaw, activitySessionId = m.activitySessionId,
                    rawPacketId = m.rawPacketId, createdAt = m.createdAt,
                ))
            }
            for (a in archive.activityDaily) {
                db.activityDailyDao().upsert(ActivityDailyEntity(
                    id = a.id, date = a.date, steps = a.steps, calories = a.calories,
                    distanceMeters = a.distanceMeters, activeMinutes = a.activeMinutes,
                    source = a.source, syncedAt = a.syncedAt, createdAt = a.createdAt,
                    updatedAt = a.updatedAt,
                ))
            }
            for (b in archive.activityBuckets) {
                db.activityBucketDao().upsert(ActivityBucketEntity(
                    startEpoch = b.startEpoch, date = b.date, steps = b.steps,
                    distanceMeters = b.distanceMeters, source = b.source, updatedAt = b.updatedAt,
                ))
            }
            for (bs in archive.batterySamples) {
                db.batterySampleDao().insert(BatterySampleEntity(
                    id = bs.id, percent = bs.percent, timestamp = bs.timestamp,
                    createdAt = bs.createdAt,
                ))
            }
            for (dmc in archive.deviceMeasurementConfigs) {
                db.deviceMeasurementConfigDao().upsert(DeviceMeasurementConfigEntity(
                    deviceId = dmc.deviceId, hrIntervalMinutes = dmc.hrIntervalMinutes,
                    hrEnabled = dmc.hrEnabled, spo2Enabled = dmc.spo2Enabled,
                    stressEnabled = dmc.stressEnabled, hrvEnabled = dmc.hrvEnabled,
                    temperatureEnabled = dmc.temperatureEnabled, updatedAt = dmc.updatedAt,
                ))
            }
            for (as_ in archive.activitySessions) {
                db.activitySessionDao().upsert(ActivitySessionEntity(
                    id = as_.id, type = as_.type, statusRaw = as_.statusRaw,
                    startedAt = as_.startedAt, endedAt = as_.endedAt,
                    totalPauseSeconds = as_.totalPauseSeconds, calories = as_.calories,
                    distanceMeters = as_.distanceMeters, avgHeartRate = as_.avgHeartRate,
                    minHeartRate = as_.minHeartRate, maxHeartRate = as_.maxHeartRate,
                    avgSpO2 = as_.avgSpO2, latestSpO2 = as_.latestSpO2,
                    notes = as_.notes, useGps = as_.useGps,
                    perceivedEffort = as_.perceivedEffort, gpsPointCount = as_.gpsPointCount,
                    rejectedGpsPointCount = as_.rejectedGpsPointCount,
                    hrPollCount = as_.hrPollCount, hrPollFailureCount = as_.hrPollFailureCount,
                    spo2PollCount = as_.spo2PollCount, spo2PollFailureCount = as_.spo2PollFailureCount,
                    liveActivityID = as_.liveActivityID, lastSensorPollAt = as_.lastSensorPollAt,
                    lastGpsPointAt = as_.lastGpsPointAt, createdAt = as_.createdAt,
                    updatedAt = as_.updatedAt,
                ))
            }
            for (gp in archive.activityGpsPoints) {
                db.activityGpsPointDao().insert(ActivityGpsPointEntity(
                    id = gp.id, sessionId = gp.sessionId, latitude = gp.latitude,
                    longitude = gp.longitude, altitude = gp.altitude,
                    horizontalAccuracy = gp.horizontalAccuracy, speed = gp.speed,
                    course = gp.course, timestamp = gp.timestamp, accepted = gp.accepted,
                    rejectionReason = gp.rejectionReason,
                ))
            }
            for (ev in archive.activityEvents) {
                insertRaw(db, "activity_events", ContentValues().apply {
                    put("id", ev.id); put("sessionId", ev.sessionId); put("kind", ev.kind)
                    put("timestamp", ev.timestamp); ev.payloadJSON?.let { put("payloadJSON", it) }
                })
            }
            for (samp in archive.activitySamples) {
                insertRaw(db, "activity_samples", ContentValues().apply {
                    put("id", samp.id); put("sessionId", samp.sessionId)
                    samp.measurementId?.let { put("measurementId", it) }
                    put("kind", samp.kind); put("value", samp.value); put("unit", samp.unit)
                    put("timestamp", samp.timestamp); put("source", samp.source)
                    put("confidenceRaw", samp.confidenceRaw)
                })
            }
            for (sp in archive.activitySensorPolls) {
                insertRaw(db, "activity_sensor_polls", ContentValues().apply {
                    put("id", sp.id); put("sessionId", sp.sessionId)
                    put("timestamp", sp.timestamp); put("kind", sp.kind)
                    put("status", sp.status); sp.value?.let { put("value", it) }
                    sp.errorMessage?.let { put("errorMessage", it) }
                })
            }
            for (ss in archive.sleepSessions) {
                db.sleepSessionDao().upsert(SleepSessionEntity(
                    id = ss.id, date = ss.date, startAt = ss.startAt, endAt = ss.endAt,
                    totalMinutes = ss.totalMinutes, score = ss.score, syncedAt = ss.syncedAt,
                    sourceRaw = ss.sourceRaw, createdAt = ss.createdAt, updatedAt = ss.updatedAt,
                ))
            }
            for (block in archive.sleepStageBlocks) {
                db.sleepStageBlockDao().insert(SleepStageBlockEntity(
                    id = block.id, sessionId = block.sessionId, startAt = block.startAt,
                    startMinute = block.startMinute, durationMinutes = block.durationMinutes,
                    stageRaw = block.stageRaw,
                ))
            }
            for (conv in archive.coachConversations) {
                db.coachConversationDao().upsert(CoachConversationEntity(
                    id = conv.id, title = conv.title, createdAt = conv.createdAt,
                    updatedAt = conv.updatedAt, totalInputTokens = conv.totalInputTokens,
                    totalOutputTokens = conv.totalOutputTokens, totalCostUSD = conv.totalCostUSD,
                ))
            }
            for (msg in archive.coachMessages) {
                db.coachMessageDao().insert(CoachMessageEntity(
                    id = msg.id, conversationId = msg.conversationId, role = msg.role,
                    body = msg.body, cardsJSON = msg.cardsJSON,
                    pendingActionJSON = msg.pendingActionJSON,
                    attachmentsJson = msg.attachmentsJson, createdAt = msg.createdAt,
                    inputTokens = msg.inputTokens, outputTokens = msg.outputTokens,
                    costUSD = msg.costUSD, modelUsed = msg.modelUsed,
                    providerUsed = msg.providerUsed,
                ))
            }
            for (mem in archive.coachMemories) {
                db.coachMemoryDao().upsert(CoachMemoryEntity(
                    id = mem.id, key = mem.key, value = mem.value,
                    memoryType = mem.memoryType, importance = mem.importance,
                    expiresAt = mem.expiresAt, sourceMessageId = mem.sourceMessageId,
                    isUserEditable = mem.isUserEditable, createdAt = mem.createdAt,
                    updatedAt = mem.updatedAt,
                ))
            }
            for (tc in archive.coachToolCalls) {
                db.coachToolCallDao().insert(CoachToolCallEntity(
                    id = tc.id, conversationId = tc.conversationId,
                    messageId = tc.messageId, toolName = tc.toolName,
                    inputJSON = tc.inputJSON, outputJSON = tc.outputJSON,
                    label = tc.label, statusRaw = tc.statusRaw,
                    sequence = tc.sequence, createdAt = tc.createdAt,
                ))
            }
            for (up in archive.userProfiles) {
                db.userProfileDao().upsert(UserProfileEntity(
                    id = up.id, name = up.name, age = up.age, sex = up.sex,
                    heightCm = up.heightCm, weightKg = up.weightKg,
                    onboardingCompleted = up.onboardingCompleted,
                    baselineCompleted = up.baselineCompleted,
                    createdAt = up.createdAt, updatedAt = up.updatedAt,
                ))
            }
            for (ug in archive.userGoals) {
                db.userGoalDao().upsert(UserGoalEntity(
                    id = ug.id, steps = ug.steps, distanceMeters = ug.distanceMeters,
                    calories = ug.calories, sleepMinutes = ug.sleepMinutes,
                    activeMinutes = ug.activeMinutes, workoutsPerWeek = ug.workoutsPerWeek,
                    updatedAt = ug.updatedAt,
                ))
            }
            for (rp in archive.rawPackets) {
                db.rawPacketDao().insert(RawPacketEntity(
                    id = rp.id, timestamp = rp.timestamp, directionRaw = rp.directionRaw,
                    commandId = rp.commandId, hexPayload = rp.hexPayload,
                    decodedKind = rp.decodedKind, decodedJSON = rp.decodedJSON,
                    confidenceRaw = rp.confidenceRaw, createdAt = rp.createdAt,
                ))
            }
            for (du in archive.derivedUpdates) {
                insertRaw(db, "derived_updates", ContentValues().apply {
                    put("id", du.id); put("timestamp", du.timestamp); put("kind", du.kind)
                    put("entityType", du.entityType); put("entityId", du.entityId)
                    du.payloadJSON?.let { put("payloadJSON", it) }
                })
            }
            for (cs in archive.coachSummaries) {
                db.coachSummaryDao().upsert(CoachSummaryEntity(
                    id = cs.id, kind = cs.kind, scopeKey = cs.scopeKey,
                    title = cs.title, body = cs.body, chipsJSON = cs.chipsJSON,
                    conversationId = cs.conversationId, dataSignature = cs.dataSignature,
                    createdAt = cs.createdAt, updatedAt = cs.updatedAt,
                ))
            }
            for (wl in archive.wearableLogs) {
                db.wearableLogDao().insert(WearableLogEntity(
                    id = wl.id, timestamp = wl.timestamp,
                    categoryRaw = wl.categoryRaw ?: "CONNECTION",
                    levelRaw = wl.levelRaw ?: "INFO",
                    message = wl.event, metadataJSON = wl.detail,
                    deviceTypeRaw = wl.deviceId ?: "",
                ))
            }
            for (nr in archive.coachNotificationRecords) {
                db.coachNotificationRecordDao().insert(CoachNotificationRecordEntity(
                    id = nr.id, title = nr.title, body = nr.body, createdAt = nr.createdAt,
                ))
            }

            writableDb.execSQL("PRAGMA foreign_keys = ON")
            writableDb.setTransactionSuccessful()
        } finally {
            writableDb.endTransaction()
        }

        archive
    }

    // --- Cursor helpers ---

    private fun Cursor.str(col: String): String =
        getString(getColumnIndexOrThrow(col))

    private fun Cursor.strOrNull(col: String): String? {
        val idx = getColumnIndex(col)
        return if (idx < 0) null else getString(idx)
    }

    private fun Cursor.int_(col: String): Int =
        getInt(getColumnIndexOrThrow(col))

    private fun Cursor.intOrNull(col: String): Int? {
        val idx = getColumnIndex(col)
        return if (idx < 0 || isNull(idx)) null else getInt(idx)
    }

    private fun Cursor.long(col: String): Long =
        getLong(getColumnIndexOrThrow(col))

    private fun Cursor.longOrNull(col: String): Long? {
        val idx = getColumnIndex(col)
        return if (idx < 0 || isNull(idx)) null else getLong(idx)
    }

    private fun Cursor.dbl(col: String): Double =
        getDouble(getColumnIndexOrThrow(col))

    private fun Cursor.dblOrNull(col: String): Double? {
        val idx = getColumnIndex(col)
        return if (idx < 0 || isNull(idx)) null else getDouble(idx)
    }

    private fun Cursor.bool(col: String): Boolean =
        getInt(getColumnIndexOrThrow(col)) != 0
}
