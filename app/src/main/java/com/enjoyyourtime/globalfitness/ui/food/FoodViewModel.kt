package com.enjoyyourtime.globalfitness.ui.food

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.os.bundleOf
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.enjoyyourtime.globalfitness.AppContainer
import com.enjoyyourtime.globalfitness.GlobalFitnessApp
import com.enjoyyourtime.globalfitness.data.Measurement
import com.enjoyyourtime.globalfitness.data.Profile
import com.enjoyyourtime.globalfitness.domain.BodyMetrics
import com.enjoyyourtime.globalfitness.food.barcode.BarcodeReader
import com.enjoyyourtime.globalfitness.food.catalog.FoodSearch
import com.enjoyyourtime.globalfitness.food.data.GoalSettings
import com.enjoyyourtime.globalfitness.food.data.MealPhotos
import com.enjoyyourtime.globalfitness.food.domain.AdjustedDish
import com.enjoyyourtime.globalfitness.food.domain.DayLog
import com.enjoyyourtime.globalfitness.food.domain.DraftAmount
import com.enjoyyourtime.globalfitness.food.domain.DraftOption
import com.enjoyyourtime.globalfitness.food.domain.FoodDrafts
import com.enjoyyourtime.globalfitness.food.domain.FoodMath
import com.enjoyyourtime.globalfitness.food.domain.FoodRecall
import com.enjoyyourtime.globalfitness.food.domain.IngredientChoice
import com.enjoyyourtime.globalfitness.food.domain.IngredientInfo
import com.enjoyyourtime.globalfitness.food.domain.Recall
import com.enjoyyourtime.globalfitness.food.domain.RecipeMath
import com.enjoyyourtime.globalfitness.food.domain.GoalAim
import com.enjoyyourtime.globalfitness.food.domain.GoalBreakdown
import com.enjoyyourtime.globalfitness.food.domain.GoalInputs
import com.enjoyyourtime.globalfitness.food.domain.GoalMode
import com.enjoyyourtime.globalfitness.food.domain.NutritionGoals
import com.enjoyyourtime.globalfitness.food.domain.NutritionTargets
import com.enjoyyourtime.globalfitness.food.model.CatalogFood
import com.enjoyyourtime.globalfitness.food.model.CustomFood
import com.enjoyyourtime.globalfitness.food.model.CustomFoodSource
import com.enjoyyourtime.globalfitness.food.model.EntrySource
import com.enjoyyourtime.globalfitness.food.model.FoodEntry
import com.enjoyyourtime.globalfitness.food.model.FoodItem
import com.enjoyyourtime.globalfitness.food.model.FoodRef
import com.enjoyyourtime.globalfitness.food.model.FoodUnit
import com.enjoyyourtime.globalfitness.food.model.IngredientAdjustment
import com.enjoyyourtime.globalfitness.food.model.Meal
import com.enjoyyourtime.globalfitness.food.model.Nutrient
import com.enjoyyourtime.globalfitness.food.model.Nutrients
import com.enjoyyourtime.globalfitness.food.model.Portion
import com.enjoyyourtime.globalfitness.food.model.PortionKey
import com.enjoyyourtime.globalfitness.food.model.RecipeIngredient
import com.enjoyyourtime.globalfitness.food.off.OffCache
import com.enjoyyourtime.globalfitness.food.off.OffHit
import com.enjoyyourtime.globalfitness.food.off.OffResult
import com.enjoyyourtime.globalfitness.food.off.PhotoResult
import com.enjoyyourtime.globalfitness.food.off.SearchResult
import com.enjoyyourtime.globalfitness.food.pack.PackFind
import com.enjoyyourtime.globalfitness.food.pack.PackSearch
import com.enjoyyourtime.globalfitness.food.pack.PackageText
import com.enjoyyourtime.globalfitness.food.pack.TextReading
import com.enjoyyourtime.globalfitness.food.pack.TextScript
import com.enjoyyourtime.globalfitness.food.catalog.FoodCatalog
import com.enjoyyourtime.globalfitness.food.recognition.Detection
import com.enjoyyourtime.globalfitness.food.recognition.DetectionMath
import com.enjoyyourtime.globalfitness.food.recognition.GramsEstimator
import com.enjoyyourtime.globalfitness.food.recognition.Recognition
import com.enjoyyourtime.globalfitness.training.data.ExercisePhotos
import com.enjoyyourtime.globalfitness.health.HcAvailability
import com.enjoyyourtime.globalfitness.health.HealthDataType
import com.enjoyyourtime.globalfitness.health.HealthPermissions
import com.enjoyyourtime.globalfitness.ui.Fmt
import com.google.zxing.BarcodeFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** The day's goal: the targets, and how the automatic one was computed (null when it cannot be yet). */
data class GoalUi(
    val settings: GoalSettings = GoalSettings(),
    val targets: NutritionTargets? = null,
    val breakdown: GoalBreakdown? = null,
    /** Automatic mode without any weight: the goal sheet asks for it. */
    val needsWeight: Boolean = false,
    /** The weight used (latest weighing, else the one typed in the sheet). */
    val weightKg: Double? = null,
    /** The weight comes from a weighing (not typed in the sheet). */
    val weightMeasured: Boolean = false,
)

data class FoodDayUi(
    val loaded: Boolean = false,
    val date: LocalDate = LocalDate.now(),
    val log: DayLog = DayLog(LocalDate.now(), emptyMap(), emptyMap(), Nutrients.NONE),
    val goal: GoalUi = GoalUi(),
    /** food.db could not be opened (shown on the tab; every save then fails with a message). */
    val error: String? = null,
)

/** Hoy's food card: shown once food was logged or a goal set. */
data class FoodTodayUi(val kcal: Double, val goalKcal: Double?)

enum class PhotoStatus { RECOGNIZING, DONE, NO_MODEL, UNREADABLE }

/**
 * A meal photo being logged: its stored file, what the recognizer saw, how many foods were added from
 * it. With the item detector, a plate of several foods has its [items] (two or more): the page lists
 * them unless the user asked to see the photo as one dish ([asOne]); [recognition] is then that of the
 * whole photo, read when first needed.
 */
data class PhotoSession(
    val photo: String?,
    val preview: ImageBitmap?,
    val status: PhotoStatus,
    val recognition: Recognition? = null,
    val added: List<String> = emptyList(),
    val meal: Meal,
    val eatenAt: Long,
    val items: List<PlateItem> = emptyList(),
    val asOne: Boolean = false,
    /** "Add all" is saving the items. */
    val addingItems: Boolean = false,
    /** The packaged product the photo may show (its barcode, the user's scanned products, the words on it). */
    val pack: PackUi = PackUi(),
) {
    /** The page shows the plate's items (not the whole photo's suggestions). */
    val showsItems: Boolean get() = items.size >= 2 && !asOne
}

/** How a packaged product was found from the meal photo: its entry keeps the photo, and the photo's embedding is kept for that product. */
enum class PackWay { BARCODE, MATCH, TEXT }

/** A barcode read on the meal photo and what looking it up gave ([BarcodeState.LookingUp] meanwhile). */
data class PhotoBarcode(val code: String, val state: BarcodeState)

/** One of the user's scanned products the photo looks like. */
data class PackProduct(val customFoodId: String, val name: String, val brand: String?)

/** "Search the product by its package": the words read on the photo, searched in Open Food Facts. */
sealed interface PackText {
    data object Idle : PackText

    data object Reading : PackText

    data class Searching(val query: String) : PackText

    /** Up to [com.enjoyyourtime.globalfitness.food.pack.PackSearch.SHOWN] products, those sold in the user's country first. */
    data class Results(val query: String, val hits: List<OffHit>) : PackText

    /** No words that could name a product. */
    data object NoText : PackText

    /** The text recognition failed on this photo (not the photo's fault: worth another try). */
    data object ReadFailed : PackText

    data class NotFound(val query: String) : PackText

    data class Offline(val query: String) : PackText

    /** Too many searches in a short time (Open Food Facts' limit). */
    data class Busy(val query: String) : PackText

    data class Failed(val query: String) : PackText

    /** Text recognition is being downloaded by Google Play services ([preparing]), or this phone has no Play services. */
    data class Unavailable(val preparing: Boolean) : PackText
}

data class PackUi(
    val barcode: PhotoBarcode? = null,
    /** "Is it …?": one of the scanned products clearly ahead. */
    val match: PackProduct? = null,
    /** Otherwise the scanned products it may be (none picked). */
    val suggestions: List<PackProduct> = emptyList(),
    val text: PackText = PackText.Idle,
)

/**
 * One food item the detector found on the plate: its square crop (the entry's photo once added), what
 * the recognizer saw in it, the food chosen (the best suggestion to start with), its variants, the
 * amount (the photo's grams suggestion, else the food's portion) and whether "Add all" adds it.
 */
data class PlateItem(
    val id: Int,
    val share: Double,
    val crop: Bitmap,
    val preview: ImageBitmap,
    val recognition: Recognition?,
    val food: FoodItem?,
    /** The variants of [food] (its base first), empty when it has none. */
    val variants: List<FoodItem> = emptyList(),
    val amountText: String,
    /** The user typed the amount: another food chosen keeps it. */
    val amountTyped: Boolean = false,
    /** The user chose the photo's suggestion ("Use") over last time's amount: another food chosen gets its own suggestion. */
    val amountFromEstimate: Boolean = false,
    /** The grams suggestion for [food] (null: none, or the amount was typed). */
    val estimate: Double? = null,
    val included: Boolean,
    /** Logged by "Add all" already. */
    val added: Boolean = false,
    /** What [food] takes from its last entry: the amount (unless typed), ingredient changes and values typed by hand. */
    val recall: Recall? = null,
) {
    val amount: Double? get() = Fmt.parseDecimal(amountText)?.takeIf { it > 0 && it < 10_000 }
}

