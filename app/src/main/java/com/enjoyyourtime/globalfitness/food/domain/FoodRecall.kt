package com.enjoyyourtime.globalfitness.food.domain

import com.enjoyyourtime.globalfitness.food.model.FoodEntry
import com.enjoyyourtime.globalfitness.food.model.FoodItem
import com.enjoyyourtime.globalfitness.food.model.FoodRef
import com.enjoyyourtime.globalfitness.food.model.Nutrients
import com.enjoyyourtime.globalfitness.food.model.Portion
import com.enjoyyourtime.globalfitness.food.model.RecipeIngredient
import java.time.LocalDate
import kotlin.math.abs

/**
 * What a new entry of a food takes from the food's last entry ("as last time"): the amount of the
 * dish (with its portion and count while the food still has that portion, same size), the
 * ingredients changed (as choices: a level follows the new amount, grams typed stay) and the values
 * typed by hand ([per100], the dish's own values per 100 g; null: the food's own values).
 */
data class Recall(
    val entryId: String,
    /** The day it was eaten (where it was eaten). */
    val date: LocalDate,
    val amount: Double,
    val portion: Portion?,
    val portionCount: Double?,
    /** By ingredient id; only adjustable ingredients of the food's recipe as it is now. */
    val choices: Map<String, IngredientChoice>,
    val per100: Nutrients?,
)

/** Finding a food's last entry and what a new entry of it takes from it. Pure Kotlin. */
object FoodRecall {
    /** A portion this close to the amount logged is still the same portion. */
    private const val SAME_AMOUNT = 0.1

    /**
     * The last entry of [ref] (the exact catalog food, a variant being its own food, or the custom
     * food): the latest eaten, then the latest changed. A quick entry ([ref] null) has none.
     */
    fun lastEntry(entries: Iterable<FoodEntry>, ref: FoodRef?): FoodEntry? {
        if (ref == null) return null
        return entries.filter { FoodRef.of(it.foodId, it.customFoodId) == ref }
            .maxWithOrNull(compareBy<FoodEntry>({ it.eatenAt }, { it.updatedAt }))
    }

    /**
     * What a new draft of [item] takes from [last]: its [recipe] as the catalog has it now (only those
     * ingredient changes are kept), [ingredientPer100] to take the changes out of its values, and
     * [foodUpdatedAt] (a custom food's last change: values changed after that entry are the food's).
     */
    fun recall(
        item: FoodItem,
        last: FoodEntry,
        recipe: List<RecipeIngredient>,
        ingredientPer100: (String) -> Nutrients?,
        foodUpdatedAt: Long? = null,
    ): Recall {
        val adjustments = last.adjustments.takeIf { last.dishAmount > 0 }.orEmpty()
        val dishAmount = if (adjustments.isEmpty()) last.amount else last.dishAmount
        val count = last.portionCount?.takeIf { it > 0 }
        val portion = last.portionKey?.let { k -> item.portions.firstOrNull { it.key == k } }
            ?.takeIf { p -> count != null && abs(FoodMath.amountOf(p, count) - dishAmount) < SAME_AMOUNT }
        val adjustable = recipe.filter { it.adjustable }.mapTo(HashSet()) { it.ingredient }
        val choices = adjustments.filter { it.ingredient in adjustable }.associate { it.ingredient to IngredientChoice.of(it) }
        val foodChanged = foodUpdatedAt != null && foodUpdatedAt > last.updatedAt
        val per100 = if (last.valuesEdited && !foodChanged) RecipeMath.unadjustedPer100(last.nutrients, last.amount, adjustments, ingredientPer100) else null
        return Recall(
            entryId = last.id,
            date = last.localDate,
            amount = FoodMath.round1(dishAmount),
            portion = portion,
            portionCount = count.takeIf { portion != null },
            choices = choices,
            per100 = per100,
        )
    }
}
