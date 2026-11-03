package com.enjoyyourtime.globalfitness.food.off

import com.enjoyyourtime.globalfitness.food.model.CustomFood
import com.enjoyyourtime.globalfitness.food.model.CustomFoodSource
import com.enjoyyourtime.globalfitness.food.model.Nutrient
import com.enjoyyourtime.globalfitness.food.model.Nutrients

/**
 * Products found by barcode are kept as custom foods (the next scan needs no connection). A product
 * kept incomplete (no name, no kcal) or not checked for [MAX_AGE_MS] is asked again when scanned.
 * Pure (unit-tested).
 */
object OffCache {
    /** Open Food Facts is edited all the time: a product kept longer is checked again (about three months). */
    const val MAX_AGE_MS = 90L * 24 * 60 * 60_000

    /** A new cached product (id and times assigned by the repository). */
    fun customFoodOf(p: OffProduct, code: String) = CustomFood(
        id = "",
        name = p.name ?: code,
        brand = p.brand,
        barcode = code,
        unit = p.unit,
        per100 = p.per100,
        portion = p.serving,
        source = CustomFoodSource.OFF,
        createdAt = 0,
        updatedAt = 0,
    )

    /** No real name (the code stands for it) or no kcal: Open Food Facts may know more by now. */
    fun incomplete(f: CustomFood): Boolean = f.name == f.barcode || f.per100.kcal == null

    /**
     * Whether a scan of [f] asks Open Food Facts again: incomplete, or a product of Open Food Facts not
     * checked for [MAX_AGE_MS] (its last check, else its last change; the user's own foods are never
     * replaced just for their age).
     */
    fun needsRefresh(f: CustomFood, now: Long): Boolean =
        f.barcode != null && (incomplete(f) || f.source == CustomFoodSource.OFF && now - (f.checkedAt ?: f.updatedAt) > MAX_AGE_MS)

    /**
     * [cached] with what Open Food Facts says now: its name, brand, serving and values where it gives
     * them, the cached ones (maybe completed by the user) elsewhere. Values per 100 g and per 100 ml do
     * not mix: a product now measured in the other unit takes only the new values. [now] marks it
     * checked ([CustomFood.checkedAt]); its [CustomFood.updatedAt] is the repository's, moved only
     * when something else changed (an entry logged with values typed by hand keeps them for the next one).
     */
    fun refreshed(cached: CustomFood, p: OffProduct, now: Long): CustomFood {
        val unitChanged = !p.per100.isEmpty && p.unit != cached.unit
        var per100 = if (unitChanged) Nutrients.NONE else cached.per100
        for (n in Nutrient.entries) p.per100[n]?.let { per100 = per100.with(n, it) }
        val fromOff = p.name != null || !p.per100.isEmpty
        return cached.copy(
            name = p.name ?: cached.name,
            brand = p.brand ?: cached.brand,
            unit = if (p.per100.isEmpty) cached.unit else p.unit,
            per100 = per100,
            portion = p.serving ?: cached.portion.takeUnless { unitChanged },
            // Data of Open Food Facts now: shown with its ODbL attribution.
            source = if (fromOff) CustomFoodSource.OFF else cached.source,
            checkedAt = now,
        )
    }
}
