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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
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
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.enjoyyourtime.globalfitness.R
import com.enjoyyourtime.globalfitness.food.domain.DraftOption
import com.enjoyyourtime.globalfitness.food.domain.FoodDrafts
import com.enjoyyourtime.globalfitness.food.domain.FoodMath
import com.enjoyyourtime.globalfitness.food.domain.IngredientChoice
import com.enjoyyourtime.globalfitness.food.domain.IngredientInfo
import com.enjoyyourtime.globalfitness.food.domain.Recall
import com.enjoyyourtime.globalfitness.food.domain.RecipeMath
import com.enjoyyourtime.globalfitness.food.domain.ValueEdits
import com.enjoyyourtime.globalfitness.food.model.AdjustLevel
import com.enjoyyourtime.globalfitness.food.model.FoodUnit
import com.enjoyyourtime.globalfitness.food.model.IngredientAdjustment
import com.enjoyyourtime.globalfitness.food.model.Meal
import com.enjoyyourtime.globalfitness.food.model.Nutrient
import com.enjoyyourtime.globalfitness.food.model.RecipeIngredient
import com.enjoyyourtime.globalfitness.ui.Fmt
import com.enjoyyourtime.globalfitness.ui.components.AppCard
import com.enjoyyourtime.globalfitness.ui.components.AppIcons
import com.enjoyyourtime.globalfitness.ui.components.DatePickerModal
import com.enjoyyourtime.globalfitness.ui.components.InfoRow
import com.enjoyyourtime.globalfitness.ui.components.TimeInputDialog
import com.enjoyyourtime.globalfitness.ui.theme.TabularNumbers
import java.time.LocalDate
import kotlin.math.abs

