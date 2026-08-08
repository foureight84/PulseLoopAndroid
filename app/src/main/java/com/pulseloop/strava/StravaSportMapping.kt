package com.pulseloop.strava

object StravaSportMapping {

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
