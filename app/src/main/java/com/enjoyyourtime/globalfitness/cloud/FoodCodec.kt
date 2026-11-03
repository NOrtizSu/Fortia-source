package com.enjoyyourtime.globalfitness.cloud

import com.enjoyyourtime.globalfitness.cloud.FirestoreCodec.Decoded
import com.enjoyyourtime.globalfitness.cloud.FirestoreCodec.F_DELETED
import com.enjoyyourtime.globalfitness.cloud.FirestoreCodec.F_DELETED_AT
import com.enjoyyourtime.globalfitness.cloud.FirestoreCodec.F_EDITED_AT
import com.enjoyyourtime.globalfitness.cloud.FirestoreCodec.F_SCHEMA
import com.enjoyyourtime.globalfitness.food.catalog.FoodCatalogParser
import com.enjoyyourtime.globalfitness.food.model.CustomFood
import com.enjoyyourtime.globalfitness.food.model.CustomFoodSource
import com.enjoyyourtime.globalfitness.food.model.EntrySource
import com.enjoyyourtime.globalfitness.food.model.FoodEntry
import com.enjoyyourtime.globalfitness.food.model.FoodUnit
import com.enjoyyourtime.globalfitness.food.model.IngredientAdjustment
import com.enjoyyourtime.globalfitness.food.model.Meal
import com.enjoyyourtime.globalfitness.food.model.Nutrient
import com.enjoyyourtime.globalfitness.food.model.Nutrients
import com.enjoyyourtime.globalfitness.food.model.PortionKey

/**
 * Converts the food log to and from Firestore field maps (pure Kotlin; the Firestore layer adds the
 * server `updatedAt`). Layout under `users/{uid}/` (document ids encoded with [TrainingCodec.docId]):
 * - `foodEntries/{entryId}`: one meal entry with its nutrient snapshot ([Nutrient.field] each), its
 *   changed ingredients (`adjustments`: a list of `{ingredient, baseGrams, grams, factor}`) and the
 *   photo's grams estimate, never its photo (meal photos stay on the phone);
 * - `customFoods/{foodId}`: the user's foods and the products found by barcode;
 * - tombstones: `{schemaVersion, deleted: true, deletedAt}`.
 * Every field is written, nulls included (live documents are written merged, so a value removed in
 * an edit must be overwritten). Forward compatibility as in [TrainingCodec]: a later version that
 * stores what this one cannot keep raises the version of those documents; this one then reads them
 * but never overwrites them.
 *
 * Version 2 (1.6.0): an entry with values beyond the label's eight or with changed ingredients, and a
 * custom food with such values, are written at [EXTENDED_SCHEMA_VERSION]. 1.5.0 reads them (with the
 * label's values) but never writes them back: an edit there would rescale only the values it knows,
 * or drop the changed ingredients. Everything else stays at version 1, so 1.5.0 keeps writing it.
 */
object FoodCodec {
    /** Documents 1.5.0 can keep in full. */
    const val BASE_SCHEMA_VERSION = 1

    /** Documents with data of 1.6.0 (see the class comment). */
    const val EXTENDED_SCHEMA_VERSION = 2

    /** The highest version this app reads and writes in full: documents above it are newer ([isNewerSchema]). */
    const val FOOD_SCHEMA_VERSION = EXTENDED_SCHEMA_VERSION

    fun isNewerSchema(data: Map<String, Any?>): Boolean = ((data[F_SCHEMA] as? Number)?.toInt() ?: 1) > FOOD_SCHEMA_VERSION

    /** Values above these are not food (a broken document); they are read as unknown. */
    private const val MAX_FIELD = 1_000_000.0

