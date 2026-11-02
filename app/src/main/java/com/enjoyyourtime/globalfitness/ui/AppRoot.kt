package com.enjoyyourtime.globalfitness.ui

import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.enjoyyourtime.globalfitness.R
import com.enjoyyourtime.globalfitness.data.Sex
import com.enjoyyourtime.globalfitness.domain.Metric
import com.enjoyyourtime.globalfitness.ui.account.AccountScreen
import com.enjoyyourtime.globalfitness.ui.account.AccountViewModel
import com.enjoyyourtime.globalfitness.ui.achievements.CelebrationHost
import com.enjoyyourtime.globalfitness.ui.ads.AdBanner
import com.enjoyyourtime.globalfitness.ui.wearables.WearableActivity
import com.enjoyyourtime.globalfitness.ui.body.BodyScreen
import com.enjoyyourtime.globalfitness.ui.body.ChartRange
import com.enjoyyourtime.globalfitness.ui.body.ManualEntryDialog
import com.enjoyyourtime.globalfitness.ui.components.LocalAppSnackbar
import com.enjoyyourtime.globalfitness.ui.devices.DevicesScreen
import com.enjoyyourtime.globalfitness.ui.food.FoodHost
import com.enjoyyourtime.globalfitness.ui.food.FoodRoutes
import com.enjoyyourtime.globalfitness.ui.food.FoodScreen
import com.enjoyyourtime.globalfitness.ui.food.foodGraph
import com.enjoyyourtime.globalfitness.ui.food.foodViewModel
import com.enjoyyourtime.globalfitness.ui.health.ActivityScreen
import com.enjoyyourtime.globalfitness.ui.health.HealthViewModel
import com.enjoyyourtime.globalfitness.ui.navigation.LocalSnackbarClearance
import com.enjoyyourtime.globalfitness.ui.navigation.Routes
import com.enjoyyourtime.globalfitness.ui.navigation.SnackbarClearance
import com.enjoyyourtime.globalfitness.ui.navigation.TopLevel
import com.enjoyyourtime.globalfitness.ui.navigation.openSubPage
import com.enjoyyourtime.globalfitness.ui.navigation.subPage
import com.enjoyyourtime.globalfitness.ui.onboarding.OnboardingFlow
import com.enjoyyourtime.globalfitness.ui.premium.RemoveAdsScreen
import com.enjoyyourtime.globalfitness.ui.progress.MusclesPage
import com.enjoyyourtime.globalfitness.ui.progress.MusclesViewModel
import com.enjoyyourtime.globalfitness.ui.progress.ProgressScreen
import com.enjoyyourtime.globalfitness.ui.progress.ProgressTab
import com.enjoyyourtime.globalfitness.ui.progress.rememberProgressLists
import com.enjoyyourtime.globalfitness.ui.profile.OpenSourceScreen
import com.enjoyyourtime.globalfitness.ui.profile.PrivacyPolicyScreen
import com.enjoyyourtime.globalfitness.ui.profile.ProfileScreen
import com.enjoyyourtime.globalfitness.ui.theme.AppMotion
import com.enjoyyourtime.globalfitness.ui.today.TodayScreen
import com.enjoyyourtime.globalfitness.ui.today.TodayStatus
import com.enjoyyourtime.globalfitness.ui.training.TrainingRoutes
import com.enjoyyourtime.globalfitness.ui.training.TrainingScreen
import com.enjoyyourtime.globalfitness.ui.training.music.SpotifyCoversQuestion
import com.enjoyyourtime.globalfitness.ui.training.trainingGraph
import kotlinx.coroutines.flow.StateFlow

/**
 * Single-activity root: loading → onboarding (first launch) → the five sections. [workoutToOpen]: a
 * workout the notification asked to open, opened once the sections show ([onWorkoutOpened]).
 */
