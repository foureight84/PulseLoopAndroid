package com.pulseloop.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulseloop.data.entity.SleepSessionEntity
import com.pulseloop.data.entity.SleepStageBlockEntity
import com.pulseloop.service.SleepBar
import com.pulseloop.service.SleepFormat
import com.pulseloop.service.SleepInsights
import com.pulseloop.service.SleepQualityLabel
import com.pulseloop.service.SleepRangeKey
import com.pulseloop.ui.components.CoachMessageCard
import com.pulseloop.ui.theme.PulseColors
import com.pulseloop.ui.viewmodels.SleepViewModel

/**
 * Sleep dashboard — ported from SleepView.swift (+ DesignSystem sleep components):
 * Day/Week/Month/Year range selector, LAST SLEEP hero (duration + score + rating word),
 * the stage-architecture hypnogram (Day) or nightly-duration histogram (aggregates),
 * three stage-duration cards, and the coach card with follow-up chips.
 */
@Composable
fun SleepScreen(
    navController: androidx.navigation.NavController? = null,
    viewModel: SleepViewModel? = null,
    // Invoked once when the screen opens: triggers a dedicated on-demand sleep sync (QRing
    // parity) so opening this screen actively pulls last night's sleep instead of relying on
    // the background full-sync's SLEEP stage. No-op when disconnected / unsupported.
    onOpen: () -> Unit = {},
    // Heights of the glass top/bottom bars this screen scrolls under (0 when standalone).
    topBarPadding: androidx.compose.ui.unit.Dp = 0.dp,
    bottomBarPadding: androidx.compose.ui.unit.Dp = 0.dp,
) {
    LaunchedEffect(Unit) { onOpen() }
    val state by (viewModel?.state?.collectAsState() ?: remember { mutableStateOf(SleepViewModel.SleepState()) })

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp,
            top = 16.dp + topBarPadding, bottom = 16.dp + bottomBarPadding,
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            SleepRangeSelector(state.range) { viewModel?.setRange(it) }
        }
        if (state.range == SleepRangeKey.DAY) dayItems(state, navController, viewModel)
        else aggregateItems(state, navController)
        item { Spacer(Modifier.height(64.dp)) }
    }
}

// ─────────────────────────── Day view ───────────────────────────

