package com.pulseloop.service

import com.pulseloop.R
import com.pulseloop.util.L10n
import com.pulseloop.data.DemoDataPolicy
import com.pulseloop.data.entity.SleepSessionEntity
import com.pulseloop.data.entity.SleepStageBlockEntity
import com.pulseloop.ring.SleepStage
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.*

/**
 * Ported from SleepInsights.swift.
 * Range-aware sleep scoring + interpretation. Pure logic — zero Android deps.
 * All logic is data-honest: missing nights never treated as zero, averages
 * over valid nights only, no fallback to stale prior nights.
 */

// ── Sleep Score ───────────────────────────────────────────────────────────

enum class SleepQualityLabel { EXCELLENT, GOOD, FAIR, NEEDS_WORK }

data class SleepScoreResult(
    val score: Int,
    val label: SleepQualityLabel,
    val deepPct: Int,
    val lightPct: Int,
    val awakePct: Int?,  // null when no usable awake signal
)

object SleepScore {
    private fun clamp(value: Double, lo: Double, hi: Double): Double =
        min(hi, max(lo, value))

    private fun bandScore(
        value: Double, idealLow: Double, idealHigh: Double,
        softLow: Double, softHigh: Double, hardLow: Double, hardHigh: Double,
        points: Double,
    ): Double {
        if (!value.isFinite()) return 0.0
        if (value in idealLow..idealHigh) return points
        if (value < idealLow && value >= softLow)
            return points * (0.65 + 0.35 * ((value - softLow) / (idealLow - softLow)))
        if (value > idealHigh && value <= softHigh)
            return points * (0.65 + 0.35 * ((softHigh - value) / (softHigh - idealHigh)))
        if (value < softLow)
            return points * 0.65 * clamp((value - hardLow) / (softLow - hardLow), 0.0, 1.0)
        return points * 0.65 * clamp((hardHigh - value) / (hardHigh - softHigh), 0.0, 1.0)
    }

    private fun awakeScore(awakePct: Double?, points: Double): Double {
        if (awakePct == null || !awakePct.isFinite()) return points * 0.55
        if (awakePct <= 10) return points
        if (awakePct <= 20) return points * (1 - 0.65 * ((awakePct - 10) / 10))
        return points * 0.35 * clamp((35 - awakePct) / 15, 0.0, 1.0)
    }

    fun qualityLabel(score: Int): SleepQualityLabel = when {
        score >= 85 -> SleepQualityLabel.EXCELLENT
        score >= 70 -> SleepQualityLabel.GOOD
        score >= 55 -> SleepQualityLabel.FAIR
        else -> SleepQualityLabel.NEEDS_WORK
    }

