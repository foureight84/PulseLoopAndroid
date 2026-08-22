package com.pulseloop.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.room.withTransaction
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
        MealEntryEntity::class,
        CachedFoodProductEntity::class,
    ],
    version = 22,
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
    // iOS #96: Nutrition
    abstract fun mealEntryDao(): MealEntryDao
    abstract fun foodProductDao(): FoodProductDao

    /**
     * Empty every table, atomically. Goes through Room's [withTransaction] rather than a raw
     * `openHelper.writableDatabase.beginTransaction()`: only the Room path runs
     * `RoomDatabase.endTransaction()`, which is what kicks the invalidation tracker — without it
     * every observing Flow keeps serving the rows we just deleted until something else writes.
     *
     * Not [clearAllTables]: that also drops the sqlite_sequence rows and checkpoints the WAL, and
     * it throws if called while a transaction is open — which is exactly how `DataArchiveService`
     * uses this list during a restore.
     */
    suspend fun nukeAllTables() = withTransaction {
        for (table in ALL_TABLES) {
            openHelper.writableDatabase.execSQL("DELETE FROM $table")
        }
    }

    companion object {
        /**
         * Every table in the schema, in parent-before-child order so the CASCADE foreign keys
         * (sleep_stage_blocks → sleep_sessions, coach_messages → coach_conversations) resolve on
         * their own. Shared by [nukeAllTables] and `DataArchiveService`'s restore so the two can
         * never drift — they were separate literals before, and the restore list was already two
         * tables short.
         */
        val ALL_TABLES = listOf(
            "devices", "measurements", "activity_daily", "activity_buckets",
            "battery_samples", "device_measurement_configs", "activity_sessions",
            "activity_gps_points", "activity_events", "activity_samples",
            "activity_sensor_polls", "sleep_sessions", "sleep_stage_blocks",
            "coach_conversations", "coach_messages", "coach_memories",
            "coach_tool_calls", "user_profiles", "user_goals",
            "raw_packets", "derived_updates", "coach_summaries",
            "wearable_logs", "coach_notification_records",
            "meal_entries", "food_products",
        )

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

        /** v14 → v15: re-run identity adoption now that it also covers HRV rows stored as 'live'.
         *  Same reasoning as v13 → v14: a test APK that already reached v14 ran the pre-fix version
         *  of [adoptStableMeasurementIdentities] and would otherwise keep its un-keyed HRV rows,
         *  which double the HRV series on the next re-sync. The function is idempotent, so re-running
         *  it is free for anyone whose rows are already adopted. */
        private val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                adoptStableMeasurementIdentities(db)
            }
        }

        /**
         * v15 → v16: delete the duplicate rows the pre-identity code accumulated.
         *
         * Before measurements had stable ids, a ring's history *replay* was persisted with a fresh
         * random id every time, so each re-sync appended another row at a slot already stored — on
         * a real Colmi that meant HRV, stress and temperature growing by one row per slot per sync,
         * forever (nothing prunes this table). [adoptStableMeasurementIdentities] stops the growth
         * by giving one row per slot the canonical `history:<key>:<timestamp>` id that later syncs
         * upsert onto, but it deliberately leaves the already-accumulated copies in place. They are
         * not harmless: `dailyAggregates`/`hourlyAggregates` compute `AVG(value)` over raw rows with
         * no `sourceRaw` filter, so a slot replayed more often than its neighbours drags the average
         * toward its value.
         *
         * Deleting is restricted to rows that are **provably redundant**: a non-canonical row is
         * removed only when a canonical row exists for the same `(kindRaw, timestamp)` *and* holds
         * the same `value`. A row whose value differs is a distinct reading and is always kept, so
         * this can never destroy information — every deleted row's (kind, timestamp, value) is still
         * represented by the canonical row that survives.
         *
         * **Interruption safety.** This is deliberately one statement. SQLite applies a single
         * `DELETE` atomically via its journal, so a process kill mid-migration can only leave the
         * table fully cleaned or wholly untouched — never half-deleted. Room additionally runs
         * migrations inside the transaction `SQLiteOpenHelper` opens around `onUpgrade`, so the
         * schema-version bump and this delete commit together: an interrupted upgrade rolls back to
         * v15 and simply re-runs on the next launch. The statement is also idempotent — once the
         * redundant rows are gone it matches nothing — so re-running after a rollback is a no-op.
         */
        private val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    DELETE FROM `measurements`
                    WHERE `id` NOT LIKE 'history:%'
                      AND EXISTS (
                          SELECT 1 FROM `measurements` AS `canonical`
                          WHERE `canonical`.`id` LIKE 'history:%'
                            AND `canonical`.`kindRaw` = `measurements`.`kindRaw`
                            AND `canonical`.`timestamp` = `measurements`.`timestamp`
                            AND `canonical`.`value` = `measurements`.`value`
                      )
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `activity_daily` ADD COLUMN `estimatedActiveCalories` REAL")
            }
        }

        private val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `user_profiles` ADD COLUMN `hrZoneModeRaw` TEXT NOT NULL DEFAULT 'auto'")
                db.execSQL("ALTER TABLE `user_profiles` ADD COLUMN `hrRestingBaseline` REAL")
                db.execSQL("ALTER TABLE `user_profiles` ADD COLUMN `hrRestingBaselineUpdatedAt` INTEGER")
                db.execSQL("ALTER TABLE `user_profiles` ADD COLUMN `hrCustomLowUpper` REAL")
                db.execSQL("ALTER TABLE `user_profiles` ADD COLUMN `hrCustomAthleticUpper` REAL")
                db.execSQL("ALTER TABLE `user_profiles` ADD COLUMN `hrCustomElevatedStart` REAL")
                db.execSQL("ALTER TABLE `user_profiles` ADD COLUMN `hrCustomHighStart` REAL")
            }
        }

        private val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `activity_sessions` ADD COLUMN `stravaActivityId` INTEGER")
            }
        }

        private val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `user_goals` ADD COLUMN `intakeCalories` REAL")
                db.execSQL("ALTER TABLE `user_goals` ADD COLUMN `intakeProteinG` REAL")
                db.execSQL("ALTER TABLE `user_goals` ADD COLUMN `intakeCarbsG` REAL")
                db.execSQL("ALTER TABLE `user_goals` ADD COLUMN `intakeFatG` REAL")
                db.execSQL("ALTER TABLE `user_goals` ADD COLUMN `nutritionEnabled` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `meal_entries` (
                        `id` TEXT NOT NULL, `date` INTEGER NOT NULL, `timestamp` INTEGER NOT NULL,
                        `name` TEXT NOT NULL, `mealTypeRaw` TEXT NOT NULL DEFAULT 'snack',
                        `calories` REAL NOT NULL, `proteinG` REAL NOT NULL DEFAULT 0,
                        `carbsG` REAL NOT NULL DEFAULT 0, `fatG` REAL NOT NULL DEFAULT 0,
                        `fiberG` REAL, `sugarG` REAL, `sodiumMg` REAL,
                        `sourceRaw` TEXT NOT NULL DEFAULT 'manual', `offProductCode` TEXT,
                        `servingDescription` TEXT, `servingGrams` REAL, `quantity` REAL NOT NULL DEFAULT 1,
                        `confidenceRaw` TEXT NOT NULL DEFAULT 'medium', `userEdited` INTEGER NOT NULL DEFAULT 0,
                        `notes` TEXT, `loggedByCoach` INTEGER NOT NULL DEFAULT 0,
                        `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`)
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_meal_entries_date` ON `meal_entries` (`date`)")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `food_products` (
                        `code` TEXT NOT NULL, `name` TEXT NOT NULL, `brand` TEXT,
                        `energyKcal100g` REAL NOT NULL, `protein100g` REAL NOT NULL DEFAULT 0,
                        `carbs100g` REAL NOT NULL DEFAULT 0, `fat100g` REAL NOT NULL DEFAULT 0,
                        `fiber100g` REAL, `sugars100g` REAL, `saturatedFat100g` REAL,
                        `sodiumMg100g` REAL, `servingSizeText` TEXT, `servingQuantityG` REAL,
                        `lastUsedAt` INTEGER NOT NULL, `useCount` INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(`code`)
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_food_products_lastUsedAt` ON `food_products` (`lastUsedAt`)")
            }
        }

        /**
         * v20 -> v21: `meal_entries.updatedAt` (Phase 6). The Health Connect nutrition export
         * watermarks on and versions by [com.pulseloop.data.entity.MealEntryEntity.updatedAt] so
         * a future in-place meal edit re-exports under the same `pl-meal-<id>` clientRecordId.
         * Rows are insert-once today, so backfilling `updatedAt = createdAt` is exactly
         * lossless — every existing row's two stamps are already equal. Added with a temporary
         * `DEFAULT 0` (SQLite requires one for a NOT NULL ADD COLUMN) then backfilled in a
         * single statement; both run inside Room's onUpgrade transaction, so an interrupted
         * upgrade rolls back to v20 and re-runs.
         */
        private val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `meal_entries` ADD COLUMN `updatedAt` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE `meal_entries` SET `updatedAt` = `createdAt`")
            }
        }

        /**
         * v21 -> v22: per-day/slot dedupe on delivered check-ins (iOS #94
         * CoachNotificationDataTrigger). The data trigger re-runs the due slot when a
         * full sync completes, so both the periodic worker and the trigger need a shared
         * "did this slot already fire today?" lookup — dateKey (local epoch day) + slotRaw
         * (lowercase slot name) is that key. Both columns are NOT NULL with defaults so
         * pre-#94 rows (and archive restores, which don't carry the fields) get 0/"" and
         * can never match a real dedupe query. The composite index backs the EXISTS check.
         */
        private val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `coach_notification_records` ADD COLUMN `dateKey` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `coach_notification_records` ADD COLUMN `slotRaw` TEXT NOT NULL DEFAULT ''")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_coach_notification_records_dateKey_slotRaw` ON `coach_notification_records` (`dateKey`, `slotRaw`)")
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
            // HRV needs the same 'live' pass as TEMPERATURE above, for the same reason: Colmi's
            // HRV *history* used to persist as an `HrvSample` — random id, sourceRaw 'live' — and
            // now decodes to a `HistoryMeasurement`, which keys on `history:hrv:<timestamp>`.
            // Without re-keying the old rows they don't collide with the new ones, so a re-sync
            // writes a second row at every timestamp already stored, and `range()` (which filters
            // on kindRaw + timestamp, never sourceRaw) returns both — doubling the HRV series.
            adoptStableMeasurementIdentity(db, kind = "HRV", source = "live", key = "hrv")
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
                        MIGRATION_14_15,
                        MIGRATION_15_16,
                        MIGRATION_16_17,
                        MIGRATION_17_18,
                        MIGRATION_18_19,
                        MIGRATION_19_20,
                        MIGRATION_20_21,
                        MIGRATION_21_22,
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
