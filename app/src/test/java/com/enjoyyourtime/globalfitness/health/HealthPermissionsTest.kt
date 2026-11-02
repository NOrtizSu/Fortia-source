package com.enjoyyourtime.globalfitness.health

import androidx.health.connect.client.permission.HealthPermission
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.reflect.KClass

class HealthPermissionsTest {

    private fun readOf(vararg types: HealthDataType) = types.mapNotNull { it.readPermission }.toSet()
    private fun writeOf(vararg types: HealthDataType) = types.mapNotNull { it.writePermission }.toSet()

    @Test
    fun permissionStringsMatchTheHealthConnectLibrary() {
        val reads: Map<HealthDataType, KClass<out Record>> = mapOf(
            HealthDataType.STEPS to StepsRecord::class,
            HealthDataType.DISTANCE to DistanceRecord::class,
            HealthDataType.ACTIVE_CALORIES to ActiveCaloriesBurnedRecord::class,
            HealthDataType.TOTAL_CALORIES to TotalCaloriesBurnedRecord::class,
            HealthDataType.HEART_RATE to HeartRateRecord::class,
            HealthDataType.RESTING_HEART_RATE to RestingHeartRateRecord::class,
            HealthDataType.HEART_RATE_VARIABILITY to HeartRateVariabilityRmssdRecord::class,
            HealthDataType.SLEEP to SleepSessionRecord::class,
            HealthDataType.OXYGEN_SATURATION to OxygenSaturationRecord::class,
            HealthDataType.EXERCISE to ExerciseSessionRecord::class,
            HealthDataType.RESPIRATORY_RATE to RespiratoryRateRecord::class,
            HealthDataType.WEIGHT to WeightRecord::class,
            HealthDataType.BODY_FAT to BodyFatRecord::class,
        )
        reads.forEach { (type, record) -> assertEquals(type.name, HealthPermission.getReadPermission(record), type.readPermission) }

        val writes: Map<HealthDataType, KClass<out Record>> = mapOf(
            HealthDataType.WEIGHT to WeightRecord::class,
            HealthDataType.BODY_FAT to BodyFatRecord::class,
            HealthDataType.BONE_MASS to BoneMassRecord::class,
            HealthDataType.LEAN_BODY_MASS to LeanBodyMassRecord::class,
            HealthDataType.BASAL_METABOLIC_RATE to BasalMetabolicRateRecord::class,
            HealthDataType.BODY_WATER_MASS to BodyWaterMassRecord::class,
            HealthDataType.EXERCISE to ExerciseSessionRecord::class,
            HealthDataType.NUTRITION to NutritionRecord::class,
        )
        writes.forEach { (type, record) -> assertEquals(type.name, HealthPermission.getWritePermission(record), type.writePermission) }
        assertEquals(HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY, HealthPermissions.READ_HISTORY)
        assertEquals(HealthPermission.getWritePermission(ExerciseSessionRecord::class), HealthPermissions.WRITE_EXERCISE)
        assertEquals(HealthPermission.getWritePermission(NutritionRecord::class), HealthPermissions.WRITE_NUTRITION)
        // Nutrition is written only: the app never asks to read it.
        assertNull(HealthDataType.NUTRITION.readPermission)
    }

    @Test
    fun requestedSetHas13ReadsAnd8WritesPlusOptionalHistory() {
        assertEquals(13, HealthPermissions.readPermissions.size)
        assertEquals(8, HealthPermissions.writePermissions.size)
        // Nutrition is asked in context only (after the first meal saved, or by the meals switch).
        assertEquals(20, HealthPermissions.requested(historyAvailable = false).size)
        assertTrue(HealthPermissions.WRITE_EXERCISE in HealthPermissions.requested(historyAvailable = false))
        assertFalse(HealthPermissions.WRITE_NUTRITION in HealthPermissions.requested(historyAvailable = false))
        val withHistory = HealthPermissions.requested(historyAvailable = true)
        assertEquals(21, withHistory.size)
        assertFalse(HealthPermissions.WRITE_NUTRITION in withHistory)
        assertTrue(HealthPermissions.READ_HISTORY in withHistory)
        assertFalse(HealthPermissions.READ_HISTORY in HealthPermissions.requested(historyAvailable = false))
    }

