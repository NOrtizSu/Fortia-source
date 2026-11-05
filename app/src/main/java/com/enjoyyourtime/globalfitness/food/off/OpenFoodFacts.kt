package com.enjoyyourtime.globalfitness.food.off

import com.enjoyyourtime.globalfitness.food.barcode.BarcodeReader
import com.enjoyyourtime.globalfitness.food.model.FoodUnit
import com.enjoyyourtime.globalfitness.food.model.Nutrient
import com.enjoyyourtime.globalfitness.food.model.Nutrients
import com.enjoyyourtime.globalfitness.wearable.banglejs.MiniJson
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URISyntaxException
import java.net.URL
import java.net.URLEncoder
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
    /**
     * The photo of the front of the package (about 400 px) in the user's language, else in the
     * product's main one; null when Open Food Facts has none.
     */
    val imageUrl: String? = null,
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
 * A product of a search by words (the text read on a package): enough to recognise it in a list. Its
 * values come from the product read by [barcode] once the user picks it, like a barcode lookup.
 */
data class OffHit(
    val barcode: String,
    val name: String,
    val brand: String?,
    /** As the label says it ("200 g", "6 x 33 cl"). */
    val quantity: String?,
    /** The small photo of the front (shown in the list, never stored), only from Open Food Facts' servers. */
    val imageUrl: String?,
    /** Where it is sold, as Open Food Facts tags ("en:spain"). */
    val countries: List<String> = emptyList(),
)

/** What a search by words gave. */
sealed interface SearchResult {
    /** The products found (maybe none). */
    data class Hits(val hits: List<OffHit>) : SearchResult

    /** No connection (or no answer in time). */
    data object Offline : SearchResult

    /** Too many searches: Open Food Facts said so (429), or the app holds them back for a minute. */
    data object Busy : SearchResult

    data class Failed(val reason: String) : SearchResult
}

/** What downloading a product's photo gave. */
sealed interface PhotoResult {
    class Photo(val bytes: ByteArray) : PhotoResult

    /** Open Food Facts no longer has it (404, 410): the product has no photo until it gives another address. */
    data object Gone : PhotoResult

    /**
     * No connection, no answer in time, not an image, too large, another server: tried again later.
     * [reason] says which, for the log (never the address).
     */
    data class Failed(val reason: String) : PhotoResult
}

/**
 * Open Food Facts (open database, ODbL; product images CC BY-SA; both by its contributors), API v2
 * product endpoint and its image server. Only the barcode is sent, then the photo's address: no
 * account, no identifiers, a descriptive User-Agent as the project asks. [parse], [imageOf] and
 * [photoOf] are pure (unit-tested with fixtures).
 */