private fun androidx.compose.foundation.lazy.LazyListScope.dayItems(
    state: SleepViewModel.SleepState,
    navController: androidx.navigation.NavController?,
    viewModel: SleepViewModel?,
) {
    // Date stepper: ‹ older · date · newer › (iOS #84).
    item { SleepDayNavHeader(state, viewModel) }

    val sessions = state.daySessions
    when {
        sessions.isEmpty() -> {
            val noData = SleepInsights.noDataState(SleepRangeKey.DAY)
            item { SleepHeroCard(label = noData.label, value = noData.value, support = noData.support, score = null, scoreLabel = null, noData = true) }
            item {
                VisualizationCard(eyebrow = "Stages", title = "Sleep architecture", legend = false) {
                    Column(
                        Modifier.fillMaxWidth().height(180.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("No sleep recorded", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = PulseColors.textPrimary)
                        Text(
                            "Wear your ring overnight to see your hypnogram here.",
                            fontSize = 12.sp, color = PulseColors.textMuted,
                        )
                    }
                }
            }
            item { SleepStageSummaryCards(deep = "—", light = "—", awake = "—") }
        }
        // Single session: render exactly as before, no carousel chrome.
        sessions.size == 1 -> sessionPageItems(sessions[0], state.dayBlocks[sessions[0].id] ?: emptyList())
        // Multiple sessions (night + naps): horizontal paged carousel with dot indicators.
        else -> item { SleepCarousel(sessions, state.dayBlocks) }
    }

    item {
        val summary = state.daySummary
        val fallback = state.coach
        CoachMessageCard(
            headline = summary?.title ?: fallback?.headline ?: "Sleep insights",
            body = summary?.body ?: fallback?.body ?: "",
            chips = summary?.let { parseChipsJson(it.chipsJSON) } ?: fallback?.chips ?: emptyList(),
            onTap = { navController?.navigate("coach") },
        )
    }
}

/** One session's card stack: hero + hypnogram + three stage cards. Emitted as separate LazyColumn
 *  items so a single-session day scrolls identically to the pre-carousel layout. */
private fun androidx.compose.foundation.lazy.LazyListScope.sessionPageItems(
    session: SleepSessionEntity,
    blocks: List<SleepStageBlockEntity>,
) {
    item { SessionHero(session, blocks) }
    item {
        VisualizationCard(eyebrow = "Stages", title = "Sleep architecture", legend = true) {
            SleepHypnogram(blocks = blocks, totalMin = session.totalMinutes, startTs = session.startAt)
        }
    }
    item {
        val byStage = blocks.groupBy { it.stageRaw }.mapValues { (_, b) -> b.sumOf { it.durationMinutes } }
        SleepStageSummaryCards(
            deep = SleepFormat.duration(byStage["DEEP"] ?: 0),
            light = SleepFormat.duration(byStage["LIGHT"] ?: 0),
            awake = SleepFormat.duration(byStage["AWAKE"] ?: 0),
        )
    }
}

/** Hero card for one session, with its score computed from that session's own blocks. */
@Composable
private fun SessionHero(session: SleepSessionEntity, blocks: List<SleepStageBlockEntity>) {
    val score = remember(session.id, blocks) { com.pulseloop.service.SleepScore.calculate(session, blocks) }
    SleepHeroCard(
        label = "Last Sleep",
        value = SleepFormat.duration(session.totalMinutes),
        support = "${clockTime(session.startAt)} to ${clockTime(session.endAt)}",
        score = score.score,
        scoreLabel = qualityText(score.label),
    )
}

/** Horizontal paged carousel across a day's sleep sessions, with a per-page header and dot row. */
@Composable
private fun SleepCarousel(
    sessions: List<SleepSessionEntity>,
    blocksBySession: Map<String, List<SleepStageBlockEntity>>,
) {
    // Reset to the first page whenever the day's session set changes (a different day / fewer
    // pages): keying the composable recreates the pager state.
    val sessionsKey = sessions.joinToString(",") { it.id }
    androidx.compose.runtime.key(sessionsKey) {
    val pagerState = rememberPagerState { sessions.size }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxWidth().wrapContentHeight(),
            verticalAlignment = Alignment.Top,
            pageSpacing = 12.dp,
        ) { page ->
            val s = sessions[page]
            val blocks = blocksBySession[s.id] ?: emptyList()
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    "${page + 1} of ${sessions.size} · ${clockTime(s.startAt)}–${clockTime(s.endAt)}".uppercase(),
                    fontSize = 11.sp, fontWeight = FontWeight.Medium,
                    letterSpacing = 0.8.sp, color = PulseColors.textSecondary,
                )
                SessionHero(s, blocks)
                VisualizationCard(eyebrow = "Stages", title = "Sleep architecture", legend = true) {
                    SleepHypnogram(blocks = blocks, totalMin = s.totalMinutes, startTs = s.startAt)
                }
                val byStage = blocks.groupBy { it.stageRaw }.mapValues { (_, b) -> b.sumOf { it.durationMinutes } }
                SleepStageSummaryCards(
                    deep = SleepFormat.duration(byStage["DEEP"] ?: 0),
                    light = SleepFormat.duration(byStage["LIGHT"] ?: 0),
                    awake = SleepFormat.duration(byStage["AWAKE"] ?: 0),
                )
            }
        }
        // Page indicator dots.
        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            repeat(sessions.size) { i ->
                Box(
                    Modifier
                        .padding(horizontal = 4.dp)
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(
                            if (i == pagerState.currentPage) PulseColors.accent
                            else PulseColors.textSecondary.copy(alpha = 0.3f),
                        ),
                )
            }
        }
    }
    }
}

// ─────────────────────────── Aggregate view ───────────────────────────

