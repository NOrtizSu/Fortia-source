package com.enjoyyourtime.globalfitness.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.enjoyyourtime.globalfitness.GlobalFitnessApp
import com.enjoyyourtime.globalfitness.food.data.GoalSettings
import com.enjoyyourtime.globalfitness.food.domain.FoodMath
import com.enjoyyourtime.globalfitness.food.domain.GoalAim
import com.enjoyyourtime.globalfitness.food.domain.GoalMode
import com.enjoyyourtime.globalfitness.food.model.EntrySource
import com.enjoyyourtime.globalfitness.food.model.FoodEntry
import com.enjoyyourtime.globalfitness.food.model.Meal
import com.enjoyyourtime.globalfitness.food.model.PortionKey
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Debug-only test helper: logs a sample day of food from the catalog (breakfast, lunch and an
 * afternoon snack, as searched: no meal photos) and sets the daily goal to automatic, "maintain",
 * so the Comida tab shows the ring with what is left for dinner (store screenshots, see
 * docs/store-assets, and checks on an emulator).
 *
 *     adb shell am broadcast -n com.enjoyyourtime.globalfitness/.debug.DebugFoodSeedReceiver
 *
 * The day's entries are deleted first, so a second run gives the same day (`--ez replace false`
 * keeps them); `--es date 2026-10-17` logs another day than today.
 */
class DebugFoodSeedReceiver : BroadcastReceiver() {

    private data class Item(val meal: Meal, val time: LocalTime, val foodId: String, val portion: PortionKey, val count: Double = 1.0)

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val container = (context.applicationContext as GlobalFitnessApp).container
        val food = container.food
        val repo = food.repository
        val replace = intent.getBooleanExtra("replace", true)
        val zone = ZoneId.systemDefault()
        val date = intent.getStringExtra("date")?.let(LocalDate::parse) ?: LocalDate.now(zone)
        container.appScope.launch {
            try {
                repo.awaitLoaded()
                val catalog = food.catalog.foods()
                val removed = if (replace) repo.entries.value.filter { it.localDate == date } else emptyList()
                for (e in removed) repo.deleteEntry(e.id)
                var kcal = 0.0
                for (item in DAY) {
                    val f = catalog[item.foodId] ?: error("No catalog food ${item.foodId}")
                    val portion = f.portions.firstOrNull { it.key == item.portion } ?: error("${f.id} has no portion ${item.portion.code}")
                    val amount = FoodMath.amountOf(portion, item.count)
                    val nutrients = FoodMath.nutrientsFor(f.per100, amount)
                    val at = date.atTime(item.time).atZone(zone)
                    repo.addEntry(
                        FoodEntry(
                            id = "",
                            eatenAt = at.toInstant().toEpochMilli(),
                            zoneOffsetSeconds = at.offset.totalSeconds,
                            meal = item.meal,
                            foodId = f.id,
                            customFoodId = null,
                            name = food.catalog.name(f.id),
                            amount = amount,
                            unit = f.unit,
                            portionKey = item.portion,
                            portionCount = item.count,
                            nutrients = nutrients,
                            source = EntrySource.SEARCH,
                            photo = null,
                            modelLabel = null,
                            modelConfidence = null,
                            createdAt = 0,
                            updatedAt = 0,
                        ),
                    )
                    kcal += nutrients.kcal ?: 0.0
                }
                food.settings.setGoal(GoalSettings(mode = GoalMode.AUTO, aim = GoalAim.MAINTAIN))
                food.settings.markUsed()
                Log.i(TAG, "Seeded ${DAY.size} food entries on $date (${Math.round(kcal)} kcal), removed ${removed.size}")
            } catch (e: Exception) {
                Log.e(TAG, "Food seeding failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "DebugFoodSeed"

        /**
         * One minute apart within a meal, so the foods keep this order. The coffee comes second: in
         * 02-food the "Add" button covers the end of the breakfast's second row, and its short
         * "80 kcal" hides under it whole in every language (Arabic's "… سعرة حرارية" too).
         */
        val DAY = listOf(
            Item(Meal.BREAKFAST, LocalTime.of(8, 20), "bread_with_tomato", PortionKey.UNIT),
            Item(Meal.BREAKFAST, LocalTime.of(8, 21), "coffee_with_milk", PortionKey.CUP),
            Item(Meal.LUNCH, LocalTime.of(14, 30), "paella", PortionKey.PLATE),
            Item(Meal.LUNCH, LocalTime.of(14, 31), "green_salad", PortionKey.SMALL_PLATE),
            Item(Meal.LUNCH, LocalTime.of(14, 32), "spanish_omelette", PortionKey.SLICE),
            Item(Meal.SNACK, LocalTime.of(17, 15), "yogurt", PortionKey.UNIT),
            Item(Meal.SNACK, LocalTime.of(17, 16), "banana", PortionKey.UNIT),
        )
    }
}
