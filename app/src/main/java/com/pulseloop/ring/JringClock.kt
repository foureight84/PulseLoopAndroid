package com.pulseloop.ring

import java.time.Instant
import java.util.TimeZone

/**
 * Ported from [JringClock] in JringClock.swift.
 *
 * The jring's RTC stores **local wall-clock** seconds, because [RingEncoder.makeTimeSyncCommand]
 * sends `utcEpoch + utcOffset` (matching the vendor SDK, which caches the same offset). Every
 * timestamp the ring stamps onto a history record is therefore a local-wall-clock epoch and must
 * have that offset subtracted back off to recover a true instant.
 *
 * These two halves must always move together: changing the encoder without the decoder — or vice
 * versa — shifts all history by the UTC offset.
 *
 * One instance is created per connection by [JringDriver] and shared with the driver's
 * [RingDecoder] and [JringSyncEngine], so the offset used to decode a reply is always the one
 * that connection actually sent.
 */
class JringClock(
    timeZone: TimeZone = TimeZone.getDefault(),
    now: Instant = Instant.now(),
) {
    /** Seconds east of UTC, DST included. */
    var offsetSeconds: Long = timeZone.getOffset(now.toEpochMilli()) / 1000L
        private set

    /** Latch the offset that is about to go out on the wire in a 0x01 time-sync command. */
    fun capture(timeZone: TimeZone = TimeZone.getDefault(), now: Instant = Instant.now()) {
        offsetSeconds = timeZone.getOffset(now.toEpochMilli()) / 1000L
    }

    /** Convert a ring-stamped local-wall-clock epoch into a true [Instant]. */
    fun date(ringEpochSeconds: Long): Instant = Instant.ofEpochSecond(ringEpochSeconds - offsetSeconds)
}
