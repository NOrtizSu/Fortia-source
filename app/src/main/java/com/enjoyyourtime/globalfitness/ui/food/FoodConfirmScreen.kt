package com.enjoyyourtime.globalfitness.ui.food

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.enjoyyourtime.globalfitness.R
import com.enjoyyourtime.globalfitness.food.domain.FoodMath
import com.enjoyyourtime.globalfitness.food.model.FoodUnit
import com.enjoyyourtime.globalfitness.food.model.Meal
import com.enjoyyourtime.globalfitness.food.model.Nutrient
import com.enjoyyourtime.globalfitness.ui.Fmt
import com.enjoyyourtime.globalfitness.ui.components.AppCard
import com.enjoyyourtime.globalfitness.ui.components.AppIcons
import com.enjoyyourtime.globalfitness.ui.components.DatePickerModal
import com.enjoyyourtime.globalfitness.ui.components.InfoRow
import com.enjoyyourtime.globalfitness.ui.components.TimeInputDialog
import com.enjoyyourtime.globalfitness.ui.theme.TabularNumbers
import java.time.LocalDate

/**
 * Confirm a food (or edit an entry): how much (a portion of the catalog with a count, or grams /
 * ml with quick steps), the meal (by time of day unless changed), when, and the values it gives,
 * live. [editId]: the entry being edited (opened from the day view), null for a new one.
 */
