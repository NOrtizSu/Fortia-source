package com.enjoyyourtime.globalfitness.ui.food

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.enjoyyourtime.globalfitness.R
import com.enjoyyourtime.globalfitness.food.recognition.RecognitionMath
import com.enjoyyourtime.globalfitness.ui.Fmt
import com.enjoyyourtime.globalfitness.ui.components.AppCard

/**
 * A meal photo and what the recognizer saw in it: the five most likely foods with a plain hint of how
 * sure it is, "Other…" (search), or "no food" when it sees none. After a food is added, the same photo
 * offers the next one (a plate with steak, chips and salad is three entries of one meal).
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
    val s = session
    FoodSubPage(title = stringResource(R.string.foodlog_photo_title), onBack = close) { padding ->
        LazyColumn(
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
                            Button(onClick = close, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.foodlog_done)) }
                        }
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
            }
            item(key = "privacy") { FoodSupporting(stringResource(R.string.foodlog_photo_private)) }
        }
    }
}