private fun androidx.compose.foundation.lazy.LazyListScope.aggregateItems(
    state: SleepViewModel.SleepState,
    navController: androidx.navigation.NavController?,
) {
    val valid = SleepInsights.validSessions(state.rangeSessions)
    val enough = valid.size >= 2
    val noData = SleepInsights.noDataState(state.range)
    item {
        SleepHeroCard(
            label = SleepInsights.rangeHeroLabel[state.range] ?: "Sleep",
            value = if (enough) SleepFormat.duration(state.avgMinutes ?: 0) else noData.value,
            support = if (state.range == SleepRangeKey.YEAR)
                "Tracked ${valid.size} ${if (valid.size == 1) "night" else "nights"} this year"
            else
                "${valid.size} of ${state.expectedNights} nights tracked",
            score = if (enough) state.avgScore else null,
            scoreLabel = if (enough) state.avgScore?.let { qualityText(com.pulseloop.service.SleepScore.qualityLabel(it)) } else null,
            noData = !enough,
        )
    }
    item {
        VisualizationCard(
            eyebrow = "Duration",
            title = if (state.range == SleepRangeKey.YEAR) "Monthly average" else "Nightly sleep",
            legend = false,
        ) {
            SleepDurationHistogram(
                bars = state.bars,
                goalMin = state.goalMinutes,
                slim = state.range == SleepRangeKey.MONTH,
            )
        }
    }
    item {
        SleepStageSummaryCards(
            prefix = "Avg ",
            deep = state.stageAvg?.let { SleepFormat.duration(it.first) } ?: "—",
            light = state.stageAvg?.let { SleepFormat.duration(it.second) } ?: "—",
            awake = state.stageAvg?.let { SleepFormat.duration(it.third) } ?: "—",
        )
    }
    item {
        val summary = state.rangeSummary
        val fallback = state.aggregateCoach
        CoachMessageCard(
            headline = summary?.title ?: fallback?.headline ?: "Sleep insights",
            body = summary?.body ?: fallback?.body ?: "",
            chips = summary?.let { parseChipsJson(it.chipsJSON) } ?: fallback?.chips ?: emptyList(),
            onTap = { navController?.navigate("coach") },
        )
    }
}

// ─────────────────────────── Components ───────────────────────────

/** Capsule Day/Week/Month/Year segmented control (SleepRangeSelectorView in Swift). */
@Composable
private fun SleepRangeSelector(selection: SleepRangeKey, onSelect: (SleepRangeKey) -> Unit) {
    val options = listOf(
        SleepRangeKey.DAY to "Day", SleepRangeKey.WEEK to "Week",
        SleepRangeKey.MONTH to "Month", SleepRangeKey.YEAR to "Year",
    )
    Row(
        Modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .background(PulseColors.cardSoft.copy(alpha = 0.4f))
            .border(1.dp, PulseColors.borderSubtle, CircleShape)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { (key, label) ->
            val active = selection == key
            Text(
                label,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (active) PulseColors.textPrimary else PulseColors.textMuted,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier
                    .weight(1f)
                    .clip(CircleShape)
                    .background(if (active) PulseColors.accent.copy(alpha = 0.15f) else Color.Transparent)
                    .border(1.dp, if (active) PulseColors.accent.copy(alpha = 0.4f) else Color.Transparent, CircleShape)
                    .clickable { onSelect(key) }
                    .padding(vertical = 6.dp),
            )
        }
    }
}

/** LAST SLEEP hero: duration left, big purple score + rating word right (SleepHeroCardView). */
@Composable
private fun SleepHeroCard(
    label: String,
    value: String,
    support: String?,
    score: Int?,
    scoreLabel: String?,
    noData: Boolean = false,
) {
    val shape = RoundedCornerShape(22.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                Brush.linearGradient(
                    listOf(PulseColors.sleep.copy(alpha = 0.16f), PulseColors.card),
                ),
            )
            .border(1.dp, PulseColors.borderSubtle, shape)
            .padding(20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                label.uppercase(),
                fontSize = 11.sp, fontWeight = FontWeight.Medium,
                letterSpacing = 1.8.sp, color = PulseColors.textMuted,
            )
            Text(
                value,
                fontSize = if (noData) 24.sp else 40.sp,
                fontWeight = FontWeight.SemiBold,
                color = PulseColors.textPrimary,
                modifier = Modifier.padding(top = 4.dp),
            )
            if (support != null) {
                Text(support, fontSize = 14.sp, color = PulseColors.textSecondary, modifier = Modifier.padding(top = 8.dp))
            }
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                score?.toString() ?: "—",
                fontSize = 40.sp, fontWeight = FontWeight.SemiBold,
                color = PulseColors.sleep,
            )
            if (scoreLabel != null && score != null) {
                Text(scoreLabel, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = PulseColors.textPrimary)
            }
        }
    }
}

/** Card wrapper for sleep visualizations: eyebrow + title + optional stage legend. */
@Composable
private fun VisualizationCard(
    eyebrow: String,
    title: String,
    legend: Boolean,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(22.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(PulseColors.card)
            .border(1.dp, PulseColors.borderSubtle, shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    eyebrow.uppercase(),
                    fontSize = 11.sp, fontWeight = FontWeight.Medium,
                    letterSpacing = 1.8.sp, color = PulseColors.textMuted,
                )
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = PulseColors.textPrimary)
            }
            if (legend) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    LegendItem("Deep", PulseColors.stageDeep)
                    LegendItem("Light", PulseColors.stageLight)
                    LegendItem("REM", PulseColors.stageRem)
                    LegendItem("Awake", PulseColors.stageAwake)
                }
            }
        }
        content()
    }
}

