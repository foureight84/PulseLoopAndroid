package com.pulseloop.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulseloop.coach.attachments.CoachAttachmentRef
import kotlinx.coroutines.launch
import com.pulseloop.coach.attachments.CoachAttachmentStore
import com.pulseloop.ui.components.MarkdownLite
import com.pulseloop.ui.theme.PulseColors
import com.pulseloop.ui.viewmodels.CoachViewModel

/**
 * Coach chat — ported from CoachView.swift: gradient-avatar sub-header ("PulseLoop Coach ·
 * Using your latest ring sync") with a new-chat button, iOS bubble styling (violet user
 * bubbles, neutral dark assistant cards with markdown-lite rendering), suggestion chips above
 * the composer, and the pill input with circular attach/send buttons. Inline chart attachments
 * in assistant replies are not ported yet.
 */
@Composable
fun CoachScreen(
    navController: androidx.navigation.NavController? = null,
    viewModel: CoachViewModel? = null,
) {
    val state by (viewModel?.state?.collectAsState() ?: remember {
        mutableStateOf(CoachViewModel.CoachState())
    })
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val ctx = LocalContext.current

    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.size - 1)
    }

    Column(Modifier.fillMaxSize().background(PulseColors.background)) {
        CoachSubHeader(onNewChat = { viewModel?.newConversation() })

        LazyColumn(
            modifier = Modifier.weight(1f),
            state = listState,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(state.messages.size) { idx ->
                MessageBubble(state.messages[idx])
            }
            if (state.isThinking) {
                item {
                    Text(
                        "Coach is thinking…",
                        fontSize = 12.sp, color = PulseColors.textMuted,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
            if (state.error != null) {
                item {
                    Text("Error: ${state.error}", fontSize = 12.sp, color = PulseColors.danger, modifier = Modifier.padding(start = 8.dp))
                }
            }
        }

        Composer(
            inputText = inputText,
            onInputChange = { inputText = it },
            isThinking = state.isThinking,
            showChips = state.messages.count { it.role == "user" } == 0,
            onSend = { text, staged ->
                viewModel?.sendMessage(text, staged)
                inputText = ""
            },
        )
    }
}

// ─────────────────────────── Sub-header ───────────────────────────

@Composable
private fun CoachSubHeader(onNewChat: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(PulseColors.secondaryBackground)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(Color(0xFF4DA3FF), PulseColors.accent))),
            contentAlignment = Alignment.TopEnd,
        ) {
            Box(
                Modifier
                    .padding(top = 9.dp, end = 9.dp)
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.9f)),
            )
        }
        Column(Modifier.weight(1f)) {
            Text("PulseLoop Coach", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = PulseColors.textPrimary)
            Text("Using your latest ring sync", fontSize = 12.sp, color = PulseColors.textMuted)
        }
        Box(
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(PulseColors.card)
                .border(1.dp, PulseColors.borderSubtle, CircleShape)
                .clickable(onClick = onNewChat),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Add, "New chat", tint = PulseColors.textSecondary, modifier = Modifier.size(18.dp))
        }
    }
}

// ─────────────────────────── Bubbles ───────────────────────────

@Composable
private fun MessageBubble(msg: CoachViewModel.ChatMessage) {
    val isUser = msg.role == "user"
    val ctx = LocalContext.current
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
    ) {
        Column(
            Modifier
                .widthIn(max = if (isUser) 300.dp else 360.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(if (isUser) PulseColors.accent else PulseColors.card)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (msg.attachments.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    msg.attachments.forEach { ref ->
                        val bmp = remember(ref.file) { CoachAttachmentStore.loadBitmap(ctx, ref) }
                        bmp?.let {
                            Image(
                                bitmap = it.asImageBitmap(),
                                contentDescription = "Attached photo",
                                modifier = Modifier.size(96.dp).clip(RoundedCornerShape(10.dp)),
                                contentScale = ContentScale.Crop,
                            )
                        }
                    }
                }
            }
            if (msg.text.isNotBlank()) {
                if (isUser) {
                    Text(msg.text, fontSize = 14.sp, lineHeight = 20.sp, color = Color.White)
                } else {
                    MarkdownLite(msg.text)
                }
            }
        }
    }
}

