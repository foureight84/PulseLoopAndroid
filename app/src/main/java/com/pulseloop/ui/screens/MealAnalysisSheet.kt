package com.pulseloop.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.pulseloop.coach.attachments.CoachAttachmentStore
import com.pulseloop.coach.attachments.CoachImagePayload
import com.pulseloop.coach.config.CoachClientResolver
import com.pulseloop.coach.config.CoachProviderSettingsStore
import com.pulseloop.coach.openai.OpenAIRequestBuilder
import com.pulseloop.coach.tools.NutritionTools
import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.data.entity.MealEntryEntity
import com.pulseloop.settings.ApiKeyStore
import com.pulseloop.ui.theme.PulseColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.io.InputStream
import java.util.UUID

/**
 * In-context AI meal analysis from the Nutrition page — the port of MealAnalysisSheet
 * (MealAnalysisSheet.swift, iOS PR #96): snap/pick a photo or describe the meal in text →
 * ONE single-shot structured LLM call ([MealEstimator], the CoachNotificationGenerator
 * pattern: system + user → strict JSON, no tools) → an *editable* prefilled estimate the
 * user reviews before saving.
 *
 * Deliberate divergences from iOS (both reported in ios-sync.md):
 * - iOS persists the meal photo via CoachAttachmentStore into photoRefJSON
 *   (MealAnalysisSheet.swift:296-307); Android's MealEntryEntity has NO photo-ref column,
 *   so the photo is used for the analysis call only and NOT persisted — no schema migration.
 * - iOS gates the entry buttons on coach + cloud-provider + a photo-analysis sub-toggle
 *   (NutritionView.swift:36-48); Android has neither an on-device provider mode nor that
 *   pref, so both entry buttons gate on the coach master toggle alone.
 */
private enum class AnalysisPhase { Input, Analyzing, Review, Failed }

