package com.enjoyyourtime.globalfitness.ui.training

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import com.enjoyyourtime.globalfitness.R
import com.enjoyyourtime.globalfitness.training.catalog.ExerciseAliases
import com.enjoyyourtime.globalfitness.training.model.Equipment
import com.enjoyyourtime.globalfitness.training.model.Exercise
import com.enjoyyourtime.globalfitness.training.model.Muscle
import com.enjoyyourtime.globalfitness.ui.Fmt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.Collator

/** An exercise with its texts in the current language, ready to search and sort. */
private class BrowseItem(val exercise: Exercise, val name: String, val subtitle: String, val haystack: String)

/** Which exercises the catalog lists. */
private enum class CatalogScope(val label: Int) {
    ALL(R.string.training_scope_all),
    MINE(R.string.training_scope_mine),
    FAVORITES(R.string.training_scope_favorites),
}

/** The user's machines: their own exercises and any exercise they gave a photo or settings. */
private val Exercise.isMine: Boolean get() = !builtIn || photo != null || !notes.isNullOrBlank()

/**
 * Every visible (not archived) exercise with its localized name, primary muscles and search text
 * (name, equipment, every muscle and a built-in exercise's search words, [ExerciseAliases], without
 * accents), sorted with the language's collation. A stretch says so first ("Stretching ·
 * Hamstrings") and is found by that word. Built off the main thread; null until ready.
 */
@Composable
private fun rememberBrowseItems(exercises: Map<String, Exercise>): List<BrowseItem>? {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val state = produceState<List<BrowseItem>?>(null, exercises, configuration) {
        value = withContext(Dispatchers.Default) {
            val collator = Collator.getInstance(Fmt.locale).apply { strength = Collator.PRIMARY }
            val stretching = context.getString(R.string.training_mobility)
            exercises.values.filter { !it.archived }.map { ex ->
                val name = ex.name.resolve(context)
                val equipment = context.getString(ex.equipment.labelRes)
                val primary = ex.primary.sortedBy { it.ordinal }.map { context.getString(it.labelRes) }
                val secondary = ex.secondary.sortedBy { it.ordinal }.map { context.getString(it.labelRes) }
                val kind = listOfNotNull(stretching.takeIf { ex.mobility })
                val aliases = listOfNotNull(ExerciseAliases[ex.id]?.takeIf { ex.builtIn }?.let(context::getString))
                BrowseItem(
                    exercise = ex,
                    name = name,
                    subtitle = (kind + primary.ifEmpty { listOf(equipment) }).joinToString(" · "),
                    haystack = TrainingFormat.normalize((listOf(name, equipment) + kind + primary + secondary + aliases).joinToString(" ")),
                )
            }.sortedWith { a, b -> collator.compare(a.name, b.name) }
        }
    }
    return state.value
}

/**
 * Search text and muscle / equipment filters shared by the picker and the catalog. A muscle lists
 * the exercises that train it, not the sports that move it (they have their own section).
 */
private class BrowseFilter(val query: String, val muscle: Muscle?, val equipment: Equipment?) {
    val active: Boolean get() = query.isNotBlank() || muscle != null || equipment != null

    private val tokens = TrainingFormat.searchTokens(query)

    fun accepts(item: BrowseItem): Boolean =
        (muscle == null || (muscle in item.exercise.primary && item.exercise.equipment != Equipment.SPORT)) &&
            (equipment == null || item.exercise.equipment == equipment) &&
            TrainingFormat.matches(tokens, item.haystack)
}

@Composable
private fun FilterBar(
    query: String,
    onQuery: (String) -> Unit,
    muscle: Muscle?,
    onMuscle: (Muscle?) -> Unit,
    equipment: Equipment?,
    onEquipment: (Equipment?) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            placeholder = { Text(stringResource(R.string.training_search)) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQuery("") }) { Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.training_search_clear)) }
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(Muscle.entries) { m ->
                FilterChip(selected = m == muscle, onClick = { onMuscle(if (m == muscle) null else m) }, label = { Text(m.label()) })
            }
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(Equipment.entries) { e ->
                FilterChip(
                    selected = e == equipment,
                    onClick = { onEquipment(if (e == equipment) null else e) },
                    label = { Text(e.label()) },
                    leadingIcon = { Icon(e.icon, contentDescription = null, modifier = Modifier.size(18.dp)) },
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)
            .semantics { heading() },
    )
}

/** A row of the lists: thumbnail (photo or trained muscles), name, primary muscles, favourite star, [trailing] controls (ⓘ, tick). */
@Composable
private fun ExerciseRow(item: BrowseItem, vm: TrainingViewModel, modifier: Modifier, trailing: @Composable () -> Unit = {}) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ExerciseThumbnail(item.exercise, size = 44.dp, vm = vm)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(item.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(item.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (item.exercise.favorite) {
            Icon(Icons.Filled.Star, contentDescription = stringResource(R.string.training_favorite), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        trailing()
    }
}

@Composable
private fun EmptyText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 24.dp),
    )
}

