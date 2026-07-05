package com.pulseloop.coach.summaries

import com.pulseloop.coach.config.CoachClientResolver
import com.pulseloop.coach.config.CoachProviderSettingsStore
import com.pulseloop.coach.tools.CoachFeatureFlags
import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.data.entity.CoachSummaryEntity
import com.pulseloop.settings.ApiKeyStore
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Ported from CoachSummaryService.swift.
 * Owns the Today/Sleep coach-card summaries: self-gating regeneration and the
 * tap → seeded-chat flow. Read the current summary with currentToday/currentSleepDay;
 * trigger regeneration with refreshTodayIfNeeded/refreshSleepDayIfNeeded
 * (safe to call often — they gate on data signature + rate limit).
 */
class CoachSummaryService(
    private val db: PulseLoopDatabase,
    private val apiKeyStore: ApiKeyStore,
    /** Multi-provider settings; when present, summaries run through
     *  `CoachClientResolver` (Gemini/OpenRouter follow the provider picker).
     *  null keeps the legacy OpenAI-only behavior. */
    private val providerSettings: CoachProviderSettingsStore? = null,
) {
    /** Minimum gap between Today / aggregate-sleep regenerations. */
    private val minIntervalMs = 2 * 3600_000L // 2 hours

    // ── Reads ────────────────────────────────────────────────────────────

    suspend fun currentToday(): CoachSummaryEntity? =
        db.coachSummaryDao().get(CoachSummaryKind.TODAY.rawValue,
            java.time.LocalDate.now().toString())

    suspend fun currentSleepDay(): CoachSummaryEntity? =
        db.coachSummaryDao().latest(CoachSummaryKind.SLEEP_DAY.rawValue)

    // ── Refresh (self-gating) ────────────────────────────────────────────

    suspend fun refreshTodayIfNeeded() {
        val built = CoachSummaryContextBuilder.today(db)
        val existing = db.coachSummaryDao().get(CoachSummaryKind.TODAY.rawValue, built.scopeKey)
        val now = System.currentTimeMillis()

        if (existing != null) {
            if (existing.dataSignature == built.signature) return       // no new data
            if (now - existing.updatedAt < minIntervalMs) return        // rate limited
        }
        generateAndUpsert(CoachSummaryKind.TODAY, built, existing)
    }

    suspend fun refreshSleepDayIfNeeded() {
        val built = CoachSummaryContextBuilder.sleepDay(db) ?: return
        val existing = db.coachSummaryDao().get(CoachSummaryKind.SLEEP_DAY.rawValue, built.scopeKey)
        // Once per night: only when not yet summarized
        if (existing != null) return
        generateAndUpsert(CoachSummaryKind.SLEEP_DAY, built, existing)
    }

    // ── Generate + Persist ───────────────────────────────────────────────

    private suspend fun generateAndUpsert(
        kind: CoachSummaryKind,
        built: CoachSummaryContextBuilder.Built,
        existing: CoachSummaryEntity?,
    ) {
        // Resolve the active provider (key readiness + client + model). Without a
        // provider-settings store this falls back to the legacy OpenAI-only path.
        val resolution = providerSettings?.let { CoachClientResolver.resolve(it, apiKeyStore) }
        val apiKey = if (resolution != null) resolution.key ?: "" else apiKeyStore.apiKey
        val model = providerSettings
            ?.let { CoachClientResolver.activeModel(it.snapshot(), apiKeyStore.model) }
            ?: apiKeyStore.model.ifEmpty { "gpt-5.4" }
        val flags = CoachFeatureFlags(
            coachEnabled = apiKeyStore.coachEnabled && apiKey.isNotEmpty(),
            webSearchEnabled = apiKeyStore.webSearchEnabled,
            model = model,
        )
        val content = CoachSummaryGenerator.generate(
            kind, built.contextJson, built.fallback, flags, apiKey, resolution?.client,
        )
        val now = System.currentTimeMillis()
        if (existing != null) {
            db.coachSummaryDao().upsert(existing.copy(
                title = content.title,
                body = content.body,
                chipsJSON = Json.encodeToString(
                    ListSerializer(String.serializer()),
                    content.chips
                ),
                dataSignature = built.signature,
                updatedAt = now,
            ))
        } else {
            db.coachSummaryDao().upsert(CoachSummaryEntity(
                kind = kind.rawValue,
                scopeKey = built.scopeKey,
                title = content.title,
                body = content.body,
                chipsJSON = Json.encodeToString(
                    ListSerializer(String.serializer()),
                    content.chips
                ),
                dataSignature = built.signature,
                createdAt = now,
                updatedAt = now,
            ))
        }
    }
}
