package com.enjoyyourtime.globalfitness.ui.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.enjoyyourtime.globalfitness.R
import com.enjoyyourtime.globalfitness.account.AccountUser
import com.enjoyyourtime.globalfitness.ble.GattSessionInfo
import com.enjoyyourtime.globalfitness.ble.LiveScale
import com.enjoyyourtime.globalfitness.ble.ScaleSuggestion
import com.enjoyyourtime.globalfitness.ble.ScanStatus
import com.enjoyyourtime.globalfitness.cloud.CloudSyncStatus
import com.enjoyyourtime.globalfitness.data.Sex
import com.enjoyyourtime.globalfitness.domain.Metric
import com.enjoyyourtime.globalfitness.domain.TimePoint
import com.enjoyyourtime.globalfitness.domain.Trends
import com.enjoyyourtime.globalfitness.health.HcAvailability
import com.enjoyyourtime.globalfitness.wearable.WearableSummary
import com.enjoyyourtime.globalfitness.ui.BleAccess
import com.enjoyyourtime.globalfitness.ui.Entry
import com.enjoyyourtime.globalfitness.ui.Fmt
import com.enjoyyourtime.globalfitness.ui.MainUiState
import com.enjoyyourtime.globalfitness.ui.label
import com.enjoyyourtime.globalfitness.ui.components.AppCard
import com.enjoyyourtime.globalfitness.ui.components.AppIcons
import com.enjoyyourtime.globalfitness.ui.components.ProgressRing
import com.enjoyyourtime.globalfitness.ui.components.SectionTitle
import com.enjoyyourtime.globalfitness.ui.components.Sparkline
import com.enjoyyourtime.globalfitness.ui.components.StatusChip
import com.enjoyyourtime.globalfitness.ui.components.TabPage
import com.enjoyyourtime.globalfitness.ui.devices.ScaleSuggestionCard
import com.enjoyyourtime.globalfitness.ui.food.FoodTodayCard
import com.enjoyyourtime.globalfitness.ui.food.FoodTodayUi
import com.enjoyyourtime.globalfitness.ui.wearables.wearableStatus
import com.enjoyyourtime.globalfitness.ui.health.HealthDashboard
import com.enjoyyourtime.globalfitness.ui.health.HealthFormat
import com.enjoyyourtime.globalfitness.ui.health.HealthUiState
import com.enjoyyourtime.globalfitness.ui.health.HealthViewModel
import com.enjoyyourtime.globalfitness.ui.progress.MusclesUiState
import com.enjoyyourtime.globalfitness.ui.theme.TabularNumbers
import com.enjoyyourtime.globalfitness.ui.training.TrainingTodayCard
import com.enjoyyourtime.globalfitness.ui.training.music.TodayMusicCard
import com.enjoyyourtime.globalfitness.ui.training.music.musicSuggestions
import kotlinx.coroutines.delay
import java.time.LocalDate
import kotlin.math.abs

private val HIGHLIGHTS = listOf(Metric.FAT_PERCENT, Metric.MUSCLE, Metric.WATER, Metric.VISCERAL)
private const val SLEEP_GOAL_MINUTES = 8 * 60
private const val SPARKLINE_DAYS = 30

/** Everything Hoy shows that does not come from the measurements. */
data class TodayStatus(
    val scanStatus: ScanStatus,
    val live: LiveScale,
    val sessions: Map<String, GattSessionInfo>,
    val health: HealthUiState,
    val dashboard: HealthDashboard?,
    val user: AccountUser?,
    val cloud: CloudSyncStatus,
    val wearable: WearableSummary?,
    /** A nearby scale waiting for "Is this your scale?" (same as in Devices). */
    val suggestion: ScaleSuggestion? = null,
)

/**
 * Today: greeting with today's rings (Health Connect), quick status chips, the weighing in progress,
 * today's workout, music and this week's muscles (once the user has trained), the latest weight
 * with its 30-day trend and body composition highlights.
 */