/**
 * Confirm a food (or edit an entry): which variant of a dish, how much (a portion of the catalog with
 * a count, or grams / ml with quick steps; the photo's estimate as a suggestion), the dish's
 * ingredients (more or less oil, no bread…), the meal (by time of day unless changed), when, and the
 * values it gives, live; those values can be typed by hand ("Edit values", for the amount shown). A
 * food logged before starts as its last entry ("As last time", with "Ignore last time").
 * [editId]: the entry being edited (opened from the day view), null for a new one.
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
    val key = d.serial
    var optionId by rememberSaveable(key) { mutableStateOf(d.item.foodId) }
    var amountText by rememberSaveable(key) { mutableStateOf(FoodFormat.count(FoodMath.round1(d.amount))) }
    var portionCode by rememberSaveable(key) { mutableStateOf(d.portion?.key?.code) }
    var count by rememberSaveable(key) { mutableDoubleStateOf(d.portionCount ?: 1.0) }
    var mealCode by rememberSaveable(key) { mutableStateOf(d.meal.code) }
    var eatenAt by rememberSaveable(key) { mutableLongStateOf(d.eatenAt) }
    var more by rememberSaveable(key) { mutableStateOf(false) }
    var choicesCode by rememberSaveable(key) { mutableStateOf(IngredientChoices.encode(d.startChoices)) }
    var ingredientsOpen by rememberSaveable(key) { mutableStateOf(d.startChoices.isNotEmpty()) }
    // As the food's last entry until "Ignore last time" (another variant: as its own last entry).
    var recallOff by rememberSaveable(key) { mutableStateOf(false) }
    // Values typed by hand, per 100 g of the dish ([ValueFields]); "Restore" taken ([ValueEdits.values]).
    var overridesCode by rememberSaveable(key) { mutableStateOf("") }
    var restored by rememberSaveable(key) { mutableStateOf(false) }
    var editValues by rememberSaveable(key) { mutableStateOf(false) }
    // The texts of the value fields typed by the user, while the amount, ingredients and food stay those they were typed for.
    var valueTexts by rememberSaveable(key) { mutableStateOf("") }
    var valueTextsFor by rememberSaveable(key) { mutableStateOf("") }
    var valuesReset by rememberSaveable(key) { mutableIntStateOf(0) }
    // The meal follows the time chosen until the user picks one.
    var mealPicked by rememberSaveable(key) { mutableStateOf(false) }
    // The amount follows the photo's suggestion (for each variant) until the user sets one.
    var amountSet by rememberSaveable(key) { mutableStateOf(false) }
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

    val option = d.options.firstOrNull { it.item.foodId != null && it.item.foodId == optionId } ?: d.current
    val item = option.item
    // Portions of the food, plus the entry's own one when editing a portion the catalog no longer has.
    val portions = (item.portions + listOfNotNull(d.portion.takeIf { option.item.ref == d.item.ref })).distinctBy { it.key }
    val portion = portions.firstOrNull { it.key.code == portionCode }
    val dishAmount = Fmt.parseDecimal(amountText)?.takeIf { it > 0 && it < MAX_AMOUNT }
    val choices = IngredientChoices.decode(choicesCode)
    val adjustments = dishAmount?.let { RecipeMath.adjustments(option.recipe, it, choices) }.orEmpty()
    val ingredientPer100 = { id: String -> d.ingredients[id]?.per100 }
    val recall = option.recall?.takeIf { !recallOff && d.editingId == null }
    // The values per 100 g: the food's own (or the edited entry's), or those typed last time; then those typed now.
    val valueBase = ValueEdits.base(option, recall, editingEntry = d.editingId != null && item.ref == d.item.ref, entryEdited = d.valuesEdited)
    val overrides = ValueFields.decodeOverrides(overridesCode)
    val per100 = ValueEdits.values(valueBase, restored, overrides)
    val dish = dishAmount?.let { RecipeMath.apply(per100, it, adjustments, ingredientPer100) }
    val nutrients = dish?.nutrients
    val reference = dishAmount?.let { a -> valueBase.reference?.let { RecipeMath.apply(it, a, adjustments, ingredientPer100).nutrients } }
    val valuesEdited = nutrients != null && ValueEdits.savedEdited(valueBase, restored, overrides, nutrients, reference, hasFood = item.ref != null)
    val restorable = ValueEdits.restorable(valueBase, restored, overrides)
    // The value fields show what was typed for this amount, ingredients and food, else the values as they are now.
    val valuesContext = listOf(amountText, choicesCode, optionId, restored, recallOff, valuesReset).joinToString("|")
    val typed = if (valueTextsFor == valuesContext) ValueFields.decodeTexts(valueTexts) else emptyMap()
    val invalidValues = editValues && typed.values.any { it.isNotBlank() && ValueFields.parse(it) == null }
    val unitLabel = stringResource(if (item.unit == FoodUnit.ML) R.string.unit_ml else R.string.unit_g)
    // The photo's suggestion for the food shown (each variant has its own portion, so its own estimate);
    // an entry being edited keeps the one it was logged with.
    val estimate = if (d.editingId != null) d.estimatedAmount else d.grams?.estimate(item.portion, 1.0) ?: d.estimatedAmount.takeIf { item.ref == d.item.ref }

    fun valueText(n: Nutrient): String = typed[n] ?: nutrients?.get(n)?.let { FoodFormat.field(n, it) }.orEmpty()

    /** A value typed under what the ingredient changes alone add: what is kept instead (null when it is what was typed). */
    fun kept(n: Nutrient): Double? {
        val value = typed[n]?.let(ValueFields::parse) ?: return null
        return nutrients?.get(n)?.let { ValueEdits.keptInstead(n, value, it) }
    }

    /** A value typed for the amount shown: kept as the dish's own value per 100 g (a cleared field is unknown). */
    fun typeValue(n: Nutrient, raw: String) {
        val text = ValueFields.clean(raw)
        valueTexts = ValueFields.encodeTexts(typed + (n to text))
        valueTextsFor = valuesContext
        val a = dishAmount ?: return
        val total = if (text.isBlank()) null else ValueFields.parse(text) ?: return
        overridesCode = ValueFields.encodeOverrides(overrides + (n to total?.let { ValueEdits.per100Of(n, it, a, adjustments, ingredientPer100) }))
    }

    /** "Restore": nothing typed now, and the food's own values again when the values were typed by hand before. */
    fun restoreValues() {
        overridesCode = ""
        if (valueBase.restore != null) restored = true
        valuesReset++
    }

    /** "Ignore last time": nothing from the last entry (the photo's suggestion or the default portion, the food's values, no changes). */
    fun useUsual() {
        recallOff = true
        restored = false
        overridesCode = ""
        choicesCode = ""
        amountSet = false
        valuesReset++
        val start = FoodDrafts.startAmount(item, null, estimate)
        portionCode = start.portion?.key?.code
        count = start.portionCount ?: 1.0
        amountText = FoodFormat.count(FoodMath.round1(start.amount))
    }

    fun setPortion(code: String?, newCount: Double) {
        portionCode = code
        count = newCount
        amountSet = true
        val p = portions.firstOrNull { it.key.code == code } ?: return
        amountText = FoodFormat.count(FoodMath.amountOf(p, newCount))
    }

    fun switchTo(o: DraftOption) {
        if (o.item.foodId == optionId) return
        optionId = o.item.foodId
        // Another dish: its own ingredients and values, all as usual, or as its own last entry.
        val last = o.recall?.takeIf { d.editingId == null }
        // The amount shown was the previous dish's last entry's: this one starts with its own defaults.
        val fromRecall = recall != null && !amountSet
        choicesCode = IngredientChoices.encode(last?.choices.orEmpty())
        overridesCode = ""
        restored = false
        recallOff = false
        valuesReset++
        val suggested = d.grams?.estimate(o.item.portion, 1.0).takeIf { !amountSet && d.editingId == null }
        val start = FoodDrafts.switchAmount(o.item, last, fromRecall, suggested, portionCode, count)
        if (start == null) {
            portionCode = null
            return
        }
        portionCode = start.portion?.key?.code
        start.portionCount?.let { count = it }
        amountText = FoodFormat.count(FoodMath.round1(start.amount))
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
                        val a = dishAmount ?: return@Button
                        if (saving || invalidValues) return@Button
                        vm.saveDraft(
                            DraftChoice(
                                item.copy(per100 = per100), a, adjustments, portion?.key, if (portion != null) count else null, Meal.fromCode(mealCode), eatenAt, estimate,
                                valuesEdited = valuesEdited,
                            ),
                        )
                    },
                    enabled = dishAmount != null && !saving && !invalidValues,
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
                    per100.kcal?.let { FoodSupporting(stringResource(FoodFormat.kcalPer100Res(item.unit), FoodFormat.kcal(it))) }
                }
            }

            recall?.let { r ->
                RecallNote(r, amount = true, values = r.per100 != null && !restored, onUsual = { useUsual() })
            }

            if (d.options.size > 1) {
                Heading(stringResource(R.string.foodlog_variants))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    d.options.forEachIndexed { i, o ->
                        FilterChip(
                            selected = o.item.ref == item.ref,
                            onClick = { switchTo(o) },
                            label = { Text(if (i == 0) stringResource(R.string.foodlog_variant_typical) else o.item.name) },
                        )
                    }
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
                        amountSet = true
                    },
                    singleLine = true,
                    label = { Text(stringResource(if (item.unit == FoodUnit.ML) R.string.foodlog_millilitres else R.string.foodlog_grams)) },
                    suffix = { Text(unitLabel) },
                    isError = dishAmount == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
                for (step in listOf(-10, 10, 50)) {
                    OutlinedButton(
                        onClick = {
                            val next = ((dishAmount ?: 0.0) + step).coerceAtLeast(1.0)
                            amountText = FoodFormat.count(FoodMath.round1(next))
                            portionCode = null
                            amountSet = true
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text(Fmt.signed(step.toDouble(), 0), style = MaterialTheme.typography.labelLarge.merge(TabularNumbers)) }
                }
            }
            estimate?.let { e ->
                val suggested = FoodMath.round1(e)
                // Another amount shows (last time's, or one typed): "Use" switches to the photo's suggestion.
                val use = {
                    amountText = FoodFormat.count(suggested)
                    portionCode = null
                    amountSet = true
                }
                val offer = d.editingId == null && (dishAmount == null || abs(dishAmount - suggested) >= 0.05)
                EstimateNote(e, item.unit, explain = !offer, onUse = use.takeIf { offer })
            }

            val adjustable = option.recipe.filter { it.adjustable && it.ingredient in d.ingredients }
            if (adjustable.isNotEmpty()) {
                IngredientsSection(
                    recipe = adjustable,
                    ingredients = d.ingredients,
                    dishAmount = dishAmount,
                    choices = choices,
                    adjustments = adjustments,
                    open = ingredientsOpen,
                    onOpen = { ingredientsOpen = it },
                    onChoice = { id, c -> choicesCode = IngredientChoices.encode(choices + (id to c)) },
                    totalAmount = dish?.amount?.takeIf { adjustments.isNotEmpty() },
                    unit = item.unit,
                )
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
                    if (editValues) {
                        // The values for the amount shown, as fields (more of them under "More").
                        for (n in Nutrient.MAIN) ValueField(n, valueText(n), kept(n), enabled = dishAmount != null) { typeValue(n, it) }
                        AnimatedVisibility(more) {
                            Column {
                                for (n in Nutrient.MORE) ValueField(n, valueText(n), kept(n), enabled = dishAmount != null) { typeValue(n, it) }
                                for ((title, list) in NUTRIENT_GROUPS) {
                                    Text(
                                        stringResource(title),
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(top = 8.dp).semantics { heading() },
                                    )
                                    for (n in list) ValueField(n, valueText(n), kept(n), enabled = dishAmount != null) { typeValue(n, it) }
                                }
                            }
                        }
                    } else {
                        Text(
                            nutrients?.kcal?.let { stringResource(R.string.value_kcal, FoodFormat.kcal(it)) } ?: "—",
                            style = MaterialTheme.typography.headlineSmall.merge(TabularNumbers),
                        )
                        for (n in Nutrient.MAIN.drop(1)) NutrientLine(n, nutrients?.get(n))
                        AnimatedVisibility(more) {
                            Column {
                                for (n in Nutrient.MORE) NutrientLine(n, nutrients?.get(n))
                                nutrients?.let { ExtraNutrientRows(it) }
                            }
                        }
                    }
                    TextButton(onClick = { more = !more }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(if (more) R.string.foodlog_less else R.string.foodlog_more))
                        Icon(if (more) AppIcons.ExpandLess else AppIcons.ExpandMore, contentDescription = null)
                    }
                    when {
                        editValues -> FoodSupporting(stringResource(R.string.foodlog_values_follow))
                        valuesEdited -> FoodSupporting(stringResource(R.string.foodlog_values_edited))
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            onClick = {
                                // Closing the fields: what shows is what was kept (a text that is not a number changed nothing).
                                if (editValues) valueTextsFor = ""
                                editValues = !editValues
                            },
                            enabled = !editValues || !invalidValues,
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            if (!editValues) {
                                Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.padding(end = 8.dp).size(18.dp))
                            }
                            Text(stringResource(if (editValues) R.string.foodlog_done else R.string.foodlog_values_edit))
                        }
                        if (restorable) {
                            TextButton(onClick = { restoreValues() }, modifier = Modifier.heightIn(min = 48.dp)) {
                                Text(stringResource(R.string.foodlog_values_restore))
                            }
                        }
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