class OpenFoodFacts(
    private val userAgent: String,
    private val http: (url: String, userAgent: String) -> HttpAnswer = ::get,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val imageHttp: (url: String, userAgent: String) -> ImageAnswer = ::getImage,
) {
    /** Status code and body of a GET (body null when it could not be read). */
    data class HttpAnswer(val code: Int, val body: String?)

    /**
     * Status code, content type and body of a GET of an image (body null when too large or not read),
     * and where a redirect points ([location], as the server wrote it).
     */
    class ImageAnswer(val code: Int, val contentType: String?, val body: ByteArray?, val location: String? = null)

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

    /**
     * Products matching [words] (the words read on a package; only they and the [language] are sent) in
     * Open Food Facts' full-text search (`/cgi/search.pl`, live data; a 503 page when busy, 429 over its
     * limit). The caller keeps to Open Food Facts' limits and picks the service
     * ([com.enjoyyourtime.globalfitness.food.pack.PackSearch]).
     */
    suspend fun searchLive(words: String, language: String): SearchResult = withContext(io) {
        if (words.isBlank()) SearchResult.Hits(emptyList()) else fetchSearch(searchUrl(words, language), language)
    }

    /** The same search on Open Food Facts' search service (search-a-licious: data a few months old). */
    suspend fun searchIndex(words: String, language: String): SearchResult = withContext(io) {
        if (words.isBlank()) SearchResult.Hits(emptyList()) else fetchSearch(fallbackSearchUrl(words, language), language)
    }

    private fun fetchSearch(url: String, language: String): SearchResult {
        val answer = try {
            http(url, userAgent)
        } catch (e: IOException) {
            return SearchResult.Offline
        }
        return when {
            answer.code == HTTP_TOO_MANY_REQUESTS -> SearchResult.Busy
            answer.code != HttpURLConnection.HTTP_OK || answer.body == null -> SearchResult.Failed("HTTP ${answer.code}")
            else -> parseSearch(answer.body, language)
        }
    }

    /**
     * The photo at [url] (an address [isImageUrl] accepts). At most [MAX_REDIRECTS] redirects are
     * followed, each only to an address [isImageUrl] accepts too (never to another server).
     */
    suspend fun image(url: String): PhotoResult = withContext(io) {
        var target = url
        repeat(MAX_REDIRECTS + 1) { hop ->
            if (!isImageUrl(target)) return@withContext PhotoResult.Failed(if (hop == 0) "not an Open Food Facts address" else "redirect to another server")
            val answer = try {
                imageHttp(target, userAgent)
            } catch (e: IOException) {
                return@withContext PhotoResult.Failed(e.javaClass.simpleName)
            }
            val next = answer.location?.takeIf { answer.code in REDIRECTS } ?: return@withContext photoOf(answer)
            target = resolve(target, next) ?: return@withContext PhotoResult.Failed("bad redirect")
        }
        PhotoResult.Failed("too many redirects")
    }

    companion object {
        const val BASE = "https://world.openfoodfacts.org/api/v2/product/"
        const val SEARCH_BASE = "https://world.openfoodfacts.org/cgi/search.pl"
        const val FALLBACK_SEARCH_BASE = "https://search.openfoodfacts.org/search"

        /** Products asked for per search (ranked for the user's country afterwards, a few shown). */
        const val SEARCH_PAGE = 24
        private const val HTTP_TOO_MANY_REQUESTS = 429
        private const val TIMEOUT_MS = 10_000
        private const val MAX_BYTES = 512 * 1024

        /** A product photo larger than this is not downloaded (Open Food Facts' 400 px ones take about 30 KB). */
        const val MAX_IMAGE_BYTES = 2 * 1024 * 1024

        /** Redirects followed for a photo (each to Open Food Facts' own servers only). */
        const val MAX_REDIRECTS = 2
        private val REDIRECTS = setOf(301, 302, 303, 307, 308)
        private val PNG = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A)
        private const val KJ_PER_KCAL = 4.184
        private const val SALT_PER_SODIUM = 2.5

        /**
         * Only the fields the app uses (name in the user's language with fallbacks, brand, sizes,
         * nutriments, the address of the front photo, whose language `lc` picks).
         */
        fun url(barcode: String, language: String): String {
            val lang = langOf(language)
            val fields = linkedSetOf(
                "code", "product_name_$lang", "product_name_en", "product_name", "generic_name_$lang", "brands", "quantity", "serving_quantity", "nutriments",
                "image_front_url", "image_front_small_url",
            )
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
                    // 400 px, else the 200 px one; only from Open Food Facts' own servers over HTTPS.
                    imageUrl = text(product["image_front_url"])?.takeIf(::isImageUrl) ?: text(product["image_front_small_url"])?.takeIf(::isImageUrl),
                ),
            )
        }

        /** Open Food Facts' language code: ISO 639-1, with Android's legacy "in" (Indonesian) as "id". */
        private fun langOf(language: String): String =
            language.lowercase().let { if (it == "in") "id" else it }.takeIf { it.length == 2 && it.all(Char::isLetter) } ?: "en"

        private fun searchFields(lang: String) =
            listOf("code", "product_name_$lang", "product_name", "brands", "quantity", "image_front_small_url", "countries_tags").distinct().joinToString(",")

        /** The full-text search of [words]: only the words, the language (for the names) and the fields the list shows. */
        fun searchUrl(words: String, language: String): String {
            val lang = langOf(language)
            val q = URLEncoder.encode(words.trim(), "UTF-8")
            return "$SEARCH_BASE?search_terms=$q&search_simple=1&action=process&json=1&page_size=$SEARCH_PAGE&fields=${searchFields(lang)}&lc=$lang"
        }

        /** The same search on search-a-licious (its query is Lucene syntax: only letters, digits and spaces are sent). */
        fun fallbackSearchUrl(words: String, language: String): String {
            val lang = langOf(language)
            val plain = words.replace(Regex("[^\\p{L}\\p{N}' ]"), " ").replace(Regex("\\s+"), " ").trim()
            val langs = listOf(lang, "en").distinct().joinToString(",")
            return "$FALLBACK_SEARCH_BASE?q=${URLEncoder.encode(plain, "UTF-8")}&langs=$langs&page_size=$SEARCH_PAGE&fields=${searchFields(lang)}"
        }

        /**
         * The products of a search answer (`products` of `/cgi/search.pl`, `hits` of search-a-licious),
         * each once, in the order given; those without a barcode or a name are left out.
         */
        fun parseSearch(json: String, language: String): SearchResult {
            val root = try {
                MiniJson.parse(json) as? Map<*, *>
            } catch (e: IllegalArgumentException) {
                null
            } ?: return SearchResult.Failed("bad answer")
            val list = (root["products"] ?: root["hits"]) as? List<*> ?: return SearchResult.Failed("bad answer")
            val lang = langOf(language)
            val seen = HashSet<String>()
            val hits = list.mapNotNull { item ->
                val p = item as? Map<*, *> ?: return@mapNotNull null
                val code = text(p["code"])?.takeIf { c -> c.length in 8..14 && c.all(Char::isDigit) } ?: return@mapNotNull null
                if (!seen.add(BarcodeReader.canonical(code))) return@mapNotNull null
                val name = text(p["product_name_$lang"]) ?: text(p["product_name"]) ?: return@mapNotNull null
                val brands = when (val b = p["brands"]) {
                    is List<*> -> b.mapNotNull(::text)
                    else -> text(b)?.split(',').orEmpty().map { it.trim() }
                }
                OffHit(
                    barcode = code,
                    name = name,
                    brand = brands.firstOrNull { it.isNotEmpty() },
                    quantity = text(p["quantity"]),
                    imageUrl = text(p["image_front_small_url"])?.takeIf(::isImageUrl),
                    countries = (p["countries_tags"] as? List<*>).orEmpty().mapNotNull(::text),
                )
            }
            return SearchResult.Hits(hits)
        }

        /** An address of Open Food Facts' own servers over HTTPS: the photo is fetched from nowhere else. */
        fun isImageUrl(url: String): Boolean {
            val uri = try {
                URI(url)
            } catch (e: URISyntaxException) {
                return false
            }
            val host = uri.host?.lowercase() ?: return false
            return uri.scheme == "https" && uri.rawUserInfo == null && (uri.port == -1 || uri.port == 443) &&
                (host == "openfoodfacts.org" || host.endsWith(".openfoodfacts.org"))
        }

        /** The body of [answer] when it is a JPEG, PNG or WebP image (by its type and first bytes) of at most [MAX_IMAGE_BYTES]. */
        fun imageOf(answer: ImageAnswer): ByteArray? {
            val body = answer.body ?: return null
            val type = answer.contentType?.substringBefore(';')?.trim()?.lowercase() ?: return null
            if (answer.code != HttpURLConnection.HTTP_OK || !type.startsWith("image/")) return null
            if (body.isEmpty() || body.size > MAX_IMAGE_BYTES) return null
            val jpeg = body.size >= 3 && body[0] == 0xFF.toByte() && body[1] == 0xD8.toByte() && body[2] == 0xFF.toByte()
            val png = body.size >= PNG.size && body.copyOfRange(0, PNG.size).contentEquals(PNG)
            val webp = body.size >= 12 && String(body, 0, 4, Charsets.US_ASCII) == "RIFF" && String(body, 8, 4, Charsets.US_ASCII) == "WEBP"
            return body.takeIf { jpeg || png || webp }
        }

        /** [answer] as a photo ([imageOf]), gone for good (404, 410) or failed. */
        fun photoOf(answer: ImageAnswer): PhotoResult = when (answer.code) {
            HttpURLConnection.HTTP_NOT_FOUND, HttpURLConnection.HTTP_GONE -> PhotoResult.Gone
            HttpURLConnection.HTTP_OK -> imageOf(answer)?.let(PhotoResult::Photo)
                ?: PhotoResult.Failed(if (answer.body == null) "too large or unreadable" else "not an image")
            else -> PhotoResult.Failed("HTTP ${answer.code}")
        }

        /** A redirect's [location] (maybe relative) from [base]; null when it is not an address. */
        private fun resolve(base: String, location: String): String? = try {
            URI(base).resolve(location.trim()).toString()
        } catch (e: IllegalArgumentException) {
            null
        } catch (e: URISyntaxException) {
            null
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
                val body = stream?.use { readAtMost(it, MAX_BYTES) }?.toString(Charsets.UTF_8)
                return HttpAnswer(code, body)
            } finally {
                connection.disconnect()
            }
        }

        private fun getImage(url: String, userAgent: String): ImageAnswer {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("User-Agent", userAgent)
            connection.setRequestProperty("Accept", "image/jpeg,image/png,image/webp")
            try {
                val code = connection.responseCode
                val type = connection.contentType
                // A redirect is followed by image(), which checks where it leads first.
                if (code in REDIRECTS) return ImageAnswer(code, type, null, connection.getHeaderField("Location"))
                // Not read at all when it says it is too large.
                if (code != HttpURLConnection.HTTP_OK || connection.contentLengthLong > MAX_IMAGE_BYTES) return ImageAnswer(code, type, null)
                return ImageAnswer(code, type, connection.inputStream.use { readAtMost(it, MAX_IMAGE_BYTES) })
            } finally {
                connection.disconnect()
            }
        }

        /** The whole stream, or null as soon as it is longer than [max] bytes. */
        private fun readAtMost(input: InputStream, max: Int): ByteArray? {
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            var n = input.read(buffer)
            while (n >= 0) {
                out.write(buffer, 0, n)
                if (out.size() > max) return null
                n = input.read(buffer)
            }
            return out.toByteArray()
        }
    }
}
