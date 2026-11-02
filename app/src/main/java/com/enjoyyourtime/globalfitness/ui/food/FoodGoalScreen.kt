package com.enjoyyourtime.globalfitness.ui.food

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.enjoyyourtime.globalfitness.R
import com.enjoyyourtime.globalfitness.food.data.GoalSettings
import com.enjoyyourtime.globalfitness.food.domain.BmrSource
import com.enjoyyourtime.globalfitness.food.domain.GoalAim
import com.enjoyyourtime.globalfitness.food.domain.GoalMode
import com.enjoyyourtime.globalfitness.food.domain.ManualGoal
import com.enjoyyourtime.globalfitness.food.domain.NutritionGoals
import com.enjoyyourtime.globalfitness.food.domain.NutritionTargets
import com.enjoyyourtime.globalfitness.ui.Fmt
import com.enjoyyourtime.globalfitness.ui.components.AppCard
import com.enjoyyourtime.globalfitness.ui.components.InfoRow
import com.enjoyyourtime.globalfitness.ui.health.FoodExportRow

/**
 * The daily goal: automatic (BMR of the scale or Mifflin–St Jeor, plus the day's activity from
 * Health Connect, adjusted to lose / keep / gain weight; explained line by line) or the user's own
 * numbers, the targets and limits that follow, and saving meals to Health Connect.
 */
@Composable
fun FoodGoalScreen(onBack: () -> Unit) {
    val vm = foodViewModel()
    val goal by vm.goal.collectAsStateWithLifecycle()
    val saved = goal.settings
    var modeCode by rememberSaveable { mutableStateOf(saved.mode.code) }
    var aimCode by rememberSaveable { mutableStateOf(saved.aim.code) }
    var weightText by rememberSaveable { mutableStateOf(saved.weightKg?.let { Fmt.num(it, 1) }.orEmpty()) }
    // Without grouping: "2,300" would read back as 2.3.
    var kcalText by rememberSaveable { mutableStateOf(saved.manual?.kcal?.let { Fmt.num(it, 0) }.orEmpty()) }
    var proteinText by rememberSaveable { mutableStateOf(saved.manual?.protein?.let(FoodFormat::count).orEmpty()) }
    var carbsText by rememberSaveable { mutableStateOf(saved.manual?.carbs?.let(FoodFormat::count).orEmpty()) }
    var fatText by rememberSaveable { mutableStateOf(saved.manual?.fat?.let(FoodFormat::count).orEmpty()) }
    val mode = GoalMode.fromCode(modeCode)
    val aim = GoalAim.fromCode(aimCode)
    val typedWeight = Fmt.parseDecimal(weightText)?.takeIf { it in 20.0..400.0 }
    val kcal = Fmt.parseDecimal(kcalText)?.takeIf { it in 500.0..10_000.0 }
    fun grams(text: String): Double? = Fmt.parseDecimal(text)?.takeIf { it in 0.0..MAX_MACRO_G }
    val manual = kcal?.let { ManualGoal(it, grams(proteinText), grams(carbsText), grams(fatText)) }
    val canSave = mode == GoalMode.AUTO || manual != null

    fun save() {
        vm.saveGoal(
            GoalSettings(
                mode = mode,
                aim = aim,
                manual = manual ?: saved.manual,
                weightKg = typedWeight ?: saved.weightKg,
            ),
        )
        onBack()
    }

    FoodSubPage(
        title = stringResource(R.string.foodlog_goal_title),
        onBack = onBack,
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Button(onClick = ::save, enabled = canSave, modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp).heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.action_save))
                }
            }
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Every segment as tall as the tallest label (a label may take two lines, see SegmentLabel).
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                GoalMode.entries.forEachIndexed { i, m ->
                    SegmentedButton(
                        selected = m == mode,
                        onClick = { modeCode = m.code },
                        shape = SegmentedButtonDefaults.itemShape(i, GoalMode.entries.size),
                        contentPadding = SegmentContentPadding,
                        modifier = Modifier.fillMaxHeight(),
                        label = { SegmentLabel(stringResource(if (m == GoalMode.AUTO) R.string.foodlog_goal_auto else R.string.foodlog_goal_manual)) },
                    )
                }
            }
            if (mode == GoalMode.AUTO) {
                Heading(stringResource(R.string.foodlog_goal_aim))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                    GoalAim.entries.forEachIndexed { i, a ->
                        SegmentedButton(
                            selected = a == aim,
                            onClick = { aimCode = a.code },
                            shape = SegmentedButtonDefaults.itemShape(i, GoalAim.entries.size),
                            contentPadding = SegmentContentPadding,
                            modifier = Modifier.fillMaxHeight(),
                            // "Prendre du poids", "Kiloyu korumak": two lines, smaller only if a word is still too long.
                            label = { SegmentLabel(stringResource(aimRes(a))) },
                        )
                    }
                }
                if (!goal.weightMeasured) {
                    FoodSupporting(stringResource(R.string.foodlog_goal_weight_text))
                    OutlinedTextField(
                        value = weightText,
                        onValueChange = { weightText = it.take(6) },
                        singleLine = true,
                        label = { Text(stringResource(R.string.foodlog_goal_weight)) },
                        suffix = { Text(stringResource(R.string.unit_kg)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                AutoExplanation(vm.previewAuto(aim, typedWeight), aim)
            } else {
                FoodSupporting(stringResource(R.string.foodlog_goal_manual_text))
                NumberField(kcalText, { kcalText = it }, stringResource(R.string.common_required_mark, stringResource(R.string.foodlog_nutrient_kcal)), stringResource(R.string.unit_kcal))
                NumberField(proteinText, { proteinText = it }, stringResource(R.string.foodlog_nutrient_protein), stringResource(R.string.unit_g))
                NumberField(carbsText, { carbsText = it }, stringResource(R.string.foodlog_nutrient_carbs), stringResource(R.string.unit_g))
                NumberField(fatText, { fatText = it }, stringResource(R.string.foodlog_nutrient_fat), stringResource(R.string.unit_g))
                manual?.let { TargetsCard(NutritionGoals.manual(it, goal.weightKg ?: typedWeight)) }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest)
            FoodExportRow()
            FoodSupporting(stringResource(R.string.foodlog_goal_disclaimer))
        }
    }
}

