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
import com.enjoyyourtime.globalfitness.food.model.Nutrient
import com.enjoyyourtime.globalfitness.food.model.Nutrients
import com.enjoyyourtime.globalfitness.food.model.PortionKey
import com.enjoyyourtime.globalfitness.training.model.Tombstone

/**
 * The tables of food.db (plain SQL, so the unit tests run it on a real SQLite). Every statement is
 * safe to run again (`IF NOT EXISTS`, a column added only when missing): the upgrade steps of later
 * versions may re-run them, and a file of a newer version opens as it is (see
 * [FoodDatabase.onDowngrade]; Android then marks it with the older version, so the newer app
 * upgrades it again).
 *
 * Version 2 (1.6.0): the fourteen nutrients beyond the label in both tables, and the entry's changed
 * ingredients ([AdjustmentsColumn]) and photo grams estimate. They come after the version 1 columns,
 * as `ALTER TABLE` adds them, so a new file and an upgraded one have the same layout.
 */
object FoodSchema {
    const val VERSION = 2

    /** The eight nutrient columns of version 1, in [Nutrient] order. */
    val NUTRIENT_COLUMNS = Nutrient.LABEL.map { it.code }

    /** The nutrient columns added in version 2, in [Nutrient] order. */
    val EXTRA_NUTRIENT_COLUMNS = Nutrient.EXTRA.map { it.code }

    private val ENTRY_V1 = listOf(
        "id", "eaten_at", "zone_offset", "meal", "food_id", "custom_food_id", "name", "amount", "unit",
        "portion_key", "portion_count",
    ) + NUTRIENT_COLUMNS + listOf("source", "photo", "model_label", "model_confidence", "created_at", "updated_at")

    /** Columns version 2 adds to food_entries, with their types. */
    private val ENTRY_V2: List<Pair<String, String>> =
        EXTRA_NUTRIENT_COLUMNS.map { it to "REAL" } + listOf("adjustments" to "TEXT", "estimated_amount" to "REAL")

    private val CUSTOM_FOOD_V1 = listOf("id", "name", "brand", "barcode", "unit") + NUTRIENT_COLUMNS +
        listOf("portion", "source", "created_at", "updated_at")

    /** Columns version 2 adds to custom_foods. */
    private val CUSTOM_FOOD_V2: List<Pair<String, String>> = EXTRA_NUTRIENT_COLUMNS.map { it to "REAL" }

    val ENTRY_COLUMNS = ENTRY_V1 + ENTRY_V2.map { it.first }

    val CUSTOM_FOOD_COLUMNS = CUSTOM_FOOD_V1 + CUSTOM_FOOD_V2.map { it.first }

    private val nutrientDefs = NUTRIENT_COLUMNS.joinToString(",\n") { "    $it REAL" }

    private fun defs(columns: List<Pair<String, String>>) = columns.joinToString(",\n") { (name, type) -> "    $name $type" }

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
            updated_at INTEGER NOT NULL,
        ${defs(ENTRY_V2)}
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
            updated_at INTEGER NOT NULL,
        ${defs(CUSTOM_FOOD_V2)}
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

    /** What [upgrade] needs of a database: run a statement, list a table's columns. */
    interface Sql {
        fun exec(sql: String)

        fun columns(table: String): Set<String>
    }

    /**
     * Brings a file of [oldVersion] to [VERSION]. Safe to run again on a file that already has some
     * or all of it (a newer file opened by an older app, then by this one again).
     */
    fun upgrade(sql: Sql, oldVersion: Int) {
        if (oldVersion >= VERSION) return
        CREATE.forEach(sql::exec)
        addMissing(sql, "food_entries", ENTRY_V2)
        addMissing(sql, "custom_foods", CUSTOM_FOOD_V2)
    }

