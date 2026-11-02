package com.enjoyyourtime.globalfitness.health

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import com.enjoyyourtime.globalfitness.data.Repository
import com.enjoyyourtime.globalfitness.food.FoodModule
import com.enjoyyourtime.globalfitness.scale.ScaleRegistry
import com.enjoyyourtime.globalfitness.training.TrainingModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Health Connect wiring for the app container. Registered as activity lifecycle callbacks so
 * every resume refreshes permissions and runs a throttled sync (at most every 5 minutes).
 */
class HealthModule(
    context: Context,
    repository: Repository,
    scales: ScaleRegistry,
    private val scope: CoroutineScope,
    training: TrainingModule,
    food: FoodModule,
) : Application.ActivityLifecycleCallbacks {

    val cache = HealthCache(context)
    val settings = HealthSettings(context)
    val gateway = HealthConnectGateway(context)
    val sync = HealthSync(gateway, cache, settings, scope, ownPackage = context.packageName)
    val exporter = ScaleExporter(gateway, cache, settings, repository, scales, scope, ownPackage = context.packageName)
    val workoutExporter = WorkoutExporter(
        gateway = gateway,
        cache = cache,
        repository = training.repository,
        settings = training.settings,
        scope = scope,
        ownPackage = context.packageName,
        genericTitle = { type -> context.getString(WorkoutExporter.genericTitleRes(type)) },
        exerciseName = { it.resolve(context) },
    )

    /** The meals of the food log as nutrition records (write only). */
    val foodExporter = FoodExporter(gateway, food.repository, food.settings, scope, installId = { food.installId })

    init {
        exporter.start()
        workoutExporter.start()
        foodExporter.start()
    }

    /** Refreshes availability / permissions, then syncs if the last attempt is old enough. */
    fun onForeground() {
        scope.launch(Dispatchers.IO) {
            gateway.refresh()
            sync.syncIfStale()
            workoutExporter.onForeground()
            foodExporter.onForeground()
        }
    }

    override fun onActivityResumed(activity: Activity) = onForeground()

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

    override fun onActivityStarted(activity: Activity) = Unit

    override fun onActivityPaused(activity: Activity) = Unit

    override fun onActivityStopped(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) = Unit
}