    /** Calculate sleep score from session + stage blocks. */
    fun calculate(
        session: SleepSessionEntity,
        blocks: List<SleepStageBlockEntity>,
    ): SleepScoreResult {
        // Two denominators, because since issue #63 a session carries two numbers. `total` is
        // time asleep: stage shares and the duration band are judged against it, as sleep-stage
        // percentages conventionally are (of total sleep time, not time in bed) and as the headline
        // the user sees. `span` is first block to last: the awake share and the "does this ring
        // label awake at all" heuristic are judged against it, since both are about time in bed —
        // and judged against asleep time the coverage test below would be true for every ring,
        // handing a ring that never labels awake the full awake sub-score for nothing.
        //
        // Minutes the wearer added over time the ring missed (issue #106) are asleep but carry no
        // stage, so the score judges the night the ring measured: they leave `total` (they would
        // dilute every stage share) and the coverage test, and stay in `span`, which held them as a
        // between-record gap before the edit and still does.
        val added = addedMinutes(blocks)
        val total = if (session.totalMinutes > 0) (session.totalMinutes - added).coerceAtLeast(0).toDouble() else 0.0
        val span = session.spanMinutes.toDouble().takeIf { it > 0 } ?: total
        // stageRaw is persisted as the SleepStage enum name (uppercase) — match it exactly.
        // Some rings report REM in big-data sleep; the score model has no REM band, so fold
        // REM into deep (both are restorative sleep the deep band rewards).
        fun minutesFor(vararg stages: SleepStage) =
            blocks.filter { b -> stages.any { it.name == b.stageRaw } }.sumOf { it.durationMinutes }.toDouble()
        val deep = minutesFor(SleepStage.DEEP, SleepStage.REM)
        val light = minutesFor(SleepStage.LIGHT)
        val awake = minutesFor(SleepStage.AWAKE)
        val coveredStageMin = blocks.filterNot { it.stageRaw == SleepStage.ADDED.name }
            .sumOf { it.durationMinutes.toDouble() }
        val hasAwakeSignal = blocks.any { it.stageRaw == SleepStage.AWAKE.name } ||
            awake > 0 || (span > 0 && coveredStageMin >= span * 0.95)

        val totalHours = total / 60
        val deepPct = if (total > 0) (deep / total) * 100 else 0.0
        val lightPct = if (total > 0) (light / total) * 100 else 0.0
        val awakePct: Double? = if (span > 0 && hasAwakeSignal) (awake / span) * 100 else null

        val duration = bandScore(totalHours, 7.5, 8.5, 6.0, 9.5, 3.0, 12.0, 35.0)
        val deepScore = bandScore(deepPct, 13.0, 23.0, 5.0, 35.0, 0.0, 45.0, 30.0)
        val lightScore = bandScore(lightPct, 50.0, 60.0, 35.0, 75.0, 20.0, 90.0, 20.0)
        val awakeSub = awakeScore(awakePct, 15.0)
        val score = clamp((duration + deepScore + lightScore + awakeSub).roundToInt().toDouble(), 0.0, 100.0).toInt()

        return SleepScoreResult(
            score = score,
            label = qualityLabel(score),
            deepPct = deepPct.roundToInt(),
            lightPct = lightPct.roundToInt(),
            awakePct = awakePct?.roundToInt(),
        )
    }
}

// ── Formatting ────────────────────────────────────────────────────────────

/**
 * How long the wearer was **asleep** across these stage blocks — every stage the ring recorded,
 * less the ones it marked awake (issue #63).
 *
 * This is a session's `totalMinutes`, and it is deliberately not the span from its start to its
 * end. A night the ring splits into two records is stored as one row covering both, so the span
 * also counts the minutes between them: on the reporter's night the app said 8 h 10 (23:51 to
 * 08:02) for a night whose two records declared 268 and 140 minutes, 6 h 48. Neither figure is
 * wrong, but only one of them is a duration — the other is a range, and the sleep card already
 * shows the range on its own line underneath. See [spanMinutes] for that one.
 *
 * The vendor app draws the same distinction, and the reporter found where (`SleepActivity:695`,
 * `com.yucheng.smarthealthpro`): each history entry is built as
 * `deepSleepTotal + lightSleepTotal + remTotal`, with `wakeDuration` carried separately and
 * excluded, while `startTime` comes from the first record of the day and `endTime` from the last.
 * It never conflates the two either.
 *
 * Implemented as "every stage except AWAKE" rather than "DEEP + LIGHT + REM" on purpose. The two
 * are the same sum on any ring that labels its stages — YCBT's sleep tag 4 *is* AWAKE — but
 * `SleepStage.UNKNOWN` is the `else` branch of every decoder in this app, an unrecognised stage
 * byte *inside* a sleep record. Those minutes were slept; naming three stages explicitly would
 * silently drop them, and on a ring whose stage codes we don't fully decode it could zero out a
 * whole night.
 */
fun asleepMinutes(blocks: List<SleepStageBlockEntity>): Int =
    blocks.filter { it.stageRaw != SleepStage.AWAKE.name }
        .sumOf { it.durationMinutes }
        .coerceAtLeast(0)

/**
 * Minutes the wearer added to a record over time the ring missed (issue #106) — asleep, unstaged.
 * They are inside [asleepMinutes] (the AWAKE-exclusion rule already admits them) and outside
 * everything that judges stages: subtract them wherever a stage share or score is computed.
 */
fun addedMinutes(blocks: List<SleepStageBlockEntity>): Int =
    blocks.filter { it.stageRaw == SleepStage.ADDED.name }.sumOf { it.durationMinutes }

