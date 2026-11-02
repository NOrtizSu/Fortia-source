@file:OptIn(ExperimentalMaterial3Api::class)

package com.enjoyyourtime.globalfitness.ui.food

import android.content.ActivityNotFoundException
import android.net.Uri
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.text.modifiers.TextAutoSizeLayoutScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.LayoutModifier
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.enjoyyourtime.globalfitness.R
import com.enjoyyourtime.globalfitness.food.model.FoodEntry
import com.enjoyyourtime.globalfitness.food.model.FoodUnit
import com.enjoyyourtime.globalfitness.food.model.Meal
import com.enjoyyourtime.globalfitness.food.model.Nutrient
import com.enjoyyourtime.globalfitness.food.model.PortionKey
import com.enjoyyourtime.globalfitness.food.recognition.ConfidenceHint
import com.enjoyyourtime.globalfitness.health.HealthPermissions
import com.enjoyyourtime.globalfitness.ui.Fmt
import com.enjoyyourtime.globalfitness.ui.components.AppIcons
import com.enjoyyourtime.globalfitness.ui.components.LocalAppSnackbar
import com.enjoyyourtime.globalfitness.ui.components.TopBarTitle
import com.enjoyyourtime.globalfitness.ui.navigation.ReserveAppSnackbarSpace
import com.enjoyyourtime.globalfitness.ui.theme.TabularNumbers
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToLong

/** The food view model shared by the Comida tab, its pages and Hoy's card (activity scoped). */
@Composable
fun foodViewModel(): FoodViewModel {
    val owner = LocalActivity.current as? ViewModelStoreOwner ?: checkNotNull(LocalViewModelStoreOwner.current)
    return viewModel(viewModelStoreOwner = owner, factory = FoodViewModel.Factory)
}

/** Scaffold of the food pages: title bar with a back arrow, optional actions and bottom bar. */
@Composable
fun FoodSubPage(
    title: String,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { TopBarTitle(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back)) }
                },
                actions = actions,
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
        bottomBar = { ReserveAppSnackbarSpace(bottomBar) },
        content = content,
    )
}

/** Starts the camera (into [cameraTarget], shared through the FileProvider: no permission) or the Photo Picker. */
class ImageLauncher(val canTakePhoto: Boolean, val takePhoto: () -> Unit, val pickPhoto: () -> Unit)

@Composable
fun rememberImageLauncher(cameraTarget: File, onImage: (uri: Uri, fromCamera: Boolean) -> Unit, onUnavailable: () -> Unit): ImageLauncher {
    val context = LocalContext.current
    val image by rememberUpdatedState(onImage)
    val unavailable by rememberUpdatedState(onUnavailable)
    val cameraUri = remember(cameraTarget) {
        try {
            FileProvider.getUriForFile(context, context.packageName + ".fileprovider", cameraTarget)
        } catch (e: IllegalArgumentException) {
            null
        }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        if (saved && cameraUri != null) image(cameraUri, true)
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) image(uri, false)
    }
    return remember(camera, gallery, cameraUri) {
        ImageLauncher(
            canTakePhoto = cameraUri != null,
            takePhoto = {
                try {
                    if (cameraUri != null) {
                        cameraTarget.parentFile?.mkdirs()
                        camera.launch(cameraUri)
                    }
                } catch (e: ActivityNotFoundException) {
                    unavailable()
                } catch (e: SecurityException) {
                    unavailable()
                }
            },
            pickPhoto = {
                try {
                    gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                } catch (e: ActivityNotFoundException) {
                    unavailable()
                }
            },
        )
    }
}

/**
 * The food log's part of the main scaffold: asks for the nutrition write permission in context, once
 * (after the first meal saved while Health Connect is set up; the save never waits for it), and says
 * so in the app's snackbar when a change could not be saved (on any food page).
 */
@Composable
fun FoodHost() {
    val vm = foodViewModel()
    val ask by vm.askHealthPermission.collectAsStateWithLifecycle()
    val launcher = rememberLauncherForActivityResult(vm.healthPermissionContract) { vm.onHealthPermissionResult() }
    LaunchedEffect(ask) {
        if (!ask) return@LaunchedEffect
        vm.onHealthPermissionAsked()
        runCatching { launcher.launch(setOf(HealthPermissions.WRITE_NUTRITION)) }
    }
    val snackbar = LocalAppSnackbar.current
    val failed = stringResource(R.string.foodlog_write_failed)
    LaunchedEffect(vm, failed) { vm.writeFailed.collect { snackbar.showSnackbar(failed) } }
}

/** A meal photo (small, rounded), or the meal glyph when there is none. */
@Composable
fun MealThumbnail(photo: String?, size: Dp, modifier: Modifier = Modifier) {
    val vm = foodViewModel()
    val px = with(LocalDensity.current) { size.roundToPx() }
    val bitmap by produceState(initialValue = null as androidx.compose.ui.graphics.ImageBitmap?, photo, px) {
        value = photo?.let { vm.thumbnail(it, px * 2) }
    }
    val shape = RoundedCornerShape(12.dp)
    val b = bitmap
    if (b != null) {
        Image(b, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier.size(size).clip(shape))
    } else {
        Box(modifier.size(size).clip(shape).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
            Icon(AppIcons.Restaurant, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(size / 2))
        }
    }
}

