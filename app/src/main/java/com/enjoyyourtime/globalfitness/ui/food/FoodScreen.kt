@file:OptIn(ExperimentalMaterial3Api::class)

package com.enjoyyourtime.globalfitness.ui.food

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.enjoyyourtime.globalfitness.R
import com.enjoyyourtime.globalfitness.food.domain.NutritionTargets
import com.enjoyyourtime.globalfitness.food.model.FoodEntry
import com.enjoyyourtime.globalfitness.food.model.Meal
import com.enjoyyourtime.globalfitness.food.model.Nutrient
import com.enjoyyourtime.globalfitness.food.model.Nutrients
import com.enjoyyourtime.globalfitness.ui.Fmt
import com.enjoyyourtime.globalfitness.ui.components.AppCard
import com.enjoyyourtime.globalfitness.ui.components.AppIcons
import com.enjoyyourtime.globalfitness.ui.components.DatePickerModal
import com.enjoyyourtime.globalfitness.ui.components.LocalAppSnackbar
import com.enjoyyourtime.globalfitness.ui.components.NoticeCard
import com.enjoyyourtime.globalfitness.ui.components.ProgressRing
import com.enjoyyourtime.globalfitness.ui.components.TabPage
import com.enjoyyourtime.globalfitness.ui.theme.TabularNumbers
import com.enjoyyourtime.globalfitness.ui.training.TrainingIcons
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * The Comida tab: one day of the food log (day navigation, the summary against the daily goal,
 * the meals with their foods) and the add button (photo, gallery, barcode, search, manual).
 */
