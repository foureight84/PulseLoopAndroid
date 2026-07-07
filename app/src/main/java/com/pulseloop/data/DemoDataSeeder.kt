package com.pulseloop.data

import androidx.room.withTransaction
import com.pulseloop.data.entity.*
import com.pulseloop.ring.MeasurementKind
import com.pulseloop.ring.RingConnectionState
import com.pulseloop.ring.RingDeviceType
import com.pulseloop.ring.SleepStage
import com.pulseloop.ring.WearableCapability
import com.pulseloop.ring.WearableCapability.Companion.toCsv
import com.pulseloop.service.SleepScore
import com.pulseloop.util.TimeUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.ZonedDateTime
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Ported 1:1 from SeedData.swift (`seedVitals` + activity/sleep seeding) so the Android debug
 * build and the iOS Simulator display the SAME demo dataset — same values at the same clock
 * times, so charts render identically on both platforms.
 *
 * Fully deterministic (no RNG): every series is a sinusoid + fixed extreme days that walk each
 * metric through its threshold zones. Measurements use [MeasurementKind].name for kindRaw — the
 * same keys the readers (ViewModels, coach tools) query. Sleep sessions are keyed by waking day
 * like the sync engine but under their own "demo-sleep-$dayStart" ids + sourceRaw "demo", and
 * days that already hold a synced ring session are skipped — so neither seeding nor clearing can
 * ever touch a real night.
 *
 * Like iOS, the demo device is seeded as CONNECTED at 82% battery (data-display capabilities
 * only — no manual/realtime/find-device caps — and no BLE identifiers, so the sync coordinator
 * ignores it). Ring event handlers write through DeviceDao.currentReal(), and current() ranks a
 * real ring above this row, so the demo device never absorbs a real ring's identity.
 */
object DemoDataSeeder {
    private const val DEMO_DEVICE_ID = "demo-device"

    /** Swift `Double.rounded()`: nearest integer, ties away from zero. */
    private fun swiftRounded(x: Double): Double = if (x >= 0) floor(x + 0.5) else ceil(x - 0.5)

    suspend fun seed(db: PulseLoopDatabase) = withContext(Dispatchers.IO) {
        // Everything is seeded relative to "now" — iOS uses Date()/Calendar the same way. Slow
        // vitals land at fixed clock hours of each calendar day (including later *today*, which
        // iOS also seeds; readers treat those as the latest reading, matching the Simulator).
        val now = ZonedDateTime.now()

        db.withTransaction {
            // Replace previous demo rows (real ring data is untouched).
            db.measurementDao().clearDemo()
            db.activityDailyDao().clearDemo()
            db.sleepStageBlockDao().clearDemo()
            db.sleepSessionDao().clearDemo()

            // Demo device — connected at 82% like the iOS demo seed, so the header pill and
            // capability-gated vitals cards match the Simulator.
            db.deviceDao().upsert(DeviceEntity(
                id = DEMO_DEVICE_ID,
                name = "Demo Ring",
                batteryPercent = 82,
                stateRaw = RingConnectionState.CONNECTED.name,
                deviceTypeRaw = RingDeviceType.COLMI_R02.name,
                capabilitiesRaw = setOf(
                    WearableCapability.HEART_RATE, WearableCapability.SPO2,
                    WearableCapability.STEPS, WearableCapability.SLEEP,
                    WearableCapability.BATTERY, WearableCapability.BLOOD_PRESSURE,
                    WearableCapability.BLOOD_SUGAR, WearableCapability.REM_SLEEP,
                    WearableCapability.STRESS, WearableCapability.FATIGUE,
                    WearableCapability.HRV, WearableCapability.TEMPERATURE,
                ).toCsv(),
            ))

            // User profile — same physiology as the iOS demo (age shifts reference ranges).
            db.userProfileDao().upsert(UserProfileEntity(
                name = "Demo User", age = 25, sex = "not set",
                heightCm = 178.0, weightKg = 73.0,
                onboardingCompleted = true,
            ))

            // Goals
            db.userGoalDao().upsert(UserGoalEntity(steps = 10000, sleepMinutes = 480))

            // ~90 days of daily activity (iOS SeedData): weekly rhythm + slow upward trend +
            // deterministic wobble.
            for (offset in -89..0) {
                val date = now.plusDays(offset.toLong())
                val weekend = date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY
                val base = 7600.0 + (offset + 89) * 9
                val wobble = sin(offset * 0.9) * 1400 + ((abs(offset) * 37) % 900)
                val steps = max(2600, (base + wobble - (if (weekend) 1500 else 0)).toInt())
                db.activityDailyDao().upsert(ActivityDailyEntity(
                    date = TimeUtil.startOfDayLocal(date.toInstant().toEpochMilli()),
                    steps = steps,
                    calories = steps * 0.045 + 120,
                    distanceMeters = steps * 0.72,
                    activeMinutes = max(8, steps / 230),
                    source = "demo",
                ))
            }

            seedVitals(db, now)

            // ~30 nights of sleep with the iOS stage pattern + per-night scores.
            for (i in 0..29) {
                seedSleepNight(db, now, dayOffset = i)
            }

            // Coach conversation
            db.coachConversationDao().upsert(CoachConversationEntity(
                title = "Welcome to PulseLoop",
            ))
        }
    }

