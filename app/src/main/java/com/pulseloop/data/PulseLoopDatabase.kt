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
    ],
    version = 5,
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

        fun getInstance(context: Context): PulseLoopDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    PulseLoopDatabase::class.java,
                    "pulseloop.db"
                )
                    .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
