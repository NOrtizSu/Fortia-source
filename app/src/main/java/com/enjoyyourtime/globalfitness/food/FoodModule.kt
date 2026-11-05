package com.enjoyyourtime.globalfitness.food

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.os.SystemClock
import android.util.Log
import com.enjoyyourtime.globalfitness.BuildConfig
import com.enjoyyourtime.globalfitness.food.catalog.FoodCatalog
import com.enjoyyourtime.globalfitness.food.data.FoodDatabase
import com.enjoyyourtime.globalfitness.food.data.FoodRepository
import com.enjoyyourtime.globalfitness.food.data.FoodSettings
import com.enjoyyourtime.globalfitness.food.data.MealPhotos
import com.enjoyyourtime.globalfitness.food.data.ProductPhotos
import com.enjoyyourtime.globalfitness.food.model.CustomFood
import com.enjoyyourtime.globalfitness.food.model.CustomFoodSource
import com.enjoyyourtime.globalfitness.food.off.OffCache
import com.enjoyyourtime.globalfitness.food.off.OffResult
import com.enjoyyourtime.globalfitness.food.off.OpenFoodFacts
import com.enjoyyourtime.globalfitness.food.off.PhotoResult
import com.enjoyyourtime.globalfitness.food.pack.PackSearch
import com.enjoyyourtime.globalfitness.food.pack.ProductEmbeddings
import com.enjoyyourtime.globalfitness.food.pack.ProductPacks
import com.enjoyyourtime.globalfitness.food.pack.TextReader
import com.enjoyyourtime.globalfitness.food.recognition.FoodRecognizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The food log ("Comida"): entries and custom foods (food.db), the offline catalog, meal photos, the
 * on-device recognizer, Open Food Facts for barcodes, packaged products recognised from a photo of the
 * package ([packs], [textReader], [packSearch]) and the goal settings. Nothing heavy happens at app
 * start: the catalog and the models load on first use.
 */
class FoodModule(context: Context, private val scope: CoroutineScope) {
    private val appContext = context.applicationContext
    val settings = FoodSettings(context, scope)
    val database = FoodDatabase(context)
    val repository = FoodRepository(database, scope)
    val photos = MealPhotos(context)
    val catalog = FoodCatalog(context)
    val recognizer = FoodRecognizer(context, scope)
    val openFoodFacts = OpenFoodFacts(userAgent = "Fortia/${BuildConfig.VERSION_NAME} (enjoyyourtime.online@gmail.com)")
    val productPhotos = ProductPhotos { File(appContext.filesDir, ProductPhotos.DIR) }

    /** "Products you already scanned": embeddings kept in noBackupFilesDir (never backed up). */
    val packs = ProductPacks(
        recognizer,
        ProductEmbeddings { File(appContext.noBackupFilesDir, EMBEDDINGS_FILE) },
        repository,
        productPhotos,
        photos,
        scope,
    )

    /** The words of a package photo, read on the phone. */
    val textReader = TextReader(appContext, scope)

    /** Open Food Facts searched by those words, within its limits. */
    val packSearch = PackSearch(
        live = { words, language -> openFoodFacts.searchLive(words, language) },
        index = { words, language -> openFoodFacts.searchIndex(words, language) },
        clock = SystemClock::elapsedRealtime,
    )

    /** Photos being downloaded, as "product id + address" (one download of each at a time). */
    private val fetchingPhotos: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Products opened to be logged whose photo needs nothing more while the app runs ([PhotoOutcome.done]). */
    private val photosLookedAfter: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Products opened to be logged whose photo is being looked after now (one attempt at a time). */
    private val lookingAfterPhotos: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Products whose photo address is being asked for (one request at a time per product). */
    private val checkingPhotos: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** What looking for a product's photo came to (logged at debug level, without addresses). */
    private enum class PhotoOutcome(val label: String, val done: Boolean) {
        STORED("stored", true),
        PRESENT("already on the phone", true),
        NONE("none on Open Food Facts", true),
        GONE("gone from Open Food Facts", true),
        MISSING("product deleted", true),
        UNKNOWN("address not known yet", false),
        FAILED("failed", false),
        BUSY("skipped, in flight", false),
    }

