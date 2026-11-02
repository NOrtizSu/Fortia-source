package com.enjoyyourtime.globalfitness.food.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.enjoyyourtime.globalfitness.food.domain.GoalAim
import com.enjoyyourtime.globalfitness.food.domain.GoalMode
import com.enjoyyourtime.globalfitness.food.domain.ManualGoal
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The daily goal as the user set it (see [com.enjoyyourtime.globalfitness.food.domain.NutritionGoals]). */
data class GoalSettings(
    val mode: GoalMode = GoalMode.AUTO,
    val aim: GoalAim = GoalAim.MAINTAIN,
    /** Manual mode: the user's numbers (kcal required, macros optional). */
    val manual: ManualGoal? = null,
    /** Weight typed in the goal sheet while there is no weighing yet. */
    val weightKg: Double? = null,
)

/**
 * Preferences of the food log (own SharedPreferences file, part of Android's backup like "training").
 * The file is opened and read on [io] when the app starts (never on the main thread): until then the
 * flows hold the defaults, and a value set meanwhile wins over the one read.
 */
class FoodSettings(prefs: () -> SharedPreferences, scope: CoroutineScope, io: CoroutineDispatcher = Dispatchers.IO) {
    constructor(context: Context, scope: CoroutineScope) : this({ context.getSharedPreferences("food", Context.MODE_PRIVATE) }, scope)

    private val prefs: SharedPreferences by lazy(prefs)

    private val defaultGoal = GoalSettings()
    private val _goal = MutableStateFlow(defaultGoal)
    val goal: StateFlow<GoalSettings> = _goal.asStateFlow()

    private val _goalSet = MutableStateFlow(false)

    /** The user opened the goal sheet and saved it at least once (Hoy then shows the food card). */
    val goalSet: StateFlow<Boolean> = _goalSet.asStateFlow()

    private val _used = MutableStateFlow(false)

    /** Food was logged at least once on this phone (Hoy then shows the food card). */
    val used: StateFlow<Boolean> = _used.asStateFlow()

    private val _healthExport = MutableStateFlow<Boolean?>(null)

    /** "Save meals to Health Connect": null until touched, which means on once the permission is granted. */
    val healthExportChoice: StateFlow<Boolean?> = _healthExport.asStateFlow()

    private val _hcAsked = MutableStateFlow(false)

    /** The nutrition permission was asked in context once (after the first meal saved). */
    val healthPermissionAsked: StateFlow<Boolean> = _hcAsked.asStateFlow()

    init {
        scope.launch(io) { load() }
    }

    private fun load() {
        val p = prefs
        val goal = readGoal(p)
        // compareAndSet: a value the user set while the file was being read is kept.
        _goal.compareAndSet(defaultGoal, goal)
        if (p.getBoolean(KEY_GOAL_SET, false)) _goalSet.value = true
        if (p.getBoolean(KEY_USED, false)) _used.value = true
        if (p.contains(KEY_HC_EXPORT)) _healthExport.compareAndSet(null, p.getBoolean(KEY_HC_EXPORT, true))
        if (p.getBoolean(KEY_HC_ASKED, false)) _hcAsked.value = true
    }

    fun setGoal(goal: GoalSettings) {
        _goal.value = goal
        _goalSet.value = true
        prefs.edit {
            putString(KEY_MODE, goal.mode.code)
            putString(KEY_AIM, goal.aim.code)
            putDouble(KEY_MANUAL_KCAL, goal.manual?.kcal)
            putDouble(KEY_MANUAL_PROTEIN, goal.manual?.protein)
            putDouble(KEY_MANUAL_CARBS, goal.manual?.carbs)
            putDouble(KEY_MANUAL_FAT, goal.manual?.fat)
            putDouble(KEY_WEIGHT, goal.weightKg)
            putBoolean(KEY_GOAL_SET, true)
        }
    }

    fun markUsed() {
        if (_used.value) return
        _used.value = true
        prefs.edit { putBoolean(KEY_USED, true) }
    }

    fun setHealthExport(enabled: Boolean) {
        _healthExport.value = enabled
        prefs.edit { putBoolean(KEY_HC_EXPORT, enabled) }
    }

    fun markHealthPermissionAsked() {
        _hcAsked.value = true
        prefs.edit { putBoolean(KEY_HC_ASKED, true) }
    }

    private fun readGoal(p: SharedPreferences): GoalSettings {
        fun double(key: String): Double? = p.getString(key, null)?.toDoubleOrNull()
        val kcal = double(KEY_MANUAL_KCAL)
        return GoalSettings(
            mode = GoalMode.fromCode(p.getString(KEY_MODE, null)),
            aim = GoalAim.fromCode(p.getString(KEY_AIM, null)),
            manual = kcal?.let { ManualGoal(it, double(KEY_MANUAL_PROTEIN), double(KEY_MANUAL_CARBS), double(KEY_MANUAL_FAT)) },
            weightKg = double(KEY_WEIGHT),
        )
    }

    private fun SharedPreferences.Editor.putDouble(key: String, value: Double?) {
        if (value == null) remove(key) else putString(key, value.toString())
    }

    private companion object {
        const val KEY_MODE = "goal_mode"
        const val KEY_AIM = "goal_aim"
        const val KEY_MANUAL_KCAL = "goal_kcal"
        const val KEY_MANUAL_PROTEIN = "goal_protein"
        const val KEY_MANUAL_CARBS = "goal_carbs"
        const val KEY_MANUAL_FAT = "goal_fat"
        const val KEY_WEIGHT = "goal_weight"
        const val KEY_GOAL_SET = "goal_set"
        const val KEY_USED = "used"
        const val KEY_HC_EXPORT = "hc_export"
        const val KEY_HC_ASKED = "hc_asked"
    }
}
