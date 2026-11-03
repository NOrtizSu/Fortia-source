package com.enjoyyourtime.globalfitness.food.catalog

import com.enjoyyourtime.globalfitness.food.model.FoodEntry
import com.enjoyyourtime.globalfitness.food.model.FoodItem
import com.enjoyyourtime.globalfitness.food.model.FoodRef
import java.text.Normalizer

/** How often and how recently the user logged a food. */
data class FoodUsage(val count: Int, val lastUsed: Long)

/**
 * Finding foods by name in the user's language: case- and accent-insensitive ("platano" finds
 * "Plátano"), every word of the query must match. Ranking: the name starts with the query, then a
 * word of it does, then it only contains it; foods the user logs come first among the first two
 * kinds (most used, then most recent), so everyday foods are one tap away. Pure Kotlin.
 */
object FoodSearch {
    /** Accents and other combining marks, but not the kana voicing marks (パン is not ハン). */
    private val MARKS = Regex("[\\p{Mn}&&[^\\u3099\\u309A]]+")

    /**
     * Lowercase, without accents, letters and digits only, single spaces; hiragana as katakana (ぴざ
     * finds ピザ: Japanese names are written either way).
     */
    fun normalize(s: String): String {
        val plain = MARKS.replace(Normalizer.normalize(s, Normalizer.Form.NFD), "").lowercase()
        val out = StringBuilder(plain.length)
        var space = true
        for (ch in plain) {
            val c = if (ch in HIRAGANA) ch + KATAKANA_OFFSET else ch
            if (c.isLetterOrDigit() || c == VOICED || c == SEMI_VOICED) {
                out.append(c)
                space = false
            } else if (!space) {
                out.append(' ')
                space = true
            }
        }
        return out.toString().trimEnd()
    }

    /** The hiragana letters ぁ to ゖ; the katakana of each is 0x60 further (ァ to ヶ). */
    private val HIRAGANA = 'ぁ'..'ゖ'
    private const val KATAKANA_OFFSET = 0x60

    /** The combining (dakuten) and semi-voiced (handakuten) marks: ぴ is ひ + U+309A once decomposed. */
    private const val VOICED = '゙'
    private const val SEMI_VOICED = '゚'

    /** 3: the name starts with the query; 2: every query word starts a word of the name; 1: every word is in it; 0: no match. */
    fun tier(name: String, query: String): Int {
        if (query.isEmpty()) return 0
        if (name.startsWith(query)) return 3
        val words = name.split(' ')
        val terms = query.split(' ')
        if (terms.all { t -> words.any { it.startsWith(t) } }) return 2
        if (terms.all { name.contains(it) }) return 1
        return 0
    }

    /** Each food's usage in [entries] (by catalog or custom food; quick manual entries have none). */
    fun usage(entries: Iterable<FoodEntry>): Map<FoodRef, FoodUsage> {
        val out = HashMap<FoodRef, FoodUsage>()
        for (e in entries) {
            val ref = FoodRef.of(e.foodId, e.customFoodId) ?: continue
            val u = out[ref]
            out[ref] = if (u == null) FoodUsage(1, e.eatenAt) else FoodUsage(u.count + 1, maxOf(u.lastUsed, e.eatenAt))
        }
        return out
    }

    /**
     * The best [limit] matches of [query] among [items]. An empty query lists the recent foods (most
     * recently logged first).
     */
    fun search(items: List<FoodItem>, usage: Map<FoodRef, FoodUsage>, query: String, limit: Int = 50): List<FoodItem> {
        val q = normalize(query)
        if (q.isEmpty()) {
            return items.mapNotNull { item -> item.ref?.let(usage::get)?.let { item to it } }
                .sortedWith(compareByDescending<Pair<FoodItem, FoodUsage>> { it.second.lastUsed }.thenByDescending { it.second.count })
                .take(limit)
                .map { it.first }
        }
        data class Hit(val item: FoodItem, val tier: Int, val used: FoodUsage?, val name: String)
        val hits = items.mapNotNull { item ->
            val name = normalize(item.name)
            var t = tier(name, q)
            if (t == 0 && item.brand != null) t = if (tier(normalize(item.name + " " + item.brand), q) > 0) 1 else 0
            if (t == 0) null else Hit(item, t, item.ref?.let(usage::get), name)
        }
        val ranked = hits.sortedWith(
            compareByDescending<Hit> { if (it.used != null && it.tier >= 2) 1 else 0 }
                .thenByDescending { it.tier }
                .thenByDescending { it.used?.count ?: 0 }
                .thenByDescending { it.used?.lastUsed ?: 0L }
                .thenBy { it.name.length }
                .thenBy { it.name },
        ).map { it.item }
        return withVariants(ranked, items).take(limit)
    }

    /**
     * [ranked] with the variants of each catalog food in it right after it (a search that finds
     * "Coffee" also lists "Cortado"), each food once, at its first place.
     */
    fun withVariants(ranked: List<FoodItem>, items: List<FoodItem>): List<FoodItem> {
        val variants = items.filter { it.variantOf != null }.groupBy { it.variantOf }
        if (variants.isEmpty()) return ranked
        val out = ArrayList<FoodItem>(ranked.size)
        val seen = HashSet<FoodRef>()
        for (item in ranked) {
            val ref = item.ref
            if (ref != null && !seen.add(ref)) continue
            out += item
            val id = item.foodId ?: continue
            for (v in variants[id].orEmpty()) if (v.ref?.let(seen::add) != false) out += v
        }
        return out
    }
}
