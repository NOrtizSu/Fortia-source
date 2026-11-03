package com.enjoyyourtime.globalfitness.ui.food

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.enjoyyourtime.globalfitness.R
import com.enjoyyourtime.globalfitness.food.model.FoodItem
import com.enjoyyourtime.globalfitness.ui.components.ConfirmDialog
import com.enjoyyourtime.globalfitness.ui.theme.TabularNumbers
import kotlinx.coroutines.flow.drop

/**
 * Search the catalog and the user's own foods in the current language (accents and case ignored);
 * the recent foods while the query is empty. A food that is not there is entered by hand.
 */
@Composable
fun FoodSearchScreen(onOpen: (String) -> Unit, onBack: () -> Unit) {
    val vm = foodViewModel()
    val query by vm.query.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }
    var deleting by remember { mutableStateOf<FoodItem?>(null) }
    // Choosing the food of one item of a plate: a food of the list, nothing typed by hand.
    val forItem = remember { vm.searchingForItem }
    LaunchedEffect(Unit) {
        vm.ensureCatalog()
        runCatching { focus.requestFocus() }
    }
    // A new query starts at its best match. The list would otherwise keep its scroll position on the
    // first visible food (by key) when that food is also in the new results, hiding the better ones above.
    val listState = rememberLazyListState()
    LaunchedEffect(listState) {
        snapshotFlow { query to results }.drop(1).collect { listState.scrollToItem(0) }
    }

    FoodSubPage(title = stringResource(R.string.foodlog_search_title), onBack = onBack) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
            OutlinedTextField(
                value = query,
                onValueChange = vm::setQuery,
                singleLine = true,
                placeholder = { Text(stringResource(R.string.foodlog_search_hint)) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { vm.setQuery("") }) { Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.foodlog_search_clear)) }
                    }
                },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).focusRequester(focus),
            )
            LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 24.dp), modifier = Modifier.weight(1f)) {
                if (query.isBlank() && results.isNotEmpty()) {
                    item(key = "recent") {
                        Text(
                            stringResource(R.string.foodlog_search_recent),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).semantics { heading() },
                        )
                    }
                }
                if (query.isNotBlank() && results.isEmpty()) {
                    item(key = "none") {
                        FoodSupporting(stringResource(R.string.foodlog_search_none, query.trim()), Modifier.padding(16.dp))
                    }
                }
                items(results, key = { it.ref.toString() }) { item ->
                    ResultRow(
                        item,
                        onClick = {
                            // For an item of a plate the food goes back to it; otherwise it is confirmed.
                            if (vm.chooseItem(item)) onOpen(FoodRoutes.CONFIRM) else onBack()
                        },
                        onEdit = {
                            vm.editCustomFood(item)
                            onOpen(FoodRoutes.MANUAL)
                        },
                        onDelete = { deleting = item },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHighest)
                }
                if (!forItem) item(key = "manual") {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                vm.startManual(name = query.trim())
                                onOpen(FoodRoutes.MANUAL)
                            },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) {
                            Icon(Icons.Filled.Edit, contentDescription = null)
                            Text(stringResource(R.string.foodlog_add_manual), modifier = Modifier.padding(start = 8.dp))
                        }
                        OutlinedButton(
                            onClick = {
                                vm.startManual(name = query.trim(), saveAsFood = true)
                                onOpen(FoodRoutes.MANUAL)
                            },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) { Text(stringResource(R.string.foodlog_create_food)) }
                    }
                }
            }
        }
    }
    deleting?.let { item ->
        ConfirmDialog(
            title = stringResource(R.string.foodlog_food_delete_title, item.name),
            text = stringResource(R.string.foodlog_food_delete_text),
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = {
                vm.deleteCustomFood(item)
                deleting = null
            },
            onDismiss = { deleting = null },
        )
    }
}

/** A food of the results; the user's own foods (and kept products) also have a menu on a long press: edit, delete. */
@Composable
private fun ResultRow(item: FoodItem, onClick: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    val kcal = item.per100.kcal?.let { stringResource(FoodFormat.kcalPer100Res(item.unit), FoodFormat.kcal(it)) }
    val detail = listOfNotNull(item.brand, kcal ?: "—").joinToString(" · ")
    val own = item.customFoodId != null
    var menu by remember { mutableStateOf(false) }
    Box {
        ListItem(
            headlineContent = { Text(item.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            supportingContent = { Text(detail, style = MaterialTheme.typography.bodyMedium.merge(TabularNumbers), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = if (own) {
                Modifier.combinedClickable(
                    role = Role.Button,
                    onLongClickLabel = stringResource(R.string.foodlog_food_options),
                    onLongClick = { menu = true },
                    onClick = onClick,
                )
            } else {
                Modifier.clickable(role = Role.Button, onClick = onClick)
            },
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.foodlog_food_edit)) },
                leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                onClick = {
                    menu = false
                    onEdit()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.foodlog_food_delete)) },
                leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                onClick = {
                    menu = false
                    onDelete()
                },
            )
        }
    }
}
