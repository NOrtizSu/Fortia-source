package com.enjoyyourtime.globalfitness.training

import androidx.health.connect.client.records.ExerciseSegment
import com.enjoyyourtime.globalfitness.training.catalog.ExerciseCatalog
import com.enjoyyourtime.globalfitness.training.model.Equipment
import com.enjoyyourtime.globalfitness.training.model.ExerciseKind
import com.enjoyyourtime.globalfitness.training.model.Muscle
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Invariants of the larger gym catalog: stretches, muscle coverage without them, variety and unique names. */
class GymCatalogTest {
    private val all = ExerciseCatalog.all
    private val training = all.filter { !it.mobility }

    @Test
    fun stretchesAreTimedBodyweightOrBandStretchingSegments() {
        val stretches = all.filter { it.mobility }
        assertTrue("${stretches.size} stretches", stretches.size in 30..45)
        for (e in stretches) {
            assertEquals(e.id, ExerciseKind.DURATION, e.kind)
            // Foam rolling uses the roller and the bosu balance the bosu ("other" equipment), the halo a light
            // kettlebell; every other drill is bodyweight or a band.
            val expected = when {
                e.id.startsWith("foam_roll_") || e.id == "bosu_balance" -> setOf(Equipment.OTHER)
                e.id == "kettlebell_halo" -> setOf(Equipment.KETTLEBELL)
                else -> setOf(Equipment.BODYWEIGHT, Equipment.BAND)
            }
            assertTrue(e.id, e.equipment in expected)
            assertEquals(e.id, ExerciseSegment.EXERCISE_SEGMENT_TYPE_STRETCHING, e.hcSegment)
            assertNull(e.id, e.bodyweightFactor)
            assertTrue("${e.id} stretches no muscle", e.primary.isNotEmpty())
        }
        // Only stretches export as stretching.
        for (e in training) assertTrue(e.id, e.hcSegment != ExerciseSegment.EXERCISE_SEGMENT_TYPE_STRETCHING)
    }

    @Test
    fun everyMuscleIsTrainedByTwoExercisesBesidesTheStretches() {
        val primaries = training.flatMap { it.primary }.groupingBy { it }.eachCount()
        for (m in Muscle.entries) assertTrue("$m: ${primaries[m]} training exercises", (primaries[m] ?: 0) >= 2)
    }

    @Test
    fun theThinMusclesGotTheirOwnExercises() {
        val primaries = training.flatMap { it.primary }.groupingBy { it }.eachCount()
        // What the 144-exercise catalog had, plus at least what the expansion promised.
        val minimum = mapOf(
            Muscle.NECK to 1 + 3,
            Muscle.ADDUCTORS to 3 + 4,
            Muscle.CALVES to 4 + 4,
            Muscle.LOWER_BACK to 4 + 4,
            Muscle.OBLIQUES to 5 + 4,
            Muscle.FOREARMS to 5 + 5,
        )
        for ((m, n) in minimum) assertTrue("$m: ${primaries[m]} < $n", (primaries[m] ?: 0) >= n)
        val equipment = all.groupingBy { it.equipment }.eachCount()
        assertTrue("kettlebell ${equipment[Equipment.KETTLEBELL]}", (equipment[Equipment.KETTLEBELL] ?: 0) >= 10)
        assertTrue("band ${equipment[Equipment.BAND]}", (equipment[Equipment.BAND] ?: 0) >= 10)
    }

    @Test
    fun pushUpSharesFollowHowMuchOfTheBodyTheHandsCarry() {
        fun share(id: String) = assertNotNull(id, ExerciseCatalog[id]?.bodyweightFactor).let { ExerciseCatalog[id]!!.bodyweightFactor!! }
        assertTrue(share("incline_push_up") < share("push_up"))
        assertTrue(share("knee_push_up") < share("push_up"))
        assertTrue(share("push_up") < share("decline_push_up"))
        assertEquals(share("push_up"), share("diamond_push_up"), 0.0)
    }

    @Test
    fun noExerciseHasTheSameNameAsAnother() {
        // Gym names and the sports' names (their own master file) share one namespace in the app.
        val files = MASTERS.map { master ->
            generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
                .map { File(it, master) }
                .firstOrNull { it.isFile }
                .also { assertNotNull(master, it) }!!
        }
        val names = files.flatMap { it.readLines(Charsets.UTF_8) }.filter { it.startsWith("ex_") }.map { line ->
            val (key, value) = line.split(" = ", limit = 2)
            val (en, es) = value.split(" ||| ")
            Triple(key, en.trim().lowercase(), es.trim().lowercase())
        }
        assertEquals(all.size, names.size)
        for (pick in listOf<(Triple<String, String, String>) -> String>({ it.second }, { it.third })) {
            val dup = names.groupBy(pick).filterValues { it.size > 1 }.keys
            assertTrue("names used twice: $dup", dup.isEmpty())
        }
    }

    private companion object {
        val MASTERS = listOf("tools/i18n/src/master/97_exercises.txt", "tools/i18n/src/master/97_sports.txt")
    }
}