@Composable
fun AppRoot(workoutToOpen: StateFlow<String?>, onWorkoutOpened: () -> Unit) {
    // Activity-scoped: shared by every section.
    val vm: MainViewModel = viewModel(factory = MainViewModel.Factory)
    val healthVm: HealthViewModel = viewModel(factory = HealthViewModel.Factory)
    val state by vm.uiState.collectAsStateWithLifecycle()
    val loadError by vm.loadError.collectAsStateWithLifecycle()
    val rationaleSeen by vm.permissionRationaleSeen.collectAsStateWithLifecycle()

    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val access = rememberBleAccess(
        rationaleSeen = rationaleSeen,
        onRationaleSeen = vm::markPermissionRationaleSeen,
        onChanged = vm::refreshScanner,
    )
    val healthPermissions = rememberLauncherForActivityResult(healthVm.permissionContract) { healthVm.onPermissionsResult() }
    val connectHealth = { healthPermissions.launch(healthVm.requestedPermissions()) }

    LaunchedEffect(Unit) {
        vm.events.collect { event ->
            when (event) {
                is UiEvent.Message -> {
                    if (event.haptic) haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                    snackbar.currentSnackbarData?.dismiss()
                    snackbar.showSnackbar(event.text.resolve(context), withDismissAction = true, duration = SnackbarDuration.Short)
                }
            }
        }
    }

    if (!state.loaded) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            val error = loadError
            if (error == null) {
                CircularProgressIndicator()
            } else {
                Text(
                    stringResource(R.string.error_database_open, error),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        return
    }

    // Decided once the data is loaded: a first launch walks through the onboarding to the end.
    var onboarding by rememberSaveable { mutableStateOf(state.profile == null) }
    if (onboarding || state.profile == null) {
        val accountVm: AccountViewModel = viewModel(factory = AccountViewModel.Factory)
        OnboardingFlow(
            vm = vm,
            accountVm = accountVm,
            healthVm = healthVm,
            access = access,
            snackbar = snackbar,
            onConnectHealth = connectHealth,
            onFinished = { onboarding = false },
        )
        return
    }
    // Only now may the ad consent form appear (it never covers the onboarding).
    LaunchedEffect(Unit) { vm.markOnboardingCompleted() }

    // Sub-pages report their own bottom bar here so app-wide messages appear above it.
    val snackbarClearance = remember { SnackbarClearance() }
    CompositionLocalProvider(LocalAppSnackbar provides snackbar, LocalSnackbarClearance provides snackbarClearance) {
        MainScaffold(vm, healthVm, access, snackbar, connectHealth, workoutToOpen, onWorkoutOpened)
    }
}

@Composable
private fun MainScaffold(
    vm: MainViewModel,
    healthVm: HealthViewModel,
    access: BleAccess,
    snackbar: SnackbarHostState,
    connectHealth: () -> Unit,
    workoutToOpen: StateFlow<String?>,
    onWorkoutOpened: () -> Unit,
) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val section = TopLevel.of(route)
    val context = LocalContext.current
    val activity = LocalActivity.current

    // Activity scoped: Hoy's weekly card and Progreso → Músculos share the statistics.
    val musclesVm: MusclesViewModel = viewModel(factory = MusclesViewModel.Factory)
    var bodyMetric by rememberSaveable { mutableStateOf(Metric.WEIGHT) }
    var bodyRange by rememberSaveable { mutableStateOf(ChartRange.M3) }
    var showManual by rememberSaveable { mutableStateOf(false) }
    // Comida's "Add" button lives in the scaffold (the app-wide snackbar moves it up) and opens the tab's sheet.
    var addFood by rememberSaveable { mutableStateOf(false) }
    // Hoisted so Hoy can open Progreso on a given tab (a metric → Cuerpo, "View activity" → Actividad).
    var progressTab by rememberSaveable { mutableStateOf(ProgressTab.MUSCLES) }
    val progressLists = rememberProgressLists()
    // "Añadir peso manual" shrinks to its icon once the body page leaves the top (it would cover the chart and rows).
    val manualFabExpanded by remember { derivedStateOf { progressLists.body.firstVisibleItemIndex == 0 } }
    val openProgress = { tab: ProgressTab ->
        progressTab = tab
        navController.navigateToSection(TopLevel.PROGRESS)
    }
    val openRoute = { route: String -> navController.openSubPage(route) }

    // Interstitial opportunity on every section change (not on the first one, not on sub-pages).
    var lastSection by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(section) {
        val current = section?.route ?: return@LaunchedEffect
        val previous = lastSection
        lastSection = current
        if (previous != null && previous != current) activity?.let(vm::onSectionChanged)
    }

    // The workout notification's tap: over Hoy at a cold start, over whatever page shows otherwise.
    val workoutRequest by workoutToOpen.collectAsStateWithLifecycle()
    LaunchedEffect(workoutRequest) {
        val id = workoutRequest ?: return@LaunchedEffect
        onWorkoutOpened()
        navController.openWorkout(id)
    }

    val openWearable: (String) -> Unit = { id -> context.startActivity(WearableActivity.intent(context, id)) }
    // The health view model outlives the screens: refresh "today" (and band.db) on every resume.
    val foodVm = foodViewModel()
    LifecycleResumeEffect(Unit) {
        healthVm.onResume()
        // Comida and Hoy's card follow midnight and a change of language.
        foodVm.onResume()
        onPauseOrDispose { }
    }

    // The celebration of new achievement levels covers every page (sub-pages too), under dialogs.
    CelebrationHost(onOpenAchievements = { navController.openSubPage(TrainingRoutes.ACHIEVEMENTS) }) {
        Scaffold(
            contentWindowInsets = WindowInsets(0),
            bottomBar = {
                AnimatedVisibility(visible = route == null || section != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                    Column {
                        AdBanner()
                        NavigationBar {
                            TopLevel.entries.forEach { item ->
                                val selected = item == section
                                NavigationBarItem(
                                    selected = selected,
                                    onClick = { navController.navigateToSection(item) },
                                    icon = { Icon(if (selected) item.selectedIcon else item.icon, contentDescription = null) },
                                    label = { NavLabel(stringResource(item.label)) },
                                )
                            }
                        }
                    }
                }
            },
            floatingActionButton = {
                if (section == TopLevel.PROGRESS && progressTab == ProgressTab.BODY) {
                    ExtendedFloatingActionButton(
                        onClick = { showManual = true },
                        expanded = manualFabExpanded,
                        // Named for TalkBack while only the icon shows.
                        icon = { Icon(Icons.Filled.Add, contentDescription = if (manualFabExpanded) null else stringResource(R.string.action_add_manual_weight)) },
                        text = { Text(stringResource(R.string.action_add_manual_weight)) },
                    )
                } else if (section == TopLevel.FOOD) {
                    ExtendedFloatingActionButton(
                        onClick = { addFood = true },
                        icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                        text = { Text(stringResource(R.string.foodlog_add)) },
                    )
                }
            },
            snackbarHost = {
                // Sub-pages have no bottom bar: app-wide messages (a weighing saved during a workout) stay
                // above the system navigation bar, the keyboard and the page's own bottom bar.
                val onSubPage = route != null && section == null
                val insets = WindowInsets.navigationBars.union(WindowInsets.ime).asPaddingValues().calculateBottomPadding()
                val clearance = LocalSnackbarClearance.current.bottom
                SnackbarHost(snackbar, Modifier.padding(bottom = if (onSubPage) maxOf(insets, clearance) else 0.dp))
            },
        ) { padding ->
            NavHost(
                navController = navController,
                startDestination = TopLevel.TODAY.route,
                modifier = Modifier.padding(padding).consumeWindowInsets(padding),
                enterTransition = { fadeIn(AppMotion.effects()) },
                exitTransition = { fadeOut(AppMotion.effects()) },
            ) {
                composable(TopLevel.TODAY.route) {
                    val live by vm.live.collectAsStateWithLifecycle()
                    val sessions by vm.sessions.collectAsStateWithLifecycle()
                    val scanStatus by vm.scanStatus.collectAsStateWithLifecycle()
                    val health by healthVm.uiState.collectAsStateWithLifecycle()
                    val dashboard by healthVm.dashboard.collectAsStateWithLifecycle()
                    val user by vm.accountUser.collectAsStateWithLifecycle()
                    val cloud by vm.cloudStatus.collectAsStateWithLifecycle()
                    val wearable by vm.wearable.collectAsStateWithLifecycle()
                    val suggestion by vm.scaleSuggestion.collectAsStateWithLifecycle()
                    val state by vm.uiState.collectAsStateWithLifecycle()
                    val muscles by musclesVm.state.collectAsStateWithLifecycle()
                    val food by foodViewModel().today.collectAsStateWithLifecycle()
                    TodayScreen(
                        state = state,
                        status = TodayStatus(scanStatus, live, sessions, health, dashboard, user, cloud, wearable, suggestion),
                        muscles = muscles,
                        food = food,
                        onOpenFood = { navController.navigateToSection(TopLevel.FOOD) },
                        access = access,
                        onRetryScan = vm::refreshScanner,
                        onConnectHealth = connectHealth,
                        onInstallHealth = { healthVm.openPlayStore() },
                        onOpenMetric = { metric ->
                            bodyMetric = metric
                            openProgress(ProgressTab.BODY)
                        },
                        onAddWeight = { showManual = true },
                        onOpenAccount = { navController.openSubPage(Routes.ACCOUNT) },
                        onOpenDevices = { navController.openSubPage(Routes.DEVICES) },
                        onOpenActivity = { openProgress(ProgressTab.ACTIVITY) },
                        onOpenBand = { wearable?.let { openWearable(it.device.id) } ?: navController.openSubPage(Routes.DEVICES) },
                        onOpenTraining = { navController.navigateToSection(TopLevel.TRAINING) },
                        onOpenMuscles = { openProgress(ProgressTab.MUSCLES) },
                        onOpenSubPage = openRoute,
                        onAcceptSuggestion = vm::acceptScaleSuggestion,
                        onDeclineSuggestion = vm::declineScaleSuggestion,
                    )
                }
                composable(TopLevel.TRAINING.route) {
                    TrainingScreen(onOpen = openRoute)
                }
                composable(TopLevel.FOOD.route) {
                    FoodScreen(onOpen = openRoute, adding = addFood, onAdding = { addFood = it })
                }
                composable(TopLevel.PROGRESS.route) {
                    val state by vm.uiState.collectAsStateWithLifecycle()
                    ProgressScreen(
                        tab = progressTab,
                        onTab = { tab ->
                            // Cuerpo and Actividad were sections: switching tabs stays an interstitial
                            // opportunity (the policy's frequency cap applies as for any section change).
                            if (tab != progressTab) activity?.let(vm::onSectionChanged)
                            progressTab = tab
                        },
                        lists = progressLists,
                        muscles = {
                            MusclesPage(
                                vm = musclesVm,
                                female = state.profile?.sex == Sex.FEMALE,
                                listState = progressLists.muscles,
                                onOpenTraining = { navController.navigateToSection(TopLevel.TRAINING) },
                                onOpen = openRoute,
                            )
                        },
                        body = {
                            BodyScreen(
                                state = state,
                                metric = bodyMetric,
                                onMetric = { bodyMetric = it },
                                range = bodyRange,
                                onRange = { bodyRange = it },
                                onDelete = vm::deleteMeasurement,
                                listState = progressLists.body,
                            )
                        },
                        activity = { ActivityScreen(healthVm, onConnect = connectHealth, listState = progressLists.activity) },
                    )
                }
                composable(TopLevel.PROFILE.route) {
                    ProfileScreen(
                        vm = vm,
                        healthVm = healthVm,
                        onConnectHealth = connectHealth,
                        onOpenDevices = { navController.openSubPage(Routes.DEVICES) },
                        onOpenTrainingSettings = { navController.openSubPage(TrainingRoutes.SETTINGS) },
                        onOpenFoodGoal = { navController.openSubPage(FoodRoutes.GOAL) },
                        onOpenAchievements = { navController.openSubPage(TrainingRoutes.ACHIEVEMENTS) },
                        onOpenAccount = { navController.openSubPage(Routes.ACCOUNT) },
                        onOpenRemoveAds = { navController.openSubPage(Routes.REMOVE_ADS) },
                        onOpenPrivacyPolicy = { navController.openSubPage(Routes.PRIVACY_POLICY) },
                        onOpenLicenses = { navController.openSubPage(Routes.LICENSES) },
                    )
                }
                subPage(Routes.ACCOUNT, navController) { back -> AccountScreen(onBack = back) }
                subPage(Routes.REMOVE_ADS, navController) { back -> RemoveAdsScreen(onBack = back) }
                subPage(Routes.PRIVACY_POLICY, navController) { back -> PrivacyPolicyScreen(onBack = back) }
                subPage(Routes.LICENSES, navController) { back -> OpenSourceScreen(onBack = back) }
                subPage(Routes.DEVICES, navController) { back ->
                    DevicesScreen(vm, healthVm, onConnectHealth = connectHealth, onOpenWearable = openWearable, onBack = back)
                }
                trainingGraph(navController)
                foodGraph(navController)
            }
        }
    }

    // Once per install, after the start's ad consent: may the music cards load Spotify's covers?
    SpotifyCoversQuestion(onMainSection = section != null)
    // After the first meal saved with Health Connect set up: may Fortia write nutrition? (never blocks the save)
    // Also the message when a change to the food log could not be saved.
    FoodHost()
    if (showManual) {
        ManualEntryDialog(
            onSave = { weight, timestamp ->
                vm.addManual(weight, timestamp)
                showManual = false
            },
            onDismiss = { showManual = false },
        )
    }
}

