package com.pulseloop.widgets

import android.content.Context
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Box
import androidx.glance.layout.ContentScale
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.unit.ColorProvider
import com.pulseloop.MainActivity
import com.pulseloop.ui.theme.PulseColors

// The three PulseLoop home-screen widgets (iOS #44 / PulseLoopWidgets.swift), as Glance app
// widgets. Each renders its tile body into a Bitmap via [WidgetTileRenderer] (Glance cannot host
// the app's Compose canvases) inside a dark PulseColors.card container, and opens the app on tap.
// Data comes from the snapshot JSON the app publishes ([WidgetSnapshotPublisher]); freshness is
// driven by the publisher's triggers plus [WidgetRefreshWorker] — updatePeriodMillis stays 0.

/** Per-glanceId configuration keys (Glance preferences state). */
object WidgetPrefKeys {
    val METRIC: Preferences.Key<String> = stringPreferencesKey("metric")
    val LEFT: Preferences.Key<String> = stringPreferencesKey("left")
    val RIGHT: Preferences.Key<String> = stringPreferencesKey("right")
}

/** Shared card chrome: dark card background, rounded corners (S+), tap-to-open, 14 dp padding. */
@Composable
private fun WidgetCard(content: @Composable () -> Unit) {
    var modifier = GlanceModifier
        .fillMaxSize()
        .background(ColorProvider(PulseColors.card))
        .clickable(actionStartActivity<MainActivity>())
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        modifier = modifier.cornerRadius(20.dp)
    }
    Box(modifier = modifier.padding(14.dp)) { content() }
}

@Composable
private fun TileBitmap(render: (widthPx: Int, heightPx: Int, density: Float) -> android.graphics.Bitmap) {
    val context = LocalContext.current
    val size = LocalSize.current
    val density = context.resources.displayMetrics.density
    // Inner content area = widget size minus the 14 dp card padding on each side.
    val widthPx = ((size.width.value - 28f) * density).toInt().coerceAtLeast(1)
    val heightPx = ((size.height.value - 28f) * density).toInt().coerceAtLeast(1)
    Image(
        provider = ImageProvider(render(widthPx, heightPx, density)),
        contentDescription = null,
        modifier = GlanceModifier.fillMaxSize(),
        contentScale = ContentScale.FillBounds,
    )
}

// ─────────────────────────── 1. Daily Activity (fixed, medium) ───────────────────────────

/** Steps / distance / calories labels on the left, the three concentric rings on the right. */
class PulseActivityWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snapshot = WidgetSnapshotStore.load(context)
        provideContent {
            WidgetCard {
                TileBitmap { w, h, density ->
                    WidgetTileRenderer(density).renderActivityWide(w, h, snapshot, System.currentTimeMillis())
                }
            }
        }
    }
}

class PulseActivityWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = PulseActivityWidget()
}

// ─────────────────────────── 2. Metric Tile (configurable, small) ───────────────────────────

/** One Today tile of your choice (configured via the widget's configure activity). */
class PulseMetricWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snapshot = WidgetSnapshotStore.load(context)
        provideContent {
            val prefs = currentState<Preferences>()
            val metric = WidgetMetric.fromKey(prefs[WidgetPrefKeys.METRIC]) ?: WidgetMetric.ACTIVITY
            WidgetCard {
                TileBitmap { w, h, density ->
                    WidgetTileRenderer(density).renderMetricTile(w, h, metric, snapshot, System.currentTimeMillis())
                }
            }
        }
    }
}

class PulseMetricWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = PulseMetricWidget()
}

// ─────────────────────────── 3. Dual Metric (configurable, medium) ───────────────────────────

/** Two Today tiles side by side on one continuous card, left/right independently configurable. */
class PulseDualMetricWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snapshot = WidgetSnapshotStore.load(context)
        provideContent {
            val prefs = currentState<Preferences>()
            val left = WidgetMetric.fromKey(prefs[WidgetPrefKeys.LEFT]) ?: WidgetMetric.ACTIVITY
            val right = WidgetMetric.fromKey(prefs[WidgetPrefKeys.RIGHT]) ?: WidgetMetric.HEART_RATE
            WidgetCard {
                TileBitmap { w, h, density ->
                    WidgetTileRenderer(density).renderDualTile(w, h, left, right, snapshot, System.currentTimeMillis())
                }
            }
        }
    }
}

class PulseDualMetricWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = PulseDualMetricWidget()
}
