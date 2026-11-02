package com.enjoyyourtime.globalfitness.food

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import com.enjoyyourtime.globalfitness.BuildConfig
import com.enjoyyourtime.globalfitness.food.catalog.FoodCatalog
import com.enjoyyourtime.globalfitness.food.data.FoodDatabase
import com.enjoyyourtime.globalfitness.food.data.FoodRepository
import com.enjoyyourtime.globalfitness.food.data.FoodSettings
import com.enjoyyourtime.globalfitness.food.data.MealPhotos
import com.enjoyyourtime.globalfitness.food.off.OpenFoodFacts
import com.enjoyyourtime.globalfitness.food.recognition.FoodRecognizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * The food log ("Comida"): entries and custom foods (food.db), the offline catalog, meal photos, the
 * on-device recognizer, Open Food Facts for barcodes and the goal settings. Nothing heavy happens at
 * app start: the catalog and the model load on first use.
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
            withContext(Dispatchers.IO) {
                if (repository.entries.value.isNotEmpty()) settings.markUsed()
                photos.deleteUnused(used, PHOTO_GRACE_MS)
            }
        }
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
        const val INSTALL_FILE = "food_hc_install"

        /** A photo younger than this may belong to an entry being logged right now. */
        const val PHOTO_GRACE_MS = 24L * 60 * 60_000
    }
}
