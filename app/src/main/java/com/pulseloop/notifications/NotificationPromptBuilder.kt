package com.pulseloop.notifications

/**
 * Ported from NotificationPromptBuilder.swift.
 * Prompts for the daily check-in generator. Tuned for short, engaging, unique
 * push notifications.
 */
object NotificationPromptBuilder {

    fun systemPrompt(slot: CoachNotificationSlot): String {
        val slotGuidance = when (slot) {
            CoachNotificationSlot.MORNING ->
                "This is the MORNING check-in: lead with how last night's sleep went and set up the day — a light, motivating plan or one small nudge (move, hydrate, a step target)."
            CoachNotificationSlot.EVENING ->
                "This is the EVENING check-in: recap the day's activity (steps, active minutes, workouts) and ease toward wind-down — a calm nudge about recovery or tomorrow."
        }
        return buildString {
            appendLine("You write a single push notification for PulseLoop, a smart-ring health app. It is a short, friendly daily check-in grounded in the user's own ring data.")
            appendLine()
            appendLine(slotGuidance)
            appendLine()
            appendLine("Rules:")
            appendLine("- Output ONLY JSON {\"title\",\"body\"}. Title ≤ ~6 words; body 1–2 short sentences.")
            appendLine("- Be specific to today's actual numbers (steps, sleep, heart rate, SpO2, workouts) — never generic filler. Mention a real number when you have one.")
            appendLine("- Surface ONE clear insight or nudge, not a list.")
            appendLine("- Be warm and engaging, like a thoughtful coach. At most one emoji, and only if it fits.")
            appendLine("- Ground every claim in the provided data. If data is thin, keep it light and honest; never invent numbers.")
            appendLine("- No medical diagnosis or alarming language. Wellness tone only.")
            appendLine("- When an `environment` block (city + weather) is present, actively consider it when shaping the check-in (outdoor vs indoor, timing around rain, hydration). If conditions are extreme (very hot, very cold, storms, heavy rain), call it out with one practical adjustment — hydrate more, layer up, or move indoors.")
        }
    }

    fun developerMessage(packet: NotificationContextPacket): String {
        val json = packet.toJsonString()
        return buildString {
            appendLine("Context (last ~12 hours):")
            appendLine(json)
            appendLine()
            appendLine("Write a fresh ${packet.slot} check-in now as {\"title\",\"body\"}.")
        }
    }

    /**
     * The strict JSON schema for the notification response.
     */
    fun textFormat(): Map<String, Any> = mapOf(
        "type" to "json_schema",
        "name" to "coach_notification",
        "schema" to mapOf(
            "type" to "object",
            "properties" to mapOf(
                "title" to mapOf("type" to "string", "maxLength" to 50),
                "body" to mapOf("type" to "string", "maxLength" to 160),
            ),
            "required" to listOf("title", "body"),
            "additionalProperties" to false,
        ),
        "strict" to true,
    )
}
