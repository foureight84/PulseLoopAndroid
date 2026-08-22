package com.pulseloop.nutrition

import com.pulseloop.data.dao.FoodProductDao
import com.pulseloop.data.entity.CachedFoodProductEntity

/**
 * Bounded-LRU helpers over [FoodProductDao] — the port of the "Open Food Facts product
 * cache" section of `NutritionRepository` in Services/Repositories.swift (iOS PR #96).
 *
 * The ODbL usage rules are cache-first: every product lookup that reaches OFF maps to a real
 * user action (search submit, barcode scan, coach tool call), so pickers must always consult
 * this table before the network. The table is the cache — [OpenFoodFactsClient] caches
 * nothing.
 */
object FoodProductCache {
    /** LRU cap for the Open Food Facts product cache (iOS `maxCachedProducts`). */
    const val MAX_CACHED_PRODUCTS = 500

    /** The cached product for an OFF code, or null — check this before any network call. */
    suspend fun byCode(dao: FoodProductDao, code: String): CachedFoodProductEntity? =
        dao.byCode(code)

    /** Most recently used cached products for the quick-log "recent foods" list. */
    suspend fun recent(dao: FoodProductDao, limit: Int = 12): List<CachedFoodProductEntity> =
        dao.recent(limit)

    /**
     * Mark a cached product as used (bumps the frequency/recency signals). Re-upserts the
     * cache row only — iOS's `touchProduct` mutates the row and lets the caller batch the
     * save with the meal insert; Room has no unsaved state, so this writes exactly that one
     * row and nothing else.
     */
    suspend fun touch(dao: FoodProductDao, entity: CachedFoodProductEntity) {
        dao.upsert(entity.copy(useCount = entity.useCount + 1, lastUsedAt = System.currentTimeMillis()))
    }

    /**
     * Insert or refresh a fetched product in the cache (keyed on its OFF code), keeping the
     * LRU bounded. Ported from `upsertCachedProduct`: an existing row has its
     * name/brand/nutriments/serving refreshed and its recency restamped, and the prune runs
     * only when actually over the cap (cheap count probe — the old iOS
     * prune-on-every-insert did a full-table sort per upsert).
     */
    suspend fun upsertCached(dao: FoodProductDao, product: FoodProduct): CachedFoodProductEntity {
        val now = System.currentTimeMillis()
        val row = dao.byCode(product.code)
            ?.copy(
                name = product.name,
                brand = product.brand,
                energyKcal100g = product.energyKcal100g,
                protein100g = product.protein100g,
                carbs100g = product.carbs100g,
                fat100g = product.fat100g,
                fiber100g = product.fiber100g,
                sugars100g = product.sugars100g,
                saturatedFat100g = product.saturatedFat100g,
                sodiumMg100g = product.sodiumMg100g,
                servingSizeText = product.servingSizeText,
                servingQuantityG = product.servingQuantityG,
                lastUsedAt = now,
            )
            ?: product.asCachedProduct().copy(lastUsedAt = now)
        dao.upsert(row)
        if (dao.count() > MAX_CACHED_PRODUCTS) dao.prune(MAX_CACHED_PRODUCTS)
        return row
    }
}
