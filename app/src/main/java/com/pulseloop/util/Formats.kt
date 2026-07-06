package com.pulseloop.util

/**
 * Shared display formatters for the activity/sleep surfaces. The home-screen
 * widget payloads promise to be label-identical to the Today tiles
 * (WidgetSnapshotPublisher), so every surface — TodayScreen, ActivityScreen,
 * the widget publisher — must format through here; a drifted copy shows a
 * different number on the widget than in the app.
 */
object Formats {
    /** Whole count with thousands separators — steps, calories ("8,432"). */
    fun count(value: Int): String = "%,d".format(value)

    /** One-decimal display distance ("6.8"); pair with `UnitConverter.distanceUnit`. */
    fun distance(value: Double): String = "%.1f".format(value)

    /** Sleep-style duration, always hours + minutes ("7h 25m"). */
    fun hoursMinutes(totalMinutes: Int): String = "${totalMinutes / 60}h ${totalMinutes % 60}m"

    /** Workout-style duration, minutes-only under an hour ("45m", "1h 5m"). */
    fun durationCompact(totalMinutes: Int): String =
        if (totalMinutes >= 60) hoursMinutes(totalMinutes) else "${totalMinutes}m"
}
