package com.enjoyyourtime.globalfitness.health

import androidx.health.connect.client.records.MealType
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Mass
import com.enjoyyourtime.globalfitness.food.data.ExportLedgerEntry
import com.enjoyyourtime.globalfitness.food.model.FoodEntry
import com.enjoyyourtime.globalfitness.food.model.Meal
import com.enjoyyourtime.globalfitness.food.model.Nutrient
import com.enjoyyourtime.globalfitness.food.model.NutrientUnit
import java.time.Instant
import java.time.ZoneOffset

/** A meal entry as a Health Connect nutrition record (pure values; [FoodExportPlanner.buildRecord] makes the record). */
data class PlannedNutrition(
    val entryId: String,
    val clientRecordId: String,
    /** The entry's updatedAt: an edit writes a higher version, which Health Connect stores over the old one. */
    val version: Long,
    val start: Long,
    val end: Long,
    val zoneOffsetSeconds: Int,
    /** `MealType.MEAL_TYPE_*`. */
    val mealType: Int,
    val name: String,
    val energyKcal: Double?,
    val proteinG: Double?,
    val carbsG: Double?,
    val sugarG: Double?,
    val fatG: Double?,
    val saturatedFatG: Double?,
    val fiberG: Double?,
    /** Salt / 2.5. */
    val sodiumG: Double?,
    /**
     * The values beyond the label that are known, in grams: mono- and polyunsaturated fat,
     * cholesterol, minerals, vitamins ([FoodExportPlanner.buildRecord] puts each in its field of the
     * same name; vitamin A as µg RAE and folate as µg DFE, the units Health Connect means).
     */
    val extraG: Map<Nutrient, Double> = emptyMap(),
)

data class FoodExportPlan(
    /** Records to write (new entries and edited ones). */
    val writes: List<PlannedNutrition>,
    /** Entries whose record goes (deleted in the app or through the cloud backup), by entry id. */
    val deletes: List<String>,
    /** Records of this install in Health Connect after the run. */
    val savedCount: Int,
) {
    val isEmpty: Boolean get() = writes.isEmpty() && deletes.isEmpty()
}

/**
 * What to write to and delete from Health Connect for the food log (pure, unit-tested), from the
 * ledger of what this install wrote ([ExportLedgerEntry], kept in food.db next to the entries):
 * - an entry is written when the ledger does not have its current version (new or edited) and the
 *   switch is on; a record the user deleted in Health Connect is not written again until the entry changes;
 * - a ledger row whose entry is gone is deleted from Health Connect (the deletion travels with the
 *   database: a food.db recreated empty has no ledger either, so it never deletes everything);
 * - turning the switch off stops writing and deletes nothing (deleting an entry still removes its record).
 */
object FoodExportPlanner {
    const val CLIENT_ID_PREFIX = "fortia-meal-"

    /** Short span of a meal record (Health Connect needs the end after the start). */
    const val RECORD_SPAN_MS = 60_000L

    private const val SODIUM_PER_SALT = 1 / 2.5

    fun clientRecordId(entryId: String): String = CLIENT_ID_PREFIX + entryId

    fun plan(entries: List<FoodEntry>, ledger: Map<String, ExportLedgerEntry>, writeEnabled: Boolean): FoodExportPlan {
        val live = entries.associateBy { it.id }
        val writes = if (writeEnabled) entries.filter { ledger[it.id]?.version != it.updatedAt }.map(::planned) else emptyList()
        val deletes = ledger.keys.filter { it !in live }
        val saved = ledger.keys.count { it in live } + writes.count { it.entryId !in ledger }
        return FoodExportPlan(writes, deletes, saved)
    }

    fun planned(e: FoodEntry): PlannedNutrition {
        val n = e.nutrients
        return PlannedNutrition(
            entryId = e.id,
            clientRecordId = clientRecordId(e.id),
            version = e.updatedAt,
            start = e.eatenAt,
            end = e.eatenAt + RECORD_SPAN_MS,
            zoneOffsetSeconds = e.zoneOffsetSeconds,
            mealType = mealType(e.meal),
            name = e.name,
            energyKcal = n.kcal?.let(::valid),
            proteinG = n.protein?.let(::valid),
            carbsG = n.carbs?.let(::valid),
            sugarG = n.sugars?.let(::valid),
            fatG = n.fat?.let(::valid),
            saturatedFatG = n.satFat?.let(::valid),
            fiberG = n.fiber?.let(::valid),
            sodiumG = n.salt?.let { valid(it * SODIUM_PER_SALT, MAX_SODIUM_G) },
            extraG = Nutrient.EXTRA.mapNotNull { nutrient -> n[nutrient]?.let { grams(it, nutrient.unit) }?.let { g -> valid(g, maxOf(nutrient))?.let { nutrient to it } } }.toMap(),
        )
    }