/** What the confirm screen starts from: a food, how much, which meal and when. */
data class FoodDraft(
    /** The entry being edited, null for a new one. */
    val editingId: String?,
    val item: FoodItem,
    val source: EntrySource,
    /** The amount of the dish itself (its ingredients' changes come on top, see [adjustments]). */
    val amount: Double,
    val portion: Portion?,
    val portionCount: Double?,
    val meal: Meal,
    val eatenAt: Long,
    val photo: String? = null,
    val modelLabel: String? = null,
    val modelConfidence: Double? = null,
    /** Bumped for every new draft, so the screen resets its fields. */
    val serial: Long = 0,
    /** The ingredients of [item] that can be changed (empty: none, or not a dish of the catalog). */
    val recipe: List<RecipeIngredient> = emptyList(),
    /** The variants offered (the base first, the "typical" one; [item] among them); empty when it has none. */
    val options: List<DraftOption> = emptyList(),
    /** Every ingredient of [recipe] and of the [options]' recipes. */
    val ingredients: Map<String, IngredientInfo> = emptyMap(),
    /** The ingredients the edited entry had changed. */
    val adjustments: List<IngredientAdjustment> = emptyList(),
    /** The photo's grams suggestion ([amount] starts with it). */
    val estimatedAmount: Double? = null,
    /** The photo's estimator: another variant chosen gets its own suggestion (its portion differs). */
    val grams: GramsEstimator? = null,
    /** What a new entry of [item] took from its last one ("as last time"); [amount], [portion] and the ingredients start with it. */
    val recall: Recall? = null,
    /** The food's own values (what "Restore" brings back): those of an edited entry's food today, null when it is no longer known. */
    val own: Nutrients? = item.per100,
    /** The edited entry had values typed by hand. */
    val valuesEdited: Boolean = false,
    /** A product found from the photo of its package: once logged, the photo's embedding is kept for it. */
    val fromPackage: Boolean = false,
) {
    /** [item] and its recipe as an option (it may have no variants). */
    val current: DraftOption get() = DraftOption(item, recipe, recall, own)

    /** The ingredient choices the screen starts with: the edited entry's changes, else those recalled. */
    val startChoices: Map<String, IngredientChoice>
        get() = if (adjustments.isNotEmpty()) adjustments.associate { it.ingredient to IngredientChoice.of(it) } else recall?.choices.orEmpty()
}

/**
 * What the confirm screen saves: the food (the draft's, or another of its variants, with the values
 * per 100 g it is logged with: its own or those typed by hand), the amount of the dish, its changed
 * ingredients, the portion, the meal and the time.
 */
data class DraftChoice(
    val item: FoodItem,
    val dishAmount: Double,
    val adjustments: List<IngredientAdjustment>,
    val portionKey: PortionKey?,
    val portionCount: Double?,
    val meal: Meal,
    val eatenAt: Long,
    /** The photo's grams suggestion for [item], kept with the entry for quality stats. */
    val estimatedAmount: Double? = null,
    /** Some values were typed by hand (see [FoodEntry.valuesEdited]). */
    val valuesEdited: Boolean = false,
)

enum class SaveStatus { SAVING, SAVED, FAILED }

/** The confirm screen's save of draft [serial]: the page closes once saved and stays open when it failed. */
data class DraftSave(val serial: Long, val status: SaveStatus, val fromPhoto: Boolean)

/**
 * The manual entry form's starting point (a barcode not found, a product with missing values, "Create
 * food", "Edit food"). Kept in the saved state: it survives the process like the fields typed in it.
 */
data class ManualDraft(
    val name: String = "",
    val brand: String? = null,
    val barcode: String? = null,
    val unit: FoodUnit = FoodUnit.G,
    val amount: Double? = null,
    /** Values for [amount]. */
    val values: Nutrients = Nutrients.NONE,
    /** The custom food these values update (a cached product being completed, or one created by this form). */
    val customFoodId: String? = null,
    val saveAsFood: Boolean = false,
    /** Completing the missing values of a product found by barcode (logged with its serving afterwards). */
    val completing: Boolean = false,
    /** "Edit food" from the search: the food is saved, nothing is logged. */
    val editOnly: Boolean = false,
    val serial: Long = 0,
) {
    fun toBundle(): Bundle = bundleOf(
        "name" to name,
        "brand" to brand,
        "barcode" to barcode,
        "unit" to unit.code,
        "amount" to (amount ?: Double.NaN),
        "values" to DoubleArray(Nutrient.entries.size) { values[Nutrient.entries[it]] ?: Double.NaN },
        "customFoodId" to customFoodId,
        "saveAsFood" to saveAsFood,
        "completing" to completing,
        "editOnly" to editOnly,
        "serial" to serial,
    )

    companion object {
        fun fromBundle(b: Bundle): ManualDraft {
            var values = Nutrients.NONE
            b.getDoubleArray("values")?.forEachIndexed { i, v -> if (!v.isNaN()) Nutrient.entries.getOrNull(i)?.let { values = values.with(it, v) } }
            return ManualDraft(
                name = b.getString("name").orEmpty(),
                brand = b.getString("brand"),
                barcode = b.getString("barcode"),
                unit = FoodUnit.fromCode(b.getString("unit")),
                amount = b.getDouble("amount", Double.NaN).takeUnless { it.isNaN() },
                values = values,
                customFoodId = b.getString("customFoodId"),
                saveAsFood = b.getBoolean("saveAsFood"),
                completing = b.getBoolean("completing"),
                editOnly = b.getBoolean("editOnly"),
                serial = b.getLong("serial"),
            )
        }
    }
}

sealed interface BarcodeState {
    data object Reading : BarcodeState

    /** No barcode found in the photo: the user may type it. */
    data object NotRead : BarcodeState

    data class LookingUp(val code: String) : BarcodeState

    /** [last]: the product's last entry, if it was logged before. */
    data class Found(val code: String, val item: FoodItem, val missing: List<Nutrient>, val fromCache: Boolean, val last: FoodEntry? = null) : BarcodeState

    data class NotFound(val code: String) : BarcodeState

    data class Offline(val code: String) : BarcodeState

    data class Failed(val code: String) : BarcodeState
}