// ─────────────────────────── Composer ───────────────────────────

private val SUGGESTIONS = listOf(
    "How am I doing today?",
    "Explain my heart rate trend",
    "Summarize my week",
    "How can I sleep better?",
)

@Composable
private fun Composer(
    inputText: String,
    onInputChange: (String) -> Unit,
    isThinking: Boolean,
    showChips: Boolean,
    onSend: (String, List<CoachAttachmentRef>) -> Unit,
) {
    val ctx = LocalContext.current
    val imageInputEnabled = remember { com.pulseloop.coach.config.CoachProviderSettingsStore(ctx).imageInputEnabled }
    // Staged (not yet sent) attachments — persisted to the store on pick, sent with the
    // next message. Image-only sends are allowed (iOS #31).
    var staged by remember { mutableStateOf(listOf<CoachAttachmentRef>()) }
    val scope = rememberCoroutineScope()
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        // Decode + JPEG re-encode + file write happen off the main thread — the
        // ActivityResult callback arrives on it, and a large photo would freeze the frame.
        uri?.let { picked ->
            scope.launch {
                val ref = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    CoachAttachmentStore.save(ctx, picked)
                }
                ref?.let { staged = staged + it }
            }
        }
    }
    val canSend = (inputText.isNotBlank() || staged.isNotEmpty()) && !isThinking

    Column(
        Modifier
            .fillMaxWidth()
            .background(PulseColors.secondaryBackground)
            .padding(bottom = 8.dp),
    ) {
        if (showChips) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SUGGESTIONS.forEach { chip ->
                    Text(
                        chip,
                        fontSize = 12.sp, fontWeight = FontWeight.Medium,
                        color = PulseColors.textSecondary,
                        maxLines = 1,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(PulseColors.card)
                            .border(1.dp, PulseColors.borderSubtle, CircleShape)
                            .clickable(enabled = !isThinking) { onSend(chip, emptyList()) }
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                    )
                }
            }
        }
        if (staged.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                staged.forEach { ref ->
                    Box {
                        val bmp = remember(ref.file) { CoachAttachmentStore.loadBitmap(ctx, ref) }
                        bmp?.let {
                            Image(
                                bitmap = it.asImageBitmap(),
                                contentDescription = "Staged photo",
                                modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)),
                                contentScale = ContentScale.Crop,
                            )
                        }
                        IconButton(
                            onClick = {
                                CoachAttachmentStore.delete(ctx, ref)
                                staged = staged - ref
                            },
                            modifier = Modifier.size(20.dp).align(Alignment.TopEnd),
                        ) { Icon(Icons.Filled.Close, "Remove photo", Modifier.size(14.dp), tint = Color.White) }
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (imageInputEnabled) {
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(PulseColors.card)
                        .clickable(enabled = !isThinking) {
                            pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Add, "Attach photo", tint = PulseColors.textMuted, modifier = Modifier.size(20.dp))
                }
            }
            // Pill text field (iOS composer): filled capsule, no outline.
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(22.dp))
                    .background(PulseColors.card)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                if (inputText.isEmpty()) {
                    Text("Ask the coach...", fontSize = 14.sp, color = PulseColors.textMuted)
                }
                BasicTextField(
                    value = inputText,
                    onValueChange = onInputChange,
                    enabled = !isThinking,
                    maxLines = 3,
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontSize = 14.sp, color = PulseColors.textPrimary,
                    ),
                    cursorBrush = SolidColor(PulseColors.accent),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(if (canSend) PulseColors.accent else PulseColors.card)
                    .clickable(enabled = canSend) {
                        onSend(inputText.trim(), staged)
                        staged = emptyList()
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.ArrowUpward, "Send",
                    tint = if (canSend) Color.White else PulseColors.textMuted,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