    private fun addMissing(sql: Sql, table: String, columns: List<Pair<String, String>>) {
        val existing = sql.columns(table)
        for ((name, type) in columns) if (name !in existing) sql.exec("ALTER TABLE $table ADD COLUMN $name $type")
    }
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
        FoodSchema.upgrade(
            object : FoodSchema.Sql {
                override fun exec(sql: String) = db.execSQL(sql)

                override fun columns(table: String): Set<String> =
                    db.rawQuery("PRAGMA table_info($table)", null).use { c ->
                        val name = c.getColumnIndexOrThrow("name")
                        buildSet { while (c.moveToNext()) add(c.getString(name)) }
                    }
            },
            oldVersion,
        )
    }

    /**
     * A file of a newer app version (the app was downgraded) opens as it is: the newer columns are left
     * alone (rows this version rewrites lose their values there); see [FoodSchema].
     */
    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    // ---- reads ------------------------------------------------------------------------------

    override fun loadEntries(): List<FoodEntry> =
        readableDatabase.rawQuery("SELECT ${FoodSchema.ENTRY_COLUMNS.joinToString()} FROM food_entries ORDER BY eaten_at, id", null).use { c ->
            val i = Columns(FoodSchema.ENTRY_COLUMNS)
            buildList {
                while (c.moveToNext()) {
                    add(
                        FoodEntry(
                            id = c.getString(i["id"]),
                            eatenAt = c.getLong(i["eaten_at"]),
                            zoneOffsetSeconds = c.getInt(i["zone_offset"]),
                            meal = Meal.fromCode(c.getString(i["meal"])),
                            foodId = c.stringOrNull(i["food_id"]),
                            customFoodId = c.stringOrNull(i["custom_food_id"]),
                            name = c.getString(i["name"]),
                            amount = c.getDouble(i["amount"]),
                            unit = FoodUnit.fromCode(c.getString(i["unit"])),
                            portionKey = PortionKey.fromCode(c.stringOrNull(i["portion_key"])),
                            portionCount = c.doubleOrNull(i["portion_count"]),
                            nutrients = c.nutrients(i),
                            source = EntrySource.fromCode(c.getString(i["source"])),
                            photo = c.stringOrNull(i["photo"]),
                            modelLabel = c.stringOrNull(i["model_label"]),
                            modelConfidence = c.doubleOrNull(i["model_confidence"]),
                            createdAt = c.getLong(i["created_at"]),
                            updatedAt = c.getLong(i["updated_at"]),
                            adjustments = AdjustmentsColumn.decode(c.stringOrNull(i["adjustments"])),
                            estimatedAmount = c.doubleOrNull(i["estimated_amount"]),
                        ),
                    )
                }
            }
        }

    override fun loadCustomFoods(): List<CustomFood> =
        readableDatabase.rawQuery("SELECT ${FoodSchema.CUSTOM_FOOD_COLUMNS.joinToString()} FROM custom_foods ORDER BY created_at, id", null).use { c ->
            val i = Columns(FoodSchema.CUSTOM_FOOD_COLUMNS)
            buildList {
                while (c.moveToNext()) {
                    add(
                        CustomFood(
                            id = c.getString(i["id"]),
                            name = c.getString(i["name"]),
                            brand = c.stringOrNull(i["brand"]),
                            barcode = c.stringOrNull(i["barcode"]),
                            unit = FoodUnit.fromCode(c.getString(i["unit"])),
                            per100 = c.nutrients(i),
                            portion = c.doubleOrNull(i["portion"]),
                            source = CustomFoodSource.fromCode(c.getString(i["source"])),
                            createdAt = c.getLong(i["created_at"]),
                            updatedAt = c.getLong(i["updated_at"]),
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
                putText("adjustments", AdjustmentsColumn.encode(e.adjustments))
                putDouble("estimated_amount", e.estimatedAmount)
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

    /** Every nutrient column (the column is the nutrient's code). */
    private fun ContentValues.putNutrients(n: Nutrients) {
        for (nutrient in Nutrient.entries) putDouble(nutrient.code, n[nutrient])
    }

    /** The nutrient columns of a row read with the column list [i]. */
    private fun Cursor.nutrients(i: Columns) = Nutrients.of { doubleOrNull(i[it.code]) }

    /** Column positions of a SELECT by name. */
    private class Columns(names: List<String>) {
        private val index = names.withIndex().associate { (i, name) -> name to i }

        operator fun get(name: String): Int = index.getValue(name)
    }

    private fun ContentValues.putDouble(key: String, v: Double?) = if (v == null) putNull(key) else put(key, v)

    private fun ContentValues.putText(key: String, v: String?) = if (v == null) putNull(key) else put(key, v)

    private fun Cursor.doubleOrNull(i: Int): Double? = if (isNull(i)) null else getDouble(i)

    private fun Cursor.stringOrNull(i: Int): String? = if (isNull(i)) null else getString(i)

    companion object {
        const val DB_NAME = "food.db"
    }
}
