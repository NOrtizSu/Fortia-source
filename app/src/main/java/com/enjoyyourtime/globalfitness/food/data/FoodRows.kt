package com.enjoyyourtime.globalfitness.food.data

import com.enjoyyourtime.globalfitness.food.model.CustomFood
import com.enjoyyourtime.globalfitness.food.model.CustomFoodSource
import com.enjoyyourtime.globalfitness.food.model.EntrySource
import com.enjoyyourtime.globalfitness.food.model.FoodEntry
import com.enjoyyourtime.globalfitness.food.model.FoodUnit
import com.enjoyyourtime.globalfitness.food.model.Meal
import com.enjoyyourtime.globalfitness.food.model.Nutrient
import com.enjoyyourtime.globalfitness.food.model.Nutrients
import com.enjoyyourtime.globalfitness.food.model.PortionKey

/**
 * The rows of food.db's food_entries and custom_foods as column -> value maps (String, Long, Int,
 * Double or null), and back from a row read by column name. Pure Kotlin: [FoodDatabase] runs them
 * on the phone, the unit tests on a real SQLite.
 */
object FoodRows {
    fun entryRow(e: FoodEntry): Map<String, Any?> = linkedMapOf<String, Any?>(
        "id" to e.id,
        "eaten_at" to e.eatenAt,
        "zone_offset" to e.zoneOffsetSeconds,
        "meal" to e.meal.code,
        "food_id" to e.foodId,
        "custom_food_id" to e.customFoodId,
        "name" to e.name,
        "amount" to e.amount,
        "unit" to e.unit.code,
        "portion_key" to e.portionKey?.code,
        "portion_count" to e.portionCount,
    ).apply {
        putAll(nutrients(e.nutrients))
        put("source", e.source.code)
        put("photo", e.photo)
        put("model_label", e.modelLabel)
        put("model_confidence", e.modelConfidence)
        put("created_at", e.createdAt)
        put("updated_at", e.updatedAt)
        put("adjustments", AdjustmentsColumn.encode(e.adjustments))
        put("estimated_amount", e.estimatedAmount)
        put("values_edited", if (e.valuesEdited) 1L else 0L)
    }

    fun entryOf(row: (String) -> Any?): FoodEntry = FoodEntry(
        id = row.text("id")!!,
        eatenAt = row.long("eaten_at")!!,
        zoneOffsetSeconds = row.long("zone_offset")!!.toInt(),
        meal = Meal.fromCode(row.text("meal")),
        foodId = row.text("food_id"),
        customFoodId = row.text("custom_food_id"),
        name = row.text("name")!!,
        amount = row.double("amount")!!,
        unit = FoodUnit.fromCode(row.text("unit")),
        portionKey = PortionKey.fromCode(row.text("portion_key")),
        portionCount = row.double("portion_count"),
        nutrients = nutrientsOf(row),
        source = EntrySource.fromCode(row.text("source")),
        photo = row.text("photo"),
        modelLabel = row.text("model_label"),
        modelConfidence = row.double("model_confidence"),
        createdAt = row.long("created_at")!!,
        updatedAt = row.long("updated_at")!!,
        adjustments = AdjustmentsColumn.decode(row.text("adjustments")),
        estimatedAmount = row.double("estimated_amount"),
        valuesEdited = (row.long("values_edited") ?: 0L) != 0L,
    )

    fun customFoodRow(f: CustomFood): Map<String, Any?> = linkedMapOf<String, Any?>(
        "id" to f.id,
        "name" to f.name,
        "brand" to f.brand,
        "barcode" to f.barcode,
        "unit" to f.unit.code,
    ).apply {
        putAll(nutrients(f.per100))
        put("portion", f.portion)
        put("source", f.source.code)
        put("created_at", f.createdAt)
        put("updated_at", f.updatedAt)
        put("checked_at", f.checkedAt)
    }

    fun customFoodOf(row: (String) -> Any?): CustomFood = CustomFood(
        id = row.text("id")!!,
        name = row.text("name")!!,
        brand = row.text("brand"),
        barcode = row.text("barcode"),
        unit = FoodUnit.fromCode(row.text("unit")),
        per100 = nutrientsOf(row),
        portion = row.double("portion"),
        source = CustomFoodSource.fromCode(row.text("source")),
        createdAt = row.long("created_at")!!,
        updatedAt = row.long("updated_at")!!,
        checkedAt = row.long("checked_at"),
    )

    /** Every nutrient column (the column is the nutrient's code). */
    private fun nutrients(n: Nutrients): Map<String, Any?> = Nutrient.entries.associate { it.code to n[it] }

    private fun nutrientsOf(row: (String) -> Any?) = Nutrients.of { row.double(it.code) }

    private fun ((String) -> Any?).text(name: String): String? = this(name)?.toString()

    private fun ((String) -> Any?).long(name: String): Long? = (this(name) as? Number)?.toLong()

    private fun ((String) -> Any?).double(name: String): Double? = (this(name) as? Number)?.toDouble()
}
