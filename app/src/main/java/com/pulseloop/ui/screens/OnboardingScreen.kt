package com.pulseloop.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Paid
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.pulseloop.R
import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.data.entity.UserGoalEntity
import com.pulseloop.data.entity.UserProfileEntity
import com.pulseloop.ring.RingBLEClient
import com.pulseloop.service.RingSyncCoordinator
import com.pulseloop.settings.ApiKeyStore
import com.pulseloop.settings.UnitSystem
import com.pulseloop.ui.components.PrimaryButton
import com.pulseloop.ui.components.SecondaryButton
import com.pulseloop.ui.onboarding.GoalDraft
import com.pulseloop.ui.onboarding.GoalEditor
import com.pulseloop.ui.onboarding.OnboardingProgressStore
import com.pulseloop.ui.onboarding.OnboardingStep
import com.pulseloop.ui.onboarding.ProfileDraft
import com.pulseloop.ui.onboarding.ProfileEditor
import com.pulseloop.ui.theme.PulseColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The 5-step first-launch onboarding flow — ported from OnboardingFlowView.swift (iOS #48):
 * welcome → ring pairing → profile → goals → baseline education. Progress persists via
 * [OnboardingProgressStore] so a killed app relaunches on the same step.
 */
@Composable
fun OnboardingScreen(
    db: PulseLoopDatabase,
    bleClient: RingBLEClient,
    apiKeyStore: ApiKeyStore,
    coordinator: RingSyncCoordinator? = null,
    onFinished: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val progressStore = remember {
        OnboardingProgressStore(context.getSharedPreferences("pulseloop_onboarding", Context.MODE_PRIVATE))
    }
    var path by remember { mutableStateOf(progressStore.loadPath()) }
    val step = path.lastOrNull() ?: OnboardingStep.WELCOME

    fun push(next: OnboardingStep) {
        if (path.lastOrNull() == next) return
        path = path + next
        progressStore.savePath(path)
    }

    fun goBack() {
        if (path.size <= 1) return
        path = path.dropLast(1)
        progressStore.savePath(path)
    }

    fun finish() {
        scope.launch {
            val profile = db.userProfileDao().get() ?: UserProfileEntity()
            db.userProfileDao().upsert(
                profile.copy(
                    onboardingCompleted = true,
                    baselineCompleted = true,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
            apiKeyStore.onboardingCompleted = true
            progressStore.clear()
            onFinished()
        }
    }

    // BLE permissions gate on "Get started" (preserved from the previous onboarding screen).
    val blePermissions = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        if (blePermissions.all { results[it] == true }) push(OnboardingStep.RING)
    }

    fun getStarted() {
        val granted = blePermissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        if (granted) push(OnboardingStep.RING) else permissionLauncher.launch(blePermissions)
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(PulseColors.background)
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        OnboardingTopBar(
            step = step,
            canGoBack = path.size > 1,
            showsSkip = step == OnboardingStep.PROFILE,
            onBack = ::goBack,
            onSkip = { push(OnboardingStep.GOALS) },
        )

        AnimatedContent(
            targetState = step,
            transitionSpec = {
                (fadeIn(tween(300)) + slideInHorizontally(tween(300)) { it / 4 })
                    .togetherWith(fadeOut(tween(200)))
            },
            label = "onboardingStep",
            modifier = Modifier.weight(1f),
        ) { current ->
            when (current) {
                OnboardingStep.WELCOME -> WelcomeStep(
                    getStarted = ::getStarted,
                    exploreWithoutRing = { push(OnboardingStep.PROFILE) },
                )
                OnboardingStep.RING -> PairingScreen(
                    bleClient = bleClient,
                    onConnected = {
                        scope.launch {
                            delay(700) // let the success animation land (iOS advanceAfterConnection)
                            if (path.lastOrNull() == OnboardingStep.RING) push(OnboardingStep.PROFILE)
                        }
                    },
                    onSkip = { push(OnboardingStep.PROFILE) },
                )
                OnboardingStep.PROFILE -> ProfileStep(
                    db = db,
                    apiKeyStore = apiKeyStore,
                    coordinator = coordinator,
                    next = { push(OnboardingStep.GOALS) },
                )
                OnboardingStep.GOALS -> GoalsStep(
                    db = db,
                    apiKeyStore = apiKeyStore,
                    coordinator = coordinator,
                    next = { push(OnboardingStep.BASELINE) },
                )
                OnboardingStep.BASELINE -> BaselineStep(finish = ::finish)
            }
        }
    }
}

// MARK: - Top bar

@Composable
private fun OnboardingTopBar(
    step: OnboardingStep,
    canGoBack: Boolean,
    showsSkip: Boolean,
    onBack: () -> Unit,
    onSkip: () -> Unit,
) {
    val total = OnboardingStep.entries.size
    Column(
        Modifier.padding(horizontal = 18.dp).padding(bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(width = 60.dp, height = 44.dp), contentAlignment = Alignment.CenterStart) {
                if (canGoBack) {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = PulseColors.textSecondary,
                        )
                    }
                }
            }
            Text(
                "Step ${step.index + 1} of $total",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                color = PulseColors.textMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            Box(Modifier.size(width = 60.dp, height = 44.dp), contentAlignment = Alignment.CenterEnd) {
                if (showsSkip) {
                    Text(
                        "Skip",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = PulseColors.textSecondary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(onClick = onSkip)
                            .padding(horizontal = 10.dp, vertical = 12.dp),
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            OnboardingStep.entries.forEach { item ->
                Box(
                    Modifier
                        .weight(1f)
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(if (item.index <= step.index) PulseColors.accent else PulseColors.cardSoft),
                )
            }
        }
    }
}

// MARK: - Welcome

private data class WelcomeFeature(
    val icon: ImageVector,
    val title: String,
    val subtitle: String,
    val tint: Color,
)

@Composable
private fun WelcomeStep(getStarted: () -> Unit, exploreWithoutRing: () -> Unit) {
    val features = listOf(
        WelcomeFeature(Icons.Filled.Paid, "No subscription", "Own your ring data", PulseColors.success),
        WelcomeFeature(Icons.Filled.Lock, "Privacy first", "Data stays on device", PulseColors.info),
        WelcomeFeature(Icons.Filled.AutoAwesome, "AI health coach", "Learns your baseline", PulseColors.accent),
        WelcomeFeature(Icons.Filled.MonitorHeart, "See your vitals", "HR, SpO₂, HRV & stress", PulseColors.heartRate),
        WelcomeFeature(Icons.Filled.Bedtime, "Sleep tracking", "Stages and trends", PulseColors.sleep),
        WelcomeFeature(Icons.Filled.DirectionsRun, "Activity recording", "Live workout tracking", PulseColors.steps),
    )

    StepScaffold(
        footer = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryButton("Get started", icon = Icons.Filled.ArrowForward, onClick = getStarted)
                SecondaryButton("Explore without ring", icon = Icons.Filled.GridView, onClick = exploreWithoutRing)
            }
        },
    ) {
        Image(
            painter = painterResource(R.drawable.pulseloop_logo),
            contentDescription = null,
            modifier = Modifier
                .size(92.dp)
                .shadow(
                    18.dp,
                    RoundedCornerShape(22.dp),
                    ambientColor = PulseColors.accent,
                    spotColor = PulseColors.accent,
                )
                .clip(RoundedCornerShape(22.dp)),
        )
        StepHeader(title = "Set up PulseLoop")

        // Two-column feature grid (rows of two, since we're inside a scroll column).
        features.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                pair.forEach { feature ->
                    Column(
                        Modifier
                            .weight(1f)
                            .height(118.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(PulseColors.card)
                            .border(1.dp, PulseColors.borderSubtle, RoundedCornerShape(16.dp))
                            .padding(horizontal = 13.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
                    ) {
                        Box(
                            Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(11.dp))
                                .background(feature.tint.copy(alpha = 0.14f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(feature.icon, contentDescription = null, Modifier.size(18.dp), tint = feature.tint)
                        }
                        Text(
                            feature.title,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = PulseColors.textPrimary,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                        )
                        Text(
                            feature.subtitle,
                            fontSize = 12.sp,
                            color = PulseColors.textMuted,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                        )
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

// MARK: - Profile

@Composable
private fun ProfileStep(
    db: PulseLoopDatabase,
    apiKeyStore: ApiKeyStore,
    coordinator: RingSyncCoordinator?,
    next: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf<ProfileDraft?>(null) }

    LaunchedEffect(Unit) {
        if (draft == null) {
            val profile = db.userProfileDao().get()
            // A manually stored units preference wins over the locale default (iOS: profile units).
            val storedUnits = apiKeyStore.unitSystem?.let {
                try { UnitSystem.valueOf(it) } catch (_: Exception) { null }
            }
            draft = ProfileDraft.from(profile, existingUnits = storedUnits)
        }
    }

    StepScaffold(
        footer = {
            PrimaryButton("Continue", icon = Icons.Filled.ArrowForward, enabled = draft != null) {
                val current = draft ?: return@PrimaryButton
                scope.launch {
                    val existing = db.userProfileDao().get()
                    val entity = current.applyTo(existing ?: UserProfileEntity())
                    db.userProfileDao().upsert(entity)
                    apiKeyStore.unitSystem = current.units.name
                    coordinator?.applyUserProfileToRing()
                    next()
                }
            }
        },
    ) {
        StepHeader(
            title = "Profile",
            subtitle = "Used to tune calories, activity goals, and summaries.",
        )
        draft?.let { current ->
            ProfileEditor(draft = current, onDraftChange = { draft = it })
        }
    }
}

// MARK: - Goals

@Composable
private fun GoalsStep(
    db: PulseLoopDatabase,
    apiKeyStore: ApiKeyStore,
    coordinator: RingSyncCoordinator?,
    next: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val units = remember { apiKeyStore.resolvedUnitSystem }
    var draft by remember { mutableStateOf<GoalDraft?>(null) }

    LaunchedEffect(Unit) {
        if (draft == null) {
            draft = GoalDraft.from(db.userGoalDao().get(), units)
        }
    }

    StepScaffold(
        footer = {
            PrimaryButton("Save goals", icon = Icons.Filled.Check, enabled = draft != null) {
                val current = draft ?: return@PrimaryButton
                scope.launch {
                    val existing = db.userGoalDao().get()
                    db.userGoalDao().upsert(
                        current.applyTo(existing ?: UserGoalEntity(), units, includeWeeklyWorkouts = false),
                    )
                    coordinator?.setGoal(current.steps.toInt())
                    next()
                }
            }
        },
    ) {
        StepHeader(
            title = "Daily goals",
            subtitle = "Start with recommended targets. You can change these anytime.",
        )
        draft?.let { current ->
            GoalEditor(
                draft = current,
                onDraftChange = { draft = it },
                units = units,
                includeWeeklyWorkouts = false,
            )
        }
    }
}

// MARK: - Baseline

private data class BaselineMilestone(
    val icon: ImageVector?,
    val title: String,
    val subtitle: String,
    val tint: Color,
)

@Composable
private fun BaselineStep(finish: () -> Unit) {
    val milestones = listOf(
        BaselineMilestone(null, "Day 1", "Basic activity and vitals", PulseColors.info),
        BaselineMilestone(Icons.Filled.Bedtime, "After sleep", "Sleep trends", PulseColors.sleep),
        BaselineMilestone(Icons.AutoMirrored.Filled.TrendingUp, "After 3–7 days", "Personalized baseline", PulseColors.success),
    )

    StepScaffold(
        footer = {
            PrimaryButton("Go to app", icon = Icons.Filled.ArrowForward, onClick = finish)
        },
    ) {
        StepHeader(
            title = "You're ready",
            subtitle = "A little context before your first day with PulseLoop.",
        )

        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(
                    Brush.linearGradient(
                        listOf(PulseColors.accent.copy(alpha = 0.14f), PulseColors.card),
                    ),
                )
                .border(1.dp, PulseColors.borderSubtle, RoundedCornerShape(22.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "PulseLoop learns your baseline over time",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = PulseColors.textPrimary,
                )
                Text(
                    "Wear your ring during the day and sync after sleep. Trends become more personal after a few days.",
                    fontSize = 14.sp,
                    color = PulseColors.textSecondary,
                    lineHeight = 20.sp,
                )
            }

            milestones.forEach { milestone ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(
                        Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(milestone.tint.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (milestone.icon != null) {
                            Icon(milestone.icon, contentDescription = null, Modifier.size(15.dp), tint = milestone.tint)
                        } else {
                            Text("1", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = milestone.tint)
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            milestone.title,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = PulseColors.textPrimary,
                        )
                        Text(
                            milestone.subtitle,
                            fontSize = 13.sp,
                            color = PulseColors.textMuted,
                        )
                    }
                }
            }
        }
    }
}

// MARK: - Shared step chrome

/** Scrolling step content above a pinned action footer (iOS `OnboardingActionFooter`). */
@Composable
private fun StepScaffold(
    footer: @Composable () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
            content = content,
        )
        androidx.compose.material3.HorizontalDivider(thickness = 1.dp, color = PulseColors.borderSubtle)
        Box(
            Modifier
                .fillMaxWidth()
                .background(PulseColors.secondaryBackground)
                .padding(horizontal = 24.dp, vertical = 12.dp),
        ) {
            footer()
        }
    }
}

/** Large centered step title + optional subtitle (iOS `CompactOnboardingHeader`). */
@Composable
private fun StepHeader(title: String, subtitle: String? = null) {
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            title,
            fontSize = 30.sp,
            fontWeight = FontWeight.SemiBold,
            color = PulseColors.textPrimary,
            textAlign = TextAlign.Center,
        )
        if (subtitle != null) {
            Text(
                subtitle,
                fontSize = 15.sp,
                color = PulseColors.textSecondary,
                textAlign = TextAlign.Center,
                lineHeight = 21.sp,
            )
        }
    }
}
