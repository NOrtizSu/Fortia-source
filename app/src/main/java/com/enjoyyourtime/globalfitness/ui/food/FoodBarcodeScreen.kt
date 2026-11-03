package com.enjoyyourtime.globalfitness.ui.food

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.enjoyyourtime.globalfitness.R
import com.enjoyyourtime.globalfitness.food.barcode.BarcodeReader
import com.enjoyyourtime.globalfitness.food.model.FoodUnit
import com.enjoyyourtime.globalfitness.food.model.Nutrient
import com.enjoyyourtime.globalfitness.ui.Fmt
import com.enjoyyourtime.globalfitness.ui.components.AppCard
import com.enjoyyourtime.globalfitness.ui.components.InfoRow

/**
 * A packaged product from a photo of its barcode: read on the phone (ZXing), then looked up in Open
 * Food Facts (only the number is sent). Not found → manual entry with the barcode; values missing →
 * the user completes them; no connection → retry. A product found once is kept for next time.
 */
@Composable
fun FoodBarcodeScreen(onOpen: (String) -> Unit, onBack: () -> Unit) {
    val vm = foodViewModel()
    val state by vm.barcode.collectAsStateWithLifecycle()
    val back = {
        vm.clearBarcode()
        onBack()
    }
    BackHandler(onBack = back)
    // The lookup does not survive the process: back to the day instead of a spinner forever.
    LaunchedEffect(Unit) { if (vm.barcode.value == null) onBack() }
    FoodSubPage(title = stringResource(R.string.foodlog_barcode_title), onBack = back) { padding ->
        Column(
            Modifier.padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (val s = state) {
                null, BarcodeState.Reading -> Working(stringResource(R.string.foodlog_barcode_reading))
                is BarcodeState.LookingUp -> Working(stringResource(R.string.foodlog_barcode_looking_up, s.code))
                BarcodeState.NotRead -> {
                    Text(stringResource(R.string.foodlog_barcode_not_read), style = MaterialTheme.typography.titleMedium)
                    FoodSupporting(stringResource(R.string.foodlog_barcode_not_read_text))
                    TypeCode(onSubmit = vm::onBarcodeTyped)
                }
                is BarcodeState.NotFound -> {
                    Text(stringResource(R.string.foodlog_barcode_not_found), style = MaterialTheme.typography.titleMedium)
                    FoodSupporting(stringResource(R.string.foodlog_barcode_not_found_text, s.code))
                    Button(
                        onClick = {
                            vm.manualFromBarcode()
                            onOpen(FoodRoutes.MANUAL)
                        },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text(stringResource(R.string.foodlog_add_manual)) }
                }
                is BarcodeState.Offline -> {
                    Text(stringResource(R.string.foodlog_barcode_offline), style = MaterialTheme.typography.titleMedium)
                    FoodSupporting(stringResource(R.string.foodlog_barcode_offline_text, s.code))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = vm::retryBarcode, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.action_retry)) }
                        OutlinedButton(
                            onClick = {
                                vm.manualFromBarcode()
                                onOpen(FoodRoutes.MANUAL)
                            },
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) { Text(stringResource(R.string.foodlog_add_manual)) }
                    }
                }
                is BarcodeState.Failed -> {
                    Text(stringResource(R.string.foodlog_barcode_failed), style = MaterialTheme.typography.titleMedium)
                    Button(onClick = vm::retryBarcode, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.action_retry)) }
                }
                is BarcodeState.Found -> {
                    val item = s.item
                    AppCard {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(item.name, style = MaterialTheme.typography.titleLarge)
                            item.brand?.let { FoodSupporting(it) }
                            FoodSupporting(stringResource(R.string.foodlog_barcode_code, s.code))
                            // Logged before: "Use this product" starts as that time ("Last time: 2 × Serving · 60 g").
                            s.last?.let { FoodSupporting(stringResource(R.string.foodlog_barcode_last_time, FoodFormat.entryAmount(it))) }
                            Text(
                                stringResource(if (item.unit == FoodUnit.ML) R.string.foodlog_values_per_100ml else R.string.foodlog_values_per_100g),
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                            for (n in Nutrient.LABEL) {
                                val v = item.per100[n]
                                InfoRow(stringResource(FoodFormat.nutrientRes(n)), v?.let { FoodFormat.value(n, it) } ?: "—")
                            }
                            // Values the user added to a kept product (Open Food Facts gives only the label's).
                            ExtraNutrientRows(item.per100, knownOnly = true)
                            if (item.fromOpenFoodFacts) {
                                Text(
                                    stringResource(R.string.foodlog_off_attribution),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 8.dp),
                                )
                            }
                        }
                    }
                    if (s.missing.isNotEmpty()) {
                        FoodSupporting(
                            stringResource(R.string.foodlog_barcode_missing, Fmt.list(s.missing.map { stringResource(FoodFormat.nutrientRes(it)) })),
                        )
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val needsKcal = Nutrient.KCAL in s.missing
                        if (!needsKcal) {
                            Button(
                                onClick = {
                                    vm.useBarcodeProduct()
                                    onOpen(FoodRoutes.CONFIRM)
                                },
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) { Text(stringResource(R.string.foodlog_barcode_use)) }
                        }
                        if (s.missing.isNotEmpty()) {
                            val complete: @Composable () -> Unit = { Text(stringResource(R.string.foodlog_barcode_complete)) }
                            val onComplete = {
                                vm.manualFromBarcode()
                                onOpen(FoodRoutes.MANUAL)
                            }
                            if (needsKcal) {
                                Button(onClick = onComplete, modifier = Modifier.heightIn(min = 48.dp)) { complete() }
                            } else {
                                OutlinedButton(onClick = onComplete, modifier = Modifier.heightIn(min = 48.dp)) { complete() }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Working(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator()
        FoodSupporting(text)
    }
}

@Composable
private fun TypeCode(onSubmit: (String) -> Unit) {
    var code by rememberSaveable { mutableStateOf("") }
    val valid = BarcodeReader.isValidCode(code)
    OutlinedTextField(
        value = code,
        onValueChange = { v -> code = v.filter(Char::isDigit).take(14) },
        singleLine = true,
        label = { Text(stringResource(R.string.foodlog_barcode_type)) },
        isError = code.length >= 8 && !valid,
        supportingText = if (code.length >= 8 && !valid) {
            { Text(stringResource(R.string.foodlog_barcode_invalid)) }
        } else {
            null
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { if (valid) onSubmit(code) }),
        modifier = Modifier.fillMaxWidth(),
    )
    Button(onClick = { onSubmit(code) }, enabled = valid, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.foodlog_barcode_search)) }
}