    @Test
    fun nutritionWritePermissionIsOptionalLikeWorkouts() {
        val granted = writeOf(HealthDataType.NUTRITION)
        assertTrue(HealthPermissions.canExportMeals(granted))
        assertFalse(HealthPermissions.canExportWorkouts(granted))
        assertFalse(HealthPermissions.canWriteAnyScaleValue(granted))
        assertFalse(HealthPermissions.canExportMeals(writeOf(HealthDataType.EXERCISE)))
        // Not part of the connect request: without it (everyone at first) nothing is "missing".
        val connected = HealthPermissions.requested(historyAvailable = false)
        assertTrue(HealthPermissions.missing(connected, historyAvailable = false).isEmpty())
        assertTrue(HealthPermissions.allGranted(connected, historyAvailable = false))
        assertTrue(HealthPermissions.connectedFully(connected - HealthPermissions.WRITE_EXERCISE, historyAvailable = false))
        // Granted in context, it changes nothing about the connection either.
        assertTrue(HealthPermissions.missing(connected + HealthPermissions.WRITE_NUTRITION, historyAvailable = false).isEmpty())
    }

    @Test
    fun partialGrantEnablesOnlyTheGrantedTypes() {
        val granted = readOf(HealthDataType.STEPS, HealthDataType.SLEEP) + writeOf(HealthDataType.BODY_FAT)
        assertEquals(setOf(HealthDataType.STEPS, HealthDataType.SLEEP), HealthPermissions.readableTypes(granted))
        assertEquals(setOf(HealthDataType.BODY_FAT), HealthPermissions.writableTypes(granted))
        assertTrue(HealthPermissions.hasAnyWearableRead(granted))
        // Body fat alone is not enough to export: weight is required.
        assertFalse(HealthPermissions.canExport(granted))
        assertTrue(HealthPermissions.canExport(granted + writeOf(HealthDataType.WEIGHT)))
        assertTrue(HealthDataType.HEART_RATE in HealthPermissions.missingWearableTypes(granted))
        assertFalse(HealthDataType.STEPS in HealthPermissions.missingWearableTypes(granted))
    }

    @Test
    fun exerciseWritePermissionDoesNotChangeTheScaleExport() {
        val granted = writeOf(HealthDataType.EXERCISE)
        assertTrue(HealthPermissions.canExportWorkouts(granted))
        assertFalse(HealthPermissions.canExport(granted))
        assertFalse(HealthPermissions.canWriteAnyScaleValue(granted))
        assertFalse(HealthDataType.EXERCISE in HealthPermissions.exportTypes)
        assertEquals(ExportRecordType.entries.map { it.dataType }, HealthPermissions.exportTypes)
        assertTrue(HealthPermissions.canWriteAnyScaleValue(writeOf(HealthDataType.BODY_FAT)))
        assertFalse(HealthPermissions.canExportWorkouts(writeOf(HealthDataType.WEIGHT) + readOf(HealthDataType.EXERCISE)))
        // Users who connected before workouts were exported miss only this permission.
        val before = HealthPermissions.requested(historyAvailable = false) - HealthPermissions.WRITE_EXERCISE
        assertEquals(setOf(HealthPermissions.WRITE_EXERCISE), HealthPermissions.missing(before, historyAvailable = false))
        assertTrue(HealthPermissions.canExport(before))
        // ...which does not make them "connected with partial permissions": workouts have their own switch.
        assertFalse(HealthPermissions.allGranted(before, historyAvailable = false))
        assertTrue(HealthPermissions.connectedFully(before, historyAvailable = false))
        assertFalse(HealthPermissions.connectedFully(before - HealthPermissions.readPermissions.first(), historyAvailable = false))
    }

    @Test
    fun readingOnlyWeightDoesNotCountAsWearableData() {
        val granted = readOf(HealthDataType.WEIGHT, HealthDataType.BODY_FAT)
        assertFalse(HealthPermissions.hasAnyWearableRead(granted))
        assertEquals(HealthPermissions.wearableTypes.size, HealthPermissions.missingWearableTypes(granted).size)
    }

    @Test
    fun importWindowDependsOnHistoryPermission() {
        assertEquals(30, HealthPermissions.importWindowDays(emptySet()))
        assertEquals(365, HealthPermissions.importWindowDays(setOf(HealthPermissions.READ_HISTORY)))
    }

    @Test
    fun missingAndAllGranted() {
        val all = HealthPermissions.requested(historyAvailable = false)
        assertTrue(HealthPermissions.allGranted(all, historyAvailable = false))
        assertFalse(HealthPermissions.allGranted(all, historyAvailable = true))
        assertEquals(setOf(HealthPermissions.READ_HISTORY), HealthPermissions.missing(all, historyAvailable = true))
        // Extra unrelated grants do not matter.
        assertTrue(HealthPermissions.allGranted(all + "android.permission.health.READ_NUTRITION", historyAvailable = false))
    }

    @Test
    fun exportSwitchDefaultsOnOnlyWithPermission() {
        assertTrue(HealthSettings.exportEnabled(choice = null, canExport = true))
        assertFalse(HealthSettings.exportEnabled(choice = null, canExport = false))
        assertFalse(HealthSettings.exportEnabled(choice = false, canExport = true))
        assertFalse(HealthSettings.exportEnabled(choice = true, canExport = false))
    }
}
