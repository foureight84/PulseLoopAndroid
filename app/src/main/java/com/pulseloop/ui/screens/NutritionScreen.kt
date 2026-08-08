package com.pulseloop.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.data.dao.MealTotals
import com.pulseloop.data.entity.MealEntryEntity
import com.pulseloop.data.entity.UserGoalEntity
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

    Scaffold(
        containerColor = PulseColors.background,
        topBar = {
            TopAppBar(
                title = { Text("Nutrition") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = PulseColors.background),
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(16.dp),
        ) {
            // Day navigation
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
            onSave = { name, type, cal, p, c, f ->
                scope.launch {
                    db.mealEntryDao().upsert(MealEntryEntity(
                        date = todayStart, timestamp = System.currentTimeMillis(),
                        name = name, mealTypeRaw = type,
                        calories = cal, proteinG = p, carbsG = c, fatG = f,
                    ))
                    reload()
                }
                showAddDialog = false
            },
        )
    }
}

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
    onSave: (name: String, type: String, cal: Double, p: Double, c: Double, f: Double) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("snack") }
    var cal by remember { mutableStateOf("") }
    var protein by remember { mutableStateOf("") }
    var carbs by remember { mutableStateOf("") }
    var fat by remember { mutableStateOf("") }
    val valid = name.isNotBlank() && (cal.toDoubleOrNull() ?: 0.0) > 0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Log Meal") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                onSave(name, type, cal.toDoubleOrNull() ?: 0.0, protein.toDoubleOrNull() ?: 0.0, carbs.toDoubleOrNull() ?: 0.0, fat.toDoubleOrNull() ?: 0.0)
            }, enabled = valid) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