    private fun logged(productId: String, outcome: PhotoOutcome, reason: String? = null): PhotoOutcome {
        Log.d(TAG, "Product photo $productId: ${outcome.label}" + (reason?.let { " ($it)" } ?: ""))
        return outcome
    }

    /**
     * Downloads the photo of the product [productId] in the background when it has one to get
     * ([OffCache.imageToFetch], read when the download starts), whatever the screens do meanwhile;
     * nothing is shown when it fails (offline, too large, not an image): the product's next lookup
     * tries again. A photo Open Food Facts no longer has (404, 410) leaves the product without one.
     */
    fun fetchProductPhoto(productId: String) {
        scope.launch(Dispatchers.IO) { downloadPhoto(productId) }
    }

    /** [fetchProductPhoto]'s work, on the calling (IO) thread; what it came to. */
    private suspend fun downloadPhoto(productId: String): PhotoOutcome {
        val product = repository.customFood(productId) ?: return logged(productId, PhotoOutcome.MISSING)
        val url = OffCache.imageToFetch(product, productPhotos::exists) ?: return logged(
            productId,
            when {
                product.source != CustomFoodSource.OFF -> PhotoOutcome.NONE
                product.image != null && productPhotos.exists(product.image) -> PhotoOutcome.PRESENT
                product.imageUrl == null -> PhotoOutcome.UNKNOWN
                else -> PhotoOutcome.NONE
            },
        )
        // Another address (changed meanwhile) is downloaded at the same time; the older one is then dropped.
        val key = "$productId $url"
        if (!fetchingPhotos.add(key)) return logged(productId, PhotoOutcome.BUSY)
        try {
            return when (val r = openFoodFacts.image(url)) {
                is PhotoResult.Photo -> {
                    val name = productPhotos.store(r.bytes) ?: return logged(productId, PhotoOutcome.FAILED, "not stored")
                    // Kept only if the product still exists and still has that address.
                    val stored = repository.updateCustomFood(productId) { if (it.imageUrl == url) it.copy(image = name) else it }
                    if (stored?.image == name) {
                        // Embedded for "products you already scanned" (in the background, when the model has an embedding).
                        packs.sync()
                        logged(productId, PhotoOutcome.STORED)
                    } else {
                        productPhotos.delete(name)
                        logged(productId, PhotoOutcome.FAILED, "product changed meanwhile")
                    }
                }
                PhotoResult.Gone -> {
                    repository.updateCustomFood(productId) { if (it.imageUrl == url) it.copy(imageUrl = "", image = null) else it }
                    logged(productId, PhotoOutcome.GONE)
                }
                is PhotoResult.Failed -> logged(productId, PhotoOutcome.FAILED, r.reason)
            }
        } catch (e: CancellationException) {
            logged(productId, PhotoOutcome.FAILED, "cancelled")
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not keep a product photo", e)
            return logged(productId, PhotoOutcome.FAILED, e.javaClass.simpleName)
        } finally {
            fetchingPhotos.remove(key)
        }
    }

    /**
     * A product kept before photos were asked for ([OffCache.needsImageCheck]): Open Food Facts is
     * asked for its photo's address only (its values stay as kept until it is due for a refresh); no
     * longer there, it has none. Null when offline or failed (asked again later), or when it is being
     * asked already (a scan, then "Use" at once: the first one downloads the photo); throws when
     * food.db cannot be written.
     */
    suspend fun checkProductPhoto(product: CustomFood, language: String): CustomFood? {
        val barcode = product.barcode ?: return null
        if (!checkingPhotos.add(product.id)) {
            Log.d(TAG, "Product photo ${product.id}: address request skipped, in flight")
            return null
        }
        try {
            val url = when (val r = openFoodFacts.product(barcode, language)) {
                is OffResult.Found -> r.product.imageUrl
                OffResult.NotFound -> null
                else -> {
                    Log.d(TAG, "Product photo ${product.id}: address request failed ($r)")
                    return null
                }
            }
            Log.d(TAG, "Product photo ${product.id}: address " + if (url != null) "known" else "none")
            return repository.updateCustomFood(product.id) { OffCache.withImage(it, url) }
        } finally {
            checkingPhotos.remove(product.id)
        }
    }

