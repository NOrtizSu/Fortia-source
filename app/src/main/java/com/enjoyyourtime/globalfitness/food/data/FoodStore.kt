package com.enjoyyourtime.globalfitness.food.data

import com.enjoyyourtime.globalfitness.food.model.CustomFood
import com.enjoyyourtime.globalfitness.food.model.FoodEntry
import com.enjoyyourtime.globalfitness.training.model.Tombstone

/** Blocking persistence of the food log ([FoodDatabase] on the phone, a fake in tests). */
interface FoodStore {
    fun loadEntries(): List<FoodEntry>

    fun loadCustomFoods(): List<CustomFood>

    fun loadTombstones(): List<Tombstone>

    fun upsertEntry(entry: FoodEntry)

    /** Removes the entry and records a tombstone (one transaction). */
    fun deleteEntry(id: String, deletedAt: Long)

    fun upsertCustomFood(food: CustomFood)

    /** Removes the custom food and records a tombstone (entries keep their own snapshot). */
    fun deleteCustomFood(id: String, deletedAt: Long)

    /** Forgets tombstones once the backup no longer needs them (only those not newer than given). */
    fun dropTombstones(tombstones: Collection<Tombstone>)

    /** Changes from the cloud backup in one transaction: upserts, then removals (no local tombstones). */
    fun applyBatch(batch: FoodBatch)

    /** What this phone wrote to Health Connect, by entry id (never part of any backup decision). */
    fun loadExportLedger(): Map<String, ExportLedgerEntry>

    fun putExportLedger(entries: Collection<ExportLedgerEntry>)

    fun removeExportLedger(entryIds: Collection<String>)
}

/** The food log as one consistent read (taken under the repository's write lock), for the cloud backup. */
data class FoodSnapshot(
    val entries: List<FoodEntry>,
    val customFoods: List<CustomFood>,
    val tombstones: List<Tombstone>,
)

/** A set of changes applied atomically (cloud restore / merge). */
data class FoodBatch(
    val upsertEntries: List<FoodEntry> = emptyList(),
    val removeEntries: List<String> = emptyList(),
    val upsertCustomFoods: List<CustomFood> = emptyList(),
    val removeCustomFoods: List<String> = emptyList(),
) {
    val isEmpty: Boolean
        get() = upsertEntries.isEmpty() && removeEntries.isEmpty() && upsertCustomFoods.isEmpty() && removeCustomFoods.isEmpty()
}

/** The `updatedAt` of every item when the cloud sync read them (absent: did not exist then). */
data class FoodVersions(
    val entries: Map<String, Long> = emptyMap(),
    val customFoods: Map<String, Long> = emptyMap(),
)

/**
 * One meal entry written to Health Connect: its record's client version (the entry's updatedAt),
 * Health Connect's own record id when known, and the install that wrote it ([device]: food.db is
 * part of Android's backup, Health Connect's data is not, so rows of another install are ignored).
 */
data class ExportLedgerEntry(val entryId: String, val version: Long, val recordId: String?, val exportedAt: Long, val device: String)