/**
 * "≈ 180 g (estimated)" under the amount: the photo's suggestion, which the user confirms or changes;
 * with [onUse] (another amount shows, such as last time's) a "Use" button switches to it.
 */
@Composable
internal fun EstimateNote(estimate: Double, unit: FoodUnit, modifier: Modifier = Modifier, explain: Boolean = true, onUse: (() -> Unit)? = null) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                stringResource(R.string.foodlog_estimated, FoodFormat.quantity(FoodMath.round1(estimate), unit)),
                style = MaterialTheme.typography.bodyMedium.merge(TabularNumbers),
                color = MaterialTheme.colorScheme.primary,
            )
            if (explain) FoodSupporting(stringResource(R.string.foodlog_estimated_text))
        }
        if (onUse != null) {
            TextButton(onClick = onUse, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.foodlog_estimated_use)) }
        }
    }
}

/**
 * "As last time (Mon 2 Nov)": a food logged before starts as its last entry; says what it took from
 * it ([amount], the values typed by hand ([values]), the ingredients changed) and offers "Ignore
 * last time" ([onUsual]).
 */
@Composable
internal fun RecallNote(recall: Recall, amount: Boolean, values: Boolean, onUsual: () -> Unit, modifier: Modifier = Modifier) {
    val parts = listOfNotNull(
        R.string.foodlog_recall_amount.takeIf { amount },
        R.string.foodlog_recall_values.takeIf { values },
        R.string.foodlog_recall_ingredients.takeIf { recall.choices.isNotEmpty() },
    ).map { stringResource(it) }
    AppCard(modifier, containerColor = MaterialTheme.colorScheme.secondaryContainer) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
            val color = MaterialTheme.colorScheme.onSecondaryContainer
            // "mar, 3 nov", with the year when it is another year's.
            val date = if (recall.date.year == LocalDate.now().year) Fmt.weekdayDayMonth(recall.date) else Fmt.date(recall.date)
            Text(stringResource(R.string.foodlog_recall_title, date), style = MaterialTheme.typography.titleSmall, color = color)
            if (parts.isNotEmpty()) {
                Text(stringResource(R.string.foodlog_recall_text, Fmt.list(parts)), style = MaterialTheme.typography.bodyMedium, color = color)
            }
            TextButton(onClick = onUsual, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.foodlog_recall_reset)) }
        }
    }
}

