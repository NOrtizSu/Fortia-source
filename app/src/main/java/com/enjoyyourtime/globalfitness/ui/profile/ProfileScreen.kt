package com.enjoyyourtime.globalfitness.ui.profile

import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.enjoyyourtime.globalfitness.BuildConfig
import com.enjoyyourtime.globalfitness.GlobalFitnessApp
import com.enjoyyourtime.globalfitness.R
import com.enjoyyourtime.globalfitness.health.HcAvailability
import com.enjoyyourtime.globalfitness.review.openPlayListing
import com.enjoyyourtime.globalfitness.ui.Fmt
import com.enjoyyourtime.globalfitness.ui.MainViewModel
import com.enjoyyourtime.globalfitness.ui.achievements.achievementsSummary
import com.enjoyyourtime.globalfitness.ui.components.AppCard
import com.enjoyyourtime.globalfitness.ui.components.AppIcons
import com.enjoyyourtime.globalfitness.ui.components.SectionTitle
import com.enjoyyourtime.globalfitness.ui.components.TabPage
import com.enjoyyourtime.globalfitness.ui.health.HealthExportCard
import com.enjoyyourtime.globalfitness.ui.health.HealthViewModel
import com.enjoyyourtime.globalfitness.ui.labelRes
import com.enjoyyourtime.globalfitness.ui.training.TrainingIcons
import java.time.LocalDate

/**
 * Profile: the single profile, devices, training settings and achievements, the optional cloud account, ads /
 * premium, data export and import, the Health Connect export switch, the app language and "About".
 */
