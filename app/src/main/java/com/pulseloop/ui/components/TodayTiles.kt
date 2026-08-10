package com.pulseloop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulseloop.ui.theme.PulseColors

// Today-page tiles, ported from Views/TodayTiles.swift. Every tile is half-width (2-column grid)
// and shares one fixed height so the grid never jumps. Each wraps the shared [TodayTile] chrome
// around a metric-specific visualization, reusing the Vitals design-system components so Today
// and Vitals speak the same visual language. Interpretation math arrives pre-computed in
// [com.pulseloop.ui.viewmodels.VitalCardState] — these composables only lay out prepared state.

/** Shared sizing so every Today tile is identical (TodayTileMetrics in Swift). */
object TodayTileMetrics {
    /** The design height, at the default font scale (1.0). */
    val baseHeight = 168.dp
    val corner = 20.dp

    /**
     * The tile height, grown with the user's font-size setting.
     *
     * The grid is a fixed-height design, but everything inside a tile is sized in `sp` — so a tile
     * stops fitting its own contents the moment the user raises the font scale. That is what clipped
     * the Activity tile's third row (calories, issue #24 round two): the three label/value pairs need
     * `3 × (12 + 18)sp + 2 × 3dp`, and only ~108dp of the 168dp tile is left after the 16dp padding,
     * the eyebrow row, and the 8dp spacer. They stop fitting at roughly **fontScale 1.15**.
     *
     * **This is not a density/DPI problem.** dp and sp both scale by density, so a 420dpi Pixel 8 and
     * a 480dpi Pixel 10 Pro XL lay out identically at the same font scale — which is why this passed
     * verification on the Pixel 8. Only `fontScale` moves sp relative to dp. Shrinking the text again
     * would just move the breaking point; the container has to grow instead.
     *
     * Sampling the scale at 16.sp (the Activity value size) rather than reading `fontScale` directly
     * matters on Android 14+, where font scaling is non-linear and every sp size has its own curve —
     * `fontScale` alone would under-report the growth of the text that actually overflows.
     *
     * Clamped at the bottom so a small-text user still gets the designed layout, and at the top so an
     * accessibility-max setting can't produce an absurd grid. The clamp is safe: at the 2.0 ceiling the
     * values block needs 186dp and a 1.6×-clamped tile still offers ~199dp.
     */
    val height: Dp
        @Composable get() {
            val scale = with(LocalDensity.current) { 16.sp.toDp() / 16.dp }
            return baseHeight * scale.coerceIn(1f, 1.6f)
        }
}

/**
 * The card shell shared by every Today tile: fixed height, rounded card background, eyebrow
 * header (colored dot + caps label), optional tap.
 */
@Composable
fun TodayTile(
    label: String,
    color: Color,
    modifier: Modifier = Modifier,
    onTap: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(TodayTileMetrics.corner)
    Column(
        modifier = modifier
            .height(TodayTileMetrics.height)
            .clip(shape)
            .background(PulseColors.card)
            .border(1.dp, PulseColors.borderSubtle, shape)
            .then(if (onTap != null) Modifier.clickable(onClick = onTap) else Modifier)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier
                    .size(8.dp)
                    .shadow(5.dp, CircleShape, ambientColor = color.copy(alpha = 0.7f), spotColor = color.copy(alpha = 0.7f))
                    .clip(CircleShape)
                    .background(color),
            )
            Text(
                label.uppercase(),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.6.sp,
                color = PulseColors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        content()
    }
}

// ─────────────────── Activity tile (steps + distance + calories → one loop) ───────────────────

/** One labeled value beside the activity loop — label tinted in its ring color. */
data class ActivityTileValue(val label: String, val text: String, val color: Color)

/**
 * Collapses the three activity metrics into the concentric progress loop the Activity page uses,
 * sized for a half-width tile (ActivityTileView in Swift).
 */
