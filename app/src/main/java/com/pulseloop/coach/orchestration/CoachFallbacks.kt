package com.pulseloop.coach.orchestration

import com.pulseloop.R
import com.pulseloop.util.L10n
import com.pulseloop.coach.context.CoachContextPacket
import com.pulseloop.coach.schema.CoachConfidence
import com.pulseloop.coach.schema.CoachResponse
import com.pulseloop.coach.schema.CoachResponseType

/**
 * Ported from [CoachFallbacks] in CoachFallbacks.swift.
 * Deterministic, grounded responses for when the LLM coach can't run (no key /
 * offline / API error) or when the final output can't be parsed.
 */
object CoachFallbacks {

    /** Used after an API failure or unrepairable output. */
    fun fallback(): CoachResponse = CoachResponse(
        responseType = CoachResponseType.ERROR_RECOVERY,
        title = L10n.get(R.string.fb_trouble_title),
        summary = L10n.get(R.string.fb_trouble),
        dataQualityNote = L10n.get(R.string.fb_no_changes),
        followUpChips = listOf(L10n.get(R.string.coach_suggest_today), L10n.get(R.string.coach_suggest_week), L10n.get(R.string.fb_chip_missing)),
        confidence = CoachConfidence.LOW,
    )

    /** Used when the coach is disabled (offline / no key). Grounded in the context packet. */
    fun scripted(packet: CoachContextPacket): CoachResponse {
        // Build a basic fallback from context packet trends
        val steps7d = packet.trends.steps7d
        val todaySteps = if (steps7d.isNotEmpty()) steps7d.last().toInt() else 0
        val avgSteps = if (steps7d.isNotEmpty()) steps7d.average().toInt() else 0

        val bullets = mutableListOf<String>()
        if (todaySteps > 0) bullets.add(L10n.get(R.string.fb_steps_today, todaySteps))
        if (avgSteps > 0) bullets.add(L10n.plural(R.plurals.fb_steps_avg, avgSteps, avgSteps))

        if (bullets.isEmpty()) {
            return CoachResponse(
                responseType = CoachResponseType.DATA_MISSING,
                title = L10n.get(R.string.fb_no_activity_title),
                summary = L10n.get(R.string.fb_no_activity),
                dataQualityNote = packet.dataQualityWarnings.firstOrNull(),
                followUpChips = listOf(L10n.get(R.string.fb_chip_connected), L10n.get(R.string.fb_chip_missing)),
                confidence = CoachConfidence.LOW,
            )
        }

        return CoachResponse(
            responseType = CoachResponseType.INSIGHT,
            title = L10n.get(R.string.fb_today_title),
                summary = L10n.plural(R.plurals.fb_today, todaySteps, todaySteps),
            bullets = bullets,
            dataQualityNote = packet.dataQualityWarnings.lastOrNull(),
            followUpChips = listOf(L10n.get(R.string.fb_chip_compare), L10n.get(R.string.fb_chip_hr_trend)),
            confidence = CoachConfidence.MEDIUM,
        )
    }

    /** Parser failure — couldn't decode model output. */
    fun parseError(): CoachResponse = CoachResponse(
        responseType = CoachResponseType.ERROR_RECOVERY,
        title = L10n.get(R.string.fb_parse_title),
            summary = L10n.get(R.string.fb_parse),
            followUpChips = listOf(L10n.get(R.string.action_try_again), L10n.get(R.string.fb_chip_day), L10n.get(R.string.fb_chip_new)),
        confidence = CoachConfidence.LOW,
    )
}
