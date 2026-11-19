package com.pulseloop.data.dao

import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Deliberately includes real and demo rows: this is an inspection tool, not a health reader. */
data class DebugTableCount(val name: String, val count: Int)
data class DebugDatabaseRow(val tableName: String, val timestamp: Long, val detail: String)

@Dao
interface DebugDao {
    @Query("""
        SELECT 'measurements' AS name, count(*) AS count FROM measurements
        UNION ALL SELECT 'activity_daily', count(*) FROM activity_daily
        UNION ALL SELECT 'sleep_sessions', count(*) FROM sleep_sessions
        UNION ALL SELECT 'devices', count(*) FROM devices
        UNION ALL SELECT 'raw_packets', count(*) FROM raw_packets
        UNION ALL SELECT 'wearable_logs', count(*) FROM wearable_logs
    """)
    fun counts(): Flow<List<DebugTableCount>>

    @Query("""
        SELECT * FROM (SELECT 'measurements' AS tableName, timestamp, kindRaw || ': ' || value || ' ' || unit || ' · ' || sourceRaw AS detail FROM measurements
        UNION ALL SELECT 'activity_daily', updatedAt, steps || ' steps · ' || source FROM activity_daily
        UNION ALL SELECT 'sleep_sessions', startAt, totalMinutes || ' minutes · ' || sourceRaw FROM sleep_sessions
        UNION ALL SELECT 'devices', updatedAt, deviceTypeRaw || ' · ' || stateRaw || ' · ' || COALESCE(firmwareVersion, 'firmware unknown') FROM devices
        ) WHERE tableName = :table
        ORDER BY timestamp DESC LIMIT 40
    """)
    fun recentRows(table: String): Flow<List<DebugDatabaseRow>>
}