    /**
     * A collection read decoded like the training ones: ids not in canonical form are ignored, live
     * documents of a newer schema are read-only ([RemoteDocs.readOnly]).
     */
    fun <T> collect(docs: Iterable<TrainingCodec.RawDoc>, complete: Boolean, decode: (String, Map<String, Any?>, Long?) -> Decoded<T>?): RemoteDocs<T> {
        val live = ArrayList<T>()
        val dead = ArrayList<Tombstone>()
        val readOnly = HashSet<String>()
        var maxUpdatedAt: Long? = null
        for (doc in docs) {
            doc.updatedAtMillis?.let { maxUpdatedAt = maxOf(maxUpdatedAt ?: it, it) }
            val id = TrainingCodec.rawId(doc.docId) ?: continue
            when (val decoded = decode(id, doc.data, doc.updatedAtMillis)) {
                is Decoded.Live -> {
                    live += decoded.record
                    if (isNewerSchema(doc.data)) readOnly += id
                }
                is Decoded.Dead -> dead += decoded.tombstone
                null -> Unit
            }
        }
        return RemoteDocs(live, dead, complete, maxUpdatedAt, readOnly)
    }

    // ---- Entries -------------------------------------------------------------------------------

    /** The version an entry's document is written with. */
    fun schemaOf(e: FoodEntry): Int = if (e.hasExtendedData) EXTENDED_SCHEMA_VERSION else BASE_SCHEMA_VERSION

    fun schemaOf(f: CustomFood): Int = if (f.per100.hasExtra) EXTENDED_SCHEMA_VERSION else BASE_SCHEMA_VERSION

    fun entryToMap(e: FoodEntry): Map<String, Any?> = linkedMapOf<String, Any?>(
        F_SCHEMA to schemaOf(e),
        F_DELETED to false,
        "eatenAt" to e.eatenAt,
        "zoneOffset" to e.zoneOffsetSeconds.toLong(),
        "meal" to e.meal.code,
        "foodId" to e.foodId,
        "customFoodId" to e.customFoodId,
        "name" to e.name,
        "amount" to e.amount,
        "unit" to e.unit.code,
        "portionKey" to e.portionKey?.code,
        "portionCount" to e.portionCount,
    ).apply {
        putAll(nutrientsToMap(e.nutrients))
        put("source", e.source.code)
        put("modelLabel", e.modelLabel)
        put("modelConfidence", e.modelConfidence)
        put("adjustments", e.adjustments.map(::adjustmentToMap))
        put("estimatedAmount", e.estimatedAmount)
        put("createdAt", e.createdAt)
        put(F_EDITED_AT, e.updatedAt)
    }

    /** An entry without a time, a name or a positive amount is ignored; unknown codes fall back like the database does. */
    fun entryFromMap(id: String, data: Map<String, Any?>, updatedAtMillis: Long?): Decoded<FoodEntry>? {
        tombstoneOf(id, data, updatedAtMillis)?.let { return it }
        if (id.isEmpty()) return null
        val eatenAt = long(data["eatenAt"]) ?: return null
        val name = (data["name"] as? String)?.takeIf { it.isNotBlank() } ?: return null
        val amount = double(data["amount"])?.takeIf { it > 0 } ?: return null
        val updatedAt = long(data[F_EDITED_AT]) ?: updatedAtMillis ?: eatenAt
        return Decoded.Live(
            FoodEntry(
                id = id,
                eatenAt = eatenAt,
                zoneOffsetSeconds = (long(data["zoneOffset"]) ?: 0L).coerceIn(-64_800L, 64_800L).toInt(),
                meal = Meal.fromCode(data["meal"] as? String),
                foodId = (data["foodId"] as? String)?.takeIf { it.isNotEmpty() },
                customFoodId = (data["customFoodId"] as? String)?.takeIf { it.isNotEmpty() },
                name = name,
                amount = amount,
                unit = FoodUnit.fromCode(data["unit"] as? String),
                portionKey = PortionKey.fromCode(data["portionKey"] as? String),
                portionCount = double(data["portionCount"])?.takeIf { it > 0 },
                nutrients = nutrientsFromMap(data),
                source = EntrySource.fromCode(data["source"] as? String),
                photo = null,
                modelLabel = data["modelLabel"] as? String,
                modelConfidence = double(data["modelConfidence"]),
                createdAt = long(data["createdAt"]) ?: updatedAt,
                updatedAt = updatedAt,
                adjustments = (data["adjustments"] as? List<*>).orEmpty().mapNotNull { adjustmentFromMap(it as? Map<*, *>) }.distinctBy { it.ingredient },
                estimatedAmount = double(data["estimatedAmount"])?.takeIf { it > 0 && it < MAX_FIELD },
            ),
        )
    }

