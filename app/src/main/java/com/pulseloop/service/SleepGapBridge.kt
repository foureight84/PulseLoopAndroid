package com.pulseloop.service

import android.content.Context
import com.pulseloop.data.entity.SleepStageBlockEntity
import com.pulseloop.settings.QuietHoursPrefs
import com.pulseloop.settings.ZenWindow
import java.time.ZoneId

/**
 * Whether a gap of [SleepSegmentation.SESSION_GAP_MINUTES] or more between two runs of sleep is
 * still one night (issue #105).
 *
 * The hour-long gap rule keeps a nap apart from the night, but it also split a night whose wearer
 * lay awake for 72 minutes between two ring records, both inside the same Bedtime window. The
 * wearer's own window is better evidence than the gap's length, so a gap that lies wholly inside
 * one is bridged: the two parts stay one session, and the gap reads as awake between records.
 */
fun interface SleepGapBridge {
    fun bridges(gapStart: Long, gapEnd: Long): Boolean

    /**
     * This bridge, plus every gap [blocks] already leave open. A stored session holds an hour-plus
     * gap only because a bridge allowed it when the night was written, so re-segmenting what is
     * left of that session after a delete or edit must keep it whole there.
     */
    fun keepingGapsOf(blocks: List<SleepStageBlockEntity>): SleepGapBridge = SleepGapBridge { gapStart, gapEnd ->
        bridges(gapStart, gapEnd) || blocks.none { b ->
            b.startAt < gapEnd && b.startAt + b.durationMinutes * 60_000L > gapStart
        }
    }

    companion object {
        val NONE = SleepGapBridge { _, _ -> false }

        /** The bridge for the user's current quiet-hours settings. Reads them once. */
        fun fromSettings(context: Context): SleepGapBridge {
            val prefs = QuietHoursPrefs(context)
            return forWindows(
                prefs.enabled,
                QuietHoursPrefs.activeBedtimeWindows(prefs, context),
                prefs.startMinutes,
                prefs.endMinutes,
            )
        }

        /**
         * The same source order as the import gate ([QuietHoursPrefs.resolveKeptMinutes]): the
         * recorded Bedtime/DND windows when that tracking is on and the night has any; otherwise
         * the clock window if the schedule is on; otherwise nothing is bridged. A night that has
         * Bedtime windows is judged by them alone, so a gap after Bedtime ended is not bridged by a
         * wider clock schedule. Bedtime tracking bridges with the schedule off (issue #126), as
         * the import gate trims with it off.
         *
         * [zenWindows] is null when Bedtime tracking is off.
         */
        fun forWindows(
            scheduleEnabled: Boolean,
            zenWindows: List<ZenWindow>?,
            startMinutes: Int,
            endMinutes: Int,
            zone: ZoneId = ZoneId.systemDefault(),
        ): SleepGapBridge {
            if (!scheduleEnabled && zenWindows == null) return NONE
            return SleepGapBridge { gapStart, gapEnd ->
                if (gapEnd <= gapStart) return@SleepGapBridge false
                val night = zenWindows?.let { QuietHoursPrefs.nightWindows(it, gapStart, zone) }.orEmpty()
                if (night.isNotEmpty()) {
                    night.any { w -> w.start <= gapStart && gapEnd <= QuietHoursPrefs.effectiveEnd(w) }
                } else {
                    scheduleEnabled && clockCovers(gapStart, gapEnd, startMinutes, endMinutes, zone)
                }
            }
        }

        /**
         * Every minute of the gap inside the daily clock window. A gap of a day or more never is, and
         * neither is anything under a window whose start equals its end: for the import gate that
         * means "gate off", not "asleep all day", and bridging under it would merge an afternoon nap
         * into the night (issue #116 review).
         */
        private fun clockCovers(gapStart: Long, gapEnd: Long, start: Int, end: Int, zone: ZoneId): Boolean {
            if (start == end || (start == 0 && end >= 24 * 60)) return false
            if (gapEnd - gapStart >= 24 * 3_600_000L) return false
            var t = gapStart
            while (t < gapEnd) {
                if (!QuietHoursPrefs.covers(start, end, QuietHoursPrefs.minuteOfDay(t, zone))) return false
                t += 60_000L
            }
            return QuietHoursPrefs.covers(start, end, QuietHoursPrefs.minuteOfDay(gapEnd - 1, zone))
        }
    }
}
