package com.enjoyyourtime.globalfitness.food.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The eight nutrients of the EU nutrition label and fourteen more (fats in detail, cholesterol,
 * minerals, vitamins; see [Nutrient] for their units). Absolute values (an amount eaten) or per 100 g
 * / ml (a food); null when unknown (a missing value is never a zero).
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
    val monoFat: Double? = null,
    val polyFat: Double? = null,
    val cholesterol: Double? = null,
    val potassium: Double? = null,
    val calcium: Double? = null,
    val iron: Double? = null,
    val magnesium: Double? = null,
    val zinc: Double? = null,
    val vitA: Double? = null,
    val vitC: Double? = null,
    val vitD: Double? = null,
    val vitE: Double? = null,
    val vitB12: Double? = null,
    val folate: Double? = null,
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
        Nutrient.MONO_FAT -> monoFat
        Nutrient.POLY_FAT -> polyFat
        Nutrient.CHOLESTEROL -> cholesterol
        Nutrient.POTASSIUM -> potassium
        Nutrient.CALCIUM -> calcium
        Nutrient.IRON -> iron
        Nutrient.MAGNESIUM -> magnesium
        Nutrient.ZINC -> zinc
        Nutrient.VIT_A -> vitA
        Nutrient.VIT_C -> vitC
        Nutrient.VIT_D -> vitD
        Nutrient.VIT_E -> vitE
        Nutrient.VIT_B12 -> vitB12
        Nutrient.FOLATE -> folate
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
        Nutrient.MONO_FAT -> copy(monoFat = value)
        Nutrient.POLY_FAT -> copy(polyFat = value)
        Nutrient.CHOLESTEROL -> copy(cholesterol = value)
        Nutrient.POTASSIUM -> copy(potassium = value)
        Nutrient.CALCIUM -> copy(calcium = value)
        Nutrient.IRON -> copy(iron = value)
        Nutrient.MAGNESIUM -> copy(magnesium = value)
        Nutrient.ZINC -> copy(zinc = value)
        Nutrient.VIT_A -> copy(vitA = value)
        Nutrient.VIT_C -> copy(vitC = value)
        Nutrient.VIT_D -> copy(vitD = value)
        Nutrient.VIT_E -> copy(vitE = value)
        Nutrient.VIT_B12 -> copy(vitB12 = value)
        Nutrient.FOLATE -> copy(folate = value)
    }

    /** Every known value times [factor] (unknown ones stay unknown). */
    fun scaled(factor: Double): Nutrients = of { n -> get(n)?.let { it * factor } }

    /** Sum of the known values; a nutrient is unknown only when it is unknown on both sides. */
    operator fun plus(other: Nutrients): Nutrients = of { n -> add(get(n), other[n]) }

    val isEmpty: Boolean get() = Nutrient.entries.all { get(it) == null }

    /** Some value beyond the eight of the label is known (older app versions cannot keep those). */
    val hasExtra: Boolean get() = Nutrient.EXTRA.any { get(it) != null }

    private fun add(a: Double?, b: Double?): Double? = if (a == null) b else if (b == null) a else a + b

    companion object {
        val NONE = Nutrients()

        /** Sum of [items] (see [plus]). */
        fun sum(items: Iterable<Nutrients>): Nutrients = items.fold(NONE) { acc, n -> acc + n }

        /** The nutrients whose value is [value] of each. */
        inline fun of(value: (Nutrient) -> Double?): Nutrients {
            var out = NONE
            for (n in Nutrient.entries) value(n)?.let { out = out.with(n, it) }
            return out
        }
    }
}

/** Unit of a nutrient's values. */
enum class NutrientUnit { KCAL, G, MG, UG }

/**
 * The nutrients: the label's eight first (label order), then the others. [code] is the column of
 * foods.tsv and of food.db, and is used in stored text (never change it); [field] names it in the
 * cloud backup. Vitamin A in µg RAE, folate in µg DFE.
 */
enum class Nutrient(val code: String, val field: String, val unit: NutrientUnit) {
    KCAL("kcal", "kcal", NutrientUnit.KCAL),
    PROTEIN("protein", "protein", NutrientUnit.G),
    CARBS("carbs", "carbs", NutrientUnit.G),
    SUGARS("sugars", "sugars", NutrientUnit.G),
    FAT("fat", "fat", NutrientUnit.G),
    SAT_FAT("sat_fat", "satFat", NutrientUnit.G),
    FIBER("fiber", "fiber", NutrientUnit.G),
    SALT("salt", "salt", NutrientUnit.G),
    MONO_FAT("mono_fat", "monoFat", NutrientUnit.G),
    POLY_FAT("poly_fat", "polyFat", NutrientUnit.G),
    CHOLESTEROL("cholesterol", "cholesterol", NutrientUnit.MG),
    POTASSIUM("potassium", "potassium", NutrientUnit.MG),
    CALCIUM("calcium", "calcium", NutrientUnit.MG),
    IRON("iron", "iron", NutrientUnit.MG),
    MAGNESIUM("magnesium", "magnesium", NutrientUnit.MG),
    ZINC("zinc", "zinc", NutrientUnit.MG),
    VIT_A("vit_a", "vitA", NutrientUnit.UG),
    VIT_C("vit_c", "vitC", NutrientUnit.MG),
    VIT_D("vit_d", "vitD", NutrientUnit.UG),
    VIT_E("vit_e", "vitE", NutrientUnit.MG),
    VIT_B12("vit_b12", "vitB12", NutrientUnit.UG),
    FOLATE("folate", "folate", NutrientUnit.UG),
    ;

