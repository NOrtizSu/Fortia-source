package com.enjoyyourtime.globalfitness.ui.training

import com.enjoyyourtime.globalfitness.R
import com.enjoyyourtime.globalfitness.training.catalog.ExerciseAliases
import com.enjoyyourtime.globalfitness.training.catalog.ExerciseCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The exercise search: the token matching of [TrainingFormat] and the search words of the built-in
 * exercises ([ExerciseAliases]), checked against the English and Spanish master texts the way the
 * exercise browser builds its haystacks (name plus search words).
 */
class ExerciseSearchTest {
    @Test
    fun stemsDropOneVerbOrPluralEndingKeepingEnoughOfTheWord() {
        assertEquals("camin", TrainingFormat.stem("caminar"))
        assertEquals("camin", TrainingFormat.stem("caminando"))
        assertEquals("corr", TrainingFormat.stem("corriendo"))
        assertEquals("walk", TrainingFormat.stem("walking"))
        assertEquals("row", TrainingFormat.stem("rows"))
        assertEquals("run", TrainingFormat.stem("runs"))
        assertEquals("sentadilla", TrainingFormat.stem("sentadillas"))
        assertEquals("flexion", TrainingFormat.stem("flexiones"))
        // Too little would be left: "and" would find "clean and press" (the search words find walking).
        assertEquals("andar", TrainingFormat.stem("andar"))
        assertEquals("bus", TrainingFormat.stem("bus"))
        assertEquals("ir", TrainingFormat.stem("ir"))
    }

    @Test
    fun aStemOnlyCountsAtTheStartOfAWord() {
        assertTrue(TrainingFormat.tokenMatches("caminar", "caminata"))
        assertTrue(TrainingFormat.tokenMatches("rows", "seated cable row"))
        assertTrue(TrainingFormat.tokenMatches("sentadillas", "sentadilla bulgara"))
        // As typed, a token still matches anywhere ("curl" in "hammer curl", "dilla" in "sentadilla").
        assertTrue(TrainingFormat.tokenMatches("dilla", "sentadilla"))
        // Its stem only at a word start: "picaminar" is not "caminar".
        assertFalse(TrainingFormat.tokenMatches("caminar", "picaminata"))
        assertFalse(TrainingFormat.tokenMatches("remos", "extremo"))
    }

    @Test
    fun oneAndTwoLetterTokensOnlyMatchTheStartOfAWord() {
        assertFalse(TrainingFormat.tokenMatches("a", "press de banca"))
        assertFalse(TrainingFormat.tokenMatches("ca", "press de banca"))
        assertFalse(TrainingFormat.tokenMatches("ar", "caminar"))
        assertTrue(TrainingFormat.tokenMatches("ca", "caminar"))
        assertTrue(TrainingFormat.tokenMatches("de", "press de banca"))
        assertTrue(TrainingFormat.tokenMatches("b", "press de banca"))
        // Across the Spanish catalog, a letter or two finds only names with a word starting with them.
        for (token in listOf("a", "e", "o", "s", "de", "la", "es", "ar", "on")) {
            val found = spanish.filterValues { TrainingFormat.tokenMatches(token, it) }
            assertTrue("\"$token\" finds ${found.size} of ${spanish.size}", found.size < spanish.size / 2)
            for ((id, text) in found) assertTrue("$token in $id: $text", text.split(Regex("[^\\p{L}\\p{N}]+")).any { it.startsWith(token) })
        }
    }

    @Test
    fun scriptsWithoutSpacesStillMatchAnywhere() {
        assertTrue(TrainingFormat.tokenMatches("走", "快走"))
        assertTrue(TrainingFormat.tokenMatches("步行", "快走 步行"))
        assertTrue(TrainingFormat.tokenMatches("ラン", "ランニング"))
        assertEquals("ランニング", TrainingFormat.stem("ランニング"))
    }