@Composable
fun MealAnalysisDialog(
    /** Start-of-day millis of the selected nutrition day (iOS MealAnalysisSheet.day). */
    dayMillis: Long,
    startWithCamera: Boolean,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { PulseLoopDatabase.getInstance(context) }

    var phase by remember { mutableStateOf<AnalysisPhase>(AnalysisPhase.Input) }
    var failMessage by remember { mutableStateOf("") }
    var mealType by remember { mutableStateOf(MealAnalysisLogic.inferredMealType()) }
    var describeText by remember { mutableStateOf("") }
    var image by remember { mutableStateOf<Bitmap?>(null) }

    // Review-phase editable fields (prefilled from the estimate) — iOS :30-37.
    var name by remember { mutableStateOf("") }
    var calories by remember { mutableStateOf("") }
    var protein by remember { mutableStateOf("") }
    var carbs by remember { mutableStateOf("") }
    var fat by remember { mutableStateOf("") }
    var assumptions by remember { mutableStateOf("") }
    var confidence by remember { mutableStateOf("medium") }

    val canAnalyze = MealAnalysisLogic.canAnalyze(image != null, describeText)

    // ── Image acquisition ──────────────────────────────────────────────

    var captureFile by remember { mutableStateOf<File?>(null) }
    // Set when CAMERA was just granted, so the capture starts after recomposition (a local
    // fun cannot be referenced from a launcher callback declared above it).
    var pendingCameraStart by remember { mutableStateOf(false) }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val file = captureFile
        captureFile = null
        if (ok && file != null) {
            scope.launch {
                // Same subsample intent as CoachAttachmentStore.save(uri): a full-res 50 MP
                // decode would allocate ~200 MB.
                image = withContext(Dispatchers.IO) {
                    decodeSubsampled({ if (file.exists()) file.inputStream() else null })
                }
                file.delete()
            }
        }
    }
    // TakePicture needs CAMERA granted when the app declares it in its manifest.
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) pendingCameraStart = true }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                image = withContext(Dispatchers.IO) { decodeSubsampledUri(context, uri) }
            }
        }
    }

    fun launchCameraCapture() {
        val dir = File(context.filesDir, "camera_captures").apply { mkdirs() }
        val file = File(dir, "${UUID.randomUUID()}.jpg")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        captureFile = file
        cameraLauncher.launch(uri)
    }

    fun launchCamera() {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            return
        }
        launchCameraCapture()
    }

    // iOS .onAppear: preset meal type + start in camera mode (MealAnalysisSheet.swift:77-82).
    LaunchedEffect(startWithCamera) {
        if (startWithCamera && image == null && phase == AnalysisPhase.Input) launchCamera()
    }
    LaunchedEffect(pendingCameraStart) {
        if (pendingCameraStart) {
            pendingCameraStart = false
            launchCameraCapture()
        }
    }

    // ── Analysis + save ────────────────────────────────────────────────

    fun analyze() {
        phase = AnalysisPhase.Analyzing
        scope.launch {
            when (val result = MealEstimator.estimate(context, describeText, image)) {
                is MealEstimator.Result.Success -> {
                    // iOS analyze() (MealAnalysisSheet.swift:271-279): rounded ints as text.
                    name = result.estimate.name
                    calories = result.estimate.calories.roundedText()
                    protein = result.estimate.proteinG.roundedText()
                    carbs = result.estimate.carbsG.roundedText()
                    fat = result.estimate.fatG.roundedText()
                    assumptions = result.estimate.assumptions
                    confidence = result.estimate.confidence
                    phase = AnalysisPhase.Review
                }
                is MealEstimator.Result.Failure -> {
                    failMessage = result.message
                    phase = AnalysisPhase.Failed
                }
            }
        }
    }

    fun save() {
        val kcal = calories.toDoubleOrNull() ?: return
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) return
        scope.launch {
            // iOS save() (MealAnalysisSheet.swift:288-294): today → the current clock time,
            // a past day → noon. NutritionTools.resolveTimestamp(date, time = null) is exactly
            // that rule.
            val timestamp = NutritionTools.resolveTimestamp(
                date = java.time.Instant.ofEpochMilli(dayMillis)
                    .atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString(),
                time = null,
            )
            // DIVERGENCE (reported): iOS also persists the photo via CoachAttachmentStore into
            // photoRefJSON (MealAnalysisSheet.swift:296-307). MealEntryEntity has no photo-ref
            // column and this port deliberately ships no schema migration, so the photo is NOT
            // stored — it feeds the analysis call only.
            db.mealEntryDao().upsert(MealEntryEntity(
                date = dayMillis,
                timestamp = timestamp,
                name = trimmedName,
                mealTypeRaw = mealType,
                calories = kcal,
                proteinG = protein.toDoubleOrNull() ?: 0.0,
                carbsG = carbs.toDoubleOrNull() ?: 0.0,
                fatG = fat.toDoubleOrNull() ?: 0.0,
                sourceRaw = NutritionTools.sourceRawLlmEstimate,
                confidenceRaw = MealAnalysisLogic.confidenceRaw(confidence),
                notes = assumptions.trim().takeIf { it.isNotEmpty() }, // iOS :308
            ))
            onSaved()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .background(PulseColors.background)
                .systemBarsPadding(),
        ) {
            // iOS: navigationTitle "AI meal log" (inline) + Cancel (MealAnalysisSheet.swift:70-74).
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "AI meal log",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = PulseColors.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Meal-type picker — iOS segmented Picker (:51-54); Android uses the same
                // FilterChip row MealLogDialog uses.
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("breakfast", "lunch", "dinner", "snack").forEach { t ->
                        FilterChip(
                            selected = mealType == t,
                            onClick = { mealType = t },
                            label = { Text(t.replaceFirstChar { it.uppercase() }) },
                        )
                    }
                }

                when (phase) {
                    AnalysisPhase.Input -> {
                        val picked = image
                        if (picked != null) {
                            // iOS image preview + xmark remove overlay (:102-115).
                            Box(Modifier.fillMaxWidth()) {
                                Image(
                                    bitmap = picked.asImageBitmap(),
                                    contentDescription = null,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(220.dp)
                                        .background(PulseColors.cardSoft, RoundedCornerShape(14.dp)),
                                )
                                IconButton(
                                    onClick = { image = null },
                                    modifier = Modifier.align(Alignment.TopEnd),
                                ) {
                                    Icon(Icons.Filled.Close, "Remove photo", tint = Color.White)
                                }
                            }
                        } else {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                OutlinedButton(onClick = { launchCamera() }, modifier = Modifier.weight(1f)) {
                                    Icon(Icons.Filled.PhotoCamera, null, Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Camera")
                                }
                                OutlinedButton(
                                    onClick = {
                                        photoPicker.launch(
                                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                        )
                                    },
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Icon(Icons.Filled.PhotoLibrary, null, Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Library")
                                }
                            }
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                if (image == null) "OR DESCRIBE IT" else "ADD DETAIL (OPTIONAL)",
                                fontSize = 11.sp,
                                letterSpacing = 1.4.sp,
                                color = PulseColors.textMuted,
                            )
                            OutlinedTextField(
                                value = describeText,
                                onValueChange = { describeText = it },
                                placeholder = { Text("e.g. two eggs, toast with butter, black coffee") },
                                modifier = Modifier.fillMaxWidth(),
                                minLines = 2,
                            )
                        }

                        Button(
                            onClick = { analyze() },
                            enabled = canAnalyze,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = PulseColors.accent),
                        ) {
                            Icon(Icons.Filled.AutoAwesome, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Analyze")
                        }

                        // iOS disclaimer, verbatim (:145-148).
                        Text(
                            "Sent to your configured AI provider only when you tap Analyze. Estimates are labeled and editable.",
                            fontSize = 12.sp,
                            color = PulseColors.textMuted,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    AnalysisPhase.Analyzing -> {
                        if (image != null) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(180.dp)
                                    .background(PulseColors.cardSoft, RoundedCornerShape(14.dp)),
                            )
                        }
                        CircularProgressIndicator(color = PulseColors.accent, modifier = Modifier.size(28.dp))
                        Text("Estimating nutrition…", fontSize = 14.sp, color = PulseColors.textSecondary)
                    }

                    AnalysisPhase.Review -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // ProvenanceBadge(source: .llmEstimate) — "AI estimate", sparkles,
                            // accent (NutritionComponents.swift:231-264).
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = PulseColors.accent.copy(alpha = 0.14f),
                            ) {
                                Row(
                                    Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(Icons.Filled.AutoAwesome, null, tint = PulseColors.accent, modifier = Modifier.size(12.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        "AI estimate",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = PulseColors.accent,
                                    )
                                }
                            }
                            if (confidence != "high") {
                                // iOS confidence caption (:174-178).
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "${confidence.replaceFirstChar { it.uppercase() }} confidence",
                                    fontSize = 12.sp,
                                    color = PulseColors.textMuted,
                                )
                            }
                        }
                        if (assumptions.isNotBlank()) {
                            // iOS assumptions block (:181-188).
                            Text(
                                assumptions,
                                fontSize = 12.sp,
                                color = PulseColors.textSecondary,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(PulseColors.cardSoft, RoundedCornerShape(10.dp))
                                    .padding(12.dp),
                            )
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Review & adjust", fontWeight = FontWeight.SemiBold, color = PulseColors.calories)
                            EditRow("Name", name) { name = it }
                            EditRow("Calories (kcal)", calories) { calories = it }
                            EditRow("Protein (g)", protein) { protein = it }
                            EditRow("Carbs (g)", carbs) { carbs = it }
                            EditRow("Fat (g)", fat) { fat = it }
                        }
                        val canSave = MealAnalysisLogic.canSave(name, calories)
                        Button(
                            onClick = { save() },
                            enabled = canSave,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = PulseColors.accent),
                        ) {
                            Icon(Icons.Filled.Check, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Log meal")
                        }
                        TextButton(
                            onClick = { phase = AnalysisPhase.Input },
                            modifier = Modifier.align(Alignment.CenterHorizontally),
                        ) {
                            Icon(Icons.Filled.Refresh, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Re-analyze", color = PulseColors.accent)
                        }
                    }

                    AnalysisPhase.Failed -> {
                        // iOS failedPhase (:210-219).
                        Icon(
                            Icons.Filled.Warning,
                            null,
                            tint = PulseColors.warning,
                            modifier = Modifier.size(34.dp).align(Alignment.CenterHorizontally),
                        )
                        Text(
                            failMessage,
                            fontSize = 14.sp,
                            color = PulseColors.textSecondary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedButton(
                            onClick = { phase = AnalysisPhase.Input },
                            modifier = Modifier.align(Alignment.CenterHorizontally),
                        ) {
                            Icon(Icons.Filled.Refresh, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Try again")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EditRow(label: String, value: String, onChange: (String) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 14.sp, color = PulseColors.textSecondary, modifier = Modifier.weight(1f))
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            modifier = Modifier.width(140.dp),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
    }
}

/** iOS \(Int(value.rounded())) for the review fields (MealAnalysisSheet.swift:273-276). */
private fun Double.roundedText(): String = Math.round(this).toString()

/** Subsampled decode from a stream opener (two opens: bounds, then pixels) — the same
 *  power-of-two subsample CoachAttachmentStore.save(uri) applies before its downscale. */
private fun decodeSubsampled(open: () -> InputStream?): Bitmap? {
    val boundsStream = open() ?: return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    boundsStream.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    var longEdge = maxOf(bounds.outWidth, bounds.outHeight)
    while (longEdge / 2 >= 1024) {
        sample *= 2
        longEdge /= 2
    }
    return open()?.use { stream ->
        BitmapFactory.decodeStream(stream, null, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}

private fun decodeSubsampledUri(context: Context, uri: Uri): Bitmap? = try {
    decodeSubsampled({ context.contentResolver.openInputStream(uri) })
} catch (_: Exception) {
    null
}

/**
 * The pure decision logic of the meal-analysis sheet, split out of the composable so it is
 * unit-testable without Android classes — the same split NutritionTools makes for its tool
 * bodies. Ported from MealAnalysisSheet.swift (iOS #96).
 */
object MealAnalysisLogic {

    /**
     * iOS canAnalyze (MealAnalysisSheet.swift:39-41): an image, or a description whose
     * trimmed length is at least 3.
     */
    fun canAnalyze(hasImage: Boolean, description: String): Boolean =
        hasImage || description.trim().length >= 3

    /**
     * iOS canSave (MealAnalysisSheet.swift:43-45): a non-blank trimmed name AND numeric
     * calories (Double(calories) != nil).
     */
    fun canSave(name: String, calories: String): Boolean =
        name.trim().isNotEmpty() && calories.toDoubleOrNull() != null

    /**
     * iOS save()'s confidence mapping (MealAnalysisSheet.swift:306): high → known,
     * medium → partial, anything else → unknown. Identical to NutritionTools.decodeConfidenceRaw.
     */
    fun confidenceRaw(raw: String?): String = when (raw) {
        "high" -> "known"
        "medium" -> "partial"
        else -> "unknown"
    }

    /**
     * iOS MealType.inferred (NutritionModels.swift:20-27): hours 4–10 breakfast, 11–14 lunch,
     * 17–21 dinner, otherwise snack.
     */
    fun inferredMealType(hour: Int = java.time.LocalTime.now().hour): String = when (hour) {
        in 4..10 -> "breakfast"
        in 11..14 -> "lunch"
        in 17..21 -> "dinner"
        else -> "snack"
    }

    /** The strict meal_estimate response shape (MealAnalysisSheet.swift:321-334). */
    @Serializable
    data class Estimate(
        val name: String,
        val calories: Double,
        @SerialName("protein_g") val proteinG: Double,
        @SerialName("carbs_g") val carbsG: Double,
        @SerialName("fat_g") val fatG: Double,
        val assumptions: String = "",
        val confidence: String = "",
    )

    /**
     * Fence/prose-tolerant decode — ported from MealEstimator.decode (MealAnalysisSheet.swift:
     * 413-422): try the whole trimmed text as JSON; else slice from the first "{" to the last
     * "}" and try again. null when neither yields a usable estimate.
     */
    fun decode(text: String): Estimate? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        val json = Json { ignoreUnknownKeys = true }
        if (trimmed.startsWith("{")) {
            return try { json.decodeFromString(Estimate.serializer(), trimmed) } catch (_: Exception) { null }
        }
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return try {
            json.decodeFromString(Estimate.serializer(), trimmed.substring(start, end + 1))
        } catch (_: Exception) {
            null
        }
    }

    /** Verbatim system prompt (MealAnalysisSheet.swift:338-342). */
    val systemPrompt: String =
        "You are a nutrition estimator. Given a meal description and/or photo, " +
            "identify the food and estimate total calories and macros for the portion shown or described. " +
            "Be realistic about portion sizes; state your assumptions (portion sizes, preparation) briefly. " +
            "Use confidence \"high\" only for clearly identifiable, standard portions. " +
            "Return only JSON matching the schema."

    /**
     * The verbatim strict meal_estimate json_schema (MealAnalysisSheet.swift:344-362), built
     * once as the Responses-API text.format object.
     */
    fun textFormat(): JsonObject {
        fun described(value: String) = JsonObject(mapOf("type" to JsonPrimitive("string"), "description" to JsonPrimitive(value)))
        fun number() = JsonObject(mapOf("type" to JsonPrimitive("number")))
        val props = JsonObject(mapOf(
            "name" to described("Short display name for the meal"),
            "calories" to number(),
            "protein_g" to number(),
            "carbs_g" to number(),
            "fat_g" to number(),
            "assumptions" to described("Portion/preparation assumptions, one short sentence, or empty string"),
            "confidence" to JsonObject(mapOf(
                "type" to JsonPrimitive("string"),
                "enum" to JsonArray(listOf(JsonPrimitive("low"), JsonPrimitive("medium"), JsonPrimitive("high"))),
            )),
        ))
        val schema = JsonObject(mapOf(
            "type" to JsonPrimitive("object"),
            "properties" to props,
            "required" to JsonArray(listOf(
                "name", "calories", "protein_g", "carbs_g", "fat_g", "assumptions", "confidence",
            ).map { JsonPrimitive(it) }),
            "additionalProperties" to JsonPrimitive(false),
        ))
        return JsonObject(mapOf(
            "type" to JsonPrimitive("json_schema"),
            "name" to JsonPrimitive("meal_estimate"),
            "strict" to JsonPrimitive(true),
            "schema" to schema,
        ))
    }
}

/**
 * One-shot structured meal estimation against the user's configured provider — the port of
 * MealEstimator (MealAnalysisSheet.swift:319-422). Reuses the coach provider stack —
 * CoachClientResolver (cloud OR self-hosted local LLM) + strict json_schema text.format,
 * the CoachSummaryService resolution pattern — no new HTTP client, no tools.
 */
object MealEstimator {

    sealed class Result {
        data class Success(val estimate: MealAnalysisLogic.Estimate) : Result()
        data class Failure(val message: String) : Result()
    }

    suspend fun estimate(context: Context, description: String, image: Bitmap?): Result =
        withContext(Dispatchers.IO) {
            val appContext = context.applicationContext
            val keyStore = ApiKeyStore(appContext)
            // iOS flags.coachEnabled guard (MealAnalysisSheet.swift:376-378). Android has no
            // separate on-device provider mode, so the coach toggle + a READY provider is the
            // gate — resolved through CoachClientResolver like every other coach feature, so a
            // self-hosted local LLM (readiness = validated base URL, no key) qualifies the
            // same way it does for summaries and chat (CoachClientResolver.kt:59-76).
            val providerStore = CoachProviderSettingsStore(appContext)
            val resolution = CoachClientResolver.resolve(providerStore, keyStore)
            if (!keyStore.coachEnabled || resolution.key == null) {
                return@withContext Result.Failure(
                    "AI analysis needs the coach enabled with a configured provider (Settings → AI Coach). " +
                        "You can still search the database or enter the meal manually.",
                )
            }
            val model = CoachClientResolver.activeModel(providerStore.snapshot(), keyStore.model)

            val images = mutableListOf<CoachImagePayload>()
            if (image != null) {
                // Reuse the attachment pipeline's downscale/encode, then discard the temp file
                // (MealAnalysisSheet.swift:382-386).
                val ref = CoachAttachmentStore.save(appContext, image)
                if (ref != null) {
                    CoachAttachmentStore.payload(appContext, ref)?.let { images.add(it) }
                    CoachAttachmentStore.delete(appContext, ref)
                }
                if (images.isEmpty()) {
                    return@withContext Result.Failure("Couldn't process that photo. Try again or describe the meal.")
                }
            }

            // iOS :392 — the description, or the photo-only prompt.
            val prompt = description.trim().ifEmpty { "Estimate the nutrition of the meal in the photo." }
            val input = JsonArray(listOf(
                OpenAIRequestBuilder.message("system", MealAnalysisLogic.systemPrompt),
                OpenAIRequestBuilder.message("user", prompt, images),
            ))
            // iOS passes flags.settings.reasoningEffort through OpenAIRequestBuilder.data
            // (MealAnalysisSheet.swift:401); the model-aware overload also drops `reasoning`
            // for the legacy chat families that reject it outright.
            val requestBody = JsonObject(mapOf(
                "model" to JsonPrimitive(model),
                "input" to input,
                "text" to JsonObject(mapOf("format" to MealAnalysisLogic.textFormat())),
            ) + OpenAIRequestBuilder.reasoningParams(providerStore.snapshot().reasoningEffort, model))
            try {
                val response = resolution.client.send(requestBody.toString().toByteArray())
                val estimate = MealAnalysisLogic.decode(response.outputText)
                    ?: return@withContext Result.Failure(
                        "The AI didn't return a usable estimate. Try again or enter the meal manually.",
                    )
                Result.Success(estimate)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.Failure("Analysis failed: ${e.message ?: e.javaClass.simpleName}")
            }
        }
}
