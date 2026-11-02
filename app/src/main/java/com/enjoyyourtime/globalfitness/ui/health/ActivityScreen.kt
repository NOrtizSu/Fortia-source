@file:OptIn(ExperimentalMaterial3Api::class)

package com.enjoyyourtime.globalfitness.ui.health

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.enjoyyourtime.globalfitness.R
import com.enjoyyourtime.globalfitness.health.HcAvailability
import com.enjoyyourtime.globalfitness.health.HealthDataType
import com.enjoyyourtime.globalfitness.health.HealthPermissions
import com.enjoyyourtime.globalfitness.health.SleepStage
import com.enjoyyourtime.globalfitness.health.TimedValue
import com.enjoyyourtime.globalfitness.health.domain.HealthStats
import com.enjoyyourtime.globalfitness.health.domain.HeartRateZones
import com.enjoyyourtime.globalfitness.health.domain.SleepQuality
import com.enjoyyourtime.globalfitness.health.domain.SleepRating
import com.enjoyyourtime.globalfitness.health.labelRes
import com.enjoyyourtime.globalfitness.ui.Fmt
import com.enjoyyourtime.globalfitness.ui.asString
import com.enjoyyourtime.globalfitness.ui.components.AppCard
import com.enjoyyourtime.globalfitness.ui.components.Dot
import com.enjoyyourtime.globalfitness.ui.components.SectionTitle
import com.enjoyyourtime.globalfitness.ui.components.StatusChip
import com.enjoyyourtime.globalfitness.ui.progress.ProgressTabContent
import com.enjoyyourtime.globalfitness.ui.theme.ChartTheme
import com.enjoyyourtime.globalfitness.ui.theme.TabularNumbers
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

private const val SLEEP_CHART_NIGHTS = 14
private const val SPO2_DAYS = 14
private const val VITALS_DAYS = 30
private const val BODY_FAT_DAYS = 90

/**
 * Activity (the Actividad tab of Progreso): what the band or watch wrote to Health Connect (today,
 * steps, heart rate, sleep, SpO2, heart-rate variability, respiratory rate, workouts, body fat from
 * other apps) plus the sync status. Connection and permissions live in Devices and the scale export
 * switch in Profile. [listState] is hoisted so Progreso's title bar knows when the page is scrolled.
 */
@Composable
fun ActivityScreen(vm: HealthViewModel, onConnect: () -> Unit, listState: LazyListState) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val dashboard by vm.dashboard.collectAsStateWithLifecycle()
    val detail by vm.detail.collectAsStateWithLifecycle()
    var stepsRange by rememberSaveable { mutableStateOf(DayRange.D30) }

    ProgressTabContent(listState) {
        val available = state.availability == HcAvailability.AVAILABLE
        if (!available || !state.canRead) {
            item {
                HealthConnectionCard(
                    state = state,
                    onConnect = onConnect,
                    onOpenSettings = { vm.openHealthConnectSettings() },
                    onOpenPlayStore = { vm.openPlayStore() },
                )
            }
            item { MiFitnessHelpCard() }
            return@ProgressTabContent
        }
        item { SyncCard(state, onSync = vm::syncNow) }
        val d = dashboard ?: return@ProgressTabContent
        if (!d.hasData && !state.sync.running) item { NoDataCard() }
        // Partial grants: each section is shown only when its data type is readable.
        val readable = HealthPermissions.readableTypes(state.granted)
        item { SectionTitle(stringResource(R.string.nav_today)) }
        item { TodayGrid(d) }
        if (HealthDataType.STEPS in readable) item { StepsCard(d, stepsRange) { stepsRange = it } }
        if (HealthDataType.HEART_RATE in readable || d.hrDayDevices.isNotEmpty()) item { HeartRateCard(d, onDay = vm::selectHrDay) }
        if (HealthDataType.HEART_RATE_VARIABILITY in readable) item { HrvCard(d) }
        if (HealthDataType.SLEEP in readable) item { SleepCard(d) }
        if (HealthDataType.OXYGEN_SATURATION in readable) item { Spo2Card(d) }
        if (HealthDataType.RESPIRATORY_RATE in readable) item { RespiratoryRateCard(d) }
        if (HealthDataType.BODY_FAT in readable) item { ExternalBodyFatCard(d) }
        if (HealthDataType.EXERCISE in readable) {
            item { SectionTitle(stringResource(R.string.health_workouts_title)) }
            if (d.exercises.isEmpty()) {
                item { EmptyText(stringResource(R.string.health_workouts_none)) }
            } else {
                items(d.exercises, key = { it.session.id }) { ExerciseRow(it, onClick = { vm.openExercise(it) }) }
            }
        }
        item { SourcesCard(d) }
        item { MiFitnessHelpCard() }
    }

    detail?.let { item ->
        val age = state.profile?.ageAt(System.currentTimeMillis())
        ExerciseDetailDialog(item, age, onDismiss = vm::closeExercise)
    }
}

