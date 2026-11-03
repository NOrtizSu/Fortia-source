package com.enjoyyourtime.globalfitness.food.catalog

import com.enjoyyourtime.globalfitness.food.model.CatalogFood
import com.enjoyyourtime.globalfitness.food.model.FoodGroup
import com.enjoyyourtime.globalfitness.food.model.FoodUnit
import com.enjoyyourtime.globalfitness.food.model.Nutrient
import com.enjoyyourtime.globalfitness.food.model.Nutrients
import com.enjoyyourtime.globalfitness.food.model.Portion
import com.enjoyyourtime.globalfitness.food.model.PortionKey

/**
 * Reads the offline food catalog `assets/food/foods.tsv` (UTF-8, tab-separated, one header row).
 * Version 1: `id group unit kcal protein carbs sugars fat sat_fat fiber salt portion portions visual
 * source`; version 2 appends `mono_fat poly_fat cholesterol potassium calcium iron magnesium zinc
 * vit_a vit_c vit_d vit_e vit_b12 folate variant_of` ([Nutrient.code] for the values, see [Nutrient]
 * for their units). Values per 100 g (per 100 ml when `unit` is `ml`), empty = unknown. Columns are
 * found by their header name, so either version (or a file with more columns, or another order)
 * reads. Malformed rows (an id that is not a lowercase slug, a duplicate id) are skipped; an unknown
 * portion key is dropped; a `variant_of` that names no food, a variant or the food itself is ignored
 * (the row stays, as a plain food).
 */
object FoodCatalogParser {
    val ID = Regex("[a-z0-9_]+")

    fun parse(text: String): List<CatalogFood> {
        val lines = text.removePrefix("﻿").lineSequence().map { it.trimEnd('\r') }.filter { it.isNotBlank() && !it.startsWith("#") }.iterator()
        if (!lines.hasNext()) return emptyList()
        val header = lines.next().split('\t').map { it.trim().lowercase() }
        val col = header.withIndex().associate { (i, name) -> name to i }
        val idCol = col["id"] ?: return emptyList()
        val seen = HashSet<String>()
        val out = ArrayList<CatalogFood>()
        for (line in lines) {
            val f = line.split('\t')
            fun cell(name: String): String? = col[name]?.let { f.getOrNull(it) }?.trim()?.takeIf { it.isNotEmpty() }
            fun num(name: String): Double? = cell(name)?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
            val id = f.getOrNull(idCol)?.trim().orEmpty()
            if (!ID.matches(id) || !seen.add(id)) continue
            out += CatalogFood(
                id = id,
                group = FoodGroup.fromCode(cell("group")),
                unit = FoodUnit.fromCode(cell("unit")),
                per100 = Nutrients.of { num(it.code) },
                portion = num("portion")?.takeIf { it > 0 },
                portions = portions(cell("portions")),
                visual = cell("visual") == "1",
                source = cell("source"),
                variantOf = cell("variant_of")?.takeIf { ID.matches(it) && it != id },
            )
        }
        // A variant's base must be a food of the file, and not a variant itself.
        val byId = out.associateBy { it.id }
        return out.map { food ->
            val base = food.variantOf?.let(byId::get)
            if (food.variantOf != null && (base == null || base.variantOf != null)) food.copy(variantOf = null) else food
        }
    }

    /** `unit:120|slice:30` → portions in that order (unknown keys, bad or non-positive amounts dropped). */
    fun portions(text: String?): List<Portion> =
        text.orEmpty().split('|').mapNotNull { part ->
            val key = PortionKey.fromCode(part.substringBefore(':', "").trim()) ?: return@mapNotNull null
            val amount = part.substringAfter(':', "").trim().toDoubleOrNull()?.takeIf { it > 0 && it.isFinite() } ?: return@mapNotNull null
            Portion(key, amount)
        }.distinctBy { it.key }
}