@Composable
fun FoodConfirmScreen(editId: String?, onSaved: (fromPhoto: Boolean) -> Unit, onBack: () -> Unit) {
    val vm = foodViewModel()
    if (editId != null) {
        // Opened once per visit (a rotation keeps the edits in progress); again after the process was
        // recreated, when the view model no longer has the draft.
        var opened by rememberSaveable(editId) { mutableStateOf(false) }
        LaunchedEffect(editId) {
            if (!opened || vm.draft.value?.editingId != editId) {
                opened = true
                if (!vm.editEntry(editId)) onBack()
            }
        }
    } else {
        // A new food's draft does not survive the process: back to where it came from.
        LaunchedEffect(Unit) { if (vm.draft.value == null) onBack() }
    }
    val draftState by vm.draft.collectAsStateWithLifecycle()
    val d = draftState?.takeIf { editId == null || it.editingId == editId } ?: return
    val item = d.item
    val key = d.serial
    var amountText by rememberSaveable(key) { mutableStateOf(FoodFormat.count(FoodMath.round1(d.amount))) }
    var portionCode by rememberSaveable(key) { mutableStateOf(d.portion?.key?.code) }
    var count by rememberSaveable(key) { mutableDoubleStateOf(d.portionCount ?: 1.0) }
    var mealCode by rememberSaveable(key) { mutableStateOf(d.meal.code) }
    var eatenAt by rememberSaveable(key) { mutableLongStateOf(d.eatenAt) }
    var more by rememberSaveable(key) { mutableStateOf(false) }
    // The meal follows the time chosen until the user picks one.
    var mealPicked by rememberSaveable(key) { mutableStateOf(false) }
    // The page closes once the view model saved the draft; it stays (the snackbar says why) when that failed.
    val saveState by vm.draftSave.collectAsStateWithLifecycle()
    val save = saveState?.takeIf { it.serial == key }
    val saving = save != null && save.status != SaveStatus.FAILED
    LaunchedEffect(save?.status) { if (save?.status == SaveStatus.SAVED) onSaved(save.fromPhoto) }
    val a11y = LocalAccessibilityManager.current
    val undoMillis = remember(a11y) {
        a11y?.calculateRecommendedTimeoutMillis(FoodViewModel.UNDO_MS, containsIcons = true, containsText = true, containsControls = true)
            ?: FoodViewModel.UNDO_MS
    }
    var pickDate by rememberSaveable { mutableStateOf(false) }
    var pickTime by rememberSaveable { mutableStateOf(false) }

    // Portions of the catalog, plus the entry's own one when editing a portion the catalog no longer has.
    val portions = (item.portions + listOfNotNull(d.portion)).distinctBy { it.key }
    val portion = portions.firstOrNull { it.key.code == portionCode }
    val amount = Fmt.parseDecimal(amountText)?.takeIf { it > 0 && it < MAX_AMOUNT }
    val nutrients = amount?.let { FoodMath.nutrientsFor(item.per100, it) }
    val unitLabel = stringResource(if (item.unit == FoodUnit.ML) R.string.unit_ml else R.string.unit_g)

    fun setPortion(code: String?, newCount: Double) {
        portionCode = code
        count = newCount
        val p = portions.firstOrNull { it.key.code == code } ?: return
        amountText = FoodFormat.count(FoodMath.amountOf(p, newCount))
    }

    FoodSubPage(
        title = stringResource(if (d.editingId != null) R.string.foodlog_edit_title else R.string.foodlog_confirm_title),
        onBack = onBack,
        actions = {
            if (d.editingId != null) {
                IconButton(onClick = {
                    vm.deleteEntry(d.editingId, undoMillis)
                    onBack()
                }) { Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.action_delete)) }
            }
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Button(
                    onClick = {
                        val a = amount ?: return@Button
                        if (saving) return@Button
                        vm.saveDraft(a, portion?.key, if (portion != null) count else null, Meal.fromCode(mealCode), eatenAt)
                    },
                    enabled = amount != null && !saving,
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp).heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.action_save)) }
            }
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MealThumbnail(d.photo, 56.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.name, style = MaterialTheme.typography.titleLarge)
                    item.brand?.let { FoodSupporting(it) }
                    item.per100.kcal?.let { FoodSupporting(stringResource(FoodFormat.kcalPer100Res(item.unit), FoodFormat.kcal(it))) }
                }
            }

            Heading(stringResource(R.string.foodlog_amount))
            if (portions.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (p in portions) {
                        FilterChip(
                            selected = p.key.code == portionCode,
                            onClick = { setPortion(p.key.code, if (p.key.code == portionCode) count else 1.0) },
                            label = { Text(stringResource(R.string.foodlog_portion_chip, stringResource(FoodFormat.portionRes(p.key)), FoodFormat.quantity(p.amount, item.unit))) },
                        )
                    }
                    FilterChip(selected = portion == null, onClick = { portionCode = null }, label = { Text(stringResource(if (item.unit == FoodUnit.ML) R.string.foodlog_millilitres else R.string.foodlog_grams)) })
                }
            }
            if (portion != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilledTonalIconButton(onClick = { setPortion(portionCode, FoodMath.lessPortions(count)) }, enabled = count > FoodMath.MIN_PORTIONS) {
                        Text("−", style = MaterialTheme.typography.titleLarge, modifier = Modifier.labelled(stringResource(R.string.foodlog_portion_less)))
                    }
                    Text(
                        stringResource(R.string.foodlog_portion_count, FoodFormat.count(count), stringResource(FoodFormat.portionRes(portion.key))),
                        style = MaterialTheme.typography.titleMedium.merge(TabularNumbers),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.widthIn(min = 96.dp),
                    )
                    FilledTonalIconButton(onClick = { setPortion(portionCode, FoodMath.morePortions(count)) }) {
                        Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.foodlog_portion_more))
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { v ->
                        amountText = v.take(7)
                        portionCode = null
                    },
                    singleLine = true,
                    label = { Text(stringResource(if (item.unit == FoodUnit.ML) R.string.foodlog_millilitres else R.string.foodlog_grams)) },
                    suffix = { Text(unitLabel) },
                    isError = amount == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
                for (step in listOf(-10, 10, 50)) {
                    OutlinedButton(
                        onClick = {
                            val next = ((amount ?: 0.0) + step).coerceAtLeast(1.0)
                            amountText = FoodFormat.count(FoodMath.round1(next))
                            portionCode = null
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text(Fmt.signed(step.toDouble(), 0), style = MaterialTheme.typography.labelLarge.merge(TabularNumbers)) }
                }
            }

            Heading(stringResource(R.string.foodlog_meal))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (m in Meal.entries) {
                    FilterChip(
                        selected = m.code == mealCode,
                        onClick = {
                            mealCode = m.code
                            mealPicked = true
                        },
                        label = { Text(stringResource(FoodFormat.mealRes(m))) },
                    )
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { pickDate = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(Icons.Filled.DateRange, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(Fmt.relativeDay(FoodViewModel.dateOf(eatenAt), LocalDate.now()).replaceFirstChar { it.titlecase(Fmt.locale) })
                }
                OutlinedButton(onClick = { pickTime = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text(Fmt.time(eatenAt)) }
            }

            AppCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        nutrients?.kcal?.let { stringResource(R.string.value_kcal, FoodFormat.kcal(it)) } ?: "—",
                        style = MaterialTheme.typography.headlineSmall.merge(TabularNumbers),
                    )
                    for (n in Nutrient.MAIN.drop(1)) NutrientLine(n, nutrients?.get(n))
                    AnimatedVisibility(more) { Column { for (n in Nutrient.MORE) NutrientLine(n, nutrients?.get(n)) } }
                    TextButton(onClick = { more = !more }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(if (more) R.string.foodlog_less else R.string.foodlog_more))
                        Icon(if (more) AppIcons.ExpandLess else AppIcons.ExpandMore, contentDescription = null)
                    }
                    if (item.fromOpenFoodFacts) {
                        Text(stringResource(R.string.foodlog_off_attribution), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }

    if (pickDate) {
        DatePickerModal(
            initial = FoodViewModel.dateOf(eatenAt),
            onPick = { date -> eatenAt = FoodViewModel.at(date, FoodViewModel.timeOf(eatenAt)) },
            onDismiss = { pickDate = false },
        )
    }
    if (pickTime) {
        TimeInputDialog(
            initial = FoodViewModel.timeOf(eatenAt),
            onPick = { time ->
                eatenAt = FoodViewModel.at(FoodViewModel.dateOf(eatenAt), time)
                // A new entry follows the time chosen unless the meal was picked by hand.
                if (d.editingId == null && !mealPicked) mealCode = FoodMath.mealFor(time).code
            },
            onDismiss = { pickTime = false },
        )
    }
}

@Composable
private fun NutrientLine(n: Nutrient, v: Double?) {
    InfoRow(stringResource(FoodFormat.nutrientRes(n)), v?.let { stringResource(R.string.value_g, FoodFormat.grams(it)) } ?: "—")
}

@Composable
internal fun Heading(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp).semantics { heading() })
}

private const val MAX_AMOUNT = 10_000.0