    /**
     * Removes exactly what [seed] wrote — demo-tagged measurement/activity/sleep rows and the
     * demo device row — leaving real ring data untouched.
     */
    suspend fun clear(db: PulseLoopDatabase) = withContext(Dispatchers.IO) {
        db.withTransaction {
            db.measurementDao().clearDemo()
            db.activityDailyDao().clearDemo()
            db.deviceDao().deleteById(DEMO_DEVICE_ID)
            db.sleepStageBlockDao().clearDemo()
            db.sleepSessionDao().clearDemo()
        }
    }

    /**
     * Every vital's measurement history, byte-for-byte the iOS `seedVitals` formulas. Each series
     * deliberately walks its threshold zones — including over-threshold extremes — so the
     * zone-colored charts show their full color range. HR/SpO₂ get a dense last-24h pass
     * (the dashboard's window) on top of the ~30-day history.
     */
    private suspend fun seedVitals(db: PulseLoopDatabase, now: ZonedDateTime) {
        suspend fun add(kind: MeasurementKind, value: Double, ts: ZonedDateTime) =
            insertDemo(db, kind, value, ts.toInstant().toEpochMilli())

        // Heart rate — every 2h across the past ~30 days. Circadian shape: low overnight
        // (~55–62), higher through the day (~68–95), evening exercise spike every third day.
        for (hour in -720..-26 step 2) {
            val ts = now.plusHours(hour.toLong())
            val hourOfDay = ts.hour
            val dayIndex = abs(hour) / 24
            val circadian = if (hourOfDay < 6 || hourOfDay > 22)
                56.0 + (hourOfDay % 4)                                 // overnight resting
            else
                70.0 + sin((hourOfDay - 6) / 16.0 * PI) * 16           // daytime arc peaking mid-afternoon
            var hr = circadian + sin(hour * 0.35) * 5 + (dayIndex % 3)
            if (dayIndex % 3 == 0 && hourOfDay == 18) hr = 142.0 + (dayIndex % 4) * 4  // workout evenings
            add(MeasurementKind.HEART_RATE, swiftRounded(hr), ts)
        }
        // Dense last 24h (hourly) — mostly 58–95, with spikes into the red (≥150).
        for (hour in -24..0) {
            val ts = now.plusHours(hour.toLong())
            var hr = 62 + (sin(hour * 0.7) + 1) * 15 + abs(hour % 5)
            if (hour == -8) hr = 152.0            // afternoon spike → High (red)
            if (hour == -2) hr = 138.0            // recent effort → Elevated
            add(MeasurementKind.HEART_RATE, swiftRounded(hr), ts)
        }

        // SpO₂ — every 6h across the past ~30 days, mostly 95–99 with a periodic overnight dip.
        for (hour in -720..-30 step 6) {
            val ts = now.plusHours(hour.toLong())
            val dayIndex = abs(hour) / 24
            var spo2 = 97.0 + (if ((abs(hour) / 6) % 3 == 0) 1 else 0) - (dayIndex % 2)
            if (dayIndex % 9 == 4 && ts.hour < 6) spo2 = 92.0        // overnight dip
            add(MeasurementKind.SPO2, min(100.0, spo2), ts)
        }
        // Dense last 24h, every 2h. Mostly 96–99 with a dip to Low (91) and one Very low (88).
        for (hour in -24..0 step 2) {
            val ts = now.plusHours(hour.toLong())
            var spo2 = 97.0 + (if (abs(hour) % 3 == 0) 2 else 0)
            if (hour == -10) spo2 = 91.0          // Low
            if (hour == -16) spo2 = 88.0          // Very low
            add(MeasurementKind.SPO2, min(100.0, spo2), ts)
        }

        // Slow vitals over ~30 days (a few readings/day where useful). Each walks its zones.
        for (day in -29..0) {
            val dayStart = now.plusDays(day.toLong())
            fun at(h: Int, m: Int = 0): ZonedDateTime =
                dayStart.withHour(h).withMinute(m).withSecond(0).withNano(0)
            val phase = day.toDouble()

            // Stress — 3 readings/day. Calm→High; a hard day pushes into the red (≥76).
            for ((h, base) in listOf(9 to 22.0, 14 to 48.0, 19 to 66.0)) {
                var v = base + sin(phase * 0.5) * 12
                if (day == -4 && h == 14) v = 84.0          // High (red)
                add(MeasurementKind.STRESS, max(3.0, min(99.0, swiftRounded(v))), at(h))
            }

            // Fatigue — 1 reading/day (evening). Fresh→High fatigue (≥75 red on a couple of days).
            var fatigue = 40 + sin(phase * 0.4) * 22
            if (day == -3 || day == -12) fatigue = 80.0     // High fatigue (red)
            add(MeasurementKind.FATIGUE, max(5.0, min(98.0, swiftRounded(fatigue))), at(21))

            // HRV — 1 reading/day (overnight). ~55±18 so the 30-day baseline (mean±sd) forms and
            // some days fall below/above it (amber/green bands).
            var hrv = 55 + sin(phase * 0.6) * 16 + abs(day % 3) * 3
            if (day == -6) hrv = 26.0                       // sharp dip → below baseline
            if (day == -18) hrv = 92.0                      // spike → above baseline
            add(MeasurementKind.HRV, swiftRounded(hrv), at(4))

            // Blood pressure — 1 pair/day (morning). Normal→Stage 2; a couple of high days.
            var sys = 116 + sin(phase * 0.45) * 10
            var dia = 76 + sin(phase * 0.45) * 6
            if (day == -2 || day == -15) { sys = 146.0; dia = 96.0 }   // Stage 2 (red)
            if (day == -9) { sys = 132.0; dia = 86.0 }                 // Stage 1
            add(MeasurementKind.BLOOD_PRESSURE_SYSTOLIC, swiftRounded(sys), at(8))
            add(MeasurementKind.BLOOD_PRESSURE_DIASTOLIC, swiftRounded(dia), at(8))

            // Glucose — 1 fasting reading/day (morning). Normal→High (≥126 red on a couple of days).
            var glucose = 92 + sin(phase * 0.5) * 12
            if (day == -5) glucose = 138.0                  // High (red)
            if (day == -20) glucose = 112.0                 // Elevated (amber)
            add(MeasurementKind.BLOOD_SUGAR, swiftRounded(glucose), at(7, 30))

            // Temperature — skin temp, 1 reading/day. Typical 33–35.5 with a warm spike (≥36
            // amber) and a cool dip (<31 blue).
            var temp = 34.0 + sin(phase * 0.5) * 1.2
            if (day == -7) temp = 37.1                      // Warm
            if (day == -22) temp = 30.4                     // Cool
            add(MeasurementKind.TEMPERATURE, swiftRounded(temp * 10) / 10, at(3))
        }
    }