// ---- Connection / onboarding --------------------------------------------------------------

/** Health Connect status: install / connect / permissions, and a shortcut to its settings. */
@Composable
fun HealthConnectionCard(
    state: HealthUiState,
    onConnect: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenPlayStore: () -> Unit,
    showTitle: Boolean = true,
) {
    AppCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (showTitle) Text("Health Connect", style = MaterialTheme.typography.titleMedium)
            when (state.availability) {
                HcAvailability.NOT_SUPPORTED -> BodyText(stringResource(R.string.hc_not_supported))
                HcAvailability.NOT_INSTALLED -> {
                    BodyText(stringResource(R.string.hc_install_text))
                    Button(onClick = onOpenPlayStore) { Text(stringResource(R.string.action_install_health_connect)) }
                }
                HcAvailability.UPDATE_REQUIRED -> {
                    BodyText(stringResource(R.string.hc_update_text))
                    Button(onClick = onOpenPlayStore) { Text(stringResource(R.string.action_update_health_connect)) }
                }
                HcAvailability.AVAILABLE -> when {
                    state.granted.isEmpty() -> {
                        BodyText(stringResource(R.string.hc_connect_text))
                        Button(onClick = onConnect) { Text(stringResource(R.string.action_connect_health_connect)) }
                    }
                    else -> {
                        StatusLine(
                            stringResource(if (state.allDataGranted) R.string.hc_connected else R.string.hc_connected_partial),
                            good = state.allDataGranted,
                        )
                        val missing = HealthPermissions.missingWearableTypes(state.granted)
                        if (missing.isNotEmpty()) {
                            BodyText(stringResource(R.string.hc_missing_read, Fmt.list(missing.map { stringResource(it.labelRes) })))
                        }
                        if (!state.canExport) BodyText(stringResource(R.string.hc_missing_write_weight))
                        if (state.missingWorkoutPermission) BodyText(stringResource(R.string.hc_missing_write_exercise))
                        if (state.historyAvailable && !HealthPermissions.historyGranted(state.granted)) {
                            BodyText(stringResource(R.string.hc_history_hint))
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (!state.allGranted || state.missingWorkoutPermission) {
                                FilledTonalButton(onClick = onConnect) { Text(stringResource(R.string.hc_review_permissions)) }
                            }
                            OutlinedButton(onClick = onOpenSettings) { Text(stringResource(R.string.hc_open)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusLine(text: String, good: Boolean) {
    val colors = ChartTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Dot(if (good) colors.good else colors.warning, size = 10.dp)
        Text(text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun SyncCard(state: HealthUiState, onSync: () -> Unit) {
    val sync = state.sync
    AppCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.hc_sync_title), style = MaterialTheme.typography.titleSmall)
                    Text(
                        when {
                            sync.running -> stringResource(R.string.hc_syncing)
                            sync.lastSyncAt != null -> stringResource(R.string.hc_last_sync, Fmt.relative(sync.lastSyncAt))
                            else -> stringResource(R.string.hc_never_synced)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                FilledTonalButton(onClick = onSync, enabled = !sync.running) {
                    Icon(Icons.Filled.Refresh, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.hc_sync_now))
                }
            }
            if (sync.running) LinearProgressIndicator(Modifier.fillMaxWidth())
            sync.error?.let { Text(it.asString(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun NoDataCard() {
    AppCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.health_no_band_data_title), style = MaterialTheme.typography.titleSmall)
            BodyText(stringResource(R.string.health_no_band_data_text))
        }
    }
}

@Composable
private fun MiFitnessHelpCard() {
    AppCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.mifitness_help_title), style = MaterialTheme.typography.titleSmall)
            BodyText(stringResource(R.string.mifitness_help_step1))
            BodyText(stringResource(R.string.mifitness_help_step2))
            BodyText(stringResource(R.string.mifitness_help_step3))
            BodyText(stringResource(R.string.mifitness_help_other_apps))
        }
    }
}

// ---- Today ----------------------------------------------------------------------------------

@Composable
private fun TodayGrid(d: HealthDashboard) {
    val t = d.todaySummary
    val night = d.lastNight
    val quality = d.lastNightQuality
    val restingAvg = d.restingTrend.current
    val tiles = listOf<@Composable (Modifier) -> Unit>(
        { m ->
            Tile(
                stringResource(R.string.today_steps),
                t.steps?.let { HealthFormat.count(it) } ?: "—",
                d.stepsAvg7?.let { stringResource(R.string.health_avg_7_days, HealthFormat.count(it.average.roundToInt().toLong())) },
                m,
            )
        },
        { m ->
            Tile(
                stringResource(R.string.hc_type_active_calories),
                t.activeKcal?.let { HealthFormat.kcal(it) } ?: "—",
                t.totalKcal?.let { stringResource(R.string.health_total_kcal, HealthFormat.kcal(it)) },
                m,
            )
        },
        { m -> Tile(stringResource(R.string.hc_type_distance), t.distanceM?.let { HealthFormat.km(it) } ?: "—", null, m) },
        { m ->
            Tile(
                stringResource(R.string.hc_type_resting_heart_rate),
                t.restingHr?.let { HealthFormat.bpm(it) } ?: "—",
                restingAvg?.let { avg ->
                    val average = HealthFormat.bpm(avg.average.roundToInt())
                    d.restingTrend.change?.let { stringResource(R.string.health_avg_7_days_vs_previous, average, Fmt.signed(it, 0)) }
                        ?: stringResource(R.string.health_avg_7_days, average)
                },
                m,
            )
        },
        { m ->
            Tile(
                stringResource(R.string.health_last_night_sleep),
                night?.let { HealthFormat.minutes(it.asleepMinutes) } ?: "—",
                d.sleepAvg7?.let { stringResource(R.string.health_avg_7_days, HealthFormat.minutes(it.average.roundToInt())) },
                m,
                chip = quality,
            )
        },
        { m ->
            Tile(
                stringResource(R.string.hc_type_blood_oxygen),
                t.spo2Avg?.let { HealthFormat.percent(it) } ?: "—",
                t.spo2Min?.let { stringResource(R.string.health_minimum_value, HealthFormat.percent(it)) },
                m,
            )
        },
    )
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        tiles.chunked(2).forEach { row ->
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { tile -> tile(Modifier.weight(1f).fillMaxHeight()) }
            }
        }
    }
}

@StringRes
private fun SleepRating.labelRes(): Int = when (this) {
    SleepRating.GOOD -> R.string.sleep_quality_good
    SleepRating.FAIR -> R.string.sleep_quality_fair
    SleepRating.POOR -> R.string.sleep_quality_poor
}

/** "Good · 85/100". */
@Composable
private fun SleepQualityChip(q: SleepQuality) {
    StatusChip(stringResource(R.string.sleep_quality_chip, stringResource(q.rating.labelRes()), Fmt.count(q.score.toLong())), q.status)
}

@Composable
private fun Tile(label: String, value: String, sub: String?, modifier: Modifier, chip: SleepQuality? = null) {
    AppCard(modifier) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleLarge.merge(TabularNumbers), fontWeight = FontWeight.SemiBold)
            chip?.let { SleepQualityChip(it) }
            sub?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

// ---- Steps ----------------------------------------------------------------------------------

@Composable
private fun StepsCard(d: HealthDashboard, range: DayRange, onRange: (DayRange) -> Unit) {
    val series = HealthStats.filledSeries(d.daily.mapNotNull { (date, a) -> a.steps?.let { date to it } }.toMap(), d.today, range.days)
    val withData = series.mapNotNull { it.second }
    ChartCard(stringResource(R.string.health_daily_steps)) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            DayRange.entries.forEachIndexed { i, r ->
                SegmentedButton(
                    selected = r == range,
                    onClick = { onRange(r) },
                    shape = SegmentedButtonDefaults.itemShape(i, DayRange.entries.size),
                    icon = {},
                    label = { Text(pluralStringResource(R.plurals.common_days, r.days, r.days)) },
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        if (withData.isEmpty()) {
            EmptyText(stringResource(R.string.health_steps_none))
        } else {
            StepsBarChart(series, HealthViewModel.STEPS_GOAL)
            val reached = withData.count { it >= HealthViewModel.STEPS_GOAL }
            val parts = listOfNotNull(
                stringResource(R.string.health_steps_average, HealthFormat.count(withData.average().roundToInt().toLong())),
                pluralStringResource(
                    R.plurals.health_steps_goal_met,
                    withData.size,
                    HealthFormat.count(HealthViewModel.STEPS_GOAL),
                    reached,
                    withData.size,
                ),
                d.stepsAvg30?.let { stringResource(R.string.health_avg_30_days, HealthFormat.count(it.average.roundToInt().toLong())) },
            )
            SmallText(parts.joinToString(" · "))
        }
    }
}

// ---- Heart rate -----------------------------------------------------------------------------

@Composable
private fun HeartRateCard(d: HealthDashboard, onDay: (LocalDate) -> Unit) {
    ChartCard(stringResource(R.string.health_heart_rate)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onDay(d.hrDay.minusDays(1)) }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = stringResource(R.string.health_previous_day))
            }
            Text(
                HealthFormat.relativeDay(d.hrDay, d.today),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { onDay(d.hrDay.plusDays(1)) }, enabled = d.hrDay < d.today) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = stringResource(R.string.health_next_day))
            }
        }
        val (start, end) = HealthStats.dayBounds(d.hrDay, ZoneId.systemDefault())
        if (d.hrDaySamples.isEmpty()) {
            EmptyText(stringResource(R.string.health_hr_day_none))
        } else {
            HeartRateDayChart(d.hrDaySamples, d.hrDayResting, start, end)
            val stats = HeartRateZones.stats(d.hrDaySamples, start, end - 1)
            if (stats != null) {
                val base = stringResource(
                    R.string.health_hr_day_stats,
                    HealthFormat.bpm(stats.avg),
                    Fmt.count(stats.min.toLong()),
                    Fmt.count(stats.max.toLong()),
                )
                SmallText(d.hrDayResting?.let { stringResource(R.string.health_hr_day_stats_resting, base, HealthFormat.bpm(it)) } ?: base)
            }
        }
        d.hrDayDevices.forEach { series ->
            Spacer(Modifier.height(16.dp))
            Text(
                series.name?.let { stringResource(R.string.health_wearable_live_title, it) } ?: stringResource(R.string.health_band_direct_title),
                style = MaterialTheme.typography.titleSmall,
            )
            SmallText(stringResource(R.string.health_wearable_live_text))
            Spacer(Modifier.height(8.dp))
            HeartRateDayChart(series.samples, null, start, end)
        }
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.health_last_30_days), style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        if (d.hrRanges.isEmpty()) EmptyText(stringResource(R.string.health_no_hr_data)) else HeartRateRangeChart(d.hrDays, d.hrRanges)
    }
}