/**
 * "Protein 45 / 120 g" with a thin bar: a target ([limit] false) fills up, a limit ([limit] true)
 * turns to the error colour once passed. TalkBack reads the line as one sentence.
 */
@Composable
fun NutrientProgress(label: String, value: Double, target: Double?, limit: Boolean, modifier: Modifier = Modifier) {
    val valueText = stringResource(R.string.value_g, FoodFormat.grams(value))
    val text = if (target != null) stringResource(R.string.foodlog_value_of_target, valueText, stringResource(R.string.value_g, FoodFormat.grams(target))) else valueText
    val over = target != null && value > target
    val description = when {
        limit && target != null && over -> stringResource(R.string.foodlog_limit_over_desc, label, text)
        limit && target != null -> stringResource(R.string.foodlog_limit_desc, label, text)
        else -> stringResource(R.string.foodlog_label_value, label, text)
    }
    Column(modifier.clearAndSetSemantics { contentDescription = description }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(text, style = MaterialTheme.typography.labelLarge.merge(TabularNumbers), color = if (limit && over) MaterialTheme.colorScheme.error else Color.Unspecified)
        }
        if (target != null && target > 0) {
            LinearProgressIndicator(
                progress = { (value / target).toFloat().coerceIn(0f, 1f) },
                color = if (limit && over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier.fillMaxWidth().height(6.dp),
                drawStopIndicator = {},
                gapSize = 0.dp,
            )
        }
    }
}

/** Numbers and labels of the food log. */
object FoodFormat {
    /** Whole kcal, grouped ("1.250"). */
    fun kcal(v: Double): String = Fmt.count(v.roundToLong())

    /** The number of grams of a nutrient (shown through value_g): one decimal under 10 g, none above or for nothing ("4,5", "32", "0"). */
    fun grams(v: Double): String = Fmt.num(v, if (abs(v) < 10 && abs(v) >= 0.05) 1 else 0)

    /** An amount eaten: "150 g", "250 ml" (one decimal only when it has one). */
    fun quantity(v: Double, unit: FoodUnit): String {
        val n = if (v % 1.0 == 0.0 || v >= 100) Fmt.num(v, 0) else Fmt.num(v, 1)
        return Fmt.string(if (unit == FoodUnit.ML) R.string.value_ml else R.string.value_g, n)
    }

    /** "1,5" or "2". */
    fun count(v: Double): String = if (v % 1.0 == 0.0) Fmt.num(v, 0) else Fmt.num(v, 1)

    /**
     * What a list row shows under the name: "2 × Slice · 120 g", "Slice · 60 g" for one portion (as the
     * portion chips say it; Chinese, Japanese and Korean portion names already hold the "1") or "150 g".
     */
    fun entryAmount(e: FoodEntry): String {
        val q = quantity(e.amount, e.unit)
        val key = e.portionKey
        val count = e.portionCount
        return when {
            key == null || count == null || count <= 0 -> q
            count == 1.0 -> Fmt.string(R.string.foodlog_portion_chip, Fmt.string(portionRes(key)), q)
            else -> Fmt.string(R.string.foodlog_portion_amount, count(count), Fmt.string(portionRes(key)), q)
        }
    }

    /** "%1$s kcal per 100 g" or per 100 ml. */
    @StringRes
    fun kcalPer100Res(unit: FoodUnit): Int = if (unit == FoodUnit.ML) R.string.foodlog_kcal_per_100ml else R.string.foodlog_kcal_per_100g

    @StringRes
    fun mealRes(meal: Meal): Int = when (meal) {
        Meal.BREAKFAST -> R.string.foodlog_meal_breakfast
        Meal.LUNCH -> R.string.foodlog_meal_lunch
        Meal.SNACK -> R.string.foodlog_meal_snack
        Meal.DINNER -> R.string.foodlog_meal_dinner
        Meal.OTHER -> R.string.foodlog_meal_other
    }

    @StringRes
    fun nutrientRes(n: Nutrient): Int = when (n) {
        Nutrient.KCAL -> R.string.foodlog_nutrient_kcal
        Nutrient.PROTEIN -> R.string.foodlog_nutrient_protein
        Nutrient.CARBS -> R.string.foodlog_nutrient_carbs
        Nutrient.SUGARS -> R.string.foodlog_nutrient_sugars
        Nutrient.FAT -> R.string.foodlog_nutrient_fat
        Nutrient.SAT_FAT -> R.string.foodlog_nutrient_sat_fat
        Nutrient.FIBER -> R.string.foodlog_nutrient_fiber
        Nutrient.SALT -> R.string.foodlog_nutrient_salt
    }