@Composable
fun FoodScreen(onOpen: (String) -> Unit, adding: Boolean, onAdding: (Boolean) -> Unit) {
    val vm = foodViewModel()
    val state by vm.dayUi.collectAsStateWithLifecycle()
    val deleted by vm.deleted.collectAsStateWithLifecycle()
    val snackbar = LocalAppSnackbar.current
    val resources = LocalResources.current
    var pickDate by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val unavailable = stringResource(R.string.foodlog_camera_unavailable)
    val showUnavailable: () -> Unit = { scope.launch { snackbar.showSnackbar(unavailable) } }

    val mealPhoto = rememberImageLauncher(
        vm.cameraTarget,
        onImage = { uri, fromCamera ->
            vm.onMealPhoto(uri, fromCamera)
            onOpen(FoodRoutes.PHOTO)
        },
        onUnavailable = showUnavailable,
    )
    val barcodePhoto = rememberImageLauncher(
        vm.cameraTarget,
        onImage = { uri, fromCamera ->
            vm.onBarcodePhoto(uri, fromCamera)
            onOpen(FoodRoutes.BARCODE)
        },
        onUnavailable = showUnavailable,
    )

    LaunchedEffect(Unit) { vm.onTabShown() }
    // The model loads while the camera or the photo picker is open.
    val takeMealPhoto = {
        vm.prepareRecognizer()
        mealPhoto.takePhoto()
    }
    val pickMealPhoto = {
        vm.prepareRecognizer()
        mealPhoto.pickPhoto()
    }

    // "Undo" for an entry deleted on the edit page. The view model ends it on time (also when this tab
    // is left meanwhile): the snackbar just shows it while it lasts.
    LaunchedEffect(deleted) {
        val e = deleted ?: return@LaunchedEffect
        snackbar.currentSnackbarData?.dismiss()
        val result = snackbar.showSnackbar(
            resources.getString(R.string.foodlog_deleted, vm.entryName(e)),
            actionLabel = resources.getString(R.string.foodlog_undo),
            withDismissAction = true,
            duration = SnackbarDuration.Indefinite,
        )
        if (result == SnackbarResult.ActionPerformed) vm.undoDelete(e) else vm.confirmDelete(e)
    }

    TabPage(
        title = stringResource(R.string.nav_food),
        bottomPadding = 96.dp,
        actions = {
            IconButton(onClick = { onOpen(FoodRoutes.GOAL) }) {
                Icon(AppIcons.Flag, contentDescription = stringResource(R.string.foodlog_goal_title))
            }
        },
    ) {
        item(key = "day") {
            DayNavigator(
                date = state.date,
                onPrevious = vm::previousDay,
                onNext = vm::nextDay,
                onToday = { vm.showDay(LocalDate.now()) },
                onPick = { pickDate = true },
            )
        }
        item(key = "summary") {
            SummaryCard(state.log.total, state.log.partial, state.goal.targets, needsGoal = state.goal.targets == null, onOpenGoal = { onOpen(FoodRoutes.GOAL) })
        }
        val error = state.error
        if (error != null) {
            item(key = "error") {
                NoticeCard(
                    title = stringResource(R.string.foodlog_db_error),
                    text = stringResource(R.string.error_database_open, error),
                    icon = AppIcons.Restaurant,
                    iconTint = MaterialTheme.colorScheme.error,
                )
            }
        } else if (state.loaded && state.log.isEmpty) {
            item(key = "empty") {
                NoticeCard(
                    title = stringResource(if (state.date == LocalDate.now()) R.string.foodlog_empty_today else R.string.foodlog_empty_day),
                    text = stringResource(R.string.foodlog_empty_text),
                    icon = AppIcons.Restaurant,
                    actions = {
                        if (mealPhoto.canTakePhoto) {
                            Button(onClick = takeMealPhoto, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.foodlog_add_photo_short)) }
                        }
                        OutlinedButton(
                            onClick = {
                                vm.startSearch(forPhoto = false)
                                onOpen(FoodRoutes.SEARCH)
                            },
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) { Text(stringResource(R.string.foodlog_add_search_short)) }
                    },
                )
            }
        }
        for ((meal, entries) in state.log.meals) {
            item(key = "meal-${meal.code}") {
                MealSection(meal, entries, state.log.mealTotals[meal] ?: Nutrients.NONE, onOpenEntry = { onOpen(FoodRoutes.edit(it.id)) })
            }
        }
        item(key = "note") {
            Text(
                stringResource(R.string.foodlog_estimates_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }

    if (adding) {
        AddSheet(
            canTakePhoto = mealPhoto.canTakePhoto,
            onDismiss = { onAdding(false) },
            onPhoto = {
                onAdding(false)
                takeMealPhoto()
            },
            onGallery = {
                onAdding(false)
                pickMealPhoto()
            },
            onBarcode = {
                onAdding(false)
                if (barcodePhoto.canTakePhoto) barcodePhoto.takePhoto() else barcodePhoto.pickPhoto()
            },
            onBarcodeGallery = {
                onAdding(false)
                barcodePhoto.pickPhoto()
            },
            onSearch = {
                onAdding(false)
                vm.startSearch(forPhoto = false)
                onOpen(FoodRoutes.SEARCH)
            },
            onManual = {
                onAdding(false)
                vm.startManual()
                onOpen(FoodRoutes.MANUAL)
            },
        )
    }
    if (pickDate) {
        DatePickerModal(initial = state.date, onPick = vm::showDay, onDismiss = { pickDate = false })
    }
}

@Composable
private fun DayNavigator(date: LocalDate, onPrevious: () -> Unit, onNext: () -> Unit, onToday: () -> Unit, onPick: () -> Unit) {
    val today = LocalDate.now()
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onPrevious) { Icon(AppIcons.ChevronLeft, contentDescription = stringResource(R.string.foodlog_previous_day)) }
        TextButton(onClick = onPick, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
            Text(
                Fmt.relativeDay(date, today).replaceFirstChar { it.titlecase(Fmt.locale) },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (date != today) {
            TextButton(onClick = onToday, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.nav_today)) }
        }
        IconButton(onClick = onNext, enabled = date < today) {
            Icon(AppIcons.ChevronRight, contentDescription = stringResource(R.string.foodlog_next_day))
        }
    }
}

