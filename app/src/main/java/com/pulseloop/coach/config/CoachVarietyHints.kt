package com.pulseloop.coach.config

/**
 * Ported from CoachVarietyHints.swift (iOS #65).
 * Prompt-only variety for the coach's repetitive surfaces (daily check-ins,
 * Today/Sleep cards). Picks a deterministic "coaching angle" from a seed so the
 * same context yields the same angle within a run (idempotent regeneration),
 * while different seeds rotate through fresh framings — avoiding the "every
 * notification opens the same way" fatigue.
 *
 * The seed hash is a stable FNV-1a over the UTF-8 bytes — NOT Kotlin/JVM's
 * `String.hashCode()`, which isn't guaranteed stable across platforms/versions
 * and would risk a different angle across app builds for the same seed.
 */
object CoachVarietyHints {
    /** ~8 distinct framings. Order is fixed so [angle] is reproducible. */
    val angles: List<String> = listOf(
        "Compare today to yesterday — call out what changed and why it might matter.",
        "Notice a streak or milestone worth celebrating (consecutive active days, a personal best).",
        "Zoom in on a single metric and go one level deeper than usual (e.g. resting HR, deep sleep).",
        "Take a recovery lens — how rested is the body, and what would help it bounce back?",
        "Lead with a genuine question that invites the user to reflect, then ground it in a number.",
        "Lead with one concrete, immediately useful tip tied to today's data.",
        "If weather/location context is available, make the advice weather-aware (outdoor vs indoor, hydration, rain).",
        "Zoom out to the week's trend — is the direction improving, flat, or slipping?",
    )

    /**
     * Deterministic angle for a seed. Stable across launches (FNV-1a), so
     * regenerating the same check-in doesn't reshuffle the framing.
     */
    fun angle(seed: String): String {
        if (angles.isEmpty()) return ""
        val index = (fnv1a(seed) % angles.size.toULong()).toInt()
        return angles[index]
    }

    /** FNV-1a 64-bit over UTF-8 bytes — a small, stable, non-cryptographic hash. */
    fun fnv1a(string: String): ULong {
        var hash = 0xcbf29ce484222325UL
        val prime = 0x100000001b3UL
        for (byte in string.toByteArray(Charsets.UTF_8)) {
            hash = hash xor (byte.toUByte().toULong())
            hash *= prime
        }
        return hash
    }
}