    @StringRes
    fun portionRes(key: PortionKey): Int = when (key) {
        PortionKey.UNIT -> R.string.food_portion_unit
        PortionKey.PIECE -> R.string.food_portion_piece
        PortionKey.SLICE -> R.string.food_portion_slice
        PortionKey.PLATE -> R.string.food_portion_plate
        PortionKey.SMALL_PLATE -> R.string.food_portion_small_plate
        PortionKey.BOWL -> R.string.food_portion_bowl
        PortionKey.CUP -> R.string.food_portion_cup
        PortionKey.GLASS -> R.string.food_portion_glass
        PortionKey.CAN -> R.string.food_portion_can
        PortionKey.BOTTLE -> R.string.food_portion_bottle
        PortionKey.TBSP -> R.string.food_portion_tbsp
        PortionKey.TSP -> R.string.food_portion_tsp
        PortionKey.HANDFUL -> R.string.food_portion_handful
        PortionKey.SERVING -> R.string.food_portion_serving
    }

    @StringRes
    fun hintRes(hint: ConfidenceHint): Int = when (hint) {
        ConfidenceHint.VERY_LIKELY -> R.string.foodlog_hint_very_likely
        ConfidenceHint.LIKELY -> R.string.foodlog_hint_likely
        ConfidenceHint.POSSIBLE -> R.string.foodlog_hint_possible
    }
}

/** Small grey line of text. */
@Composable
fun FoodSupporting(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}

/** Semantics label for a button that only shows an icon. */
fun Modifier.labelled(label: String): Modifier = semantics { contentDescription = label }

/** Horizontal padding of a segmented button's content: a little less than Material's, for long labels. */
val SegmentContentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)

/**
 * The label of a segmented button that fits in every language: up to two lines broken between words,
 * the text shrinking (down to 11 sp) only when a word would otherwise be cut or a third line needed
 * ("Prendre du poids", "Pertahankan", large fonts).
 */
@Composable
fun SegmentLabel(text: String) {
    val style = LocalTextStyle.current
    val max = style.fontSize.takeIf { it.isSpecified } ?: 14.sp
    BasicText(
        text = text,
        style = style.copy(color = style.color.takeOrElse { LocalContentColor.current }, textAlign = TextAlign.Center),
        maxLines = 2,
        autoSize = remember(max) { WholeWordsAutoSize(max) },
        modifier = Modifier.then(LeaveCheckRoom),
    )
}

/**
 * Material's segmented button measures its label with the button's whole width and then puts the
 * checkmark's room beside it (shown or not): the label really has that much less, also when the row
 * asks how tall its buttons need to be (it makes them all as tall as the tallest label).
 */
private object LeaveCheckRoom : LayoutModifier {
    /** The checkmark and the space after it (SegmentedButtonDefaults: 18 dp + 8 dp). */
    private val room = SegmentedButtonDefaults.IconSize + 8.dp

    private fun Density.narrower(width: Int): Int = if (width == Constraints.Infinity) width else (width - room.roundToPx()).coerceAtLeast(0)

    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val placeable = measurable.measure(constraints.copy(minWidth = 0, maxWidth = narrower(constraints.maxWidth)))
        return layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }

    override fun IntrinsicMeasureScope.minIntrinsicHeight(measurable: IntrinsicMeasurable, width: Int): Int =
        measurable.minIntrinsicHeight(narrower(width))

    override fun IntrinsicMeasureScope.maxIntrinsicHeight(measurable: IntrinsicMeasurable, width: Int): Int =
        measurable.maxIntrinsicHeight(narrower(width))
}

/** The largest font size from [max] down to 11 sp whose layout fits its lines and cuts no word in two. */
private data class WholeWordsAutoSize(val max: TextUnit) : TextAutoSize {
    override fun TextAutoSizeLayoutScope.getFontSize(constraints: Constraints, text: AnnotatedString): TextUnit {
        var size = max.value
        while (size > MIN_SP) {
            val layout = performLayout(constraints, text, size.sp)
            if (!layout.didOverflowHeight && !layout.didOverflowWidth && !cutsAWord(layout, text.text)) return size.sp
            size -= STEP_SP
        }
        return MIN_SP.sp
    }

    private companion object {
        const val MIN_SP = 11f
        const val STEP_SP = 0.5f
    }
}

/** A line of [layout] ends inside a word of a script that separates words with spaces (not Chinese or Japanese). */
private fun cutsAWord(layout: TextLayoutResult, text: String): Boolean {
    for (line in 0 until layout.lineCount - 1) {
        val end = layout.getLineEnd(line)
        if (end <= 0 || end >= text.length) continue
        val before = text[end - 1]
        val after = text[end]
        if (before.isLetterOrDigit() && after.isLetterOrDigit() && !breaksAnywhere(before) && !breaksAnywhere(after)) return true
    }
    return false
}

private fun breaksAnywhere(c: Char): Boolean = when (Character.UnicodeScript.of(c.code)) {
    Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> true
    else -> false
}
