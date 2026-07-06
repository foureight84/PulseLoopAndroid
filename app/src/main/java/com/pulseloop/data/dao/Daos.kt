package com.pulseloop.data.dao

import androidx.room.*
import com.pulseloop.data.entity.*
import kotlinx.coroutines.flow.Flow

/** Aggregated bucket returned by hourlyAggregates / dailyAggregates queries. */
data class Bucket(
    val bucket: Long,
    val avgValue: Double,
    val minValue: Double,
    val maxValue: Double,
)

@Dao
interface DeviceDao {
    @Query("SELECT * FROM devices ORDER BY updatedAt DESC LIMIT 1")
    suspend fun current(): DeviceEntity?

    @Query("SELECT * FROM devices ORDER BY updatedAt DESC LIMIT 1")
    fun currentFlow(): Flow<DeviceEntity?>

    @Upsert
    suspend fun upsert(device: DeviceEntity)

    @Query("DELETE FROM devices")
    suspend fun clear()

    /** Targeted removal (e.g. the demo device) without touching a paired ring's row. */
    @Query("DELETE FROM devices WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface MeasurementDao {
    @Query("SELECT * FROM measurements WHERE kindRaw = :kind AND timestamp BETWEEN :start AND :end ORDER BY timestamp ASC")
    suspend fun range(kind: String, start: Long, end: Long): List<MeasurementEntity>

    @Query("SELECT * FROM measurements WHERE kindRaw = :kind AND timestamp BETWEEN :start AND :end ORDER BY timestamp ASC")
    fun rangeFlow(kind: String, start: Long, end: Long): Flow<List<MeasurementEntity>>

    @Query("SELECT value FROM measurements WHERE kindRaw = :kind AND timestamp <= :before ORDER BY timestamp DESC LIMIT 1")
    suspend fun latest(kind: String, before: Long = System.currentTimeMillis()): Double?

    /** Whether any demo-seeded row exists for a kind — mirrors iOS `hasMockMeasurement(kind:)`,
     *  which switches chart fetches from the range window to full history in demo mode. */
    @Query("SELECT EXISTS(SELECT 1 FROM measurements WHERE kindRaw = :kind AND sourceRaw = 'demo')")
    suspend fun hasDemo(kind: String): Boolean

    @Insert
    suspend fun insert(measurement: MeasurementEntity)

    @Query("DELETE FROM measurements WHERE sourceRaw = 'demo'")
    suspend fun clearDemo()

    @Query("DELETE FROM measurements")
    suspend fun clear()

    /** Average per LOCAL-day bucket — pass tzOffsetMs = ZoneId.systemDefault() offset for now.
     *  Week = 7 buckets, Month = ~30 buckets. */
    @Query("""
        SELECT (CAST((timestamp + :tzOffsetMs) / 86400000 AS INTEGER) * 86400000) - :tzOffsetMs AS bucket,
               AVG(value) AS avgValue, MIN(value) AS minValue, MAX(value) AS maxValue
        FROM measurements
        WHERE kindRaw = :kind AND timestamp BETWEEN :start AND :end
        GROUP BY bucket ORDER BY bucket ASC
    """)
    suspend fun dailyAggregates(kind: String, start: Long, end: Long, tzOffsetMs: Long): List<Bucket>

    /** Average per UTC-hour bucket — used for the Today view. */
    @Query("""
        SELECT CAST(timestamp / 3600000 AS INTEGER) * 3600000 AS bucket,
               AVG(value) AS avgValue, MIN(value) AS minValue, MAX(value) AS maxValue
        FROM measurements
        WHERE kindRaw = :kind AND timestamp BETWEEN :start AND :end
        GROUP BY bucket ORDER BY bucket ASC
    """)
    suspend fun hourlyAggregates(kind: String, start: Long, end: Long): List<Bucket>
}

@Dao
interface ActivityDailyDao {
    @Query("SELECT * FROM activity_daily WHERE date = :day LIMIT 1")
    suspend fun byDay(day: Long): ActivityDailyEntity?

    @Query("SELECT * FROM activity_daily WHERE date = :day LIMIT 1")
    fun byDayFlow(day: Long): Flow<ActivityDailyEntity?>

    @Query("SELECT * FROM activity_daily ORDER BY date DESC LIMIT :limit")
    suspend fun recent(limit: Int = 7): List<ActivityDailyEntity>