@Composable
fun ActivityTile(
    rings: List<ActivityRing>,
    values: List<ActivityTileValue>,
    modifier: Modifier = Modifier,
    onTap: () -> Unit,
) {
    TodayTile(label = "Activity", color = PulseColors.steps, modifier = modifier, onTap = onTap) {
        Row(
            Modifier.weight(1f).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ActivityRings(
                rings = rings,
                size = 88.dp,
                strokeWidth = 9.dp,
                ringSpacing = 4.dp,
            )
            // Three metrics (steps/distance/calories) share one tile. At 22.sp the third value
            // overflowed and clipped the calories row (issue #24), so each pair is compact —
            // 16.sp value, tight line heights, and no font padding (Compose's default
            // includeFontPadding adds several dp per line).
            //
            // That alone was not enough: this block is measured in sp while the tile was a fixed
            // 168.dp, so calories clipped again on a device with a raised font scale. Shrinking the
            // text further would only move the breaking point — [TodayTileMetrics.height] now grows
            // the tile with the font scale instead. Don't re-pin the height to a dp literal.
            val compact = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
            Column(verticalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.weight(1f)) {
                values.forEach { value ->
                    Column {
                        Text(
                            value.label.uppercase(),
                            fontSize = 10.sp,
                            lineHeight = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.8.sp,
                            color = value.color,
                            style = compact,
                        )
                        Text(
                            value.text,
                            fontSize = 16.sp,
                            lineHeight = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = PulseColors.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = compact,
                        )
                    }
                }
            }
        }
    }
}

// ─────────────────── Sleep tile (duration + stage bar + score) ───────────────────

/** One proportional stage segment of the sleep bar. */
data class SleepStageSegment(val minutes: Double, val color: Color, val label: String)

/**
 * The one place stage blocks turn into the tile's proportional bar segments (order, colors,
 * labels). Shared by the Today sleep tile and the widget snapshot publisher so the widget's
 * stage bar is identical to the in-app one.
 */
fun buildSleepStageSegments(
    blocks: List<com.pulseloop.data.entity.SleepStageBlockEntity>,
): List<SleepStageSegment> {
    val byStage = blocks.groupBy { it.stageRaw }
        .mapValues { (_, stageBlocks) -> stageBlocks.sumOf { it.durationMinutes }.toDouble() }
    return listOf(
        SleepStageSegment(byStage["DEEP"] ?: 0.0, PulseColors.stageDeep, "DEEP"),
        SleepStageSegment(byStage["LIGHT"] ?: 0.0, PulseColors.stageLight, "LIGHT"),
        SleepStageSegment(byStage["REM"] ?: 0.0, PulseColors.stageRem, "REM"),
        SleepStageSegment(byStage["AWAKE"] ?: 0.0, PulseColors.stageAwake, "AWK"),
    ).filter { it.minutes > 0 }
}

/**
 * A single stacked bar of proportional deep/light/REM/awake segments (SleepStageBar.swift).
 * Labels are drawn under segments that are wide enough to carry them.
 */
@Composable
fun SleepStageBar(segments: List<SleepStageSegment>, modifier: Modifier = Modifier) {
    val total = segments.sumOf { it.minutes }.coerceAtLeast(1.0)
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(Modifier.fillMaxWidth().height(12.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            segments.forEach { seg ->
                Box(
                    Modifier
                        .weight((seg.minutes / total).toFloat().coerceAtLeast(0.01f))
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(3.dp))
                        .background(seg.color),
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            segments.forEach { seg ->
                Box(Modifier.weight((seg.minutes / total).toFloat().coerceAtLeast(0.01f))) {
                    // A sliver's label would dominate the sliver itself — only label ≥10% shares.
                    if (seg.minutes / total >= 0.10) {
                        Text(
                            seg.label,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.5.sp,
                            color = seg.color,
                            maxLines = 1,
                            overflow = TextOverflow.Clip,
                        )
                    }
                }
            }
        }
    }
}

/** Sleep tile: duration on top, stage distribution bar, score below (SleepTileView in Swift). */
@Composable
fun SleepTile(
    durationText: String?,
    segments: List<SleepStageSegment>,
    score: Int?,
    modifier: Modifier = Modifier,
    onTap: () -> Unit,
) {
    TodayTile(label = "Sleep", color = PulseColors.sleep, modifier = modifier, onTap = onTap) {
        if (durationText != null) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    durationText,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = PulseColors.textPrimary,
                    maxLines = 1,
                )
                SleepStageBar(segments)
                if (score != null) {
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(
                            "$score",
                            fontSize = 32.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = PulseColors.textPrimary,
                        )
                        Text(
                            "SCORE",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 1.sp,
                            color = PulseColors.textMuted,
                            modifier = Modifier.padding(bottom = 6.dp),
                        )
                    }
                }
            }
        } else {
            Column(
                Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    Icons.Outlined.Bedtime, null,
                    tint = PulseColors.sleep.copy(alpha = 0.7f),
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "No sleep recorded",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = PulseColors.textPrimary,
                )
            }
        }
    }
}

