package com.enjoyyourtime.globalfitness.health.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyWaterMassRecord
import androidx.health.connect.client.records.BoneMassRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Length
import androidx.health.connect.client.units.Percentage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.random.Random
import kotlin.reflect.KClass

/**
 * Debug-only test helper: writes deterministic sample band data into Health Connect so the
 * import can be verified on an emulator without Mi Fitness.
 *
 *     adb shell am broadcast -n com.enjoyyourtime.globalfitness/.health.debug.DebugHealthSeedReceiver --ei days 10
 *     adb shell am broadcast -n com.enjoyyourtime.globalfitness/.health.debug.DebugHealthSeedReceiver --es action clear
 *     adb shell am broadcast -n com.enjoyyourtime.globalfitness/.health.debug.DebugHealthSeedReceiver --es action dump
 *
 * `dump` logs the scale records this app wrote (client id, version, value) to verify the export;
 * `meals` (`--es action meals`) the meals' nutrition records, every nutrient as Health Connect stored it.
 *
 * The write permissions are declared only in the debug manifest; grant them with `pm grant`.
 */
class DebugHealthSeedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val days = intent.getIntExtra("days", 10).coerceIn(1, 60)
        val action = intent.getStringExtra("action") ?: "seed"
        val client = HealthConnectClient.getOrCreate(context.applicationContext)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (action) {
                    "clear" -> {
                        clearAll(client)
                        Log.i(TAG, "Cleared seeded records")
                    }
                    "dump" -> dumpScaleRecords(client, context.packageName)
                    "meals" -> dumpMealRecords(client, context.packageName)
                    else -> {
                        val count = seed(client, days)
                        Log.i(TAG, "Seeded $count records for $days days")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Seeding failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun clearAll(client: HealthConnectClient) {
        val range = TimeRangeFilter.after(Instant.now().minusSeconds(70L * 86_400))
        for (type in TYPES) client.deleteRecords(type, range)
    }

    private suspend fun dumpScaleRecords(client: HealthConnectClient, ownPackage: String) {
        val range = TimeRangeFilter.after(Instant.now().minusSeconds(400L * 86_400))
        val origin = setOf(DataOrigin(ownPackage))
        val lines = buildList {
            client.readRecords(ReadRecordsRequest(WeightRecord::class, range, origin)).records.forEach { add(line(it, "${it.weight.inKilograms} kg")) }
            client.readRecords(ReadRecordsRequest(BodyFatRecord::class, range, origin)).records.forEach { add(line(it, "${it.percentage.value} %")) }
            client.readRecords(ReadRecordsRequest(BoneMassRecord::class, range, origin)).records.forEach { add(line(it, "${it.mass.inKilograms} kg")) }
            client.readRecords(ReadRecordsRequest(LeanBodyMassRecord::class, range, origin)).records.forEach { add(line(it, "${it.mass.inKilograms} kg")) }
            client.readRecords(ReadRecordsRequest(BasalMetabolicRateRecord::class, range, origin)).records.forEach {
                add(line(it, "${it.basalMetabolicRate.inKilocaloriesPerDay} kcal/d"))
            }
            client.readRecords(ReadRecordsRequest(BodyWaterMassRecord::class, range, origin)).records.forEach { add(line(it, "${it.mass.inKilograms} kg")) }
        }
        Log.i(TAG, "Scale records in Health Connect: ${lines.size}")
        lines.sorted().forEach { Log.i(TAG, it) }
    }

    /** The meals this app wrote, read back as Health Connect stores them (every nutrient the export sets). */
    private suspend fun dumpMealRecords(client: HealthConnectClient, ownPackage: String) {
        val range = TimeRangeFilter.after(Instant.now().minusSeconds(400L * 86_400))
        val records = client.readRecords(ReadRecordsRequest(NutritionRecord::class, range, setOf(DataOrigin(ownPackage)))).records
        Log.i(TAG, "Meal records in Health Connect: ${records.size}")
        for (r in records) {
            Log.i(
                TAG,
                line(r, "\"${r.name}\" meal=${r.mealType} energy=${r.energy?.inKilocalories} kcal protein=${r.protein?.inGrams} g " +
                    "carbs=${r.totalCarbohydrate?.inGrams} g sugar=${r.sugar?.inGrams} g fat=${r.totalFat?.inGrams} g " +
                    "satFat=${r.saturatedFat?.inGrams} g fiber=${r.dietaryFiber?.inGrams} g sodium=${r.sodium?.inGrams} g"),
            )
        }
    }

    private fun line(r: Record, value: String): String {
        val m = r.metadata
        return "${m.clientRecordId} v${m.clientRecordVersion} method=${m.recordingMethod} device=${m.device?.type} $value"
    }

    private suspend fun seed(client: HealthConnectClient, days: Int): Int {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val now = ZonedDateTime.now(zone)
        val records = ArrayList<Record>()
        for (offset in days - 1 downTo 0) {
            val date = today.minusDays(offset.toLong())
            val rnd = Random(date.toEpochDay())
            fun at(h: Int, m: Int = 0): ZonedDateTime = date.atTime(LocalTime.of(h, m)).atZone(zone)
            fun meta(key: String) = Metadata.autoRecorded(BAND, "seed-$date-$key", 1)

            // Sleep: 23:30 of the previous day to about 07:10, with stage cycles.
            val sleepStart = at(0).minusMinutes(30)
            val stages = ArrayList<SleepSessionRecord.Stage>()
            var t = sleepStart
            val cycle = listOf(
                SleepSessionRecord.STAGE_TYPE_LIGHT to 25,
                SleepSessionRecord.STAGE_TYPE_DEEP to 35,
                SleepSessionRecord.STAGE_TYPE_LIGHT to 20,
                SleepSessionRecord.STAGE_TYPE_REM to 20,
                SleepSessionRecord.STAGE_TYPE_AWAKE to 4,
            )
            repeat(5) { c ->
                for ((stage, minutes) in cycle) {
                    val length = (minutes + rnd.nextInt(-5, 6) - if (stage == SleepSessionRecord.STAGE_TYPE_DEEP) c * 5 else 0).coerceAtLeast(3)
                    val end = t.plusMinutes(length.toLong())
                    stages += SleepSessionRecord.Stage(t.toInstant(), end.toInstant(), stage)
                    t = end
                }
            }
            if (t.isBefore(now)) {
                records += SleepSessionRecord(
                    sleepStart.toInstant(), sleepStart.offset, t.toInstant(), t.offset, meta("sleep"), "Sueño", null, stages,
                )
                for ((i, h) in listOf(2, 4, 6).withIndex()) {
                    records += OxygenSaturationRecord(at(h).toInstant(), at(h).offset, Percentage(94.0 + rnd.nextInt(0, 5)), meta("spo2-$i"))
                }
                records += RespiratoryRateRecord(at(3).toInstant(), at(3).offset, 13.5 + rnd.nextDouble() * 3, meta("resp"))
                records += RestingHeartRateRecord(at(7, 30).toInstant(), at(7, 30).offset, 55L + rnd.nextInt(0, 8), meta("rhr"))
                records += HeartRateVariabilityRmssdRecord(at(7, 30).toInstant(), at(7, 30).offset, 38.0 + rnd.nextDouble() * 20, meta("hrv"))
            }

            // Hourly activity and heart rate during the day.
            val exercise = offset % 2 == 0
            for (h in 8..21) {
                val start = at(h)
                val end = start.plusHours(1)
                if (end.isAfter(now)) break
                val steps = 300L + rnd.nextInt(0, 700) + if (exercise && h == 18) 4_500 else 0
                records += StepsRecord(start.toInstant(), start.offset, end.toInstant(), end.offset, steps, meta("steps-$h"))
                records += DistanceRecord(start.toInstant(), start.offset, end.toInstant(), end.offset, Length.meters(steps * 0.75), meta("dist-$h"))
                records += ActiveCaloriesBurnedRecord(
                    start.toInstant(), start.offset, end.toInstant(), end.offset, Energy.kilocalories(steps * 0.045), meta("active-$h"),
                )
                records += TotalCaloriesBurnedRecord(
                    start.toInstant(), start.offset, end.toInstant(), end.offset, Energy.kilocalories(70.0 + steps * 0.045), meta("total-$h"),
                )
                val samples = (0 until 12).map { i ->
                    val active = exercise && h == 18 && i < 8
                    val bpm = if (active) 140L + rnd.nextInt(0, 30) else 62L + rnd.nextInt(0, 25)
                    HeartRateRecord.Sample(start.plusMinutes(i * 5L).toInstant(), bpm)
                }
                records += HeartRateRecord(start.toInstant(), start.offset, end.toInstant(), end.offset, samples, meta("hr-$h"))
            }
            if (exercise && at(18, 40).isBefore(now)) {
                records += ExerciseSessionRecord(
                    at(18).toInstant(), at(18).offset, at(18, 40).toInstant(), at(18, 40).offset, meta("run"),
                    ExerciseSessionRecord.EXERCISE_TYPE_RUNNING, "Carrera al aire libre",
                )
            }
        }
        for (chunk in records.chunked(500)) client.insertRecords(chunk)
        return records.size
    }

    companion object {
        private const val TAG = "HealthSeed"
        private val BAND = Device(type = Device.TYPE_FITNESS_BAND, manufacturer = "Xiaomi", model = "Smart Band 9 (prueba)")
        private val TYPES: List<KClass<out Record>> = listOf(
            StepsRecord::class, DistanceRecord::class, ActiveCaloriesBurnedRecord::class, TotalCaloriesBurnedRecord::class,
            HeartRateRecord::class, RestingHeartRateRecord::class, HeartRateVariabilityRmssdRecord::class,
            SleepSessionRecord::class, OxygenSaturationRecord::class, ExerciseSessionRecord::class, RespiratoryRateRecord::class,
        )
    }
}
