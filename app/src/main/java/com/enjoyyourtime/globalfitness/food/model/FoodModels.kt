package com.enjoyyourtime.globalfitness.food.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The eight nutrients of the EU nutrition label. Absolute values (an amount eaten) or per 100 g / ml
 * (a food), in kcal and grams; null when unknown (a missing value is never a zero).
 */
data class Nutrients(
    val kcal: Double? = null,
    val protein: Double? = null,
    val carbs: Double? = null,
    val sugars: Double? = null,
    val fat: Double? = null,
    val satFat: Double? = null,
    val fiber: Double? = null,
    val salt: Double? = null,
) {
    operator fun get(n: Nutrient): Double? = when (n) {
        Nutrient.KCAL -> kcal
        Nutrient.PROTEIN -> protein
        Nutrient.CARBS -> carbs
        Nutrient.SUGARS -> sugars
        Nutrient.FAT -> fat
        Nutrient.SAT_FAT -> satFat
        Nutrient.FIBER -> fiber
        Nutrient.SALT -> salt
    }

    fun with(n: Nutrient, value: Double?): Nutrients = when (n) {
        Nutrient.KCAL -> copy(kcal = value)
        Nutrient.PROTEIN -> copy(protein = value)
        Nutrient.CARBS -> copy(carbs = value)
        Nutrient.SUGARS -> copy(sugars = value)
        Nutrient.FAT -> copy(fat = value)
        Nutrient.SAT_FAT -> copy(satFat = value)
        Nutrient.FIBER -> copy(fiber = value)
        Nutrient.SALT -> copy(salt = value)
    }

    /** Every known value times [factor] (unknown ones stay unknown). */
    fun scaled(factor: Double): Nutrients = map { it * factor }

    /** Sum of the known values; a nutrient is unknown only when it is unknown on both sides. */
    operator fun plus(other: Nutrients): Nutrients = Nutrients(
        add(kcal, other.kcal), add(protein, other.protein), add(carbs, other.carbs), add(sugars, other.sugars),
        add(fat, other.fat), add(satFat, other.satFat), add(fiber, other.fiber), add(salt, other.salt),
    )

    val isEmpty: Boolean get() = Nutrient.entries.all { get(it) == null }

    private fun map(f: (Double) -> Double) = Nutrients(
        kcal?.let(f), protein?.let(f), carbs?.let(f), sugars?.let(f), fat?.let(f), satFat?.let(f), fiber?.let(f), salt?.let(f),
    )

    private fun add(a: Double?, b: Double?): Double? = if (a == null) b else if (b == null) a else a + b

    companion object {
        val NONE = Nutrients()

        /** Sum of [items] (see [plus]). */
        fun sum(items: Iterable<Nutrients>): Nutrients = items.fold(NONE) { acc, n -> acc + n }
    }
}

/** The nutrients in label order; [code] is used in stored text (never change it). */
enum class Nutrient(val code: String) {
    KCAL("kcal"),
    PROTEIN("protein"),
    CARBS("carbs"),
    SUGARS("sugars"),
    FAT("fat"),
    SAT_FAT("sat_fat"),
    FIBER("fiber"),
    SALT("salt"),
    ;

    companion object {
        /** Shown first; the others under "More". */
        val MAIN = listOf(KCAL, PROTEIN, CARBS, FAT)
        val MORE = listOf(SUGARS, SAT_FAT, FIBER, SALT)
    }
}

/** Unit of a food's amounts: values are per 100 g, or per 100 ml for drinks. */
enum class FoodUnit(val code: String) {
    G("g"),
    ML("ml"),
    ;

    companion object {
        fun fromCode(code: String?): FoodUnit = if (code == ML.code) ML else G
    }
}

/** Food groups of the catalog (string `food_group_<code>`). */
enum class FoodGroup(val code: String) {
    FRUIT("fruit"),
    VEGETABLES("vegetables"),
    GRAINS("grains"),
    LEGUMES("legumes"),
    MEAT("meat"),
    FISH("fish"),
    EGGS("eggs"),
    DAIRY("dairy"),
    FATS_NUTS("fats_nuts"),
    SWEETS("sweets"),
    DRINKS("drinks"),
    DISHES("dishes"),
    FAST_FOOD("fast_food"),
    SNACKS("snacks"),
    SAUCES("sauces"),
    ;

    companion object {
        fun fromCode(code: String?): FoodGroup? = entries.firstOrNull { it.code == code }
    }
}

/** The fixed set of portion names of the catalog (string `food_portion_<code>`). */
enum class PortionKey(val code: String) {
    UNIT("unit"),
    PIECE("piece"),
    SLICE("slice"),
    PLATE("plate"),
    SMALL_PLATE("small_plate"),
    BOWL("bowl"),
    CUP("cup"),
    GLASS("glass"),
    CAN("can"),
    BOTTLE("bottle"),
    TBSP("tbsp"),
    TSP("tsp"),
    HANDFUL("handful"),
    SERVING("serving"),
    ;

    companion object {
        fun fromCode(code: String?): PortionKey? = entries.firstOrNull { it.code == code }
    }
}

/** One portion of a food: [amount] grams (or ml). */
data class Portion(val key: PortionKey, val amount: Double)