/**
 * The Comida tab and its pages (activity-scoped: the photo, the draft and the search survive moving
 * between the pages of one flow). Everything slow (decoding, the model, the catalog, the network) runs
 * off the main thread. A write food.db refuses never crashes: [writeFailed] tells the user.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FoodViewModel(private val container: AppContainer, private val state: SavedStateHandle) : ViewModel() {
    private val food = container.food
    private val repository = food.repository
    private val settings = food.settings
    private val photos = food.photos

    // ---- errors -------------------------------------------------------------------------------

    private val _writeFailed = Channel<Unit>(Channel.CONFLATED)

    /** A change could not be saved (food.db refused it or could not be opened): the app's snackbar says so. */
    val writeFailed: Flow<Unit> = _writeFailed.receiveAsFlow()

    private fun failed(e: Exception) {
        Log.w(TAG, "Could not save to the food log", e)
        _writeFailed.trySend(Unit)
    }

    /** Runs a write of the food log; on failure the user is told and null is returned. */
    private suspend fun <T> writing(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        failed(e)
        null
    }

    // ---- catalog ----------------------------------------------------------------------------

    private val catalogFoods = MutableStateFlow<Map<String, CatalogFood>>(emptyMap())
    private val catalogItems = MutableStateFlow<List<FoodItem>>(emptyList())

    /** Catalog foods (current language) and the user's own foods. */
    val items: StateFlow<List<FoodItem>> = combine(catalogItems, repository.customFoods) { catalog, custom ->
        custom.map(FoodItem::of) + catalog
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * The photo file of every product that has one on this phone (from Open Food Facts), by custom food
     * id (a database restored without the photos names files that are not there yet).
     */
    val productPhotos: StateFlow<Map<String, String>> = repository.customFoods
        .map { foods -> foods.mapNotNull { f -> f.image?.takeIf(food.productPhotos::exists)?.let { f.id to it } }.toMap() }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    private var catalogJob: Job? = null

    /**
     * Reads the catalog (names in the current language) when a food page shows, not at app start
     * (Hoy's card creates this view model); again after a change of language (cheap otherwise).
     */
    fun ensureCatalog() {
        if (catalogJob?.isActive == true) return
        catalogJob = viewModelScope.launch {
            catalogFoods.value = food.catalog.foods()
            catalogRecipes.value = food.catalog.recipes()
            catalogItems.value = food.catalog.items()
        }
    }

    /** The ingredients of the catalog's dishes, by dish id. */
    private val catalogRecipes = MutableStateFlow<Map<String, List<RecipeIngredient>>>(emptyMap())

    private suspend fun awaitCatalog(): Map<String, CatalogFood> {
        val foods = catalogFoods.value.ifEmpty { food.catalog.foods().also { catalogFoods.value = it } }
        if (catalogRecipes.value.isEmpty()) catalogRecipes.value = food.catalog.recipes()
        return foods
    }

    /**
     * The Comida tab shows: the catalog (the model loads only when a meal photo is on its way), and the
     * scanned products' photos not embedded yet are embedded in the background (nothing when the model has
     * no embedding or all are done; it pauses while a meal photo is recognized).
     */
    fun onTabShown() {
        ensureCatalog()
        food.packs.sync()
    }

    /** A meal photo is about to be taken or picked: the model loads meanwhile (and is closed again when unused). */
    fun prepareRecognizer() {
        viewModelScope.launch { food.recognizer.warmUp() }
    }

    fun catalogName(id: String): String = food.catalog.name(id)

    /**
     * The name an entry shows: a catalog food's in the current language (the entry keeps the name it
     * was logged with, which Health Connect also got), the stored one for the user's own foods, products
     * and quick entries.
     */
    fun entryName(e: FoodEntry): String = e.foodId?.let(food.catalog::localName) ?: e.name

    // ---- the day ------------------------------------------------------------------------------

    private val _day = MutableStateFlow(LocalDate.now())
    val day: StateFlow<LocalDate> = _day.asStateFlow()

    /** Today's date: rolls over at midnight while the app runs ([followMidnight]) and on [onResume]. */
    private val _today = MutableStateFlow(LocalDate.now())

    /** The day shown is "today" (and moves to the next day after midnight) rather than a chosen date. */
    private var followToday = true

    fun showDay(date: LocalDate) {
        val today = LocalDate.now()
        _today.value = today
        val d = minOf(date, today)
        followToday = d == today
        _day.value = d
    }

    /** The app is in front again: a new day may have begun (clock or zone changed), or the language changed. */
    fun onResume() {
        rollDay()
        if (catalogItems.value.isNotEmpty()) ensureCatalog()
    }

    private fun rollDay() {
        val today = LocalDate.now()
        if (_today.value != today) {
            _today.value = today
            if (followToday) _day.value = today
        }
    }

    /** Wakes up just after each midnight (and every [MAX_DAY_CHECK_MS], in case the clock or the zone changed). */
    private suspend fun followMidnight() {
        while (true) {
            val now = LocalDateTime.now()
            val untilMidnight = Duration.between(now, now.toLocalDate().plusDays(1).atStartOfDay()).toMillis()
            delay((untilMidnight + 1_000).coerceIn(1_000, MAX_DAY_CHECK_MS))
            rollDay()
        }
    }

    fun previousDay() = showDay(_day.value.minusDays(1))

    fun nextDay() = showDay(_day.value.plusDays(1))

    /** Active calories of today and of the day shown (Health Connect cache), re-read when it changes. */
    private val activeKcal: StateFlow<Map<LocalDate, Double>> = combine(_day, container.health.cache.version, container.health.gateway.granted) { d, _, granted -> d to granted }
        .mapLatest { (d, granted) ->
            if (!HealthPermissions.canRead(granted, HealthDataType.ACTIVE_CALORIES)) return@mapLatest emptyMap()
            withContext(Dispatchers.IO) {
                val today = LocalDate.now()
                runCatching { container.health.cache.loadDaily(minOf(d, today), today) }.getOrDefault(emptyList())
                    .filter { it.date == d || it.date == today }
                    .mapNotNull { a -> a.activeKcal?.let { a.date to it } }
                    .toMap()
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    private val goalInputs = combine(container.repository.profile, container.repository.measurements, settings.goal, activeKcal) { p, m, g, a ->
        // The newest weight for the protein; the BMR of the newest weighing that measured it (a weight typed
        // by hand or from another scale has no impedance, and must not hide the scale's BMR).
        GoalSource(p, m.lastOrNull(), m.lastOrNull(::hasScaleBmr), g, a)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, GoalSource(null, null, null, GoalSettings(), emptyMap()))

    private data class GoalSource(
        val profile: Profile?,
        val latest: Measurement?,
        val bmrFrom: Measurement?,
        val settings: GoalSettings,
        val active: Map<LocalDate, Double>,
    )

    /** The goal of the day shown. */
    val goal: StateFlow<GoalUi> = combine(goalInputs, _day) { src, d -> goalFor(src, d) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, GoalUi())

    val dayUi: StateFlow<FoodDayUi> = combine(repository.loaded, repository.loadError, repository.entries, _day, goal) { loaded, error, entries, d, g ->
        FoodDayUi(loaded, d, FoodMath.dayLog(entries, d), g, error)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, FoodDayUi())

    /** Hoy's card, null while hidden (nothing logged, no goal set). */
    val today: StateFlow<FoodTodayUi?> = combine(repository.entries, goalInputs, settings.used, settings.goalSet, _today) { entries, src, used, goalSet, today ->
        if (!used && !goalSet && entries.isEmpty()) return@combine null
        val kcal = entries.filter { it.localDate == today }.sumOf { it.nutrients.kcal ?: 0.0 }
        FoodTodayUi(kcal, goalFor(src, today).targets?.kcal)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private fun goalFor(src: GoalSource, date: LocalDate): GoalUi {
        val g = src.settings
        val profile = src.profile
        val measured = src.latest?.weightKg
        val weight = measured ?: g.weightKg
        if (g.mode == GoalMode.MANUAL && g.manual != null) {
            return GoalUi(g, NutritionGoals.manual(g.manual, weight), null, needsWeight = false, weightKg = weight, weightMeasured = measured != null)
        }
        if (profile == null) return GoalUi(g, null, null, needsWeight = false)
        val age = profile.ageAt(System.currentTimeMillis())
        val scaleBmr = src.bmrFrom?.let { m -> BodyMetrics.forMeasurement(profile.sex, profile.heightCm, age, m).bmr }
        val inputs = GoalInputs(profile.sex, age, profile.heightCm, weight, scaleBmr, src.active[date], g.aim)
        val breakdown = NutritionGoals.auto(inputs)
        return GoalUi(
            settings = g,
            targets = breakdown?.let { NutritionGoals.targets(it.kcal, weight, g.aim) },
            breakdown = breakdown,
            needsWeight = breakdown == null,
            weightKg = weight,
            weightMeasured = measured != null,
        )
    }

    /** The scale measured the BMR of this weighing (impedance, or its own value). */
    private fun hasScaleBmr(m: Measurement): Boolean = m.impedance != null || m.composition?.bmrKcal != null

    fun saveGoal(goal: GoalSettings) = settings.setGoal(goal)

    /** Today's automatic goal with [aim] and, without a weighing, the [weightKg] being typed (the goal sheet's preview). */
    fun previewAuto(aim: GoalAim, weightKg: Double?): GoalUi {
        val src = goalInputs.value
        val settings = src.settings.copy(mode = GoalMode.AUTO, aim = aim, weightKg = weightKg ?: src.settings.weightKg)
        return goalFor(src.copy(settings = settings), LocalDate.now())
    }

    // ---- adding: photo ------------------------------------------------------------------------

    private val _photo = MutableStateFlow<PhotoSession?>(null)
    val photo: StateFlow<PhotoSession?> = _photo.asStateFlow()

    private var photoJob: Job? = null

    /** Where the camera writes (shared through the FileProvider). */
    val cameraTarget get() = photos.cameraTarget

    /** The photo being logged, at recognition size (for "see it as one dish" after the items were found). */
    private var photoBitmap: Bitmap? = null

    /**
     * A meal photo from the camera or the gallery: stored small, then recognized on the phone. With the
     * item detector and two or more foods found, each is recognized on its own crop; otherwise the
     * whole photo, as one food. Meanwhile a barcode on the photo is read (a packaged product: looked up
     * like a scanned barcode, its page opens); without one, the photo is compared with the products the
     * user already scanned, and a photo that looks like a package has its words searched in Open Food
     * Facts. None of them logs anything.
     */
    fun onMealPhoto(uri: Uri, fromCamera: Boolean) {
        closePhotoSession()
        val now = System.currentTimeMillis()
        val meal = defaultMeal(now)
        _photo.value = PhotoSession(null, null, PhotoStatus.RECOGNIZING, meal = meal, eatenAt = now)
        // Its page is coming in: not left yet (it shows once resumed).
        photoPageLeft = false
        photoPageShown = false
        photoJob = viewModelScope.launch {
            // The scanned products' background embedding waits while this photo uses the model.
            val hold = food.packs.hold()
            try {
                recognizePhoto(uri, fromCamera)
            } finally {
                hold.close()
            }
        }
    }

    private suspend fun recognizePhoto(uri: Uri, fromCamera: Boolean) = coroutineScope {
        val bitmap = withContext(Dispatchers.IO) { photos.decode(uri, RECOGNITION_SIDE_PX) }
        if (fromCamera) withContext(Dispatchers.IO) { runCatching { photos.cameraTarget.delete() } }
        if (bitmap == null) {
            _photo.update { it?.copy(status = PhotoStatus.UNREADABLE) }
            return@coroutineScope
        }
        photoBitmap = bitmap
        val name = withContext(Dispatchers.IO) { runCatching { photos.store(bitmap) }.getOrNull() }
        _photo.update { it?.copy(photo = name, preview = bitmap.asImageBitmap()) }
        // (1) A barcode on the photo, read while the food is recognized: its product, when found, is the answer.
        val barcode = async {
            val readAt = SystemClock.elapsedRealtime()
            val code = withContext(Dispatchers.Default) {
                try {
                    barcodeOf(bitmap)
                } catch (e: Exception) {
                    // ZXing may throw more than its own exceptions on an odd picture: no barcode, no crash.
                    Log.w(TAG, "Could not read a barcode on the photo", e)
                    null
                }
            }
            Log.i(TAG, "Barcode in the photo: ${if (code == null) "none" else "read"} in ${SystemClock.elapsedRealtime() - readAt} ms")
            code?.let { lookUpPhotoBarcode(it) }
        }
        val known = awaitCatalog()
        val started = SystemClock.elapsedRealtime()
        val detection = if (food.recognizer.detectorBundled) food.recognizer.detect(bitmap) else null
        var items = emptyList<PlateItem>()
        if (detection != null && detection.boxes.size >= 2) {
            items = plateItems(bitmap, detection, known)
            Log.i(TAG, "Plate of ${items.size} items recognized in ${SystemClock.elapsedRealtime() - started} ms")
            if (items.any { it.recognition != null }) _photo.update { it?.copy(status = PhotoStatus.DONE, items = items) } else items = emptyList()
        }
        val result = if (items.isEmpty()) food.recognizer.recognize(bitmap) { it in known } else null
        if (items.isEmpty()) _photo.update { it?.copy(status = if (result == null) PhotoStatus.NO_MODEL else PhotoStatus.DONE, recognition = result) }
        val barcodeState = barcode.await()
        if (PhotoPackRules.barcodeDecides(barcodeState)) return@coroutineScope
        // (2) One of the products the user already scanned (offline): the whole photo and its largest items.
        val lookAt = SystemClock.elapsedRealtime()
        val look = food.packs.look(listOf(bitmap) + items.sortedByDescending { it.share }.take(PACK_CROPS).map { it.crop })
        if (look != null) {
            Log.i(TAG, "Compared with the scanned products in ${SystemClock.elapsedRealtime() - lookAt} ms (${look.queries.size} pictures): ${look.find?.javaClass?.simpleName ?: "nothing close"}")
            packQueries = look.queries
            showPackFind(look.find)
        }
        // (3) A photo that looks like a package (no food seen, or only a weak guess) has its words read, and searched when enough.
        if (PhotoPackRules.readsByItself(items.isNotEmpty(), barcodeState, look?.find, result, canReadPackages, photoPageLeft)) {
            startTextSearch(bitmap, auto = true)
        }
    }

    // ---- adding: a packaged product on the meal photo -----------------------------------------------

    /** How the barcode page was opened from the meal photo (null: scanned from the Comida tab, no photo). */
    private var barcodeForPhoto: PackWay? = null

    /** The embeddings of the photo being logged (the whole photo first), once compared with the scanned products. */
    private var packQueries: List<FloatArray>? = null

    /** The words read on the photo being logged (read once per photo). */
    private var packWords: String? = null

    private var textJob: Job? = null

    private val _photoOpen = MutableStateFlow<String?>(null)

    /** A page the photo page opens by itself (the barcode read on the photo was found); [onPhotoOpened] once opened. */
    val photoOpen: StateFlow<String?> = _photoOpen.asStateFlow()

    /** The photo page is in front (resumed). */
    @Volatile
    private var photoPageShown = false

    /**
     * The user left the photo page of this photo (chose a food, a product or the search, or the page was
     * covered after it showed): nothing opens or is read by itself any more. A lookup that ends while the
     * page is still coming in is not "left": its page opens once the photo page is in front.
     */
    @Volatile
    private var photoPageLeft = false

    fun setPhotoPageShown(shown: Boolean) {
        if (!shown) {
            if (photoPageShown) photoPageLeft = true
            _photoOpen.value = null
        }
        photoPageShown = shown
    }

    fun onPhotoOpened() {
        _photoOpen.value = null
    }

    /** The user goes on from the photo page (a food, a product, the search): nothing opens by itself any more. */
    private fun leavePhotoPage() {
        photoPageLeft = true
        _photoOpen.value = null
    }

    /** Packages can be read in the app's language (ML Kit has a recognizer for its writing system). */
    val canReadPackages: Boolean get() = TextScript.forLanguage(Fmt.locale.language) != null

    private fun updatePack(transform: (PackUi) -> PackUi) = _photo.update { it?.copy(pack = transform(it.pack)) }

    /** The digits of a barcode in [bitmap] (a meal photo: no UPC-E), or null ([bitmap] is left as it is). */
    private fun barcodeOf(bitmap: Bitmap, formats: List<BarcodeFormat> = BarcodeReader.MEAL_PHOTO_FORMATS): String? {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        return BarcodeReader.decode(BarcodeReader.luminanceOf(pixels, w, h), w, h, formats)
    }

    /**
     * The barcode read on the meal photo, looked up like a scanned one for the photo's card. The barcode
     * page's own state is only set when that page opens by itself (the product was found while the photo
     * page is in front), so a page showing another product is never changed.
     */
    private suspend fun lookUpPhotoBarcode(code: String): BarcodeState {
        val canonical = BarcodeReader.canonical(code)
        updatePack { it.copy(barcode = PhotoBarcode(canonical, BarcodeState.LookingUp(canonical))) }
        val started = SystemClock.elapsedRealtime()
        val cached = repository.customFoodByBarcode(canonical)
        val state = cached?.let { foundCached(canonical, it) } ?: fetchProduct(canonical)
        Log.i(TAG, "Barcode of the photo looked up in ${SystemClock.elapsedRealtime() - started} ms: ${state.javaClass.simpleName}")
        updatePack { it.copy(barcode = PhotoBarcode(canonical, state)) }
        if (PhotoPackRules.opensBarcodePage(state, photoPageLeft)) {
            barcodeJob?.cancel()
            barcodeForPhoto = PackWay.BARCODE
            _barcode.value = state
            _photoOpen.value = FoodRoutes.BARCODE
        }
        // A kept product: refreshed when due and its photo looked after, as after a scan (in the background).
        cached?.let { viewModelScope.launch { lookAfterCached(canonical, it) } }
        return state
    }

    /** "Open" on the photo's barcode: its page (looked up again: the photo page shows what it gave). */
    fun openPhotoBarcode() {
        val code = _photo.value?.pack?.barcode?.code ?: return
        openProduct(code, PackWay.BARCODE)
    }

    /** One of the scanned products the photo looks like ("Is it …?" or a suggestion) opened as its barcode page. */
    fun choosePackProduct(customFoodId: String): Boolean {
        val code = repository.customFood(customFoodId)?.barcode ?: return false
        openProduct(code, PackWay.MATCH)
        return true
    }

    /** A product of the package search chosen: looked up by its barcode (kept like a scanned one), its page opens. */
    fun choosePackHit(hit: OffHit) = openProduct(hit.barcode, PackWay.TEXT)

    private fun openProduct(code: String, way: PackWay) {
        leavePhotoPage()
        barcodeJob?.cancel()
        barcodeForPhoto = way
        _barcode.value = BarcodeState.LookingUp(BarcodeReader.canonical(code))
        barcodeJob = viewModelScope.launch {
            lookUp(code)
            if (way == PackWay.BARCODE) _barcode.value?.let { s -> updatePack { it.copy(barcode = it.barcode?.copy(state = s)) } }
        }
    }

    private fun showPackFind(find: PackFind?) {
        fun product(id: String) = repository.customFood(id)?.let { PackProduct(it.id, it.name, it.brand) }
        when (find) {
            is PackFind.Match -> updatePack { it.copy(match = product(find.best.productId), suggestions = emptyList()) }
            is PackFind.Suggestions -> updatePack { it.copy(match = null, suggestions = find.products.mapNotNull { p -> product(p.productId) }) }
            null -> updatePack { it.copy(match = null, suggestions = emptyList()) }
        }
    }

    /** "Search the product by its package" (or "Try again"): nothing while a reading or a search runs. */
    fun searchPackage() {
        val bitmap = photoBitmap ?: return
        if (_photo.value == null || !canReadPackages) return
        startTextSearch(bitmap, auto = false)
    }

    private fun startTextSearch(bitmap: Bitmap, auto: Boolean) {
        if (textJob?.isActive == true) return
        textJob = viewModelScope.launch { readPackage(bitmap, auto) }
    }

    /**
     * The words of the search field, typed or corrected by the user (a logo is not text: the words read
     * may be the small print), searched exactly as given (within the same limits: a few a minute, the same
     * words from memory). Nothing while a reading or a search runs.
     */
    fun searchPackageWords(input: String) {
        val words = PackageText.typed(input) ?: return
        if (_photo.value == null || textJob?.isActive == true) return
        textJob = viewModelScope.launch { searchWords(words, broaden = false) }
    }

    /**
     * The words on the photo read on the phone, then searched in Open Food Facts. [auto]: the photo
     * looked like a package; it is searched only when enough words were read (the page then shows the
     * button again) and while the photo page is in front.
     */
    private suspend fun readPackage(bitmap: Bitmap, auto: Boolean) {
        val script = TextScript.forLanguage(Fmt.locale.language) ?: return
        val words = packWords ?: run {
            updatePack { it.copy(text = PackText.Reading) }
            val started = SystemClock.elapsedRealtime()
            when (val r = food.textReader.read(bitmap, script)) {
                is TextReading.Lines -> {
                    var q = PackageText.query(r.lines)
                    // Another script's recognizer that read nothing of its own: the Latin one reads Latin brand names better.
                    if (PackageText.needsLatin(script, r.lines, q)) {
                        (food.textReader.read(bitmap, TextScript.LATIN) as? TextReading.Lines)?.let { latin -> PackageText.latinQuery(latin.lines)?.let { q = it } }
                    }
                    Log.i(TAG, "Package read in ${SystemClock.elapsedRealtime() - started} ms: ${r.lines.size} lines, ${q?.split(' ')?.size ?: 0} words kept")
                    packWords = q.orEmpty()
                    q
                }
                is TextReading.Unavailable -> {
                    updatePack { it.copy(text = if (auto) PackText.Idle else PackText.Unavailable(r.preparing)) }
                    return
                }
                TextReading.Failed -> {
                    updatePack { it.copy(text = if (auto) PackText.Idle else PackText.ReadFailed) }
                    return
                }
            }
        }
        if (!PhotoPackRules.searches(words, auto) || auto && photoPageLeft) {
            updatePack { it.copy(text = if (auto || !words.isNullOrEmpty()) PackText.Idle else PackText.NoText) }
            return
        }
        searchWords(words.orEmpty(), broaden = true)
    }

    /** [query] searched in Open Food Facts; [broaden]: words read on the package, searched again with fewer when all of them find nothing. */
    private suspend fun searchWords(query: String, broaden: Boolean) {
        updatePack { it.copy(text = PackText.Searching(query)) }
        val started = SystemClock.elapsedRealtime()
        var searched: String = query
        var result = food.packSearch.search(query, Fmt.locale.language)
        // Nothing for all the words (one misread): once more with fewer.
        if (broaden && result is SearchResult.Hits && result.hits.isEmpty()) {
            PackageText.broader(query)?.let { fewer ->
                searched = fewer
                updatePack { it.copy(text = PackText.Searching(fewer)) }
                result = food.packSearch.search(fewer, Fmt.locale.language)
            }
        }
        Log.i(TAG, "Package searched in ${SystemClock.elapsedRealtime() - started} ms: ${result.javaClass.simpleName}${if (searched != query) " (fewer words)" else ""}")
        val country = PackSearch.countryTag(Locale.getDefault()) ?: PackSearch.countryTag(Fmt.locale)
        val found = result
        updatePack {
            it.copy(
                text = when (found) {
                    is SearchResult.Hits -> if (found.hits.isEmpty()) PackText.NotFound(searched) else PackText.Results(searched, PackSearch.ranked(found.hits, country))
                    SearchResult.Offline -> PackText.Offline(searched)
                    SearchResult.Busy -> PackText.Busy(searched)
                    is SearchResult.Failed -> PackText.Failed(searched)
                },
            )
        }
    }

    /** Photos of the package search's products, by address: shown in the list, never stored (memory only). */
    private val hitPhotos = object : LruCache<String, ImageBitmap>(HIT_PHOTOS_BYTES) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
    }

    fun cachedHitPhoto(url: String): ImageBitmap? = hitPhotos.get(url)

    /** The photo of a product of the package search (downloaded from Open Food Facts' image server, kept in memory), or null. */
    suspend fun hitPhoto(url: String, px: Int): ImageBitmap? = hitPhotos.get(url) ?: withContext(Dispatchers.IO) {
        val bytes = (food.openFoodFacts.image(url) as? PhotoResult.Photo)?.bytes ?: return@withContext null
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val opts = BitmapFactory.Options().apply { inSampleSize = ExercisePhotos.sampleSize(bounds.outWidth, bounds.outHeight, px) }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap()
        }.getOrNull()?.also { hitPhotos.put(url, it) }
    }

    /** Each box of the plate cropped, recognized and given its first food, amount and variants. */
    private suspend fun plateItems(bitmap: Bitmap, detection: Detection, known: Map<String, CatalogFood>): List<PlateItem> {
        val shares = DetectionMath.shares(detection.boxes, bitmap.width, bitmap.height)
        return detection.boxes.mapIndexed { i, box ->
            val sq = DetectionMath.crop(box, bitmap.width, bitmap.height, detection.cropMargin)
            val crop = withContext(Dispatchers.Default) { Bitmap.createBitmap(bitmap, sq.left, sq.top, sq.size, sq.size) }
            val r = food.recognizer.recognize(crop) { it in known }
            val first = r?.takeIf { !it.notFood }?.suggestions?.firstOrNull()?.label?.let(known::get)
            withFood(
                PlateItem(id = i, share = shares[i], crop = crop, preview = crop.asImageBitmap(), recognition = r, food = null, amountText = "", included = false),
                first?.let { FoodItem.of(it, food.catalog.name(it.id)) },
            ).copy(included = first != null)
        }
    }

    /**
     * [item] with [chosen] as its food: its variants, what it takes from its last entry, and the
     * amount (last time's, else the grams suggestion, else its portion) unless the user typed it.
     */
    private fun withFood(item: PlateItem, chosen: FoodItem?): PlateItem {
        if (chosen == null) return item.copy(food = null, variants = emptyList(), estimate = null, recall = null)
        val variants = chosen.foodId?.let { id -> FoodCatalog.variantsOf(id, catalogFoods.value).map { FoodItem.of(it, food.catalog.name(it.id)) } }.orEmpty()
        val recall = recallFor(chosen, recipeOf(chosen))
        // "Use" taken: the photo's suggestion for this food too, not its last entry's amount.
        val start = FoodDrafts.plateAmount(chosen, item.recognition?.grams, item.share, item.amount.takeIf { item.amountTyped }, recall.takeUnless { item.amountFromEstimate })
        return item.copy(
            food = chosen,
            variants = variants,
            estimate = start.estimate.takeUnless { item.amountTyped },
            amountText = if (item.amountTyped) item.amountText else FoodFormat.count(FoodMath.round1(start.amount)),
            included = true,
            recall = recall,
        )
    }

    /** The ingredients of a catalog dish that the catalog knows (none for anything else). */
    private fun recipeOf(item: FoodItem): List<RecipeIngredient> =
        item.foodId?.let { catalogRecipes.value[it] }.orEmpty().filter { it.ingredient in catalogFoods.value }

    /**
     * What a new entry of [item] takes from its last entry, or null when it was never logged. A
     * custom food changed since then has its own values (not those typed then).
     */
    private fun recallFor(item: FoodItem, recipe: List<RecipeIngredient>): Recall? {
        val last = FoodRecall.lastEntry(repository.entries.value, item.ref) ?: return null
        val changedAt = item.customFoodId?.let(repository::customFood)?.updatedAt
        return FoodRecall.recall(item, last, recipe, { catalogFoods.value[it]?.per100 }, changedAt)
    }

    private fun updateItem(id: Int, transform: (PlateItem) -> PlateItem) {
        _photo.update { s -> s?.copy(items = s.items.map { if (it.id == id) transform(it) else it }) }
    }

    /** A suggestion (catalog id) chosen for item [id] of the plate. */
    fun chooseItemSuggestion(id: Int, label: String) {
        val catalog = catalogFoods.value[label] ?: return
        updateItem(id) { withFood(it, FoodItem.of(catalog, food.catalog.name(label))) }
    }

    /** Another food (a variant, or one from the search) for item [id]. */
    fun chooseItemFood(id: Int, item: FoodItem) = updateItem(id) { withFood(it, item) }

    fun setItemAmount(id: Int, text: String) = updateItem(id) { it.copy(amountText = text.take(7), amountTyped = true, amountFromEstimate = false, estimate = null) }

    /** "Use" the photo's grams suggestion for item [id] instead of last time's amount (another food chosen then gets its own). */
    fun useItemEstimate(id: Int) = updateItem(id) { item ->
        item.estimate?.let { item.copy(amountText = FoodFormat.count(FoodMath.round1(it)), amountTyped = false, amountFromEstimate = true) } ?: item
    }

    /** "Use usual values" on item [id]: nothing from its last entry (the photo's suggestion or its portion, its own values). */
    fun useItemUsual(id: Int) = updateItem(id) { item ->
        val f = item.food ?: return@updateItem item
        val start = FoodDrafts.plateAmount(f, item.recognition?.grams, item.share, typed = null)
        item.copy(recall = null, amountTyped = false, amountFromEstimate = false, estimate = start.estimate, amountText = FoodFormat.count(FoodMath.round1(start.amount)))
    }

    fun setItemIncluded(id: Int, included: Boolean) = updateItem(id) { it.copy(included = included && it.food != null) }

    fun setPhotoMeal(meal: Meal) = _photo.update { it?.copy(meal = meal) }

    /** The plate as one dish: today's five suggestions of the whole photo (recognized now if not yet). */
    fun showPhotoAsOne(asOne: Boolean) {
        val s = _photo.value ?: return
        _photo.value = s.copy(asOne = asOne)
        if (!asOne || s.recognition != null) return
        val bitmap = photoBitmap ?: return
        _photo.value = s.copy(asOne = true, status = PhotoStatus.RECOGNIZING)
        photoJob = viewModelScope.launch {
            val known = awaitCatalog()
            val result = food.recognizer.recognize(bitmap) { it in known }
            _photo.update { it?.copy(status = if (result == null) PhotoStatus.NO_MODEL else PhotoStatus.DONE, recognition = result) }
        }
    }

    /**
     * "Add all": one entry per included item of the plate, in the meal chosen, each with its own crop as
     * its photo. An item that could not be saved stays ticked (the snackbar says so) for another try.
     */
    fun addPlateItems() {
        val s = _photo.value ?: return
        if (s.addingItems) return
        val chosen = s.items.filter { it.included && it.food != null && it.amount != null }
        if (chosen.isEmpty()) return
        _photo.value = s.copy(addingItems = true)
        val zone = ZoneId.systemDefault()
        val offset = zone.rules.getOffset(Instant.ofEpochMilli(s.eatenAt)).totalSeconds
        viewModelScope.launch {
            val added = ArrayList<String>()
            for (item in chosen) {
                val f = item.food ?: continue
                val amount = item.amount ?: continue
                val photo = withContext(Dispatchers.IO) { runCatching { photos.store(item.crop) }.getOrNull() }
                photo?.let(savingPhotos::add)
                // As the confirm screen would: last time's ingredient changes and values typed by hand included.
                val dish = FoodDrafts.plateDish(f, amount, item.recall, recipeOf(f)) { catalogFoods.value[it]?.per100 }
                val entry = try {
                    writing {
                        repository.addEntry(
                            FoodEntry(
                                id = "",
                                eatenAt = s.eatenAt,
                                zoneOffsetSeconds = offset,
                                meal = s.meal,
                                foodId = f.foodId,
                                customFoodId = f.customFoodId,
                                name = f.name,
                                amount = dish.amount,
                                unit = f.unit,
                                portionKey = dish.portion?.key,
                                portionCount = dish.portionCount,
                                nutrients = dish.nutrients,
                                source = EntrySource.PHOTO,
                                photo = photo,
                                modelLabel = item.recognition?.topLabel,
                                modelConfidence = item.recognition?.topConfidence?.toDouble(),
                                createdAt = 0,
                                updatedAt = 0,
                                adjustments = dish.adjustments,
                                estimatedAmount = item.recognition?.grams?.estimate(f.portion, item.share),
                                valuesEdited = dish.valuesEdited,
                            ),
                        )
                    }
                } finally {
                    photo?.let(savingPhotos::remove)
                }
                if (entry == null) {
                    photo?.let(food::deletePhotoIfUnused)
                    continue
                }
                added += entry.name
                updateItem(item.id) { it.copy(included = false, added = true) }
            }
            if (added.isNotEmpty()) {
                settings.markUsed()
                showDay(FoodViewModel.dateOf(s.eatenAt))
                maybeAskHealthPermission()
            }
            _photo.update { it?.copy(addingItems = false, added = it.added + added) }
        }
    }

    /** The photo flow ends: a photo no entry uses (nor one being saved) is deleted. */
    fun closePhotoSession() {
        photoJob?.cancel()
        textJob?.cancel()
        photoBitmap = null
        packQueries = null
        packWords = null
        barcodeForPhoto = null
        _photoOpen.value = null
        if (searchTarget != null) searchTarget = null
        val session = _photo.value ?: return
        _photo.value = null
        session.photo?.let(food::deletePhotoIfUnused)
    }

    /** A suggestion (catalog id) chosen for the photo (as one dish). */
    fun chooseSuggestion(label: String): Boolean {
        val session = _photo.value ?: return false
        val catalog = catalogFoods.value[label] ?: return false
        val item = FoodItem.of(catalog, food.catalog.name(label))
        startDraft(item, EntrySource.PHOTO, session, estimate = session.recognition?.grams?.estimate(item.portion, 1.0), grams = session.recognition?.grams, recall = true)
        return true
    }

    // ---- adding: search -----------------------------------------------------------------------

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    fun setQuery(q: String) {
        _query.value = q
    }

    /** Matches of the query (recent foods while it is empty). */
    val results: StateFlow<List<FoodItem>> = combine(items, repository.entries, _query) { all, entries, q ->
        FoodSearch.search(all, FoodSearch.usage(entries), q)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Search opened from the photo ("Other…"): the entry keeps the photo. */
    private var searchForPhoto = false

    /** Search opened for item [id] of the plate: the food chosen goes back to that item. */
    private var searchTarget: Int? = null

    /** The search picks a food for an item of the plate (no entry of its own: no manual entry either). */
    val searchingForItem: Boolean get() = searchTarget != null

    fun startSearch(forPhoto: Boolean) {
        leavePhotoPage()
        searchForPhoto = forPhoto && _photo.value != null
        searchTarget = null
        _query.value = ""
    }

    /** "Other…" on item [id] of the plate. */
    fun startItemSearch(id: Int) {
        leavePhotoPage()
        searchForPhoto = false
        searchTarget = id.takeIf { _photo.value?.items?.any { it.id == id } == true }
        _query.value = ""
    }

    /**
     * A food chosen in the search: true when the confirm page opens for it, false when it went to the
     * plate's item the search was opened for (the search just closes).
     */
    fun chooseItem(item: FoodItem): Boolean {
        searchTarget?.let { id ->
            searchTarget = null
            chooseItemFood(id, item)
            return false
        }
        val session = _photo.value.takeIf { searchForPhoto }
        val source = when {
            session != null -> EntrySource.PHOTO
            _query.value.isBlank() -> EntrySource.RECENT
            else -> EntrySource.SEARCH
        }
        // The photo's picture estimates this food too (its own portion).
        val grams = session?.recognition?.grams
        startDraft(item, source, session, estimate = grams?.estimate(item.portion, 1.0), grams = grams, recall = true)
        return true
    }

    /** "Edit food" on one of the user's foods (or a product kept from a barcode): the manual form, saving the food only. */
    fun editCustomFood(item: FoodItem) {
        val f = item.customFoodId?.let(repository::customFood) ?: return
        setManual(
            ManualDraft(
                name = f.name,
                brand = f.brand,
                barcode = f.barcode,
                unit = f.unit,
                amount = 100.0,
                values = f.per100,
                customFoodId = f.id,
                saveAsFood = true,
                editOnly = true,
                serial = nextSerial(),
            ),
        )
    }

    /** "Delete food": gone from the search (and the backup); the meals logged with it keep their own values. */
    fun deleteCustomFood(item: FoodItem) {
        val id = item.customFoodId ?: return
        viewModelScope.launch { writing { repository.deleteCustomFood(id) } }
    }

    // ---- adding: barcode ----------------------------------------------------------------------

    private val _barcode = MutableStateFlow<BarcodeState?>(null)
    val barcode: StateFlow<BarcodeState?> = _barcode.asStateFlow()

    private var barcodeJob: Job? = null

    /** A barcode photo; [forPhoto]: taken from the meal photo's page (for the package on it: the entry keeps the meal photo). */
    fun onBarcodePhoto(uri: Uri, fromCamera: Boolean, forPhoto: Boolean = false) {
        barcodeJob?.cancel()
        barcodeForPhoto = PackWay.BARCODE.takeIf { forPhoto && _photo.value != null }
        _barcode.value = BarcodeState.Reading
        barcodeJob = viewModelScope.launch {
            val code = withContext(Dispatchers.Default) {
                val bitmap = withContext(Dispatchers.IO) { photos.decode(uri, MealPhotos.BARCODE_SIDE_PX) }
                if (fromCamera) withContext(Dispatchers.IO) { runCatching { photos.cameraTarget.delete() } }
                bitmap?.let(::readBarcode)
            }
            if (code == null) _barcode.value = BarcodeState.NotRead else lookUp(code)
        }
    }

    /** A barcode typed by hand (when the photo could not be read). */
    fun onBarcodeTyped(code: String) {
        barcodeJob?.cancel()
        barcodeJob = viewModelScope.launch { lookUp(code.filter(Char::isDigit)) }
    }

    fun retryBarcode() {
        val code = when (val s = _barcode.value) {
            is BarcodeState.Offline -> s.code
            is BarcodeState.Failed -> s.code
            else -> return
        }
        onBarcodeTyped(code)
    }

    /** The barcode of a photo aimed at one (every product format); an odd picture ZXing cannot take reads as none. */
    private fun readBarcode(bitmap: Bitmap): String? = try {
        barcodeOf(bitmap, BarcodeReader.FORMATS)
    } catch (e: Exception) {
        Log.w(TAG, "Could not read the barcode photo", e)
        null
    } finally {
        bitmap.recycle()
    }

    private suspend fun lookUp(scanned: String) {
        val code = BarcodeReader.canonical(scanned)
        repository.customFoodByBarcode(code)?.let { cached ->
            _barcode.value = foundCached(code, cached)
            lookAfterCached(code, cached)
            return
        }
        _barcode.value = BarcodeState.LookingUp(code)
        _barcode.value = fetchProduct(code)
    }

    /** A kept product found again by its barcode (with its last entry, if it was logged before). */
    private fun foundCached(code: String, cached: CustomFood): BarcodeState.Found {
        val last = FoodRecall.lastEntry(repository.entries.value, FoodRef.Custom(cached.id))
        return BarcodeState.Found(code, FoodItem.of(cached), missingOf(cached.per100), fromCache = true, last = last)
    }

    /** After a kept product is found: asked again when due; its photo comes (or is downloaded again) in the background once its address is known. */
    private suspend fun lookAfterCached(code: String, cached: CustomFood) {
        when {
            OffCache.needsRefresh(cached, System.currentTimeMillis()) -> refresh(code, cached)
            OffCache.needsImageCheck(cached) -> writing { food.checkProductPhoto(cached, Fmt.locale.language) }
        }
        food.fetchProductPhoto(cached.id)
    }

    /** The product of [code] from Open Food Facts, kept for next time when found (only the number is sent). */
    private suspend fun fetchProduct(code: String): BarcodeState =
        when (val r = food.openFoodFacts.product(code, Fmt.locale.language)) {
            is OffResult.Found -> {
                val p = r.product
                if (p.name == null && p.per100.isEmpty) {
                    BarcodeState.NotFound(code)
                } else {
                    writing { repository.addCustomFood(OffCache.customFoodOf(p, code)) }
                        ?.let { stored ->
                            food.fetchProductPhoto(stored.id)
                            BarcodeState.Found(code, FoodItem.of(stored), p.missing, fromCache = false)
                        }
                        ?: BarcodeState.Failed(code)
                }
            }
            OffResult.NotFound -> BarcodeState.NotFound(code)
            OffResult.Offline -> BarcodeState.Offline(code)
            is OffResult.Failed -> BarcodeState.Failed(code)
        }

    /**
     * A kept product that is incomplete or old is asked again while the cached one shows; the page takes
     * the new values if it still shows that product. Offline or not found, the cached one simply stays.
     */
    private suspend fun refresh(code: String, cached: CustomFood) {
        val p = (food.openFoodFacts.product(code, Fmt.locale.language) as? OffResult.Found)?.product ?: return
        val now = System.currentTimeMillis()
        val stored = writing { repository.updateCustomFood(cached.id) { OffCache.refreshed(it, p, now) } } ?: return
        _barcode.update { s ->
            if (s is BarcodeState.Found && s.code == code && s.item.customFoodId == stored.id) {
                s.copy(item = FoodItem.of(stored), missing = missingOf(stored.per100))
            } else {
                s
            }
        }
    }

    private fun missingOf(n: Nutrients) = Nutrient.LABEL.filter { n[it] == null }

    /** "Use this product"; opened from a meal photo, the entry keeps that photo (meal and time too). */
    fun useBarcodeProduct() {
        val found = _barcode.value as? BarcodeState.Found ?: return
        val session = _photo.value.takeIf { barcodeForPhoto != null }
        startDraft(found.item, EntrySource.BARCODE, session, recall = true, fromPackage = session != null)
    }

    /** "Complete the values" of a product with missing ones, or "Enter manually" for one not found. */
    fun manualFromBarcode() {
        when (val s = _barcode.value) {
            is BarcodeState.Found -> {
                val item = s.item
                setManual(
                    ManualDraft(
                        name = item.name,
                        brand = item.brand,
                        barcode = s.code,
                        unit = item.unit,
                        amount = 100.0,
                        values = item.per100,
                        customFoodId = item.customFoodId,
                        saveAsFood = true,
                        completing = item.customFoodId != null,
                        serial = nextSerial(),
                    ),
                )
            }
            is BarcodeState.NotFound -> setManual(ManualDraft(barcode = s.code, saveAsFood = true, serial = nextSerial()))
            else -> setManual(ManualDraft(serial = nextSerial()))
        }
    }

    fun clearBarcode() {
        barcodeJob?.cancel()
        barcodeForPhoto = null
        _barcode.value = null
    }

    // ---- adding: manual -----------------------------------------------------------------------

    private val _manual = MutableStateFlow(state.get<Bundle>(KEY_MANUAL)?.let(ManualDraft::fromBundle) ?: ManualDraft())
    val manual: StateFlow<ManualDraft> = _manual.asStateFlow()

    private fun setManual(d: ManualDraft) {
        _manual.value = d
        state[KEY_MANUAL] = d.toBundle()
    }

    fun startManual(name: String = "", saveAsFood: Boolean = false) {
        setManual(ManualDraft(name = name, saveAsFood = saveAsFood, serial = nextSerial()))
    }

    /**
     * The manual form's values ([values] for [amount]): saved as a custom food when asked (or updates the
     * food it completes or edits), then confirmed like any food unless only the food was being edited.
     * [onDone] gets false when nothing could be saved (the form stays, a message says so).
     */
    fun submitManual(name: String, brand: String?, unit: FoodUnit, amount: Double, values: Nutrients, saveAsFood: Boolean, onDone: (Boolean) -> Unit) {
        if (manualJob?.isActive == true) return
        val draft = _manual.value
        val per100 = FoodMath.per100Of(values, amount)
        manualJob = viewModelScope.launch {
            val saved = writing {
                when {
                    draft.customFoodId != null -> repository.updateCustomFood(draft.customFoodId) {
                        it.copy(name = name, brand = brand, unit = unit, per100 = per100)
                    }
                    saveAsFood -> repository.addCustomFood(
                        CustomFood(
                            id = "",
                            name = name,
                            brand = brand,
                            barcode = draft.barcode,
                            unit = unit,
                            per100 = per100,
                            portion = amount,
                            // The user's own values, even for a barcode Open Food Facts does not know.
                            source = CustomFoodSource.USER,
                            createdAt = 0,
                            updatedAt = 0,
                        ),
                    ).also { created ->
                        // Back here and "Continue" again: that food is updated, not created twice.
                        val m = _manual.value
                        if (m.serial == draft.serial) setManual(m.copy(customFoodId = created.id))
                    }
                    else -> null
                }.let { Saved(it) }
            }
            if (saved == null) {
                onDone(false)
                return@launch
            }
            if (draft.editOnly) {
                onDone(true)
                return@launch
            }
            // A product (completed or not) keeps what makes it one: the barcode source, its ODbL line, its serving.
            val item = saved.food?.let(FoodItem::of)
                ?: FoodItem(null, name.trim(), brand, unit, per100, amount, emptyList(), null, barcode = draft.barcode)
            val session = _photo.value.takeIf { searchForPhoto && draft.barcode == null }
            val source = when {
                draft.barcode != null -> EntrySource.BARCODE
                session != null -> EntrySource.PHOTO
                else -> EntrySource.MANUAL
            }
            // Completing a product's values per 100: its serving is what is eaten (as "Use" would give).
            startDraft(item, source, session, amount = amount.takeUnless { draft.completing })
            onDone(true)
        }
    }

    /** What [submitManual] stored (null: a quick entry, no food saved). */
    private class Saved(val food: CustomFood?)

    private var manualJob: Job? = null

    // ---- confirm / edit ----------------------------------------------------------------------

    private val _draft = MutableStateFlow<FoodDraft?>(null)
    val draft: StateFlow<FoodDraft?> = _draft.asStateFlow()

    private val _draftSave = MutableStateFlow<DraftSave?>(null)

    /** The save of the confirm screen's draft (by its serial). */
    val draftSave: StateFlow<DraftSave?> = _draftSave.asStateFlow()

    /** Serials of the drafts, kept in the saved state: a form restored after the process keeps its fields. */
    private var serial = state.get<Long>(KEY_SERIAL) ?: 0L

    private fun nextSerial(): Long = (++serial).also { state[KEY_SERIAL] = it }

    /**
     * A new draft of [item]. [amount]: a fixed amount (a product's values just completed for it); else,
     * with [recall], as the food's last entry when it was logged before (its amount, ingredient
     * changes and values typed by hand; each variant offered as its own last entry); else the
     * photo's [estimate] when there is one (a suggestion: the screen says so), else the food's
     * default portion.
     */
    private fun startDraft(
        item: FoodItem,
        source: EntrySource,
        session: PhotoSession?,
        amount: Double? = null,
        estimate: Double? = null,
        grams: GramsEstimator? = null,
        recall: Boolean = false,
        fromPackage: Boolean = false,
    ) {
        // The confirm page opens: a product found on the photo's barcode must not open over it.
        leavePhotoPage()
        val now = System.currentTimeMillis()
        val eatenAt = session?.eatenAt ?: eatenAtFor(_day.value, now)
        val shape = FoodDrafts.shape(item, catalogFoods.value, catalogRecipes.value, food.catalog::name)
        val asLast = if (recall && amount == null) recallFor(item, shape.recipe) else null
        val start = if (amount != null) DraftAmount(amount, null, null) else FoodDrafts.startAmount(item, asLast, estimate)
        _draft.value = FoodDraft(
            editingId = null,
            item = item,
            source = source,
            amount = start.amount,
            portion = start.portion,
            portionCount = start.portionCount,
            meal = session?.meal ?: defaultMeal(eatenAt),
            eatenAt = eatenAt,
            photo = session?.photo,
            modelLabel = session?.recognition?.topLabel,
            modelConfidence = session?.recognition?.topConfidence?.toDouble(),
            serial = nextSerial(),
            recipe = shape.recipe,
            options = shape.options.map { o ->
                when {
                    o.item.ref == item.ref -> o.copy(recall = asLast)
                    recall && amount == null -> o.copy(recall = recallFor(o.item, o.recipe))
                    else -> o
                }
            },
            ingredients = shape.ingredients,
            estimatedAmount = estimate.takeIf { amount == null },
            grams = grams.takeIf { amount == null },
            recall = asLast,
            fromPackage = fromPackage && session?.photo != null && item.customFoodId != null,
        )
        lookAfterPhoto(item.customFoodId)
    }

    /** A product opened to be logged gets its photo from Open Food Facts if it has none yet (in the background). */
    private fun lookAfterPhoto(customFoodId: String?) {
        customFoodId?.let(repository::customFood)?.let { food.lookAfterProductPhoto(it, Fmt.locale.language) }
    }

    /** Opens an existing entry in the confirm screen. Returns false when it no longer exists (or food.db is not open). */
    suspend fun editEntry(id: String): Boolean {
        try {
            repository.awaitLoaded()
        } catch (e: IllegalStateException) {
            // The tab shows why.
            return false
        }
        val catalogFoods = awaitCatalog()
        val e = repository.entry(id) ?: return false
        // The dish as it was before its ingredients were changed (undone with today's ingredient
        // values, which saving re-applies: an entry saved unchanged keeps its values).
        val adjustments = e.adjustments.takeIf { e.dishAmount > 0 }.orEmpty()
        val dishAmount = if (adjustments.isEmpty()) e.amount else e.dishAmount
        val per100 = RecipeMath.unadjustedPer100(e.nutrients, e.amount, adjustments) { catalogFoods[it]?.per100 }
        val catalog = e.foodId?.let { catalogFoods[it] }
        val custom = e.customFoodId?.let(repository::customFood)
        val base = when {
            catalog != null -> FoodItem.of(catalog, food.catalog.name(catalog.id))
            custom != null -> FoodItem.of(custom)
            else -> FoodItem(FoodRef.of(e.foodId, e.customFoodId), e.name, null, e.unit, per100, dishAmount, emptyList(), null)
        }
        // The values logged stay the entry's own (a newer catalog never rewrites them); "Restore" brings
        // back the food's own ones, while it is still known. Editing never recalls another entry.
        val item = base.copy(name = entryName(e), per100 = per100, unit = e.unit)
        val own = (catalog?.per100 ?: custom?.per100)?.takeIf { (catalog?.unit ?: custom?.unit) == e.unit }
        val shape = FoodDrafts.shape(item, catalogFoods, catalogRecipes.value, food.catalog::name, adjustments, dishAmount)
        val draft = FoodDraft(
            editingId = id,
            item = item,
            source = e.source,
            amount = dishAmount,
            portion = e.portionKey?.let { k -> item.portions.firstOrNull { it.key == k } ?: e.portionCount?.takeIf { it > 0 }?.let { Portion(k, dishAmount / it) } },
            portionCount = e.portionCount,
            meal = e.meal,
            eatenAt = e.eatenAt,
            photo = e.photo,
            modelLabel = e.modelLabel,
            modelConfidence = e.modelConfidence,
            serial = nextSerial(),
            recipe = shape.recipe,
            options = shape.options,
            ingredients = shape.ingredients,
            adjustments = adjustments,
            estimatedAmount = e.estimatedAmount,
            own = own,
            valuesEdited = e.valuesEdited,
        )
        // The entry's own option is the draft's (its values, the food's own ones to restore).
        _draft.value = draft.copy(options = draft.options.map { if (it.item.ref == item.ref) draft.current else it })
        lookAfterPhoto(custom?.id)
        return true
    }

    /**
     * Saves the draft with the screen's choices (a new entry, or the edited one). [draftSave] says when
     * it is done; after a failure (a message is shown) the same draft may be saved again.
     */
    fun saveDraft(choice: DraftChoice) {
        val d = _draft.value ?: return
        val previous = _draftSave.value?.takeIf { it.serial == d.serial }
        // A second tap while it saves (or once saved) saves nothing more.
        if (choice.dishAmount <= 0 || previous != null && previous.status != SaveStatus.FAILED) return
        _draftSave.value = DraftSave(d.serial, SaveStatus.SAVING, fromPhoto = d.photo != null && d.editingId == null)
        val zone = ZoneId.systemDefault()
        val offset = zone.rules.getOffset(Instant.ofEpochMilli(choice.eatenAt)).totalSeconds
        val dish = RecipeMath.apply(choice.item.per100, choice.dishAmount, choice.adjustments) { d.ingredients[it]?.per100 }
        d.photo?.let(savingPhotos::add)
        viewModelScope.launch {
            val saved = try {
                writing { save(d, choice, dish, offset) } != null
            } finally {
                d.photo?.let(savingPhotos::remove)
            }
            _draftSave.update { s -> if (s?.serial == d.serial) s.copy(status = if (saved) SaveStatus.SAVED else SaveStatus.FAILED) else s }
        }
    }

    /** Photos of entries being saved right now (not in the list yet): never deleted meanwhile (read from any thread). */
    private val savingPhotos: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private suspend fun save(d: FoodDraft, c: DraftChoice, dish: AdjustedDish, offset: Int) {
        val item = c.item
        if (d.editingId != null) {
            repository.updateEntry(d.editingId) {
                it.copy(
                    eatenAt = c.eatenAt,
                    // Logged while travelling: the entry keeps its zone unless its time changes.
                    zoneOffsetSeconds = if (c.eatenAt == it.eatenAt) it.zoneOffsetSeconds else offset,
                    meal = c.meal,
                    // Another variant of the dish chosen: the entry is that food now.
                    foodId = if (item.ref == d.item.ref) it.foodId else item.foodId,
                    customFoodId = if (item.ref == d.item.ref) it.customFoodId else item.customFoodId,
                    name = if (item.ref == d.item.ref) it.name else item.name,
                    amount = dish.amount,
                    unit = item.unit,
                    portionKey = c.portionKey,
                    portionCount = c.portionCount,
                    nutrients = dish.nutrients,
                    adjustments = c.adjustments,
                    valuesEdited = c.valuesEdited,
                )
            }
            return
        }
        val entry = repository.addEntry(
            FoodEntry(
                id = "",
                eatenAt = c.eatenAt,
                zoneOffsetSeconds = offset,
                meal = c.meal,
                foodId = item.foodId,
                customFoodId = item.customFoodId,
                name = item.name,
                amount = dish.amount,
                unit = item.unit,
                portionKey = c.portionKey,
                portionCount = c.portionCount,
                nutrients = dish.nutrients,
                source = d.source,
                photo = d.photo,
                modelLabel = d.modelLabel,
                modelConfidence = d.modelConfidence,
                createdAt = 0,
                updatedAt = 0,
                adjustments = c.adjustments,
                estimatedAmount = c.estimatedAmount,
                valuesEdited = c.valuesEdited,
            ),
        )
        settings.markUsed()
        // The day shown follows the entry, so the user sees it there.
        showDay(entry.localDate)
        // A product logged from a photo of its package: the photo's embedding helps recognise it next time.
        if (d.fromPackage && d.photo != null && _photo.value?.photo == d.photo) {
            item.customFoodId?.let { food.packs.remember(it, d.photo, packQueries, photoBitmap) }
        }
        if (d.photo != null) {
            _photo.update { s -> if (s != null && s.photo == d.photo) s.copy(added = s.added + entry.name, meal = c.meal, eatenAt = c.eatenAt) else s }
        }
        maybeAskHealthPermission()
    }

    // ---- deleting (with undo) ----------------------------------------------------------------

    private val _deleted = MutableStateFlow<FoodEntry?>(null)

    /** The entry just deleted, while its "Undo" is offered (the snackbar only shows it: the timing is here). */
    val deleted: StateFlow<FoodEntry?> = _deleted.asStateFlow()

    /** Ends the "Undo" of [_deleted] on time, whether the Comida tab is still shown or not. */
    private var undoJob: Job? = null

    /** Deletes the entry; its "Undo" is offered for [undoMillis] (longer with accessibility services that ask for it). */
    fun deleteEntry(id: String, undoMillis: Long = UNDO_MS) {
        viewModelScope.launch {
            val gone = writing { repository.deleteEntry(id) } ?: return@launch
            val previous = _deleted.value
            // First the new one: its photo (maybe the same plate's) stays while its "Undo" is offered.
            _deleted.value = gone
            undoJob?.cancel()
            undoJob = viewModelScope.launch {
                delay(undoMillis)
                confirmDelete(gone)
            }
            previous?.let(::finishDelete)
        }
    }

    /** "Undo" of the deletion of [e] (the entry the snackbar named). */
    fun undoDelete(e: FoodEntry) {
        if (_deleted.value?.id == e.id) {
            undoJob?.cancel()
            undoJob = null
            _deleted.value = null
        }
        viewModelScope.launch {
            if (repository.entry(e.id) == null && writing { repository.restoreEntry(e) } == null) {
                // Not restored after all: its photo goes like the entry.
                finishDelete(e)
            }
        }
    }

    /** "Undo" was not taken (dismissed, or its time is up): the photo goes with the entry (unless another entry of the same photo keeps it). */
    fun confirmDelete(e: FoodEntry) {
        if (_deleted.value?.id != e.id) return
        _deleted.value = null
        undoJob?.cancel()
        undoJob = null
        finishDelete(e)
    }

    private fun finishDelete(e: FoodEntry) {
        e.photo?.let(food::deletePhotoIfUnused)
    }

    /**
     * Photos this view model still needs although no entry may use them: the entry whose "Undo" is
     * offered, an entry being saved, the photo being logged. Read by [FoodRepository.photoInUse] from
     * any thread (the cloud sync deletes the photos of entries deleted on another phone).
     */
    private fun needsPhoto(name: String): Boolean = _deleted.value?.photo == name || name in savingPhotos || _photo.value?.photo == name

    /** A meal photo for the lists (small), or null. */
    suspend fun thumbnail(name: String, px: Int): ImageBitmap? = withContext(Dispatchers.IO) {
        runCatching { photos.loadBitmap(name, px)?.asImageBitmap() }.getOrNull()
    }

    /**
     * Product photos already decoded, by file name and size: a row scrolled back into view shows its
     * photo at once (a file name is never reused for another photo).
     */
    private val productThumbnails = object : LruCache<String, ImageBitmap>(PRODUCT_THUMBNAILS_BYTES) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
    }

    /** A product's photo already decoded at [px] ([productThumbnail]), or null. */
    fun cachedProductThumbnail(name: String, px: Int): ImageBitmap? = productThumbnails.get("$name@$px")

    /** A product's photo ([productPhotos]) for the lists (small), or null. */
    suspend fun productThumbnail(name: String, px: Int): ImageBitmap? = cachedProductThumbnail(name, px) ?: withContext(Dispatchers.IO) {
        runCatching { food.productPhotos.loadBitmap(name, px)?.asImageBitmap() }.getOrNull()?.also { productThumbnails.put("$name@$px", it) }
    }

    // ---- Health Connect ------------------------------------------------------------------------

    private val _askHealth = MutableStateFlow(false)

    /** Ask for the nutrition permission now (once, after the first meal saved while Health Connect is set up). */
    val askHealthPermission: StateFlow<Boolean> = _askHealth.asStateFlow()

    private fun maybeAskHealthPermission() {
        val gateway = container.health.gateway
        val granted = gateway.granted.value
        val setUp = gateway.availability.value == HcAvailability.AVAILABLE && granted.isNotEmpty()
        if (!setUp || HealthPermissions.canExportMeals(granted) || settings.healthPermissionAsked.value || settings.healthExportChoice.value == false) return
        settings.markHealthPermissionAsked()
        _askHealth.value = true
    }

    fun onHealthPermissionAsked() {
        _askHealth.value = false
    }

    fun onHealthPermissionResult() {
        viewModelScope.launch(Dispatchers.IO) { container.health.gateway.refresh() }
    }

    val healthPermissionContract = container.health.gateway.permissionContract()

    // ---- helpers -------------------------------------------------------------------------------

    /** A new entry for a past day is placed at the same time of day on that day (now for today). */
    private fun eatenAtFor(day: LocalDate, now: Long): Long {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        if (day >= today) return now
        val time = LocalDateTime.ofInstant(Instant.ofEpochMilli(now), zone).toLocalTime()
        return day.atTime(time).atZone(zone).toInstant().toEpochMilli()
    }

    private fun defaultMeal(at: Long): Meal =
        FoodMath.mealFor(LocalDateTime.ofInstant(Instant.ofEpochMilli(at), ZoneId.systemDefault()).toLocalTime())

    // Last: everything needsPhoto reads exists by now (the sync may call it at once, from another thread).
    private val photoUser = repository.registerPhotoUser(::needsPhoto)

    init {
        viewModelScope.launch { followMidnight() }
    }

    override fun onCleared() {
        // The activity is finishing: a pending "Undo" is settled now (its timer goes with this view model).
        _deleted.value?.let(::confirmDelete)
        photoUser.close()
    }

    companion object {
        private const val TAG = "FoodViewModel"
        private const val KEY_MANUAL = "food_manual"
        private const val KEY_SERIAL = "food_serial"

        /**
         * A meal photo is decoded this large for recognition (the items of a plate are crops of it; the
         * stored photo is smaller, [MealPhotos.MAX_SIDE_PX]).
         */
        private const val RECOGNITION_SIDE_PX = 1280

        /** How long "Undo" is offered after deleting an entry (the snackbar's long duration). */
        const val UNDO_MS = 10_000L

        /** Memory for decoded product photos (a 400 px photo takes about 0.5 MB). */
        private const val PRODUCT_THUMBNAILS_BYTES = 8 * 1024 * 1024

        /** Memory for the photos of the package search's products (200 px ones: about 0.15 MB each). */
        private const val HIT_PHOTOS_BYTES = 4 * 1024 * 1024

        /** The largest items of a plate also compared with the scanned products (besides the whole photo). */
        private const val PACK_CROPS = 3

        /** The day is checked at least this often (a clock or time zone change moves midnight). */
        private const val MAX_DAY_CHECK_MS = 15 * 60_000L

        val Factory = viewModelFactory {
            initializer { FoodViewModel((this[APPLICATION_KEY] as GlobalFitnessApp).container, createSavedStateHandle()) }
        }

        /** Local time of day of an epoch time. */
        fun timeOf(at: Long): LocalTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(at), ZoneId.systemDefault()).toLocalTime()

        fun dateOf(at: Long): LocalDate = LocalDateTime.ofInstant(Instant.ofEpochMilli(at), ZoneId.systemDefault()).toLocalDate()

        fun at(date: LocalDate, time: LocalTime): Long = date.atTime(time).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }
}