@Composable
private fun LegendItem(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(color))
        Text(label, fontSize = 9.sp, color = PulseColors.textSecondary)
    }
}

/**
 * Step-style hypnogram: AWAKE/REM/LIGHT/DEEP lanes, glowing stage-colored segments,
 * dashed vertical transition connectors, time ticks below (SleepHypnogramView in Swift).
 */
@Composable
private fun SleepHypnogram(
    blocks: List<SleepStageBlockEntity>,
    totalMin: Int,
    startTs: Long,
    height: androidx.compose.ui.unit.Dp = 210.dp,
) {
    val lanes = listOf("AWAKE", "REM", "LIGHT", "DEEP")
    val laneFrac = mapOf("AWAKE" to 0.15f, "REM" to 0.38f, "LIGHT" to 0.62f, "DEEP" to 0.85f, "UNKNOWN" to 0.62f)
    fun stageColor(stage: String): Color = when (stage) {
        "DEEP" -> PulseColors.stageDeep
        "LIGHT" -> PulseColors.stageLight
        "REM" -> PulseColors.stageRem
        "AWAKE" -> PulseColors.stageAwake
        else -> PulseColors.stageLight
    }
    val sorted = remember(blocks) {
        blocks.filter { it.durationMinutes > 0 && it.stageRaw != "UNKNOWN" }.sortedBy { it.startMinute }
    }
    val safeTotal = if (totalMin > 0) totalMin else 1
    val ticks = listOf(0, safeTotal / 3, safeTotal * 2 / 3, safeTotal).map { offset ->
        clockTime(startTs + offset * 60_000L)
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(height - 22.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF0F141F))
                .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(16.dp)),
        ) {
            // Lane labels on the left.
            Column(
                Modifier.fillMaxHeight().padding(vertical = 14.dp, horizontal = 12.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                lanes.forEach { stage ->
                    Text(
                        stage,
                        fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.4.sp, color = stageColor(stage),
                    )
                }
            }
            // Plot area, inset to clear the labels.
            Canvas(
                Modifier
                    .fillMaxSize()
                    .padding(start = 64.dp, end = 16.dp, top = 16.dp, bottom = 16.dp),
            ) {
                if (sorted.isEmpty()) return@Canvas
                fun laneY(stage: String) = size.height * (laneFrac[stage] ?: 0.62f)
                fun x(minute: Int) = (minute.toFloat() / safeTotal).coerceIn(0f, 1f) * size.width

                // Dashed vertical connectors between consecutive blocks.
                for (i in 1 until sorted.size) {
                    val prev = sorted[i - 1]
                    val cur = sorted[i]
                    val cx = x(cur.startMinute)
                    drawLine(
                        color = Color(0xFFD2CDFF).copy(alpha = 0.46f),
                        start = Offset(cx, laneY(prev.stageRaw)),
                        end = Offset(cx, laneY(cur.stageRaw)),
                        strokeWidth = 1.2.dp.toPx(),
                        cap = StrokeCap.Round,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.5.dp.toPx(), 3.dp.toPx())),
                    )
                }
                // Horizontal segment per block: soft halo underlay + solid line.
                sorted.forEach { block ->
                    val y = laneY(block.stageRaw)
                    val startX = x(block.startMinute)
                    val endX = x(block.startMinute + block.durationMinutes).coerceAtLeast(startX)
                    val color = stageColor(block.stageRaw)
                    drawLine(
                        color = color.copy(alpha = 0.16f),
                        start = Offset(startX, y), end = Offset(endX, y),
                        strokeWidth = 12.dp.toPx(), cap = StrokeCap.Round,
                    )
                    drawLine(
                        color = color,
                        start = Offset(startX, y), end = Offset(endX, y),
                        strokeWidth = 6.5.dp.toPx(), cap = StrokeCap.Round,
                    )
                }
            }
        }
        // Time ticks.
        Row(
            Modifier.fillMaxWidth().padding(start = 64.dp, end = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            ticks.forEach { Text(it, fontSize = 10.sp, color = PulseColors.textMuted) }
        }
    }
}

/** Three side-by-side stage duration cards (SleepStageSummaryCardsView in Swift). */
@Composable
private fun SleepStageSummaryCards(prefix: String = "", deep: String, light: String, awake: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        StageStat("${prefix}Deep", deep, PulseColors.stageDeep, Modifier.weight(1f))
        StageStat("${prefix}Light", light, PulseColors.stageLight, Modifier.weight(1f))
        StageStat("${prefix}Awake", awake, PulseColors.stageAwake, Modifier.weight(1f))
    }
}