@Composable
fun ProfileScreen(
    vm: MainViewModel,
    healthVm: HealthViewModel,
    onConnectHealth: () -> Unit,
    onOpenDevices: () -> Unit,
    onOpenTrainingSettings: () -> Unit,
    onOpenFoodGoal: () -> Unit,
    onOpenAchievements: () -> Unit,
    onOpenAccount: () -> Unit,
    onOpenRemoveAds: () -> Unit,
    onOpenPrivacyPolicy: () -> Unit,
    onOpenLicenses: () -> Unit,
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val user by vm.accountUser.collectAsStateWithLifecycle()
    val cloud by vm.cloudStatus.collectAsStateWithLifecycle()
    val premium by vm.premium.collectAsStateWithLifecycle()
    val privacyOptions by vm.adPrivacyOptionsRequired.collectAsStateWithLifecycle()
    val health by healthVm.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val activity = LocalActivity.current
    var editing by rememberSaveable { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri?.let { vm.exportCsv(context.contentResolver, it) }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.importCsv(context.contentResolver, it) }
    }
    val profile = state.profile ?: return

    TabPage(title = stringResource(R.string.nav_profile)) {
        item(key = "profile") {
            AppCard(containerColor = MaterialTheme.colorScheme.secondaryContainer) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(56.dp)
                                .background(MaterialTheme.colorScheme.primary, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                profile.name.trim().take(1).uppercase(Fmt.locale),
                                style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        }
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(profile.name, style = MaterialTheme.typography.titleLarge)
                            val count = state.entries.size
                            Text(
                                pluralStringResource(R.plurals.profile_measurement_count, count, count),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                    val age = profile.ageAt(System.currentTimeMillis())
                    val details = listOf(
                        stringResource(profile.sex.labelRes),
                        pluralStringResource(R.plurals.value_years, age, Fmt.count(age.toLong())),
                        stringResource(R.string.value_cm, Fmt.num(profile.heightCm, if (profile.heightCm % 1.0 == 0.0) 0 else 1)),
                        profile.targetWeightKg?.let { stringResource(R.string.profile_target_value, Fmt.kg(it, 1)) }
                            ?: stringResource(R.string.profile_no_target),
                    )
                    Text(details.joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = { editing = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Icon(Icons.Filled.Edit, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.profile_edit))
                    }
                }
            }
        }

        item(key = "devices-title") { SectionTitle(stringResource(R.string.profile_section_devices)) }
        item(key = "devices") {
            AppCard {
                SettingRow(
                    icon = AppIcons.Bluetooth,
                    title = stringResource(R.string.nav_devices),
                    subtitle = devicesSummary(),
                    onClick = onOpenDevices,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHighest)
                SettingRow(
                    icon = AppIcons.FitnessCenter,
                    title = stringResource(R.string.profile_training_title),
                    subtitle = stringResource(R.string.profile_training_text),
                    onClick = onOpenTrainingSettings,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHighest)
                SettingRow(
                    icon = AppIcons.Restaurant,
                    title = stringResource(R.string.foodlog_goal_title),
                    subtitle = stringResource(R.string.foodlog_goal_profile_text),
                    onClick = onOpenFoodGoal,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHighest)
                SettingRow(
                    icon = TrainingIcons.Trophy,
                    title = stringResource(R.string.achievements_title),
                    subtitle = achievementsSummary(),
                    onClick = onOpenAchievements,
                )
            }
        }

        item(key = "account-title") { SectionTitle(stringResource(R.string.profile_section_account)) }
        item(key = "account") {
            AppCard {
                val signedIn = user
                val paused = cloud.conflict != null || cloud.deletionPending
                val who = signedIn?.email ?: stringResource(R.string.account_connected)
                SettingRow(
                    icon = if (paused) Icons.Filled.Warning else AppIcons.Cloud,
                    iconTint = if (paused) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    title = stringResource(R.string.profile_account_title),
                    subtitle = when {
                        signedIn == null -> stringResource(R.string.profile_account_none)
                        paused -> stringResource(R.string.profile_account_paused, who)
                        cloud.error != null -> stringResource(R.string.profile_account_error, who)
                        cloud.lastSyncAt != null -> stringResource(R.string.profile_account_synced, who, Fmt.relative(cloud.lastSyncAt!!))
                        else -> who
                    },
                    onClick = onOpenAccount,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHighest)
                if (premium) {
                    SettingRow(
                        icon = Icons.Filled.CheckCircle,
                        title = stringResource(R.string.premium_active_title),
                        subtitle = stringResource(R.string.premium_active_text),
                        onClick = null,
                    )
                } else {
                    SettingRow(
                        icon = Icons.Filled.Star,
                        title = stringResource(R.string.remove_ads_title),
                        subtitle = stringResource(R.string.remove_ads_subtitle),
                        onClick = onOpenRemoveAds,
                    )
                }
                if (privacyOptions) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHighest)
                    SettingRow(
                        icon = Icons.Filled.Lock,
                        title = stringResource(R.string.ads_privacy_options),
                        subtitle = stringResource(R.string.ads_privacy_options_text),
                        onClick = { activity?.let(vm::showAdPrivacyOptions) },
                    )
                }
            }
        }

        item(key = "data-title") { SectionTitle(stringResource(R.string.profile_section_data)) }
        item(key = "data") {
            AppCard {
                SettingRow(
                    icon = AppIcons.Upload,
                    title = stringResource(R.string.csv_export),
                    subtitle = stringResource(R.string.csv_export_text),
                    onClick = { exportLauncher.launch("fortia-${LocalDate.now()}.csv") },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHighest)
                SettingRow(
                    icon = AppIcons.Download,
                    title = stringResource(R.string.csv_import),
                    subtitle = stringResource(R.string.csv_import_text),
                    onClick = {
                        importLauncher.launch(arrayOf("text/*", "application/csv", "application/vnd.ms-excel", "application/octet-stream"))
                    },
                )
            }
        }
        if (health.availability == HcAvailability.AVAILABLE) {
            item(key = "hc") {
                HealthExportCard(
                    state = health,
                    onToggle = healthVm::setExportEnabled,
                    onExportHistory = healthVm::exportHistory,
                    onRequestPermissions = onConnectHealth,
                )
            }
        }

        item(key = "about-title") { SectionTitle(stringResource(R.string.profile_section_about)) }
        item(key = "about") {
            AppCard {
                SettingRow(
                    icon = Icons.Filled.Info,
                    title = "${stringResource(R.string.app_name)} ${BuildConfig.VERSION_NAME}",
                    subtitle = stringResource(
                        if (BuildConfig.DEBUG) R.string.about_version_debug else R.string.about_version,
                        BuildConfig.VERSION_CODE.toString(),
                    ),
                    onClick = null,
                )
                // Rating on the user's own initiative opens the Play page: Play's policy keeps the in-app card off buttons.
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHighest)
                SettingRow(
                    icon = AppIcons.StarOutline,
                    title = stringResource(R.string.about_rate_app),
                    subtitle = stringResource(R.string.about_rate_app_text),
                    onClick = { openPlayListing(context) },
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    // Android 13+: per-app language in the system settings (the app follows the system otherwise).
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHighest)
                    SettingRow(
                        icon = AppIcons.Language,
                        title = stringResource(R.string.profile_language),
                        subtitle = Fmt.locale.getDisplayName(Fmt.locale).replaceFirstChar { it.titlecase(Fmt.locale) },
                        onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(Settings.ACTION_APP_LOCALE_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                                )
                            }
                        },
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHighest)
                SettingRow(
                    icon = AppIcons.Code,
                    title = stringResource(R.string.about_source_code),
                    subtitle = stringResource(R.string.about_source_code_text),
                    onClick = { runCatching { uriHandler.openUri(SOURCE_CODE_URL) } },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHighest)
                SettingRow(
                    icon = Icons.Filled.Lock,
                    title = stringResource(R.string.privacy_policy_title),
                    subtitle = stringResource(R.string.privacy_policy_subtitle),
                    onClick = {
                        // The web version (Spanish) when online and the app is in Spanish; the copy
                        // bundled in the app, in the app's language, otherwise.
                        val online = context.getSystemService(ConnectivityManager::class.java)?.let { cm ->
                            cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                        } == true
                        val webMatchesLanguage = Fmt.locale.language == PRIVACY_POLICY_WEB_LANGUAGE
                        if (!online || !webMatchesLanguage || runCatching { uriHandler.openUri(PRIVACY_POLICY_URL) }.isFailure) {
                            onOpenPrivacyPolicy()
                        }
                    },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHighest)
                SettingRow(
                    icon = AppIcons.Logo,
                    title = stringResource(R.string.about_licenses_title),
                    subtitle = stringResource(R.string.about_licenses_text),
                    onClick = onOpenLicenses,
                )
            }
        }
    }

    if (editing) {
        ProfileDialog(
            initial = profile,
            onSubmit = {
                vm.updateProfile(it)
                editing = false
            },
            onDismiss = { editing = false },
        )
    }
}

/** "1 scale · 2 bands or watches", or what the page holds while nothing is set up yet. */
@Composable
private fun devicesSummary(): String {
    val container = (LocalContext.current.applicationContext as GlobalFitnessApp).container
    val scales by container.settings.configuredScales.collectAsStateWithLifecycle()
    val wearables by container.wearables.summaries.collectAsStateWithLifecycle()
    val parts = listOfNotNull(
        scales.size.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.profile_devices_scales, it, it) },
        wearables.size.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.profile_devices_wearables, it, it) },
    )
    return if (parts.isEmpty()) stringResource(R.string.profile_devices_none) else parts.joinToString(" · ")
}

@Composable
private fun SettingRow(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    onClick: (() -> Unit)?,
    iconTint: Color = MaterialTheme.colorScheme.primary,
) {
    val modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { text -> @Composable { Text(text) } },
        leadingContent = { Icon(icon, contentDescription = null, tint = iconTint) },
        trailingContent = if (onClick != null) {
            { Icon(AppIcons.ChevronRight, contentDescription = null) }
        } else {
            null
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = modifier,
    )
}