@Composable
fun TodayScreen(
    state: MainUiState,
    status: TodayStatus,
    muscles: MusclesUiState,
    /** The food card, null while hidden (nothing logged, no goal set). */
    food: FoodTodayUi?,
    onOpenFood: () -> Unit,
    access: BleAccess,
    onRetryScan: () -> Unit,
    onConnectHealth: () -> Unit,
    onInstallHealth: () -> Unit,
    onOpenMetric: (Metric) -> Unit,
    onAddWeight: () -> Unit,
    onOpenAccount: () -> Unit,
    onOpenDevices: () -> Unit,
    onOpenActivity: () -> Unit,
    onOpenBand: () -> Unit,
    onOpenTraining: () -> Unit,
    onOpenMuscles: () -> Unit,
    onOpenSubPage: (route: String) -> Unit,
    onAcceptSuggestion: () -> Unit,
    onDeclineSuggestion: () -> Unit,
) {
    // Keeps relative times and the visibility of a failed session fresh.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }
    val entries = state.entries
    val session = visibleSession(status.sessions, now)
    val music = musicSuggestions()

    TabPage(title = stringResource(R.string.nav_today)) {
        item(key = "hero") {
            HeroCard(
                name = state.profile?.name,
                health = status.health,
                dashboard = status.dashboard,
                onConnect = onConnectHealth,
                onInstall = onInstallHealth,
                onOpenActivity = onOpenActivity,
            )
        }
        val conflict = status.user != null && (status.cloud.conflict != null || status.cloud.deletionPending)
        if (conflict) {
            item(key = "conflict") {
                AppCard(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    onClick = onOpenAccount,
                    onClickLabel = stringResource(R.string.today_open_account),
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.today_cloud_paused_title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                            Text(
                                stringResource(R.string.today_cloud_paused_text),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                }
            }
        }
        item(key = "chips") {
            StatusChips(
                status = status,
                onOpenAccount = onOpenAccount,
                onOpenDevices = onOpenDevices,
                onOpenBand = onOpenBand,
            )
        }
        status.suggestion?.let { s ->
            item(key = "scale-suggestion") { ScaleSuggestionCard(s, onAccept = onAcceptSuggestion, onDecline = onDeclineSuggestion) }
        }
        if (scaleCardVisible(status.scanStatus, status.live, session)) {
            item(key = "scale") { ScaleLiveCard(status.scanStatus, status.live, session, access, onRetryScan) }
        }
        // Only for people who train (any workout, the one in progress included): a scale-only user
        // keeps the weight near the top. Below the cloud, suggestion and weighing cards, which need
        // to be seen right away (the user may be standing on the scale).
        if (muscles.hasWorkouts) {
            item(key = "training") { TrainingTodayCard(onOpenTraining = onOpenTraining, onOpen = onOpenSubPage) }
            music?.let { suggestions -> item(key = "music") { TodayMusicCard(suggestions) } }
            item(key = "muscles") {
                WeekMusclesCard(muscles, female = state.profile?.sex == Sex.FEMALE, onOpen = onOpenMuscles)
            }
        }
        // Only once the user logs food or set a goal: Hoy stays as it was for everyone else.
        food?.let { f -> item(key = "food") { FoodTodayCard(f, onOpen = onOpenFood) } }
        item(key = "weight") { WeightCard(entries, now, onOpen = { onOpenMetric(Metric.WEIGHT) }, onAddWeight = onAddWeight) }
        if (entries.isNotEmpty()) {
            val latest = entries.lastOrNull { it.composition.fatPercent != null }
            item(key = "composition-title") {
                Column {
                    SectionTitle(stringResource(R.string.body_composition))
                    if (latest != null && latest != entries.last()) {
                        Text(
                            stringResource(R.string.today_last_composition, Fmt.relative(latest.measurement.timestamp, now)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                }
            }
            if (latest == null) {
                item(key = "composition-empty") {
                    Text(
                        stringResource(R.string.body_no_composition_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            } else {
                HIGHLIGHTS.chunked(2).forEachIndexed { index, row ->
                    item(key = "composition-$index") {
                        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            row.forEach { metric ->
                                HighlightTile(metric, latest, onClick = { onOpenMetric(metric) }, modifier = Modifier.weight(1f).fillMaxHeight())
                            }
                        }
                    }
                }
            }
        }
        item(key = "disclaimer") {
            Text(
                stringResource(R.string.common_disclaimer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

@Composable
private fun HeroCard(
    name: String?,
    health: HealthUiState,
    dashboard: HealthDashboard?,
    onConnect: () -> Unit,
    onInstall: () -> Unit,
    onOpenActivity: () -> Unit,
) {
    val onContainer = MaterialTheme.colorScheme.onPrimaryContainer
    AppCard(containerColor = MaterialTheme.colorScheme.primaryContainer) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Column {
                Text(
                    name?.let { stringResource(R.string.today_greeting_name, it) } ?: stringResource(R.string.today_greeting),
                    style = MaterialTheme.typography.headlineSmall,
                    color = onContainer,
                    modifier = Modifier.semantics { heading() },
                )
                Text(Fmt.longDate(LocalDate.now()), style = MaterialTheme.typography.bodyMedium, color = onContainer)
            }
            val connected = health.availability == HcAvailability.AVAILABLE && health.canRead
            when {
                connected -> Rings(dashboard, onOpenActivity)
                health.availability == HcAvailability.AVAILABLE -> HeroCallToAction(
                    text = stringResource(R.string.today_hc_connect_text),
                    action = stringResource(R.string.action_connect_health_connect),
                    onClick = onConnect,
                )
                health.availability == HcAvailability.NOT_INSTALLED || health.availability == HcAvailability.UPDATE_REQUIRED ->
                    HeroCallToAction(
                        text = stringResource(R.string.today_hc_install_text),
                        action = stringResource(
                            if (health.availability == HcAvailability.NOT_INSTALLED) R.string.action_install_health_connect else R.string.action_update_health_connect,
                        ),
                        onClick = onInstall,
                    )
                else -> Unit
            }
        }
    }
}

@Composable
private fun HeroCallToAction(text: String, action: String, onClick: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
        Button(onClick = onClick, modifier = Modifier.heightIn(min = 48.dp)) { Text(action) }
    }
}

@Composable
private fun Rings(d: HealthDashboard?, onOpenActivity: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val track = scheme.onPrimaryContainer.copy(alpha = 0.14f)
    val steps = d?.todaySummary?.steps
    val sleep = d?.lastNight?.asleepMinutes
    val resting = d?.todaySummary?.restingHr ?: d?.restingTrend?.current?.average?.let { Math.round(it).toInt() }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        RingColumn(
            label = stringResource(R.string.today_steps),
            caption = stringResource(R.string.today_goal_of, HealthFormat.count(HealthViewModel.STEPS_GOAL)),
            ring = {
                ProgressRing(
                    progress = steps?.let { it.toFloat() / HealthViewModel.STEPS_GOAL },
                    value = steps?.let { HealthFormat.count(it) } ?: "—",
                    unit = "",
                    color = scheme.primary,
                    trackColor = track,
                    description = steps?.let {
                        stringResource(R.string.today_steps_desc, HealthFormat.count(it), HealthFormat.count(HealthViewModel.STEPS_GOAL))
                    } ?: stringResource(R.string.today_steps_desc_none),
                )
            },
        )
        RingColumn(
            label = stringResource(R.string.today_sleep),
            caption = stringResource(R.string.today_sleep_goal),
            ring = {
                ProgressRing(
                    progress = sleep?.let { it.toFloat() / SLEEP_GOAL_MINUTES },
                    value = sleep?.let { HealthFormat.hoursMinutes(it) } ?: "—",
                    unit = if (sleep != null) stringResource(R.string.unit_hours_short) else "",
                    color = scheme.tertiary,
                    trackColor = track,
                    description = sleep?.let { stringResource(R.string.today_sleep_desc, HealthFormat.minutes(it)) }
                        ?: stringResource(R.string.today_sleep_desc_none),
                )
            },
        )
        RingColumn(
            label = stringResource(R.string.today_resting_hr),
            caption = stringResource(R.string.unit_bpm),
            ring = {
                // Scale: a full ring is 100 bpm, the upper end of the normal resting range.
                ProgressRing(
                    progress = resting?.let { it / 100f },
                    value = resting?.let { Fmt.count(it.toLong()) } ?: "—",
                    unit = "",
                    color = scheme.secondary,
                    trackColor = track,
                    description = resting?.let { pluralStringResource(R.plurals.today_resting_hr_desc, it, it) }
                        ?: stringResource(R.string.today_resting_hr_desc_none),
                )
            },
        )
    }
    if (d != null && !d.hasData) {
        Text(
            stringResource(R.string.today_hc_no_data),
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onPrimaryContainer,
        )
    }
    FilledTonalButton(
        onClick = onOpenActivity,
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = scheme.surfaceContainerLowest,
            contentColor = scheme.primary,
        ),
        modifier = Modifier.heightIn(min = 48.dp),
    ) { Text(stringResource(R.string.today_view_activity)) }
}

@Composable
private fun RingColumn(label: String, caption: String, ring: @Composable () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ring()
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer, textAlign = TextAlign.Center)
        Text(caption, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

@Composable
private fun StatusChips(
    status: TodayStatus,
    onOpenAccount: () -> Unit,
    onOpenDevices: () -> Unit,
    onOpenBand: () -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val scale = when (status.scanStatus) {
            is ScanStatus.Scanning -> R.string.chip_scale_listening
            ScanStatus.Starting -> R.string.chip_scale_starting
            ScanStatus.MissingPermission -> R.string.chip_scale_permission
            ScanStatus.BluetoothOff -> R.string.chip_bluetooth_off
            ScanStatus.NoBluetoothLe -> R.string.chip_no_ble
            ScanStatus.Stopped -> R.string.chip_scale_paused
            is ScanStatus.Failed -> R.string.chip_scale_error
        }
        StatusAssistChip(stringResource(scale), AppIcons.Bluetooth, onOpenDevices)
        if (status.user != null) {
            val cloud = status.cloud
            val text = when {
                cloud.conflict != null || cloud.deletionPending -> stringResource(R.string.chip_cloud_paused)
                cloud.syncing || cloud.deleting -> stringResource(R.string.chip_cloud_syncing)
                cloud.error != null -> stringResource(R.string.chip_cloud_error)
                cloud.pendingUpload -> stringResource(R.string.chip_cloud_pending)
                cloud.lastSyncAt != null -> stringResource(R.string.chip_cloud_synced, Fmt.relative(cloud.lastSyncAt))
                else -> stringResource(R.string.chip_cloud_connected)
            }
            StatusAssistChip(text, AppIcons.Cloud, onOpenAccount)
        }
        status.wearable?.let { StatusAssistChip(stringResource(R.string.chip_wearable, it.device.name, wearableStatus(it)), AppIcons.Watch, onOpenBand) }
    }
}

@Composable
private fun StatusAssistChip(text: String, icon: ImageVector, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        label = { Text(text, maxLines = 1) },
        leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize)) },
    )
}

@Composable
private fun WeightCard(entries: List<Entry>, now: Long, onOpen: () -> Unit, onAddWeight: () -> Unit) {
    val latest = entries.lastOrNull()
    if (latest == null) {
        AppCard {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.body_no_measurements_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.today_no_measurements_text),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FilledTonalButton(onClick = onAddWeight, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.action_add_manual_weight))
                }
            }
        }
        return
    }
    val m = latest.measurement
    val points = remember(entries) { entries.map { TimePoint(it.measurement.timestamp, it.measurement.weightKg) } }
    val weekly = remember(points) { Trends.weeklyTrend(Trends.movingAverage(points)) }
    val recent = points.filter { it.t >= now - SPARKLINE_DAYS * Trends.DAY_MS }.map { it.v }
    AppCard(onClick = onOpen, onClickLabel = stringResource(R.string.today_view_weight_trend)) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.metric_weight), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(Fmt.num(m.weightKg, 2), style = MaterialTheme.typography.displaySmall.merge(TabularNumbers))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.unit_kg), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 6.dp))
                }
                val (icon, trend) = when {
                    weekly == null -> AppIcons.TrendFlat to stringResource(R.string.today_weekly_change_none)
                    abs(weekly) < 0.1 -> AppIcons.TrendFlat to
                        stringResource(R.string.today_weekly_stable, stringResource(R.string.value_kg, Fmt.signed(weekly, 2)))
                    else -> (if (weekly > 0) AppIcons.TrendUp else AppIcons.TrendDown) to
                        stringResource(R.string.today_weekly_change, stringResource(R.string.value_kg, Fmt.signed(weekly, 2)))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(6.dp))
                    Text(trend, style = MaterialTheme.typography.bodyMedium.merge(TabularNumbers))
                }
                Text(
                    Fmt.relative(m.timestamp, now),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (recent.size >= 2) {
                Spacer(Modifier.width(16.dp))
                Column(horizontalAlignment = Alignment.End) {
                    Sparkline(recent, MaterialTheme.colorScheme.primary, Modifier.width(112.dp).height(56.dp))
                    Text(pluralStringResource(R.plurals.common_days, SPARKLINE_DAYS, SPARKLINE_DAYS), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun HighlightTile(metric: Metric, latest: Entry, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = latest.composition
    val value = metric.extract(c)
    AppCard(modifier, onClick = onClick, onClickLabel = stringResource(R.string.common_view_trend)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(metric.label(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            Text(value?.let { Fmt.metric(metric, it) } ?: "—", style = MaterialTheme.typography.titleLarge.merge(TabularNumbers), maxLines = 1)
            if (value != null) metric.classify(c)?.let { StatusChip(it) }
        }
    }
}