@Composable
private fun StageStat(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier
            .clip(shape)
            .background(PulseColors.card)
            .border(1.dp, PulseColors.borderSubtle, shape)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(color))
            Text(
                label.uppercase(),
                fontSize = 10.sp, fontWeight = FontWeight.Medium,
                letterSpacing = 0.6.sp, color = PulseColors.textMuted,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            value,
            fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
            color = PulseColors.textPrimary,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

/**
 * Nightly-duration histogram with gradient bars, faint placeholders for untracked nights,
 * and a dashed goal line (SleepDurationHistogramChart in Swift).
 */
@Composable
private fun SleepDurationHistogram(
    bars: List<SleepBar>,
    goalMin: Int?,
    slim: Boolean,
    height: androidx.compose.ui.unit.Dp = 210.dp,
) {
    if (bars.isEmpty()) {
        Box(Modifier.fillMaxWidth().height(height), contentAlignment = Alignment.Center) {
            Text("No nights tracked in this range yet.", fontSize = 12.sp, color = PulseColors.textMuted)
        }
        return
    }
    val yMax = maxOf(bars.mapNotNull { it.durationMin }.maxOrNull() ?: 0, goalMin ?: 0)
        .let { if (it > 0) it * 1.15f else 600f }
    val labelInterval = if (bars.size > 14) maxOf(1, bars.size / 6) else 1

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(Modifier.fillMaxWidth().height(height - 24.dp)) {
            val slotW = size.width / bars.size
            val barW = if (slim) 7.dp.toPx() else (slotW * 0.7f).coerceAtMost(30.dp.toPx())
            val r = if (slim) 3.dp.toPx() else 6.dp.toPx()
            bars.forEachIndexed { i, bar ->
                val cx = slotW * i + slotW / 2
                val duration = if (bar.present) bar.durationMin else null
                val h = if (duration != null) (duration / yMax) * size.height else size.height
                val top = size.height - h
                val brush = if (duration != null) {
                    Brush.verticalGradient(listOf(Color(0xFF8B7CFF), Color(0xFF3F2DD8)), startY = top)
                } else {
                    Brush.verticalGradient(listOf(PulseColors.accent.copy(alpha = 0.05f), PulseColors.accent.copy(alpha = 0.05f)))
                }
                drawRoundRect(
                    brush = brush,
                    topLeft = Offset(cx - barW / 2, top),
                    size = androidx.compose.ui.geometry.Size(barW, h),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r),
                )
            }
            if (goalMin != null && goalMin > 0) {
                val gy = size.height - (goalMin / yMax) * size.height
                drawLine(
                    color = PulseColors.textMuted.copy(alpha = 0.5f),
                    start = Offset(0f, gy), end = Offset(size.width, gy),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())),
                )
            }
        }
        Row(Modifier.fillMaxWidth()) {
            bars.forEachIndexed { i, bar ->
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    if (i % labelInterval == 0) {
                        Text(bar.label, fontSize = 9.sp, color = PulseColors.textMuted, maxLines = 1)
                    }
                }
            }
        }
    }
}

// ─────────────────────────── Day navigation (iOS #84) ───────────────────────────

/** ‹ older · [Today ▾] · newer › date stepper above the Day content. Horizontal swipe is owned by
 *  the session carousel, so days move only via the chevrons or the date picker. */
@Composable
private fun SleepDayNavHeader(state: SleepViewModel.SleepState, viewModel: SleepViewModel?) {
    var showPicker by remember { mutableStateOf(false) }
    val canOlder = state.dayOffset < state.maxDayOffset
    val canNewer = state.dayOffset > 0

    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        NavChevron(Icons.Filled.ChevronLeft, "Previous day", canOlder) { viewModel?.stepDay(older = true) }
        Column(
            Modifier
                .weight(1f)
                .clip(RoundedCornerShape(12.dp))
                .clickable { showPicker = true }
                .padding(vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                dayLabel(state.shownDayMillis),
                fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = PulseColors.textPrimary,
            )
            val sub = daySubLabel(state.shownDayMillis)
            Text(
                sub.ifEmpty { " " },
                fontSize = 11.sp, fontWeight = FontWeight.Medium,
                letterSpacing = 0.5.sp, color = PulseColors.textSecondary,
            )
        }
        NavChevron(Icons.Filled.ChevronRight, "Next day", canNewer) { viewModel?.stepDay(older = false) }
    }

    if (showPicker) {
        SleepDayPickerDialog(
            shownDayMillis = state.shownDayMillis,
            maxDayOffset = state.maxDayOffset,
            onDismiss = { showPicker = false },
            onPick = { viewModel?.jumpToDay(it); showPicker = false },
        )
    }
}