    @Test
    fun walkingIsFoundByTheWordsPeopleUseForIt() {
        for (query in listOf("caminar", "Caminar", "andar", "paseo", "caminata", "caminando", "pasear")) {
            assertTrue("$query in Spanish", "outdoor_walk" in search(spanish, query))
        }
        for (query in listOf("caminar", "andar")) {
            val found = search(spanish, query)
            assertTrue("$query: $found", found.containsAll(listOf("outdoor_walk", "treadmill_walk")))
        }
        assertTrue("incline_treadmill_walk" in search(spanish, "caminar inclinado"))
        assertTrue("incline_treadmill_walk" in search(spanish, "cinta cuesta"))
        assertTrue("nordic_walking" in search(spanish, "caminar bastones"))
        for (query in listOf("walk", "walking", "Walks", "stroll")) {
            assertTrue("$query in English", "outdoor_walk" in search(english, query))
        }
        assertTrue(search(english, "treadmill walk").containsAll(listOf("treadmill_walk", "incline_treadmill_walk")))
        assertTrue("incline_treadmill_walk" in search(english, "12-3-30"))
        // Walking is not found by every word that merely contains part of it.
        assertFalse("outdoor_walk" in search(spanish, "camion"))
    }

    @Test
    fun searchWordsFindTheOtherNamesOfAnExercise() {
        assertTrue("pec_deck" in search(spanish, "contractor"))
        assertTrue("rowing_machine" in search(english, "erg"))
        assertTrue("jump_rope" in search(spanish, "comba"))
        assertTrue("foam_roll_quads" in search(spanish, "rodillo"))
    }

    @Test
    fun theExercisesAndSportsOf18AreFoundByTheWordsPeopleUse() {
        fun findsAll(haystacks: Map<String, String>, query: String, vararg ids: String) {
            val found = search(haystacks, query)
            assertTrue("\"$query\" finds $found", found.containsAll(ids.toList()))
        }
        findsAll(spanish, "muscle up", "bar_muscle_up", "ring_muscle_up")
        findsAll(spanish, "pino", "wall_handstand", "handstand", "wall_handstand_push_up")
        findsAll(spanish, "calistenia", "front_lever", "back_lever", "tuck_planche", "human_flag")
        findsAll(spanish, "pesa rusa", "kettlebell_halo", "american_kettlebell_swing", "kettlebell_overhead_carry")
        findsAll(spanish, "halterofilia", "push_jerk", "clean_and_jerk", "squat_snatch", "snatch_pull")
        // "Arrancada" in Spain, "arranque" in much of Latin America.
        findsAll(
            spanish, "arranque",
            "power_snatch", "hang_power_snatch", "squat_snatch", "snatch_balance", "snatch_pull", "dumbbell_snatch", "kettlebell_snatch",
        )
        findsAll(spanish, "barra hexagonal", "trap_bar_deadlift")
        findsAll(spanish, "strongman", "yoke_carry", "log_press", "atlas_stone", "tyre_flip", "sandbag_to_shoulder")
        findsAll(spanish, "neumático", "tyre_flip")
        findsAll(spanish, "trx", "suspension_row", "suspension_chest_press", "suspension_pike", "suspension_fallout")
        findsAll(spanish, "pelota suiza", "swiss_ball_crunch", "swiss_ball_pass", "stir_the_pot")
        findsAll(spanish, "fitball", "swiss_ball_crunch", "swiss_ball_hamstring_curl")
        findsAll(spanish, "bosu", "bosu_squat", "bosu_push_up", "bosu_balance")
        findsAll(spanish, "pilates", "pilates", "pilates_hundred", "pilates_teaser", "pilates_shoulder_bridge")
        findsAll(spanish, "saco", "heavy_bag", "bag_kicks", "knee_strikes")
        findsAll(spanish, "boxeo", "shadow_boxing", "heavy_bag", "speed_bag", "pad_work", "shadow_hooks", "shadow_uppercuts")
        findsAll(spanish, "manoplas", "pad_work")
        findsAll(spanish, "esquiar", "alpine_skiing", "cross_country_skiing")
        findsAll(spanish, "ping pong", "table_tennis")
        findsAll(spanish, "patines", "inline_skating", "ice_skating")
        findsAll(spanish, "piragua", "kayaking")
        findsAll(spanish, "nadar en el mar", "open_water_swimming")
        findsAll(english, "muscle up", "bar_muscle_up", "ring_muscle_up")
        findsAll(english, "hex bar", "trap_bar_deadlift")
        findsAll(english, "stability ball", "swiss_ball_crunch", "swiss_ball_pike")
        findsAll(english, "punching bag", "heavy_bag")
        findsAll(english, "ping pong", "table_tennis")
        findsAll(english, "rollerblade", "inline_skating")
        findsAll(english, "skiing", "alpine_skiing", "cross_country_skiing")
        // No false friends: padel is not paddling, the rowing machine is not rowing on the water by name alone.
        assertFalse("kayaking" in search(spanish, "pádel"))
        assertFalse("padel" in search(spanish, "piragua"))
        assertFalse("rowing_machine" in search(spanish, "remo en el agua"))
        assertFalse("plank" in search(spanish, "planche"))
        // "Gancho" is the hook; the uppercut is not found by it.
        assertTrue("shadow_hooks" in search(spanish, "gancho"))
        assertFalse("shadow_uppercuts" in search(spanish, "gancho"))
    }

