package com.pulseloop.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulseloop.coach.tools.NutritionTools
import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.data.dao.MealTotals
import com.pulseloop.data.entity.MealEntryEntity
import com.pulseloop.data.entity.UserGoalEntity
import com.pulseloop.nutrition.FoodProductCache
import com.pulseloop.nutrition.NutritionMath
import com.pulseloop.nutrition.OpenFoodFactsClient
import com.pulseloop.nutrition.asFoodProduct
import com.pulseloop.settings.ApiKeyStore
import com.pulseloop.ui.theme.PulseColors
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import java.util.Calendar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NutritionScreen(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { PulseLoopDatabase.getInstance(context) }

    var dayOffset by remember { mutableIntStateOf(0) }
    val calendar = remember { Calendar.getInstance() }
    val todayStart = remember(dayOffset) {
        calendar.apply { timeInMillis = System.currentTimeMillis(); add(Calendar.DAY_OF_YEAR, -dayOffset) }.run {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            timeInMillis
        }
    }
    val dateLabel = remember(todayStart) {
        java.text.SimpleDateFormat("EEE, MMM d", java.util.Locale.US).format(java.util.Date(todayStart))
    }

    var meals by remember { mutableStateOf<List<MealEntryEntity>>(emptyList()) }
    var totals by remember { mutableStateOf<List<MealTotals>>(emptyList()) }
    var goal by remember { mutableStateOf<UserGoalEntity?>(null) }

    fun reload() {
        scope.launch {
            meals = db.mealEntryDao().byDay(todayStart)
            totals = db.mealEntryDao().dayTotals(todayStart)
        }
    }
    LaunchedEffect(Unit) { goal = db.userGoalDao().get() }
    LaunchedEffect(dayOffset) { reload() }

    var showAddDialog by remember { mutableStateOf(false) }
    var analysisRequest by remember { mutableStateOf<AnalysisRequest?>(null) }

    // iOS gates the Photo/Describe pills on coach + cloud provider + a photo-analysis
    // sub-toggle (NutritionView.swift:36-48). Android has no on-device provider mode and no
    // photo-analysis pref, so both pills gate on the coach master toggle alone (reported
    // divergence in ios-sync.md).
    val keyStore = remember(context) { ApiKeyStore(context) }
    var coachEnabled by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { coachEnabled = keyStore.coachEnabled }

    Scaffold(
        containerColor = PulseColors.background,
        // Insets are already applied by the route wrapper (see SettingsSubScreen).
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("Nutrition") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                windowInsets = WindowInsets(0, 0, 0, 0),
                colors = TopAppBarDefaults.topAppBarColors(containerColor = PulseColors.background),
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(16.dp),
        ) {
            // Day navigation — left goes to older dates (higher offset), right goes to today (offset 0).
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { dayOffset++ }) { Icon(Icons.Filled.ChevronLeft, "Previous") }
                    Text(dateLabel, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    IconButton(onClick = { if (dayOffset > 0) dayOffset-- }, enabled = dayOffset > 0) { Icon(Icons.Filled.ChevronRight, "Next") }
                }
            }

            // Calorie gauge
            item {
                val totalCal = totals.sumOf { it.totalCal }
                val goalCal = goal?.intakeCalories ?: 2000.0
                val pct = if (goalCal > 0) (totalCal / goalCal).coerceAtMost(1.0) else 0.0
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = PulseColors.card)) {
                    Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("${totalCal.roundToInt()} kcal", fontSize = 32.sp, fontWeight = FontWeight.Bold)
                        Text("of ${goalCal.roundToInt()}", color = PulseColors.textMuted)
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { pct.toFloat() },
                            modifier = Modifier.fillMaxWidth().height(8.dp),
                            color = PulseColors.calories,
                            trackColor = PulseColors.cardSoft,
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            MacroBar("Protein", totals.sumOf { it.totalP }, goal?.intakeProteinG ?: 150.0, PulseColors.heartRate)
                            MacroBar("Carbs", totals.sumOf { it.totalC }, goal?.intakeCarbsG ?: 250.0, PulseColors.calories)
                            MacroBar("Fat", totals.sumOf { it.totalF }, goal?.intakeFatG ?: 65.0, PulseColors.warning)
                        }
                    }
                }
            }

            // Meals grouped by type
            val mealTypes = listOf("breakfast", "lunch", "dinner", "snack")
            for (type in mealTypes) {
                val typeMeals = meals.filter { it.mealTypeRaw == type }
                if (typeMeals.isNotEmpty()) {
                    item {
                        Text(type.replaceFirstChar { it.uppercase() }, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = PulseColors.textSecondary)
                    }
                    items(typeMeals, key = { it.id }) { meal ->
                        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = PulseColors.card)) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(meal.name, fontWeight = FontWeight.Medium)
                                    Text("${meal.calories.roundToInt()} kcal · P${meal.proteinG.roundToInt()} C${meal.carbsG.roundToInt()} F${meal.fatG.roundToInt()}", fontSize = 12.sp, color = PulseColors.textMuted)
                                }
                                IconButton(onClick = {
                                    scope.launch {
                                        db.mealEntryDao().deleteById(meal.id)
                                        reload()
                                    }
                                }) {
                                    Icon(Icons.Filled.Delete, "Delete", tint = PulseColors.danger, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
            }

            // AI log actions (iOS logActions, NutritionView.swift:114-125): Photo opens the
            // analysis sheet in camera mode, Describe in describe mode.
            if (coachEnabled) {
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { analysisRequest = AnalysisRequest(startWithCamera = true) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Filled.PhotoCamera, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Photo")
                        }
                        OutlinedButton(
                            onClick = { analysisRequest = AnalysisRequest(startWithCamera = false) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Filled.AutoAwesome, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Describe")
                        }
                    }
                }
            }

            // Add button
            item {
                OutlinedButton(
                    onClick = { showAddDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Log Meal")
                }
            }
        }
    }

    if (showAddDialog) {
        MealLogDialog(
            onDismiss = { showAddDialog = false },
            onSave = { save ->
                scope.launch {
                    val entry = MealEntryEntity(
                        date = todayStart, timestamp = System.currentTimeMillis(),
                        name = save.name, mealTypeRaw = save.type,
                        calories = save.calories, proteinG = save.proteinG,
                        carbsG = save.carbsG, fatG = save.fatG,
                        sourceRaw = save.sourceRaw,
                        offProductCode = save.offProductCode,
                        servingDescription = save.servingDescription,
                        servingGrams = save.servingGrams,
                        quantity = save.quantity,
                        userEdited = save.userEdited,
                    )
                    // null keeps MealEntryEntity's own default — see MealLogSave.confidenceRaw.
                    db.mealEntryDao().upsert(
                        save.confidenceRaw?.let { entry.copy(confidenceRaw = it) } ?: entry)
                    reload()
                }
                showAddDialog = false
            },
        )
    }

    analysisRequest?.let { request ->
        // iOS: .sheet(item: $analysisSheet) { MealAnalysisSheet(day:startWithCamera:) }
        // (NutritionView.swift:106-108).
        MealAnalysisDialog(
            dayMillis = todayStart,
            startWithCamera = request.startWithCamera,
            onDismiss = { analysisRequest = null },
            onSaved = {
                analysisRequest = null
                reload()
            },
        )
    }
}