/**
 * A bottom bar label on one line: five sections leave about 62 dp each at 360 dp, so long words
 * ("Fortschritt", "Тренировки", "الملف الشخصي") shrink a little instead of being cut. The bar
 * measures its labels without a width limit, so the item's share of the window is the limit.
 */
@Composable
private fun NavLabel(text: String) {
    val window = LocalWindowInfo.current.containerSize.width
    val itemWidth = with(LocalDensity.current) { ((window.toDp() - NAV_BAR_INSETS) / TopLevel.entries.size).coerceAtLeast(40.dp) }
    BasicText(
        text,
        modifier = Modifier.widthIn(max = itemWidth),
        style = LocalTextStyle.current.copy(color = LocalContentColor.current),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        autoSize = TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = LocalTextStyle.current.fontSize.takeIf { it.isSpecified } ?: 12.sp, stepSize = 0.5.sp),
    )
}

/** The bar's side padding and the gaps between its items (Material 3: 8 dp each). */
private val NAV_BAR_INSETS = 48.dp

/** The workout's page: back to it when it is already open under other pages, else opened on top (never twice). */
private fun NavHostController.openWorkout(id: String) {
    val route = TrainingRoutes.workout(id)
    if (!popBackStack(route, inclusive = false)) openSubPage(route)
}

/** Bottom-bar navigation: one copy of each section, state saved and restored when switching. */
private fun NavHostController.navigateToSection(section: TopLevel) {
    navigate(section.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
