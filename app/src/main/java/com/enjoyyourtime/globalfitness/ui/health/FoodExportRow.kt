package com.enjoyyourtime.globalfitness.ui.health

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.enjoyyourtime.globalfitness.GlobalFitnessApp
import com.enjoyyourtime.globalfitness.R
import com.enjoyyourtime.globalfitness.health.FoodExportState
import com.enjoyyourtime.globalfitness.health.HcAvailability
import com.enjoyyourtime.globalfitness.health.HealthModule
import com.enjoyyourtime.globalfitness.health.HealthPermissions
import com.enjoyyourtime.globalfitness.health.HealthSettings
import com.enjoyyourtime.globalfitness.ui.asString
import com.enjoyyourtime.globalfitness.ui.theme.TabularNumbers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class FoodExportUiState(
    val availability: HcAvailability = HcAvailability.NOT_SUPPORTED,
    val canWrite: Boolean = false,
    val enabled: Boolean = false,
    val export: FoodExportState = FoodExportState(),
)

/** State of the "Save meals to Health Connect" switch ([FoodExportRow]). */
class FoodExportViewModel(private val health: HealthModule) : ViewModel() {
    val uiState: StateFlow<FoodExportUiState> = combine(
        health.gateway.availability,
        health.gateway.granted,
        health.foodExporter.choice,
        health.foodExporter.state,
    ) { availability, granted, choice, export ->
        val canWrite = HealthPermissions.canExportMeals(granted)
        FoodExportUiState(availability, canWrite, HealthSettings.exportEnabled(choice, canWrite), export)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, FoodExportUiState())

    val permissionContract = health.gateway.permissionContract()

    /** Only the nutrition permission (the rest of Health Connect may be connected already). */
    val missingPermissions: Set<String> = setOf(HealthPermissions.WRITE_NUTRITION)

    fun setEnabled(enabled: Boolean) = health.foodExporter.setEnabled(enabled)

    fun onPermissionsResult() {
        viewModelScope.launch(Dispatchers.IO) { health.gateway.refresh() }
    }

    fun openHealthConnect(): Boolean = health.gateway.openSettings()

    companion object {
        val Factory = viewModelFactory {
            initializer { FoodExportViewModel((this[APPLICATION_KEY] as GlobalFitnessApp).container.health) }
        }
    }
}

/**
 * "Save meals to Health Connect" next to the workouts switch (and in the food goal page), with its
 * own request of the nutrition write permission; same states as [WorkoutExportRow].
 */
@Composable
fun FoodExportRow(modifier: Modifier = Modifier, vm: FoodExportViewModel = viewModel(factory = FoodExportViewModel.Factory)) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    var requested by rememberSaveable { mutableStateOf(false) }
    val permissions = rememberLauncherForActivityResult(vm.permissionContract) { vm.onPermissionsResult() }
    val available = state.availability == HcAvailability.AVAILABLE

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val switchable = available && state.canWrite
        Row(
            Modifier.toggleable(value = state.enabled, enabled = switchable, role = Role.Switch, onValueChange = vm::setEnabled),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.hc_meals_export_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Switch(checked = state.enabled, onCheckedChange = null, enabled = switchable)
        }
        Supporting(stringResource(R.string.hc_meals_export_text))
        when {
            !available -> Supporting(stringResource(R.string.hc_not_available))
            !state.canWrite -> {
                Supporting(stringResource(R.string.hc_meals_export_missing_permission))
                if (requested) Supporting(stringResource(R.string.hc_meals_export_denied))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = {
                        requested = true
                        permissions.launch(vm.missingPermissions)
                    }) { Text(stringResource(R.string.hc_workouts_export_allow)) }
                    if (requested) OutlinedButton(onClick = { vm.openHealthConnect() }) { Text(stringResource(R.string.hc_open)) }
                }
            }
            !state.enabled -> Small(stringResource(R.string.hc_meals_export_off))
            else -> {
                val count = state.export.savedCount
                Small(if (count == 0) stringResource(R.string.hc_meals_export_count_none) else pluralStringResource(R.plurals.hc_meals_export_count, count, count))
            }
        }
        state.export.error?.let { Text(it.asString(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun Supporting(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Small(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall.merge(TabularNumbers), color = MaterialTheme.colorScheme.onSurfaceVariant)
}