    private fun adjustmentToMap(a: IngredientAdjustment): Map<String, Any?> =
        linkedMapOf("ingredient" to a.ingredient, "baseGrams" to a.baseGrams, "grams" to a.grams, "factor" to a.factor)

    private fun adjustmentFromMap(m: Map<*, *>?): IngredientAdjustment? {
        if (m == null) return null
        val id = (m["ingredient"] as? String)?.takeIf { FoodCatalogParser.ID.matches(it) } ?: return null
        val base = double(m["baseGrams"])?.takeIf { it in 0.0..MAX_FIELD } ?: return null
        val grams = double(m["grams"])?.takeIf { it in 0.0..MAX_FIELD } ?: return null
        return IngredientAdjustment(id, base, grams, double(m["factor"])?.takeIf { it in 0.0..100.0 })
    }

    // ---- Custom foods --------------------------------------------------------------------------

    fun customFoodToMap(f: CustomFood): Map<String, Any?> = linkedMapOf<String, Any?>(
        F_SCHEMA to schemaOf(f),
        F_DELETED to false,
        "name" to f.name,
        "brand" to f.brand,
        "barcode" to f.barcode,
        "unit" to f.unit.code,
    ).apply {
        putAll(nutrientsToMap(f.per100))
        put("portion", f.portion)
        put("source", f.source.code)
        put("createdAt", f.createdAt)
        put(F_EDITED_AT, f.updatedAt)
    }

    fun customFoodFromMap(id: String, data: Map<String, Any?>, updatedAtMillis: Long?): Decoded<CustomFood>? {
        tombstoneOf(id, data, updatedAtMillis)?.let { return it }
        if (id.isEmpty()) return null
        val name = (data["name"] as? String)?.takeIf { it.isNotBlank() } ?: return null
        val updatedAt = long(data[F_EDITED_AT]) ?: updatedAtMillis ?: 0L
        return Decoded.Live(
            CustomFood(
                id = id,
                name = name,
                brand = data["brand"] as? String,
                barcode = (data["barcode"] as? String)?.takeIf { it.isNotEmpty() },
                unit = FoodUnit.fromCode(data["unit"] as? String),
                per100 = nutrientsFromMap(data),
                portion = double(data["portion"])?.takeIf { it > 0 },
                source = CustomFoodSource.fromCode(data["source"] as? String),
                createdAt = long(data["createdAt"]) ?: updatedAt,
                updatedAt = updatedAt,
            ),
        )
    }

    // ---- Shared --------------------------------------------------------------------------------

    fun tombstoneToMap(deletedAt: Long): Map<String, Any?> = mapOf(F_SCHEMA to BASE_SCHEMA_VERSION, F_DELETED to true, F_DELETED_AT to deletedAt)

    /** Every nutrient, by [Nutrient.field] (the label's eight have the names 1.5.0 reads). */
    private fun nutrientsToMap(n: Nutrients): Map<String, Any?> = Nutrient.entries.associate { it.field to n[it] }

    /** Negative or non-finite values are unknown (a field an older version did not write too). */
    private fun nutrientsFromMap(data: Map<String, Any?>): Nutrients = Nutrients.of { double(data[it.field])?.takeIf { d -> d >= 0 } }

    private fun tombstoneOf(id: String, data: Map<String, Any?>, updatedAtMillis: Long?): Decoded.Dead? {
        if (data[F_DELETED] != true) return null
        return Decoded.Dead(Tombstone(id, long(data[F_DELETED_AT]) ?: updatedAtMillis ?: 0L))
    }

    private fun long(v: Any?): Long? = (v as? Number)?.toLong()

    private fun double(v: Any?): Double? = (v as? Number)?.toDouble()?.takeIf { it.isFinite() }
}
