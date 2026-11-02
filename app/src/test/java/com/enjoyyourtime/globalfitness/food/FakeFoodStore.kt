package com.enjoyyourtime.globalfitness.food

import com.enjoyyourtime.globalfitness.food.data.ExportLedgerEntry
import com.enjoyyourtime.globalfitness.food.data.FoodBatch
import com.enjoyyourtime.globalfitness.food.data.FoodStore
import com.enjoyyourtime.globalfitness.food.model.CustomFood
import com.enjoyyourtime.globalfitness.food.model.EntrySource
import com.enjoyyourtime.globalfitness.food.model.FoodEntry
import com.enjoyyourtime.globalfitness.food.model.FoodTombstones
import com.enjoyyourtime.globalfitness.food.model.FoodUnit
import com.enjoyyourtime.globalfitness.food.model.Meal
import com.enjoyyourtime.globalfitness.food.model.Nutrients
import com.enjoyyourtime.globalfitness.training.model.Tombstone

/** In-memory [FoodStore] with the same semantics as food.db; it can be told to fail like a broken one. */
class FakeFoodStore : FoodStore {
    val entries = LinkedHashMap<String, FoodEntry>()
    val foods = LinkedHashMap<String, CustomFood>()
    val tombstones = LinkedHashMap<Pair<String, String>, Tombstone>()
    val ledger = LinkedHashMap<String, ExportLedgerEntry>()

    /** The database cannot be opened. */
    var failLoad = false

    /** A cloud batch touching one of these ids is refused (as SQLite refuses a row it cannot store). */
    var refuse: Set<String> = emptySet()

    override fun loadEntries(): List<FoodEntry> {
        if (failLoad) throw IllegalStateException("disk I/O error")
        return entries.values.sortedWith(compareBy({ it.eatenAt }, { it.id }))
    }

    override fun loadCustomFoods() = foods.values.toList()

    override fun loadTombstones() = tombstones.values.toList()

    override fun upsertEntry(entry: FoodEntry) {
        entries[entry.id] = entry
        tombstones.remove(FoodTombstones.ENTRY to entry.id)
    }

    override fun deleteEntry(id: String, deletedAt: Long) {
        entries.remove(id)
        tombstones[FoodTombstones.ENTRY to id] = Tombstone(FoodTombstones.ENTRY, id, deletedAt)
    }

    override fun upsertCustomFood(food: CustomFood) {
        foods[food.id] = food
        tombstones.remove(FoodTombstones.CUSTOM_FOOD to food.id)
    }

    override fun deleteCustomFood(id: String, deletedAt: Long) {
        foods.remove(id)
        tombstones[FoodTombstones.CUSTOM_FOOD to id] = Tombstone(FoodTombstones.CUSTOM_FOOD, id, deletedAt)
    }

    override fun dropTombstones(tombstones: Collection<Tombstone>) {
        for (t in tombstones) {
            val key = t.kind to t.id
            if ((this.tombstones[key]?.deletedAt ?: Long.MAX_VALUE) <= t.deletedAt) this.tombstones.remove(key)
        }
    }

    override fun applyBatch(batch: FoodBatch) {
        val ids = batch.upsertEntries.map { it.id } + batch.removeEntries + batch.upsertCustomFoods.map { it.id } + batch.removeCustomFoods
        if (ids.any { it in refuse }) throw IllegalStateException("constraint failed")
        batch.upsertCustomFoods.forEach(::upsertCustomFood)
        batch.upsertEntries.forEach(::upsertEntry)
        batch.removeEntries.forEach { entries.remove(it) }
        batch.removeCustomFoods.forEach { foods.remove(it) }
    }

    override fun loadExportLedger(): Map<String, ExportLedgerEntry> = LinkedHashMap(ledger)

    override fun putExportLedger(entries: Collection<ExportLedgerEntry>) {
        entries.forEach { ledger[it.entryId] = it }
    }

    override fun removeExportLedger(entryIds: Collection<String>) {
        entryIds.forEach { ledger.remove(it) }
    }
}

/** A plain entry for tests (values for 150 g of a food with 200 kcal / 100 g). */
fun testEntry(
    id: String = "",
    eatenAt: Long = 1_700_000_000_000L,
    meal: Meal = Meal.LUNCH,
    name: String = "Paella",
    amount: Double = 150.0,
    nutrients: Nutrients = Nutrients(kcal = 300.0, protein = 12.0, carbs = 30.0, sugars = 1.5, fat = 9.0, satFat = 2.0, fiber = 1.5, salt = 1.0),
    photo: String? = null,
    zoneOffsetSeconds: Int = 3600,
    updatedAt: Long = 0,
) = FoodEntry(
    id = id,
    eatenAt = eatenAt,
    zoneOffsetSeconds = zoneOffsetSeconds,
    meal = meal,
    foodId = "paella",
    customFoodId = null,
    name = name,
    amount = amount,
    unit = FoodUnit.G,
    portionKey = null,
    portionCount = null,
    nutrients = nutrients,
    source = EntrySource.SEARCH,
    photo = photo,
    modelLabel = null,
    modelConfidence = null,
    createdAt = updatedAt,
    updatedAt = updatedAt,
)
