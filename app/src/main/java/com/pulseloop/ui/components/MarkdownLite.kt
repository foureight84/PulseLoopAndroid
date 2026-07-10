package com.pulseloop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulseloop.ui.theme.PulseColors

/**
 * Minimal markdown renderer shared across the app (coach replies, the "What's new" update
 * dialog): `## ` headings as semibold lines, `- `/`* `/`• ` bullet lines with accent dots,
 * `**bold**` inline, `[text](url)` links flattened to their text, and `` `code` `` unwrapped.
 * Anything else renders as-is. Full markdown (tables, images) is intentionally out of scope —
 * this exists so release notes and assistant text read cleanly instead of showing raw `#`/`*`.
 *
 * Colors default to the fixed dark [PulseColors] palette (correct on the coach's dark cards).
 * A caller rendering onto a theme-driven surface — e.g. the Material [AlertDialog] update
 * sheet, which is light in light mode — must pass theme colors so the text stays legible.
 */
@Composable
fun MarkdownLite(
    text: String,
    modifier: Modifier = Modifier,
    headingColor: Color = PulseColors.textPrimary,
    bodyColor: Color = PulseColors.textSecondary,
    bulletColor: Color = PulseColors.accent,
    boldColor: Color = PulseColors.textPrimary,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        text.split("\n").forEach { rawLine ->
            val line = rawLine.trimEnd()
            when {
                line.isBlank() -> Spacer(Modifier.height(2.dp))
                line.startsWith("#") -> Text(
                    mdInline(line.trimStart('#', ' '), boldColor),
                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = headingColor,
                )
                line.startsWith("- ") || line.startsWith("* ") || line.startsWith("• ") -> Row {
                    Box(
                        Modifier
                            .padding(top = 7.dp, end = 8.dp)
                            .size(5.dp)
                            .clip(CircleShape)
                            .background(bulletColor),
                    )
                    Text(mdInline(line.substring(2), boldColor), fontSize = 14.sp, lineHeight = 20.sp, color = bodyColor)
                }
                else -> Text(mdInline(line, boldColor), fontSize = 14.sp, lineHeight = 20.sp, color = bodyColor)
            }
        }
    }
}

private val LINK_RE = Regex("""\[([^\]]+)]\(([^)]+)\)""")
private val CODE_RE = Regex("""`([^`]+)`""")

/**
 * Render one line of inline markdown: flatten `[text](url)` to `text` and unwrap `` `code` ``
 * first (so their contents can still carry `**bold**`), then bold the `**…**` runs. Bare URLs
 * are left untouched — dropping them would hide the changelog's "Full Changelog" link target.
 */
private fun mdInline(raw: String, boldColor: Color): AnnotatedString {
    val line = raw
        .replace(LINK_RE) { it.groupValues[1] }
        .replace(CODE_RE) { it.groupValues[1] }
    return buildAnnotatedString {
        var rest = line
        while (true) {
            val start = rest.indexOf("**")
            val end = if (start >= 0) rest.indexOf("**", start + 2) else -1
            if (start < 0 || end < 0) {
                append(rest)
                return@buildAnnotatedString
            }
            append(rest.substring(0, start))
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = boldColor)) {
                append(rest.substring(start + 2, end))
            }
            rest = rest.substring(end + 2)
        }
    }
}
