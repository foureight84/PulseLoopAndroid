package com.pulseloop.widgets

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.ui.graphics.toArgb
import com.pulseloop.service.MetricZone
import com.pulseloop.service.MetricKind
import com.pulseloop.ui.components.ActivityRing
import com.pulseloop.ui.components.ZoneLineSplitter
import com.pulseloop.ui.components.toColor
import com.pulseloop.ui.theme.PulseColors
import java.text.DateFormat
import java.util.Date
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Bitmap renderer for the widget tile bodies, ported from PulseLoopWidgets/WidgetTiles.swift.
 * Glance has its own composition and cannot host the app's Compose canvases, so each tile body is
 * drawn into a [Bitmap] with android.graphics and shown via `Image(ImageProvider(bitmap))`. The
 * drawing math is the same as the app's Canvas composables (ActivityRings, ZoneLineChart,
 * VitalRingGauge, SleepStageBar) — zone/threshold interpretation arrives pre-baked in the
 * [WidgetSnapshot] payloads, and geometry (270° gauge, ring insets, gap/threshold line splitting
 * via [ZoneLineSplitter]) matches the in-app components. Widgets always use the PulseLoop dark
 * palette regardless of system theme.
 */
class WidgetTileRenderer(private val density: Float) {

    private fun dp(v: Float): Float = v * density

    // Dark-palette colors (always, matching the in-app cards).
    private val textPrimary = PulseColors.textPrimary.toArgb()
    private val textMuted = PulseColors.textMuted.toArgb()
    private val trackColor = 0x1AFFFFFF // white @ 10%, the in-app ring/gauge track

    private enum class Weight { MEDIUM, SEMIBOLD, BOLD }

