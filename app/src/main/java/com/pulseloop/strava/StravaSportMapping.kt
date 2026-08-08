package com.pulseloop.strava

object StravaSportMapping {

    // Maps PulseLoop activity types to Strava API sport_type enum values (used in PUT /activities/{id}).
    // These differ from the TCX XML <Activity Sport="..."> names in StravaTCXBuilder — by design. 

    fun toStravaType(type: String): String? = when (type) {
        "run" -> "Run"
        "walk" -> "Walk"
        "cycle" -> "Ride"
        "gym" -> "WeightTraining"
        "squash" -> "Squash"
        "yoga" -> "Yoga"
        "hike" -> "Hike"
        "dance" -> "Workout"
        "sport" -> "Workout"
        else -> "Workout"
    }
}