// ---- Sleep ----------------------------------------------------------------------------------

@Composable
private fun SleepCard(d: HealthDashboard) {
    ChartCard(stringResource(R.string.today_sleep)) {
        val night = d.lastNight
        if (night == null) {
            EmptyText(stringResource(R.string.health_sleep_last_night_none))
        } else {
            Text(
                stringResource(R.string.health_last_night_range, Fmt.time(night.start), Fmt.time(night.end)),
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(4.dp))
            d.lastNightQuality?.let { q ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SleepQualityChip(q)
                    Text(
                        stringResource(R.string.health_asleep_value, HealthFormat.minutes(night.asleepMinutes)),
                        style = MaterialTheme.typography.bodyMedium.merge(TabularNumbers),
                    )
                }
                SmallText(
                    stringResource(R.string.sleep_quality_explained) + " " +
                        (
                            q.restorativeShare?.let { stringResource(R.string.sleep_quality_restorative, Fmt.percent(it * 100), Fmt.percent(40.0)) }
                                ?: stringResource(R.string.sleep_quality_no_stages)
                            ),
                )
            }
            Spacer(Modifier.height(8.dp))
            if (night.hasStages || night.awakeMinutes > 0) Hypnogram(night)
            val parts = SleepStage.entries.filter { night.minutesOf(it) > 0 }
                .map { stringResource(R.string.chart_composition_item, stringResource(it.labelRes), HealthFormat.minutes(night.minutesOf(it))) } +
                listOfNotNull(night.efficiencyPercent?.let { stringResource(R.string.health_sleep_efficiency, Fmt.percent(it)) })
            SmallText(parts.joinToString(" · "))
        }
        Spacer(Modifier.height(16.dp))
        Text(pluralStringResource(R.plurals.health_last_nights, SLEEP_CHART_NIGHTS, SLEEP_CHART_NIGHTS), style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        val nights = HealthStats.filledSeries(d.nights, d.today, SLEEP_CHART_NIGHTS)
        if (nights.all { it.second == null }) {
            EmptyText(stringResource(R.string.health_sleep_none))
        } else {
            SleepStackedChart(nights)
            d.sleepAvg7?.let { SmallText(stringResource(R.string.health_sleep_avg_7_nights, HealthFormat.minutes(it.average.roundToInt()))) }
        }
    }
}

