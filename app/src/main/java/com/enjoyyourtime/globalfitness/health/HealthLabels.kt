package com.enjoyyourtime.globalfitness.health

import androidx.annotation.StringRes
import com.enjoyyourtime.globalfitness.R

/** String resources for the Health Connect data types (permission hints, sync errors). */
@get:StringRes
val HealthDataType.labelRes: Int
    get() = when (this) {
        HealthDataType.STEPS -> R.string.hc_type_steps
        HealthDataType.DISTANCE -> R.string.hc_type_distance
        HealthDataType.ACTIVE_CALORIES -> R.string.hc_type_active_calories
        HealthDataType.TOTAL_CALORIES -> R.string.hc_type_total_calories
        HealthDataType.HEART_RATE -> R.string.hc_type_heart_rate
        HealthDataType.RESTING_HEART_RATE -> R.string.hc_type_resting_heart_rate
        HealthDataType.HEART_RATE_VARIABILITY -> R.string.hc_type_hrv
        HealthDataType.SLEEP -> R.string.hc_type_sleep
        HealthDataType.OXYGEN_SATURATION -> R.string.hc_type_blood_oxygen
        HealthDataType.EXERCISE -> R.string.hc_type_exercise
        HealthDataType.RESPIRATORY_RATE -> R.string.hc_type_respiratory_rate
        HealthDataType.WEIGHT -> R.string.hc_type_weight
        HealthDataType.BODY_FAT -> R.string.hc_type_body_fat
        HealthDataType.BONE_MASS -> R.string.hc_type_bone_mass
        HealthDataType.LEAN_BODY_MASS -> R.string.hc_type_lean_mass
        HealthDataType.BASAL_METABOLIC_RATE -> R.string.hc_type_bmr
        HealthDataType.BODY_WATER_MASS -> R.string.hc_type_body_water
        HealthDataType.NUTRITION -> R.string.hc_type_nutrition
    }

@get:StringRes
val SleepStage.labelRes: Int
    get() = when (this) {
        SleepStage.AWAKE -> R.string.sleep_stage_awake
        SleepStage.LIGHT -> R.string.sleep_stage_light
        SleepStage.DEEP -> R.string.sleep_stage_deep
        SleepStage.REM -> R.string.sleep_stage_rem
        SleepStage.UNKNOWN -> R.string.sleep_stage_unknown
    }
