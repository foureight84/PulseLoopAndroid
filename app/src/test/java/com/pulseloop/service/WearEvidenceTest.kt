package com.pulseloop.service

import com.pulseloop.service.WearEvidence.Companion.WEAR_PROOF_WINDOW_MS
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule that keeps a vital the ring cannot measure from being reported as "you're not wearing it".
 * See [WearEvidence] for the capture evidence behind it.
 */
class WearEvidenceTest {

    private val t0 = 1_700_000_000_000L

    @Test
    fun `without a heart rate reading nothing vouches for wear`() {
        // A fresh connection has earned no proof, so a not-worn push is taken at face value —
        // which is the behaviour that makes the "put the ring on" hint useful in the first place.
        assertFalse(WearEvidence().provesWorn(t0))
    }

    @Test
    fun `a recent bpm proves the ring is on a finger`() {
        val e = WearEvidence().withHeartRateSample(t0)
        assertTrue(e.provesWorn(t0))
        // The real case from zaggash's capture: a good HR reading, then an SpO2 failure 8s later.
        assertTrue(e.provesWorn(t0 + 8_000))
    }

    @Test
    fun `proof expires so a ring genuinely taken off is reported again`() {
        val e = WearEvidence().withHeartRateSample(t0)
        assertTrue(e.provesWorn(t0 + WEAR_PROOF_WINDOW_MS))
        assertFalse(e.provesWorn(t0 + WEAR_PROOF_WINDOW_MS + 1))
    }

    @Test
    fun `a bpm stamped in the future never vouches`() {
        // A clock jump must not hand out indefinite proof.
        val e = WearEvidence().withHeartRateSample(t0 + 60_000)
        assertFalse(e.provesWorn(t0))
    }

    @Test
    fun `a newer bpm extends the proof`() {
        val e = WearEvidence().withHeartRateSample(t0).withHeartRateSample(t0 + 100_000)
        assertFalse(WearEvidence().withHeartRateSample(t0).provesWorn(t0 + 200_000))
        assertTrue(e.provesWorn(t0 + 200_000))
    }

    @Test
    fun `clearing forgets the proof so a new connection must earn its own`() {
        val e = WearEvidence().withHeartRateSample(t0)
        assertFalse(e.cleared().provesWorn(t0))
    }
}
