package com.enjoyyourtime.globalfitness.food

import com.enjoyyourtime.globalfitness.food.pack.Embedded
import com.enjoyyourtime.globalfitness.food.pack.PackFind
import com.enjoyyourtime.globalfitness.food.pack.PackMath
import com.enjoyyourtime.globalfitness.food.pack.ProductEmbeddings
import com.enjoyyourtime.globalfitness.food.pack.ProductRefs
import com.enjoyyourtime.globalfitness.food.recognition.EmbeddingSpec
import com.enjoyyourtime.globalfitness.food.recognition.ModelSpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** "Products you already scanned": the embedding in the model's json, the matching rule, and the stored embeddings. */
class ProductMatchTest {
    @get:Rule
    val folder = TemporaryFolder()

    // Fixed values (contract v1's), so these cases do not move with the shipped defaults.
    private val rule = EmbeddingSpec(outputIndex = 2, space = "s1", threshold = 0.65f, margin = 0.06f, suggestThreshold = 0.56f)

    /** A unit vector at [degrees] from the first axis in the plane of the first two (cosine with [at] (0) is cos(degrees)). */
    private fun at(degrees: Double, dim: Int = 4): FloatArray {
        val r = Math.toRadians(degrees)
        return FloatArray(dim).also {
            it[0] = cos(r).toFloat()
            it[1] = sin(r).toFloat()
        }
    }

    /** The angle whose cosine is [c]. */
    private fun angle(c: Double) = Math.toDegrees(kotlin.math.acos(c))

    // ---- json ---------------------------------------------------------------------------------------

    private val json = """
        {"version": 3, "labels": ["apple", "bread", "_not_food"],
         "input": {"width": 320, "height": 320, "mean": [0.485, 0.456, 0.406], "std": [0.229, 0.224, 0.225]},
         "embedding": {"output": {"index": 2, "shape": [1, 960], "dtype": "float32", "kind": "embedding"},
           "input": {"resize": "stretch"}, "l2_normalize": true, "metric": "cosine",
           "space": "fortia-food-emb-dummy-2026-11-04",
           "match": {"threshold": 0.7, "margin": 0.05, "suggest_threshold": 0.6, "suggest_max": 2}}}
    """.trimIndent()

    @Test
    fun theEmbeddingBlockIsRead() {
        val e = ModelSpec.parse(json).embedding!!
        assertEquals(2, e.outputIndex)
        assertEquals("fortia-food-emb-dummy-2026-11-04", e.space)
        assertTrue(e.stretch)
        assertEquals(0.7f, e.threshold)
        assertEquals(0.05f, e.margin)
        assertEquals(0.6f, e.suggestThreshold)
        assertEquals(2, e.suggestMax)
    }

    @Test
    fun withoutTheBlockOrASpaceTheFeatureIsOff() {
        assertNull(ModelSpec.parse("""{"labels": ["apple", "_not_food"]}""").embedding)
        assertNull(ModelSpec.parse("""{"labels": ["apple", "_not_food"], "embedding": {"output": {"index": 2}}}""").embedding)
        // The shipped 1.6.0 model has none.
        val shipped = File("src/main/assets/food/food_model.json").takeIf { it.isFile }?.readText()
        if (shipped != null && !shipped.contains("\"embedding\"")) assertNull(ModelSpec.parse(shipped).embedding)
    }

    @Test
    fun missingMatchValuesTakeTheContractsOnes() {
        val e = EmbeddingSpec.parse(mapOf("space" to "x"))!!
        // Contract v2's values.
        assertEquals(0.67f, e.threshold)
        assertEquals(0.075f, e.margin)
        assertEquals(0.57f, e.suggestThreshold)
        assertEquals(3, e.suggestMax)
        assertNull(e.outputIndex)
    }

    @Test
    fun theShippedModelCarriesTheEmbeddingOfContractV2() {
        val spec = ModelSpec.parse(File("src/main/assets/food/food_model.json").readText())
        assertEquals("_not_food", spec.labels.last())
        val e = assertNotNull(spec.embedding)
        assertEquals("fortia-food-3-int8-2026-11-05", e.space)
        assertEquals(2, e.outputIndex)
        assertTrue(e.stretch)
        assertEquals(0.67f, e.threshold)
        assertEquals(0.075f, e.margin)
        assertEquals(0.57f, e.suggestThreshold)
        assertEquals(3, e.suggestMax)
    }

    private fun <T> assertNotNull(value: T?): T {
        org.junit.Assert.assertNotNull(value)
        return value!!
    }

