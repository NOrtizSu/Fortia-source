package com.enjoyyourtime.globalfitness.food.pack

import android.graphics.Bitmap
import android.util.Log
import com.enjoyyourtime.globalfitness.food.data.FoodRepository
import com.enjoyyourtime.globalfitness.food.data.MealPhotos
import com.enjoyyourtime.globalfitness.food.data.ProductPhotos
import com.enjoyyourtime.globalfitness.food.recognition.FoodRecognizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** A photo's embeddings (the whole photo first, then its items) and what they look like among the scanned products. */
class PackLook(val queries: List<FloatArray>, val find: PackFind?)

/**
 * "Products you already scanned": a meal photo compared on the phone, offline, with the photos of the
 * products the user found by barcode (their Open Food Facts front photo, and their own photos logged as
 * them), by the classifier's image embedding (embedding_contract.md). Off when the app's model has no
 * embedding. Never logs anything: the screens offer what it finds.
 */
class ProductPacks(
    private val recognizer: FoodRecognizer,
    private val store: ProductEmbeddings,
    private val repository: FoodRepository,
    private val productPhotos: ProductPhotos,
    private val mealPhotos: MealPhotos,
    private val scope: CoroutineScope,
) {
    private val syncMutex = Mutex()

    /** Meal photos being recognized right now: the background embedding waits for them (it shares the model). */
    private val holds = MutableStateFlow(0)

    /** A meal photo is being recognized until the result is closed: [sync] pauses meanwhile. */
    fun hold(): AutoCloseable {
        holds.update { it + 1 }
        var closed = false
        return AutoCloseable {
            if (!closed) {
                closed = true
                holds.update { it - 1 }
            }
        }
    }

    /** Every product (id → its photo file on this phone, null: none). Blocking (files). */
    private fun images(): Map<String, String?> = repository.customFoods.value.associate { it.id to it.image?.takeIf(productPhotos::exists) }

    /**
     * Embeds in the background the product photos not embedded yet (a new photo, a phone restored from a
     * backup, a new model) and, after a new model, the user's own photos again; it waits while a meal
     * photo is being recognized and writes the file in batches. A picture whose run fails is skipped (the
     * next sync tries it again; the others go on); without a usable model it stops.
     */
    fun sync() {
        scope.launch(Dispatchers.IO) {
            // The model's json is read off the main thread (once); no embedding: nothing to do.
            val space = recognizer.embeddingSpec?.space ?: return@launch
            try {
                repository.loaded.first { it }
                syncMutex.withLock {
                    var done = 0
                    var skipped = 0
                    try {
                        val images = images()
                        store.retain(images)
                        for ((id, image) in store.frontsToEmbed(space, images)) {
                            val picture = productPhotos.loadBitmap(image, SOURCE_PX) ?: continue
                            val vector = when (val e = embedWhenFree(picture)) {
                                is Embedded.Vector -> e.values
                                Embedded.Zero -> null
                                Embedded.Failed -> {
                                    skipped++
                                    continue
                                }
                                Embedded.Unavailable -> return@withLock
                            }
                            store.setFront(space, id, image, vector, persist = false)
                            if (++done % BATCH == 0) store.flush()
                        }
                        for ((id, photo) in store.confirmedToEmbed(space)) {
                            val picture = runCatching { mealPhotos.loadBitmap(photo, SOURCE_PX) }.getOrNull()
                            val vector = when (val e = picture?.let { embedWhenFree(it) }) {
                                is Embedded.Vector -> e.values
                                Embedded.Failed -> {
                                    skipped++
                                    continue
                                }
                                Embedded.Unavailable -> return@withLock
                                // The meal photo is gone, or it has no usable embedding: dropped.
                                Embedded.Zero, null -> null
                            }
                            store.setConfirmed(space, id, photo, vector, persist = false)
                            if (++done % BATCH == 0) store.flush()
                        }
                    } finally {
                        store.flush()
                        if (done > 0 || skipped > 0) Log.i(TAG, "Embedded $done product photos ($skipped failed, tried again next time)")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Could not embed the product photos", e)
            }
        }
    }

    /** [picture]'s embedding, once no meal photo is being recognized. */
    private suspend fun embedWhenFree(picture: Bitmap): Embedded {
        holds.first { it == 0 }
        return recognizer.embed(picture)
    }

    /**
     * What [pictures] (the whole photo, then the items of a plate) look like among the scanned products;
     * null when the feature is off or no product was embedded yet (the model is not even run).
     */
    suspend fun look(pictures: List<Bitmap>): PackLook? {
        val rule = withContext(Dispatchers.IO) { recognizer.embeddingSpec } ?: return null
        val known = repository.customFoods.value.mapTo(HashSet()) { it.id }
        val refs = withContext(Dispatchers.IO) {
            // First use since the app started: products deleted meanwhile go first.
            if (!store.loaded) store.retain(images())
            store.refs(rule.space)
        }.filterKeys { it in known }
        if (refs.isEmpty()) return null
        val queries = ArrayList<FloatArray>()
        for (p in pictures) {
            when (val e = recognizer.embed(p)) {
                is Embedded.Vector -> queries += e.values
                // The whole photo has none: its items alone are not enough.
                else -> if (queries.isEmpty()) return null
            }
        }
        val find = withContext(Dispatchers.Default) { PackMath.look(queries, refs, rule) }
        return PackLook(queries, find)
    }

    /**
     * The user logged [productId] from a photo of its package: that photo's embedding (the one of
     * [queries] closest to the product, else [picture]'s) is kept as one more reference of the product,
     * with the name of its meal [photo] (to embed it again for a new model), when it looks like the
     * product's front photo (not the back of the package, photographed for its barcode). Never the photo.
     */
    fun remember(productId: String, photo: String?, queries: List<FloatArray>?, picture: Bitmap?) {
        scope.launch(Dispatchers.IO) {
            val rule = recognizer.embeddingSpec ?: return@launch
            try {
                val refs = store.refs(rule.space)[productId]
                val all = refs?.all.orEmpty()
                val vector = queries?.maxByOrNull { q -> all.maxOfOrNull { PackMath.cosine(q, it) } ?: 0f }
                    ?: (picture?.let { recognizer.embed(it) } as? Embedded.Vector)?.values
                    ?: return@launch
                if (PackMath.keepsOwnPhoto(vector, refs?.front, rule)) store.addConfirmed(rule.space, productId, photo, vector)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Could not keep the photo's embedding", e)
            }
        }
    }

    /**
     * Follows the products until cancelled: a product deleted (here or by the cloud backup) loses its
     * embeddings, a new photo is embedded again. The file is not read for this: until the matching is
     * first used, [look] and [sync] do it.
     */
    suspend fun follow() {
        repository.loaded.first { it }
        repository.customFoods.collect { foods ->
            if (!store.loaded) return@collect
            withContext(Dispatchers.IO) {
                val images = foods.associate { it.id to it.image?.takeIf(productPhotos::exists) }
                syncMutex.withLock { store.retain(images, load = false) }
            }
        }
    }

    private companion object {
        const val TAG = "ProductPacks"

        /** The photos are decoded this large for the embedding (the model stretches them to its input, 320 px now). */
        const val SOURCE_PX = 640

        /** The file is written every this many new embeddings while many are computed (a new model), and at the end. */
        const val BATCH = 50
    }
}
