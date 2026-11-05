package com.enjoyyourtime.globalfitness.ui.food

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.enjoyyourtime.globalfitness.R
import com.enjoyyourtime.globalfitness.food.domain.FoodMath
import com.enjoyyourtime.globalfitness.food.model.FoodUnit
import com.enjoyyourtime.globalfitness.food.model.Meal
import com.enjoyyourtime.globalfitness.food.pack.PackageText
import com.enjoyyourtime.globalfitness.food.recognition.RecognitionMath
import com.enjoyyourtime.globalfitness.ui.Fmt
import com.enjoyyourtime.globalfitness.ui.components.AppCard
import com.enjoyyourtime.globalfitness.ui.components.AppIcons
import com.enjoyyourtime.globalfitness.ui.components.LocalAppSnackbar
import com.enjoyyourtime.globalfitness.ui.theme.TabularNumbers
import kotlin.math.abs
import kotlinx.coroutines.launch

/**
 * A meal photo and what the recognizer saw in it: the five most likely foods with a plain hint of how
 * sure it is, "Other…" (search), or "no food" when it sees none. After a food is added, the same photo
 * offers the next one (a plate with steak, chips and salad is three entries of one meal). When the
 * item detector finds several foods on the plate, they are listed one by one instead (each with its
 * own suggestions, variants and grams suggestion) and "Add all" logs them in one go; the whole
 * photo's suggestions stay one tap away ("See it as one dish").
 */
