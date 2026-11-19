package com.pulseloop.ui.screens

import com.pulseloop.R
import com.pulseloop.util.L10n
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulseloop.data.dao.CoachConversationSummary
import com.pulseloop.ui.theme.PulseColors
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * The coach's past conversations (issue #131): tap one to reopen it, swipe it away to delete it,
 * or delete them all. Every delete asks first — it takes the messages, the tool-call trace and any
 * attached photos with it, and there is no undo.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoachHistorySheet(
    conversations: List<CoachConversationSummary>,
    currentId: String?,
    onOpen: (String) -> Unit,
    onDelete: (String) -> Unit,
    onDeleteAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    var pendingDelete by remember { mutableStateOf<CoachConversationSummary?>(null) }
    var confirmDeleteAll by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = PulseColors.background) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.coach_past_conversations), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = PulseColors.textPrimary)
            if (conversations.isEmpty()) {
                Text(
                    stringResource(R.string.coach_history_empty),
                    fontSize = 13.sp, color = PulseColors.textMuted,
                )
            } else {
                Text(stringResource(R.string.coach_history_swipe), fontSize = 12.sp, color = PulseColors.textMuted)
                LazyColumn(
                    Modifier.weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(conversations, key = { it.id }) { convo ->
                        HistoryRow(
                            convo = convo,
                            isCurrent = convo.id == currentId,
                            onOpen = { onOpen(convo.id) },
                            onSwipedAway = { pendingDelete = convo },
                        )
                    }
                }
                TextButton(
                    onClick = { confirmDeleteAll = true },
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                ) { Text(stringResource(R.string.coach_delete_all), color = PulseColors.danger) }
            }
        }
    }

    pendingDelete?.let { convo ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.coach_delete_one_title)) },
            text = { Text(stringResource(R.string.coach_delete_one_body, CoachHistoryText.title(convo.firstQuestion))) },
            confirmButton = {
                TextButton(onClick = { onDelete(convo.id); pendingDelete = null }) {
                    Text(stringResource(R.string.action_delete), color = PulseColors.danger)
                }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    if (confirmDeleteAll) {
        AlertDialog(
            onDismissRequest = { confirmDeleteAll = false },
            title = { Text(stringResource(R.string.coach_delete_all_title)) },
            text = { Text(pluralStringResource(R.plurals.coach_delete_all_body, conversations.size, conversations.size)) },
            confirmButton = {
                TextButton(onClick = { confirmDeleteAll = false; onDeleteAll() }) {
                    Text(stringResource(R.string.action_delete_all), color = PulseColors.danger)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteAll = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistoryRow(
    convo: CoachConversationSummary,
    isCurrent: Boolean,
    onOpen: () -> Unit,
    onSwipedAway: () -> Unit,
) {
    // The swipe only asks; the row snaps back and the dialog decides. Returning false keeps the
    // state from settling on "dismissed", which would leave a cancelled row swiped off screen.
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value != SwipeToDismissBoxValue.Settled) onSwipedAway()
            false
        },
    )
    val shape = RoundedCornerShape(12.dp)
    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(shape)
                    .background(PulseColors.danger.copy(alpha = 0.25f))
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(Icons.Filled.Delete, stringResource(R.string.action_delete), tint = PulseColors.danger)
            }
        },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(PulseColors.card)
                .border(1.dp, if (isCurrent) PulseColors.accent else PulseColors.borderSubtle, shape)
                .clickable(onClick = onOpen)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(
                CoachHistoryText.title(convo.firstQuestion),
                fontSize = 14.sp, color = PulseColors.textPrimary,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                CoachHistoryText.subtitle(convo, isCurrent),
                fontSize = 12.sp, color = PulseColors.textMuted,
            )
        }
    }
}

/** The history row's text, kept out of the composable so it can be unit-tested. */
internal object CoachHistoryText {

    fun title(firstQuestion: String?): String =
        firstQuestion?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotEmpty() }
            ?: L10n.get(R.string.coach_untitled)

    fun subtitle(
        convo: CoachConversationSummary,
        isCurrent: Boolean,
        zone: ZoneId = ZoneId.systemDefault(),
        today: LocalDate = LocalDate.now(zone),
    ): String {
        val parts = mutableListOf(whenText(convo.updatedAt, zone, today))
        parts += L10n.plural(R.plurals.coach_messages, convo.messageCount, convo.messageCount)
        if (isCurrent) parts += L10n.get(R.string.coach_open_now)
        return parts.joinToString(" · ")
    }

    fun whenText(epochMs: Long, zone: ZoneId, today: LocalDate): String {
        val at = Instant.ofEpochMilli(epochMs).atZone(zone)
        val day = at.toLocalDate()
        return when {
            day == today -> L10n.get(R.string.when_today_at, at.format(TIME))
            day == today.minusDays(1) -> L10n.get(R.string.when_yesterday_at, at.format(TIME))
            day.year == today.year -> at.format(SAME_YEAR)
            else -> at.format(OTHER_YEAR)
        }
    }

    private val TIME = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
    private val SAME_YEAR = DateTimeFormatter.ofPattern("MMM d", Locale.getDefault())
    private val OTHER_YEAR = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.getDefault())
}
