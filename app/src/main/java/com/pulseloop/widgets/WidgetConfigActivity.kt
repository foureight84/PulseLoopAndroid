package com.pulseloop.widgets

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.lifecycle.lifecycleScope
import com.pulseloop.ui.theme.PulseColors
import com.pulseloop.ui.theme.PulseLoopTheme
import kotlinx.coroutines.launch

/**
 * The one APPWIDGET_CONFIGURE activity shared by the two configurable widgets (iOS uses
 * AppIntent-driven "Edit Widget" sheets; Android widgets configure through an activity). It shows
 * one metric picker for the Metric Tile widget, or left/right pickers for the Dual widget, writes
 * the choice into the widget's Glance preferences state, and triggers a render.
 */
class WidgetConfigActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        // Canceling out of configuration must abort the widget placement.
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        val providerClass = AppWidgetManager.getInstance(this)
            .getAppWidgetInfo(appWidgetId)?.provider?.className
        val isDual = providerClass == PulseDualMetricWidgetReceiver::class.java.name

        setContent {
            PulseLoopTheme {
                ConfigScreen(isDual = isDual) { single, left, right ->
                    lifecycleScope.launch {
                        val manager = GlanceAppWidgetManager(this@WidgetConfigActivity)
                        val glanceId = manager.getGlanceIdBy(appWidgetId)
                        updateAppWidgetState(this@WidgetConfigActivity, glanceId) { prefs ->
                            if (isDual) {
                                prefs[WidgetPrefKeys.LEFT] = left.key
                                prefs[WidgetPrefKeys.RIGHT] = right.key
                            } else {
                                prefs[WidgetPrefKeys.METRIC] = single.key
                            }
                        }
                        if (isDual) {
                            PulseDualMetricWidget().update(this@WidgetConfigActivity, glanceId)
                        } else {
                            PulseMetricWidget().update(this@WidgetConfigActivity, glanceId)
                        }
                        // Make sure a snapshot exists before the first render.
                        WidgetSnapshotPublisher.publish(this@WidgetConfigActivity)
                        setResult(
                            RESULT_OK,
                            Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId),
                        )
                        finish()
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfigScreen(
    isDual: Boolean,
    onDone: (single: WidgetMetric, left: WidgetMetric, right: WidgetMetric) -> Unit,
) {
    var single by remember { mutableStateOf(WidgetMetric.ACTIVITY) }
    var left by remember { mutableStateOf(WidgetMetric.ACTIVITY) }
    var right by remember { mutableStateOf(WidgetMetric.HEART_RATE) }

    Column(
        Modifier
            .fillMaxSize()
            .background(PulseColors.background)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            if (isDual) "Choose Metrics" else "Choose Metric",
            fontSize = 24.sp,
            fontWeight = FontWeight.SemiBold,
            color = PulseColors.textPrimary,
        )
        Text(
            if (isDual) "Pick the left and right PulseLoop metrics."
            else "Pick which PulseLoop metric this widget shows.",
            fontSize = 14.sp,
            color = PulseColors.textSecondary,
        )
        Spacer(Modifier.height(8.dp))

        if (isDual) {
            MetricPicker("LEFT METRIC", left) { left = it }
            Spacer(Modifier.height(12.dp))
            MetricPicker("RIGHT METRIC", right) { right = it }
        } else {
            MetricPicker(null, single) { single = it }
        }

        Spacer(Modifier.height(16.dp))
        Button(onClick = { onDone(single, left, right) }, modifier = Modifier.fillMaxWidth()) {
            Text("Add Widget")
        }
    }
}

@Composable
private fun MetricPicker(title: String?, selected: WidgetMetric, onSelect: (WidgetMetric) -> Unit) {
    Column {
        if (title != null) {
            Text(
                title,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 1.sp,
                color = PulseColors.textMuted,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        WidgetMetric.entries.forEach { metric ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(metric) }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = metric == selected, onClick = { onSelect(metric) })
                Spacer(Modifier.width(4.dp))
                Text(metric.displayName, fontSize = 15.sp, color = PulseColors.textPrimary)
            }
        }
    }
}