    @Test
    fun everySearchWordStringBelongsToABuiltInExerciseAndIsNamedAfterIt() {
        val ids = ExerciseCatalog.all.map { it.id }.toSet()
        assertEquals(emptySet<String>(), ExerciseAliases.ids - ids)
        val string = R.string::class.java
        for (id in ExerciseAliases.ids) {
            val field = runCatching { string.getField("exa_$id") }.getOrNull()
            assertNotNull("missing string exa_$id", field)
            assertEquals("exa_$id is not the search words of $id", field!!.getInt(null), ExerciseAliases[id])
        }
        // The master file has exactly these keys, each once, with English and Spanish words.
        val keys = aliasEntries.map { it.first }
        assertEquals("repeated: ${keys.groupingBy { it }.eachCount().filterValues { it > 1 }.keys}", keys.size, keys.toSet().size)
        assertEquals(ExerciseAliases.ids.map { "exa_$it" }.toSet(), keys.toSet())
        for ((key, texts) in aliasEntries) {
            for (text in listOf(texts.first, texts.second)) {
                assertTrue("$key has an empty word: \"$text\"", text.split(',').all { it.isNotBlank() })
            }
        }
        // The first new exercises' words are there.
        for (id in listOf("outdoor_walk", "treadmill_walk", "incline_treadmill_walk", "jump_rope", "foam_roll_quads")) {
            assertNotNull(id, ExerciseAliases[id])
        }
    }

    // ---- the catalog as the browser searches it, from the master texts -------------------------------

    /** id -> normalized name and search words, in English and Spanish. */
    private val english: Map<String, String>
    private val spanish: Map<String, String>
    private val aliasEntries: List<Pair<String, Pair<String, String>>> = entries(ALIASES)

    init {
        val names = NAMES.flatMap(::entries).toMap()
        val aliases = aliasEntries.toMap()
        val ids = ExerciseCatalog.all.map { it.id }
        fun haystack(id: String, pick: (Pair<String, String>) -> String): String {
            val name = names["ex_$id"] ?: throw AssertionError("no name for $id")
            return TrainingFormat.normalize(listOfNotNull(pick(name), aliases["exa_$id"]?.let(pick)).joinToString(" "))
        }
        english = ids.associateWith { haystack(it) { p -> p.first } }
        spanish = ids.associateWith { haystack(it) { p -> p.second } }
    }

    private fun search(haystacks: Map<String, String>, query: String): Set<String> {
        val tokens = TrainingFormat.searchTokens(query)
        return haystacks.filterValues { TrainingFormat.matches(tokens, it) }.keys
    }

    /** key -> (English, Spanish) of a master file, found from the working directory (the module) upwards. */
    private fun entries(master: String): List<Pair<String, Pair<String, String>>> {
        val file = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .map { File(it, master) }
            .firstOrNull { it.isFile }
        assertNotNull("$master not found above ${System.getProperty("user.dir")}", file)
        return file!!.readLines(Charsets.UTF_8)
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { line ->
                val (key, value) = line.split(" = ", limit = 2)
                val parts = value.split(" ||| ")
                assertEquals("$key needs exactly one English and one Spanish text", 2, parts.size)
                key.trim() to (parts[0].trim() to parts[1].trim())
            }
    }

    private companion object {
        val NAMES = listOf("tools/i18n/src/master/97_exercises.txt", "tools/i18n/src/master/97_sports.txt")
        const val ALIASES = "tools/i18n/src/master/97_exercise_aliases.txt"
    }
}