    private fun textPaint(
        sizeDp: Float,
        color: Int,
        weight: Weight = Weight.MEDIUM,
        mono: Boolean = false,
        trackingEm: Float = 0f,
    ): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        textSize = dp(sizeDp)
        typeface = when (weight) {
            Weight.MEDIUM, Weight.SEMIBOLD -> Typeface.create("sans-serif-medium", Typeface.NORMAL)
            Weight.BOLD -> Typeface.create("sans-serif", Typeface.BOLD)
        }
        if (mono) fontFeatureSettings = "'tnum'" // tabular figures ≈ monospacedDigit
        if (trackingEm != 0f) letterSpacing = trackingEm
    }

    private fun fillPaint(color: Int): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }

    private fun strokePaint(color: Int, widthPx: Float, round: Boolean = false): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = widthPx
            strokeCap = if (round) Paint.Cap.ROUND else Paint.Cap.BUTT
        }

    private fun withAlpha(color: Int, alpha: Float): Int =
        (color and 0x00FFFFFF) or (((alpha.coerceIn(0f, 1f) * 255).toInt()) shl 24)

    private fun textHeight(paint: Paint): Float =
        paint.fontMetrics.let { it.descent - it.ascent }

    /** Draws [text] with its TOP at [top]; returns the text height. */
    private fun drawTextTop(canvas: Canvas, text: String, x: Float, top: Float, paint: Paint): Float {
        canvas.drawText(text, x, top - paint.fontMetrics.ascent, paint)
        return textHeight(paint)
    }

    /** Shrinks the paint's textSize until [text] fits [maxWidth] (imitates minimumScaleFactor). */
    private fun fitText(paint: Paint, text: String, maxWidth: Float, minScale: Float) {
        if (maxWidth <= 0) return
        val original = paint.textSize
        var size = original
        while (size > original * minScale && paint.measureText(text) > maxWidth) {
            size *= 0.95f
            paint.textSize = size
        }
    }

    private fun formatTime(epochSeconds: Long): String =
        DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(epochSeconds * 1000L))

    // ─────────────────────────── Entry points ───────────────────────────

    /** The fixed full-width Daily Activity widget body (iOS WidgetActivityFullContent). */
    fun renderActivityWide(width: Int, height: Int, snapshot: WidgetSnapshot?, nowMs: Long): Bitmap {
        val bitmap = Bitmap.createBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val rect = RectF(0f, 0f, width.toFloat(), height.toFloat())
        val payload = snapshot?.activity
        val rolledOver = snapshot?.isRolledOver(nowMs) ?: false

        if (payload == null || rolledOver) {
            drawEmptyMessage(
                canvas, rect,
                if (rolledOver) "No data yet today" else "Open PulseLoop to sync",
                PulseColors.steps.toArgb(),
            )
        } else {
            drawActivityWideContent(canvas, rect, payload)
        }
        // Staleness time in the top-right corner (iOS overlay alignment: .topTrailing).
        snapshot?.stalenessEpochSeconds(nowMs)?.let { stale ->
            val p = textPaint(9f, withAlpha(textMuted, 0.8f))
            val text = formatTime(stale)
            drawTextTop(canvas, text, rect.right - p.measureText(text), rect.top, p)
        }
        return bitmap
    }

    /** One configurable metric tile (the small widget, and each half of the dual widget). */
    fun renderMetricTile(width: Int, height: Int, metric: WidgetMetric, snapshot: WidgetSnapshot?, nowMs: Long): Bitmap {
        val bitmap = Bitmap.createBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        drawMetricTile(Canvas(bitmap), RectF(0f, 0f, width.toFloat(), height.toFloat()), metric, snapshot, nowMs)
        return bitmap
    }

    /** Two metric tiles side by side with a 16 dp gap (iOS PulseLoopDualMetricWidget). */
    fun renderDualTile(
        width: Int, height: Int,
        left: WidgetMetric, right: WidgetMetric,
        snapshot: WidgetSnapshot?, nowMs: Long,
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val gap = dp(16f)
        val tileW = (width - gap) / 2f
        drawMetricTile(canvas, RectF(0f, 0f, tileW, height.toFloat()), left, snapshot, nowMs)
        drawMetricTile(canvas, RectF(tileW + gap, 0f, width.toFloat(), height.toFloat()), right, snapshot, nowMs)
        return bitmap
    }

    // ─────────────────────────── Tile dispatcher (iOS WidgetMetricTileView) ───────────────────────────

    private fun drawMetricTile(canvas: Canvas, rect: RectF, metric: WidgetMetric, snapshot: WidgetSnapshot?, nowMs: Long) {
        val accent = metric.accentColor.toArgb()
        val staleText = snapshot?.stalenessEpochSeconds(nowMs)?.let(::formatTime)
        val headerH = drawHeader(canvas, rect, metric.headerLabel, accent, staleText)
        val content = RectF(rect.left, rect.top + headerH + dp(8f), rect.right, rect.bottom)
        val rolledOver = snapshot?.isRolledOver(nowMs) ?: false

        when (metric.tileStyle) {
            WidgetMetric.TileStyle.RINGS -> drawActivitySmallContent(canvas, content, snapshot?.activity, rolledOver)
            WidgetMetric.TileStyle.SLEEP -> drawSleepContent(canvas, content, snapshot?.sleep, rolledOver)
            else -> {
                val payload = snapshot?.metrics?.get(metric.metricKind?.widgetKey)
                if (payload == null) {
                    drawEmptyMessage(
                        canvas, content,
                        if (snapshot == null) "Open PulseLoop to sync" else "No data",
                        accent,
                    )
                } else {
                    when (metric.tileStyle) {
                        WidgetMetric.TileStyle.CHART -> drawChartContent(canvas, content, payload)
                        WidgetMetric.TileStyle.GAUGE -> drawGaugeContent(canvas, content, payload)
                        else -> drawBloodPressureContent(canvas, content, payload)
                    }
                }
            }
        }
    }

    /** The eyebrow header: glowing 8 dp dot + 11 sp tracked uppercase label (+ staleness time). */
    private fun drawHeader(canvas: Canvas, rect: RectF, label: String, accent: Int, staleText: String?): Float {
        val headerH = dp(14f)
        val dotR = dp(4f)
        val cy = rect.top + headerH / 2f
        // Glow, then the solid dot (software canvas → BlurMaskFilter works).
        val glow = fillPaint(withAlpha(accent, 0.7f)).apply {
            maskFilter = BlurMaskFilter(dp(5f), BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawCircle(rect.left + dotR, cy, dotR + dp(0.5f), glow)
        canvas.drawCircle(rect.left + dotR, cy, dotR, fillPaint(accent))

        var rightLimit = rect.right
        if (staleText != null) {
            val p = textPaint(9f, withAlpha(textMuted, 0.8f))
            val w = p.measureText(staleText)
            canvas.drawText(staleText, rect.right - w, cy - (p.fontMetrics.ascent + p.fontMetrics.descent) / 2f, p)
            rightLimit = rect.right - w - dp(4f)
        }
        val labelPaint = textPaint(11f, textMuted, Weight.MEDIUM, trackingEm = 0.6f / 11f)
        val text = label.uppercase()
        fitText(labelPaint, text, rightLimit - (rect.left + dotR * 2 + dp(8f)), 0.7f)
        canvas.drawText(text, rect.left + dotR * 2 + dp(8f), cy - (labelPaint.fontMetrics.ascent + labelPaint.fontMetrics.descent) / 2f, labelPaint)
        return headerH
    }

    /** Centered muted message for missing data / first-run states (iOS WidgetEmptyMessage). */
    private fun drawEmptyMessage(canvas: Canvas, rect: RectF, message: String, accent: Int) {
        // In place of the iOS SF Symbol: a small accent ring + core dot status mark.
        val markR = dp(9f)
        val msgPaint = textPaint(13f, textPrimary, Weight.MEDIUM)
        fitText(msgPaint, message, rect.width(), 0.7f)
        val totalH = markR * 2 + dp(6f) + textHeight(msgPaint)
        val top = rect.top + (rect.height() - totalH) / 2f
        val cx = rect.centerX()
        canvas.drawCircle(cx, top + markR, markR, strokePaint(withAlpha(accent, 0.7f), dp(2f)))
        canvas.drawCircle(cx, top + markR, dp(3f), fillPaint(withAlpha(accent, 0.7f)))
        drawTextTop(canvas, message, cx - msgPaint.measureText(message) / 2f, top + markR * 2 + dp(6f), msgPaint)
    }

    // ─────────────────────────── Activity ───────────────────────────

    private fun activityRings(payload: WidgetActivityPayload?): List<ActivityRing> {
        return listOf(
            ActivityRing(payload?.steps, payload?.stepsGoal ?: 1.0, PulseColors.steps),
            ActivityRing(payload?.distanceDisplay, payload?.distanceGoalDisplay ?: 1.0, PulseColors.distance),
            ActivityRing(payload?.calories, payload?.caloriesGoal ?: 1.0, PulseColors.calories),
        )
    }

    /** Ports ActivityRings' Canvas math: concentric tracks + rounded progress arcs from 12 o'clock. */
    private fun drawActivityRings(
        canvas: Canvas, cx: Float, cy: Float,
        sizePx: Float, strokePx: Float, spacingPx: Float,
        rings: List<ActivityRing>,
    ) {
        rings.forEachIndexed { index, ring ->
            val inset = index * (strokePx + spacingPx)
            val diameter = sizePx - inset * 2 - strokePx
            if (diameter <= 0) return@forEachIndexed
            val arc = RectF(cx - diameter / 2, cy - diameter / 2, cx + diameter / 2, cy + diameter / 2)
            canvas.drawArc(arc, 0f, 360f, false, strokePaint(trackColor, strokePx, round = true))
            val sweep = (360.0 * ring.progress).toFloat()
            if (sweep > 0f) {
                canvas.drawArc(arc, -90f, sweep, false, strokePaint(ring.color.toArgb(), strokePx, round = true))
            }
        }
    }

    /** Small-tile activity body: 88 dp rings + stacked 10 sp labels / 26 sp values (iOS WidgetActivityContent). */
    private fun drawActivitySmallContent(canvas: Canvas, rect: RectF, payload: WidgetActivityPayload?, rolledOver: Boolean) {
        val values: List<Triple<String, String, Int>> = if (payload == null || rolledOver) emptyList() else buildList {
            payload.stepsText?.let { add(Triple("STEPS", it, PulseColors.steps.toArgb())) }
            payload.distanceText?.let { add(Triple(payload.distanceUnitLabel, it, PulseColors.distance.toArgb())) }
            payload.caloriesText?.let { add(Triple("CAL", it, PulseColors.calories.toArgb())) }
        }
        if (values.isEmpty()) {
            drawEmptyMessage(
                canvas, rect,
                if (rolledOver) "No data yet today" else "Open PulseLoop to sync",
                PulseColors.steps.toArgb(),
            )
            return
        }

        val ringSize = min(dp(88f), rect.height())
        drawActivityRings(
            canvas, rect.left + ringSize / 2, rect.centerY(),
            ringSize, dp(9f), dp(4f), activityRings(payload),
        )

        val textLeft = rect.left + ringSize + dp(10f)
        val maxW = rect.right - textLeft
        val rowHeights = values.map { (label, value, _) ->
            textHeight(textPaint(10f, 0)) + textHeight(textPaint(26f, 0))
        }
        val totalH = rowHeights.sum() + dp(4f) * (values.size - 1)
        var y = rect.centerY() - totalH / 2f
        values.forEach { (label, value, color) ->
            val labelPaint = textPaint(10f, color, Weight.SEMIBOLD, trackingEm = 0.8f / 10f)
            y += drawTextTop(canvas, label, textLeft, y, labelPaint)
            val valuePaint = textPaint(26f, textPrimary, Weight.SEMIBOLD, mono = true)
            fitText(valuePaint, value, maxW, 0.6f)
            y += drawTextTop(canvas, value, textLeft, y, valuePaint)
            y += dp(4f)
        }
    }

    /** Wide activity body: labeled metric stacks left, 112 dp rings right (iOS WidgetActivityFullContent). */
    private fun drawActivityWideContent(canvas: Canvas, rect: RectF, payload: WidgetActivityPayload) {
        val ringSize = min(dp(112f), rect.height())
        val ringCx = rect.right - ringSize / 2
        drawActivityRings(canvas, ringCx, rect.centerY(), ringSize, dp(11f), dp(5f), activityRings(payload))

        val textRight = rect.right - ringSize - dp(12f)
        val colW = (textRight - rect.left) / 2f

        fun metricBlock(x: Float, top: Float, label: String, value: String?, unit: String?, color: Int, maxW: Float): Float {
            val labelPaint = textPaint(15f, color, Weight.BOLD, trackingEm = 0.6f / 15f)
            fitText(labelPaint, label.uppercase(), maxW, 0.7f)
            var y = top + drawTextTop(canvas, label.uppercase(), x, top, labelPaint) + dp(5f)
            val valuePaint = textPaint(32f, textPrimary, Weight.SEMIBOLD, mono = true)
            val text = value ?: "—"
            val unitPaint = textPaint(14f, textMuted, Weight.MEDIUM)
            val unitW = if (unit != null && value != null) unitPaint.measureText(unit) + dp(4f) else 0f
            fitText(valuePaint, text, maxW - unitW, 0.6f)
            val baseline = y - valuePaint.fontMetrics.ascent
            canvas.drawText(text, x, baseline, valuePaint)
            if (unit != null && value != null) {
                canvas.drawText(unit, x + valuePaint.measureText(text) + dp(4f), baseline, unitPaint)
            }
            return (y + textHeight(valuePaint)) - top
        }

        val rowH = textHeight(textPaint(15f, 0)) + dp(5f) + textHeight(textPaint(32f, 0))
        val totalH = rowH * 2 + dp(14f)
        val top = rect.top + ((rect.height() - totalH) / 2f).coerceAtLeast(0f)
        metricBlock(rect.left, top, "Steps", payload.stepsText, null, PulseColors.steps.toArgb(), colW - dp(6f))
        metricBlock(
            rect.left + colW, top, "Distance", payload.distanceText,
            if (payload.distanceText != null) payload.distanceUnitLabel.lowercase() else null,
            PulseColors.distance.toArgb(), colW - dp(6f),
        )
        metricBlock(
            rect.left, top + rowH + dp(14f), "Calories", payload.caloriesText,
            if (payload.caloriesText != null) "cal" else null,
            PulseColors.calories.toArgb(), colW - dp(6f),
        )
    }

    // ─────────────────────────── Sleep ───────────────────────────

    private fun drawSleepContent(canvas: Canvas, rect: RectF, payload: WidgetSleepPayload?, rolledOver: Boolean) {
        if (payload == null || rolledOver) {
            drawEmptyMessage(canvas, rect, "No sleep recorded", PulseColors.sleep.toArgb())
            return
        }
        var y = rect.top
        val durationPaint = textPaint(30f, textPrimary, Weight.SEMIBOLD, mono = true)
        fitText(durationPaint, payload.durationText, rect.width(), 0.7f)
        y += drawTextTop(canvas, payload.durationText, rect.left, y, durationPaint) + dp(10f)

        y += drawSleepStageBar(canvas, RectF(rect.left, y, rect.right, y + dp(24f)), payload.segments) + dp(10f)

        val scorePaint = textPaint(32f, textPrimary, Weight.SEMIBOLD, mono = true)
        val scoreText = "${payload.score}"
        val baseline = y - scorePaint.fontMetrics.ascent
        canvas.drawText(scoreText, rect.left, baseline, scorePaint)
        val capPaint = textPaint(10f, textMuted, Weight.SEMIBOLD, trackingEm = 0.1f)
        canvas.drawText("SCORE", rect.left + scorePaint.measureText(scoreText) + dp(5f), baseline, capPaint)
    }

    /** Proportional stage bar + per-stage labels for segments ≥10% share (SleepStageBar parity). */
    private fun drawSleepStageBar(canvas: Canvas, rect: RectF, segments: List<WidgetSleepPayload.Segment>): Float {
        if (segments.isEmpty()) return 0f
        val total = segments.sumOf { it.minutes }.coerceAtLeast(1.0)
        val gap = dp(2f)
        val barH = dp(12f)
        val usable = rect.width() - gap * (segments.size - 1)
        var x = rect.left
        val labelTops = mutableListOf<Triple<Float, Float, WidgetSleepPayload.Segment>>() // x, width, seg
        segments.forEach { seg ->
            val w = ((seg.minutes / total) * usable).toFloat().coerceAtLeast(dp(1f))
            val color = colorFromHex(seg.colorHex).toArgb()
            val r = barH / 4f // rounded segment corners (3 dp at 12 dp height)
            canvas.drawRoundRect(RectF(x, rect.top, x + w, rect.top + barH), r, r, fillPaint(color))
            labelTops.add(Triple(x, w, seg))
            x += w + gap
        }
        val labelTop = rect.top + barH + dp(3f)
        var labelH = 0f
        labelTops.forEach { (lx, lw, seg) ->
            if (seg.minutes / total >= 0.10) {
                val p = textPaint(9f, colorFromHex(seg.colorHex).toArgb(), Weight.SEMIBOLD, trackingEm = 0.5f / 9f)
                fitText(p, seg.label, lw, 0.75f)
                if (p.measureText(seg.label) <= lw) {
                    labelH = maxOf(labelH, drawTextTop(canvas, seg.label, lx, labelTop, p))
                }
            }
        }
        return barH + dp(3f) + labelH
    }

    // ─────────────────────────── Zone chart (iOS WidgetChartContent) ───────────────────────────

    private fun drawChartContent(canvas: Canvas, rect: RectF, payload: WidgetMetricPayload) {
        // Value row at the top.
        var y = rect.top
        val valuePaint = textPaint(26f, textPrimary, Weight.SEMIBOLD, mono = true)
        val unitPaint = textPaint(11f, textMuted, Weight.MEDIUM)
        val unitW = payload.unitText?.let { unitPaint.measureText(it) + dp(4f) } ?: 0f
        fitText(valuePaint, payload.valueText, rect.width() - unitW, 0.7f)
        val baseline = y - valuePaint.fontMetrics.ascent
        canvas.drawText(payload.valueText, rect.left, baseline, valuePaint)
        payload.unitText?.let {
            canvas.drawText(it, rect.left + valuePaint.measureText(payload.valueText) + dp(4f), baseline, unitPaint)
        }
        y += textHeight(valuePaint)

        if (payload.isEmpty) {
            val p = textPaint(12f, textMuted)
            fitText(p, payload.statusText, rect.width(), 0.7f)
            drawTextTop(canvas, payload.statusText, rect.left, rect.bottom - textHeight(p), p)
            return
        }
        val chartH = min(dp(56f), rect.bottom - y - dp(2f))
        if (chartH > 0) {
            drawZoneChart(canvas, RectF(rect.left, rect.bottom - chartH, rect.right, rect.bottom), payload)
        }
    }

    /** Ports ZoneLineChart's canvas: bands, dashed rules, gap-broken threshold-colored line, points. */
    private fun drawZoneChart(canvas: Canvas, rect: RectF, payload: WidgetMetricPayload) {
        val samples = payload.samples
        if (samples.size < 2) return
        val minTs = samples.first().t
        val maxTs = samples.last().t
        val span = (maxTs - minTs).coerceAtLeast(1)
        val domainSpan = (payload.yUpper - payload.yLower).coerceAtLeast(0.001)

        fun x(t: Long): Float = rect.left + (t - minTs).toFloat() / span * rect.width()
        fun y(v: Double): Float =
            rect.top + rect.height() * (1f - ((v - payload.yLower) / domainSpan).toFloat().coerceIn(0f, 1f))

        fun lineColor(value: Double): Int = colorFromHex(payload.lineColorHex(value)).toArgb()

        // Reference bands behind everything.
        payload.referenceBands.forEach { band ->
            val color = vitalColorTokenFrom(band.colorToken).toColor().toArgb()
            canvas.drawRect(
                RectF(rect.left, y(band.upper), rect.right, y(band.lower)),
                fillPaint(withAlpha(color, band.opacity.toFloat())),
            )
        }
        // Dashed rules.
        payload.dashedRules.forEach { rule ->
            val ry = y(rule)
            val p = strokePaint(withAlpha(textMuted, 0.5f), dp(1f)).apply {
                pathEffect = DashPathEffect(floatArrayOf(dp(4f), dp(4f)), 0f)
            }
            canvas.drawLine(rect.left, ry, rect.right, ry, p)
        }
        // Zone-colored line, broken across data gaps (90 min — the card-chart gap on 24h ranges).
        val timestampsMs = samples.map { it.t * 1000L }
        val runs = ZoneLineSplitter.segmentsByGap(timestampsMs, 90 * 60_000L)
        runs.forEach { run ->
            if (run.first == run.last) {
                val s = samples[run.first]
                canvas.drawCircle(x(s.t), y(s.v), dp(2f), fillPaint(lineColor(s.v)))
            }
            for (i in (run.first + 1)..run.last) {
                val a = samples[i - 1]
                val b = samples[i]
                ZoneLineSplitter.split(a.t.toDouble(), a.v, b.t.toDouble(), b.v, payload.thresholds)
                    .forEach { (p0, p1) ->
                        val mid = (p0.value + p1.value) / 2
                        canvas.drawLine(
                            x(p0.x.toLong()), y(p0.value), x(p1.x.toLong()), y(p1.value),
                            strokePaint(lineColor(mid), dp(3f), round = true),
                        )
                    }
            }
        }
        // Sample dots for SpO₂ only (iOS showPoints: metricKind == .spo2).
        if (payload.kind == MetricKind.SPO2.widgetKey) {
            samples.forEach { s ->
                canvas.drawCircle(x(s.t), y(s.v), dp(3f), fillPaint(lineColor(s.v)))
            }
        }
    }

    // ─────────────────────────── Ring gauge (iOS WidgetGaugeContent) ───────────────────────────

    private fun drawGaugeContent(canvas: Canvas, rect: RectF, payload: WidgetMetricPayload) {
        val value = payload.latestValue
        if (value == null || payload.isEmpty) {
            drawEmptyMessage(canvas, rect, payload.statusText, colorFromHex(payload.statusColorHex).toArgb())
            return
        }
        // The in-app tile uses a fixed 118 dp gauge; size to the available square, capped at 118.
        val size = min(dp(118f), min(rect.width(), rect.height()))
        drawGauge(
            canvas, rect.centerX(), rect.centerY(), size,
            lineWidthPx = size * (11f / 118f),
            value = value,
            lower = payload.yLower, upper = payload.yUpper,
            zones = payload.zones.map { it.toMetricZone() },
            valueColor = colorFromHex(payload.statusColorHex).toArgb(),
            centerValue = payload.valueText,
            centerStatus = payload.statusText,
        )
    }

    private fun drawBloodPressureContent(canvas: Canvas, rect: RectF, payload: WidgetMetricPayload) {
        val sys = payload.systolic
        val dia = payload.diastolic
        if (payload.isEmpty || sys == null || dia == null) {
            drawEmptyMessage(canvas, rect, payload.statusText, PulseColors.bloodPressure.toArgb())
            return
        }
        val colW = rect.width() / 2f
        drawBpColumn(
            canvas, RectF(rect.left, rect.top, rect.left + colW, rect.bottom),
            "SYS", sys, 80.0, 190.0,
            payload.systolicZones.map { it.toMetricZone() },
            PulseColors.bloodPressure.toArgb(),
        )
        drawBpColumn(
            canvas, RectF(rect.left + colW, rect.top, rect.right, rect.bottom),
            "DIA", dia, 50.0, 130.0,
            payload.diastolicZones.map { it.toMetricZone() },
            withAlpha(PulseColors.bloodPressure.toArgb(), 0.7f),
        )
    }

    private fun drawBpColumn(
        canvas: Canvas, rect: RectF,
        title: String, value: Double, lower: Double, upper: Double,
        zones: List<MetricZone>, fallbackColor: Int,
    ) {
        val valueColor = zones.firstOrNull { it.contains(value) }
            ?.colorToken?.toColor()?.toArgb()
            ?: fallbackColor
        val caption = textPaint(10f, textMuted, Weight.SEMIBOLD, trackingEm = 0.8f / 10f)
        val captionH = textHeight(caption)
        val gaugeSize = min(dp(66f), min(rect.width(), rect.height() - captionH - dp(5f)))
        val totalH = gaugeSize + dp(5f) + captionH
        val top = rect.top + (rect.height() - totalH) / 2f
        drawGauge(
            canvas, rect.centerX(), top + gaugeSize / 2f, gaugeSize,
            lineWidthPx = dp(7f),
            value = value, lower = lower, upper = upper,
            zones = zones, valueColor = valueColor,
            centerValue = "${value.toInt()}",
            centerStatus = null,
        )
        drawTextTop(canvas, title, rect.centerX() - caption.measureText(title) / 2f, top + gaugeSize + dp(5f), caption)
    }

    /** Ports VitalRingGauge: 270° open-bottom arc with zone segments, value arc, marker, center stack. */
    private fun drawGauge(
        canvas: Canvas, cx: Float, cy: Float, sizePx: Float, lineWidthPx: Float,
        value: Double, lower: Double, upper: Double,
        zones: List<MetricZone>, valueColor: Int,
        centerValue: String, centerStatus: String?,
    ) {
        val startAngle = 135f
        val sweep = 270f
        fun fraction(v: Double): Double {
            val span = upper - lower
            if (span <= 0) return 0.0
            return ((v - lower) / span).coerceIn(0.0, 1.0)
        }
        fun angle(f: Double): Float = startAngle + sweep * f.toFloat()

        val inset = lineWidthPx / 2f
        val radius = sizePx / 2f - inset
        val arc = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
        fun tipCenter(f: Double): Pair<Float, Float> {
            val a = Math.toRadians(angle(f).toDouble())
            return (cx + (radius * cos(a)).toFloat()) to (cy + (radius * sin(a)).toFloat())
        }

        // Track.
        canvas.drawArc(arc, startAngle, sweep, false, strokePaint(trackColor, lineWidthPx, round = true))

        // Muted zone arcs, butt-capped at interior boundaries.
        for (zone in zones) {
            val lo = fraction(zone.lower ?: lower)
            val hi = fraction(zone.upper ?: upper)
            if (hi > lo) {
                val color = withAlpha(zone.colorToken.toColor().toArgb(), 0.32f)
                canvas.drawArc(arc, angle(lo), ((hi - lo) * sweep).toFloat(), false, strokePaint(color, lineWidthPx))
            }
        }
        // Rounded outer tips only at the two sweep ends (filled 180° wedges bulging outward).
        fun roundTip(f: Double, color: Int) {
            val (tx, ty) = tipCenter(f)
            val end = angle(f)
            val bulge = if (f >= 0.5) end + 90f else end - 90f
            canvas.drawArc(
                RectF(tx - lineWidthPx / 2, ty - lineWidthPx / 2, tx + lineWidthPx / 2, ty + lineWidthPx / 2),
                bulge - 90f, 180f, true, fillPaint(color),
            )
        }
        zones.firstOrNull()?.let { roundTip(0.0, withAlpha(it.colorToken.toColor().toArgb(), 0.32f)) }
        zones.lastOrNull()?.let { roundTip(1.0, withAlpha(it.colorToken.toColor().toArgb(), 0.32f)) }

        // Value arc + marker on the stroke centerline.
        val valueFraction = fraction(value)
        if (valueFraction > 0) {
            canvas.drawArc(arc, startAngle, (valueFraction * sweep).toFloat(), false, strokePaint(valueColor, lineWidthPx, round = true))
        }
        val (mx, my) = tipCenter(valueFraction)
        canvas.drawCircle(mx, my, lineWidthPx * 0.55f / 2f, fillPaint(textPrimary))

        // Center stack: value / status, scaled off the gauge size like the in-app gauge.
        val sizeDp = sizePx / density
        val valuePaint = textPaint(sizeDp * 0.30f, textPrimary, Weight.SEMIBOLD)
        val statusPaint = centerStatus?.let { textPaint(sizeDp * 0.08f, valueColor, Weight.SEMIBOLD, trackingEm = 0.08f) }
        val statusH = statusPaint?.let { textHeight(it) } ?: 0f
        val stackH = textHeight(valuePaint) + statusH
        var ty = cy - stackH / 2f
        fitText(valuePaint, centerValue, sizePx - lineWidthPx * 3, 0.6f)
        ty += drawTextTop(canvas, centerValue, cx - valuePaint.measureText(centerValue) / 2f, ty, valuePaint)
        if (centerStatus != null && statusPaint != null) {
            val text = centerStatus.uppercase()
            fitText(statusPaint, text, sizePx - lineWidthPx * 2.5f, 0.7f)
            drawTextTop(canvas, text, cx - statusPaint.measureText(text) / 2f, ty, statusPaint)
        }
    }
}
