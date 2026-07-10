package com.pulseloop.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit coverage for [mdInline], the pure inline-markdown parser shared by the coach replies and
 * the update-changelog dialog. Asserts on the flattened plain text (.text) and the bold spans
 * (.spanStyles) so no Compose runtime/UI is needed.
 */
class MarkdownLiteTest {
    private val bold = Color.Black

    @Test
    fun `link is flattened to its text, url dropped`() {
        assertEquals("see the docs", mdInline("see [the docs](https://example.com)", bold).text)
    }

    @Test
    fun `inline code is unwrapped`() {
        assertEquals("run build now", mdInline("run `build` now", bold).text)
    }

    @Test
    fun `bare url is left untouched`() {
        val url = "Full Changelog: https://example.com/compare/v1...v2"
        assertEquals(url, mdInline(url, bold).text)
    }

    @Test
    fun `bold run is stripped of markers and styled semibold`() {
        val out = mdInline("a **strong** word", bold)
        assertEquals("a strong word", out.text)
        val span = out.spanStyles.single()
        assertEquals(FontWeight.SemiBold, span.item.fontWeight)
        assertEquals("strong", out.text.substring(span.start, span.end))
    }

    @Test
    fun `multiple bold runs each get a span`() {
        val out = mdInline("**one** and **two**", bold)
        assertEquals("one and two", out.text)
        assertEquals(2, out.spanStyles.size)
    }

    @Test
    fun `unterminated marker is left literal`() {
        // A lone ** with no closing pair must not swallow the rest of the line.
        val out = mdInline("price is 5 ** off", bold)
        assertEquals("price is 5 ** off", out.text)
        assertTrue(out.spanStyles.isEmpty())
    }

    @Test
    fun `code inside a link text survives flattening`() {
        // Link flatten runs first, then code unwrap — nested markup resolves in one pass.
        assertEquals("use build", mdInline("use [`build`](https://x)", bold).text)
    }
}