// ---- Readings: SpO2, HRV, respiratory rate, body fat from other apps -------------------------

@Composable
private fun Spo2Card(d: HealthDashboard) {
    ChartCard(pluralStringResource(R.plurals.health_spo2_title, SPO2_DAYS, SPO2_DAYS)) {
        if (d.spo2.isEmpty()) {
            EmptyText(stringResource(R.string.health_spo2_none))
        } else {
            Spo2Chart(d.spo2, d.spo2Start, d.spo2End)
            val values = d.spo2.map { it.value }
            SmallText(
                pluralStringResource(
                    R.plurals.health_spo2_summary,
                    values.size,
                    HealthFormat.percent(values.average()),
                    HealthFormat.percent(values.min()),
                    values.size,
                ),
            )
            SourceText(d, d.spo2)
        }
    }
}

/** Heart-rate variability (RMSSD) from the band or watch: higher usually means better recovery. */
@Composable
private fun HrvCard(d: HealthDashboard) {
    val ms = { v: Double -> Fmt.string(R.string.value_ms, Fmt.num(v, 0)) }
    ReadingsCard(
        title = pluralStringResource(R.plurals.health_hrv_title, VITALS_DAYS, VITALS_DAYS),
        points = d.hrv,
        xStart = d.vitalsStart,
        xEnd = d.vitalsEnd,
        format = ms,
        axisFormat = { v, decimals -> Fmt.num(v, decimals) },
        description = { stringResource(R.string.chart_readings_desc, stringResource(R.string.hc_type_hrv), it) },
        empty = stringResource(R.string.health_hrv_none),
        explanation = stringResource(R.string.health_hrv_explained),
        dashboard = d,
    )
}

