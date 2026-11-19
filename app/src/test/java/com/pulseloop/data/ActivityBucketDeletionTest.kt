package com.pulseloop.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What a deleted activity bucket does to the ring's own cumulative day counter (issue #70).
 *
 * The tombstone and the restate both need Room; this is the rule that doesn't, and it is the one
 * the deletion actually hung on — the history path honoured the tombstone while the live path
 * ratcheted the deleted block straight back in from the ring's running total.
 */
class ActivityBucketDeletionTest {

    @Test
    fun `with nothing deleted the ring's counter ratchets as before`() {
        assertEquals(8_000, ActivityBucketDeletion.ratchetAgainstRing(7_500, 8_000, 0))
    }

    @Test
    fun `a counter behind the stored total never drags the day down`() {
        assertEquals(8_000, ActivityBucketDeletion.ratchetAgainstRing(8_000, 7_900, 0))
    }

    /** The scenario: 1,000 steps deleted out of today's 8,000, then the next live frame lands. */
    @Test
    fun `the deleted block does not come back on the next live frame`() {
        val afterDelete = 7_000

        assertEquals(afterDelete, ActivityBucketDeletion.ratchetAgainstRing(afterDelete, 8_000, 1_000))
    }

    /** Steps taken after the deletion still count — the day is corrected, not frozen. */
    @Test
    fun `the day keeps climbing from the corrected total`() {
        assertEquals(7_500, ActivityBucketDeletion.ratchetAgainstRing(7_000, 8_500, 1_000))
    }

    /** The ring resets its own counter at midnight; a stale deficit must not push a day negative. */
    @Test
    fun `a deficit larger than the counter floors at zero`() {
        assertEquals(0, ActivityBucketDeletion.ratchetAgainstRing(0, 200, 1_000))
        assertEquals(0.0, ActivityBucketDeletion.ratchetAgainstRing(0.0, 150.0, 900.0), 0.001)
    }
}
