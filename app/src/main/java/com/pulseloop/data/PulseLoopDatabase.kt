package com.pulseloop.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.pulseloop.data.dao.*
import com.pulseloop.data.entity.*

/**
 * Ported from the SwiftData ModelContainer in PulseLoop.
 * Room database holding all PulseLoop entities.
 */
@Database(
    entities = [
        DeviceEntity::class,
        MeasurementEntity::class,
        ActivityDailyEntity::class,
        ActivityBucketEntity::class,
        DeviceMeasurementConfigEntity::class,
        ActivitySessionEntity::class,
        ActivityGpsPointEntity::class,
        ActivityEventEntity::class,
        ActivitySampleEntity::class,
        ActivitySensorPollEntity::class,
        SleepSessionEntity::class,
        SleepStageBlockEntity::class,
        CoachConversationEntity::class,
        CoachMessageEntity::class,
        CoachMemoryEntity::class,
        CoachToolCallEntity::class,
        UserProfileEntity::class,
        UserGoalEntity::class,
        RawPacketEntity::class,
        DerivedUpdateEntity::class,
        CoachSummaryEntity::class,
        WearableLogEntity::class,
        BatterySampleEntity::class,
        CoachNotificationRecordEntity::class,
    ],
    version = 14,
    exportSchema = false,
)
abstract class PulseLoopDatabase : RoomDatabase() {
    abstract fun deviceDao(): DeviceDao
    abstract fun measurementDao(): MeasurementDao
    abstract fun activityDailyDao(): ActivityDailyDao
    abstract fun activityBucketDao(): ActivityBucketDao
    abstract fun deviceMeasurementConfigDao(): DeviceMeasurementConfigDao
    abstract fun activitySessionDao(): ActivitySessionDao
    abstract fun activityGpsPointDao(): ActivityGpsPointDao
    abstract fun sleepSessionDao(): SleepSessionDao
    abstract fun sleepStageBlockDao(): SleepStageBlockDao
    abstract fun coachConversationDao(): CoachConversationDao
    abstract fun coachMessageDao(): CoachMessageDao
    abstract fun coachMemoryDao(): CoachMemoryDao
    abstract fun coachToolCallDao(): CoachToolCallDao
    abstract fun userProfileDao(): UserProfileDao
    abstract fun userGoalDao(): UserGoalDao
    abstract fun coachSummaryDao(): CoachSummaryDao
    abstract fun wearableLogDao(): WearableLogDao
    abstract fun rawPacketDao(): RawPacketDao
    abstract fun batterySampleDao(): BatterySampleDao
    abstract fun coachNotificationRecordDao(): CoachNotificationRecordDao

    companion object {
        @Volatile private var INSTANCE: PulseLoopDatabase? = null

        /** v2 → v3: adds the activity_buckets table (idempotent re-sync of activity history). */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `activity_buckets` (
                        `startEpoch` INTEGER NOT NULL,
                        `date` INTEGER NOT NULL,
                        `steps` INTEGER NOT NULL,
                        `distanceMeters` REAL NOT NULL,
                        `source` TEXT NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`startEpoch`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_activity_buckets_date` ON `activity_buckets` (`date`)")
            }
        }

        /** v3 → v4: per-device measurement config (iOS #19) + coach message attachments (iOS #31). */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `device_measurement_configs` (
                        `deviceId` TEXT NOT NULL,
                        `hrIntervalMinutes` INTEGER NOT NULL,
                        `hrEnabled` INTEGER NOT NULL,
                        `spo2Enabled` INTEGER NOT NULL,
                        `stressEnabled` INTEGER NOT NULL,
                        `hrvEnabled` INTEGER NOT NULL,
                        `temperatureEnabled` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`deviceId`)
                    )
                    """.trimIndent()
                )
                db.execSQL("ALTER TABLE `coach_messages` ADD COLUMN `attachmentsJson` TEXT")
            }
        }

        /** v4 → v5: distance + calorie goal columns on user_goals (iOS #48 GoalDraft). */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `user_goals` ADD COLUMN `distanceMeters` REAL NOT NULL DEFAULT 8000.0")
                db.execSQL("ALTER TABLE `user_goals` ADD COLUMN `calories` INTEGER NOT NULL DEFAULT 500")
            }
        }

        /** v5 → v6: exact wearable model id on devices (iOS #49 exact-model identification). */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `devices` ADD COLUMN `wearableModelID` TEXT")
            }
        }

        /** v6 → v7: sleep session provenance, so demo seeding/clearing can't touch ring sessions. */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `sleep_sessions` ADD COLUMN `sourceRaw` TEXT NOT NULL DEFAULT 'ring'")
            }
        }

        /** v7 → v8: battery-level history for the Wearable-settings drainage chart (iOS #61b). */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `battery_samples` (
                        `id` TEXT NOT NULL,
                        `percent` INTEGER NOT NULL,
                        `timestamp` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_battery_samples_timestamp` ON `battery_samples` (`timestamp`)")
            }
        }

