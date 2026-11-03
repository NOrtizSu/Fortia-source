package com.enjoyyourtime.globalfitness.ui.food

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.enjoyyourtime.globalfitness.R
import com.enjoyyourtime.globalfitness.food.model.FoodUnit
import com.enjoyyourtime.globalfitness.food.model.Nutrient
import com.enjoyyourtime.globalfitness.food.model.Nutrients
import com.enjoyyourtime.globalfitness.ui.Fmt
import com.enjoyyourtime.globalfitness.ui.components.AppIcons

/**
 * A food by hand: name, amount and its kcal (required), the other values if known. It can be kept
 * as the user's own food (to find it in the search next time). A product found by barcode with
 * missing values opens here to complete them (per 100 g / ml).
 */
@Composable
fun FoodManualScreen(onOpen: (String) -> Unit, onBack: () -> Unit) {
    val vm = foodViewModel()
    val draft by vm.manual.collectAsStateWithLifecycle()
    val key = draft.serial
    var name by rememberSaveable(key) { mutableStateOf(draft.name) }
    var unitCode by rememberSaveable(key) { mutableStateOf(draft.unit.code) }
    var amountText by rememberSaveable(key) { mutableStateOf(draft.amount?.let(FoodFormat::count).orEmpty()) }
    val values = Nutrient.entries.associateWith { n ->
        // Without grouping (a "1,250" kcal would read back as 1.25).
        rememberSaveable(key, n) { mutableStateOf(draft.values[n]?.let { if (n == Nutrient.KCAL) Fmt.num(it, 0) else FoodFormat.count(it) }.orEmpty()) }
    }
    var save by rememberSaveable(key) { mutableStateOf(draft.saveAsFood) }
    var more by rememberSaveable(key) { mutableStateOf((Nutrient.MORE + Nutrient.EXTRA).any { draft.values[it] != null }) }
    val completing = draft.completing
    val editOnly = draft.editOnly
    val unit = FoodUnit.fromCode(unitCode)
    val amount = Fmt.parseDecimal(amountText)?.takeIf { it > 0 && it < 10_000 }
    fun parsed(n: Nutrient): Double? = Fmt.parseDecimal(values.getValue(n).value)?.takeIf { it >= 0 && it < 100_000 }
    var submitting by remember(key) { mutableStateOf(false) }
    val kcal = parsed(Nutrient.KCAL)
    val valid = name.isNotBlank() && amount != null && kcal != null

    val title = when {
        editOnly -> R.string.foodlog_edit_food_title
        completing -> R.string.foodlog_complete_title
        else -> R.string.foodlog_manual_title
    }
    FoodSubPage(
        title = stringResource(title),
        onBack = onBack,
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Button(
                    onClick = {
                        val a = amount ?: return@Button
                        if (submitting) return@Button
                        submitting = true
                        var n = Nutrients.NONE
                        for (nutrient in Nutrient.entries) n = n.with(nutrient, parsed(nutrient))
                        // Opened once the food (and its draft) exists; the form stays when it could not be saved.
                        vm.submitManual(name.trim(), draft.brand, unit, a, n, save || completing || editOnly) { done ->
                            submitting = false
                            when {
                                !done -> Unit
                                editOnly -> onBack()
                                else -> onOpen(FoodRoutes.CONFIRM)
                            }
                        }
                    },
                    enabled = valid && !submitting,
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp).heightIn(min = 48.dp),
                ) { Text(stringResource(if (editOnly) R.string.action_save else R.string.action_continue)) }
            }
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            draft.barcode?.let { FoodSupporting(stringResource(R.string.foodlog_barcode_code, it)) }
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(120) },
                singleLine = true,
                label = { Text(stringResource(R.string.common_required_mark, stringResource(R.string.foodlog_manual_name))) },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )
            // Every segment as tall as the tallest label (a label may take two lines).
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                FoodUnit.entries.forEachIndexed { i, u ->
                    SegmentedButton(
                        selected = u.code == unitCode,
                        onClick = { unitCode = u.code },
                        shape = SegmentedButtonDefaults.itemShape(i, FoodUnit.entries.size),
                        contentPadding = SegmentContentPadding,
                        modifier = Modifier.fillMaxHeight(),
                        label = { SegmentLabel(stringResource(if (u == FoodUnit.ML) R.string.foodlog_millilitres else R.string.foodlog_grams)) },
                    )
                }
            }
            OutlinedTextField(
                value = amountText,
                onValueChange = { amountText = it.take(7) },
                singleLine = true,
                label = { Text(stringResource(R.string.common_required_mark, stringResource(R.string.foodlog_amount))) },
                suffix = { Text(stringResource(if (unit == FoodUnit.ML) R.string.unit_ml else R.string.unit_g)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
            Heading(
                if (amount != null) {
                    stringResource(R.string.foodlog_manual_values_for, FoodFormat.quantity(amount, unit))
                } else {
                    stringResource(R.string.foodlog_manual_values)
                },
            )
            for (n in Nutrient.MAIN) ValueField(n, values.getValue(n).value, required = n == Nutrient.KCAL) { values.getValue(n).value = it }
            AnimatedVisibility(more) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    for (n in Nutrient.MORE) ValueField(n, values.getValue(n).value, required = false) { values.getValue(n).value = it }
                    // The values beyond the label, all optional (a product label rarely has them).
                    Heading(stringResource(R.string.foodlog_more_nutrients))
                    FoodSupporting(stringResource(R.string.foodlog_more_nutrients_text))
                    for ((title, list) in listOf(
                        R.string.foodlog_nutrients_fats to Nutrient.FATS,
                        R.string.foodlog_nutrients_minerals to Nutrient.MINERALS,
                        R.string.foodlog_nutrients_vitamins to Nutrient.VITAMINS,
                    )) {
                        Text(stringResource(title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        for (n in list) ValueField(n, values.getValue(n).value, required = false) { values.getValue(n).value = it }
                    }
                }
            }
            TextButton(onClick = { more = !more }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(if (more) R.string.foodlog_less else R.string.foodlog_more))
                Icon(if (more) AppIcons.ExpandLess else AppIcons.ExpandMore, contentDescription = null)
            }
            if (!completing && !editOnly) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = save, role = Role.Checkbox, onValueChange = { save = it }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = save, onCheckedChange = null)
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(stringResource(R.string.foodlog_manual_save_food), style = MaterialTheme.typography.bodyLarge)
                        FoodSupporting(stringResource(R.string.foodlog_manual_save_food_text))
                    }
                }
            }
        }
    }
}

@Composable
private fun ValueField(n: Nutrient, value: String, required: Boolean, onChange: (String) -> Unit) {
    val label = stringResource(FoodFormat.nutrientRes(n))
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.take(7)) },
        singleLine = true,
        label = { Text(if (required) stringResource(R.string.common_required_mark, label) else label) },
        suffix = { Text(stringResource(FoodFormat.unitRes(n))) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
    )
}