/**
 * The asleep minutes the ring itself measured: [asleepMinutes] less [addedMinutes]. The
 * denominator for [sleepStageScore], so a night the wearer extended scores exactly as the ring's
 * own record would have (issue #106).
 */
fun measuredAsleepMinutes(blocks: List<SleepStageBlockEntity>): Int =
    (asleepMinutes(blocks) - addedMinutes(blocks)).coerceAtLeast(0)

/**
 * Awake minutes for a session, including the short gaps between its ring records (issue #81).
 *
 * The ring reports a brief night-waking two ways: as an explicit `AWAKE` stage block, and — when it
 * catches the waking by closing the record and opening a new one rather than staging it — as the
 * gap between two records of the same session. The second form is real not-asleep minutes the
 * RECORDS card already displays as "Awake Xm between", so the Awake figure counts it too; otherwise
 * the same night can read 4m awake on one card and 0m on the other (five wakings, four caught by
 * reopening, one staged).
 *
 * Every gap counts, however short: see [betweenRecordAwakeMinutes].
 */
fun awakeMinutes(blocks: List<SleepStageBlockEntity>): Int {
    val staged = blocks
        .filter { it.stageRaw == SleepStage.AWAKE.name }
        .sumOf { it.durationMinutes }
    val runs = sleepRecordRuns(blocks)
    val between = (0 until runs.lastIndex).sumOf { i ->
        betweenRecordAwakeMinutes(runs[i], runs[i + 1])
    }
    return staged + between
}

/**
 * The waking between two consecutive ring records of one session, in minutes. The one rule the
 * Awake figure ([awakeMinutes]), the RECORDS card's "Awake Xm between" line and the hypnogram
 * ([hypnogramBlocks]) read, so they cannot disagree about the same gap (issue #81).
 *
 * **Any gap is a waking, and counts at least one minute (issue #111).** This used to drop gaps
 * under two minutes as the "one-minute reopen seam" of issue #63. The reporter then checked all 19
 * between-record gaps on their ring since late August: the shortest were 52 s, 79 s and 88 s, the
 * 52 s and 88 s ones were get-ups they had written down at the time, and none was a reopen without
 * waking. The ring opens a new record on a whole minute, so how short a gap looks depends on where
 * in the minute the last one ended; no threshold separates a seam from a short get-up.
 *
 * Rounded, not truncated: a record's stored end is its start plus whole minutes, up to 30 s either
 * side of the ring's own end, so a 2m07 waking can be stored as 1m46. Back-to-back records (gap 0,
 * as a Jring's 15-minute packets are) stay 0.
 *
 * No upper cap is needed: a session only holds a gap of [SleepSegmentation.SESSION_GAP_MINUTES] or
 * more when the wearer's own Bedtime window spans it (issue #105), and then it is a waking too.
 */
fun betweenRecordAwakeMinutes(earlier: SleepRecordRun, later: SleepRecordRun): Int {
    val gapMs = later.startAt - earlier.endAt
    if (gapMs <= 0L) return 0
    return Math.floorDiv(gapMs + 29_999L, 60_000L).toInt().coerceAtLeast(1)
}

/**
 * The blocks to draw on the hypnogram: the session's own, plus an AWAKE block over each gap between
 * records that [betweenRecordAwakeMinutes] counts as a waking (issue #111). Without it the chart drew
 * two records back to back while the Awake card and the RECORDS card both counted the minutes
 * between them. The added block covers the hole the chart would otherwise leave; it is for display
 * only and must not be fed back into [awakeMinutes], which counts these gaps itself.
 */
