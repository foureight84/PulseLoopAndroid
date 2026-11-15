package com.pulseloop.ui.screens

import com.pulseloop.R
import com.pulseloop.util.L10n
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pulseloop.coach.config.CoachModelCatalog
import com.pulseloop.coach.config.CoachProviderMode
import com.pulseloop.ui.theme.PulseColors
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The cloud-provider model picker: the curated [presets] first, then every model the provider
 * itself lists ([CoachModelCatalog]), fetched as soon as the provider is selected with a saved key.
 * The listing is advisory — [selected] stays whatever string the user picked — but it is what tells
 * the user a stored model has been retired, instead of every coach turn failing with a 404.
 *
 * [onListing] reports each fetched listing (null while there is none) so the Reasoning picker can
 * offer the selected model's real levels.
 */
@Composable
fun CoachModelPicker(
    provider: CoachProviderMode,
    providerName: String,
    selected: String,
    presets: List<Pair<String, String>>,
    savedKey: String,
    onPick: (String) -> Unit,
    onListing: (CoachModelCatalog.Result.Success?) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var listing by remember(provider) { mutableStateOf<CoachModelCatalog.Result?>(null) }
    var loading by remember(provider) { mutableStateOf(false) }

    // One load at a time: a Refresh still in flight when the key changes (or another Refresh) is
    // cancelled, so an older key's answer can't land on top of the current one.
    var job by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    fun load(force: Boolean) {
        job?.cancel()
        if (savedKey.isBlank()) { listing = null; loading = false; onListing(null); return }
        job = scope.launch {
            loading = true
            try {
                val result = CoachModelCatalog.fetch(provider, savedKey, force)
                listing = result
                onListing(result as? CoachModelCatalog.Result.Success)
            } finally {
                // A cancelled job's finally can run after its replacement set `loading`; only the
                // live job clears it, or the replacement's fetch would look finished.
                if (isActive) loading = false
            }
        }
    }
    LaunchedEffect(provider, savedKey) { load(force = false) }

    val success = listing as? CoachModelCatalog.Result.Success
    val presetSlugs = presets.map { it.first }.toSet()
    val others = success?.entries?.filter { it.id !in presetSlugs }.orEmpty()

    Text(stringResource(R.string.coach_model), style = MaterialTheme.typography.labelMedium)
    Spacer(Modifier.height(4.dp))
    var expanded by remember { mutableStateOf(false) }
    var filter by remember(provider) { mutableStateOf("") }
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(selected, Modifier.weight(1f))
            Icon(Icons.Filled.ArrowDropDown, null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            @Composable
            fun Item(slug: String, detail: String) = DropdownMenuItem(
                text = {
                    Column {
                        Text(slug)
                        if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                onClick = { onPick(slug); expanded = false },
            )

            if (presets.isNotEmpty()) MenuHeader(stringResource(R.string.picker_recommended))
            presets.forEach { (slug, blurb) -> Item(slug, blurb) }

            if (others.isNotEmpty()) {
                HorizontalDivider()
                MenuHeader(stringResource(R.string.picker_all_models, providerName, others.size))
                // OpenRouter lists hundreds; a filter beats scrolling, and the cap keeps the
                // (non-lazy) menu from composing every row.
                if (others.size > FILTER_THRESHOLD) {
                    OutlinedTextField(
                        value = filter,
                        onValueChange = { filter = it },
                        placeholder = { Text(stringResource(R.string.picker_filter)) },
                        singleLine = true,
                        modifier = Modifier.padding(horizontal = 12.dp).fillMaxWidth(),
                    )
                }
                val matches = others.filter { e ->
                    filter.isBlank() || e.id.contains(filter.trim(), ignoreCase = true) ||
                        e.name?.contains(filter.trim(), ignoreCase = true) == true
                }
                matches.take(MAX_ROWS).forEach { e ->
                    val detail = listOfNotNull(
                        e.name?.takeIf { it != e.id },
                        e.expiresOn?.let { L10n.get(R.string.picker_retiring, it) },
                    ).joinToString(" · ")
                    Item(e.id, detail)
                }
                if (matches.size > MAX_ROWS) {
                    MenuHeader(stringResource(R.string.picker_more, matches.size - MAX_ROWS))
                }
            }
        }
    }

    // Status of the live listing, and what it says about the selected model.
    val expiry = success?.find(selected)?.expiresOn
    val unlisted = success != null && CoachModelCatalog.isUnlisted(provider, success, selected)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        val (status, color) = when {
            savedKey.isBlank() -> stringResource(R.string.picker_save_key, providerName) to MaterialTheme.colorScheme.onSurfaceVariant
            loading -> stringResource(R.string.picker_loading, providerName) to MaterialTheme.colorScheme.onSurfaceVariant
            listing is CoachModelCatalog.Result.Failure -> (listing as CoachModelCatalog.Result.Failure).message to PulseColors.danger
            success != null -> stringResource(R.string.picker_available, success.entries.size, providerName) to MaterialTheme.colorScheme.onSurfaceVariant
            else -> "" to MaterialTheme.colorScheme.onSurfaceVariant
        }
        Text(status, style = MaterialTheme.typography.bodySmall, color = color, modifier = Modifier.weight(1f))
        if (savedKey.isNotBlank() && !loading) {
            TextButton(onClick = { load(force = true) }) {
                Text(if (listing is CoachModelCatalog.Result.Failure) stringResource(R.string.action_retry) else stringResource(R.string.action_refresh))
            }
        }
    }
    if (unlisted) {
        Text(
            stringResource(R.string.picker_missing, providerName, selected),
            style = MaterialTheme.typography.bodySmall,
            color = PulseColors.danger,
        )
    } else if (expiry != null) {
        Text(
            stringResource(R.string.picker_retiring_on, providerName, expiry),
            style = MaterialTheme.typography.bodySmall,
            color = PulseColors.warning,
        )
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun MenuHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

private const val FILTER_THRESHOLD = 15
private const val MAX_ROWS = 60