@Composable
private fun Loading() {
    Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

/** The "create" row at the top of the picker (and its only row when nothing matches). */
@Composable
private fun CreateRow(onCreate: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onCreate)
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.width(16.dp))
        Text(stringResource(R.string.training_create_exercise), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
    }
}

/**
 * Chooses exercises to add to a workout or a routine ([onAdd] gets them, then the page leaves):
 * search, muscle and equipment filters, favourites and recent ones first, then every exercise A–Z
 * and the sports and activities in their own section.
 * Several can be ticked and added at once. An exercise created from here comes back as
 * [createdExerciseId] and is ticked with the others ([onCreatedTicked] clears it).
 */
@Composable
fun ExercisePickerScreen(
    onAdd: (List<String>) -> Unit,
    onBack: () -> Unit,
    onCreate: () -> Unit,
    createdExerciseId: String?,
    onCreatedTicked: () -> Unit,
) {
    val vm = trainingViewModel()
    val exercises by vm.exercises.collectAsStateWithLifecycle()
    val recent by vm.recentExerciseIds.collectAsStateWithLifecycle()
    val items = rememberBrowseItems(exercises)
    var query by rememberSaveable { mutableStateOf("") }
    var muscle by rememberSaveable { mutableStateOf<Muscle?>(null) }
    var equipment by rememberSaveable { mutableStateOf<Equipment?>(null) }
    val selected = rememberSaveable(saver = listSaver(save = { it.toList() }, restore = { it.toMutableStateList() })) {
        mutableStateListOf<String>()
    }
    val filter = BrowseFilter(query, muscle, equipment)
    LaunchedEffect(createdExerciseId) {
        if (createdExerciseId != null) {
            if (createdExerciseId !in selected) selected.add(createdExerciseId)
            onCreatedTicked()
        }
    }
    // A second tap while the page leaves must not add the exercises twice.
    var added by rememberSaveable { mutableStateOf(false) }
    val add = dropUnlessResumed {
        if (!added) {
            added = true
            onAdd(selected.toList())
            onBack()
        }
    }

    TrainingSubPage(
        title = stringResource(R.string.training_picker_title),
        onBack = onBack,
        bottomBar = {
            if (selected.isNotEmpty()) {
                Surface(tonalElevation = 3.dp) {
                    Button(
                        onClick = add,
                        enabled = !added,
                        modifier = Modifier
                            .navigationBarsPadding()
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                            .heightIn(min = 48.dp),
                    ) {
                        Text(pluralStringResource(R.plurals.training_add_selected, selected.size, Fmt.count(selected.size.toLong())))
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(Modifier.subPageContent(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
            item(key = "filters") { FilterBar(query, { query = it }, muscle, { muscle = it }, equipment, { equipment = it }) }
            if (items == null) {
                item(key = "loading") { Loading() }
                return@LazyColumn
            }
            item(key = "create") { CreateRow(onCreate) }
            val toggle: (String) -> Unit = { id -> if (id in selected) selected.remove(id) else selected.add(id) }
            if (filter.active) {
                val found = items.filter(filter::accepts)
                if (found.isEmpty()) item(key = "none") { EmptyText(stringResource(R.string.training_no_results)) }
                sportsLast("found", found) { section, list -> pickerRows(section, list, vm, selected, toggle) }
            } else {
                val byId = items.associateBy { it.exercise.id }
                val favorites = items.filter { it.exercise.favorite }
                val recentItems = recent.mapNotNull { byId[it] }
                if (favorites.isNotEmpty()) {
                    item(key = "h-fav") { SectionHeader(stringResource(R.string.training_scope_favorites)) }
                    pickerRows("fav", favorites, vm, selected, toggle)
                }
                if (recentItems.isNotEmpty()) {
                    item(key = "h-recent") { SectionHeader(stringResource(R.string.training_recent_exercises)) }
                    pickerRows("recent", recentItems, vm, selected, toggle)
                }
                item(key = "h-all") { SectionHeader(stringResource(R.string.training_all_exercises)) }
                sportsLast("all", items) { section, list -> pickerRows(section, list, vm, selected, toggle) }
            }
        }
    }
}

/**
 * [list] as rows ([rows] gets a key prefix and a part of it): the sports and activities last, under
 * their own header when other exercises come before them, so they do not mix with the gym A–Z.
 * Walking, the most common activity, heads them; the rest stay in A–Z order.
 */
private fun LazyListScope.sportsLast(section: String, list: List<BrowseItem>, rows: LazyListScope.(String, List<BrowseItem>) -> Unit) {
    val (others, sports) = list.partition { it.exercise.equipment != Equipment.SPORT }
    rows(section, others)
    if (sports.isEmpty()) return
    if (others.isNotEmpty()) item(key = "$section-h-sports") { SectionHeader(stringResource(R.string.equipment_sport)) }
    rows("$section-sports", sports.sortedBy { it.exercise.id != WALKING_ID })
}

/** The built-in walking activity, listed first among the sports. */
private const val WALKING_ID = "outdoor_walk"

/** Rows that tick an exercise; keys are prefixed because favourites and recent ones repeat in "all". */
private fun LazyListScope.pickerRows(
    section: String,
    list: List<BrowseItem>,
    vm: TrainingViewModel,
    selected: List<String>,
    onToggle: (String) -> Unit,
) {
    items(list, key = { "$section-${it.exercise.id}" }) { item ->
        val id = item.exercise.id
        val checked = id in selected
        ExerciseRow(
            item = item,
            vm = vm,
            modifier = Modifier.toggleable(value = checked, role = Role.Checkbox, onValueChange = { onToggle(id) }),
            trailing = {
                // Its own button: the row's tap still ticks. No "see progress" from here, it would leave the picker.
                ExerciseInfoButton(item.exercise)
                Checkbox(checked = checked, onCheckedChange = null)
            },
        )
    }
}

/**
 * The exercise and machine catalog: every exercise, the user's machines or the favourites, with
 * the same search and filters as the picker. A row opens the exercise; the button creates a machine,
 * or an activity while the sports filter is on ([onCreate] gets whether it is a sport).
 */
@Composable
fun CatalogScreen(onBack: () -> Unit, onOpenExercise: (String) -> Unit, onCreate: (sport: Boolean) -> Unit) {
    val vm = trainingViewModel()
    val exercises by vm.exercises.collectAsStateWithLifecycle()
    val items = rememberBrowseItems(exercises)
    var scope by rememberSaveable { mutableStateOf(CatalogScope.ALL) }
    var query by rememberSaveable { mutableStateOf("") }
    var muscle by rememberSaveable { mutableStateOf<Muscle?>(null) }
    var equipment by rememberSaveable { mutableStateOf<Equipment?>(null) }
    val filter = BrowseFilter(query, muscle, equipment)
    val list = remember(items, scope, query, muscle, equipment) {
        items?.filter { item ->
            val ex = item.exercise
            filter.accepts(item) && when (scope) {
                CatalogScope.ALL -> true
                CatalogScope.MINE -> ex.isMine
                CatalogScope.FAVORITES -> ex.favorite
            }
        }
    }

    TrainingSubPage(
        title = stringResource(R.string.training_catalog_title),
        onBack = onBack,
        floatingActionButton = {
            val sport = equipment == Equipment.SPORT
            ExtendedFloatingActionButton(
                onClick = { onCreate(sport) },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(stringResource(if (sport) R.string.training_new_activity else R.string.training_new_machine)) },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.subPageContent(padding), contentPadding = PaddingValues(bottom = 96.dp)) {
            item(key = "scope") {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    CatalogScope.entries.forEachIndexed { i, s ->
                        SegmentedButton(
                            selected = s == scope,
                            onClick = { scope = s },
                            shape = SegmentedButtonDefaults.itemShape(i, CatalogScope.entries.size),
                            icon = {},
                            label = { Text(stringResource(s.label), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        )
                    }
                }
            }
            item(key = "filters") { FilterBar(query, { query = it }, muscle, { muscle = it }, equipment, { equipment = it }) }
            when {
                list == null -> item(key = "loading") { Loading() }
                list.isEmpty() -> item(key = "none") {
                    EmptyText(
                        stringResource(
                            when {
                                filter.active -> R.string.training_no_results
                                scope == CatalogScope.MINE -> R.string.training_mine_empty
                                else -> R.string.training_favorites_empty
                            },
                        ),
                    )
                }
                else -> {
                    item(key = "count") {
                        Text(
                            pluralStringResource(R.plurals.training_exercises_count, list.size, Fmt.count(list.size.toLong())),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
                        )
                    }
                    sportsLast("catalog", list) { _, part ->
                        items(part, key = { it.exercise.id }) { item ->
                            ExerciseRow(
                                item = item,
                                vm = vm,
                                modifier = Modifier.clickable(onClickLabel = stringResource(R.string.training_open_exercise)) { onOpenExercise(item.exercise.id) },
                                trailing = { ExerciseInfoButton(item.exercise, onOpenProgress = { onOpenExercise(item.exercise.id) }) },
                            )
                        }
                    }
                }
            }
        }
    }
}
