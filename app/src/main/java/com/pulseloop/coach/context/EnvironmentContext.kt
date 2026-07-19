package com.pulseloop.coach.context

/**
 * Ported from CoachContextPacket.EnvironmentContext in CoachContextPacket.swift.
 * City-level location + current weather, opt-in (iOS #65d). Raw coordinates never appear
 * here — only the reverse-geocoded city/region, matching iOS's privacy-by-design scope.
 */
@kotlinx.serialization.Serializable
data class EnvironmentContext(
    val city: String? = null,
    val region: String? = null,
    val tempC: Double? = null,
    val condition: String? = null,
    val highC: Double? = null,
    val lowC: Double? = null,
    val precipitationChancePct: Int? = null,
    val sunrise: String? = null,
    val sunset: String? = null,
    val asOf: String = "",
)