/**
 * A value of the values card as a field: for the amount shown, in its unit; empty is unknown. [kept]:
 * what is saved instead of a value typed under what the changed ingredients alone add.
 */
@Composable
private fun ValueField(n: Nutrient, text: String, kept: Double?, enabled: Boolean, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = text,
        onValueChange = onChange,
        singleLine = true,
        enabled = enabled,
        label = { Text(stringResource(FoodFormat.nutrientRes(n))) },
        suffix = { Text(stringResource(FoodFormat.unitRes(n))) },
        supportingText = kept?.let { { Text(stringResource(R.string.foodlog_values_kept, FoodFormat.value(n, it))) } },
        isError = text.isNotBlank() && ValueFields.parse(text) == null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
        textStyle = LocalTextStyle.current.merge(TabularNumbers),
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    )
}

/**
 * The dish's ingredients that can be changed: closed, a line says what changed; open, each one with
 * its usual amount for the dish logged and the choices none / ½ / normal / 1½ / double / grams typed.
 */
@Composable
private fun IngredientsSection(
    recipe: List<RecipeIngredient>,
    ingredients: Map<String, IngredientInfo>,
    dishAmount: Double?,
    choices: Map<String, IngredientChoice>,
    adjustments: List<IngredientAdjustment>,
    open: Boolean,
    onOpen: (Boolean) -> Unit,
    onChoice: (String, IngredientChoice) -> Unit,
    totalAmount: Double?,
    unit: FoodUnit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Heading(stringResource(R.string.foodlog_ingredients), Modifier.weight(1f))
        TextButton(onClick = { onOpen(!open) }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(if (open) R.string.foodlog_less else R.string.foodlog_ingredients_adjust))
            Icon(if (open) AppIcons.ExpandLess else AppIcons.ExpandMore, contentDescription = null)
        }
    }
    if (!open) {
        val changed = adjustments.mapNotNull { a ->
            val info = ingredients[a.ingredient] ?: return@mapNotNull null
            stringResource(R.string.foodlog_label_amount, info.name, FoodFormat.quantity(FoodMath.round1(a.grams), info.unit))
        }
        FoodSupporting(if (changed.isEmpty()) stringResource(R.string.foodlog_ingredients_usual) else stringResource(R.string.foodlog_ingredients_changed, Fmt.list(changed)))
        totalAmount?.let { FoodSupporting(stringResource(R.string.foodlog_total_weight, FoodFormat.quantity(FoodMath.round1(it), unit))) }
        return
    }
    FoodSupporting(stringResource(R.string.foodlog_ingredients_text))
    AppCard {
        Column(Modifier.padding(vertical = 8.dp)) {
            recipe.forEachIndexed { i, r ->
                val info = ingredients[r.ingredient] ?: return@forEachIndexed
                if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHighest)
                val base = RecipeMath.baseGrams(r, dishAmount ?: 0.0)
                val grams = adjustments.firstOrNull { it.ingredient == r.ingredient }?.grams ?: base
                IngredientRow(info, base, grams, choices[r.ingredient] ?: IngredientChoice.NORMAL) { onChoice(r.ingredient, it) }
            }
        }
    }
    totalAmount?.let {
        Text(
            stringResource(R.string.foodlog_total_weight, FoodFormat.quantity(FoodMath.round1(it), unit)),
            style = MaterialTheme.typography.bodyMedium.merge(TabularNumbers),
        )
    }
}