    companion object {
        /** Shown first; the others under "More". */
        val MAIN = listOf(KCAL, PROTEIN, CARBS, FAT)
        val MORE = listOf(SUGARS, SAT_FAT, FIBER, SALT)

        /**
         * The eight values of the EU nutrition label (what a product page gives, what 1.5.0 stored), in
         * label order: the order of food.db's columns and of the backup's version hash.
         */
        val LABEL = listOf(KCAL, PROTEIN, CARBS, SUGARS, FAT, SAT_FAT, FIBER, SALT)

        /** The values beyond the label, in the groups the screens show them in. */
        val FATS = listOf(MONO_FAT, POLY_FAT, CHOLESTEROL)
        val MINERALS = listOf(POTASSIUM, CALCIUM, IRON, MAGNESIUM, ZINC)
        val VITAMINS = listOf(VIT_A, VIT_C, VIT_D, VIT_E, VIT_B12, FOLATE)
        val EXTRA = FATS + MINERALS + VITAMINS
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
    /** The base food this one is a variant of (paella_seafood → paella), null for a base or a plain food. */
    val variantOf: String? = null,
)

/**
 * One ingredient of a dish (`assets/food/recipes.tsv`): [grams] of catalog food [ingredient] (in its
 * own unit: ml for a drink) in 100 g (or 100 ml) of the finished dish. [adjustable]: the user may
 * change it when logging (oil, bread, rice, cheese, sauce…), not water, salt or spices.
 */
data class RecipeIngredient(val ingredient: String, val grams: Double, val adjustable: Boolean)

/** The amounts an ingredient can be changed to, as a factor of its normal amount ([code] is stored). */
enum class AdjustLevel(val code: String, val factor: Double) {
    NONE("none", 0.0),
    HALF("half", 0.5),
    NORMAL("normal", 1.0),
    ONE_AND_A_HALF("one_half", 1.5),
    DOUBLE("double", 2.0),
    ;

    companion object {
        fun ofFactor(factor: Double?): AdjustLevel? = entries.firstOrNull { factor != null && kotlin.math.abs(it.factor - factor) < 1e-9 }
    }
}

/**
 * An ingredient of a logged dish changed by the user: [grams] of [ingredient] instead of the
 * [baseGrams] the recipe puts in the amount logged. [factor] when chosen as a level (½, double…),
 * null when typed in grams (the grams then stay when the dish amount changes).
 */
data class IngredientAdjustment(val ingredient: String, val baseGrams: Double, val grams: Double, val factor: Double?) {
    /** How much the logged weight changes (g, or ml for a drink). */
    val delta: Double get() = grams - baseGrams
}

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
    /** A catalog variant: the id of its base food. */
    val variantOf: String? = null,
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
            variantOf = food.variantOf,
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
    /**
     * The dish's ingredients the user changed (none for a food logged as it is); [nutrients] and
     * [amount] already include them. Kept to edit them again later.
     */
    val adjustments: List<IngredientAdjustment> = emptyList(),
    /** The grams (ml) the photo suggested, whatever the user then logged (for later quality stats). */
    val estimatedAmount: Double? = null,
) {
    /** The amount of the dish itself, before its ingredients were changed. */
    val dishAmount: Double get() = amount - adjustments.sumOf { it.delta }

    /** Holds data an app older than 1.6.0 cannot keep (values beyond the label, changed ingredients). */
    val hasExtendedData: Boolean get() = nutrients.hasExtra || adjustments.isNotEmpty()

    /** The calendar day it belongs to: the local date where it was eaten. */
    val localDate: LocalDate
        get() = Instant.ofEpochMilli(eatenAt).atOffset(ZoneOffset.ofTotalSeconds(zoneOffsetSeconds)).toLocalDate()
}

/** Kinds of food tombstones (the deletions the cloud backup propagates). */
object FoodTombstones {
    const val ENTRY = "food_entry"
    const val CUSTOM_FOOD = "custom_food"
}