    /** A value in [unit] as grams (kcal are not a mass: null). */
    fun grams(v: Double, unit: NutrientUnit): Double? = when (unit) {
        NutrientUnit.G -> v
        NutrientUnit.MG -> v / 1_000.0
        NutrientUnit.UG -> v / 1_000_000.0
        NutrientUnit.KCAL -> null
    }

    /** connect-client's limit of a field: 100 kg for fats, 100 g for cholesterol, minerals and vitamins. */
    private fun maxOf(n: Nutrient): Double = if (n.unit == NutrientUnit.G) MAX_VALUE else MAX_MICRO_G

    fun mealType(meal: Meal): Int = when (meal) {
        Meal.BREAKFAST -> MealType.MEAL_TYPE_BREAKFAST
        Meal.LUNCH -> MealType.MEAL_TYPE_LUNCH
        Meal.SNACK -> MealType.MEAL_TYPE_SNACK
        Meal.DINNER -> MealType.MEAL_TYPE_DINNER
        Meal.OTHER -> MealType.MEAL_TYPE_UNKNOWN
    }

    /** Health Connect refuses negative and absurd values: only that field is left out. */
    private fun valid(v: Double, max: Double = MAX_VALUE): Double? = v.takeIf { it.isFinite() && it >= 0 && it <= max }

    /** connect-client's limits: energy 100,000 kcal and macronutrients 100 kg; minerals such as sodium 100 g. */
    private const val MAX_VALUE = 100_000.0
    private const val MAX_SODIUM_G = 100.0
    private const val MAX_MICRO_G = 100.0

    /** The record of [p], entered by hand in the app (manual entry, like the workouts and manual weights). */
    fun buildRecord(p: PlannedNutrition): NutritionRecord {
        val offset = ZoneOffset.ofTotalSeconds(p.zoneOffsetSeconds)
        return NutritionRecord(
            startTime = Instant.ofEpochMilli(p.start),
            startZoneOffset = offset,
            endTime = Instant.ofEpochMilli(p.end),
            endZoneOffset = offset,
            metadata = Metadata.manualEntry(p.clientRecordId, p.version),
            energy = p.energyKcal?.let(Energy::kilocalories),
            protein = p.proteinG?.let(Mass::grams),
            totalCarbohydrate = p.carbsG?.let(Mass::grams),
            sugar = p.sugarG?.let(Mass::grams),
            totalFat = p.fatG?.let(Mass::grams),
            saturatedFat = p.saturatedFatG?.let(Mass::grams),
            dietaryFiber = p.fiberG?.let(Mass::grams),
            sodium = p.sodiumG?.let(Mass::grams),
            monounsaturatedFat = p.extra(Nutrient.MONO_FAT),
            polyunsaturatedFat = p.extra(Nutrient.POLY_FAT),
            cholesterol = p.extra(Nutrient.CHOLESTEROL),
            potassium = p.extra(Nutrient.POTASSIUM),
            calcium = p.extra(Nutrient.CALCIUM),
            iron = p.extra(Nutrient.IRON),
            magnesium = p.extra(Nutrient.MAGNESIUM),
            zinc = p.extra(Nutrient.ZINC),
            vitaminA = p.extra(Nutrient.VIT_A),
            vitaminC = p.extra(Nutrient.VIT_C),
            vitaminD = p.extra(Nutrient.VIT_D),
            vitaminE = p.extra(Nutrient.VIT_E),
            vitaminB12 = p.extra(Nutrient.VIT_B12),
            folate = p.extra(Nutrient.FOLATE),
            name = p.name.take(MAX_NAME),
            mealType = p.mealType,
        )
    }

    private fun PlannedNutrition.extra(n: Nutrient): Mass? = extraG[n]?.let(Mass::grams)

    private const val MAX_NAME = 200
}