/** One ingredient: its name, the grams it has now and usually, and how much of it there was. */
@Composable
private fun IngredientRow(info: IngredientInfo, base: Double, grams: Double, choice: IngredientChoice, onChoice: (IngredientChoice) -> Unit) {
    // The grams typed, kept while the field shows (a text that is not a number yet changes nothing).
    var typed by rememberSaveable(info.id) { mutableStateOf((choice as? IngredientChoice.Grams)?.grams?.let { FoodFormat.count(FoodMath.round1(it)) }.orEmpty()) }
    Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(info.name, style = MaterialTheme.typography.bodyLarge)
                // The usual amount, once it differs from what the plate has.
                if (kotlin.math.abs(grams - base) >= 0.05) {
                    FoodSupporting(stringResource(R.string.foodlog_ingredient_usual, FoodFormat.quantity(FoodMath.round1(base), info.unit)))
                }
            }
            Text(FoodFormat.quantity(FoodMath.round1(grams), info.unit), style = MaterialTheme.typography.titleSmall.merge(TabularNumbers))
            // Any other amount, typed.
            IconButton(onClick = {
                if (typed.isEmpty()) typed = FoodFormat.count(FoodMath.round1(grams))
                onChoice(IngredientChoice.Grams(Fmt.parseDecimal(typed) ?: grams))
            }) {
                Icon(
                    Icons.Filled.Edit,
                    contentDescription = stringResource(R.string.foodlog_ingredient_type, info.name),
                    tint = if (choice is IngredientChoice.Grams) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(end = 12.dp)) {
            for (level in AdjustLevel.entries) {
                val label = stringResource(levelRes(level))
                val description = levelDescriptionRes(level)?.let { stringResource(it) }
                FilterChip(
                    selected = choice == IngredientChoice.Level(level),
                    onClick = { onChoice(IngredientChoice.Level(level)) },
                    label = {
                        if (description != null) {
                            // "1½", "×2": numbers and signs read left to right also in Arabic (not "½1", "2×").
                            Text(label, style = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr), modifier = Modifier.labelled(description))
                        } else {
                            Text(label)
                        }
                    },
                )
            }
        }
        if (choice is IngredientChoice.Grams) {
            val parsed = Fmt.parseDecimal(typed)?.takeIf { it >= 0 && it < MAX_AMOUNT }
            OutlinedTextField(
                value = typed,
                onValueChange = { v ->
                    typed = v.take(7)
                    Fmt.parseDecimal(typed)?.takeIf { it >= 0 && it < MAX_AMOUNT }?.let { onChoice(IngredientChoice.Grams(it)) }
                },
                singleLine = true,
                label = { Text(info.name) },
                suffix = { Text(stringResource(if (info.unit == FoodUnit.ML) R.string.unit_ml else R.string.unit_g)) },
                isError = parsed == null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, end = 12.dp),
            )
        }
    }
}