@Composable
fun FoodPhotoScreen(onOpen: (String) -> Unit, onDone: () -> Unit) {
    val vm = foodViewModel()
    val session by vm.photo.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.ensureCatalog() }
    val close = {
        vm.closePhotoSession()
        onDone()
    }
    BackHandler(onBack = close)
    // A barcode read on the photo opens its product by itself, only while this page is in front (resumed:
    // not while it is leaving or covered).
    LifecycleResumeEffect(Unit) {
        vm.setPhotoPageShown(true)
        onPauseOrDispose { vm.setPhotoPageShown(false) }
    }
    val autoOpen by vm.photoOpen.collectAsStateWithLifecycle(minActiveState = Lifecycle.State.RESUMED)
    LaunchedEffect(autoOpen) {
        autoOpen?.let {
            vm.onPhotoOpened()
            onOpen(it)
        }
    }
    // "Photo of the barcode" for the package on this photo (its entry keeps this photo).
    val snackbar = LocalAppSnackbar.current
    val scope = rememberCoroutineScope()
    val unavailable = stringResource(R.string.foodlog_camera_unavailable)
    val barcodeLauncher = rememberImageLauncher(
        vm.cameraTarget,
        onImage = { uri, fromCamera ->
            vm.onBarcodePhoto(uri, fromCamera, forPhoto = true)
            onOpen(FoodRoutes.BARCODE)
        },
        onUnavailable = { scope.launch { snackbar.showSnackbar(unavailable) } },
    )
    val barcodePhoto = { if (barcodeLauncher.canTakePhoto) barcodeLauncher.takePhoto() else barcodeLauncher.pickPhoto() }
    val s = session
    val items = s?.showsItems == true
    FoodSubPage(
        title = stringResource(R.string.foodlog_photo_title),
        onBack = close,
        bottomBar = {
            if (s != null && items) {
                val count = s.items.count { it.included && it.food != null && it.amount != null && !it.added }
                // Everything wanted is in: the page's main action closes it.
                val done = count == 0 && s.added.isNotEmpty()
                Surface(tonalElevation = 3.dp) {
                    Column(Modifier.navigationBarsPadding().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!done) {
                            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                for (m in Meal.entries) {
                                    FilterChip(selected = m == s.meal, onClick = { vm.setPhotoMeal(m) }, label = { Text(stringResource(FoodFormat.mealRes(m))) })
                                }
                            }
                        }
                        Button(
                            onClick = { if (done) close() else vm.addPlateItems() },
                            enabled = done || count > 0 && !s.addingItems,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).heightIn(min = 48.dp),
                        ) { Text(if (done) stringResource(R.string.foodlog_done) else stringResource(R.string.foodlog_plate_add_all, count)) }
                    }
                }
            }
        },
    ) { padding ->
        val listState = rememberLazyListState()
        // Once foods are added, the page shows the line that says which (at its top).
        val addedCount = s?.added?.size ?: 0
        LaunchedEffect(addedCount) { if (addedCount > 0) listState.animateScrollToItem(0) }
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(padding),
        ) {
            if (s == null) {
                item { FoodSupporting(stringResource(R.string.foodlog_photo_gone)) }
                return@LazyColumn
            }
            item(key = "photo") {
                Box(
                    Modifier.fillMaxWidth().aspectRatio(4f / 3f).clip(MaterialTheme.shapes.large),
                    contentAlignment = Alignment.Center,
                ) {
                    val preview = s.preview
                    if (preview != null) {
                        Image(preview, contentDescription = stringResource(R.string.foodlog_photo_desc), contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().aspectRatio(4f / 3f))
                    } else if (s.status == PhotoStatus.RECOGNIZING) {
                        CircularProgressIndicator()
                    }
                }
            }
            if (s.added.isNotEmpty()) {
                item(key = "added") {
                    AppCard(containerColor = MaterialTheme.colorScheme.secondaryContainer) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.foodlog_photo_added, Fmt.list(s.added)), style = MaterialTheme.typography.bodyLarge)
                            // The plate's foods have "Done" in the bar below.
                            if (!items) Button(onClick = close, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.foodlog_done)) }
                        }
                    }
                }
            }
            packItems(s.pack, onOpen = onOpen, onBarcodePhoto = barcodePhoto)
            if (items) {
                item(key = "plate-title") {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            pluralStringResource(R.plurals.foodlog_plate_title, s.items.size, s.items.size),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.semantics { heading() },
                        )
                        FoodSupporting(stringResource(R.string.foodlog_plate_text))
                    }
                }
                items(s.items, key = { "item-${it.id}" }) { item -> PlateItemCard(item, onSearch = {
                    vm.startItemSearch(item.id)
                    onOpen(FoodRoutes.SEARCH)
                }) }
                item(key = "as-one") {
                    TextButton(onClick = { vm.showPhotoAsOne(true) }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.foodlog_plate_as_one))
                    }
                }
                if (vm.canReadPackages && s.pack.text == PackText.Idle) item(key = "pack-search") { PackSearchButton(onClick = vm::searchPackage) }
                item(key = "privacy") { FoodSupporting(stringResource(R.string.foodlog_photo_private)) }
                return@LazyColumn
            }
            if (s.items.size >= 2) {
                item(key = "as-items") {
                    TextButton(onClick = { vm.showPhotoAsOne(false) }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(pluralStringResource(R.plurals.foodlog_plate_as_items, s.items.size, s.items.size))
                    }
                }
            }
            when (s.status) {
                PhotoStatus.RECOGNIZING -> item(key = "working") {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(Modifier.padding(4.dp))
                        FoodSupporting(stringResource(R.string.foodlog_photo_recognizing))
                    }
                }
                PhotoStatus.UNREADABLE -> item(key = "unreadable") { FoodSupporting(stringResource(R.string.foodlog_photo_unreadable)) }
                PhotoStatus.NO_MODEL -> item(key = "no-model") { FoodSupporting(stringResource(R.string.foodlog_photo_no_model)) }
                PhotoStatus.DONE -> {
                    val r = s.recognition
                    if (r == null || r.notFood || r.suggestions.isEmpty()) {
                        item(key = "not-food") {
                            Text(stringResource(R.string.foodlog_photo_not_food), style = MaterialTheme.typography.titleMedium)
                        }
                    } else {
                        item(key = "question") {
                            Text(
                                stringResource(if (s.added.isEmpty()) R.string.foodlog_photo_question else R.string.foodlog_photo_question_more),
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.semantics { heading() },
                            )
                        }
                        item(key = "suggestions") {
                            AppCard {
                                r.suggestions.forEachIndexed { i, sug ->
                                    if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHighest)
                                    val name = vm.catalogName(sug.label)
                                    val hint = stringResource(FoodFormat.hintRes(RecognitionMath.hint(sug.confidence)))
                                    ListItem(
                                        headlineContent = { Text(name) },
                                        supportingContent = { Text(hint) },
                                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                        modifier = Modifier.clickable(role = Role.Button) {
                                            if (vm.chooseSuggestion(sug.label)) onOpen(FoodRoutes.CONFIRM)
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (s.status != PhotoStatus.RECOGNIZING) {
                val suggested = s.status == PhotoStatus.DONE && s.recognition?.let { !it.notFood && it.suggestions.isNotEmpty() } == true
                val label = when {
                    suggested -> R.string.foodlog_photo_other
                    s.added.isNotEmpty() -> R.string.foodlog_photo_add_another
                    else -> R.string.foodlog_photo_search
                }
                item(key = "other") {
                    OutlinedButton(
                        onClick = {
                            vm.startSearch(forPhoto = s.status != PhotoStatus.UNREADABLE)
                            onOpen(FoodRoutes.SEARCH)
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        Icon(Icons.Filled.Search, contentDescription = null)
                        Text(stringResource(label), modifier = Modifier.padding(start = 8.dp))
                    }
                }
                if (vm.canReadPackages && s.preview != null && s.pack.text == PackText.Idle) item(key = "pack-search") { PackSearchButton(onClick = vm::searchPackage) }
            }
            item(key = "privacy") { FoodSupporting(stringResource(R.string.foodlog_photo_private)) }
        }
    }
}

/**
 * One food of the plate: its crop, the food chosen (or "what is it?"), whether "Add all" adds it, the
 * three best suggestions and "Other…", the variants of the food chosen and its amount (last time's
 * when it was logged before, else the photo's suggestion, marked, or the food's portion).
 */
@Composable
private fun PlateItemCard(item: PlateItem, onSearch: () -> Unit) {
    val vm = foodViewModel()
    val food = item.food
    AppCard {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).then(
                    if (item.added || food == null) {
                        Modifier
                    } else {
                        Modifier.toggleable(value = item.included, role = Role.Checkbox, onValueChange = { vm.setItemIncluded(item.id, it) })
                    },
                ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(
                    item.preview,
                    contentDescription = stringResource(R.string.foodlog_plate_item_desc, item.id + 1),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(food?.name ?: stringResource(R.string.foodlog_plate_unknown), style = MaterialTheme.typography.titleMedium)
                    val top = item.recognition?.suggestions?.firstOrNull { it.label == food?.foodId }
                    when {
                        item.added -> FoodSupporting(stringResource(R.string.foodlog_plate_item_added))
                        top != null -> FoodSupporting(stringResource(FoodFormat.hintRes(RecognitionMath.hint(top.confidence))))
                        food == null -> FoodSupporting(stringResource(R.string.foodlog_plate_unknown_text))
                    }
                }
                when {
                    item.added -> Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    food != null -> Checkbox(checked = item.included, onCheckedChange = null)
                }
            }
            if (item.added) return@Column
            val suggestions = item.recognition?.takeIf { !it.notFood }?.suggestions.orEmpty().take(PLATE_SUGGESTIONS)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (sug in suggestions) {
                    FilterChip(
                        selected = sug.label == food?.foodId,
                        onClick = { vm.chooseItemSuggestion(item.id, sug.label) },
                        label = { Text(vm.catalogName(sug.label)) },
                    )
                }
                FilterChip(
                    selected = food != null && suggestions.none { it.label == food.foodId },
                    onClick = onSearch,
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    label = { Text(stringResource(R.string.foodlog_photo_other)) },
                )
            }
            if (food != null && item.variants.size > 1) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item.variants.forEachIndexed { i, v ->
                        FilterChip(
                            selected = v.ref == food.ref,
                            onClick = { vm.chooseItemFood(item.id, v) },
                            label = { Text(if (i == 0) stringResource(R.string.foodlog_variant_typical) else v.name) },
                        )
                    }
                }
            }
            if (food != null) {
                OutlinedTextField(
                    value = item.amountText,
                    onValueChange = { vm.setItemAmount(item.id, it) },
                    singleLine = true,
                    label = { Text(stringResource(if (food.unit == FoodUnit.ML) R.string.foodlog_millilitres else R.string.foodlog_grams)) },
                    suffix = { Text(stringResource(if (food.unit == FoodUnit.ML) R.string.unit_ml else R.string.unit_g)) },
                    isError = item.amount == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    textStyle = MaterialTheme.typography.bodyLarge.merge(TabularNumbers),
                    modifier = Modifier.fillMaxWidth(),
                )
                item.estimate?.let { e ->
                    val amount = item.amount
                    EstimateNote(
                        e, food.unit, explain = false,
                        onUse = { vm.useItemEstimate(item.id) }.takeIf { amount == null || abs(amount - FoodMath.round1(e)) >= 0.05 },
                    )
                }
                // Logged before: "Add all" logs it as last time (its amount unless typed, ingredients, values).
                item.recall?.let { r ->
                    RecallNote(r, amount = !item.amountTyped && !item.amountFromEstimate, values = r.per100 != null, onUsual = { vm.useItemUsual(item.id) })
                }
            }
        }
    }
}

