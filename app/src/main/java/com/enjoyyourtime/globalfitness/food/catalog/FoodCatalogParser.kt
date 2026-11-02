package com.enjoyyourtime.globalfitness.food.catalog

import com.enjoyyourtime.globalfitness.food.model.CatalogFood
import com.enjoyyourtime.globalfitness.food.model.FoodGroup
import com.enjoyyourtime.globalfitness.food.model.FoodUnit
import com.enjoyyourtime.globalfitness.food.model.Nutrients
import com.enjoyyourtime.globalfitness.food.model.Portion
import com.enjoyyourtime.globalfitness.food.model.PortionKey

/**
 * Reads the offline food catalog `assets/food/foods.tsv` (UTF-8, tab-separated, one header row):
 * `id group unit kcal protein carbs sugars fat sat_fat fiber salt portion portions visual source`,
 * values per 100 g (per 100 ml when `unit` is `ml`), empty = unknown. Columns are found by their
 * header name, so a file with more columns (or another order) still reads. Malformed rows (an id
 * that is not a lowercase slug, a duplicate id) are skipped; an unknown portion key is dropped.
 */
object FoodCatalogParser {
    val ID = Regex("[a-z0-9_]+")

    fun parse(text: String): List<CatalogFood> {
        val lines = text.removePrefix("\uFEFF").lineSequence().map { it.trimEnd('\r') }.filter { it.isNotBlank() && !it.startsWith("#") }.iterator()
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
                per100 = Nutrients(
                    kcal = num("kcal"),
                    protein = num("protein"),
                    carbs = num("carbs"),
                    sugars = num("sugars"),
                    fat = num("fat"),
                    satFat = num("sat_fat"),
                    fiber = num("fiber"),
                    salt = num("salt"),
                ),
                portion = num("portion")?.takeIf { it > 0 },
                portions = portions(cell("portions")),
                visual = cell("visual") == "1",
                source = cell("source"),
            )
        }
        return out
    }

    /** `unit:120|slice:30` → portions in that order (unknown keys, bad or non-positive amounts dropped). */
    fun portions(text: String?): List<Portion> =
        text.orEmpty().split('|').mapNotNull { part ->
            val key = PortionKey.fromCode(part.substringBefore(':', "").trim()) ?: return@mapNotNull null
            val amount = part.substringAfter(':', "").trim().toDoubleOrNull()?.takeIf { it > 0 && it.isFinite() } ?: return@mapNotNull null
            Portion(key, amount)
        }.distinctBy { it.key }
}
