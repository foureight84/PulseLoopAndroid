package com.pulseloop.health

import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity

/**
 * Target of the "privacy policy" link in the Health Connect permission sheet — mandatory for
 * any app requesting health data types (official get-started guide). Pre-34 devices fire
 * `androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE` at this activity directly; API 34+
 * reaches the `ViewPermissionUsageActivity` alias (manifest, guarded by
 * START_VIEW_PERMISSION_USAGE), which targets this same screen.
 *
 * In-app rationale, no hosted URL — the Gadgetbridge precedent for sideload-only
 * distribution (docs/health-connect-integration.md §2, caveat 2).
 */
class HealthConnectRationaleActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val title = TextView(this).apply {
            text = "How PulseLoop uses Health Connect"
            textSize = 18f
            setPadding(0, 0, 0, dp(12))
        }
        val body = TextView(this).apply {
            text = """
                PulseLoop can export your ring's health data — heart rate, oxygen saturation, heart rate variability, body temperature, sleep, steps, activity calories and distance, and finished workouts with GPS routes — to Health Connect, so other apps and dashboards you choose can show it.

                · The export is write-only: PulseLoop never reads data back from Health Connect.
                · Records carry a stable PulseLoop identifier, so re-exporting the same reading replaces it instead of duplicating it.
                · You control the export: the master toggle and per-data-type switches in Settings → Health Connect, and you can revoke any permission at any time in the Health Connect app.
            """.trimIndent()
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
            addView(title)
            addView(body)
        }
        setContentView(ScrollView(this).apply { addView(column) })
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
