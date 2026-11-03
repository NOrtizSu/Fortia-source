package com.enjoyyourtime.globalfitness.food.catalog

import com.enjoyyourtime.globalfitness.food.model.RecipeIngredient

/**
 * Reads the ingredients of the catalog's dishes, `assets/food/recipes.tsv` (UTF-8, tab-separated,
 * header `dish ingredient grams adjustable`): [RecipeIngredient.grams] of the ingredient in 100 g
 * (100 ml) of the finished dish, `adjustable` 1 or 0. Columns are found by name. Rows whose dish or
 * ingredient is not in [known] (when given), whose grams are not a positive number, or that make a
 * dish its own ingredient are skipped; an ingredient listed twice for a dish counts once with the
 * grams added. Dishes keep the file's ingredient order.
 */
object RecipeParser {
    fun parse(text: String, known: ((String) -> Boolean)? = null): Map<String, List<RecipeIngredient>> {
        val lines = text.removePrefix("﻿").lineSequence().map { it.trimEnd('\r') }.filter { it.isNotBlank() && !it.startsWith("#") }.iterator()
        if (!lines.hasNext()) return emptyMap()
        val header = lines.next().split('\t').map { it.trim().lowercase() }
        val col = header.withIndex().associate { (i, name) -> name to i }
        val dishCol = col["dish"] ?: return emptyMap()
        val ingredientCol = col["ingredient"] ?: return emptyMap()
        val gramsCol = col["grams"] ?: return emptyMap()
        val adjustableCol = col["adjustable"]
        val out = LinkedHashMap<String, LinkedHashMap<String, RecipeIngredient>>()
        for (line in lines) {
            val f = line.split('\t')
            val dish = f.getOrNull(dishCol)?.trim().orEmpty()
            val ingredient = f.getOrNull(ingredientCol)?.trim().orEmpty()
            val grams = f.getOrNull(gramsCol)?.trim()?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 } ?: continue
            if (!FoodCatalogParser.ID.matches(dish) || !FoodCatalogParser.ID.matches(ingredient) || dish == ingredient) continue
            if (known != null && (!known(dish) || !known(ingredient))) continue
            val adjustable = adjustableCol?.let { f.getOrNull(it)?.trim() } == "1"
            val rows = out.getOrPut(dish) { LinkedHashMap() }
            val previous = rows[ingredient]
            rows[ingredient] = if (previous == null) {
                RecipeIngredient(ingredient, grams, adjustable)
            } else {
                RecipeIngredient(ingredient, previous.grams + grams, previous.adjustable || adjustable)
            }
        }
        return out.mapValues { (_, rows) -> rows.values.toList() }
    }
}
