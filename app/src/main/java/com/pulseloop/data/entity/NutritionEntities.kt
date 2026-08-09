package com.pulseloop.data.entity

import androidx.room.*

@Entity(tableName = "meal_entries", indices = [Index("date")])
data class MealEntryEntity(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val date: Long,
    val timestamp: Long,
    val name: String,
    val mealTypeRaw: String = "snack",
    val calories: Double,
    val proteinG: Double = 0.0,
    val carbsG: Double = 0.0,
    val fatG: Double = 0.0,
    val fiberG: Double? = null,
    val sugarG: Double? = null,
    val sodiumMg: Double? = null,
    val sourceRaw: String = "manual",
    val offProductCode: String? = null,
    val servingDescription: String? = null,
    val servingGrams: Double? = null,
    val quantity: Double = 1.0,
    val confidenceRaw: String = "medium",
    val userEdited: Boolean = false,
    val notes: String? = null,
    val loggedByCoach: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "food_products", indices = [Index("lastUsedAt")])
data class CachedFoodProductEntity(
    @PrimaryKey val code: String,
    val name: String,
    val brand: String? = null,
    val energyKcal100g: Double,
    val protein100g: Double = 0.0,
    val carbs100g: Double = 0.0,
    val fat100g: Double = 0.0,
    val fiber100g: Double? = null,
    val sugars100g: Double? = null,
    val saturatedFat100g: Double? = null,
    val sodiumMg100g: Double? = null,
    val servingSizeText: String? = null,
    val servingQuantityG: Double? = null,
    val lastUsedAt: Long = System.currentTimeMillis(),
    val useCount: Int = 0,
)