        /** v8 → v9: a device-level "sync actually completed" stamp, separate from [DeviceEntity.lastSyncAt]
         *  (re-stamped on every bare CONNECT) — the coach-notification freshness gate (iOS #61c). */
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `devices` ADD COLUMN `lastFullSyncAt` INTEGER")
            }
        }

        /** v9 → v10: token/cost usage accounting on coach conversations + messages
         *  (iOS #65b). Running totals on the conversation; per-turn tokens/cost/
         *  model/provider on the message. */
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `coach_conversations` ADD COLUMN `totalInputTokens` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `coach_conversations` ADD COLUMN `totalOutputTokens` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `coach_conversations` ADD COLUMN `totalCostUSD` REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE `coach_messages` ADD COLUMN `inputTokens` INTEGER")
                db.execSQL("ALTER TABLE `coach_messages` ADD COLUMN `outputTokens` INTEGER")
                db.execSQL("ALTER TABLE `coach_messages` ADD COLUMN `costUSD` REAL")
                db.execSQL("ALTER TABLE `coach_messages` ADD COLUMN `modelUsed` TEXT")
                db.execSQL("ALTER TABLE `coach_messages` ADD COLUMN `providerUsed` TEXT")
            }
        }

        /** v10 → v11: tool-call trace metadata (iOS #65c). Friendly label,
         *  success/error status, and turn-order sequence per tool call, plus an
         *  index on messageId for the trace-disclosure UI query. */
        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `coach_tool_calls` ADD COLUMN `label` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `coach_tool_calls` ADD COLUMN `statusRaw` TEXT NOT NULL DEFAULT 'success'")
                db.execSQL("ALTER TABLE `coach_tool_calls` ADD COLUMN `sequence` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_coach_tool_calls_messageId` ON `coach_tool_calls` (`messageId`)")
            }
        }

        /** v11 → v12: persisted delivered check-ins, so the notification generator can
         *  avoid repeating its own recent phrasing/openings (iOS #65 anti-repeat hint). */
        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `coach_notification_records` (
                        `id` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `body` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_coach_notification_records_createdAt` ON `coach_notification_records` (`createdAt`)")
            }
        }

        /** v12 → v13: index replayed sensor-history identity without deleting valid collisions. */
        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Feature APKs briefly used version 8 for this index before main assigned v8 to
                // battery history. Keep the migration valid for both upgrade lineages.
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `battery_samples` (
                        `id` TEXT NOT NULL,
                        `percent` INTEGER NOT NULL,
                        `timestamp` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_battery_samples_timestamp` ON `battery_samples` (`timestamp`)")
                adoptStableMeasurementIdentities(db)
            }
        }

        /** v13 → v14: replace the pre-review unique identity index without dropping rows. */
        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                adoptStableMeasurementIdentities(db)
            }
        }

        private fun adoptStableMeasurementIdentities(db: SupportSQLiteDatabase) {
            db.execSQL("DROP INDEX IF EXISTS `index_measurements_kindRaw_timestamp_sourceRaw`")
            db.execSQL(
                "UPDATE `measurements` SET `sourceRaw` = 'live' " +
                    "WHERE `sourceRaw` = 'colmi' AND `kindRaw` IN ('HRV', 'TEMPERATURE')"
            )
            listOf(
                "HEART_RATE" to "hr",
                "SPO2" to "spo2",
                "STRESS" to "stress",
                "FATIGUE" to "fatigue",
                "HRV" to "hrv",
                "TEMPERATURE" to "temp",
                "BLOOD_PRESSURE_SYSTOLIC" to "bp_sys",
                "BLOOD_PRESSURE_DIASTOLIC" to "bp_dia",
                "BLOOD_SUGAR" to "glucose",
                "RESPIRATORY_RATE" to "resp_rate",
                "VO2MAX" to "vo2max",
            ).forEach { (kind, key) ->
                adoptStableMeasurementIdentity(db, kind = kind, source = "history", key = key)
            }
            adoptStableMeasurementIdentity(db, kind = "STRESS", source = "colmi", key = "stress")
            adoptStableMeasurementIdentity(db, kind = "TEMPERATURE", source = "live", key = "temp")
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_measurements_kindRaw_timestamp_sourceRaw` " +
                    "ON `measurements` (`kindRaw`, `timestamp`, `sourceRaw`)"
            )
        }

        private fun adoptStableMeasurementIdentity(
            db: SupportSQLiteDatabase,
            kind: String,
            source: String,
            key: String,
        ) {
            db.execSQL(
                """
                UPDATE `measurements`
                SET `id` = 'history:$key:' || `timestamp`
                WHERE `kindRaw` = '$kind' AND `sourceRaw` = '$source'
                  AND `rowid` IN (
                      SELECT MIN(`rowid`) FROM `measurements`
                      WHERE `kindRaw` = '$kind' AND `sourceRaw` = '$source'
                      GROUP BY `timestamp`
                  )
                  AND NOT EXISTS (
                      SELECT 1 FROM `measurements` AS `existing`
                      WHERE `existing`.`id` = 'history:$key:' || `measurements`.`timestamp`
                  )
                """.trimIndent()
            )
        }

        fun getInstance(context: Context): PulseLoopDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    PulseLoopDatabase::class.java,
                    "pulseloop.db"
                )
                    .addMigrations(
                        MIGRATION_2_3,
                        MIGRATION_3_4,
                        MIGRATION_4_5,
                        MIGRATION_5_6,
                        MIGRATION_6_7,
                        MIGRATION_7_8,
                        MIGRATION_8_9,
                        MIGRATION_9_10,
                        MIGRATION_10_11,
                        MIGRATION_11_12,
                        MIGRATION_12_13,
                        MIGRATION_13_14,
                    )
                    // Downgrades only (sideloading an older APK). A blanket destructive
                    // fallback would silently wipe every measurement, sleep session, and
                    // coach conversation on any future version bump that misses a
                    // Migration — that must fail loudly in development instead.
                    .fallbackToDestructiveMigrationOnDowngrade()
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