fun hypnogramBlocks(blocks: List<SleepStageBlockEntity>): List<SleepStageBlockEntity> {
    val runs = sleepRecordRuns(blocks)
    if (runs.size < 2) return blocks
    val gaps = (0 until runs.lastIndex).mapNotNull { i ->
        val earlier = runs[i]
        val later = runs[i + 1]
        if (betweenRecordAwakeMinutes(earlier, later) == 0) return@mapNotNull null
        val fromMinute = earlier.blocks.maxOf { it.startMinute + it.durationMinutes }
        val untilMinute = later.blocks.minOf { it.startMinute }
        if (untilMinute <= fromMinute) return@mapNotNull null
        SleepStageBlockEntity(
            id = "gap-${earlier.blocks.first().sessionId}-$fromMinute",
            sessionId = earlier.blocks.first().sessionId,
            startAt = earlier.endAt,
            startMinute = fromMinute,
            durationMinutes = untilMinute - fromMinute,
            stageRaw = SleepStage.AWAKE.name,
        )
    }
    return blocks + gaps
}

/**
 * The banded stage score stored on a session row — deep-percentage bands, nothing else. The one
 * source of truth for it: `EventPersistenceSubscriber` stamps it on every reconcile, and
 * `SleepRecordDeletion` restates with the same function so a post-deletion row agrees with what
 * the next sync would write (issue #78). [totalMin] is the ring-measured asleep time
 * ([measuredAsleepMinutes]): minutes the wearer added are unstaged and must not dilute it (#106).
 */
fun sleepStageScore(deepMin: Int, totalMin: Int): Int? {
    if (totalMin == 0) return null
    val deepPct = (deepMin.toFloat() / totalMin * 100).toInt()
    return when {
        deepPct >= 20 -> 90
        deepPct >= 15 -> 75
        deepPct >= 10 -> 60
        else -> 40
    }
}

/**
 * How long the session covers on the clock, first stage block to last — which since issue #63 is a
 * different number from [SleepSessionEntity.totalMinutes], the time actually asleep.
 *
 * Use this wherever wall-clock position matters (a hypnogram's x axis, a timeline); use
 * `totalMinutes` wherever a duration is being reported. The sleep card shows both, as the vendor
 * app does: the duration as the headline, the span as the range underneath it.
 */
val SleepSessionEntity.spanMinutes: Int
    get() = ((endAt - startAt) / 60_000L).toInt().coerceAtLeast(0)

/**
 * One ring sleep record inside a stored session (issue #68).
 *
 * A ring closes a session when the wearer gets up and opens a new one when they settle, so one
 * night can arrive as two or three records minutes apart. The app merges those into a single stored
 * session — that is the night, and the merge is what #63 fixed — but the individual records are real
 * information the merge then hides, and the vendor app shows them. This is that, recovered from
 * stored data rather than re-parsed: since #63 each record's blocks are placed against its own
 * declared bounds, so a record boundary is a stretch of the session's timeline that no block claims.
 */
data class SleepRecordRun(
    val startAt: Long,
    val endAt: Long,
    val blocks: List<SleepStageBlockEntity>,
) {
    val recordStartAt: Long get() = blocks.firstOrNull { it.recordStartAt > 0L }?.recordStartAt ?: startAt
    val asleepMinutes: Int get() = asleepMinutes(blocks)
    /** Minutes the wearer added over time the ring missed (issue #106); part of [asleepMinutes]. */
    val addedMinutes: Int get() = addedMinutes(blocks)
    private val measured get() = blocks.filterNot { it.stageRaw == SleepStage.ADDED.name }
    /** Where the ring's own recording starts and ends inside this run, without the added minutes —
     *  the bounds an extension is measured from (issue #106). The run's own when nothing is measured. */
    val measuredStartAt: Long get() = measured.minOfOrNull { it.startAt } ?: startAt
    val measuredEndAt: Long get() = measured.maxOfOrNull { it.startAt + it.durationMinutes * 60_000L } ?: endAt
    val spanMinutes: Int get() = ((endAt - startAt) / 60_000L).toInt().coerceAtLeast(0)
}

