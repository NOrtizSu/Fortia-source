package com.enjoyyourtime.globalfitness.food.catalog

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import com.enjoyyourtime.globalfitness.food.model.CatalogFood
import com.enjoyyourtime.globalfitness.food.model.FoodGroup
import com.enjoyyourtime.globalfitness.food.model.FoodItem
import com.enjoyyourtime.globalfitness.food.model.RecipeIngredient
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.util.Locale

/**
 * The offline food catalog (`assets/food/foods.tsv`) and the ingredients of its dishes
 * (`assets/food/recipes.tsv`, optional), read on first use off the main thread. Names are the string
 * resources `food_<id>` (and `food_group_<group>`) in the current language, found by name so the
 * catalog file and its strings can grow without code changes (res/raw/food_keep.xml keeps them
 * through resource shrinking).
 */
class FoodCatalog(private val context: Context, private val io: CoroutineDispatcher = Dispatchers.IO) {
    private val mutex = Mutex()

    @Volatile
    private var foods: Map<String, CatalogFood>? = null

    @Volatile
    private var recipes: Map<String, List<RecipeIngredient>>? = null

    @Volatile
    private var named: Pair<String, List<FoodItem>>? = null

    private val nameIds = HashMap<String, Int>()

    /** Every catalog food by id (empty when the file cannot be read). */
    suspend fun foods(): Map<String, CatalogFood> {
        foods?.let { return it }
        return mutex.withLock {
            foods ?: withContext(io) {
                try {
                    context.assets.open(ASSET).bufferedReader(Charsets.UTF_8).use { FoodCatalogParser.parse(it.readText()) }
                } catch (e: Exception) {
                    Log.w(TAG, "Food catalog not readable", e)
                    emptyList()
                }.associateBy { it.id }
            }.also { foods = it }
        }
    }

    /**
     * The ingredients of every dish that has them, by dish id (only foods of the catalog; empty when
     * the app ships no recipes).
     */
    suspend fun recipes(): Map<String, List<RecipeIngredient>> {
        recipes?.let { return it }
        val all = foods()
        return mutex.withLock {
            recipes ?: withContext(io) {
                try {
                    context.assets.open(RECIPES).bufferedReader(Charsets.UTF_8).use { RecipeParser.parse(it.readText(), all::containsKey) }
                } catch (e: FileNotFoundException) {
                    emptyMap()
                } catch (e: Exception) {
                    Log.w(TAG, "Food recipes not readable", e)
                    emptyMap()
                }
            }.also { recipes = it }
        }
    }

    /** The catalog as items named in the current language (cached per language). */
    suspend fun items(): List<FoodItem> {
        val language = Locale.getDefault().toLanguageTag()
        named?.takeIf { it.first == language }?.let { return it.second }
        val all = foods()
        val list = withContext(io) { all.values.map { FoodItem.of(it, name(it.id)) } }
        named = language to list
        return list
    }

    /** The name of catalog food [id] in the current language (a readable form of the id if it has none). */
    fun name(id: String): String = localName(id) ?: id.replace('_', ' ').replaceFirstChar { it.titlecase() }

    /** The name of catalog food [id] in the current language, or null when the catalog has no such food (any more). */
    fun localName(id: String): String? {
        val res = resId("food_$id")
        return if (res != 0) context.getString(res) else null
    }

    fun groupName(group: FoodGroup): String {
        val res = resId("food_group_${group.code}")
        return if (res != 0) context.getString(res) else group.code.replace('_', ' ')
    }

    @SuppressLint("DiscouragedApi")
    private fun resId(name: String): Int = synchronized(nameIds) {
        nameIds.getOrPut(name) { context.resources.getIdentifier(name, "string", context.packageName) }
    }

    companion object {
        private const val TAG = "FoodCatalog"
        private const val ASSET = "food/foods.tsv"
        private const val RECIPES = "food/recipes.tsv"

        /**
         * The variants offered with catalog food [id]: its base first (the "typical" one), then the
         * base's variants in the catalog's order. Empty when the food has none.
         */
        fun variantsOf(id: String, foods: Map<String, CatalogFood>): List<CatalogFood> {
            val food = foods[id] ?: return emptyList()
            val base = food.variantOf?.let(foods::get) ?: food
            val variants = foods.values.filter { it.variantOf == base.id }
            return if (variants.isEmpty()) emptyList() else listOf(base) + variants
        }
    }
}