/** One AI-analysis sheet request (iOS AnalysisRequest, NutritionView.swift:29-32). */
private data class AnalysisRequest(val startWithCamera: Boolean)

/**
 * Everything [MealLogDialog] hands back on save — the manual-form fields plus the provenance
 * fields a database product pick carries (iOS MealEntrySource raw values; persisted raw,
 * append never rename).
 */
data class MealLogSave(
    val name: String,
    val type: String,
    val calories: Double,
    val proteinG: Double,
    val carbsG: Double,
    val fatG: Double,
    val sourceRaw: String = NutritionTools.sourceRawManual,
    /**
     * iOS MealEntry.init defaults `confidence: DecodeConfidence = .known` and MealLogSheet never
     * overrides it (NutritionModels.swift:99), so a label-backed database pick records "known".
     * Null leaves MealEntryEntity's own default in place for the manual path, whose pre-existing
     * "medium" is outside the known/partial/unknown vocabulary — reported, not changed here.
     */
    val confidenceRaw: String? = null,
    val offProductCode: String? = null,
    val servingDescription: String? = null,
    val servingGrams: Double? = null,
    val quantity: Double = 1.0,
    /** Numbers changed after a database prefill — iOS flips userEdited (MealLogSheet.swift:611). */
    val userEdited: Boolean = false,
)