/** A food of the offline catalog (`assets/food/foods.tsv`); its name is the string `food_<id>`. */
data class CatalogFood(
    val id: String,
    val group: FoodGroup?,
    val unit: FoodUnit,
    val per100: Nutrients,
    /** Default amount in g / ml, null when the file gives none. */
    val portion: Double?,
    val portions: List<Portion>,
    /** The recognizer can propose it from a photo. */
    val visual: Boolean,
    val source: String?,
)

/** Where a custom food comes from: a product read from a barcode (Open Food Facts) or the user's own. */
enum class CustomFoodSource(val code: String) {
    OFF("off"),
    USER("user"),
    ;

    companion object {
        fun fromCode(code: String?): CustomFoodSource = if (code == OFF.code) OFF else USER
    }
}

/** A food the user created, or a packaged product found by its barcode (cached for reuse). */
data class CustomFood(
    val id: String,
    val name: String,
    val brand: String?,
    val barcode: String?,
    val unit: FoodUnit,
    val per100: Nutrients,
    /** Default amount (g / ml): the product's serving, or the amount the user first logged. */
    val portion: Double?,
    val source: CustomFoodSource,
    val createdAt: Long,
    val updatedAt: Long,
)

/** What an entry was logged from: a catalog food or a custom food (a quick manual entry has none). */
sealed interface FoodRef {
    data class Catalog(val id: String) : FoodRef

    data class Custom(val id: String) : FoodRef

    companion object {
        fun of(foodId: String?, customFoodId: String?): FoodRef? = when {
            foodId != null -> Catalog(foodId)
            customFoodId != null -> Custom(customFoodId)
            else -> null
        }
    }
}

/**
 * A food as the screens offer it, whatever it comes from: a catalog food (name in the current
 * language), a custom food, or a quick manual one ([ref] null, nothing stored).
 */
data class FoodItem(
    val ref: FoodRef?,
    val name: String,
    val brand: String?,
    val unit: FoodUnit,
    val per100: Nutrients,
    val portion: Double?,
    val portions: List<Portion>,
    val group: FoodGroup?,
    /** A product of Open Food Facts (its data needs the ODbL attribution). */
    val fromOpenFoodFacts: Boolean = false,
    val barcode: String? = null,
) {
    val foodId: String? get() = (ref as? FoodRef.Catalog)?.id
    val customFoodId: String? get() = (ref as? FoodRef.Custom)?.id

    companion object {
        fun of(food: CatalogFood, name: String) = FoodItem(
            ref = FoodRef.Catalog(food.id),
            name = name,
            brand = null,
            unit = food.unit,
            per100 = food.per100,
            portion = food.portion,
            portions = food.portions,
            group = food.group,
        )

        /** A product's serving (or the amount first logged) is offered as one portion. */
        fun of(food: CustomFood) = FoodItem(
            ref = FoodRef.Custom(food.id),
            name = food.name,
            brand = food.brand,
            unit = food.unit,
            per100 = food.per100,
            portion = food.portion,
            portions = listOfNotNull(food.portion?.let { Portion(PortionKey.SERVING, it) }),
            group = null,
            fromOpenFoodFacts = food.source == CustomFoodSource.OFF,
            barcode = food.barcode,
        )
    }
}

/** Meals of the day, in display order; [code] is stored (never change it). */
enum class Meal(val code: String) {
    BREAKFAST("breakfast"),
    LUNCH("lunch"),
    SNACK("snack"),
    DINNER("dinner"),
    OTHER("other"),
    ;

    companion object {
        fun fromCode(code: String?): Meal = entries.firstOrNull { it.code == code } ?: OTHER
    }
}

/** How an entry was added; [code] is stored (never change it). */
enum class EntrySource(val code: String) {
    PHOTO("photo"),
    BARCODE("barcode"),
    SEARCH("search"),
    RECENT("recent"),
    MANUAL("manual"),
    ;

    companion object {
        fun fromCode(code: String?): EntrySource = entries.firstOrNull { it.code == code } ?: MANUAL
    }
}

/**
 * One food eaten. [nutrients] is a snapshot of the values for [amount] (absolute, not per 100), so
 * later changes of the catalog or of a custom food never rewrite what was logged.
 */
data class FoodEntry(
    val id: String,
    /** When it was eaten (epoch ms) and the UTC offset of that moment where it was eaten. */
    val eatenAt: Long,
    val zoneOffsetSeconds: Int,
    val meal: Meal,
    /** The catalog food, the custom food, or neither (a quick manual entry). */
    val foodId: String?,
    val customFoodId: String?,
    /** The food's name when it was logged, in the language of that moment. */
    val name: String,
    val amount: Double,
    val unit: FoodUnit,
    val portionKey: PortionKey?,
    val portionCount: Double?,
    val nutrients: Nutrients,
    val source: EntrySource,
    /** The meal photo's file name (on this phone only, never uploaded). */
    val photo: String?,
    /** What the recognizer ranked first and how sure it was, for later quality stats. */
    val modelLabel: String?,
    val modelConfidence: Double?,
    val createdAt: Long,
    val updatedAt: Long,
) {
    /** The calendar day it belongs to: the local date where it was eaten. */
    val localDate: LocalDate
        get() = Instant.ofEpochMilli(eatenAt).atOffset(ZoneOffset.ofTotalSeconds(zoneOffsetSeconds)).toLocalDate()
}

/** Kinds of food tombstones (the deletions the cloud backup propagates). */
object FoodTombstones {
    const val ENTRY = "food_entry"
    const val CUSTOM_FOOD = "custom_food"
}