@Composable
private fun NavChevron(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon, contentDescription = label,
            tint = if (enabled) PulseColors.textPrimary else PulseColors.textMuted.copy(alpha = 0.4f),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SleepDayPickerDialog(
    shownDayMillis: Long,
    maxDayOffset: Int,
    onDismiss: () -> Unit,
    onPick: (Long) -> Unit,
) {
    val today = com.pulseloop.util.TimeUtil.startOfTodayLocal()
    val floor = today - maxDayOffset * 86_400_000L
    // Material's DatePicker keys everything to UTC midnight, so the initial selection must be the
    // shown day's calendar date expressed at UTC midnight (not its local-midnight epoch, which can
    // fall on the previous UTC day west of Greenwich).
    val state = rememberDatePickerState(
        initialSelectedDateMillis = localDayKeyToUtcMillis(shownDayMillis),
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                // Read the picker's UTC calendar date, map to that same date's local midnight, bound.
                val local = utcMillisToLocalDayKey(utcTimeMillis)
                return local in floor..today
            }
        },
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { onPick(utcMillisToLocalDayKey(it)) } ?: onDismiss()
            }) { Text("Done") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) {
        DatePicker(state = state, title = null)
    }
}

/** The calendar date of a local-midnight key, expressed at UTC midnight (what DatePicker wants). */
private fun localDayKeyToUtcMillis(localDayMillis: Long): Long {
    val date = java.time.Instant.ofEpochMilli(localDayMillis).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
    return date.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
}

/** The local-midnight key for the calendar date a DatePicker reports as UTC-midnight millis. */
private fun utcMillisToLocalDayKey(utcMillis: Long): Long {
    val date = java.time.Instant.ofEpochMilli(utcMillis).atZone(java.time.ZoneOffset.UTC).toLocalDate()
    return date.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
}

/** Primary header label: Today / Yesterday / weekday (< 7d) / abbreviated date. */
private fun dayLabel(dayMillis: Long): String {
    val today = com.pulseloop.util.TimeUtil.startOfTodayLocal()
    val daysAgo = ((today - dayMillis) / 86_400_000L).toInt()
    val date = java.time.Instant.ofEpochMilli(dayMillis).atZone(java.time.ZoneId.systemDefault())
    return when {
        daysAgo <= 0 -> "Today"
        daysAgo == 1 -> "Yesterday"
        daysAgo < 7 -> date.format(java.time.format.DateTimeFormatter.ofPattern("EEEE"))
        date.year == java.time.Year.now().value -> date.format(java.time.format.DateTimeFormatter.ofPattern("EEE, MMM d"))
        else -> date.format(java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy"))
    }
}

/** Secondary line: an absolute date when the primary line is relative, else empty. */
private fun daySubLabel(dayMillis: Long): String {
    val today = com.pulseloop.util.TimeUtil.startOfTodayLocal()
    val daysAgo = ((today - dayMillis) / 86_400_000L).toInt()
    if (daysAgo >= 7) return ""
    val date = java.time.Instant.ofEpochMilli(dayMillis).atZone(java.time.ZoneId.systemDefault())
    return date.format(java.time.format.DateTimeFormatter.ofPattern("EEE, MMM d")).uppercase(java.util.Locale.ROOT)
}

// ─────────────────────────── Helpers ───────────────────────────

private fun clockTime(ts: Long): String = SleepFormat.clockTime(ts)

private fun qualityText(label: SleepQualityLabel): String = when (label) {
    SleepQualityLabel.EXCELLENT -> "Excellent"
    SleepQualityLabel.GOOD -> "Good"
    SleepQualityLabel.FAIR -> "Fair"
    SleepQualityLabel.NEEDS_WORK -> "Needs work"
}

internal fun parseChipsJson(json: String?): List<String> {
    if (json.isNullOrBlank()) return emptyList()
    return try {
        kotlinx.serialization.json.Json.parseToJsonElement(json)
            .let { it as? kotlinx.serialization.json.JsonArray ?: return emptyList() }
            .mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
    } catch (_: Exception) {
        emptyList()
    }
}