@Composable
private fun MacroBar(label: String, value: Double, goal: Double, color: Color) {
    val pct = if (goal > 0) (value / goal).coerceAtMost(1.0) else 0.0
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 10.sp, color = PulseColors.textMuted)
        Text("${value.roundToInt()}g", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        LinearProgressIndicator(
            progress = { pct.toFloat() },
            modifier = Modifier.width(60.dp).height(4.dp),
            color = color, trackColor = PulseColors.cardSoft,
        )
    }
}

@Composable
fun MealLogDialog(
    onDismiss: () -> Unit,
    onSave: (MealLogSave) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("snack") }
    var cal by remember { mutableStateOf("") }
    var protein by remember { mutableStateOf("") }
    var carbs by remember { mutableStateOf("") }
    var fat by remember { mutableStateOf("") }
    val valid = name.isNotBlank() && (cal.toDoubleOrNull() ?: 0.0) > 0

    // ── Barcode scan → Open Food Facts lookup (iOS MealLogSheet.swift:149-261) ──
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { PulseLoopDatabase.getInstance(context) }
    val offClient = remember { OpenFoodFactsClient() }
    var showScanner by remember { mutableStateOf(false) }
    var lookingUp by remember { mutableStateOf(false) }
    var scanNotice by remember { mutableStateOf<String?>(null) }
    var pendingProduct by remember { mutableStateOf<PendingBarcodeProduct?>(null) }

    /** iOS ManualMealForm.fieldText (MealLogSheet.swift:582-584): whole or one decimal. */
    fun fieldText(value: Double): String =
        if (value == value.roundToInt().toDouble()) value.roundToInt().toString()
        else String.format(java.util.Locale.US, "%.1f", value)

    fun lookupBarcode(code: String) {
        scope.launch {
            lookingUp = true
            scanNotice = null
            try {
                // Cache-first (iOS lookupBarcode, MealLogSheet.swift:244-249): a rescanned
                // product never hits the API again.
                val cached = FoodProductCache.byCode(db.foodProductDao(), code)
                val product = cached?.asFoodProduct()
                    ?: offClient.product(code)?.also {
                        FoodProductCache.upsertCached(db.foodProductDao(), it)
                    }
                if (product == null) {
                    // iOS MealLogSheet.swift:128-130.
                    scanNotice = "Product not found for that barcode. Enter it manually instead."
                } else {
                    // Clear on success too: two scans in quick succession run two lookups, and
                    // an earlier-started "not found" completing after a later success would
                    // otherwise leave a stale notice over a prefilled form.
                    scanNotice = null
                    // iOS ServingPickerView defaults (MealLogSheet.swift:420, 461-462): one
                    // serving when OFF resolves a serving quantity, otherwise a 100 g basis.
                    val grams = product.servingQuantityG ?: 100.0
                    fun scaled(per100g: Double) = NutritionMath.scaled(per100g = per100g, grams = grams)
                    pendingProduct = PendingBarcodeProduct(
                        code = product.code,
                        gramsBasis = grams,
                        kcal = scaled(product.energyKcal100g),
                        protein = scaled(product.protein100g),
                        carbs = scaled(product.carbs100g),
                        fat = scaled(product.fat100g),
                        servingDescription = product.servingSizeText ?: "${grams.roundToInt()} g",
                    )
                    name = if (product.brand != null) "${product.name} (${product.brand})" else product.name
                    cal = fieldText(scaled(product.energyKcal100g))
                    protein = fieldText(scaled(product.protein100g))
                    carbs = fieldText(scaled(product.carbs100g))
                    fat = fieldText(scaled(product.fat100g))
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // iOS MealLogSheet.swift:258-260 catch-all.
                scanNotice = "Couldn't look up that barcode. Check your connection or enter the meal manually."
            } finally {
                lookingUp = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Log Meal") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (lookingUp) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    TextButton(onClick = { showScanner = true }, enabled = !lookingUp) {
                        Icon(Icons.Filled.QrCodeScanner, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Scan barcode")
                    }
                }
                scanNotice?.let {
                    // iOS inlineNotice copy (MealLogSheet.swift:128-130 / :259).
                    Text(it, fontSize = 12.sp, color = PulseColors.warning)
                }
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("breakfast", "lunch", "dinner", "snack").forEach { t ->
                        FilterChip(selected = type == t, onClick = { type = t }, label = { Text(t.replaceFirstChar { it.uppercase() }) })
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = cal, onValueChange = { cal = it }, label = { Text("kcal") }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(value = protein, onValueChange = { protein = it }, label = { Text("P(g)") }, modifier = Modifier.weight(1f), singleLine = true)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = carbs, onValueChange = { carbs = it }, label = { Text("C(g)") }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(value = fat, onValueChange = { fat = it }, label = { Text("F(g)") }, modifier = Modifier.weight(1f), singleLine = true)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val kcal = cal.toDoubleOrNull() ?: 0.0
                val pp = pendingProduct
                onSave(if (pp == null) {
                    MealLogSave(
                        name = name.trim(), type = type, calories = kcal,
                        proteinG = protein.toDoubleOrNull() ?: 0.0,
                        carbsG = carbs.toDoubleOrNull() ?: 0.0,
                        fatG = fat.toDoubleOrNull() ?: 0.0,
                    )
                } else {
                    // Editing a database entry's numbers flips userEdited
                    // (iOS MealLogSheet.swift:596-611).
                    val edited = kcal != pp.kcal ||
                        (protein.toDoubleOrNull() ?: 0.0) != pp.protein ||
                        (carbs.toDoubleOrNull() ?: 0.0) != pp.carbs ||
                        (fat.toDoubleOrNull() ?: 0.0) != pp.fat
                    MealLogSave(
                        name = name.trim(), type = type, calories = kcal,
                        proteinG = protein.toDoubleOrNull() ?: 0.0,
                        carbsG = carbs.toDoubleOrNull() ?: 0.0,
                        fatG = fat.toDoubleOrNull() ?: 0.0,
                        sourceRaw = NutritionTools.sourceRawOffBarcode,
                        confidenceRaw = "known",
                        offProductCode = pp.code,
                        servingDescription = pp.servingDescription,
                        servingGrams = pp.gramsBasis,
                        quantity = 1.0,
                        userEdited = edited,
                    )
                })
            }, enabled = valid) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )

    if (showScanner) {
        // iOS .sheet(isPresented: $showScanner) { BarcodeScannerSheet { code in ... } }
        // (MealLogSheet.swift:149-154): the scan closes the scanner and drives the lookup.
        BarcodeScannerDialog(
            onScan = { code ->
                showScanner = false
                lookupBarcode(code)
            },
            onDismiss = { showScanner = false },
        )
    }
}

/**
 * A scanned-and-resolved OFF product held until Save — carries the prefill snapshot so
 * post-prefill edits can flip userEdited, and the provenance fields the row records.
 */
private data class PendingBarcodeProduct(
    val code: String,
    /** Per-serving grams when OFF resolves a serving, else the 100 g basis (see lookup). */
    val gramsBasis: Double,
    val kcal: Double,
    val protein: Double,
    val carbs: Double,
    val fat: Double,
    val servingDescription: String?,
)
