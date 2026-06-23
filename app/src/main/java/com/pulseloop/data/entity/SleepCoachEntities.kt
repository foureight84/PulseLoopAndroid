package com.pulseloop.data.entity

import androidx.room.*

/**
 * Ported from [SleepSession] in PulseModels.swift.
 */
@Entity(tableName = "sleep_sessions", indices = [Index("date")])
data class SleepSessionEntity(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val date: Long,          // epoch millis, start of day
    val startAt: Long,       // epoch millis
    val endAt: Long,         // epoch millis
    val totalMinutes: Int,
    val score: Int? = null,
    val syncedAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

/**
 * Ported from [SleepStageBlock] in PulseModels.swift.
 */
@Entity(
    tableName = "sleep_stage_blocks",
    indices = [Index("sessionId")],
    foreignKeys = [ForeignKey(
        entity = SleepSessionEntity::class,
        parentColumns = ["id"], childColumns = ["sessionId"],
        onDelete = ForeignKey.CASCADE
    )],
)
data class SleepStageBlockEntity(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val sessionId: String,
    val startAt: Long,       // epoch millis
    val startMinute: Int,
    val durationMinutes: Int,
    val stageRaw: String,    // SleepStage name
)

/**
 * Ported from [CoachConversation] in PulseModels.swift.
 */
@Entity(tableName = "coach_conversations")
data class CoachConversationEntity(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val title: String = "Today check-in",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

/**
 * Ported from [CoachMessage] in PulseModels.swift.
 */
@Entity(
    tableName = "coach_messages",
    indices = [Index("conversationId")],
    foreignKeys = [ForeignKey(
        entity = CoachConversationEntity::class,
        parentColumns = ["id"], childColumns = ["conversationId"],
        onDelete = ForeignKey.CASCADE
    )],
)
data class CoachMessageEntity(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val conversationId: String,
    val role: String,            // "user" | "assistant" | "system"
    val body: String,
    val cardsJSON: String? = null,
    val pendingActionJSON: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * Ported from [CoachMemory] in PulseModels.swift.
 */
@Entity(tableName = "coach_memories")
data class CoachMemoryEntity(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val key: String,
    val value: String,
    val memoryType: String = "note",
    val importance: Int = 3,
    val expiresAt: Long? = null,
    val sourceMessageId: String? = null,
    val isUserEditable: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

/**
 * Ported from [CoachToolCall] in PulseModels.swift.
 */
@Entity(tableName = "coach_tool_calls", indices = [Index("conversationId")])
data class CoachToolCallEntity(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val conversationId: String,
    val messageId: String? = null,
    val toolName: String,
    val inputJSON: String? = null,
    val outputJSON: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * Ported from [RawPacketRow] in PulseModels.swift.
 * Debug-only; production never persists protocol hex/opcodes.
 */
@Entity(tableName = "raw_packets")
data class RawPacketEntity(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val directionRaw: String,
    val commandId: Int,
    val hexPayload: String,
    val decodedKind: String? = null,
    val decodedJSON: String? = null,
    val confidenceRaw: String = "unknown",
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * Ported from [ActivityEvent] in PulseModels.swift.
 */
@Entity(tableName = "activity_events", indices = [Index("sessionId")])
data class ActivityEventEntity(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val sessionId: String,
    val kind: String,
    val timestamp: Long,
    val payloadJSON: String? = null,
)

/**
 * Ported from [ActivitySample] in PulseModels.swift.
 */
@Entity(tableName = "activity_samples", indices = [Index("sessionId"), Index("timestamp")])
data class ActivitySampleEntity(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val sessionId: String,
    val measurementId: String? = null,
    val kind: String,
    val value: Double,
    val unit: String,
    val timestamp: Long,
    val source: String = "mock",
    val confidenceRaw: String = "known",
)

/**
 * Ported from [ActivitySensorPollEvent] in PulseModels.swift.
 */
@Entity(tableName = "activity_sensor_polls", indices = [Index("sessionId")])
data class ActivitySensorPollEntity(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val sessionId: String,
    val timestamp: Long,
    val kind: String,    // "hr" | "spo2"
    val status: String,  // "started" | "success" | "failed" | "skipped"
    val value: Double? = null,
    val errorMessage: String? = null,
)

/**
 * Ported from [DerivedUpdateRow] in PulseModels.swift.
 */
@Entity(tableName = "derived_updates")
data class DerivedUpdateEntity(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val kind: String,
    val entityType: String,
    val entityId: String,
    val payloadJSON: String? = null,
)

/**
 * Ported from [CoachSummary] in CoachSummary.swift.
 * A persisted, LLM-generated coach card shown on Today/Sleep.
 */
@Entity(tableName = "coach_summaries", indices = [Index("kind"), Index("scopeKey")])
data class CoachSummaryEntity(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val kind: String,               // "today" | "sleep_day" | "sleep_range_day" | ...
    val scopeKey: String,           // "2026-06-21" | "day" | ...
    val title: String,
    val body: String,
    val chipsJSON: String? = null,  // JSON array of follow-up chips
    val conversationId: String? = null,
    val dataSignature: String,      // hash of input data to detect staleness
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)