    @Query("SELECT * FROM activity_daily ORDER BY date DESC LIMIT :limit")
    fun recentFlow(limit: Int = 7): Flow<List<ActivityDailyEntity>>

    @Upsert
    suspend fun upsert(entry: ActivityDailyEntity)

    @Query("DELETE FROM activity_daily")
    suspend fun clear()

    @Query("DELETE FROM activity_daily WHERE source = 'demo'")
    suspend fun clearDemo()
}

@Dao
interface ActivityBucketDao {
    @Query("SELECT * FROM activity_buckets WHERE date = :day ORDER BY startEpoch ASC")
    suspend fun byDay(day: Long): List<ActivityBucketEntity>

    /** Distinct past days that have synced buckets — the DataRepairs recompute scope. */
    @Query("SELECT DISTINCT date FROM activity_buckets WHERE date < :day ORDER BY date ASC")
    suspend fun daysBefore(day: Long): List<Long>

    @Upsert
    suspend fun upsert(bucket: ActivityBucketEntity)

    @Query("DELETE FROM activity_buckets")
    suspend fun clear()
}

@Dao
interface DeviceMeasurementConfigDao {
    @Query("SELECT * FROM device_measurement_configs WHERE deviceId = :deviceId LIMIT 1")
    suspend fun byDevice(deviceId: String): DeviceMeasurementConfigEntity?

    @Query("SELECT * FROM device_measurement_configs WHERE deviceId = :deviceId LIMIT 1")
    fun byDeviceFlow(deviceId: String): Flow<DeviceMeasurementConfigEntity?>

    @Upsert
    suspend fun upsert(config: DeviceMeasurementConfigEntity)

    @Query("DELETE FROM device_measurement_configs")
    suspend fun clear()
}

@Dao
interface ActivitySessionDao {
    @Query("SELECT * FROM activity_sessions WHERE statusRaw = 'recording' OR statusRaw = 'paused' LIMIT 1")
    suspend fun active(): ActivitySessionEntity?

    @Query("SELECT * FROM activity_sessions WHERE id = :id LIMIT 1")
    suspend fun byId(id: String): ActivitySessionEntity?

    @Query("SELECT * FROM activity_sessions ORDER BY startedAt DESC LIMIT :limit")
    suspend fun recent(limit: Int = 10): List<ActivitySessionEntity>

    @Query("SELECT * FROM activity_sessions ORDER BY startedAt DESC LIMIT :limit")
    fun recentFlow(limit: Int = 10): Flow<List<ActivitySessionEntity>>

    @Upsert
    suspend fun upsert(session: ActivitySessionEntity)
}

@Dao
interface ActivityGpsPointDao {
    @Query("SELECT * FROM activity_gps_points WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    suspend fun forSession(sessionId: String): List<ActivityGpsPointEntity>

    @Insert
    suspend fun insert(point: ActivityGpsPointEntity)
}

@Dao
interface SleepSessionDao {
    @Query("SELECT * FROM sleep_sessions WHERE date = :day LIMIT 1")
    suspend fun byDay(day: Long): SleepSessionEntity?

    @Query("SELECT * FROM sleep_sessions ORDER BY date DESC LIMIT :limit")
    suspend fun recent(limit: Int = 7): List<SleepSessionEntity>

    @Query("SELECT * FROM sleep_sessions ORDER BY date DESC LIMIT :limit")
    fun recentFlow(limit: Int = 7): Flow<List<SleepSessionEntity>>

    @Query("SELECT * FROM sleep_sessions WHERE date BETWEEN :start AND :end ORDER BY date ASC")
    suspend fun inRange(start: Long, end: Long): List<SleepSessionEntity>

    @Upsert
    suspend fun upsert(session: SleepSessionEntity)

    @Query("DELETE FROM sleep_sessions")
    suspend fun clear()
}

@Dao
interface SleepStageBlockDao {
    @Query("SELECT * FROM sleep_stage_blocks WHERE sessionId = :sessionId ORDER BY startAt ASC")
    suspend fun forSession(sessionId: String): List<SleepStageBlockEntity>

    @Insert
    suspend fun insert(block: SleepStageBlockEntity)

    @Query("SELECT * FROM sleep_stage_blocks WHERE sessionId = :sessionId AND startAt = :startAt LIMIT 1")
    suspend fun findBlock(sessionId: String, startAt: Long): SleepStageBlockEntity?

    @Query("DELETE FROM sleep_stage_blocks WHERE sessionId = :sessionId")
    suspend fun deleteBySession(sessionId: String)

