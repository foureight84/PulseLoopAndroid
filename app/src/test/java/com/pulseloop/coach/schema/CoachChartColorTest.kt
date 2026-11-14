package com.pulseloop.coach.schema

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CoachChartColorTest {
    private val russian = arrayOf("пульс,ЧСС,сердц", "уд/мин", "кислород,сатурац", "сон,сна,сну,сном", "шаг")

    private fun color(title: String, y: String = "", words: Array<String> = emptyArray()): Color? {
        val chart = CoachChart(title = title, yLabel = y)
        return if (words.isEmpty()) chartColor(chart)
        else chartColor(chart, words[0], words[1], words[2], words[3], words[4])
    }

    @Test fun englishKeywordsKeepTheirColours() {
        assertEquals(Color(0xFFE53935), color("Resting HR"))
        assertEquals(Color(0xFFE53935), color("Week", y = "bpm"))
        assertEquals(Color(0xFF1E88E5), color("SpO2 trend"))
        assertEquals(Color(0xFF7E57C2), color("Sleep this week"))
        assertEquals(Color(0xFF4CAF50), color("Daily steps"))
        assertNull(color("Calories"))
    }

    @Test fun translatedTitlesAreMatchedThroughTheResourceWords() {
        assertEquals(Color(0xFFE53935), color("Пульс в покое", words = russian))
        assertEquals(Color(0xFFE53935), color("Неделя", y = "уд/мин", words = russian))
        assertEquals(Color(0xFF7E57C2), color("Длительность сна", words = russian))
        assertEquals(Color(0xFF4CAF50), color("Шаги за неделю", words = russian))
        assertNull(color("Калории", words = russian))
    }

    @Test fun englishWordsStillMatchUnderATranslation() {
        assertEquals(Color(0xFFE53935), color("HR за неделю", words = russian))
    }
}
