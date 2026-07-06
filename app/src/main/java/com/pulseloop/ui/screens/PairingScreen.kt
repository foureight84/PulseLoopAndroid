package com.pulseloop.ui.screens

import android.content.Intent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulseloop.ring.RingBLEClient
import com.pulseloop.ring.RingConnectionState
import com.pulseloop.ring.ringModelLabel
import com.pulseloop.ui.components.CapabilityChips
import com.pulseloop.ui.components.InlineEmptyState
import com.pulseloop.ui.components.PrimaryButton
import com.pulseloop.ui.components.RingArtView
import com.pulseloop.ui.components.SecondaryButton
import com.pulseloop.ui.components.SignalStrengthDots
import com.pulseloop.ui.theme.PulseColors
import com.pulseloop.wearables.WearableModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The dedicated, modern ring-pairing screen — ported from PairingView.swift (iOS #48).
 * Swipe a carousel of supported ring models (product photo + name + capability chips) grouped
 * by brand tabs, pick yours, then scan and connect to a matching nearby device. Reused in two
 * contexts: the onboarding RING step (with a "Skip for now") and pushed from Settings.
 *
 * Pairing logic is just orchestration over [RingBLEClient]; the chosen model's `family` biases
 * which discovered devices we surface, while the client's coordinators do the real matching.
 */
@Composable
fun PairingScreen(
    bleClient: RingBLEClient,
    onConnected: () -> Unit,
    onSkip: (() -> Unit)? = null,
) {
    val state by bleClient.state.collectAsState()
    val context = LocalContext.current

    var isLooking by remember { mutableStateOf(false) }
    var didFireConnected by remember { mutableStateOf(false) }

    // The adapter flag isn't part of BLEState until a scan attempt, so poll it directly.
    var btReady by remember { mutableStateOf(bleClient.isBluetoothEnabled) }
    LaunchedEffect(Unit) {
        while (true) {
            btReady = bleClient.isBluetoothEnabled
            delay(1000)
        }
    }

    val allModels = WearableModel.CATALOG
    // "All" first, then each distinct brand alphabetically (case-insensitive).
    val brands = remember(allModels) {
        listOf(ALL_BRANDS_TAB) + allModels.map { it.brand }.distinct().sortedBy { it.lowercase() }
    }
    var selectedBrand by remember { mutableStateOf(ALL_BRANDS_TAB) }
    // Models for the selected brand tab, sorted alphabetically by name.
    val models = remember(selectedBrand) {
        val scoped = if (selectedBrand == ALL_BRANDS_TAB) allModels
        else allModels.filter { it.brand == selectedBrand }
        scoped.sortedBy { it.displayName.lowercase() }
    }
    var selectedIndex by remember(selectedBrand) { mutableIntStateOf(0) }
    val selectedModel = models.getOrNull(selectedIndex.coerceIn(0, models.size - 1)) ?: WearableModel.JRING

    // Discovered rings whose matched family equals the selected model's family, falling back
    // to all named devices if nothing matches yet so the user is never stuck.
    val matchingRings = remember(state.discovered, selectedModel.family) {
        val matches = state.discovered.filter { it.deviceType == selectedModel.family }
        matches.ifEmpty { state.discovered }
    }

    val connected = state.connectionState == RingConnectionState.CONNECTED
    val showsFooter = !connected && (if (isLooking) onSkip != null else (btReady || onSkip != null))

    // Fire onConnected exactly once, and only on a transition into CONNECTED (a screen opened
    // while already connected just shows the connected card, matching iOS onChange semantics).
    LaunchedEffect(Unit) {
        var previous = bleClient.state.value.connectionState
        bleClient.state.collect { s ->
            if (s.connectionState == RingConnectionState.CONNECTED &&
                previous != RingConnectionState.CONNECTED &&
                !didFireConnected
            ) {
                didFireConnected = true
                isLooking = false
                onConnected()
            }
            previous = s.connectionState
        }
    }

    // Re-filter/re-scan as the user changes their selected model mid-scan (iOS onChange).
    LaunchedEffect(selectedModel.id) {
        if (isLooking && !connected) bleClient.startScanning()
    }

    // Reset so a re-appear doesn't show a frozen scan.
    DisposableEffect(Unit) {
        onDispose { bleClient.stopScanning() }
    }

    Column(Modifier.fillMaxSize().background(PulseColors.background)) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PairingHeader(
                title = "Add your ring",
                subtitle = "Swipe to find your model, then tap to connect.\nYou can also explore first and pair later.",
            )

            if (!btReady) {
                BluetoothOffCard()
            } else if (connected) {
                ConnectedCard(
                    model = selectedModel,
                    deviceName = state.activeDeviceType?.displayName ?: selectedModel.displayName,
                    checkmarkFired = didFireConnected,
                    onContinue = onSkip,
                )
            } else {
                BrandTabs(brands, selectedBrand) { brand ->
                    selectedBrand = brand
                    if (isLooking) bleClient.startScanning()
                }
                // Recreated per brand so pages swap instantly (no page-slide animation).
                key(selectedBrand) {
                    ModelCarousel(models, onPageChanged = { selectedIndex = it })
                }
                if (isLooking) {
                    ScanningCard(
                        state = state,
                        selectedModel = selectedModel,
                        matchingRings = matchingRings,
                        onSelectRing = { bleClient.connectTo(it) },
                        onStop = {
                            isLooking = false
                            bleClient.stopScanning()
                        },
                    )
                }
            }

            if (state.lastError != null && !connected) {
                Text(
                    state.lastError ?: "",
                    fontSize = 12.sp,
                    color = PulseColors.danger,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        if (showsFooter) {
            Column {
                HorizontalDivider(thickness = 1.dp, color = PulseColors.borderSubtle)
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(PulseColors.secondaryBackground)
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (!isLooking && btReady) {
                        PrimaryButton("Connect ring", icon = Icons.Filled.Sensors) {
                            isLooking = true
                            bleClient.startScanning()
                        }
                        if (bleClient.hasLastKnownRing &&
                            state.connectionState != RingConnectionState.RECONNECTING
                        ) {
                            SecondaryButton("Reconnect last ring", icon = Icons.Filled.Refresh) {
                                bleClient.connectLastKnown()
                            }
                        }
                    }
                    if (onSkip != null) {
                        SecondaryButton("Skip for now", icon = Icons.Filled.ArrowForward, onClick = onSkip)
                        Text(
                            "You can pair a ring later from Settings.",
                            fontSize = 12.sp,
                            color = PulseColors.textMuted,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}

private const val ALL_BRANDS_TAB = "All"

// MARK: - Header

@Composable
private fun PairingHeader(title: String, subtitle: String) {
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
        Text(
            subtitle,
            fontSize = 15.sp,
            color = PulseColors.textSecondary,
            textAlign = TextAlign.Center,
            lineHeight = 20.sp,
        )
    }
}

// MARK: - Brand tabs

@Composable
private fun BrandTabs(
    brands: List<String>,
    selectedBrand: String,
    onSelect: (String) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        brands.forEach { brand ->
            val isSelected = brand == selectedBrand
            Box(
                Modifier
                    .heightIn(min = 44.dp)
                    .clickable { onSelect(brand) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    brand,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isSelected) Color.White else PulseColors.textSecondary,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(if (isSelected) PulseColors.accent else PulseColors.card)
                        .border(1.dp, if (isSelected) Color.Transparent else PulseColors.borderSubtle, CircleShape)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
    }
}

// MARK: - Carousel

@Composable
private fun ModelCarousel(
    models: List<WearableModel>,
    onPageChanged: (Int) -> Unit,
) {
    val pagerState = rememberPagerState(pageCount = { models.size })
    val scope = rememberCoroutineScope()

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect(onPageChanged)
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp),
        ) { page ->
            val model = models[page]
            Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            ) {
                RingArtView(tint = model.tint, imageRes = model.imageRes)
                Text(
                    model.displayName,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = PulseColors.textPrimary,
                )
                CapabilityChips(model.blurb)
            }
        }

        // Fixed-height tappable dot row keeps layout stable across brand tabs.
        Row(
            Modifier.height(44.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (models.size > 1) {
                models.forEachIndexed { index, _ ->
                    val isSelected = pagerState.currentPage == index
                    Box(
                        Modifier
                            .size(width = 32.dp, height = 44.dp)
                            .clickable { scope.launch { pagerState.animateScrollToPage(index) } },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier
                                .size(width = if (isSelected) 20.dp else 6.dp, height = 6.dp)
                                .clip(CircleShape)
                                .background(if (isSelected) PulseColors.accent else PulseColors.elevated)
                                .border(1.dp, if (isSelected) Color.Transparent else PulseColors.borderStrong, CircleShape),
                        )
                    }
                }
            }
        }
    }
}

// MARK: - Scanning card

@Composable
private fun ScanningCard(
    state: RingBLEClient.BLEState,
    selectedModel: WearableModel,
    matchingRings: List<RingBLEClient.DiscoveredRing>,
    onSelectRing: (String) -> Unit,
    onStop: () -> Unit,
) {
    val connecting = state.connectionState == RingConnectionState.CONNECTING ||
        state.connectionState == RingConnectionState.RECONNECTING

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(PulseColors.card)
            .border(1.dp, PulseColors.borderSubtle, RoundedCornerShape(20.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(
                Modifier.size(14.dp),
                strokeWidth = 2.dp,
                color = PulseColors.accent,
            )
            Text(
                if (connecting) "Connecting to ${selectedModel.displayName}…"
                else "Scanning for ${selectedModel.displayName}…",
                fontSize = 12.sp,
                color = PulseColors.textMuted,
            )
        }

        matchingRings.forEach { ring ->
            DiscoveredRingRow(ring) { onSelectRing(ring.id) }
        }

        if (matchingRings.isEmpty()) {
            InlineEmptyState(
                title = "No rings found yet",
                message = "Wake the ring by tapping or moving it, and keep it close.",
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        SecondaryButton("Stop scanning", icon = Icons.Filled.StopCircle, onClick = onStop)
    }
}

@Composable
private fun DiscoveredRingRow(ring: RingBLEClient.DiscoveredRing, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(PulseColors.card)
            .border(1.dp, PulseColors.borderSubtle, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
    ) {
        // Accent left-stripe marks devices recognized as rings. matchParentSize resolves after
        // the Row below is measured, so the stripe spans the full row height.
        if (ring.isLikelyRing) {
            Box(Modifier.matchParentSize()) {
                Box(
                    Modifier
                        .align(Alignment.CenterStart)
                        .fillMaxHeight()
                        .width(2.dp)
                        .background(PulseColors.accent),
                )
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                if (ring.isLikelyRing) Icons.Filled.CheckCircle else Icons.Filled.BluetoothSearching,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = if (ring.isLikelyRing) PulseColors.accent else PulseColors.textMuted,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    ring.name,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = PulseColors.textPrimary,
                )
                ring.deviceType?.let { type ->
                    Text(
                        ringModelLabel(ring.name, type),
                        fontSize = 11.sp,
                        color = PulseColors.accent,
                    )
                }
            }
            SignalStrengthDots(rssi = ring.rssi)
        }
    }
}

// MARK: - States

@Composable
private fun ConnectedCard(
    model: WearableModel,
    deviceName: String,
    checkmarkFired: Boolean,
    onContinue: (() -> Unit)?,
) {
    // Success ring halo: spring scale 0.7 → 1.0 + fade-in, triggered once on appear.
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appeared = true }
    val haloScale by animateFloatAsState(
        targetValue = if (appeared) 1f else 0.7f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow),
        label = "haloScale",
    )
    val haloAlpha by animateFloatAsState(
        targetValue = if (appeared) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow),
        label = "haloAlpha",
    )
    val checkScale by animateFloatAsState(
        targetValue = if (checkmarkFired || appeared) 1f else 0.3f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium),
        label = "checkScale",
    )

    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            RingArtView(tint = model.tint, size = 140f, imageRes = model.imageRes)
            Box(
                Modifier
                    .size(152.dp)
                    .scale(haloScale)
                    .alpha(haloAlpha)
                    .border(2.dp, PulseColors.success, CircleShape),
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                modifier = Modifier.size(22.dp).scale(checkScale),
                tint = PulseColors.success,
            )
            Text(
                "Connected to $deviceName",
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = PulseColors.textPrimary,
            )
        }
        if (onContinue != null) {
            PrimaryButton("Continue", icon = Icons.Filled.Check, onClick = onContinue)
        }
    }
}

@Composable
private fun BluetoothOffCard() {
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(PulseColors.card)
            .padding(horizontal = 20.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(
            Icons.Filled.BluetoothDisabled,
            contentDescription = null,
            modifier = Modifier.size(34.dp),
            tint = PulseColors.textMuted,
        )
        Text(
            "Bluetooth is off",
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            color = PulseColors.textPrimary,
        )
        Text(
            "Turn on Bluetooth to find and connect your ring.",
            fontSize = 14.sp,
            color = PulseColors.textMuted,
            textAlign = TextAlign.Center,
        )
        SecondaryButton("Open Settings", icon = Icons.Filled.Settings) {
            try {
                context.startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS))
            } catch (_: Exception) {
                // No Bluetooth settings activity on this device — nothing to open.
            }
        }
    }
}