    // ---- matching -----------------------------------------------------------------------------------

    @Test
    fun embeddingsAreNormalisedAndAZeroOneIsUnusable() {
        val v = PackMath.normalized(floatArrayOf(3f, 4f))!!
        assertArrayEquals(floatArrayOf(0.6f, 0.8f), v, 1e-6f)
        assertNull(PackMath.normalized(FloatArray(960)))
        assertNull(PackMath.normalized(floatArrayOf(1f, Float.NaN)))
        assertEquals(1f, PackMath.cosine(v, v), 1e-6f)
        // Another model's size never compares.
        assertEquals(-1f, PackMath.cosine(v, floatArrayOf(1f, 0f, 0f)))
    }

    @Test
    fun aProductScoresItsBestPhotoAgainstAnyPictureOfTheMeal() {
        val refs = mapOf(
            // Its OFF photo is far, but a photo the user logged as it is close.
            "biscuits" to ProductRefs("off_1.jpg", at(80.0), listOf(at(angle(0.9)))),
            "yogurt" to ProductRefs("off_2.jpg", at(angle(0.5))),
        )
        val scores = PackMath.scores(listOf(at(0.0), at(180.0)), refs)
        assertEquals(listOf("biscuits", "yogurt"), scores.map { it.productId })
        assertEquals(0.9f, scores[0].score, 1e-4f)
        // The whole photo (picture 0) gave it.
        assertEquals(0, scores[0].query)
        // A product with nothing stored is not a candidate.
        assertTrue(PackMath.scores(listOf(at(0.0)), mapOf("x" to ProductRefs(null, null))).isEmpty())
    }

    @Test
    fun aMatchNeedsTheThresholdAndTheMarginOverTheSecond() {
        fun scores(vararg s: Double) = PackMath.scores(listOf(at(0.0)), s.withIndex().associate { (i, c) -> "p$i" to ProductRefs("f$i", at(angle(c))) })
        // 0.80 vs 0.70: ahead by 0.10 ≥ 0.06.
        assertEquals("p0", (PackMath.find(scores(0.80, 0.70), rule) as PackFind.Match).best.productId)
        // 0.80 vs 0.77: too close; both are suggestions.
        val close = PackMath.find(scores(0.80, 0.77, 0.30), rule) as PackFind.Suggestions
        assertEquals(listOf("p0", "p1"), close.products.map { it.productId })
        // 0.62: under the threshold, a suggestion only.
        assertEquals(listOf("p0"), (PackMath.find(scores(0.62, 0.20), rule) as PackFind.Suggestions).products.map { it.productId })
        // Nothing close.
        assertNull(PackMath.find(scores(0.50, 0.40), rule))
        assertNull(PackMath.find(emptyList(), rule))
        // A single product: the second counts as 0.
        assertTrue(PackMath.find(scores(0.66), rule) is PackFind.Match)
        // At most suggest_max suggestions.
        assertEquals(3, (PackMath.find(scores(0.60, 0.60, 0.59, 0.58, 0.57), rule) as PackFind.Suggestions).products.size)
    }

    // ---- the stored embeddings --------------------------------------------------------------------------

    private fun store(): Pair<ProductEmbeddings, File> {
        val file = File(folder.root, "product_embeddings.bin")
        return ProductEmbeddings { file } to file
    }

    private fun v(x: Float) = floatArrayOf(x, sqrt(1 - x * x))

    @Test
    fun frontsAndPhotosAreKeptAcrossARestartOfTheApp() {
        val (s, file) = store()
        assertTrue(s.refs("s1").isEmpty())
        assertEquals(listOf("a" to "off_a.jpg"), s.frontsToEmbed("s1", mapOf("a" to "off_a.jpg", "b" to null)))
        s.setFront("s1", "a", "off_a.jpg", v(0.1f))
        s.addConfirmed("s1", "a", "meal_1.jpg", v(0.2f))
        assertTrue(file.isFile)
        // Embedded already: not again.
        assertTrue(s.frontsToEmbed("s1", mapOf("a" to "off_a.jpg")).isEmpty())

        val again = ProductEmbeddings { file }
        val a = again.refs("s1").getValue("a")
        assertEquals("off_a.jpg", a.frontImage)
        assertArrayEquals(v(0.1f), a.front, 1e-3f)
        assertEquals(1, a.confirmed.size)
        assertArrayEquals(v(0.2f), a.confirmed[0], 1e-3f)
    }

