package com.enjoyyourtime.globalfitness.food.domain

import com.enjoyyourtime.globalfitness.food.model.FoodEntry
import com.enjoyyourtime.globalfitness.food.model.Meal
import com.enjoyyourtime.globalfitness.food.model.Nutrient
import com.enjoyyourtime.globalfitness.food.model.Nutrients
import com.enjoyyourtime.globalfitness.food.model.Portion
import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.roundToLong

/**
 * One day of the food log: its entries by meal (in [Meal] order, empty meals left out) with totals.
 * [partial]: nutrients whose day total adds up only some entries (the others have no value for it).
 */
data class DayLog(
    val date: LocalDate,
    val meals: Map<Meal, List<FoodEntry>>,
    val mealTotals: Map<Meal, Nutrients>,
    val total: Nutrients,
    val partial: Set<Nutrient> = emptySet(),
) {
    val isEmpty: Boolean get() = meals.isEmpty()
}

/** Portions, amounts, meals and totals of the food log. Pure Kotlin. */
object FoodMath {
    private val LUNCH = LocalTime.of(11, 0)
    private val SNACK = LocalTime.of(16, 0)
    private val DINNER = LocalTime.of(19, 30)

    /** The meal a food eaten at [time] most likely belongs to: before 11:00 breakfast, 11–16 lunch, 16–19:30 snack, then dinner. */
    fun mealFor(time: LocalTime): Meal = when {
        time < LUNCH -> Meal.BREAKFAST
        time < SNACK -> Meal.LUNCH
        time < DINNER -> Meal.SNACK
        else -> Meal.DINNER
    }

    /** The smallest count of a portion (half a plate). */
    const val MIN_PORTIONS = 0.5

    /** Portion counts step by one, and by halves below one: 0.5, 1, 2, 3… (a count with a half steps to the whole numbers around it). */
    fun morePortions(count: Double): Double = if (count < 1.0) 1.0 else kotlin.math.floor(count) + 1.0

    fun lessPortions(count: Double): Double = when {
        count <= 1.0 -> MIN_PORTIONS
        count % 1.0 != 0.0 -> kotlin.math.floor(count)
        else -> count - 1.0
    }

    /** Grams (ml) of [count] times [portion], rounded to 0.1. */
    fun amountOf(portion: Portion, count: Double): Double = round1(portion.amount * count)

    /** The values of [amount] g (ml) of a food with [per100]. */
    fun nutrientsFor(per100: Nutrients, amount: Double): Nutrients = per100.scaled(amount / 100.0)

    /** Back from an absolute snapshot to per-100 values (editing an entry of a food no longer known). */
    fun per100Of(nutrients: Nutrients, amount: Double): Nutrients = if (amount > 0) nutrients.scaled(100.0 / amount) else Nutrients.NONE

    /** The entries eaten on [date] (in the zone where each was eaten), by meal, with the totals. */
    fun dayLog(entries: Iterable<FoodEntry>, date: LocalDate): DayLog {
        val today = entries.filter { it.localDate == date }.sortedWith(compareBy({ it.eatenAt }, { it.createdAt }, { it.id }))
        val byMeal = LinkedHashMap<Meal, List<FoodEntry>>()
        for (meal in Meal.entries) {
            val list = today.filter { it.meal == meal }
            if (list.isNotEmpty()) byMeal[meal] = list
        }
        val totals = byMeal.mapValues { (_, list) -> Nutrients.sum(list.map { it.nutrients }) }
        return DayLog(date, byMeal, totals, Nutrients.sum(totals.values), partial(today.map { it.nutrients }))
    }

    /** The nutrients known for some of [items] but not for all: their sum is a lower bound. */
    fun partial(items: List<Nutrients>): Set<Nutrient> =
        Nutrient.entries.filterTo(LinkedHashSet()) { n -> items.any { it[n] != null } && items.any { it[n] == null } }

    /** Total kcal eaten on each day that has entries. */
    fun kcalByDay(entries: Iterable<FoodEntry>): Map<LocalDate, Double> =
        entries.groupBy { it.localDate }.mapValues { (_, list) -> list.sumOf { it.nutrients.kcal ?: 0.0 } }

    fun round1(v: Double): Double = (v * 10).roundToLong() / 10.0
}