/**
 * Split [blocks] into the ring records they came from.
 *
 * **The boundary is read, not inferred.** Each block carries the declared start of the record it
 * arrived in ([SleepStageBlockEntity.recordStartAt], stamped by `buildStageBlocks`), so records are
 * grouped by that. The gap between two records is *not* a usable substitute: this ring closes one
 * record and opens the next a single minute later (05:57 → 05:58 on the reporter's Sept 9), which
 * is exactly the width of the seam left by rounding each block onto the minute grid *within* one
 * record. No threshold separates those two cases — 2 minutes merged a genuinely split night, and
 * 1 minute would grow a spurious second record on every unsplit one. The same one-minute boundary
 * broke the #63 merge itself, so it is a real property of this firmware rather than one night.
 *
 * [minGapMinutes] is the fallback for rows written before that column existed, where the gap is
 * all there is. Those nights keep the old behaviour rather than being claimed as unsplit.
 *
 * Returns one run for an unsplit night, which is the common case and the reason a caller can show
 * this unconditionally.
 */
fun sleepRecordRuns(
    blocks: List<SleepStageBlockEntity>,
    minGapMinutes: Int = 2,
): List<SleepRecordRun> {
    if (blocks.isEmpty()) return emptyList()
    val ordered = blocks.sortedBy { it.startAt }

    fun endOf(b: SleepStageBlockEntity) = b.startAt + b.durationMinutes * 60_000L
    fun runOf(group: List<SleepStageBlockEntity>) =
        SleepRecordRun(group.first().startAt, group.maxOf { endOf(it) }, group)

    // Every block knows its record: group by it and we are done. All-or-nothing, because a night
    // that is part migrated and part not has no consistent boundary to read — one stamped record
    // plus a legacy block would look like a two-record night whatever the truth.
    if (ordered.all { it.recordStartAt > 0L }) {
        return ordered.groupBy { it.recordStartAt }
            .entries.sortedBy { it.key }
            .map { (_, group) -> runOf(group.sortedBy { it.startAt }) }
    }

    val runs = mutableListOf<SleepRecordRun>()
    var current = mutableListOf(ordered.first())
    for (block in ordered.drop(1)) {
        val gapMs = block.startAt - current.maxOf { endOf(it) }
        if (gapMs >= minGapMinutes * 60_000L) {
            runs += runOf(current.toList())
            current = mutableListOf(block)
        } else {
            current += block
        }
    }
    runs += runOf(current.toList())
    return runs
}

object SleepFormat {
    fun duration(minutes: Int?): String {
        if (minutes == null || minutes < 0) return "—"
        val h = minutes / 60; val m = minutes % 60
        return if (h <= 0) L10n.get(R.string.duration_m, m) else L10n.get(R.string.duration_hm, h, m)
    }

    fun clockTime(ts: Long): String {
        val t = Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault())
        return t.format(com.pulseloop.util.LocaleFormats.time())
    }
}

// ── Coach Interpretation ──────────────────────────────────────────────────

data class SleepCoach(
    val headline: String,
    val body: String,
    val chips: List<String>,
)

data class SleepNoDataState(
    val label: String,
    val value: String,
    val support: String,
)

data class SleepBar(
    val label: String,
    val durationMin: Int?,
    val score: Int?,
    val present: Boolean,
)

object SleepInsights {
    val rangeHeroLabel get() = mapOf(
        SleepRangeKey.DAY to L10n.get(R.string.sleep_last),
        SleepRangeKey.WEEK to L10n.get(R.string.sleep_weekly),
        SleepRangeKey.MONTH to L10n.get(R.string.sleep_monthly),
        SleepRangeKey.YEAR to L10n.get(R.string.sleep_yearly),
    )

    private const val minAggNights = 2

    // ── Aggregation ──────────────────────────────────────────────────────

    fun validSessions(sessions: List<SleepSessionEntity>) =
        sessions.filter { it.totalMinutes > 0 }

    /** One waking day's sleep collapsed to a single combined session + its concatenated blocks. */
    data class DaySleep(val session: SleepSessionEntity, val blocks: List<SleepStageBlockEntity>)

