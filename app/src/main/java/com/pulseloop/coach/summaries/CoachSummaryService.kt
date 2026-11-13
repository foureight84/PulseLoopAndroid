package com.pulseloop.coach.summaries

import com.pulseloop.coach.config.CoachClientResolver
import com.pulseloop.coach.config.CoachProviderSettingsStore
import com.pulseloop.coach.config.CoachSleepSyncGate
import com.pulseloop.coach.config.CoachVarietyHints
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
    /** The user's step-source preference (review item 5, PR #98). Applied per regeneration so
     *  the coach's today/sleep summaries quote the same steps Today shows — Path B's separate
     *  columns otherwise let the coach read ring steps while Today read phone steps. */
    private val stepSourcePrefs: com.pulseloop.settings.StepSourcePrefs,
    /** Given the stored night's end: is the wearer's Bedtime / quiet-hours window still open (#140)? */
    private val nightInProgress: (sessionEndAt: Long) -> Boolean = { false },
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
        val built = CoachSummaryContextBuilder.today(db, stepSourcePrefs.preferPhone())
        val existing = db.coachSummaryDao().get(CoachSummaryKind.TODAY.rawValue, built.scopeKey)
        val now = System.currentTimeMillis()

        if (existing != null) {
            if (existing.dataSignature == built.signature) return       // no new data
            if (now - existing.updatedAt < minIntervalMs) return        // rate limited
        }
        generateAndUpsert(CoachSummaryKind.TODAY, built, existing)
    }

    suspend fun refreshSleepDayIfNeeded(now: Long = System.currentTimeMillis()) {
        val built = CoachSummaryContextBuilder.sleepDay(db, stepSourcePrefs.preferPhone()) ?: return
        val endAt = built.sleepSessionEnd ?: return
        val lastFullSyncAt = db.deviceDao().current()?.lastFullSyncAt
        if (!CoachSleepSyncGate.sleepDaySafeToSummarize(endAt, lastFullSyncAt, now, nightInProgress(endAt))) return

        // Signature-based upsert: regenerate when the signature differs and the floor has
        // passed, or straight away when a late sync grew the night (#140).
        val existing = db.coachSummaryDao().get(CoachSummaryKind.SLEEP_DAY.rawValue, built.scopeKey)
        if (existing != null && !CoachSleepSyncGate.sleepCardRewriteDue(
                existing.dataSignature, existing.updatedAt, built.signature, now, minIntervalMs)) return
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
        val snapshot = providerSettings?.snapshot()
        val apiKey = if (resolution != null) resolution.key ?: "" else apiKeyStore.apiKey
        val model = snapshot
            ?.let { CoachClientResolver.activeModel(it, apiKeyStore.model) }
            ?: apiKeyStore.model.ifEmpty { com.pulseloop.coach.config.OpenAIModel.DEFAULT.slug }
        val flags = CoachFeatureFlags(
            coachEnabled = apiKeyStore.coachEnabled && apiKey.isNotEmpty(),
            webSearchEnabled = apiKeyStore.webSearchEnabled,
            model = model,
            settings = CoachClientResolver.coachSettings(snapshot, model),
        )
        val angle = CoachVarietyHints.angle(built.scopeKey + kind.rawValue)
        val recentTexts = recentSummaryTexts(kind, excludeId = existing?.id)
        val content = CoachSummaryGenerator.generate(
            kind, built.contextJson, built.fallback, flags, apiKey, resolution?.client,
            angle = angle, recentTexts = recentTexts,
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

    /** The last few cards of this kind (title — body) so the generator can avoid
     *  repeating their phrasing/openings. Newest first; the row being regenerated
     *  is excluded. */
    private suspend fun recentSummaryTexts(kind: CoachSummaryKind, excludeId: String?, limit: Int = 5): List<String> {
        val rows = db.coachSummaryDao().recent(kind.rawValue, limit + 1)
        return rows.filter { it.id != excludeId }.take(limit).map { "${it.title} — ${it.body}" }
    }
}
