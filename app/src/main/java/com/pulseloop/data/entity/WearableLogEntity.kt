package com.pulseloop.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.pulseloop.ring.RingDeviceType

/**
 * Ported from WearableLog in WearableLog.swift.
 * Structured, product-invisible diagnostics for wearable connections and syncs.
 * Higher-level human-readable timeline that ships in the diagnostics export.
 */
@Entity(
    tableName = "wearable_logs",
    indices = [Index("timestamp")],
)
data class WearableLogEntity(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val deviceTypeRaw: String = "",
    val categoryRaw: String,
    val levelRaw: String,
    val message: String,
    val metadataJSON: String? = null,
) {
    val category: WearableLogCategory get() = try { WearableLogCategory.valueOf(categoryRaw) } catch (_: Exception) { WearableLogCategory.CONNECTION }
    val level: WearableLogLevel get() = try { WearableLogLevel.valueOf(levelRaw) } catch (_: Exception) { WearableLogLevel.INFO }
}

enum class WearableLogCategory { CONNECTION, SYNC, ERROR, BATTERY }
enum class WearableLogLevel { INFO, WARN, ERROR }
