package com.pulseloop.strava

/**
 * Maps PulseLoop activity types onto Strava's `sport_type` enum. Ported from
 * StravaSportMapping.swift (iOS #100).
 *
 * Distinct from the TCX `<Activity Sport="…">` names in [StravaTCXBuilder] — by design: TCX v2 only
 * allows Running / Biking / Other, which is exactly why [needsSportTypeFix] exists.
 */
object StravaSportMapping {

    /** Desired Strava `sport_type` for the follow-up activity update. Never null — unknown → Workout. */
    fun toStravaType(type: String): String = when (type) {
        "run" -> "Run"
        "walk" -> "Walk"
        "cycle" -> "Ride"
        "gym" -> "WeightTraining"
        "squash" -> "Squash"
        "yoga" -> "Yoga"
        "hike" -> "Hike"
        else -> "Workout"   // dance, sport, and any unknown custom type
    }

    /**
     * True when uploading the TCX sport alone won't produce the desired `sport_type`, so the
     * uploader must follow up with an activity update. Only run and cycle map losslessly.
     */
    fun needsSportTypeFix(type: String): Boolean = type != "run" && type != "cycle"

    /** Human label used in the Strava activity name ("Morning Run"). */
    fun displayLabel(type: String): String = when (type) {
        "run" -> "Run"
        "walk" -> "Walk"
        "cycle" -> "Ride"
        "gym" -> "Workout"
        "squash" -> "Squash"
        "yoga" -> "Yoga"
        "hike" -> "Hike"
        "dance" -> "Workout"
        else -> "Activity"
    }
}
