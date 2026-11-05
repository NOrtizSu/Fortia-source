package com.enjoyyourtime.globalfitness.food.pack

import com.enjoyyourtime.globalfitness.food.off.OffHit
import com.enjoyyourtime.globalfitness.food.off.SearchResult
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.Normalizer
import java.util.Locale

/**
 * Searches Open Food Facts by the words read on a package within its limits (10 searches a minute per
 * address): one search per user action, the same words again within [CACHE_MS] from memory, at most
 * [MAX_PER_MINUTE] searches in any minute, and none for [BACKOFF_MS] after "too many" (429).
 *
 * Two services: [live] (`/cgi/search.pl`, current data) and [index] (search-a-licious, a few months
 * behind, fast). Measured on 2026-11-04, `/cgi/search.pl` answered half the requests with a 503 page
 * ("temporarily unavailable … to anonymous users"): after a 503 or an answer that is not JSON it is left
 * alone for [LIVE_DOWN_MS] (search-a-licious first, one request instead of two), after a 429 for
 * [BACKOFF_MS]. Calls wait for each other (a second tap while one runs gets its answer). [clock] is
 * monotonic (elapsed milliseconds).
 */
class PackSearch(
    private val live: suspend (words: String, language: String) -> SearchResult,
    private val index: suspend (words: String, language: String) -> SearchResult,
    private val clock: () -> Long,
) {
    private val mutex = Mutex()
    private val cache = LinkedHashMap<String, Pair<Long, SearchResult.Hits>>()
    private val sent = ArrayDeque<Long>()
    private var blockedUntil = Long.MIN_VALUE
    private var liveAvoidedUntil = Long.MIN_VALUE

    suspend fun search(words: String, language: String): SearchResult = mutex.withLock {
        val now = clock()
        val key = "$language|${words.trim().lowercase()}"
        cache[key]?.let { (at, hits) -> if (now - at < CACHE_MS) return@withLock hits else cache.remove(key) }
        while (sent.isNotEmpty() && now - sent.first() >= MINUTE_MS) sent.removeFirst()
        if (now < blockedUntil || sent.size >= MAX_PER_MINUTE) return@withLock SearchResult.Busy
        sent.addLast(now)
        var result: SearchResult? = null
        if (now >= liveAvoidedUntil) {
            val answer = live(words, language)
            when (answer) {
                is SearchResult.Hits, SearchResult.Offline -> result = answer
                SearchResult.Busy -> liveAvoidedUntil = clock() + BACKOFF_MS
                is SearchResult.Failed -> liveAvoidedUntil = clock() + LIVE_DOWN_MS
            }
            if (result == null) {
                // Live search busy or down: the search service once; its own failure is not worse news.
                result = index(words, language).takeIf { it is SearchResult.Hits } ?: answer
            }
        }
        val found = result ?: index(words, language)
        when (found) {
            is SearchResult.Hits -> {
                cache[key] = clock() to found
                while (cache.size > CACHE_SIZE) cache.remove(cache.keys.first())
            }
            SearchResult.Busy -> blockedUntil = clock() + BACKOFF_MS
            else -> Unit
        }
        found
    }

    companion object {
        const val MAX_PER_MINUTE = 5
        const val BACKOFF_MS = 60_000L
        const val LIVE_DOWN_MS = 10 * 60_000L
        const val CACHE_MS = 30 * 60_000L
        private const val CACHE_SIZE = 16
        private const val MINUTE_MS = 60_000L

        /** Products shown for one search. */
        const val SHOWN = 8

        /** Open Food Facts' tags that are not the country's English name as Java/ICU writes it. */
        private val COUNTRY_TAGS = mapOf(
            "TR" to "en:turkey",
            "CZ" to "en:czech-republic",
            "HK" to "en:hong-kong",
            "MO" to "en:macau",
            "MM" to "en:myanmar",
            "CD" to "en:democratic-republic-of-the-congo",
            "CG" to "en:republic-of-the-congo",
            "KP" to "en:north-korea",
            "PS" to "en:state-of-palestine",
            "VA" to "en:vatican-city",
            "CI" to "en:cote-d-ivoire",
            "CV" to "en:cape-verde",
            "SZ" to "en:eswatini",
        )

        /** [hits] with the products sold in [country] (an Open Food Facts tag, "en:spain") first, otherwise in their order; at most [max]. */
        fun ranked(hits: List<OffHit>, country: String?, max: Int = SHOWN): List<OffHit> {
            val sorted = if (country == null) hits else hits.sortedBy { if (country in it.countries) 0 else 1 }
            return sorted.take(max)
        }

        /** The Open Food Facts tag of [locale]'s country ("ES" → "en:spain"), null without a country. */
        fun countryTag(locale: Locale): String? {
            val code = locale.country.takeIf { it.length == 2 }?.uppercase(Locale.ROOT) ?: return null
            COUNTRY_TAGS[code]?.let { return it }
            val name = Locale("", code).getDisplayCountry(Locale.ENGLISH).takeIf { it.isNotBlank() && it != code } ?: return null
            // "Bosnia & Herzegovina" → bosnia-and-herzegovina, "Réunion" → reunion, "St. Lucia" → saint-lucia.
            val plain = Normalizer.normalize(name, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
                .replace("&", " and ").replace(Regex("\\bSt\\.\\s*"), "Saint ")
            return "en:" + plain.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "-").trim('-')
        }
    }
}