@Composable
private fun RespiratoryRateCard(d: HealthDashboard) {
    val rate = { v: Double -> Fmt.string(R.string.value_breaths_per_minute, Fmt.num(v, 1)) }
    ReadingsCard(
        title = pluralStringResource(R.plurals.health_respiratory_title, VITALS_DAYS, VITALS_DAYS),
        points = d.respiratoryRate,
        xStart = d.vitalsStart,
        xEnd = d.vitalsEnd,
        format = rate,
        axisFormat = { v, decimals -> Fmt.num(v, decimals) },
        description = { stringResource(R.string.chart_readings_desc, stringResource(R.string.hc_type_respiratory_rate), it) },
        empty = stringResource(R.string.health_respiratory_none),
        explanation = stringResource(R.string.health_respiratory_explained),
        dashboard = d,
    )
}

/** Body fat that other apps (another scale's app, a manual log) wrote to Health Connect. */
@Composable
private fun ExternalBodyFatCard(d: HealthDashboard) {
    ReadingsCard(
        title = pluralStringResource(R.plurals.health_body_fat_other_title, BODY_FAT_DAYS, BODY_FAT_DAYS),
        points = d.externalBodyFat,
        xStart = d.bodyFatStart,
        xEnd = d.vitalsEnd,
        format = { Fmt.percent(it, 1) },
        axisFormat = { v, decimals -> Fmt.percent(v, decimals) },
        description = { stringResource(R.string.chart_readings_desc, stringResource(R.string.hc_type_body_fat), it) },
        empty = stringResource(R.string.health_body_fat_other_none),
        explanation = stringResource(R.string.health_body_fat_other_explained),
        dashboard = d,
    )
}