    @Test
    fun theUsersPhotosAreCappedNewestKept() {
        val (s, _) = store()
        for (i in 1..7) s.addConfirmed("s1", "a", "meal_$i.jpg", v(i / 10f))
        val kept = s.stored("s1", "a")!!.confirmed
        assertEquals(PackMath.MAX_CONFIRMED, kept.size)
        assertEquals((3..7).map { "meal_$it.jpg" }, kept.map { it.photo })
        // The same meal photo again replaces its embedding.
        s.addConfirmed("s1", "a", "meal_7.jpg", v(0.9f))
        assertEquals(PackMath.MAX_CONFIRMED, s.stored("s1", "a")!!.confirmed.size)
        assertArrayEquals(v(0.9f), s.stored("s1", "a")!!.confirmed.last().vector, 0f)
    }

    @Test
    fun aDeletedProductLosesItsEmbeddingsAndANewPhotoIsEmbeddedAgain() {
        val (s, file) = store()
        s.setFront("s1", "a", "off_a.jpg", v(0.1f))
        s.setFront("s1", "b", "off_b.jpg", v(0.2f))
        s.addConfirmed("s1", "b", "meal_1.jpg", v(0.3f))
        s.setFront("s1", "c", "off_c.jpg", v(0.4f))
        // a deleted (here or by the backup), b got another photo, c unchanged.
        s.retain(mapOf("b" to "off_b2.jpg", "c" to "off_c.jpg"))
        assertNull(s.stored("s1", "a"))
        val b = s.stored("s1", "b")!!
        assertNull(b.front)
        assertEquals(1, b.confirmed.size)
        assertEquals(listOf("b" to "off_b2.jpg"), s.frontsToEmbed("s1", mapOf("b" to "off_b2.jpg", "c" to "off_c.jpg")))
        // Also on disk.
        assertEquals(setOf("b", "c"), ProductEmbeddings { file }.refs("s1").keys)
        // A product whose photo is gone and that has no photo of the user's goes entirely.
        s.retain(mapOf("b" to "off_b2.jpg", "c" to null))
        assertNull(s.stored("s1", "c"))
    }

    @Test
    fun aNewModelDropsTheVectorsAndKeepsWhatToEmbedAgain() {
        val (s, file) = store()
        s.setFront("s1", "a", "off_a.jpg", v(0.1f))
        s.addConfirmed("s1", "a", "meal_1.jpg", v(0.2f))
        s.addConfirmed("s1", "a", null, v(0.3f))
        val next = ProductEmbeddings { file }
        // Another space: nothing comparable any more…
        assertTrue(next.refs("s2").isEmpty())
        // …the front photo is embedded again, and the user's photo that is known by name.
        assertEquals(listOf("a" to "off_a.jpg"), next.frontsToEmbed("s2", mapOf("a" to "off_a.jpg")))
        assertEquals(listOf("a" to "meal_1.jpg"), next.confirmedToEmbed("s2"))
        next.setFront("s2", "a", "off_a.jpg", v(0.5f))
        next.setConfirmed("s2", "a", "meal_1.jpg", v(0.6f))
        val a = next.refs("s2").getValue("a")
        assertNotNull(a.front)
        assertEquals(1, a.confirmed.size)
        assertTrue(next.confirmedToEmbed("s2").isEmpty())
        // A meal photo that is gone is dropped.
        next.addConfirmed("s2", "a", "meal_2.jpg", v(0.7f))
        val third = ProductEmbeddings { file }
        third.refs("s3")
        third.setConfirmed("s3", "a", "meal_2.jpg", null)
        assertEquals(listOf("meal_1.jpg"), third.stored("s3", "a")!!.confirmed.map { it.photo })
    }

    @Test
    fun anUnreadableFileCountsAsEmpty() {
        val (_, file) = store()
        file.writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        val s = ProductEmbeddings { file }
        assertTrue(s.refs("s1").isEmpty())
        s.setFront("s1", "a", "off_a.jpg", v(0.1f))
        assertEquals(setOf("a"), ProductEmbeddings { file }.refs("s1").keys)
    }

    @Test
    fun aFileCutShortCountsAsEmptyAndIsReplaced() {
        val (s, file) = store()
        s.setFront("s1", "a", "off_a.jpg", v(0.1f))
        s.addConfirmed("s1", "a", "meal_1.jpg", v(0.2f))
        val bytes = file.readBytes()
        // The phone switched off while it was written (the rename makes this rare, a copy fallback does not).
        file.writeBytes(bytes.copyOf(bytes.size - 7))
        val again = ProductEmbeddings { file }
        assertTrue(again.refs("s1").isEmpty())
        assertFalse(file.exists())
        // Embedded again from the photos at the next sync.
        assertEquals(listOf("a" to "off_a.jpg"), again.frontsToEmbed("s1", mapOf("a" to "off_a.jpg")))
    }

