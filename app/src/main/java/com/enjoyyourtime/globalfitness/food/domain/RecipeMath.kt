package com.enjoyyourtime.globalfitness.food.domain

import com.enjoyyourtime.globalfitness.food.model.AdjustLevel
import com.enjoyyourtime.globalfitness.food.model.IngredientAdjustment
import com.enjoyyourtime.globalfitness.food.model.Nutrient
import com.enjoyyourtime.globalfitness.food.model.Nutrients
import com.enjoyyourtime.globalfitness.food.model.RecipeIngredient
import kotlin.math.abs

/** What the user chose for one ingredient of a dish: a level of its normal amount, or grams typed. */
sealed interface IngredientChoice {
    data class Level(val level: AdjustLevel) : IngredientChoice

    data class Grams(val grams: Double) : IngredientChoice

    companion object {
        val NORMAL: IngredientChoice = Level(AdjustLevel.NORMAL)

        /** The choice an adjustment was made with (to edit it again). */
        fun of(a: IngredientAdjustment): IngredientChoice = AdjustLevel.ofFactor(a.factor)?.let(::Level) ?: Grams(a.grams)
    }
}

/** A dish with its ingredients changed: the values and the weight to log. */
data class AdjustedDish(val nutrients: Nutrients, val amount: Double)

/**
 * Changing the ingredients of a dish when it is logged (contract 3 of the food data): the dish's own
 * values stay authoritative and each change is applied as a delta, per nutrient
 * `adjusted = dish × total / 100 + Σ (new − old) × ingredient / 100`, where `old` is the recipe's
 * grams for the amount logged (`grams × total / 100`); the logged weight changes by `Σ (new − old)`
 * (ml counted as g). Pure Kotlin.
 */
object RecipeMath {
    /** The weight logged never drops below this (every ingredient removed from a small amount). */
    const val MIN_AMOUNT = 1.0

    /** A typed amount this close to the normal one changes nothing. */
    private const val SAME_GRAMS = 0.05

    /** The normal grams of [ingredient] in [dishAmount] g (ml) of its dish. */
    fun baseGrams(ingredient: RecipeIngredient, dishAmount: Double): Double = ingredient.grams * dishAmount / 100.0

    /**
     * The adjustments of [choices] (by ingredient id) for [dishAmount] of a dish with [recipe]: only
     * adjustable ingredients, and only those whose amount changes.
     */
    fun adjustments(recipe: List<RecipeIngredient>, dishAmount: Double, choices: Map<String, IngredientChoice>): List<IngredientAdjustment> =
        recipe.mapNotNull { r ->
            if (!r.adjustable) return@mapNotNull null
            val base = baseGrams(r, dishAmount)
            when (val c = choices[r.ingredient] ?: return@mapNotNull null) {
                is IngredientChoice.Level ->
                    if (c.level == AdjustLevel.NORMAL) null else IngredientAdjustment(r.ingredient, base, base * c.level.factor, c.level.factor)
                is IngredientChoice.Grams -> {
                    val grams = c.grams.coerceAtLeast(0.0)
                    if (abs(grams - base) < SAME_GRAMS) null else IngredientAdjustment(r.ingredient, base, grams, null)
                }
            }
        }

    /**
     * [dishAmount] of a dish with [dishPer100], with [adjustments]. An unknown value of the dish stays
     * unknown; an unknown value of an ingredient adds nothing to its nutrient (the rest of the change
     * still counts); nothing goes below zero. [ingredientPer100]: an ingredient's values (null: not in
     * the catalog any more, its change counts for the weight only).
     */
    fun apply(dishPer100: Nutrients, dishAmount: Double, adjustments: List<IngredientAdjustment>, ingredientPer100: (String) -> Nutrients?): AdjustedDish {
        val base = FoodMath.nutrientsFor(dishPer100, dishAmount)
        if (adjustments.isEmpty()) return AdjustedDish(base, dishAmount)
        val delta = delta(adjustments, ingredientPer100)
        val nutrients = Nutrients.of { n -> base[n]?.let { (it + delta[n.ordinal]).coerceAtLeast(0.0) } }
        return AdjustedDish(nutrients, (dishAmount + adjustments.sumOf { it.delta }).coerceAtLeast(MIN_AMOUNT))
    }

    /**
     * The values per 100 g of the dish before [adjustments], from an entry that logged [nutrients] for
     * [amount] with them: [apply] undone with the same ingredient values, so the entry opened again and
     * saved unchanged keeps its values.
     */
    fun unadjustedPer100(nutrients: Nutrients, amount: Double, adjustments: List<IngredientAdjustment>, ingredientPer100: (String) -> Nutrients?): Nutrients {
        val dish = amount - adjustments.sumOf { it.delta }
        if (adjustments.isEmpty() || dish <= 0) return FoodMath.per100Of(nutrients, amount)
        val delta = delta(adjustments, ingredientPer100)
        return Nutrients.of { n -> nutrients[n]?.let { (it - delta[n.ordinal]).coerceAtLeast(0.0) * 100.0 / dish } }
    }

    /**
     * The ingredients the confirm page offers for a logged dish: its [recipe] as the catalog has it
     * now, plus any ingredient the entry changed that the recipe no longer lists (with the share it had
     * in [dishAmount]), so an entry opened again keeps every change it was saved with.
     */
    fun ingredientsFor(recipe: List<RecipeIngredient>, adjustments: List<IngredientAdjustment>, dishAmount: Double): List<RecipeIngredient> {
        val listed = recipe.mapTo(HashSet()) { it.ingredient }
        val gone = adjustments.filter { it.ingredient !in listed && dishAmount > 0 }
            .map { RecipeIngredient(it.ingredient, it.baseGrams * 100.0 / dishAmount, adjustable = true) }
        // An ingredient the entry changed stays adjustable even if the recipe no longer says so.
        val changed = adjustments.mapTo(HashSet()) { it.ingredient }
        return recipe.map { if (!it.adjustable && it.ingredient in changed) it.copy(adjustable = true) else it } + gone
    }

    /** Σ (new − old) × ingredient / 100 for every nutrient (by ordinal); unknown values add nothing. */
    private fun delta(adjustments: List<IngredientAdjustment>, ingredientPer100: (String) -> Nutrients?): DoubleArray {
        val out = DoubleArray(Nutrient.entries.size)
        for (a in adjustments) {
            val per100 = ingredientPer100(a.ingredient) ?: continue
            for (n in Nutrient.entries) per100[n]?.let { out[n.ordinal] += a.delta * it / 100.0 }
        }
        return out
    }
}
