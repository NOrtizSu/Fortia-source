package com.enjoyyourtime.globalfitness

import android.app.Application
import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.enjoyyourtime.globalfitness.account.AccountManager
import com.enjoyyourtime.globalfitness.ads.AdSuppression
import com.enjoyyourtime.globalfitness.ads.AdsManager
import com.enjoyyourtime.globalfitness.billing.BillingRepository
import com.enjoyyourtime.globalfitness.ble.LiveScale
import com.enjoyyourtime.globalfitness.ble.ScaleMonitor
import com.enjoyyourtime.globalfitness.ble.ScaleScanner
import com.enjoyyourtime.globalfitness.cloud.CloudSyncEngine
import com.enjoyyourtime.globalfitness.cloud.ExercisePhotoFiles
import com.enjoyyourtime.globalfitness.cloud.FoodBackupSource
import com.enjoyyourtime.globalfitness.cloud.RepositoryBackupAdapter
import com.enjoyyourtime.globalfitness.cloud.TrainingBackupSource
import com.enjoyyourtime.globalfitness.data.AppDatabase
import com.enjoyyourtime.globalfitness.data.Repository
import com.enjoyyourtime.globalfitness.data.SettingsStore
import com.enjoyyourtime.globalfitness.domain.MeasurementRecorder
import com.enjoyyourtime.globalfitness.food.FoodModule
import com.enjoyyourtime.globalfitness.health.HealthModule
import com.enjoyyourtime.globalfitness.review.ReviewPrompter
import com.enjoyyourtime.globalfitness.scale.SessionStatus
import com.enjoyyourtime.globalfitness.domain.RecordOutcome
import com.enjoyyourtime.globalfitness.scale.GattSession
import com.enjoyyourtime.globalfitness.scale.ScaleContext
import com.enjoyyourtime.globalfitness.scale.drivers.AllDrivers
import com.enjoyyourtime.globalfitness.training.TrainingModule
import com.enjoyyourtime.globalfitness.ui.Fmt
import com.enjoyyourtime.globalfitness.ui.training.notification.WorkoutNotifications
import com.enjoyyourtime.globalfitness.wearable.WearableManager
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Manual dependency container (no DI library). */
class AppContainer(context: Context) {
    private val crashGuard = CoroutineExceptionHandler { _, e ->
        Log.e("GlobalFitness", "Unhandled error in application scope", e)
    }
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + crashGuard)
    val settings = SettingsStore(context)
    val database = AppDatabase(context)
    val repository = Repository(database, appScope, preferredProfileId = { settings.selectedProfileId.value })
    val scanner = ScaleScanner(context, settings, appScope)

    /** Mirrors [WearableManager.linkActive] once the wearables exist (none can be connected before). */
    private val wearableLinkActive = MutableStateFlow(false)
    val recorder = MeasurementRecorder(repository)
    val scaleRegistry = AllDrivers.registry()
    val scaleMonitor = ScaleMonitor(
        advertisements = scanner.advertisements,
        registry = scaleRegistry,
        settings = settings,
        recorder = recorder,
        contextProvider = {
            val last = repository.measurements.value.lastOrNull()
            ScaleContext.from(repository.profile.value, last?.weightKg, last?.impedance)
        },
        linkFactory = { address -> GattSession(context.applicationContext, address) },
        scope = appScope,
        wallClock = System::currentTimeMillis,
        elapsedClock = SystemClock::elapsedRealtime,
    ).also { monitor ->
        // A running scan disturbs connection setup: paused while a scale or a wearable connects or is connected.
        appScope.launch { combine(monitor.gattConnecting, wearableLinkActive) { scale, wearable -> scale || wearable }.collect { scanner.setPaused(it) } }
    }

    /**
     * Experimental direct mode for bands and watches of every supported family (Xiaomi, Huami /
     * Zepp OS, standard BLE, PineTime, Bangle.js); it includes the original Smart Band 9 mode.
     */
    val wearables by lazy {
        WearableManager(context.applicationContext, appScope).also { manager ->
            // No interstitials while any wearable streams live heart rate.
            appScope.launch {
                manager.liveHeartRateActive.collect { adSuppression.set(AdSuppression.BAND_LIVE_HEART_RATE, it) }
            }
            appScope.launch { scanner.advertisements.collect { manager.onAdvertisement(it) } }
            appScope.launch { manager.linkActive.collect { wearableLinkActive.value = it } }
        }
    }

    /** Exercise and machine catalog, workouts and the muscle statistics' data. */
    val training = TrainingModule(context, appScope)

    /** The food log ("Comida"): meals, custom foods, catalog, recognizer, barcodes, daily goal. */
    val food = FoodModule(context, appScope)

    /** The ongoing notification of the workout in progress (posted again at every start of the process). */
    val workoutNotifications = WorkoutNotifications(context, training.repository, training.rest, training.settings, appScope).also { it.start() }
    val health = HealthModule(context, repository, scaleRegistry, appScope, training, food)
    // ---- Optional Google account + cloud backup, ads and the "Quitar anuncios" purchase --------
    val adSuppression = AdSuppression().also { suppression ->
        // No interstitials while a workout is in progress: the user is logging sets in the gym.
        appScope.launch {
            training.repository.activeWorkout.collect { suppression.set(AdSuppression.WORKOUT_IN_PROGRESS, it != null) }
        }
    }
    val billing = BillingRepository(context, appScope, adSuppression)
    val account = AccountManager(context)
    val cloudSync = CloudSyncEngine(
        context,
        appScope,
        account,
        RepositoryBackupAdapter(repository, settings),
        billing.isPremium,
        training = TrainingBackupSource(training.repository, ExercisePhotoFiles(training.photos)),
        food = FoodBackupSource(food.repository) { names -> names.forEach(food.photos::delete) },
    )
    val onboarding = OnboardingStore(context)
    val ads = AdsManager(
        application = context.applicationContext as Application,
        scope = appScope,
        premium = billing.isPremium,
        entitlementKnown = billing.entitlementKnown,
        suppression = adSuppression,
        measurementSaved = scaleMonitor.outcomes.filter { it !is RecordOutcome.Duplicate }.map { },
        // A passive weighing in progress or any GATT scale session that has not finished.
        scaleBusy = combine(scaleMonitor.live, scaleMonitor.sessions) { live, sessions ->
            live is LiveScale.Connecting || live is LiveScale.Measuring || live is LiveScale.Stabilized ||
                sessions.values.any { it.status in BUSY_SESSION_STATES }
        },
        onboardingCompleted = onboarding.completed,
    )

    /** Google Play's rating card at good moments. Created at startup: it notes the first day of use. */
    val reviews = ReviewPrompter(
        context = context,
        scope = appScope,
        suppression = adSuppression,
        interstitialShowing = { ads.interstitials.showing },
        workouts = training.repository.workouts,
        onboardingCompleted = onboarding.completed,
    )

    private companion object {
        val BUSY_SESSION_STATES = setOf(SessionStatus.CONNECTING, SessionStatus.CONNECTED, SessionStatus.MEASURING)
    }
}

class GlobalFitnessApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        Fmt.init(this)
        container = AppContainer(this)
        registerActivityLifecycleCallbacks(container.health)
    }
}
