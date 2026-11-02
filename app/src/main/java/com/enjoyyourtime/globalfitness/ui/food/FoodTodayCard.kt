package com.enjoyyourtime.globalfitness.ui.food

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.enjoyyourtime.globalfitness.R
import com.enjoyyourtime.globalfitness.ui.components.AppCard
import com.enjoyyourtime.globalfitness.ui.components.AppIcons
import com.enjoyyourtime.globalfitness.ui.theme.TabularNumbers

/** Hoy's "Food today · 1,250 / 2,300 kcal" card with its progress; opens the Comida tab. */
@Composable
fun FoodTodayCard(state: FoodTodayUi, onOpen: () -> Unit) {
    val eaten = FoodFormat.kcal(state.kcal)
    val text = state.goalKcal?.let { stringResource(R.string.foodlog_today_of, eaten, FoodFormat.kcal(it)) } ?: stringResource(R.string.value_kcal, eaten)
    val title = stringResource(R.string.foodlog_today_title)
    val description = stringResource(R.string.foodlog_label_value, title, text)
    AppCard(onClick = onOpen, onClickLabel = stringResource(R.string.foodlog_today_open)) {
        Column(
            Modifier.padding(16.dp).clearAndSetSemantics { contentDescription = description },
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(AppIcons.Restaurant, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(text, style = MaterialTheme.typography.titleSmall.merge(TabularNumbers))
            }
            val goal = state.goalKcal
            if (goal != null && goal > 0) {
                LinearProgressIndicator(
                    progress = { (state.kcal / goal).toFloat().coerceIn(0f, 1f) },
                    color = if (state.kcal > goal) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier.fillMaxWidth().height(8.dp),
                    drawStopIndicator = {},
                    gapSize = 0.dp,
                )
            }
        }
    }
}
