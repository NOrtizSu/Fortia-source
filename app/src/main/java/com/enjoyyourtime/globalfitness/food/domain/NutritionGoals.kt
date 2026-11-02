package com.enjoyyourtime.globalfitness.food.domain

import com.enjoyyourtime.globalfitness.data.Sex
import kotlin.math.roundToInt

/** Automatic daily goal (from the BMR and the day's activity) or the user's own numbers. [code] is stored. */
enum class GoalMode(val code: String) {
    AUTO("auto"),
    MANUAL("manual"),
    ;

    companion object {
        fun fromCode(code: String?): GoalMode = if (code == MANUAL.code) MANUAL else AUTO
    }
}

/** What the user aims for; [factor] adjusts the maintenance calories. [code] is stored. */
enum class GoalAim(val code: String, val factor: Double) {
    LOSE("lose", 0.85),
    MAINTAIN("maintain", 1.0),
    GAIN("gain", 1.10),
    ;

    companion object {
        fun fromCode(code: String?): GoalAim = entries.firstOrNull { it.code == code } ?: MAINTAIN
    }
}

/** Where the BMR of the automatic goal comes from. */
enum class BmrSource {
    /** The latest weighing of a scale that measures it (impedance or the scale's own value). */
    SCALE,

    /** Mifflin–St Jeor from weight, height, age and sex. */
    FORMULA,
}

/** Everything the automatic goal needs; a missing weight (no weighing yet) is asked in the goal sheet. */
data class GoalInputs(
    val sex: Sex,
    val age: Int,
    val heightCm: Double,
    val weightKg: Double?,
    /** BMR the scale gave at its latest weighing, when it measures it. */
    val scaleBmr: Double?,
    /** Active calories of the day from Health Connect, null when unknown. */
    val activeKcal: Double?,
    val aim: GoalAim,
)

/** How the automatic goal was computed, to explain it in plain words. */
data class GoalBreakdown(
    val bmr: Double,
    val bmrSource: BmrSource,
    /** BMR × 1.2 (resting day) when the day's active calories are known, else BMR × 1.375 (lightly active). */
    val base: Double,
    /** The day's active calories added to [base], null when an average activity was assumed instead. */
    val active: Double?,
    val maintenance: Double,
    val aim: GoalAim,
    val kcal: Double,
)

/** The day's targets (g) and limits. */
data class NutritionTargets(
    val kcal: Double,
    val protein: Double,
    val carbs: Double,
    val fat: Double,
    /** Upper limits. */
    val sugarsMax: Double,
    val satFatMax: Double,
    val saltMax: Double,
    /** Lower target. */
    val fiberMin: Double,
)

/** The user's own numbers in manual mode (macros optional: derived from the kcal when missing). */
data class ManualGoal(val kcal: Double, val protein: Double? = null, val carbs: Double? = null, val fat: Double? = null)

/**
 * Daily calorie and nutrient goal. Pure functions (unit-tested): an estimate for healthy adults,
 * not medical advice.
 * - BMR: the scale's when it measures it, else Mifflin–St Jeor.
 * - Calories: BMR × 1.2 + the day's active calories (Health Connect), or BMR × 1.375 when those are
 *   unknown; then −15 % to lose weight, +10 % to gain.
 * - Protein 1.6 g per kg (1.8 when gaining; 20 % of the calories without a weight), fat 30 % of the
 *   calories, carbohydrates the rest. Limits: sugars and saturated fat under 10 % of the calories
 *   each, salt under 5 g; fibre at least 25 g.
 */
object NutritionGoals {
    const val RESTING_FACTOR = 1.2
    const val LIGHT_ACTIVITY_FACTOR = 1.375
    const val SALT_MAX_G = 5.0
    const val FIBER_MIN_G = 25.0
    private const val FAT_SHARE = 0.30
    private const val PROTEIN_SHARE_WITHOUT_WEIGHT = 0.20
    private const val SUGARS_SHARE = 0.10
    private const val SAT_FAT_SHARE = 0.10
    private const val KCAL_PER_G_PROTEIN = 4.0
    private const val KCAL_PER_G_CARBS = 4.0
    private const val KCAL_PER_G_FAT = 9.0

    /** Mifflin–St Jeor (1990): 10·kg + 6.25·cm − 5·age + 5 (men) / − 161 (women). */
    fun mifflinStJeor(sex: Sex, weightKg: Double, heightCm: Double, age: Int): Double =
        10 * weightKg + 6.25 * heightCm - 5 * age + if (sex == Sex.MALE) 5 else -161

    /** Null when neither the scale's BMR nor a weight is known (the sheet asks for the weight). */
    fun auto(inputs: GoalInputs): GoalBreakdown? {
        val (bmr, source) = when {
            inputs.scaleBmr != null && inputs.scaleBmr > 0 -> inputs.scaleBmr to BmrSource.SCALE
            inputs.weightKg != null && inputs.weightKg > 0 && inputs.heightCm > 0 ->
                mifflinStJeor(inputs.sex, inputs.weightKg, inputs.heightCm, inputs.age) to BmrSource.FORMULA
            else -> return null
        }
        val active = inputs.activeKcal?.takeIf { it >= 0 && it.isFinite() }
        val base = bmr * if (active != null) RESTING_FACTOR else LIGHT_ACTIVITY_FACTOR
        val maintenance = base + (active ?: 0.0)
        return GoalBreakdown(
            bmr = bmr,
            bmrSource = source,
            base = base,
            active = active,
            maintenance = maintenance,
            aim = inputs.aim,
            kcal = roundKcal(maintenance * inputs.aim.factor),
        )
    }

    /** Targets for [kcal] a day; protein per kg of [weightKg] when known. */
    fun targets(kcal: Double, weightKg: Double?, aim: GoalAim): NutritionTargets {
        val protein = if (weightKg != null && weightKg > 0) {
            weightKg * if (aim == GoalAim.GAIN) 1.8 else 1.6
        } else {
            kcal * PROTEIN_SHARE_WITHOUT_WEIGHT / KCAL_PER_G_PROTEIN
        }
        val fat = kcal * FAT_SHARE / KCAL_PER_G_FAT
        return withMacros(kcal, protein, fat, carbs = null)
    }

    /** Manual mode: the user's kcal and the macros they typed (missing ones as in [targets]). */
    fun manual(goal: ManualGoal, weightKg: Double?): NutritionTargets {
        val defaults = targets(goal.kcal, weightKg, GoalAim.MAINTAIN)
        return withMacros(goal.kcal, goal.protein ?: defaults.protein, goal.fat ?: defaults.fat, goal.carbs)
    }

    private fun withMacros(kcal: Double, protein: Double, fat: Double, carbs: Double?): NutritionTargets = NutritionTargets(
        kcal = kcal,
        protein = round1(protein),
        carbs = round1(carbs ?: ((kcal - protein * KCAL_PER_G_PROTEIN - fat * KCAL_PER_G_FAT) / KCAL_PER_G_CARBS).coerceAtLeast(0.0)),
        fat = round1(fat),
        sugarsMax = round1(kcal * SUGARS_SHARE / KCAL_PER_G_CARBS),
        satFatMax = round1(kcal * SAT_FAT_SHARE / KCAL_PER_G_FAT),
        saltMax = SALT_MAX_G,
        fiberMin = FIBER_MIN_G,
    )

    /** Calorie goals are shown to the nearest 10 kcal. */
    fun roundKcal(v: Double): Double = ((v / 10).roundToInt() * 10).toDouble()

    private fun round1(v: Double): Double = (v * 10).roundToInt() / 10.0
}