    @Query("DELETE FROM sleep_stage_blocks")
    suspend fun clear()
}

@Dao
interface CoachConversationDao {
    @Query("SELECT * FROM coach_conversations ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun recent(limit: Int = 10): List<CoachConversationEntity>

    @Query("SELECT * FROM coach_conversations ORDER BY updatedAt DESC LIMIT :limit")
    fun recentFlow(limit: Int = 10): Flow<List<CoachConversationEntity>>

    @Upsert
    suspend fun upsert(conversation: CoachConversationEntity)
}

@Dao
interface CoachMessageDao {
    @Query("SELECT * FROM coach_messages WHERE conversationId = :convId ORDER BY createdAt ASC")
    suspend fun forConversation(convId: String): List<CoachMessageEntity>

    @Query("SELECT * FROM coach_messages WHERE conversationId = :convId ORDER BY createdAt ASC")
    fun forConversationFlow(convId: String): Flow<List<CoachMessageEntity>>

    @Insert
    suspend fun insert(message: CoachMessageEntity)
}

@Dao
interface CoachMemoryDao {
    @Query("SELECT * FROM coach_memories WHERE key = :key LIMIT 1")
    suspend fun byKey(key: String): CoachMemoryEntity?

    @Query("SELECT * FROM coach_memories ORDER BY importance DESC")
    suspend fun allRanked(): List<CoachMemoryEntity>

    @Upsert
    suspend fun upsert(memory: CoachMemoryEntity)

    @Query("DELETE FROM coach_memories WHERE expiresAt IS NOT NULL AND expiresAt < :now")
    suspend fun deleteExpired(now: Long = System.currentTimeMillis())

    @Query("DELETE FROM coach_memories WHERE key = :key")
    suspend fun deleteByKey(key: String)
}

@Dao
interface CoachToolCallDao {
    @Query("SELECT * FROM coach_tool_calls WHERE conversationId = :convId ORDER BY createdAt ASC")
    suspend fun forConversation(convId: String): List<CoachToolCallEntity>

    @Insert
    suspend fun insert(call: CoachToolCallEntity)
}

@Dao
interface UserProfileDao {
    @Query("SELECT * FROM user_profiles LIMIT 1")
    suspend fun get(): UserProfileEntity?

    @Upsert
    suspend fun upsert(profile: UserProfileEntity)
}

@Dao
interface UserGoalDao {
    @Query("SELECT * FROM user_goals LIMIT 1")
    suspend fun get(): UserGoalEntity?

    @Query("SELECT * FROM user_goals LIMIT 1")
    fun getFlow(): kotlinx.coroutines.flow.Flow<UserGoalEntity?>

    @Upsert
    suspend fun upsert(goal: UserGoalEntity)
}

@Dao
interface WearableLogDao {
    @Query("SELECT * FROM wearable_logs ORDER BY timestamp DESC LIMIT :limit")
    suspend fun recent(limit: Int = 500): List<WearableLogEntity>

    @Insert
    suspend fun insert(log: WearableLogEntity)

    @Query("DELETE FROM wearable_logs WHERE timestamp < :before")
    suspend fun deleteOlderThan(before: Long)
}

@Dao
interface RawPacketDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(packet: RawPacketEntity)

    @Query("SELECT * FROM raw_packets ORDER BY timestamp DESC LIMIT :limit")
    suspend fun recent(limit: Int = 500): List<RawPacketEntity>
}

@Dao
interface CoachSummaryDao {
    @Query("SELECT * FROM coach_summaries WHERE kind = :kind AND scopeKey = :scopeKey LIMIT 1")
    suspend fun get(kind: String, scopeKey: String): CoachSummaryEntity?

    @Query("SELECT * FROM coach_summaries WHERE kind = :kind ORDER BY updatedAt DESC LIMIT 1")
    suspend fun latest(kind: String): CoachSummaryEntity?

    @Query("SELECT * FROM coach_summaries WHERE kind = :kind AND scopeKey = :scopeKey LIMIT 1")
    fun getFlow(kind: String, scopeKey: String): Flow<CoachSummaryEntity?>

    @Upsert
    suspend fun upsert(summary: CoachSummaryEntity)

    @Query("DELETE FROM coach_summaries WHERE kind = :kind AND scopeKey = :scopeKey")
    suspend fun delete(kind: String, scopeKey: String)
}
