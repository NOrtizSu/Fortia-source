package com.enjoyyourtime.globalfitness.health

import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.NutritionRecord
import com.enjoyyourtime.globalfitness.R
import com.enjoyyourtime.globalfitness.UiText
import com.enjoyyourtime.globalfitness.food.data.ExportLedgerEntry
import com.enjoyyourtime.globalfitness.food.data.FoodRepository
import com.enjoyyourtime.globalfitness.food.data.FoodSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class FoodExportState(
    /** Meals of this app in Health Connect after the last run. */
    val savedCount: Int = 0,
    val error: UiText? = null,
)

/**
 * Saves the meal entries of the food log to Health Connect, one `NutritionRecord` per entry
 * ([FoodExportPlanner] decides what), and deletes an entry's record when the entry is deleted (here
 * or on another phone, through the cloud backup). Runs after changes (debounced), when the permission
 * or the switch change and when the app comes to the foreground; after a failure it waits
 * ([WorkoutExporter.backoffMs]). Needs only the nutrition write permission (never reads nutrition).
 */
class FoodExporter(
    private val gateway: HealthConnectGateway,
    private val repository: FoodRepository,
    private val settings: FoodSettings,
    private val scope: CoroutineScope,
    /** This install's id (see [ExportLedgerEntry.device]). */
    private val installId: () -> String,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow(FoodExportState())
    val state: StateFlow<FoodExportState> = _state.asStateFlow()

    /** null until the switch is touched, which means on once the permission is granted. */
    val choice: StateFlow<Boolean?> get() = settings.healthExportChoice

    @Volatile
    private var failures = 0

    @Volatile
    private var retryAt = 0L

    fun start() {
        scope.launch {
            combine(repository.loaded, repository.entries, settings.healthExportChoice, gateway.granted) { loaded, _, _, granted ->
                loaded && HealthPermissions.canExportMeals(granted)
            }.collectLatest { ready ->
                if (!ready) return@collectLatest
                delay(DEBOUNCE_MS)
                run()
            }
        }
    }

    fun onForeground() {
        scope.launch { run() }
    }

    fun setEnabled(enabled: Boolean) = settings.setHealthExport(enabled)

    private suspend fun run() {
        if (clock() < retryAt) return
        try {
            reconcile()
            failures = 0
            retryAt = 0L
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Health Connect meal export failed", e)
            failures++
            retryAt = clock() + WorkoutExporter.backoffMs(failures, WorkoutExporter.isRateLimit(e))
            _state.update { it.copy(error = UiText.res(R.string.hc_export_failed, HealthSync.describe(e))) }
        }
    }

    private suspend fun reconcile() = withContext(io) {
        mutex.withLock {
            if (!repository.loaded.value) return@withLock
            if (!HealthPermissions.canExportMeals(gateway.granted.value)) {
                _state.update { it.copy(error = null) }
                return@withLock
            }
            val client = gateway.client() ?: return@withLock
            val device = installId()
            val ledger = repository.exportLedger().filterValues { it.device == device }
            val enabled = HealthSettings.exportEnabled(settings.healthExportChoice.value, canExport = true)
            val plan = FoodExportPlanner.plan(repository.entries.value, ledger, enabled)
            if (!plan.isEmpty) execute(client, plan, device)
            _state.value = FoodExportState(savedCount = plan.savedCount)
        }
    }

    private suspend fun execute(client: HealthConnectClient, plan: FoodExportPlan, device: String) {
        for (chunk in plan.deletes.chunked(DELETE_CHUNK)) {
            // Deleting a client id Health Connect does not have is a no-op.
            client.deleteRecords(NutritionRecord::class, emptyList(), chunk.map(FoodExportPlanner::clientRecordId))
            repository.removeExportLedger(chunk)
        }
        val now = clock()
        for (chunk in plan.writes.chunked(WRITE_CHUNK)) {
            val written = try {
                val ids = client.insertRecords(chunk.map(FoodExportPlanner::buildRecord)).recordIdsList
                chunk.mapIndexed { i, p -> ExportLedgerEntry(p.entryId, p.version, ids.getOrNull(i), now, device) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (WorkoutExporter.isTransient(e)) throw e
                // One record Health Connect refuses must not hold back the others: one by one, and a
                // refused one is noted as handled until its entry changes.
                Log.w(TAG, "Health Connect rejected ${chunk.size} meals at once, writing them one by one", e)
                chunk.map { p ->
                    val id = try {
                        client.insertRecords(listOf(FoodExportPlanner.buildRecord(p))).recordIdsList.firstOrNull()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (WorkoutExporter.isTransient(e)) throw e
                        Log.w(TAG, "Health Connect rejected meal ${p.entryId}; skipped until it changes", e)
                        null
                    }
                    ExportLedgerEntry(p.entryId, p.version, id, now, device)
                }
            }
            repository.putExportLedger(written)
        }
    }

    private companion object {
        const val TAG = "FoodExporter"
        const val DEBOUNCE_MS = 2_000L
        const val WRITE_CHUNK = 50
        const val DELETE_CHUNK = 500
    }
}