    /**
     * Collapse each waking day's sessions (main night + daytime naps) into ONE combined [DaySleep]
     * so a night plus naps counts as a single tracked day for aggregate math (averages, "N of M
     * nights", histograms). Ported from iOS `SleepInsights.collapseByDay` (PR #83).
     *
     * Deterministic. Days with exactly one session pass through unchanged. For multi-session days,
     * blocks are concatenated (sorted by startAt), totalMinutes are SUMMED (total time asleep across
     * the day, not the wall-clock span), bounds are the earliest start / latest end, and the score
     * is a duration-weighted average of the present per-session scores. The combined row carries a
     * synthetic `collapsed-<day>` id; callers resolve its blocks from the returned [DaySleep.blocks].
     * Result is sorted by day.
     */
    fun collapseByDay(
        sessions: List<SleepSessionEntity>,
        blocksBySession: (String) -> List<SleepStageBlockEntity>,
    ): List<DaySleep> =
        sessions.groupBy { it.date }.map { (day, all) ->
            // A ring night and a seeded demo night can share a date now that a connect deletes
            // nothing (`DemoDataPolicy`) — the seeder's "skip days that already have ring data"
            // guard only covers seed-after-sync, not sync-after-seed. Summing the two would
            // report ~13h asleep and inflate every average built on top. Real wins per day.
            val ring = all.filterNot { DemoDataPolicy.isDemo(it.sourceRaw) }
            val daySessions = if (ring.isNotEmpty()) ring else all
            if (daySessions.size == 1) {
                val only = daySessions.first()
                return@map DaySleep(only, blocksBySession(only.id))
            }
            val blocks = daySessions.flatMap { blocksBySession(it.id) }.sortedBy { it.startAt }
            val totalMinutes = daySessions.sumOf { it.totalMinutes }
            val startAt = daySessions.minOf { it.startAt }
            val endAt = daySessions.maxOf { it.endAt }

            val scored = daySessions.mapNotNull { s -> s.score?.let { it to maxOf(0, s.totalMinutes) } }
            val totalWeight = scored.sumOf { it.second }
            val combinedScore: Int? = when {
                scored.isEmpty() -> null
                totalWeight > 0 -> (scored.sumOf { it.first.toDouble() * it.second } / totalWeight).roundToInt()
                else -> (scored.sumOf { it.first }.toDouble() / scored.size).roundToInt()
            }

            DaySleep(
                session = SleepSessionEntity(
                    id = "collapsed-$day", date = day, startAt = startAt, endAt = endAt,
                    totalMinutes = totalMinutes, score = combinedScore,
                    sourceRaw = daySessions.first().sourceRaw,
                ),
                blocks = blocks,
            )
        }.sortedBy { it.session.date }

    fun averageDuration(valid: List<SleepSessionEntity>): Int? {
        if (valid.isEmpty()) return null
        return valid.sumOf { it.totalMinutes } / valid.size
    }

    fun averageScore(valid: List<SleepSessionEntity>, blocksBySession: (String) -> List<SleepStageBlockEntity>): Int? {
        if (valid.isEmpty()) return null
        val scores = valid.map { SleepScore.calculate(it, blocksBySession(it.id)).score }
        return scores.average().roundToInt()
    }

    private fun durationConsistency(valid: List<SleepSessionEntity>): Double? {
        if (valid.size < 2) return null
        val avg = averageDuration(valid)?.toDouble() ?: return null
        val variance = valid.sumOf { (it.totalMinutes - avg).pow(2) } / valid.size
        return sqrt(variance)
    }

    // ── Coach Text ───────────────────────────────────────────────────────

