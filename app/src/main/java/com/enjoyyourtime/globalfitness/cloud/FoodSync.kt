package com.enjoyyourtime.globalfitness.cloud

import android.util.Log
import com.enjoyyourtime.globalfitness.food.data.FoodBatch
import com.enjoyyourtime.globalfitness.food.data.FoodRepository
import com.enjoyyourtime.globalfitness.food.data.FoodVersions
import com.enjoyyourtime.globalfitness.training.data.ChangeOrigin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What the cloud backup needs from the food log: its repository and a way to delete meal photos (only
 * photos [FoodRepository.photoInUse] says nothing needs are passed).
 */
class FoodBackupSource(val repository: FoodRepository, val deletePhotos: (List<String>) -> Unit)

/**
 * The food log's part of a cloud sync, run by [CloudSyncEngine] after the training part under the
 * same lock, retries and status, following the same dataset decision ([TrainingMode]). One call:
 * read the phone and the cloud (incrementally when the [FoodBase] is trusted), plan with
 * [FoodMerge], apply on the phone in one transaction, upload, forget the confirmed tombstones,
 * save the base. Meal photos never go to the cloud; the photo of an entry deleted on another phone
 * is deleted here. Every step is idempotent: a sync that fails half-way is planned again.
 */
class FoodSync(
    private val repository: FoodRepository,
    private val deletePhotos: (List<String>) -> Unit,
    private val cloud: FoodCloud,
    private val bases: FoodBaseStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val compute: CoroutineDispatcher = Dispatchers.Default,
    private val loadTimeoutMs: Long = LOAD_TIMEOUT_MS,
) {
    /** Edits made on this phone (the sync's own writes arrive as [ChangeOrigin.CLOUD]). */
    val localChanges: Flow<Unit> = repository.changes.filter { it == ChangeOrigin.LOCAL }.map { }

    /** See [TrainingSync.prepareTransition]: a retry of the same transition keeps its original time. */
    suspend fun prepareTransition(uid: String, mode: TrainingMode, datasetId: String) {
        val previous = bases.loadFoodBase(uid)
        val since = previous?.pendingSince?.takeIf { previous.pending == mode && previous.datasetKey == datasetId } ?: clock()
        bases.saveFoodBase(uid, FoodBase(datasetKey = datasetId, pending = mode, pendingSince = since))
    }

    /** Syncs the food log of [datasetId]. Returns true when some writes are still queued offline. */
    suspend fun sync(uid: String, datasetId: String): Boolean {
        awaitLoaded()
        val stored = bases.loadFoodBase(uid)
        val mode = FoodMerge.modeFor(stored, datasetId)
        val trusted = mode == TrainingMode.MERGE && stored?.trustedFor(datasetId) == true
        val previous = stored.takeIf { trusted }
        val pendingSince = stored?.pendingSince.takeIf { mode != TrainingMode.MERGE }

        val local = repository.snapshot()
        val seen = FoodVersions(local.entries.associate { it.id to it.updatedAt }, local.customFoods.associate { it.id to it.updatedAt })
        val remote = cloud.fetchFood(uid, previous?.cursorsOrFull(trusted = true) ?: FoodCursors.FULL)
        val now = clock()
        val plan = withContext(compute) {
            FoodMerge.plan(mode, FoodLocal(local.entries, local.customFoods, local.tombstones), remote, previous, now, pendingSince)
        }

        val refused = HashSet<FoodDocKey>()
        val (applied, photos) = try {
            repository.applyRemoteIfUnchanged(plan.apply, seen)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "food.db refused the cloud batch; applying it item by item", e)
            applyOneByOne(plan.apply, seen, refused)
        }
        // Checked again now: the screens may still need a photo (an "Undo" offered, a meal being logged).
        val unused = photos.filterNot(repository::photoInUse)
        if (unused.isNotEmpty()) withContext(io) { deletePhotos(unused) }
        val skipped = HashSet<FoodDocKey>()
        fun leftOut(kind: FoodKind, requested: Collection<String>, done: Collection<String>) {
            val doneSet = done.toHashSet()
            for (id in requested) {
                val key = FoodDocKey(kind, id)
                if (id !in doneSet && key !in refused) skipped += key
            }
        }
        leftOut(FoodKind.ENTRY, plan.apply.upsertEntries.map { it.id } + plan.apply.removeEntries, applied.upsertEntries.map { it.id } + applied.removeEntries)
        leftOut(
            FoodKind.CUSTOM_FOOD,
            plan.apply.upsertCustomFoods.map { it.id } + plan.apply.removeCustomFoods,
            applied.upsertCustomFoods.map { it.id } + applied.removeCustomFoods,
        )
        // A refused document keeps what was agreed before (the cursor moves on: it is not read again and again).
        val overrides = refused.associateWith { previous?.entries?.get(it) }
        if (plan.dropNow.isNotEmpty()) repository.dropTombstones(plan.dropNow)

        val result = if (plan.writes.isEmpty()) FoodWriteResult.NOTHING else cloud.writeFood(uid, plan.writes)
        // Only now: a tombstone the server has not confirmed is uploaded again next time.
        val confirmedDeletions = plan.dropOnConfirm.filterKeys { it in result.confirmedDocs }.values
        if (confirmedDeletions.isNotEmpty()) repository.dropTombstones(confirmedDeletions)

        bases.saveFoodBase(uid, FoodMerge.nextBase(previous, datasetId, remote, plan, result, skipped, now, overrides))
        return !result.allConfirmed
    }

    /** Applies [batch] one item at a time; items food.db refuses go to [refused] (logged, never retried in a loop). */
    private suspend fun applyOneByOne(batch: FoodBatch, seen: FoodVersions, refused: MutableSet<FoodDocKey>): Pair<FoodBatch, List<String>> {
        val done = ArrayList<FoodBatch>()
        val photos = ArrayList<String>()
        suspend fun one(key: FoodDocKey, single: FoodBatch) {
            try {
                val (applied, unused) = repository.applyRemoteIfUnchanged(single, seen)
                done += applied
                photos += unused
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Skipped cloud ${key.kind.collection}/${key.id}: food.db refused it", e)
                refused += key
            }
        }
        for (f in batch.upsertCustomFoods) one(FoodDocKey(FoodKind.CUSTOM_FOOD, f.id), FoodBatch(upsertCustomFoods = listOf(f)))
        for (e in batch.upsertEntries) one(FoodDocKey(FoodKind.ENTRY, e.id), FoodBatch(upsertEntries = listOf(e)))
        for (id in batch.removeEntries) one(FoodDocKey(FoodKind.ENTRY, id), FoodBatch(removeEntries = listOf(id)))
        for (id in batch.removeCustomFoods) one(FoodDocKey(FoodKind.CUSTOM_FOOD, id), FoodBatch(removeCustomFoods = listOf(id)))
        val applied = FoodBatch(
            upsertEntries = done.flatMap { it.upsertEntries },
            removeEntries = done.flatMap { it.removeEntries },
            upsertCustomFoods = done.flatMap { it.upsertCustomFoods },
            removeCustomFoods = done.flatMap { it.removeCustomFoods },
        )
        return applied to photos.distinct()
    }

    /** Fails fast instead of waiting forever when food.db cannot be opened. */
    private suspend fun awaitLoaded() {
        val ready = withTimeoutOrNull(loadTimeoutMs) {
            combine(repository.loaded, repository.loadError) { loaded, error -> loaded || error != null }.first { it }
        }
        repository.loadError.value?.let { throw IllegalStateException("Could not open the food database: $it") }
        if (ready == null) throw IllegalStateException("The food database did not finish loading.")
    }

    private companion object {
        const val TAG = "FoodSync"
        const val LOAD_TIMEOUT_MS = 30_000L
    }
}