/**
 * A card with a value read from Health Connect over time: the chart, latest value, average and
 * number of readings, the source apps, and an explanation; or an empty state.
 */
@Composable
private fun ReadingsCard(
    title: String,
    points: List<TimedValue>,
    xStart: Long,
    xEnd: Long,
    format: (Double) -> String,
    axisFormat: (Double, Int) -> String,
    description: @Composable (String) -> String,
    empty: String,
    explanation: String,
    dashboard: HealthDashboard,
) {
    ChartCard(title) {
        if (points.isEmpty()) {
            EmptyText(empty)
        } else {
            val latest = points.last()
            val summary = pluralStringResource(
                R.plurals.health_readings_summary,
                points.size,
                format(points.map { it.value }.average()),
                points.size,
            )
            ReadingsChart(
                points = points,
                xStart = xStart,
                xEnd = xEnd,
                format = format,
                axisFormat = axisFormat,
                description = description(summary),
            )
            Text(
                stringResource(R.string.health_latest_value, format(latest.value), Fmt.relative(latest.time)),
                style = MaterialTheme.typography.titleSmall.merge(TabularNumbers),
                modifier = Modifier.padding(top = 8.dp),
            )
            SmallText(summary)
            SourceText(dashboard, points)
        }
        SmallText(explanation)
    }
}

/** "Source: Mi Fitness" for the apps that wrote [points]. */
@Composable
private fun SourceText(d: HealthDashboard, points: List<TimedValue>) {
    val names = d.sourcesOf(points)
    SmallText(stringResource(R.string.health_source, if (names.isEmpty()) stringResource(R.string.health_source_unknown) else Fmt.list(names)))
}

// ---- Exercise -------------------------------------------------------------------------------

@Composable
private fun ExerciseItem.displayName(): String = title ?: stringResource(typeName)

@Composable
private fun ExerciseRow(item: ExerciseItem, onClick: () -> Unit) {
    val e = item.session
    AppCard(onClick = onClick, onClickLabel = stringResource(R.string.health_view_details)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.displayName(), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(
                    HealthFormat.minutes((e.activeMs / 60_000L).toInt()),
                    style = MaterialTheme.typography.titleSmall.merge(TabularNumbers),
                )
            }
            val parts = listOfNotNull(
                Fmt.relative(e.start),
                e.activeKcal?.let { HealthFormat.kcal(it) },
                e.distanceM?.takeIf { it > 0 }?.let { HealthFormat.km(it) },
                item.heartRate?.let { stringResource(R.string.health_average_hr, HealthFormat.bpm(it.avg)) },
            )
            SmallText(parts.joinToString(" · "))
            SmallText(stringResource(R.string.health_source, item.source ?: stringResource(R.string.health_source_unknown)))
        }
    }
}

@StringRes
private fun zoneNameRes(index: Int): Int = when (index) {
    1 -> R.string.hr_zone_very_light
    2 -> R.string.hr_zone_light
    3 -> R.string.hr_zone_moderate
    4 -> R.string.hr_zone_hard
    else -> R.string.hr_zone_maximum
}