@Composable
private fun SummaryCard(total: Nutrients, partial: Set<Nutrient>, targets: NutritionTargets?, needsGoal: Boolean, onOpenGoal: () -> Unit) {
    var more by rememberSaveable { mutableStateOf(false) }
    val eaten = total.kcal ?: 0.0
    val scheme = MaterialTheme.colorScheme
    AppCard(containerColor = scheme.primaryContainer) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(R.string.foodlog_eaten), style = MaterialTheme.typography.labelLarge, color = scheme.onPrimaryContainer)
                    Text(
                        stringResource(R.string.value_kcal, FoodFormat.kcal(eaten)),
                        style = MaterialTheme.typography.headlineMedium.merge(TabularNumbers),
                        color = scheme.onPrimaryContainer,
                    )
                    if (targets != null) {
                        Text(
                            stringResource(R.string.foodlog_goal_of, FoodFormat.kcal(targets.kcal)),
                            style = MaterialTheme.typography.bodyMedium.merge(TabularNumbers),
                            color = scheme.onPrimaryContainer,
                        )
                    }
                }
                if (targets != null) {
                    val left = targets.kcal - eaten
                    Spacer(Modifier.width(12.dp))
                    ProgressRing(
                        progress = (eaten / targets.kcal).toFloat(),
                        value = FoodFormat.kcal(kotlin.math.abs(left)),
                        unit = stringResource(if (left >= 0) R.string.foodlog_left else R.string.foodlog_over),
                        color = if (left >= 0) scheme.primary else scheme.error,
                        trackColor = scheme.onPrimaryContainer.copy(alpha = 0.14f),
                        description = if (left >= 0) {
                            stringResource(R.string.foodlog_left_desc, FoodFormat.kcal(eaten), FoodFormat.kcal(targets.kcal), FoodFormat.kcal(left))
                        } else {
                            stringResource(R.string.foodlog_over_desc, FoodFormat.kcal(eaten), FoodFormat.kcal(targets.kcal), FoodFormat.kcal(-left))
                        },
                    )
                }
            }
            if (needsGoal) {
                Text(stringResource(R.string.foodlog_no_goal), style = MaterialTheme.typography.bodyMedium, color = scheme.onPrimaryContainer)
                OutlinedButton(onClick = onOpenGoal, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.foodlog_set_goal)) }
            }
            NutrientProgress(stringResource(R.string.foodlog_nutrient_protein), total.protein ?: 0.0, targets?.protein, limit = false, partial = Nutrient.PROTEIN in partial)
            NutrientProgress(stringResource(R.string.foodlog_nutrient_carbs), total.carbs ?: 0.0, targets?.carbs, limit = false, partial = Nutrient.CARBS in partial)
            NutrientProgress(stringResource(R.string.foodlog_nutrient_fat), total.fat ?: 0.0, targets?.fat, limit = false, partial = Nutrient.FAT in partial)
            AnimatedVisibility(more) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    NutrientProgress(stringResource(R.string.foodlog_nutrient_sugars), total.sugars ?: 0.0, targets?.sugarsMax, limit = true, partial = Nutrient.SUGARS in partial)
                    NutrientProgress(stringResource(R.string.foodlog_nutrient_sat_fat), total.satFat ?: 0.0, targets?.satFatMax, limit = true, partial = Nutrient.SAT_FAT in partial)
                    NutrientProgress(stringResource(R.string.foodlog_nutrient_fiber), total.fiber ?: 0.0, targets?.fiberMin, limit = false, partial = Nutrient.FIBER in partial)
                    NutrientProgress(stringResource(R.string.foodlog_nutrient_salt), total.salt ?: 0.0, targets?.saltMax, limit = true, partial = Nutrient.SALT in partial)
                    // The values beyond the label, only those some food of the day has.
                    if (total.hasExtra) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            ExtraNutrientRows(total, knownOnly = true, partial = partial)
                        }
                    }
                    if (partial.isNotEmpty()) {
                        Text(stringResource(R.string.foodlog_partial_note), style = MaterialTheme.typography.bodySmall, color = scheme.onPrimaryContainer)
                    }
                }
            }
            TextButton(onClick = { more = !more }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(if (more) R.string.foodlog_less else R.string.foodlog_more))
                Spacer(Modifier.width(4.dp))
                Icon(if (more) AppIcons.ExpandLess else AppIcons.ExpandMore, contentDescription = null)
            }
        }
    }
}

