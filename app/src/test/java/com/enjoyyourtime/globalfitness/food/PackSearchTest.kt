package com.enjoyyourtime.globalfitness.food

import com.enjoyyourtime.globalfitness.food.off.OffHit
import com.enjoyyourtime.globalfitness.food.off.OpenFoodFacts
import com.enjoyyourtime.globalfitness.food.off.SearchResult
import com.enjoyyourtime.globalfitness.food.pack.PackSearch
import com.enjoyyourtime.globalfitness.food.pack.PackageText
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.URLDecoder
import java.util.Locale

/** The package search in Open Food Facts: what is sent, the two answer formats, errors, and its limits. */
class PackSearchTest {
    // Trimmed real answers (2026-11-04) of /cgi/search.pl and of search-a-licious for "galletas maria fontaneda".
    private val cgi = """
        {"count":4,"page":1,"page_count":4,"page_size":24,"skip":0,"products":[
          {"code":"8410000807363","product_name":"La Buena María","product_name_es":"La Buena María","brands":"Fontaneda, Mondelez","quantity":"800 g",
           "image_front_small_url":"https://images.openfoodfacts.org/images/products/841/000/080/7363/front_es.28.200.jpg","countries_tags":["en:france","en:spain"]},
          {"code":"8410000810004","product_name":"María Integral","brands":"Fontaneda","quantity":"500 g","countries_tags":["en:spain"],
           "image_front_small_url":"http://example.com/front.jpg"},
          {"code":"","product_name":"No code"},
          {"code":"8410000807363","product_name":"Twice"},
          {"code":"12345678901234567","product_name":"Too long"},
          {"code":"3017620422003"}
        ]}
    """.trimIndent()

    private val searchALicious = """
        {"hits":[{"code":"8410000807363","brands":["Fontaneda"],"quantity":"200g","countries_tags":["en:france","en:spain"],
          "nutriments":{"energy-kcal_100g":440},"product_name":"Galleta María Fontaneda",
          "image_front_small_url":"https://images.openfoodfacts.org/images/products/841/000/080/7363/front_es.28.200.jpg"}],
         "page":1,"page_size":24,"page_count":1,"count":1,"is_count_exact":true,"took":12,"timed_out":false}
    """.trimIndent()

    @Test
    fun onlyTheWordsAndTheLanguageAreSent() {
        val url = OpenFoodFacts.searchUrl("fontaneda buena maría", "es")
        assertTrue(url.startsWith("https://world.openfoodfacts.org/cgi/search.pl?"))
        val params = url.substringAfter('?').split('&').associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
        assertEquals("fontaneda buena maría", params["search_terms"])
        assertEquals("es", params["lc"])
        assertEquals("1", params["json"])
        assertEquals(setOf("search_terms", "search_simple", "action", "json", "page_size", "fields", "lc"), params.keys)
        assertTrue(params.getValue("fields").split(',').containsAll(listOf("code", "product_name_es", "brands", "quantity", "image_front_small_url", "countries_tags")))
        // The fallback: Lucene syntax, so only letters, digits and spaces.
        val fallback = OpenFoodFacts.fallbackSearchUrl("coca-cola zero: \"azúcar\"", "es")
        assertTrue(fallback.startsWith("https://search.openfoodfacts.org/search?"))
        val q = URLDecoder.decode(fallback.substringAfter("q=").substringBefore('&'), "UTF-8")
        assertEquals("coca cola zero azúcar", q)
        assertTrue(fallback.contains("langs=es%2Cen") || fallback.contains("langs=es,en"))
        // A language code that is not one.
        assertTrue(OpenFoodFacts.searchUrl("x", "../").endsWith("lc=en"))
    }

    @Test
    fun bothAnswerFormatsAreRead() {
        val hits = (OpenFoodFacts.parseSearch(cgi, "es") as SearchResult.Hits).hits
        // No code, a repeated code, a code too long, no name: left out.
        assertEquals(listOf("8410000807363", "8410000810004"), hits.map { it.barcode })
        val first = hits[0]
        assertEquals("La Buena María", first.name)
        assertEquals("Fontaneda", first.brand)
        assertEquals("800 g", first.quantity)
        assertTrue(first.imageUrl!!.startsWith("https://images.openfoodfacts.org/"))
        assertEquals(listOf("en:france", "en:spain"), first.countries)
        // A photo address outside Open Food Facts' servers is never fetched.
        assertNull(hits[1].imageUrl)

        val sal = (OpenFoodFacts.parseSearch(searchALicious, "es") as SearchResult.Hits).hits.single()
        assertEquals("Galleta María Fontaneda", sal.name)
        assertEquals("Fontaneda", sal.brand)

        assertTrue(OpenFoodFacts.parseSearch("<html>Page temporarily unavailable</html>", "es") is SearchResult.Failed)
        assertTrue(OpenFoodFacts.parseSearch("{\"count\":0}", "es") is SearchResult.Failed)
        assertEquals(SearchResult.Hits(emptyList()), OpenFoodFacts.parseSearch("{\"count\":0,\"products\":[]}", "es"))
    }