@Composable
private fun ExerciseDetailDialog(item: ExerciseItem, age: Int?, onDismiss: () -> Unit) {
    val e = item.session
    val name = item.displayName()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                DetailRow(stringResource(R.string.health_start), Fmt.dateTime(e.start))
                DetailRow(stringResource(R.string.health_end), Fmt.dateTime(e.end))
                DetailRow(stringResource(R.string.health_duration), HealthFormat.minutes((e.activeMs / 60_000L).toInt()))
                val typeName = stringResource(item.typeName)
                item.title?.takeIf { it != typeName }?.let { DetailRow(stringResource(R.string.health_title), it) }
                DetailRow(stringResource(R.string.health_type), typeName)
                DetailRow(stringResource(R.string.hc_type_active_calories), e.activeKcal?.let { HealthFormat.kcal(it) } ?: "—")
                DetailRow(stringResource(R.string.hc_type_distance), e.distanceM?.takeIf { it > 0 }?.let { HealthFormat.km(it) } ?: "—")
                DetailRow(stringResource(R.string.health_average_hr_label), item.heartRate?.let { HealthFormat.bpm(it.avg) } ?: "—")
                DetailRow(stringResource(R.string.health_max_hr_label), item.heartRate?.let { HealthFormat.bpm(it.max) } ?: "—")
                DetailRow(stringResource(R.string.health_source_label), item.source ?: stringResource(R.string.health_source_unknown))
                if (age != null && item.zoneMs.any { it > 0 }) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.hr_zones_title, HealthFormat.bpm(HeartRateZones.maxHeartRate(age)), Fmt.count(age.toLong())),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    val zones = HeartRateZones.zones(age)
                    val total = item.zoneMs.sum().coerceAtLeast(1L)
                    item.zoneMs.forEachIndexed { index, ms ->
                        val label = if (index == 0) {
                            stringResource(R.string.hr_zone_rest, Fmt.count(zones.first().minBpm.toLong()))
                        } else {
                            val z = zones[index - 1]
                            val range = z.maxBpm?.let { "${Fmt.count(z.minBpm.toLong())}–${Fmt.count(it - 1L)}" } ?: "${Fmt.count(z.minBpm.toLong())}+"
                            stringResource(R.string.hr_zone_label, Fmt.count(index.toLong()), stringResource(zoneNameRes(index)), range)
                        }
                        DetailRow(label, "${HealthFormat.minutes((ms / 60_000L).toInt())} · ${Fmt.percent(ms * 100.0 / total)}")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium.merge(TabularNumbers))
    }
}

// ---- Sources and export -------------------------------------------------------------------

@Composable
private fun SourcesCard(d: HealthDashboard) {
    if (d.sources.isEmpty() && d.externalWeight == null) return
    AppCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.health_sources_title), style = MaterialTheme.typography.titleSmall)
            if (d.sources.isNotEmpty()) BodyText(Fmt.list(d.sources))
            d.externalWeight?.let { w ->
                BodyText(
                    stringResource(
                        R.string.health_external_weight,
                        Fmt.kg(w.value, 1),
                        d.externalWeightSource ?: stringResource(R.string.health_source_unknown),
                        Fmt.dayMonth(w.time),
                    ),
                )
            }
            SmallText(stringResource(R.string.health_sources_dedup))
        }
    }
}

/**
 * "Send scale measurements to Health Connect" switch and the history export, then the "Save
 * workouts to Health Connect" switch ([WorkoutExportRow], which handles its own state).
 */
@Composable
fun HealthExportCard(state: HealthUiState, onToggle: (Boolean) -> Unit, onExportHistory: () -> Unit, onRequestPermissions: () -> Unit) {
    val export = state.export
    AppCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // The whole row is the switch, so TalkBack reads its title with its state.
            Row(
                Modifier.toggleable(value = state.exportEnabled, enabled = state.canExport, role = Role.Switch, onValueChange = onToggle),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.hc_export_title),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = state.exportEnabled, onCheckedChange = null, enabled = state.canExport)
            }
            BodyText(stringResource(R.string.hc_export_text))
            if (!state.canExport) {
                BodyText(stringResource(R.string.hc_export_missing_permission))
                FilledTonalButton(onClick = onRequestPermissions) { Text(stringResource(R.string.action_grant_permission)) }
            } else {
                SmallText(
                    if (export.exportedCount == 0) {
                        stringResource(R.string.hc_export_count_none)
                    } else {
                        pluralStringResource(R.plurals.hc_export_count, export.exportedCount, export.exportedCount)
                    },
                )
                OutlinedButton(onClick = onExportHistory, enabled = !export.running) { Text(stringResource(R.string.hc_export_history)) }
                if (export.running) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            export.message?.let { SmallText(it.asString()) }
            export.error?.let { Text(it.asString(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest)
            WorkoutExportRow()
            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest)
            FoodExportRow()
        }
    }
}

// ---- Small building blocks ----------------------------------------------------------------

@Composable
private fun ChartCard(title: String, content: @Composable () -> Unit) {
    AppCard {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun BodyText(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SmallText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall.merge(TabularNumbers),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun EmptyText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
    )
}
