package com.pulseloop.coach.summaries

import com.pulseloop.coach.context.CoachLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SummaryLanguageSignatureTest {
    @Test fun englishAddsNothingToTheSignature() {
        // JVM tests resolve the default (English) strings.xml.
        assertNull(CoachLanguage.languageName())
        assertEquals("", CoachLanguage.directive())
        assertEquals("", CoachSummaryContextBuilder.languageSuffix(null))
    }

    @Test fun languageRoundTripsThroughTheSignature() {
        val sig = "1200|·|30" + CoachSummaryContextBuilder.languageSuffix("Russian")
        assertEquals("Russian", CoachSummaryContextBuilder.languageOf(sig))
        assertEquals("", CoachSummaryContextBuilder.languageOf("1200|·|30"))
    }
}
