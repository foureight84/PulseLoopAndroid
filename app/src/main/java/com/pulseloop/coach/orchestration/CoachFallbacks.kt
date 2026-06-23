package com.pulseloop.coach.orchestration

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
        title = "I had trouble with that",
        summary = "I checked your data but couldn't finish preparing the answer. Try asking again, or narrow the question.",
        dataQualityNote = "No changes were made.",
        followUpChips = listOf("How am I doing today?", "Summarize my week", "What data is missing?"),
        confidence = CoachConfidence.LOW,
    )

    /** Used when the coach is disabled (offline / no key). Grounded in the context packet. */
    fun scripted(packet: CoachContextPacket): CoachResponse {
        // Build a basic fallback from context packet trends
        val steps7d = packet.trends.steps7d
        val todaySteps = if (steps7d.isNotEmpty()) steps7d.last().toInt() else 0
        val avgSteps = if (steps7d.isNotEmpty()) steps7d.average().toInt() else 0

        val bullets = mutableListOf<String>()
        if (todaySteps > 0) bullets.add("Steps today: ${todaySteps}")
        if (avgSteps > 0) bullets.add("7-day average: ${avgSteps} steps/day")

        if (bullets.isEmpty()) {
            return CoachResponse(
                responseType = CoachResponseType.DATA_MISSING,
                title = "No activity synced yet",
                summary = "I don't have today's activity from the ring yet. Sync the ring or take a measurement and I'll summarize what comes in. (The AI coach is off — add an OpenAI key in Settings to enable full coaching.)",
                dataQualityNote = packet.dataQualityWarnings.firstOrNull(),
                followUpChips = listOf("Is my ring connected?", "What data is missing?"),
                confidence = CoachConfidence.LOW,
            )
        }

        return CoachResponse(
            responseType = CoachResponseType.INSIGHT,
            title = "Here's where you are today",
            summary = "You're at ${todaySteps} steps so far today. The AI coach is off — add an OpenAI key in Settings for trends and tailored guidance.",
            bullets = bullets,
            dataQualityNote = packet.dataQualityWarnings.lastOrNull(),
            followUpChips = listOf("How does today compare to yesterday?", "What's my heart rate trend?"),
            confidence = CoachConfidence.MEDIUM,
        )
    }

    /** Parser failure — couldn't decode model output. */
    fun parseError(): CoachResponse = CoachResponse(
        responseType = CoachResponseType.ERROR_RECOVERY,
        title = "Parse Error",
        summary = "I had trouble formatting my response. Please try again.",
        followUpChips = listOf("Try again", "Summarize my day", "What's new?"),
        confidence = CoachConfidence.LOW,
    )
}
