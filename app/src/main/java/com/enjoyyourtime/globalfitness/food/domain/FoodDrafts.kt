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

/**
 * A food the confirm screen can switch to (one of the variants of a dish), with its ingredients,
 * what a new entry takes from its last one ([recall], none when editing) and the food's own values
 * ([own], what "Restore" brings back; null for an entry whose food is no longer known).
 */
data class DraftOption(
    val item: FoodItem,
    val recipe: List<RecipeIngredient>,
    val recall: Recall? = null,
    val own: Nutrients? = item.per100,
)

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

/** An amount to start with, and the photo's suggestion for the food (shown under it, whether used or not). */
data class AmountStart(val amount: Double, val estimate: Double?)

/** How much a draft starts with: grams (ml) of the dish, and the portion with its count when it is one. */
data class DraftAmount(val amount: Double, val portion: Portion?, val portionCount: Double?)

/**
 * An item of a plate as "Add all" logs it: its values and weight (ingredient changes included), the
 * changes, its portion, and whether its values are typed ones recalled from its last entry.
 */
data class PlateDish(
    val nutrients: Nutrients,
    val amount: Double,
    val adjustments: List<IngredientAdjustment>,
    val portion: Portion?,
    val portionCount: Double?,
    val valuesEdited: Boolean,
)

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
     * ([typed]); otherwise the amount of the food's last entry ([recall]), else the photo's
     * suggestion for that food ([grams], the item being [share] of the plate), else the food's
     * default portion. [AmountStart.estimate] is that suggestion unless the amount was typed (the
     * screen then says "≈ … (estimated)").
     */
    fun plateAmount(food: FoodItem, grams: GramsEstimator?, share: Double, typed: Double?, recall: Recall? = null): AmountStart {
        if (typed != null) return AmountStart(typed, null)
        val estimate = grams?.estimate(food.portion, share)
        return AmountStart(recall?.amount ?: estimate ?: defaultAmount(food), estimate)
    }

    /**
     * How a new draft of [item] starts: as its last entry ([recall]) when there is one, else with
     * the photo's suggestion ([estimate], in grams), else with its default portion.
     */
    fun startAmount(item: FoodItem, recall: Recall?, estimate: Double?): DraftAmount {
        if (recall != null) return DraftAmount(recall.amount, recall.portion, recall.portionCount.takeIf { recall.portion != null })
        if (estimate != null) return DraftAmount(estimate, null, null)
        val portion = defaultPortion(item)
        return DraftAmount(defaultAmount(item), portion, portion?.let { 1.0 })
    }

    /**
     * The amount when the confirm screen switches to the variant [to]: as its own last entry
     * ([toRecall]); its defaults when the amount shown was the previous food's last entry's
     * ([fromRecall]: the photo's suggestion for it, else its default portion); else the photo's
     * suggestion for it ([suggested], while the user set no amount), else the same named portion
     * ([portionKey], [count]) or its default one. Null: the amount typed stays, in grams.
     */
    fun switchAmount(to: FoodItem, toRecall: Recall?, fromRecall: Boolean, suggested: Double?, portionKey: String?, count: Double): DraftAmount? = when {
        toRecall != null -> startAmount(to, toRecall, null)
        fromRecall -> startAmount(to, null, suggested)
        suggested != null -> DraftAmount(suggested, null, null)
        else -> portionAfterSwitch(to, portionKey)?.let { DraftAmount(FoodMath.amountOf(it, count), it, count) }
    }

    /**
     * [amount] of [food] (an item of a plate) as "Add all" logs it: with its last entry's ingredient
     * changes and typed values ([recall]) through the same maths as the confirm screen, and the
     * last entry's portion when the amount is still that, else the default portion when it is that.
     */
    fun plateDish(food: FoodItem, amount: Double, recall: Recall?, recipe: List<RecipeIngredient>, ingredientPer100: (String) -> Nutrients?): PlateDish {
        val adjustments = RecipeMath.adjustments(recipe, amount, recall?.choices.orEmpty())
        val per100 = recall?.per100 ?: food.per100
        val dish = RecipeMath.apply(per100, amount, adjustments, ingredientPer100)
        val own = RecipeMath.apply(food.per100, amount, adjustments, ingredientPer100).nutrients
        val asLast = recall?.takeIf { it.portion != null && it.portionCount != null && it.amount == amount }
        val portion = asLast?.portion ?: defaultPortion(food)?.takeIf { it.amount == amount }
        return PlateDish(
            nutrients = dish.nutrients,
            amount = dish.amount,
            adjustments = adjustments,
            portion = portion,
            portionCount = asLast?.portionCount ?: portion?.let { 1.0 },
            valuesEdited = ValueEdits.edited(recall?.per100 != null, emptyMap(), dish.nutrients, own),
        )
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
