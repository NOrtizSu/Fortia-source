package com.enjoyyourtime.globalfitness.health

/**
 * Health Connect data types used by the app. Permission strings are the public Health Connect
 * names (`android.permission.health.*`), kept here as plain strings so the permission-set logic
 * stays pure Kotlin. They must match the `<uses-permission>` entries in the manifest.
 */
enum class HealthDataType(val readPermission: String?, val writePermission: String?) {
    STEPS(read("STEPS"), null),
    DISTANCE(read("DISTANCE"), null),
    ACTIVE_CALORIES(read("ACTIVE_CALORIES_BURNED"), null),
    TOTAL_CALORIES(read("TOTAL_CALORIES_BURNED"), null),
    HEART_RATE(read("HEART_RATE"), null),
    RESTING_HEART_RATE(read("RESTING_HEART_RATE"), null),
    HEART_RATE_VARIABILITY(read("HEART_RATE_VARIABILITY"), null),
    SLEEP(read("SLEEP"), null),
    OXYGEN_SATURATION(read("OXYGEN_SATURATION"), null),

    /** Read: workouts of the band or watch. Write: the app's own training log ([WorkoutExporter]). */
    EXERCISE(read("EXERCISE"), write("EXERCISE")),
    RESPIRATORY_RATE(read("RESPIRATORY_RATE"), null),
    WEIGHT(read("WEIGHT"), write("WEIGHT")),
    BODY_FAT(read("BODY_FAT"), write("BODY_FAT")),
    BONE_MASS(null, write("BONE_MASS")),
    LEAN_BODY_MASS(null, write("LEAN_BODY_MASS")),
    BASAL_METABOLIC_RATE(null, write("BASAL_METABOLIC_RATE")),
    BODY_WATER_MASS(null, write("BODY_WATER_MASS")),

    /** Write only: the meals of the food log ([FoodExporter]); the app never reads nutrition. */
    NUTRITION(null, write("NUTRITION")),
}

private fun read(name: String) = "android.permission.health.READ_$name"
private fun write(name: String) = "android.permission.health.WRITE_$name"

/** Pure permission-set logic: what to request and what each partial grant enables. */
object HealthPermissions {
    /** Lets the app read data older than 30 days before the first grant (feature-gated). */
    const val READ_HISTORY = "android.permission.health.READ_HEALTH_DATA_HISTORY"

    /** Initial import window without / with the history permission. */
    const val DEFAULT_WINDOW_DAYS = 30
    const val HISTORY_WINDOW_DAYS = 365

    val readPermissions: Set<String> = HealthDataType.entries.mapNotNull { it.readPermission }.toSet()
    val writePermissions: Set<String> = HealthDataType.entries.mapNotNull { it.writePermission }.toSet()

    /** Types the app reads from wearables (weight/body fat are read only to show other sources). */
    val wearableTypes: List<HealthDataType> = listOf(
        HealthDataType.STEPS,
        HealthDataType.DISTANCE,
        HealthDataType.ACTIVE_CALORIES,
        HealthDataType.TOTAL_CALORIES,
        HealthDataType.HEART_RATE,
        HealthDataType.RESTING_HEART_RATE,
        HealthDataType.HEART_RATE_VARIABILITY,
        HealthDataType.SLEEP,
        HealthDataType.OXYGEN_SATURATION,
        HealthDataType.EXERCISE,
        HealthDataType.RESPIRATORY_RATE,
    )

    /**
     * Types whose scale values the app writes. Listed from [ExportRecordType] rather than derived
     * from the write permissions: workouts ([HealthDataType.EXERCISE]) are written too, by another
     * exporter, and must never make the scale export think it has something to write.
     */
    val exportTypes: List<HealthDataType> = ExportRecordType.entries.map { it.dataType }

    /** Write permission for the workouts of the training log (exercise sessions). */
    val WRITE_EXERCISE: String = checkNotNull(HealthDataType.EXERCISE.writePermission)

    /** Write permission for the meals of the food log (nutrition records). */
    val WRITE_NUTRITION: String = checkNotNull(HealthDataType.NUTRITION.writePermission)

    /**
     * Asked only in context, never by "Connect Health Connect": nutrition after the first meal saved
     * (and by the meals switch), so connecting for the scale or the band does not ask about meals.
     */
    val contextOnly: Set<String> = setOf(WRITE_NUTRITION)

    /** Everything the connect request asks for (not [contextOnly]); the history permission only when the device supports it. */
    fun requested(historyAvailable: Boolean): Set<String> =
        readPermissions + writePermissions - contextOnly + if (historyAvailable) setOf(READ_HISTORY) else emptySet()

    fun readableTypes(granted: Set<String>): Set<HealthDataType> =
        HealthDataType.entries.filter { it.readPermission != null && it.readPermission in granted }.toSet()

    fun writableTypes(granted: Set<String>): Set<HealthDataType> =
        HealthDataType.entries.filter { it.writePermission != null && it.writePermission in granted }.toSet()

    fun canRead(granted: Set<String>, type: HealthDataType): Boolean = type.readPermission?.let { it in granted } == true

    fun canWrite(granted: Set<String>, type: HealthDataType): Boolean = type.writePermission?.let { it in granted } == true

    fun hasAnyWearableRead(granted: Set<String>): Boolean = wearableTypes.any { canRead(granted, it) }

    /** Scale export needs at least the weight write permission; other values are optional. */
    fun canExport(granted: Set<String>): Boolean = canWrite(granted, HealthDataType.WEIGHT)

    /** Some scale value may be written (or deleted again); the exercise permission does not count. */
    fun canWriteAnyScaleValue(granted: Set<String>): Boolean = exportTypes.any { canWrite(granted, it) }

    /** Workouts are saved as exercise sessions (and deleted again) only with this permission. */
    fun canExportWorkouts(granted: Set<String>): Boolean = canWrite(granted, HealthDataType.EXERCISE)

    /** Meals are saved as nutrition records (and deleted again) only with this permission. */
    fun canExportMeals(granted: Set<String>): Boolean = canWrite(granted, HealthDataType.NUTRITION)

    fun historyGranted(granted: Set<String>): Boolean = READ_HISTORY in granted

    /** Days to import on a full read. */
    fun importWindowDays(granted: Set<String>): Int =
        if (historyGranted(granted)) HISTORY_WINDOW_DAYS else DEFAULT_WINDOW_DAYS

    fun missing(granted: Set<String>, historyAvailable: Boolean): Set<String> = requested(historyAvailable) - granted

    /** True when every requested permission is granted. */
    fun allGranted(granted: Set<String>, historyAvailable: Boolean): Boolean = missing(granted, historyAvailable).isEmpty()

    /**
     * Requested permissions the connection status does not depend on: writing workouts has its own
     * switch (and "Allow" button), and users who connected before it existed do not have it.
     */
    val optionalForStatus: Set<String> = setOf(WRITE_EXERCISE)

    /** "Connected" rather than "connected with partial permissions": all granted but [optionalForStatus]. */
    fun connectedFully(granted: Set<String>, historyAvailable: Boolean): Boolean =
        (missing(granted, historyAvailable) - optionalForStatus).isEmpty()

    /** Wearable types the user has not allowed, for the "missing permissions" hint. */
    fun missingWearableTypes(granted: Set<String>): List<HealthDataType> = wearableTypes.filterNot { canRead(granted, it) }
}
