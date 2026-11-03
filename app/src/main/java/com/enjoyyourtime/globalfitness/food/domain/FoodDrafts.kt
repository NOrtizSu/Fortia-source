package com.enjoyyourtime.globalfitness.food.domain

import com.enjoyyourtime.globalfitness.food.catalog.FoodCatalog
import com.enjoyyourtime.globalfitness.food.model.CatalogFood
import com.enjoyyourtime.globalfitness.food.model.FoodItem
import com.enjoyyourtime.globalfitness.food.model.FoodUnit
import com.enjoyyourtime.globalfitness.food.model.IngredientAdjustment
import com.enjoyyourtime.globalfitness.food.model.Nutrients
import com.enjoyyourtime.globalfitness.food.model.Portion
import com.enjoyyourtime.globalfitness.food.model.RecipeIngredient
import com.enjoyyourtime.globalfitness.food.recognition.GramsEstimator

/** A food the confirm screen can switch to (one of the variants of a dish), with its ingredients. */
data class DraftOption(val item: FoodItem, val recipe: List<RecipeIngredient>)

/** An ingredient of a dish as the confirm screen shows it (a catalog food). */
data class IngredientInfo(val id: String, val name: String, val unit: FoodUnit, val per100: Nutrients)

/** What the confirm screen offers around a food: its ingredients, its variants, and those of their recipes. */
data class DraftShape(
    val recipe: List<RecipeIngredient>,
    val options: List<DraftOption>,
    val ingredients: Map<String, IngredientInfo>,
) {
    companion object {
        val NONE = DraftShape(emptyList(), emptyList(), emptyMap())
    }
}

/** An amount to start with, and the photo's suggestion when it is that suggestion. */
data class AmountStart(val amount: Double, val estimate: Double?)

/** Building the confirm screen's draft of a food (pure Kotlin). */
object FoodDrafts {
    /** The portion a food starts with: its default one if it is a named portion, else its only one. */
    fun defaultPortion(item: FoodItem): Portion? =
        item.portions.firstOrNull { it.amount == item.portion } ?: item.portions.firstOrNull()?.takeIf { item.portion == null }

    /**
     * The variants and ingredients of [item] (a catalog food; anything else has none). [item] itself
     * stands for its own option, not the catalog's copy: an entry being edited keeps its own values
     * (and [adjustments], the ingredients it changed in [dishAmount], stay offered even if its recipe
     * no longer lists them).
     */
    fun shape(
        item: FoodItem,
        foods: Map<String, CatalogFood>,
        recipes: Map<String, List<RecipeIngredient>>,
        name: (String) -> String,
        adjustments: List<IngredientAdjustment> = emptyList(),
        dishAmount: Double = 0.0,
    ): DraftShape {
        val id = item.foodId ?: return DraftShape.NONE
        val recipe = RecipeMath.ingredientsFor(recipes[id].orEmpty(), adjustments, dishAmount).filter { it.ingredient in foods }
        val options = FoodCatalog.variantsOf(id, foods).map { f ->
            if (f.id == id) DraftOption(item, recipe) else DraftOption(FoodItem.of(f, name(f.id)), recipes[f.id].orEmpty())
        }
        val ingredients = (recipe + options.flatMap { it.recipe }).map { it.ingredient }.distinct().mapNotNull { ing ->
            foods[ing]?.let { IngredientInfo(it.id, name(it.id), it.unit, it.per100) }
        }.associateBy { it.id }
        return DraftShape(recipe, options, ingredients)
    }

    /** The amount a food starts with when nothing else says: its default portion, else 100 g (ml). */
    fun defaultAmount(item: FoodItem): Double = defaultPortion(item)?.amount ?: item.portion ?: DEFAULT_AMOUNT

    /**
     * The amount of an item of a plate whose food is now [food]: an amount the user typed stays
     * ([typed]); otherwise the photo's suggestion for that food ([grams], the item being [share] of
     * the plate), else the food's default portion. [AmountStart.estimate] is set only when the
     * suggestion is used (the screen then says "≈ … (estimated)").
     */
    fun plateAmount(food: FoodItem, grams: GramsEstimator?, share: Double, typed: Double?): AmountStart {
        if (typed != null) return AmountStart(typed, null)
        val estimate = grams?.estimate(food.portion, share)
        return AmountStart(estimate ?: defaultAmount(food), estimate)
    }

    const val DEFAULT_AMOUNT = 100.0

    /**
     * When the confirm screen switches to [to]: the same named portion if it has one (its own amount),
     * else its default portion; null keeps the amount typed.
     */
    fun portionAfterSwitch(to: FoodItem, portionKey: String?): Portion? {
        if (portionKey == null) return null
        return to.portions.firstOrNull { it.key.code == portionKey } ?: defaultPortion(to)
    }
}