/** Suggestions shown for each food of a plate. */
private const val PLATE_SUGGESTIONS = 3

/**
 * The packaged product the photo may show, at the top of the page: the barcode read on it (its page
 * opens by itself once found), the scanned products it looks like ("Is it …?", or a few suggestions),
 * and the products Open Food Facts has for the words on the package. Each opens the product's page
 * (Found → Use), nothing is logged from here.
 */
private fun LazyListScope.packItems(pack: PackUi, onOpen: (String) -> Unit, onBarcodePhoto: () -> Unit) {
    pack.barcode?.let { b ->
        item(key = "pack-barcode") {
            val vm = foodViewModel()
            val found = b.state as? BarcodeState.Found
            val looking = b.state is BarcodeState.LookingUp
            val status = when (b.state) {
                is BarcodeState.Found -> listOfNotNull(found?.item?.brand, stringResource(R.string.foodlog_pack_barcode, b.code)).joinToString(" · ")
                is BarcodeState.LookingUp -> stringResource(R.string.foodlog_barcode_looking_up, b.code)
                is BarcodeState.NotFound -> stringResource(R.string.foodlog_barcode_not_found)
                is BarcodeState.Offline -> stringResource(R.string.foodlog_barcode_offline)
                else -> stringResource(R.string.foodlog_barcode_failed)
            }
            AppCard(containerColor = MaterialTheme.colorScheme.primaryContainer) {
                ProductRow(
                    name = found?.item?.name ?: stringResource(R.string.foodlog_pack_barcode, b.code),
                    detail = status,
                    customFoodId = found?.item?.customFoodId,
                    fromOpenFoodFacts = found?.item?.fromOpenFoodFacts == true,
                    working = looking,
                    onClick = {
                        vm.openPhotoBarcode()
                        onOpen(FoodRoutes.BARCODE)
                    }.takeUnless { looking },
                )
            }
        }
    }
    pack.match?.let { m ->
        item(key = "pack-match") {
            val vm = foodViewModel()
            AppCard(containerColor = MaterialTheme.colorScheme.primaryContainer) {
                ProductRow(
                    name = stringResource(R.string.foodlog_pack_match, m.name),
                    detail = listOfNotNull(m.brand, stringResource(R.string.foodlog_pack_match_text)).joinToString(" · "),
                    customFoodId = m.customFoodId,
                    fromOpenFoodFacts = true,
                    onClick = { if (vm.choosePackProduct(m.customFoodId)) onOpen(FoodRoutes.BARCODE) },
                )
            }
        }
    }
    if (pack.suggestions.isNotEmpty()) {
        item(key = "pack-suggestions") {
            val vm = foodViewModel()
            AppCard {
                Column(Modifier.padding(top = 12.dp)) {
                    Text(
                        stringResource(R.string.foodlog_pack_suggestions),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(horizontal = 16.dp).semantics { heading() },
                    )
                    pack.suggestions.forEach { p ->
                        ProductRow(
                            name = p.name,
                            detail = p.brand,
                            customFoodId = p.customFoodId,
                            fromOpenFoodFacts = true,
                            onClick = { if (vm.choosePackProduct(p.customFoodId)) onOpen(FoodRoutes.BARCODE) },
                        )
                    }
                }
            }
        }
    }
    val text = pack.text
    if (text == PackText.Idle) return
    item(key = "pack-text") { PackTextStatus(text, foodViewModel(), onOpen, onBarcodePhoto) }
}