@Composable
private fun MealSection(meal: Meal, entries: List<FoodEntry>, total: Nutrients, onOpenEntry: (FoodEntry) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp).semantics(mergeDescendants = true) { heading() }, verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(FoodFormat.mealRes(meal)), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(
                stringResource(R.string.value_kcal, FoodFormat.kcal(total.kcal ?: 0.0)),
                style = MaterialTheme.typography.titleSmall.merge(TabularNumbers),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AppCard {
            entries.forEachIndexed { i, e ->
                if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHighest)
                EntryRow(e, onClick = { onOpenEntry(e) })
            }
        }
    }
}

@Composable
private fun EntryRow(e: FoodEntry, onClick: () -> Unit) {
    val vm = foodViewModel()
    ListItem(
        headlineContent = { Text(vm.entryName(e), maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            // A dish whose ingredients or values were changed says so ("Plate · 368 g · Adjusted").
            val amount = FoodFormat.entryAmount(e)
            Text(
                if (e.adjustments.isEmpty() && !e.valuesEdited) amount else stringResource(R.string.foodlog_entry_adjusted, amount),
                style = MaterialTheme.typography.bodyMedium.merge(TabularNumbers),
            )
        },
        // The meal photo, else the product's photo (the row reads the name already).
        leadingContent = { MealThumbnail(e.photo, 48.dp, customFoodId = e.customFoodId) },
        trailingContent = {
            Text(
                e.nutrients.kcal?.let { stringResource(R.string.value_kcal, FoodFormat.kcal(it)) } ?: "—",
                style = MaterialTheme.typography.titleSmall.merge(TabularNumbers),
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClickLabel = stringResource(R.string.foodlog_edit_entry), role = Role.Button, onClick = onClick),
    )
}

@Composable
private fun AddSheet(
    canTakePhoto: Boolean,
    onDismiss: () -> Unit,
    onPhoto: () -> Unit,
    onGallery: () -> Unit,
    onBarcode: () -> Unit,
    onBarcodeGallery: () -> Unit,
    onSearch: () -> Unit,
    onManual: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(
                stringResource(R.string.foodlog_add_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp).semantics { heading() },
            )
            if (canTakePhoto) SheetRow(TrainingIcons.PhotoCamera, stringResource(R.string.foodlog_add_photo), stringResource(R.string.foodlog_add_photo_text), onPhoto)
            SheetRow(TrainingIcons.Image, stringResource(R.string.foodlog_add_gallery), stringResource(R.string.foodlog_add_gallery_text), onGallery)
            SheetRow(
                AppIcons.Barcode,
                stringResource(R.string.foodlog_add_barcode),
                stringResource(R.string.foodlog_add_barcode_text),
                onBarcode,
                trailing = if (canTakePhoto) {
                    {
                        IconButton(onClick = onBarcodeGallery) {
                            Icon(TrainingIcons.Image, contentDescription = stringResource(R.string.foodlog_add_barcode_gallery))
                        }
                    }
                } else {
                    null
                },
            )
            SheetRow(Icons.Filled.Search, stringResource(R.string.foodlog_add_search), stringResource(R.string.foodlog_add_search_text), onSearch)
            SheetRow(Icons.Filled.Edit, stringResource(R.string.foodlog_add_manual), stringResource(R.string.foodlog_add_manual_text), onManual)
        }
    }
}

@Composable
private fun SheetRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, text: String, onClick: () -> Unit, trailing: (@Composable () -> Unit)? = null) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(text) },
        leadingContent = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp)) },
        trailingContent = trailing,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(role = Role.Button, onClick = onClick),
    )
}
