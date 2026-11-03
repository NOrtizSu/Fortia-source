package com.enjoyyourtime.globalfitness.food.off

import com.enjoyyourtime.globalfitness.food.model.FoodUnit
import com.enjoyyourtime.globalfitness.food.model.Nutrient
import com.enjoyyourtime.globalfitness.food.model.Nutrients
import com.enjoyyourtime.globalfitness.wearable.banglejs.MiniJson
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import kotlin.math.roundToLong

/** A packaged product as Open Food Facts describes it (values per 100 g / ml). */
data class OffProduct(
    val barcode: String,
    val name: String?,
    val brand: String?,
    val unit: FoodUnit,
    val per100: Nutrients,
    /** The label's serving in g / ml, when given. */
    val serving: Double?,
) {
    /** Nutrients the product page does not give (the user may fill them in). */
    val missing: List<Nutrient> get() = Nutrient.LABEL.filter { per100[it] == null }
}

sealed interface OffResult {
    data class Found(val product: OffProduct) : OffResult

    data object NotFound : OffResult

    /** No connection (or Open Food Facts did not answer in time). */
    data object Offline : OffResult

    data class Failed(val reason: String) : OffResult
}

/**
 * Open Food Facts (open database, ODbL; product images and data by its contributors), API v2 product
 * endpoint. Only the barcode is sent: no account, no identifiers, a descriptive User-Agent as the
 * project asks. [parse] is pure (unit-tested with fixtures).
 */
class OpenFoodFacts(
    private val userAgent: String,
    private val http: (url: String, userAgent: String) -> HttpAnswer = ::get,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    /** Status code and body of a GET (body null when it could not be read). */
    data class HttpAnswer(val code: Int, val body: String?)

    /** The product with [barcode] in the user's [language] (two-letter code). */
    suspend fun product(barcode: String, language: String): OffResult = withContext(io) {
        if (barcode.isEmpty() || !barcode.all { it.isDigit() }) return@withContext OffResult.NotFound
        val answer = try {
            http(url(barcode, language), userAgent)
        } catch (e: UnknownHostException) {
            return@withContext OffResult.Offline
        } catch (e: SocketTimeoutException) {
            return@withContext OffResult.Offline
        } catch (e: IOException) {
            return@withContext OffResult.Offline
        }
        when {
            answer.code == HttpURLConnection.HTTP_NOT_FOUND -> OffResult.NotFound
            answer.code != HttpURLConnection.HTTP_OK || answer.body == null -> OffResult.Failed("HTTP ${answer.code}")
            else -> parse(answer.body, barcode, language)
        }
    }

    companion object {
        const val BASE = "https://world.openfoodfacts.org/api/v2/product/"
        private const val TIMEOUT_MS = 10_000
        private const val MAX_BYTES = 512 * 1024
        private const val KJ_PER_KCAL = 4.184
        private const val SALT_PER_SODIUM = 2.5

        /** Only the fields the app uses (name in the user's language with fallbacks, brand, sizes, nutriments). */
        fun url(barcode: String, language: String): String {
            val lang = language.lowercase().takeIf { it.length == 2 && it.all(Char::isLetter) } ?: "en"
            val fields = linkedSetOf("code", "product_name_$lang", "product_name_en", "product_name", "generic_name_$lang", "brands", "quantity", "serving_quantity", "nutriments")
            return "$BASE$barcode?fields=${fields.joinToString(",")}&lc=$lang"
        }

        /** The product in an API v2 answer, or [OffResult.NotFound] (status 0, no product). */
        fun parse(json: String, barcode: String, language: String): OffResult {
            val root = try {
                MiniJson.parse(json) as? Map<*, *>
            } catch (e: IllegalArgumentException) {
                null
            } ?: return OffResult.Failed("bad answer")
            val status = (root["status"] as? Number)?.toInt()
            val product = root["product"] as? Map<*, *>
            if (status == 0 || product == null) return OffResult.NotFound
            val lang = language.lowercase()
            val name = text(product["product_name_$lang"]) ?: text(product["product_name"]) ?: text(product["product_name_en"])
                ?: text(product["generic_name_$lang"])
            val brand = text(product["brands"])?.split(',')?.map { it.trim() }?.firstOrNull { it.isNotEmpty() }
            val n = product["nutriments"] as? Map<*, *> ?: emptyMap<String, Any?>()
            // energy_100g is always kJ (energy_unit describes the value as entered, not this field).
            val kcal = number(n["energy-kcal_100g"]) ?: number(n["energy_100g"])?.let { kj -> kj / KJ_PER_KCAL }
            val salt = number(n["salt_100g"]) ?: number(n["sodium_100g"])?.let { it * SALT_PER_SODIUM }
            val per100 = Nutrients(
                kcal = kcal?.let(::round0),
                protein = number(n["proteins_100g"])?.let(::round1),
                carbs = number(n["carbohydrates_100g"])?.let(::round1),
                sugars = number(n["sugars_100g"])?.let(::round1),
                fat = number(n["fat_100g"])?.let(::round1),
                satFat = number(n["saturated-fat_100g"])?.let(::round1),
                fiber = number(n["fiber_100g"])?.let(::round1),
                salt = salt?.let { (it * 100).roundToLong() / 100.0 },
            )
            val quantity = text(product["quantity"]).orEmpty().lowercase()
            val liquid = Regex("\\d\\s*(ml|cl|l)\\b").containsMatchIn(quantity)
            return OffResult.Found(
                OffProduct(
                    barcode = text(product["code"])?.takeIf { it.all(Char::isDigit) } ?: barcode,
                    name = name,
                    brand = brand,
                    unit = if (liquid) FoodUnit.ML else FoodUnit.G,
                    per100 = per100,
                    serving = number(product["serving_quantity"])?.takeIf { it > 0 && it < 5000 },
                ),
            )
        }

        private fun text(v: Any?): String? = (v as? String)?.trim()?.takeIf { it.isNotEmpty() }

        /** OFF gives numbers, sometimes as text ("12.5", "12,5"); negative or absurd values are unknown. */
        private fun number(v: Any?): Double? {
            val d = when (v) {
                is Number -> v.toDouble()
                is String -> v.trim().replace(',', '.').toDoubleOrNull()
                else -> null
            } ?: return null
            return d.takeIf { it.isFinite() && it >= 0 && it < 10_000 }
        }

        private fun round0(v: Double) = v.roundToLong().toDouble()

        private fun round1(v: Double) = (v * 10).roundToLong() / 10.0

        private fun get(url: String, userAgent: String): HttpAnswer {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = true
            connection.useCaches = false
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("User-Agent", userAgent)
            connection.setRequestProperty("Accept", "application/json")
            try {
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val body = stream?.use { input ->
                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(8 * 1024)
                    var n = input.read(buffer)
                    while (n >= 0) {
                        out.write(buffer, 0, n)
                        if (out.size() > MAX_BYTES) return HttpAnswer(code, null)
                        n = input.read(buffer)
                    }
                    out.toString(Charsets.UTF_8.name())
                }
                return HttpAnswer(code, body)
            } finally {
                connection.disconnect()
            }
        }
    }
}