/** TalkBack says what the reading and the search come to as it changes (the status lines only, not the field). */
private val announced = Modifier.semantics { liveRegion = LiveRegionMode.Polite }

/**
 * What the package search shows: the words searched in a field the user can correct and search again
 * (a logo is not text, so the words read may be the small print), or an empty field to type the brand
 * and name when none could be read; then the search working, its products, or why there are none
 * (with what can be done).
 */
@Composable
private fun PackTextStatus(text: PackText, vm: FoodViewModel, onOpen: (String) -> Unit, onBarcodePhoto: () -> Unit) {
    val start = PhotoPackRules.wordsField(text)
    if (start == null) {
        if (text == PackText.Reading) PackWorking(stringResource(R.string.foodlog_pack_reading))
        return
    }
    // The field's text, as the user sees it: what any search (the field's own, or "Try again") sends.
    var words by rememberSaveable { mutableStateOf(start) }
    var edited by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(start) { words = PhotoPackRules.fieldAfter(words, edited, start) }
    val search = {
        edited = false
        vm.searchPackageWords(words)
    }
    val padded = Modifier.padding(horizontal = 16.dp)
    AppCard {
        Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PackWordsField(
                words,
                onChange = {
                    words = it.take(PackageText.MAX_TYPED)
                    edited = true
                },
                searching = text is PackText.Searching,
                onSearch = search,
                modifier = padded,
            )
            when (text) {
                PackText.Idle, PackText.Reading -> Unit
                is PackText.Searching -> PackWorking(stringResource(R.string.foodlog_pack_searching, text.query), padded)
                is PackText.Results -> {
                    Column(padded, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            stringResource(R.string.foodlog_pack_results, text.query),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = announced.semantics { heading() },
                        )
                        FoodSupporting(stringResource(R.string.foodlog_pack_results_text))
                    }
                    Column {
                        text.hits.forEachIndexed { i, hit ->
                            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHighest)
                            ListItem(
                                headlineContent = { Text(hit.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                                supportingContent = {
                                    listOfNotNull(hit.brand, hit.quantity).joinToString(" · ").takeIf { it.isNotEmpty() }?.let {
                                        Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                },
                                leadingContent = { HitThumbnail(hit.imageUrl, 48.dp) },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                modifier = Modifier.clickable(role = Role.Button) {
                                    vm.choosePackHit(hit)
                                    onOpen(FoodRoutes.BARCODE)
                                },
                            )
                        }
                    }
                    OffAttribution(photo = text.hits.any { it.imageUrl != null }, padded)
                }
                PackText.NoText -> PackProblem(stringResource(R.string.foodlog_pack_no_text), onBarcodePhoto = onBarcodePhoto)
                PackText.ReadFailed -> PackProblem(stringResource(R.string.foodlog_pack_read_failed), onRetry = vm::searchPackage, onBarcodePhoto = onBarcodePhoto)
                is PackText.NotFound -> PackProblem(stringResource(R.string.foodlog_pack_none, text.query), onBarcodePhoto = onBarcodePhoto)
                is PackText.Offline -> PackProblem(stringResource(R.string.foodlog_pack_offline), onRetry = search, onBarcodePhoto = onBarcodePhoto)
                is PackText.Busy -> PackProblem(stringResource(R.string.foodlog_pack_busy), onRetry = search, onBarcodePhoto = onBarcodePhoto)
                is PackText.Failed -> PackProblem(stringResource(R.string.foodlog_barcode_failed), onRetry = search, onBarcodePhoto = onBarcodePhoto)
                is PackText.Unavailable -> if (text.preparing) {
                    PackProblem(stringResource(R.string.foodlog_pack_preparing), onRetry = vm::searchPackage, onBarcodePhoto = onBarcodePhoto)
                } else {
                    PackProblem(stringResource(R.string.foodlog_pack_unavailable), onBarcodePhoto = onBarcodePhoto)
                }
            }
            FoodSupporting(stringResource(R.string.foodlog_pack_private), padded)
        }
    }
}