    @Test
    fun vectorsAreStoredAsFloat16() {
        for (f in floatArrayOf(0f, 1f, -1f, 0.5f, 0.0322f, -0.0007f, 1e-6f, 6.1e-5f, 65504f)) {
            assertEquals(f, ProductEmbeddings.fromHalf(ProductEmbeddings.toHalf(f)), maxOf(abs(f) * 1e-3f, 6e-8f))
        }
        assertTrue(ProductEmbeddings.fromHalf(ProductEmbeddings.toHalf(1e6f)).isInfinite())
        // A 960-value embedding keeps its cosines to the third decimal, in half the size.
        val random = java.util.Random(3)
        val a = PackMath.normalized(FloatArray(960) { random.nextGaussian().toFloat() })!!
        val b = PackMath.normalized(FloatArray(960) { a[it] + 0.5f * random.nextGaussian().toFloat() })!!
        val (s, file) = store()
        s.setFront("s1", "a", "off_a.jpg", a)
        val stored = ProductEmbeddings { file }.refs("s1").getValue("a").front!!
        assertEquals(PackMath.cosine(a, b), PackMath.cosine(stored, b), 1e-3f)
        assertTrue(file.length() < 960 * 2 + 100)
    }

    @Test
    fun aBatchIsWrittenOnlyWhenFlushed() {
        val (s, file) = store()
        s.setFront("s1", "a", "off_a.jpg", v(0.1f), persist = false)
        s.setFront("s1", "b", "off_b.jpg", v(0.2f), persist = false)
        assertFalse(file.exists())
        s.flush()
        assertEquals(setOf("a", "b"), ProductEmbeddings { file }.refs("s1").keys)
    }

    @Test
    fun theFileIsNotReadJustToFollowTheProducts() {
        val (s, file) = store()
        s.setFront("s1", "a", "off_a.jpg", v(0.1f))
        val fresh = ProductEmbeddings { file }
        // At app start the products are followed without reading the file…
        fresh.retain(emptyMap(), load = false)
        assertFalse(fresh.loaded)
        // …the first use reads it (and then drops what was deleted meanwhile).
        fresh.retain(emptyMap())
        assertTrue(fresh.loaded)
        assertTrue(fresh.refs("s1").isEmpty())
    }

    @Test
    fun anAllZeroOrBrokenOutputIsNotAnEmbedding() {
        assertTrue(PackMath.embedded(FloatArray(960)) is Embedded.Zero)
        assertTrue(PackMath.embedded(floatArrayOf(1f, Float.NaN)) is Embedded.Failed)
        assertTrue(PackMath.embedded(FloatArray(0)) is Embedded.Failed)
        assertArrayEquals(floatArrayOf(0.6f, 0.8f), (PackMath.embedded(floatArrayOf(3f, 4f)) as Embedded.Vector).values, 1e-6f)
    }

    @Test
    fun itemsOfAPlateOnlyCountWithAFullMatch() {
        val refs = mapOf("a" to ProductRefs("fa", at(0.0)), "b" to ProductRefs("fb", at(270.0)))
        // The whole photo is close to nothing; one item is a clear match of "a".
        val clear = PackMath.look(listOf(at(180.0), at(angle(0.9))), refs, rule)
        assertEquals("a", (clear as PackFind.Match).best.productId)
        assertEquals(1, clear.best.query)
        // An item only "close" (0.60) is no suggestion: the contract measured whole photos.
        assertNull(PackMath.look(listOf(at(180.0), at(angle(0.60))), refs, rule))
        // The whole photo still gives suggestions.
        assertTrue(PackMath.look(listOf(at(angle(0.60))), refs, rule) is PackFind.Suggestions)
    }

    @Test
    fun theBackOfAPackageIsNotKeptAsTheProductsPhoto() {
        val front = at(0.0)
        assertTrue(PackMath.keepsOwnPhoto(at(angle(0.7)), front, rule))
        // Far from the front photo (the back of the package, photographed for its barcode).
        assertFalse(PackMath.keepsOwnPhoto(at(angle(0.3)), front, rule))
        // No front embedding yet (its photo is still being downloaded): kept.
        assertTrue(PackMath.keepsOwnPhoto(at(angle(0.3)), null, rule))
    }
}