private fun levelRes(level: AdjustLevel): Int = when (level) {
    AdjustLevel.NONE -> R.string.foodlog_level_none
    AdjustLevel.HALF -> R.string.foodlog_level_half
    AdjustLevel.NORMAL -> R.string.foodlog_level_normal
    AdjustLevel.ONE_AND_A_HALF -> R.string.foodlog_level_one_half
    AdjustLevel.DOUBLE -> R.string.foodlog_level_double
}

/** What TalkBack says for a level written with a symbol ("½", "×2"). */
private fun levelDescriptionRes(level: AdjustLevel): Int? = when (level) {
    AdjustLevel.HALF -> R.string.foodlog_level_half_desc
    AdjustLevel.ONE_AND_A_HALF -> R.string.foodlog_level_one_half_desc
    AdjustLevel.DOUBLE -> R.string.foodlog_level_double_desc
    else -> null
}

/** The ingredient choices of the confirm page as one saveable text: `id=level` or `id=g:grams`, `;` between. */
internal object IngredientChoices {
    fun encode(choices: Map<String, IngredientChoice>): String = choices.entries.joinToString(";") { (id, c) ->
        when (c) {
            is IngredientChoice.Level -> "$id=${c.level.code}"
            is IngredientChoice.Grams -> "$id=g:${c.grams}"
        }
    }