    fun dayCoach(
        session: SleepSessionEntity,
        blocks: List<SleepStageBlockEntity>,
        activitySteps: Int?,
    ): SleepCoach {
        val score = SleepScore.calculate(session, blocks)
        val deepPct = score.deepPct
        val awakePct = score.awakePct
        val chips = mutableListOf<String>()
        if (session.totalMinutes in 420..540) chips.add(L10n.get(R.string.sc_chip_good_duration))
        if (deepPct in 13..23) chips.add(L10n.get(R.string.sc_chip_deep_balanced))
        else if (deepPct > 23) chips.add(L10n.get(R.string.sc_chip_deep_strong))
        if (awakePct != null && awakePct <= 10) chips.add(L10n.get(R.string.sc_chip_awake_low))

        if (score.score >= 85) {
            val body = if ((activitySteps ?: 0) > 5000)
                L10n.get(R.string.sc_strong_active)
            else L10n.get(R.string.sc_strong)
            return SleepCoach(L10n.get(R.string.sc_strong_title), body, chips.ifEmpty { listOf(L10n.get(R.string.quality_excellent)) }.take(3))
        }
        if (awakePct != null && awakePct > 15) {
            return SleepCoach(
                L10n.get(R.string.sc_restless_title),
                L10n.get(R.string.sc_restless),
                (listOf(L10n.get(R.string.sc_chip_awake_elevated)) + chips).take(3),
            )
        }
        if (session.totalMinutes < 390) {
            return SleepCoach(
                L10n.get(R.string.sc_short_title),
                L10n.get(R.string.sc_short),
                (listOf(L10n.get(R.string.sc_chip_short)) + chips).take(3),
            )
        }
        return SleepCoach(
            L10n.get(R.string.sc_solid_title),
            L10n.get(R.string.sc_solid),
            chips.ifEmpty { listOf(L10n.get(R.string.quality_good)) }.take(3),
        )
    }

    val dayNoDataCoach = SleepCoach(
        L10n.get(R.string.sc_none_title),
            L10n.get(R.string.sc_none),
        emptyList(),
    )

    fun aggregateCoach(
        range: SleepRangeKey,
        sessions: List<SleepSessionEntity>,
        expectedNights: Int,
        goalMin: Int?,
        blocksBySession: (String) -> List<SleepStageBlockEntity>,
    ): SleepCoach {
        val valid = validSessions(sessions)
        val avgMin = averageDuration(valid)
        if (valid.size < minAggNights) {
            val (titleRes, bodyRes) = when (range) {
                SleepRangeKey.WEEK -> R.string.sc_few_week_title to R.plurals.sc_few_week
                SleepRangeKey.MONTH -> R.string.sc_few_month_title to R.plurals.sc_few_month
                else -> R.string.sc_few_year_title to R.plurals.sc_few_year
            }
            return SleepCoach(
                L10n.get(titleRes),
                L10n.plural(bodyRes, valid.size, valid.size),
                listOf(nightsTrackedChip(valid.size, expectedNights)),
            )
        }

        val chips = mutableListOf(nightsTrackedChip(valid.size, expectedNights))
        avgMin?.let { goalDeltaChip(it, goalMin) }?.let { chips.add(it) }
        durationConsistency(valid)?.let { consistencyChip(it) }?.let { chips.add(it) }

        val avgText = SleepFormat.duration(avgMin)
        val coveragePhrase = L10n.get(R.string.sleep_tracked_of, valid.size, expectedNights)

        return when (range) {
            SleepRangeKey.WEEK -> {
                val incomplete = valid.size < expectedNights
                val missing = expectedNights - valid.size
                val body = if (incomplete)
                    L10n.get(R.string.sc_week_incomplete, avgText, valid.size, L10n.plural(R.plurals.sc_missing_nights, missing, missing))
                else L10n.get(R.string.sc_week_full, avgText)
                SleepCoach(L10n.get(R.string.sc_week_title), body, chips.take(3))
            }
            SleepRangeKey.MONTH -> {
                val sparse = valid.size.toDouble() < expectedNights.toDouble() * 0.5
                val body = if (sparse)
                    L10n.get(R.string.sc_month_sparse, avgText, coveragePhrase)
                else L10n.get(R.string.sc_month, avgText, coveragePhrase)
                SleepCoach(L10n.get(R.string.sc_month_title), body, chips.take(3))
            }
            else -> SleepCoach(
                L10n.get(R.string.sc_year_title),
                L10n.plural(R.plurals.sc_year, valid.size, avgText, valid.size),
                chips.take(3),
            )
        }
    }

    private fun nightsTrackedChip(valid: Int, expected: Int) = L10n.get(R.string.sc_chip_tracked, valid, expected)

    private fun goalDeltaChip(avgMin: Int, goalMin: Int?): String? {
        if (goalMin == null) return null
        val delta = avgMin - goalMin
        if (abs(delta) <= 20) return L10n.get(R.string.sc_chip_on_target)
        return if (delta < 0) L10n.get(R.string.sc_chip_below_goal) else L10n.get(R.string.sc_chip_above_goal)
    }

