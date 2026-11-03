package com.enjoyyourtime.globalfitness.food.data

import com.enjoyyourtime.globalfitness.food.catalog.FoodCatalogParser
import com.enjoyyourtime.globalfitness.food.model.IngredientAdjustment

/**
 * The changed ingredients of an entry as stored in food.db's `adjustments` column:
 * `ingredient,baseGrams,grams,factor` per ingredient, separated by `;` (factor empty for grams typed),
 * numbers in plain dot notation. Null for none. Malformed parts are skipped when read.
 */
object AdjustmentsColumn {
    fun encode(adjustments: List<IngredientAdjustment>): String? =
        adjustments.takeIf { it.isNotEmpty() }?.joinToString(";") { a ->
            listOf(a.ingredient, a.baseGrams.toString(), a.grams.toString(), a.factor?.toString().orEmpty()).joinToString(",")
        }

    fun decode(text: String?): List<IngredientAdjustment> {
        if (text.isNullOrBlank()) return emptyList()
        return text.split(';').mapNotNull { part ->
            val f = part.split(',')
            if (f.size < 3) return@mapNotNull null
            val id = f[0].trim().takeIf { FoodCatalogParser.ID.matches(it) } ?: return@mapNotNull null
            val base = f[1].toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 } ?: return@mapNotNull null
            val grams = f[2].toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 } ?: return@mapNotNull null
            val factor = f.getOrNull(3)?.takeIf { it.isNotBlank() }?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
            IngredientAdjustment(id, base, grams, factor)
        }.distinctBy { it.ingredient }
    }
}