    fun decode(text: String): Map<String, IngredientChoice> {
        if (text.isEmpty()) return emptyMap()
        return text.split(';').mapNotNull { part ->
            val id = part.substringBefore('=', "").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val v = part.substringAfter('=', "")
            val choice = if (v.startsWith("g:")) {
                v.removePrefix("g:").toDoubleOrNull()?.let { IngredientChoice.Grams(it) }
            } else {
                AdjustLevel.entries.firstOrNull { it.code == v }?.let { IngredientChoice.Level(it) }
            }
            choice?.let { id to it }
        }.toMap()
    }
}

/**
 * The values card's fields as saveable text: the values typed (per 100 g, `code=value`, empty for
 * unknown) and the texts of the fields typed (`code=text`), `;` between.
 */
internal object ValueFields {
    /** Above this a value typed is not food. */
    private const val MAX_VALUE = 100_000.0

    fun encodeOverrides(overrides: Map<Nutrient, Double?>): String = overrides.entries.joinToString(";") { (n, v) -> "${n.code}=${v?.toString().orEmpty()}" }

    fun decodeOverrides(text: String): Map<Nutrient, Double?> = parts(text) { v ->
        if (v.isEmpty()) Present(null) else v.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }?.let { Present(it) }
    }

    fun encodeTexts(texts: Map<Nutrient, String>): String = texts.entries.joinToString(";") { (n, t) -> "${n.code}=${clean(t)}" }

    fun decodeTexts(text: String): Map<Nutrient, String> = parts(text) { Present(clean(it)) }

    /** What a number field keeps of a text: digits (of any script) and decimal separators. */
    fun clean(text: String): String = text.filter { it.isDigit() || it == ',' || it == '.' || it == '٫' }.take(8)

    /** A value typed, or null when it is not one (empty fields are unknown, see the callers). */
    fun parse(text: String): Double? = Fmt.parseDecimal(text)?.takeIf { it >= 0 && it < MAX_VALUE }

    private class Present<T>(val value: T)

    private fun <T> parts(text: String, value: (String) -> Present<T>?): Map<Nutrient, T> {
        if (text.isEmpty()) return emptyMap()
        val out = LinkedHashMap<Nutrient, T>()
        for (part in text.split(';')) {
            val n = Nutrient.entries.firstOrNull { it.code == part.substringBefore('=', "") } ?: continue
            value(part.substringAfter('=', "")).let { if (it != null) out[n] = it.value }
        }
        return out
    }
}

/** The values beyond the label in their groups, with their headings (as [ExtraNutrientRows] shows them). */
private val NUTRIENT_GROUPS = listOf(
    R.string.foodlog_nutrients_fats to Nutrient.FATS,
    R.string.foodlog_nutrients_minerals to Nutrient.MINERALS,
    R.string.foodlog_nutrients_vitamins to Nutrient.VITAMINS,
)

@Composable
private fun NutrientLine(n: Nutrient, v: Double?) {
    InfoRow(stringResource(FoodFormat.nutrientRes(n)), v?.let { FoodFormat.value(n, it) } ?: "—")
}

@Composable
internal fun Heading(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = modifier.padding(top = 4.dp).semantics { heading() })
}

private const val MAX_AMOUNT = 10_000.0
