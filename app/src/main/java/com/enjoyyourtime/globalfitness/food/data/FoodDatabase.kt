package com.enjoyyourtime.globalfitness.food.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.core.database.sqlite.transaction
import com.enjoyyourtime.globalfitness.food.model.CustomFood
import com.enjoyyourtime.globalfitness.food.model.CustomFoodSource
import com.enjoyyourtime.globalfitness.food.model.EntrySource
import com.enjoyyourtime.globalfitness.food.model.FoodEntry
import com.enjoyyourtime.globalfitness.food.model.FoodTombstones
import com.enjoyyourtime.globalfitness.food.model.FoodUnit
import com.enjoyyourtime.globalfitness.food.model.Meal
import com.enjoyyourtime.globalfitness.food.model.Nutrients
import com.enjoyyourtime.globalfitness.food.model.PortionKey
import com.enjoyyourtime.globalfitness.training.model.Tombstone

/**
 * The tables of food.db (plain SQL, so the unit tests run it on a real SQLite). Every statement is
 * safe to run again (`IF NOT EXISTS`): the upgrade steps of later versions may re-run them, and a
 * file of a newer version opens as it is (see [FoodDatabase.onDowngrade]).
 */
object FoodSchema {
    const val VERSION = 1

    /** The eight nutrient columns, in [Nutrients] order. */
    val NUTRIENT_COLUMNS = listOf("kcal", "protein", "carbs", "sugars", "fat", "sat_fat", "fiber", "salt")

    val ENTRY_COLUMNS = listOf(
        "id", "eaten_at", "zone_offset", "meal", "food_id", "custom_food_id", "name", "amount", "unit",
        "portion_key", "portion_count",
    ) + NUTRIENT_COLUMNS + listOf("source", "photo", "model_label", "model_confidence", "created_at", "updated_at")

    val CUSTOM_FOOD_COLUMNS = listOf("id", "name", "brand", "barcode", "unit") + NUTRIENT_COLUMNS +
        listOf("portion", "source", "created_at", "updated_at")

    private val nutrientDefs = NUTRIENT_COLUMNS.joinToString(",\n") { "    $it REAL" }

    val CREATE: List<String> = listOf(
        """
        CREATE TABLE IF NOT EXISTS food_entries (
            id TEXT PRIMARY KEY,
            eaten_at INTEGER NOT NULL,
            zone_offset INTEGER NOT NULL,
            meal TEXT NOT NULL,
            food_id TEXT,
            custom_food_id TEXT,
            name TEXT NOT NULL,
            amount REAL NOT NULL,
            unit TEXT NOT NULL,
            portion_key TEXT,
            portion_count REAL,
        $nutrientDefs,
            source TEXT NOT NULL,
            photo TEXT,
            model_label TEXT,
            model_confidence REAL,
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS idx_food_entries_eaten ON food_entries(eaten_at)",
        """
        CREATE TABLE IF NOT EXISTS custom_foods (
            id TEXT PRIMARY KEY,
            name TEXT NOT NULL,
            brand TEXT,
            barcode TEXT,
            unit TEXT NOT NULL,
        $nutrientDefs,
            portion REAL,
            source TEXT NOT NULL,
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS idx_custom_foods_barcode ON custom_foods(barcode)",
        """
        CREATE TABLE IF NOT EXISTS tombstones (
            kind TEXT NOT NULL,
            id TEXT NOT NULL,
            deleted_at INTEGER NOT NULL,
            PRIMARY KEY (kind, id)
        )
        """.trimIndent(),
        // Health Connect: what this phone wrote (never backed up to the cloud).
        """
        CREATE TABLE IF NOT EXISTS hc_export (
            entry_id TEXT PRIMARY KEY,
            version INTEGER NOT NULL,
            record_id TEXT,
            exported_at INTEGER NOT NULL,
            device TEXT NOT NULL
        )
        """.trimIndent(),
    )

    /** Statements that bring a file of [oldVersion] to [VERSION] (none yet: version 1 is the first). */
    fun upgrade(oldVersion: Int): List<String> = if (oldVersion < VERSION) CREATE else emptyList()
}

/**
 * The food log in its own SQLite file (food.db), like training.db: entries, custom foods and
 * cached barcode products, tombstones for the cloud backup and the Health Connect ledger. All
 * methods are blocking.
 */
class FoodDatabase(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, FoodSchema.VERSION), FoodStore {