// ─────────────────── Hero insight + coach message cards ───────────────────

enum class ChipTone { NEUTRAL, UP, DOWN, WARN }

data class ToneChip(val label: String, val tone: ChipTone)

private fun ChipTone.foreground() = when (this) {
    ChipTone.NEUTRAL -> PulseColors.textSecondary
    ChipTone.UP -> PulseColors.success
    ChipTone.DOWN -> PulseColors.danger
    ChipTone.WARN -> PulseColors.warning
}

private fun ChipTone.background() = when (this) {
    ChipTone.NEUTRAL -> Color.White.copy(alpha = 0.10f)
    ChipTone.UP -> PulseColors.success.copy(alpha = 0.15f)
    ChipTone.DOWN -> PulseColors.danger.copy(alpha = 0.15f)
    ChipTone.WARN -> PulseColors.warning.copy(alpha = 0.15f)
}

private fun ChipTone.border() = when (this) {
    ChipTone.NEUTRAL -> PulseColors.borderSubtle
    else -> foreground().copy(alpha = 0.30f)
}

/** Full-width gradient hero card at the top of Today (HeroInsightCardView in Swift). */
@Composable
fun HeroInsightCard(title: String, summary: String, chips: List<ToneChip>, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(24.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                Brush.linearGradient(
                    listOf(PulseColors.accent.copy(alpha = 0.18f), PulseColors.spo2.copy(alpha = 0.10f)),
                ),
            )
            .border(1.dp, PulseColors.borderSubtle, shape)
            .padding(20.dp),
    ) {
        Text(title, fontSize = 28.sp, fontWeight = FontWeight.SemiBold, color = PulseColors.textPrimary)
        Text(
            summary,
            fontSize = 15.sp,
            lineHeight = 21.sp,
            color = PulseColors.textSecondary,
            modifier = Modifier.padding(top = 8.dp),
        )
        if (chips.isNotEmpty()) {
            Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                chips.forEach { chip ->
                    Text(
                        chip.label,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = chip.tone.foreground(),
                        maxLines = 1,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(chip.tone.background())
                            .border(1.dp, chip.tone.border(), CircleShape)
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }
            }
        }
    }
}

/** Full-width coach card (COACH eyebrow, headline, body, chips) — CoachMessageCard in Swift. */
@Composable
fun CoachMessageCard(
    headline: String,
    body: String,
    chips: List<String> = emptyList(),
    modifier: Modifier = Modifier,
    onTap: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(24.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(PulseColors.card)
            .border(1.dp, PulseColors.borderSubtle, shape)
            .then(if (onTap != null) Modifier.clickable(onClick = onTap) else Modifier)
            .padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(
                Modifier
                    .size(6.dp)
                    .shadow(5.dp, CircleShape, ambientColor = PulseColors.accent, spotColor = PulseColors.accent)
                    .clip(CircleShape)
                    .background(PulseColors.accent),
            )
            Text(
                "COACH",
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 1.8.sp,
                color = PulseColors.textMuted,
            )
        }
        Text(
            headline,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = PulseColors.textPrimary,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            body,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            color = PulseColors.textSecondary,
            modifier = Modifier.padding(top = 6.dp),
        )
        if (chips.isNotEmpty()) {
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                chips.take(3).forEach { chip ->
                    Text(
                        chip,
                        fontSize = 11.sp,
                        color = PulseColors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(PulseColors.cardSoft)
                            .border(1.dp, PulseColors.borderSubtle, CircleShape)
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }
            }
        }
    }
}