    private fun off(answers: Map<String, () -> OpenFoodFacts.HttpAnswer>, asked: MutableList<String>, dispatcher: TestDispatcher) =
        OpenFoodFacts(userAgent = "Fortia/test", http = { url, _ ->
            asked += url
            answers.entries.first { url.startsWith(it.key) }.value()
        }, io = dispatcher)

    @Test
    fun eachServiceAnswersAndFails() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val asked = ArrayList<String>()
        val off = off(
            mapOf(
                // The 503 HTML page ("not available to anonymous users") seen on 2026-11-04.
                OpenFoodFacts.SEARCH_BASE to { OpenFoodFacts.HttpAnswer(503, "<html>Page temporarily unavailable</html>") },
                OpenFoodFacts.FALLBACK_SEARCH_BASE to { OpenFoodFacts.HttpAnswer(200, searchALicious) },
            ),
            asked, dispatcher,
        )
        assertTrue(off.searchLive("galletas maria fontaneda", "es") is SearchResult.Failed)
        assertEquals("8410000807363", (off.searchIndex("galletas maria fontaneda", "es") as SearchResult.Hits).hits.single().barcode)
        assertEquals(2, asked.size)

        val limited = off(mapOf(OpenFoodFacts.SEARCH_BASE to { OpenFoodFacts.HttpAnswer(429, null) }), asked, dispatcher)
        assertEquals(SearchResult.Busy, limited.searchLive("galletas", "es"))

        val offline = off(mapOf("https://" to { throw IOException("no network") }), asked, dispatcher)
        assertEquals(SearchResult.Offline, offline.searchLive("galletas", "es"))

        val ok = off(mapOf(OpenFoodFacts.SEARCH_BASE to { OpenFoodFacts.HttpAnswer(200, cgi) }), asked, dispatcher)
        assertEquals(2, (ok.searchLive("fontaneda", "es") as SearchResult.Hits).hits.size)