    override fun onCreate(db: SQLiteDatabase) {
        FoodSchema.CREATE.forEach(db::execSQL)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        FoodSchema.upgrade(oldVersion).forEach(db::execSQL)
    }

    /** A file of a newer app version (the app was downgraded) opens as it is; see [FoodSchema]. */
    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    // ---- reads ------------------------------------------------------------------------------

    override fun loadEntries(): List<FoodEntry> =
        readableDatabase.rawQuery("SELECT ${FoodSchema.ENTRY_COLUMNS.joinToString()} FROM food_entries ORDER BY eaten_at, id", null).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        FoodEntry(
                            id = c.getString(0),
                            eatenAt = c.getLong(1),
                            zoneOffsetSeconds = c.getInt(2),
                            meal = Meal.fromCode(c.getString(3)),
                            foodId = c.stringOrNull(4),
                            customFoodId = c.stringOrNull(5),
                            name = c.getString(6),
                            amount = c.getDouble(7),
                            unit = FoodUnit.fromCode(c.getString(8)),
                            portionKey = PortionKey.fromCode(c.stringOrNull(9)),
                            portionCount = c.doubleOrNull(10),
                            nutrients = c.nutrients(11),
                            source = EntrySource.fromCode(c.getString(19)),
                            photo = c.stringOrNull(20),
                            modelLabel = c.stringOrNull(21),
                            modelConfidence = c.doubleOrNull(22),
                            createdAt = c.getLong(23),
                            updatedAt = c.getLong(24),
                        ),
                    )
                }
            }
        }

    override fun loadCustomFoods(): List<CustomFood> =
        readableDatabase.rawQuery("SELECT ${FoodSchema.CUSTOM_FOOD_COLUMNS.joinToString()} FROM custom_foods ORDER BY created_at, id", null).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        CustomFood(
                            id = c.getString(0),
                            name = c.getString(1),
                            brand = c.stringOrNull(2),
                            barcode = c.stringOrNull(3),
                            unit = FoodUnit.fromCode(c.getString(4)),
                            per100 = c.nutrients(5),
                            portion = c.doubleOrNull(13),
                            source = CustomFoodSource.fromCode(c.getString(14)),
                            createdAt = c.getLong(15),
                            updatedAt = c.getLong(16),
                        ),
                    )
                }
            }
        }

    override fun loadTombstones(): List<Tombstone> =
        readableDatabase.rawQuery("SELECT kind, id, deleted_at FROM tombstones", null).use { c ->
            buildList { while (c.moveToNext()) add(Tombstone(c.getString(0), c.getString(1), c.getLong(2))) }
        }

    override fun loadExportLedger(): Map<String, ExportLedgerEntry> =
        readableDatabase.rawQuery("SELECT entry_id, version, record_id, exported_at, device FROM hc_export", null).use { c ->
            buildMap {
                while (c.moveToNext()) put(c.getString(0), ExportLedgerEntry(c.getString(0), c.getLong(1), c.stringOrNull(2), c.getLong(3), c.getString(4)))
            }
        }

    // ---- writes -----------------------------------------------------------------------------

    override fun upsertEntry(entry: FoodEntry) {
        writableDatabase.transaction { writeEntry(entry) }
    }

    override fun deleteEntry(id: String, deletedAt: Long) {
        writableDatabase.transaction {
            delete("food_entries", "id = ?", arrayOf(id))
            tombstone(FoodTombstones.ENTRY, id, deletedAt)
        }
    }

    override fun upsertCustomFood(food: CustomFood) {
        writableDatabase.transaction { writeCustomFood(food) }
    }

    override fun deleteCustomFood(id: String, deletedAt: Long) {
        writableDatabase.transaction {
            delete("custom_foods", "id = ?", arrayOf(id))
            tombstone(FoodTombstones.CUSTOM_FOOD, id, deletedAt)
        }
    }

    override fun dropTombstones(tombstones: Collection<Tombstone>) {
        if (tombstones.isEmpty()) return
        writableDatabase.transaction {
            for (t in tombstones) delete("tombstones", "kind = ? AND id = ? AND deleted_at <= ?", arrayOf(t.kind, t.id, t.deletedAt.toString()))
        }
    }

    override fun applyBatch(batch: FoodBatch) {
        if (batch.isEmpty) return
        writableDatabase.transaction {
            batch.upsertCustomFoods.forEach { writeCustomFood(it) }
            batch.upsertEntries.forEach { writeEntry(it) }
            batch.removeEntries.forEach { delete("food_entries", "id = ?", arrayOf(it)) }
            batch.removeCustomFoods.forEach { delete("custom_foods", "id = ?", arrayOf(it)) }
        }
    }

    override fun putExportLedger(entries: Collection<ExportLedgerEntry>) {
        if (entries.isEmpty()) return
        writableDatabase.transaction {
            for (e in entries) {
                insertWithOnConflict(
                    "hc_export",
                    null,
                    ContentValues().apply {
                        put("entry_id", e.entryId)
                        put("version", e.version)
                        if (e.recordId == null) putNull("record_id") else put("record_id", e.recordId)
                        put("exported_at", e.exportedAt)
                        put("device", e.device)
                    },
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
            }
        }
    }

    override fun removeExportLedger(entryIds: Collection<String>) {
        if (entryIds.isEmpty()) return
        writableDatabase.transaction { for (id in entryIds) delete("hc_export", "entry_id = ?", arrayOf(id)) }
    }

    private fun SQLiteDatabase.writeEntry(e: FoodEntry) {
        insertWithOnConflict(
            "food_entries",
            null,
            ContentValues().apply {
                put("id", e.id)
                put("eaten_at", e.eatenAt)
                put("zone_offset", e.zoneOffsetSeconds)
                put("meal", e.meal.code)
                putText("food_id", e.foodId)
                putText("custom_food_id", e.customFoodId)
                put("name", e.name)
                put("amount", e.amount)
                put("unit", e.unit.code)
                putText("portion_key", e.portionKey?.code)
                putDouble("portion_count", e.portionCount)
                putNutrients(e.nutrients)
                put("source", e.source.code)
                putText("photo", e.photo)
                putText("model_label", e.modelLabel)
                putDouble("model_confidence", e.modelConfidence)
                put("created_at", e.createdAt)
                put("updated_at", e.updatedAt)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        // An entry restored (undo) or recreated after a deletion is live again.
        delete("tombstones", "kind = ? AND id = ?", arrayOf(FoodTombstones.ENTRY, e.id))
    }

    private fun SQLiteDatabase.writeCustomFood(f: CustomFood) {
        insertWithOnConflict(
            "custom_foods",
            null,
            ContentValues().apply {
                put("id", f.id)
                put("name", f.name)
                putText("brand", f.brand)
                putText("barcode", f.barcode)
                put("unit", f.unit.code)
                putNutrients(f.per100)
                putDouble("portion", f.portion)
                put("source", f.source.code)
                put("created_at", f.createdAt)
                put("updated_at", f.updatedAt)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        delete("tombstones", "kind = ? AND id = ?", arrayOf(FoodTombstones.CUSTOM_FOOD, f.id))
    }

    private fun SQLiteDatabase.tombstone(kind: String, id: String, at: Long) {
        insertWithOnConflict(
            "tombstones",
            null,
            ContentValues().apply {
                put("kind", kind)
                put("id", id)
                put("deleted_at", at)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    private fun ContentValues.putNutrients(n: Nutrients) {
        putDouble("kcal", n.kcal)
        putDouble("protein", n.protein)
        putDouble("carbs", n.carbs)
        putDouble("sugars", n.sugars)
        putDouble("fat", n.fat)
        putDouble("sat_fat", n.satFat)
        putDouble("fiber", n.fiber)
        putDouble("salt", n.salt)
    }

    /** The eight nutrient columns starting at [first], in [FoodSchema.NUTRIENT_COLUMNS] order. */
    private fun Cursor.nutrients(first: Int) = Nutrients(
        doubleOrNull(first), doubleOrNull(first + 1), doubleOrNull(first + 2), doubleOrNull(first + 3),
        doubleOrNull(first + 4), doubleOrNull(first + 5), doubleOrNull(first + 6), doubleOrNull(first + 7),
    )

    private fun ContentValues.putDouble(key: String, v: Double?) = if (v == null) putNull(key) else put(key, v)

    private fun ContentValues.putText(key: String, v: String?) = if (v == null) putNull(key) else put(key, v)

    private fun Cursor.doubleOrNull(i: Int): Double? = if (isNull(i)) null else getDouble(i)

    private fun Cursor.stringOrNull(i: Int): String? = if (isNull(i)) null else getString(i)

    companion object {
        const val DB_NAME = "food.db"
    }
}