/**
 * The words searched in Open Food Facts, editable ([words], kept by the caller: "Try again" sends them
 * too): the words read or last searched, empty with a hint when none could be read; searched again only
 * when submitted. Autocorrect is off (brand names).
 */
@Composable
private fun PackWordsField(words: String, onChange: (String) -> Unit, searching: Boolean, onSearch: () -> Unit, modifier: Modifier = Modifier) {
    val keyboard = LocalSoftwareKeyboardController.current
    val canSearch = !searching && PackageText.typed(words) != null
    val submit = {
        if (canSearch) {
            keyboard?.hide()
            onSearch()
        }
    }
    OutlinedTextField(
        value = words,
        onValueChange = onChange,
        singleLine = true,
        label = { Text(stringResource(R.string.foodlog_pack_words_label)) },
        placeholder = { Text(stringResource(R.string.foodlog_pack_words_hint)) },
        trailingIcon = {
            IconButton(onClick = submit, enabled = canSearch) {
                Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.foodlog_barcode_search))
            }
        },
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { submit() }),
        modifier = modifier.fillMaxWidth(),
    )
}

/** A product with its photo (from Open Food Facts, credited once shown): tap to open it. */
@Composable
private fun ProductRow(
    name: String,
    detail: String?,
    customFoodId: String?,
    fromOpenFoodFacts: Boolean,
    onClick: (() -> Unit)?,
    working: Boolean = false,
) {
    var photoShown by remember(customFoodId) { mutableStateOf(false) }
    Column {
        ListItem(
            headlineContent = { Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            supportingContent = detail?.takeIf { it.isNotEmpty() }?.let { { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis) } },
            leadingContent = {
                if (customFoodId != null) {
                    MealThumbnail(null, 56.dp, customFoodId = customFoodId, onProductPhoto = { photoShown = it })
                } else {
                    Icon(AppIcons.Barcode, contentDescription = null, modifier = Modifier.size(32.dp))
                }
            },
            trailingContent = {
                when {
                    working -> CircularProgressIndicator(Modifier.size(24.dp))
                    onClick != null -> Icon(AppIcons.ChevronRight, contentDescription = null)
                }
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier,
        )
        if (fromOpenFoodFacts && photoShown) OffAttribution(photo = true, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp))
    }
}

