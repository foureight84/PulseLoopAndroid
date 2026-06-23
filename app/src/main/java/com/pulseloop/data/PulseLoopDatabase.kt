package com.pulseloop.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
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
    version = 2,
    exportSchema = false,
)
abstract class PulseLoopDatabase : RoomDatabase() {
    abstract fun deviceDao(): DeviceDao
    abstract fun measurementDao(): MeasurementDao
    abstract fun activityDailyDao(): ActivityDailyDao
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

        fun getInstance(context: Context): PulseLoopDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    PulseLoopDatabase::class.java,
                    "pulseloop.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