        // No words: nothing sent.
        asked.clear()
        assertEquals(SearchResult.Hits(emptyList()), ok.searchLive("  ", "es"))
        assertTrue(asked.isEmpty())
    }

    /** The two services as [PackSearch] calls them: what each answers, and what was sent to each. */
    private class Services(var live: SearchResult = SearchResult.Hits(emptyList()), var index: SearchResult = SearchResult.Hits(emptyList())) {
        val sent = ArrayList<String>()

        fun search(clock: () -> Long) = PackSearch(
            live = { words, _ -> sent += "live:$words"; live },
            index = { words, _ -> sent += "index:$words"; index },
            clock = clock,
        )
    }

    private val hits = SearchResult.Hits(listOf(OffHit("8410000807363", "La Buena María", "Fontaneda", "800 g", null)))

    @Test
    fun liveSearchFirstTheSearchServiceWhenItIsDown() = runTest {
        var now = 0L
        val s = Services(live = SearchResult.Failed("HTTP 503"), index = hits)
        val search = s.search { now }
        // 503: the search service answers this time…
        assertEquals(hits, search.search("fontaneda", "es"))
        assertEquals(listOf("live:fontaneda", "index:fontaneda"), s.sent)
        // …and is asked first (one request) for the next ten minutes.
        s.sent.clear()
        now += 60_000
        search.search("activia", "es")
        assertEquals(listOf("index:activia"), s.sent)
        s.sent.clear()
        now += PackSearch.LIVE_DOWN_MS
        s.live = hits
        search.search("heinz", "es")
        assertEquals(listOf("live:heinz"), s.sent)
    }

    @Test
    fun aTooManyFromLiveSearchBacksItOffEvenWhenTheOtherAnswers() = runTest {
        var now = 0L
        val s = Services(live = SearchResult.Busy, index = hits)
        val search = s.search { now }
        assertEquals(hits, search.search("fontaneda", "es"))
        s.sent.clear()
        now += 30_000
        search.search("activia", "es")
        // Not asked again within the minute.
        assertEquals(listOf("index:activia"), s.sent)
        s.sent.clear()
        now += PackSearch.BACKOFF_MS
        s.live = hits
        search.search("heinz", "es")
        assertEquals(listOf("live:heinz"), s.sent)
    }

    @Test
    fun offlineIsSaidAtOnceAndBothFailingIsAFailure() = runTest {
        val offline = Services(live = SearchResult.Offline, index = hits)
        assertEquals(SearchResult.Offline, offline.search { 0L }.search("fontaneda", "es"))
        assertEquals(listOf("live:fontaneda"), offline.sent)
        val broken = Services(live = SearchResult.Failed("HTTP 503"), index = SearchResult.Failed("HTTP 500"))
        assertTrue(broken.search { 0L }.search("fontaneda", "es") is SearchResult.Failed)
    }

    @Test
    fun correctedWordsAreSearchedWithinTheSameLimits() = runTest {
        // The words read were the small print; the user corrects them in the field and searches twice.
        var now = 0L
        val s = Services(live = hits)
        val search = s.search { now }
        search.search(PackageText.typed("senza aggiunta conservanti")!!, "es")
        val corrected = PackageText.typed("  coca-cola   zero ")!!
        assertEquals(hits, search.search(corrected, "es"))
        assertEquals(hits, search.search(corrected, "es"))
        // The second submit of the same words comes from memory.
        assertEquals(listOf("live:senza aggiunta conservanti", "live:coca-cola zero"), s.sent)
        // Typed searches count in the limit per minute like the others.
        for (i in 3..PackSearch.MAX_PER_MINUTE) search.search("words $i", "es")
        assertEquals(SearchResult.Busy, search.search("one more", "es"))
    }

    @Test
    fun sameWordsComeFromMemoryAndSearchesAreLimited() = runTest {
        var now = 0L
        val sent = ArrayList<String>()
        val search = PackSearch({ words, _ ->
            sent += words
            SearchResult.Hits(listOf(OffHit("8410000807363", words, null, null, null)))
        }, { _, _ -> SearchResult.Failed("unused") }, clock = { now })

        search.search("fontaneda maría", "es")
        // Again (a second tap, the same photo): from memory.
        search.search("Fontaneda María ", "es")
        assertEquals(1, sent.size)
        // Other words: sent, up to the limit per minute.
        for (i in 2..PackSearch.MAX_PER_MINUTE) search.search("words $i", "es")
        assertEquals(PackSearch.MAX_PER_MINUTE, sent.size)
        assertEquals(SearchResult.Busy, search.search("one more", "es"))
        assertEquals(PackSearch.MAX_PER_MINUTE, sent.size)
        // A minute later: allowed again.
        now += 60_000
        assertTrue(search.search("one more", "es") is SearchResult.Hits)
        // The memory lasts half an hour.
        now += PackSearch.CACHE_MS
        search.search("fontaneda maría", "es")
        assertEquals(PackSearch.MAX_PER_MINUTE + 2, sent.size)
    }

    @Test
    fun afterTooManyNothingIsSentForAMinute() = runTest {
        var now = 1_000L
        var answer: SearchResult = SearchResult.Busy
        var sent = 0
        // Both services say "too many".
        val search = PackSearch({ _, _ ->
            sent++
            answer
        }, { _, _ -> answer }, clock = { now })
        assertEquals(SearchResult.Busy, search.search("a", "es"))
        answer = SearchResult.Hits(emptyList())
        now += 30_000
        assertEquals(SearchResult.Busy, search.search("b", "es"))
        assertEquals(1, sent)
        now += 31_000
        assertEquals(SearchResult.Hits(emptyList()), search.search("b", "es"))
        assertEquals(2, sent)
        // Offline or failed answers are not kept: the next try asks again.
        answer = SearchResult.Offline
        search.search("c", "es")
        search.search("c", "es")
        assertEquals(4, sent)
    }

    @Test
    fun productsSoldInTheUsersCountryComeFirst() {
        fun hit(code: String, vararg countries: String) = OffHit(code, code, null, null, null, countries.toList())
        val hits = listOf(hit("1", "en:france"), hit("2", "en:spain", "en:france"), hit("3"), hit("4", "en:spain"))
        assertEquals(listOf("2", "4", "1", "3"), PackSearch.ranked(hits, "en:spain").map { it.barcode })
        assertEquals(listOf("1", "2", "3", "4"), PackSearch.ranked(hits, null).map { it.barcode })
        assertEquals(2, PackSearch.ranked(hits, "en:spain", max = 2).size)

        assertEquals("en:spain", PackSearch.countryTag(Locale.forLanguageTag("es-ES")))
        assertEquals("en:united-states", PackSearch.countryTag(Locale.forLanguageTag("en-US")))
        assertEquals("en:united-kingdom", PackSearch.countryTag(Locale.forLanguageTag("en-GB")))
        assertNull(PackSearch.countryTag(Locale.forLanguageTag("es")))
        assertEquals("en:brazil", PackSearch.countryTag(Locale.forLanguageTag("pt-BR")))
        // Names that are not Open Food Facts' tags ("Türkiye", "Czechia", "Hong Kong SAR China"…).
        assertEquals("en:turkey", PackSearch.countryTag(Locale.forLanguageTag("tr-TR")))
        assertEquals("en:czech-republic", PackSearch.countryTag(Locale.forLanguageTag("cs-CZ")))
        assertEquals("en:hong-kong", PackSearch.countryTag(Locale.forLanguageTag("zh-HK")))
        // Accents and "&" as Open Food Facts writes them.
        assertEquals("en:bosnia-and-herzegovina", PackSearch.countryTag(Locale.forLanguageTag("bs-BA")))
        assertEquals("en:reunion", PackSearch.countryTag(Locale.forLanguageTag("fr-RE")))
    }
}
