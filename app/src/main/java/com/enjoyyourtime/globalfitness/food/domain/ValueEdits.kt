package com.enjoyyourtime.globalfitness.food.domain

import com.enjoyyourtime.globalfitness.food.model.IngredientAdjustment
import com.enjoyyourtime.globalfitness.food.model.Nutrient
import com.enjoyyourtime.globalfitness.food.model.Nutrients
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Values typed by hand on the confirm screen ("Edit values"). A value is typed for the amount shown
 * (the total, with the ingredient changes) and kept as the dish's own value per 100 g for that
 * nutrient only, the ingredient changes taken out: a later change of the amount or of the
 * ingredients still applies on top, and the other nutrients keep the food's values. Pure Kotlin.
 */
object ValueEdits {
    /** [per100] with the values typed by hand ([overrides], per 100 g of the dish; a null value is unknown). */
    fun apply(per100: Nutrients, overrides: Map<Nutrient, Double?>): Nutrients =
        if (overrides.isEmpty()) per100 else Nutrients.of { n -> if (n in overrides) overrides[n] else per100[n] }

    /**
     * The dish's own value of [n] per 100 g for which [dishAmount] of it with [adjustments] gives
     * [total] (the value typed): [RecipeMath.unadjustedPer100] for that nutrient, never below zero.
     */
    fun per100Of(n: Nutrient, total: Double, dishAmount: Double, adjustments: List<IngredientAdjustment>, ingredientPer100: (String) -> Nutrients?): Double {
        if (dishAmount <= 0) return 0.0
        // The amount logged with the changes, unclamped: undoing them gives back the dish amount exactly.
        val logged = dishAmount + adjustments.sumOf { it.delta }
        return RecipeMath.unadjustedPer100(Nutrients.NONE.with(n, total), logged, adjustments, ingredientPer100)[n] ?: 0.0
    }

    /** [v] as the fields show it: whole kcal, other values to 0.1 (0.01 under 1). */
    fun rounded(n: Nutrient, v: Double): Double = when {
        n == Nutrient.KCAL -> v.roundToLong().toDouble()
        abs(v) < 1 -> (v * 100).roundToLong() / 100.0
        else -> (v * 10).roundToLong() / 10.0
    }

    /** The smallest change the fields show for [v] (see [rounded]). */
    fun step(n: Nutrient, v: Double): Double = when {
        n == Nutrient.KCAL -> 1.0
        abs(v) < 1 -> 0.01
        else -> 0.1
    }

    /**
     * What is saved instead of a value typed under what the ingredient changes alone add: [saved] when
     * it is higher than [typed] by more than half a step of the field, else null (rounding is no change).
     */
    fun keptInstead(n: Nutrient, typed: Double, saved: Double): Double? = saved.takeIf { it - typed > step(n, typed) / 2 }

    /** Some value of [values] shows differently from [own] (both for the same amount); unknown differs from any value. */
    fun differs(values: Nutrients, own: Nutrients): Boolean =
        Nutrient.entries.any { n -> values[n]?.let { rounded(n, it) } != own[n]?.let { rounded(n, it) } }

    /**
     * Whether an entry saved with [values] has values typed by hand: some were typed now
     * ([overrides]) or it started from such values ([startedEdited]), and they differ from what the
     * food's own values give for the same amount ([own]; null when the food is no longer known: what
     * was typed stays typed).
     */
    fun edited(startedEdited: Boolean, overrides: Map<Nutrient, Double?>, values: Nutrients, own: Nutrients?): Boolean = when {
        !startedEdited && overrides.isEmpty() -> false
        own == null -> true
        else -> differs(values, own)
    }

    /**
     * The values per 100 g the confirm screen works from for [option] ([recall]: what a new entry took
     * from the food's last one; [editingEntry]: [option] is the entry being edited, which had values
     * typed by hand when [entryEdited]):
     * - a new entry starts with the values typed last time, else the food's own; "Restore" and the
     *   comparison use the food's own values;
     * - an entry edited again starts with its own values. If they were typed by hand, "Restore" brings
     *   back the food's own values and they are compared with those; otherwise they stay what it is
     *   compared with and "Restore" only drops what was typed now (a newer catalog never rewrites it).
     */
    fun base(option: DraftOption, recall: Recall?, editingEntry: Boolean, entryEdited: Boolean): ValueBase {
        val item = option.item.per100
        return when {
            editingEntry && entryEdited -> ValueBase(item, restore = option.own, reference = option.own, startedEdited = true)
            editingEntry -> ValueBase(item, restore = null, reference = item, startedEdited = false)
            else -> {
                val own = option.own ?: item
                ValueBase(recall?.per100 ?: item, restore = own, reference = own, startedEdited = recall?.per100 != null)
            }
        }
    }

    /** The values shown and saved: [base]'s start, or after "Restore" ([restored]) its own values, with [overrides]. */
    fun values(base: ValueBase, restored: Boolean, overrides: Map<Nutrient, Double?>): Nutrients =
        apply(if (restored && base.restore != null) base.restore else base.start, overrides)

    /** "Restore" has something to bring back: values typed now, or values typed before and the food's own to go back to. */
    fun restorable(base: ValueBase, restored: Boolean, overrides: Map<Nutrient, Double?>): Boolean =
        overrides.isNotEmpty() || base.startedEdited && !restored && base.restore != null

    /**
     * [FoodEntry.valuesEdited] of the entry saved with [values] ([reference]: [ValueBase.reference] for
     * the same amount and ingredients). A quick entry ([hasFood] false: no food to start from again) never has it.
     */
    fun savedEdited(base: ValueBase, restored: Boolean, overrides: Map<Nutrient, Double?>, values: Nutrients, reference: Nutrients?, hasFood: Boolean): Boolean =
        hasFood && edited(base.startedEdited && !restored, overrides, values, reference)
}

/**
 * What the confirm screen's values start from ([start], per 100 g), what "Restore" brings back
 * ([restore]; null: only what was typed now goes), what they are compared with to tell whether they
 * were changed by hand ([reference]; null: nothing to compare with, what was typed stays typed), and
 * whether they start as typed by hand ([startedEdited]). See [ValueEdits.base].
 */
data class ValueBase(val start: Nutrients, val restore: Nutrients?, val reference: Nutrients?, val startedEdited: Boolean)