    private suspend fun insertDemo(db: PulseLoopDatabase, kind: MeasurementKind, value: Double, ts: Long) {
        db.measurementDao().insert(MeasurementEntity(
            kindRaw = kind.name, value = value, unit = kind.unit,
            timestamp = ts, sourceRaw = "demo",
        ))
    }

    /**
     * One night ending at 7:10 on the morning `dayOffset` days ago — iOS SeedData's duration
     * formula and reference stage pattern (sums to 455m, scaled to the night's total). Keyed by
     * waking day like the sync engine but under a demo-owned id ("demo-sleep-$dayStart",
     * sourceRaw "demo"); a day that already has a synced ring session is left alone rather
     * than overwritten with fabricated data.
     */
    private suspend fun seedSleepNight(db: PulseLoopDatabase, now: ZonedDateTime, dayOffset: Int) {
        val wakeDayStart = TimeUtil.startOfDayLocal(now.minusDays(dayOffset.toLong()).toInstant().toEpochMilli())
        if (db.sleepSessionDao().ringByDay(wakeDayStart) != null) return
        val totalMinutes = 360 + ((sin(dayOffset * 0.8) + 1) * 70).toInt() + (if (dayOffset % 3 == 0) -25 else 15)
        val wake = wakeDayStart + (7 * 60 + 10) * 60_000L      // 7:10 AM
        val sleepStart = wake - totalMinutes * 60_000L

        // Reference hypnogram scaled to the requested total; REM cycles get longer later in the
        // night, matching real sleep architecture (iOS `stageBlocks`).
        val pattern = listOf(
            SleepStage.LIGHT to 58, SleepStage.DEEP to 46, SleepStage.LIGHT to 70,
            SleepStage.REM to 22, SleepStage.AWAKE to 12, SleepStage.DEEP to 71,
            SleepStage.LIGHT to 88, SleepStage.REM to 38, SleepStage.AWAKE to 10,
            SleepStage.LIGHT to 40,
        )
        val referenceTotal = pattern.sumOf { it.second }
        val scale = totalMinutes.toDouble() / referenceTotal

        val sessionId = "demo-sleep-$wakeDayStart"
        var minute = 0
        val blocks = pattern.mapIndexed { index, (stage, refMinutes) ->
            val duration = if (index == pattern.lastIndex)
                max(1, totalMinutes - minute)   // absorb rounding into the last block
            else
                max(1, swiftRounded(refMinutes * scale).toInt())
            val block = SleepStageBlockEntity(
                sessionId = sessionId,
                startAt = sleepStart + minute * 60_000L,
                startMinute = minute,
                durationMinutes = duration,
                stageRaw = stage.name,
            )
            minute += duration
            block
        }

        val session = SleepSessionEntity(
            id = sessionId, date = wakeDayStart,
            startAt = sleepStart, endAt = wake,
            totalMinutes = totalMinutes,
            sourceRaw = "demo",
        )
        db.sleepStageBlockDao().deleteBySession(sessionId)
        db.sleepSessionDao().upsert(session.copy(score = SleepScore.calculate(session, blocks).score))
        blocks.forEach { db.sleepStageBlockDao().insert(it) }
    }
}