    /**
     * A product of Open Food Facts opened to be logged (the search, "as last time", an entry): its photo
     * is looked after in the background ([checkProductPhoto] if needed, then the download). One attempt
     * at a time; once it is stored, or Open Food Facts says there is none, nothing more is asked while
     * the app runs. A failed or cut attempt is tried again the next time the product is opened (never
     * at once). A scan does it every time instead.
     */
    fun lookAfterProductPhoto(product: CustomFood, language: String) {
        if (product.source != CustomFoodSource.OFF || product.barcode == null || product.id in photosLookedAfter) return
        if (!lookingAfterPhotos.add(product.id)) {
            logged(product.id, PhotoOutcome.BUSY)
            return
        }
        scope.launch(Dispatchers.IO) {
            try {
                if (OffCache.needsImageCheck(product)) checkProductPhoto(product, language)
                if (downloadPhoto(product.id).done) photosLookedAfter += product.id
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Could not ask for a product photo", e)
            } finally {
                lookingAfterPhotos.remove(product.id)
            }
        }
    }

    /**
     * Identifies this install for the Health Connect ledger (see ExportLedgerEntry.device): kept in
     * noBackupFilesDir, so a phone restored from a backup gets a new one. Blocking (first call reads a file).
     */
    val installId: String by lazy {
        val file = File(appContext.noBackupFilesDir, INSTALL_FILE)
        file.takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() }
            ?: UUID.randomUUID().toString().also { id -> runCatching { file.writeText(id) } }
    }

    /** Deletes a meal photo off the main thread, unless an entry or a screen still needs it ([FoodRepository.photoInUse]). */
    fun deletePhotoIfUnused(name: String) {
        scope.launch(Dispatchers.IO) { if (!repository.photoInUse(name)) photos.delete(name) }
    }

    init {
        scope.launch {
            repository.loaded.first { it }
            // Photos of entries deleted while the app was closed (or of a photo never saved).
            val used = repository.entries.value.mapNotNullTo(HashSet()) { it.photo }
            // Product photos no product uses (an older version rewrote its row, a download cut short).
            val usedProducts = repository.customFoods.value.mapNotNullTo(HashSet()) { it.image }
            withContext(Dispatchers.IO) {
                if (repository.entries.value.isNotEmpty()) settings.markUsed()
                photos.deleteUnused(used, PHOTO_GRACE_MS)
                productPhotos.deleteUnused(usedProducts, PHOTO_GRACE_MS)
            }
        }
        // A product deleted (here or on another phone) or given a new photo: its old photo file goes.
        scope.launch(Dispatchers.IO) { productPhotos.deleteDropped(repository.customFoods) }
        // Their embeddings too (once the matching was used: the file is not read at start).
        scope.launch { packs.follow() }
        // The model takes several MB of native memory: given back when Android runs short.
        appContext.registerComponentCallbacks(
            object : ComponentCallbacks2 {
                override fun onTrimMemory(level: Int) {
                    if (FoodRecognizer.releaseOnTrim(level)) recognizer.release()
                }

                override fun onConfigurationChanged(newConfig: Configuration) = Unit

                @Deprecated("Deprecated in Java")
                override fun onLowMemory() = recognizer.release()
            },
        )
    }

    private companion object {
        const val TAG = "FoodModule"
        const val INSTALL_FILE = "food_hc_install"
        const val EMBEDDINGS_FILE = "product_embeddings.bin"

        /** A photo younger than this may belong to an entry being logged right now. */
        const val PHOTO_GRACE_MS = 24L * 60 * 60_000
    }
}