/** The automatic goal in plain words, with the aim and weight being chosen. */
@Composable
private fun AutoExplanation(preview: GoalUi, aim: GoalAim) {
    val b = preview.breakdown
    val targets = preview.targets
    if (b == null || targets == null) {
        FoodSupporting(stringResource(R.string.foodlog_goal_needs_weight))
        return
    }
    AppCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.foodlog_goal_how), style = MaterialTheme.typography.titleSmall)
            InfoRow(
                stringResource(if (b.bmrSource == BmrSource.SCALE) R.string.foodlog_goal_bmr_scale else R.string.foodlog_goal_bmr_formula),
                stringResource(R.string.value_kcal, FoodFormat.kcal(b.bmr)),
            )
            if (b.active != null) {
                InfoRow(stringResource(R.string.foodlog_goal_resting), stringResource(R.string.value_kcal, FoodFormat.kcal(b.base)))
                InfoRow(stringResource(R.string.foodlog_goal_active), stringResource(R.string.foodlog_plus_kcal, FoodFormat.kcal(b.active)))
            } else {
                InfoRow(stringResource(R.string.foodlog_goal_light), stringResource(R.string.value_kcal, FoodFormat.kcal(b.base)))
                FoodSupporting(stringResource(R.string.foodlog_goal_no_activity))
            }
            InfoRow(stringResource(R.string.foodlog_goal_maintenance), stringResource(R.string.value_kcal, FoodFormat.kcal(b.maintenance)))
            if (aim != GoalAim.MAINTAIN) {
                FoodSupporting(stringResource(if (aim == GoalAim.LOSE) R.string.foodlog_goal_lose_adjust else R.string.foodlog_goal_gain_adjust))
            }
            Text(
                stringResource(R.string.foodlog_goal_result, FoodFormat.kcal(b.kcal)),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
    TargetsCard(targets, perKg = preview.weightKg != null, aim = aim)
}

@Composable
private fun TargetsCard(t: NutritionTargets, perKg: Boolean = false, aim: GoalAim = GoalAim.MAINTAIN) {
    AppCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.foodlog_goal_targets), style = MaterialTheme.typography.titleSmall)
            InfoRow(
                if (perKg) stringResource(R.string.foodlog_goal_protein_per_kg, Fmt.num(if (aim == GoalAim.GAIN) 1.8 else 1.6, 1)) else stringResource(R.string.foodlog_nutrient_protein),
                stringResource(R.string.value_g, FoodFormat.grams(t.protein)),
            )
            InfoRow(stringResource(R.string.foodlog_goal_fat_share), stringResource(R.string.value_g, FoodFormat.grams(t.fat)))
            InfoRow(stringResource(R.string.foodlog_goal_carbs_rest), stringResource(R.string.value_g, FoodFormat.grams(t.carbs)))
            Text(stringResource(R.string.foodlog_goal_limits), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            InfoRow(stringResource(R.string.foodlog_goal_sugars_limit), stringResource(R.string.foodlog_less_than, stringResource(R.string.value_g, FoodFormat.grams(t.sugarsMax))))
            InfoRow(stringResource(R.string.foodlog_goal_sat_fat_limit), stringResource(R.string.foodlog_less_than, stringResource(R.string.value_g, FoodFormat.grams(t.satFatMax))))
            InfoRow(stringResource(R.string.foodlog_nutrient_salt), stringResource(R.string.foodlog_less_than, stringResource(R.string.value_g, FoodFormat.grams(t.saltMax))))
            InfoRow(stringResource(R.string.foodlog_nutrient_fiber), stringResource(R.string.foodlog_at_least, stringResource(R.string.value_g, FoodFormat.grams(t.fiberMin))))
        }
    }
}

@Composable
private fun NumberField(value: String, onChange: (String) -> Unit, label: String, suffix: String) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.take(6)) },
        singleLine = true,
        label = { Text(label) },
        suffix = { Text(suffix) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Highest daily grams of one macronutrient accepted in the manual goal. */
private const val MAX_MACRO_G = 1_000.0

private fun aimRes(a: GoalAim): Int = when (a) {
    GoalAim.LOSE -> R.string.foodlog_goal_lose
    GoalAim.MAINTAIN -> R.string.foodlog_goal_maintain
    GoalAim.GAIN -> R.string.foodlog_goal_gain
}