    private fun consistencyChip(sd: Double): String? = when {
        sd <= 40 -> L10n.get(R.string.sc_chip_consistent)
        sd >= 80 -> L10n.get(R.string.sc_chip_variable)
        else -> null
    }

    // ── No-data states ───────────────────────────────────────────────────

    fun noDataState(range: SleepRangeKey): SleepNoDataState = when (range) {
        SleepRangeKey.DAY -> SleepNoDataState(L10n.get(R.string.sleep_last), L10n.get(R.string.nd_day_title),
            L10n.get(R.string.nd_day))
        SleepRangeKey.WEEK -> SleepNoDataState(L10n.get(R.string.sleep_weekly), L10n.get(R.string.nd_week_title),
            L10n.get(R.string.nd_week))
        SleepRangeKey.MONTH -> SleepNoDataState(L10n.get(R.string.sleep_monthly), L10n.get(R.string.nd_month_title),
            L10n.get(R.string.nd_month))
        SleepRangeKey.YEAR -> SleepNoDataState(L10n.get(R.string.sleep_yearly), L10n.get(R.string.nd_year_title),
            L10n.get(R.string.nd_year))
    }

    // ── Histogram builders ───────────────────────────────────────────────

    fun buildNightAxis(
        start: Long, end: Long,
        sessions: List<SleepSessionEntity>,
        blocksBySession: (String) -> List<SleepStageBlockEntity>,
        range: SleepRangeKey,
    ): List<SleepBar> {
        val byDate = sessions.associateBy {
            Instant.ofEpochMilli(it.date).atZone(ZoneId.systemDefault()).truncatedTo(ChronoUnit.DAYS)
                .toInstant().toEpochMilli()
        }
        val bars = mutableListOf<SleepBar>()
        var cursor = Instant.ofEpochMilli(start).atZone(ZoneId.systemDefault()).truncatedTo(ChronoUnit.DAYS)
        val last = Instant.ofEpochMilli(end).atZone(ZoneId.systemDefault()).truncatedTo(ChronoUnit.DAYS)
        val weekdayFmt = DateTimeFormatter.ofPattern("EEEEE", java.util.Locale.getDefault())

        while (!cursor.isAfter(last)) {
            val cursorMs = cursor.toInstant().toEpochMilli()
            val session = byDate[cursorMs]
            val present = (session?.totalMinutes ?: 0) > 0
            val label = if (range == SleepRangeKey.WEEK) cursor.format(weekdayFmt)
                else cursor.dayOfMonth.toString()
            val score = if (present) session?.let { s -> SleepScore.calculate(s, blocksBySession(s.id)).score } else null
            bars.add(SleepBar(label, session?.totalMinutes, score, present))
            cursor = cursor.plusDays(1)
        }
        return bars
    }

    fun buildMonthBuckets(
        end: Long,
        sessions: List<SleepSessionEntity>,
        blocksBySession: (String) -> List<SleepStageBlockEntity>,
    ): List<SleepBar> {
        val valid = validSessions(sessions)
        val byMonth = valid.groupBy {
            val t = Instant.ofEpochMilli(it.date).atZone(ZoneId.systemDefault())
            "${t.year}-${t.monthValue}"
        }
        val monthFmt = DateTimeFormatter.ofPattern("MMM", java.util.Locale.getDefault())
        val endZ = Instant.ofEpochMilli(end).atZone(ZoneId.systemDefault())
        return (11 downTo 0).map { i ->
            val monthDate = endZ.minusMonths(i.toLong())
            val key = "${monthDate.year}-${monthDate.monthValue}"
            val monthSessions = byMonth[key] ?: emptyList()
            SleepBar(
                label = monthDate.format(monthFmt),
                durationMin = averageDuration(monthSessions),
                score = averageScore(monthSessions, blocksBySession),
                present = monthSessions.isNotEmpty(),
            )
        }
    }
}

// ── Extensions ────────────────────────────────────────────────────────────

private fun Double.roundToInt(): Int = round(this).toInt()
