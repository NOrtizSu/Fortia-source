package com.enjoyyourtime.globalfitness.food.pack

import com.enjoyyourtime.globalfitness.food.recognition.EmbeddingSpec
import kotlin.math.sqrt

/**
 * What one product is compared with: the embedding of its Open Food Facts front photo ([frontImage]
 * is that photo's file, so a new photo is embedded again) and those of the user's own photos logged
 * as it ([confirmed], oldest first).
 */
class ProductRefs(val frontImage: String?, val front: FloatArray?, val confirmed: List<FloatArray> = emptyList()) {
    val all: List<FloatArray> get() = listOfNotNull(front) + confirmed

    val isEmpty: Boolean get() = front == null && confirmed.isEmpty()
}

/** What embedding a picture gave. */
sealed interface Embedded {
    /** The L2-normalised embedding. */
    class Vector(val values: FloatArray) : Embedded

    /** The model gives an all-zero vector for this picture: it has no usable embedding (not tried again). */
    data object Zero : Embedded

    /** This picture's run failed (out of memory, an error, values that are not numbers): tried again later; other pictures still are. */
    data object Failed : Embedded

    /** No usable model: none in the app, it could not load, or it has no embedding output. Nothing is embedded now. */
    data object Unavailable : Embedded
}

/** A product's best cosine with the photo: [query] is which of the photo's embeddings (0: the whole photo) gave it. */
data class PackScore(val productId: String, val score: Float, val query: Int)

/** What the photo looks like among the products the user already scanned. */
sealed interface PackFind {
    /** One product clearly ahead: "Is it …?". */
    data class Match(val best: PackScore) : PackFind

    /** A few products close enough to be suggested (best first, none picked). */
    data class Suggestions(val products: List<PackScore>) : PackFind
}

/** The matching of embedding_contract.md (pure, unit-tested). */
object PackMath {
    /** Below this norm an embedding is unusable (all zero). */
    private const val MIN_NORM = 1e-6

    /** How many of the user's own photos each product keeps (the oldest goes first). */
    const val MAX_CONFIRMED = 5

    /** [v] divided by its length, or null when it is (nearly) zero or not finite. */
    fun normalized(v: FloatArray): FloatArray? {
        var sum = 0.0
        for (x in v) {
            if (!x.isFinite()) return null
            sum += x.toDouble() * x
        }
        val norm = sqrt(sum)
        if (norm < MIN_NORM) return null
        return FloatArray(v.size) { (v[it] / norm).toFloat() }
    }

    /** The model's raw output [v] as an embedding: not finite is a failed run, (nearly) zero is no embedding. */
    fun embedded(v: FloatArray): Embedded {
        if (v.isEmpty() || v.any { !it.isFinite() }) return Embedded.Failed
        return normalized(v)?.let(Embedded::Vector) ?: Embedded.Zero
    }

    /** The cosine of two normalised embeddings (their dot product); -1 when their sizes differ. */
    fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return -1f
        var s = 0.0
        for (i in a.indices) s += a[i].toDouble() * b[i]
        return s.toFloat()
    }

    /** Every product's best score against any of [queries] (best first; ties by id). Products without references are left out. */
    fun scores(queries: List<FloatArray>, refs: Map<String, ProductRefs>): List<PackScore> = refs.mapNotNull { (id, r) ->
        var best: PackScore? = null
        for ((qi, q) in queries.withIndex()) {
            for (g in r.all) {
                val s = cosine(q, g)
                if (best == null || s > best.score) best = PackScore(id, s, qi)
            }
        }
        best
    }.sortedWith(compareByDescending<PackScore> { it.score }.thenBy { it.productId })

    /**
     * The contract's rule: the best product is a match when it scores at least `threshold` and `margin`
     * more than any other product (0 for none); otherwise, with [suggest], the products scoring at
     * least `suggest_threshold` (at most `suggest_max`) are suggestions; otherwise nothing.
     */
    fun find(scores: List<PackScore>, rule: EmbeddingSpec, suggest: Boolean = true): PackFind? {
        val best = scores.firstOrNull() ?: return null
        val second = scores.getOrNull(1)?.score ?: 0f
        if (best.score >= rule.threshold && best.score - second >= rule.margin) return PackFind.Match(best)
        if (!suggest) return null
        val close = scores.filter { it.score >= rule.suggestThreshold }.take(rule.suggestMax)
        return if (close.isEmpty()) null else PackFind.Suggestions(close)
    }

    /**
     * What a photo looks like: [queries] are the whole photo's embedding, then those of its items (a
     * plate). The whole photo follows the contract's rule (a match, else suggestions); the items, which
     * the contract did not measure, only count with a full match (a match of the whole photo wins).
     */
    fun look(queries: List<FloatArray>, refs: Map<String, ProductRefs>, rule: EmbeddingSpec): PackFind? {
        val whole = queries.firstOrNull() ?: return null
        val photo = find(scores(listOf(whole), refs), rule)
        if (photo is PackFind.Match) return photo
        val items = queries.drop(1)
        val item = if (items.isEmpty()) null else find(scores(items, refs), rule, suggest = false) as? PackFind.Match
        return item?.let { PackFind.Match(it.best.copy(query = it.best.query + 1)) } ?: photo
    }

    /**
     * Whether a photo the user logged a product from (embedding [v]) is kept as one of its references:
     * it must look like the product's front photo (at least `suggest_threshold`), so the back of a
     * package photographed for its barcode is not kept; without a front embedding yet, it is kept.
     */
    fun keepsOwnPhoto(v: FloatArray, front: FloatArray?, rule: EmbeddingSpec): Boolean =
        front == null || cosine(v, front) >= rule.suggestThreshold
}
