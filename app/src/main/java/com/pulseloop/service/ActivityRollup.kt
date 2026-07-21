package com.pulseloop.service

import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.data.entity.ActivityDailyEntity
import com.pulseloop.data.entity.ActivitySessionEntity
import com.pulseloop.util.TimeUtil

/**
 * Ported from ActivityService.creditDailyRollup/reverseDailyRollup in PulseServices.swift
 * (iOS #57a). Credits a finished workout's minutes (and, for GPS workouts, distance) to its
 * day's [ActivityDailyEntity] row exactly once, at finish — a summary refresh must never
 * double-count. Reversed on delete so the day's totals stay honest.
 */
object ActivityRollup {
    private fun minutesFor(session: ActivitySessionEntity): Int {
        val ended = session.endedAt ?: return 0
        return maxOf(0, (((ended - session.startedAt) / 1000.0) - session.totalPauseSeconds).toInt()) / 60
    }

    suspend fun credit(db: PulseLoopDatabase, session: ActivitySessionEntity) {
        val minutes = minutesFor(session)
        if (minutes <= 0) return
        val dayStart = TimeUtil.startOfDayLocal(session.startedAt)
        val existing = db.activityDailyDao().byDay(dayStart)
        val row = existing ?: ActivityDailyEntity(date = dayStart, source = "manual_recording")
        db.activityDailyDao().upsert(row.copy(
            activeMinutes = row.activeMinutes + minutes,
            distanceMeters = if (session.useGps) row.distanceMeters + (session.distanceMeters ?: 0.0) else row.distanceMeters,
            source = if (row.source == "manual_recording") "manual_recording" else "hr_and_manual",
            updatedAt = System.currentTimeMillis(),
        ))
    }

    suspend fun reverse(db: PulseLoopDatabase, session: ActivitySessionEntity) {
        val minutes = minutesFor(session)
        if (minutes <= 0) return
        val dayStart = TimeUtil.startOfDayLocal(session.startedAt)
        val row = db.activityDailyDao().byDay(dayStart) ?: return
        db.activityDailyDao().upsert(row.copy(
            activeMinutes = maxOf(0, row.activeMinutes - minutes),
            distanceMeters = if (session.useGps)
                maxOf(0.0, row.distanceMeters - (session.distanceMeters ?: 0.0))
            else row.distanceMeters,
            updatedAt = System.currentTimeMillis(),
        ))
    }
}