/** A product of the package search's photo (downloaded for the list, kept in memory only), or the generic icon. */
@Composable
private fun HitThumbnail(url: String?, size: Dp) {
    val vm = foodViewModel()
    val px = with(LocalDensity.current) { size.roundToPx() }
    val photo by produceState(initialValue = url?.let(vm::cachedHitPhoto), url, px) {
        if (url != null && value == null) value = vm.hitPhoto(url, px * 2)
    }
    val shape = RoundedCornerShape(12.dp)
    val p = photo
    if (p != null) {
        Image(p, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(size).clip(shape))
    } else {
        Box(Modifier.size(size).clip(shape).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
            Icon(AppIcons.Restaurant, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(size / 2))
        }
    }
}

@Composable
private fun PackWorking(text: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator(Modifier.padding(4.dp))
        FoodSupporting(text, announced)
    }
}

/** Why the package search found nothing, with what can be done instead ("Try again", a photo of the barcode). */
@Composable
private fun PackProblem(text: String, onBarcodePhoto: () -> Unit, onRetry: (() -> Unit)? = null) {
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FoodSupporting(text, announced)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            onRetry?.let { Button(onClick = it, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.action_retry)) } }
            OutlinedButton(onClick = onBarcodePhoto, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(AppIcons.Barcode, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.foodlog_pack_barcode_photo), modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

/** "Search the product by its package": its words read on the phone, then searched in Open Food Facts. */
@Composable
private fun PackSearchButton(onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Icon(Icons.Filled.ShoppingCart, contentDescription = null)
        Text(stringResource(R.string.foodlog_pack_search), modifier = Modifier.padding(start = 8.dp))
    }
}
