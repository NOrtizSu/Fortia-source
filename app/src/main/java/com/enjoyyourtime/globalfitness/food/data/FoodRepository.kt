package com.enjoyyourtime.globalfitness.food.data

import com.enjoyyourtime.globalfitness.food.barcode.BarcodeReader
import com.enjoyyourtime.globalfitness.food.model.CustomFood
import com.enjoyyourtime.globalfitness.food.model.FoodEntry
import com.enjoyyourtime.globalfitness.training.data.ChangeOrigin
import com.enjoyyourtime.globalfitness.training.model.Tombstone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Single source of truth of the food log, like the training repository: loaded into memory at start,
 * writes go to the [FoodStore] on [io] (serialized by a mutex) and then update the StateFlows. Every
 * write bumps `updatedAt` past the version it replaced (deletions too), so the cloud backup can
 * decide by last writer wins.
 */
class FoodRepository(
    private val store: FoodStore,
    scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val mutex = Mutex()

    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    private val _loadError = MutableStateFlow<String?>(null)
    val loadError: StateFlow<String?> = _loadError.asStateFlow()

    private val _entries = MutableStateFlow<List<FoodEntry>>(emptyList())

    /** Every entry, oldest first (by the time it was eaten). */
    val entries: StateFlow<List<FoodEntry>> = _entries.asStateFlow()

    private val _customFoods = MutableStateFlow<List<CustomFood>>(emptyList())

    /** The user's own foods and the products found by barcode. */
    val customFoods: StateFlow<List<CustomFood>> = _customFoods.asStateFlow()

    private val _changes = MutableSharedFlow<ChangeOrigin>(extraBufferCapacity = 16)

    /** Emitted after every successful write. */
    val changes: SharedFlow<ChangeOrigin> = _changes.asSharedFlow()

    init {
        scope.launch {
            mutex.withLock {
                try {
                    val (entries, foods) = withContext(io) { store.loadEntries() to store.loadCustomFoods() }
                    _entries.value = sorted(entries)
                    _customFoods.value = foods
                    _loaded.value = true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _loadError.value = e.message ?: e.javaClass.simpleName
                }
            }
        }
    }

    /** Waits for food.db; throws at once (and for every write) when it could not be opened. */
    suspend fun awaitLoaded() {
        combine(loaded, loadError) { done, error -> done || error != null }.first { it }
        loadError.value?.let { throw IllegalStateException("Could not open the food database: $it") }
    }

    private val photoUsers = CopyOnWriteArrayList<(String) -> Boolean>()

    /**
     * Registers what else still needs meal photos no entry uses (the screens: an "Undo" offered, the
     * photo being logged, a save in flight). Close the result to unregister. [inUse] is called from
     * any thread.
     */
    fun registerPhotoUser(inUse: (String) -> Boolean): AutoCloseable {
        photoUsers += inUse
        return AutoCloseable { photoUsers -= inUse }
    }

    /** An entry uses the photo file [name], or a registered user still needs it: it must not be deleted. */
    fun photoInUse(name: String): Boolean = _entries.value.any { it.photo == name } || photoUsers.any { it(name) }

    private suspend fun <T> write(origin: ChangeOrigin = ChangeOrigin.LOCAL, block: suspend () -> T): T {
        awaitLoaded()
        val result = mutex.withLock { block() }
        _changes.tryEmit(origin)
        return result
    }

    fun entry(id: String): FoodEntry? = _entries.value.firstOrNull { it.id == id }

    fun customFood(id: String): CustomFood? = _customFoods.value.firstOrNull { it.id == id }

    /** The cached product of [barcode] (the most recently updated one if there are several), whatever its GTIN form. */
    fun customFoodByBarcode(barcode: String): CustomFood? {
        val code = BarcodeReader.canonical(barcode)
        return _customFoods.value.filter { it.barcode != null && BarcodeReader.canonical(it.barcode) == code }.maxByOrNull { it.updatedAt }
    }

    // ---- entries ----------------------------------------------------------------------------

    /** Stores a new entry ([draft]'s id, createdAt and updatedAt are assigned here). */
    suspend fun addEntry(draft: FoodEntry): FoodEntry = write {
        val now = clock()
        val entry = draft.copy(id = newId(), createdAt = now, updatedAt = now, name = draft.name.trim())
        withContext(io) { store.upsertEntry(entry) }
        _entries.value = sorted(_entries.value + entry)
        entry
    }

    /** Applies [transform] and stores the result with a new updatedAt; null when the entry no longer exists. */
    suspend fun updateEntry(id: String, transform: (FoodEntry) -> FoodEntry): FoodEntry? = write {
        val current = _entries.value.firstOrNull { it.id == id } ?: return@write null
        val changed = transform(current)
        if (changed == current) return@write current
        val stored = changed.copy(id = id, name = changed.name.trim(), createdAt = current.createdAt, updatedAt = maxOf(clock(), current.updatedAt + 1))
        withContext(io) { store.upsertEntry(stored) }
        _entries.value = sorted(_entries.value.map { if (it.id == id) stored else it })
        stored
    }

    /** Deletes the entry; returns it (for "Undo") or null when it was already gone. */
    suspend fun deleteEntry(id: String): FoodEntry? = write {
        val current = _entries.value.firstOrNull { it.id == id } ?: return@write null
        withContext(io) { store.deleteEntry(id, deletedAt(current.updatedAt)) }
        _entries.value = _entries.value.filterNot { it.id == id }
        current
    }

    /** "Undo" of [deleteEntry]: the same entry comes back, newer than its tombstone. */
    suspend fun restoreEntry(entry: FoodEntry): FoodEntry = write {
        val stored = entry.copy(updatedAt = maxOf(clock(), entry.updatedAt + 2))
        withContext(io) { store.upsertEntry(stored) }
        _entries.value = sorted(_entries.value.filterNot { it.id == entry.id } + stored)
        stored
    }

    // ---- custom foods -----------------------------------------------------------------------

    /** Stores a new custom food (or cached product); id and times assigned here. */
    suspend fun addCustomFood(draft: CustomFood): CustomFood = write {
        val now = clock()
        val food = draft.copy(id = newId(), name = draft.name.trim(), createdAt = now, updatedAt = now)
        withContext(io) { store.upsertCustomFood(food) }
        _customFoods.value = _customFoods.value + food
        food
    }

    /**
     * Applies [transform] and stores the result with a new updatedAt, unless only its local check time
     * changed (a product asked again that is still the same: no new version, nothing to back up).
     */
    suspend fun updateCustomFood(id: String, transform: (CustomFood) -> CustomFood): CustomFood? = write {
        val current = _customFoods.value.firstOrNull { it.id == id } ?: return@write null
        val changed = transform(current).let { it.copy(name = it.name.trim()) }
        if (changed == current) return@write current
        val newVersion = changed.copy(checkedAt = current.checkedAt) != current
        val updatedAt = if (newVersion) maxOf(clock(), current.updatedAt + 1) else current.updatedAt
        val stored = changed.copy(id = id, createdAt = current.createdAt, updatedAt = updatedAt)
        withContext(io) { store.upsertCustomFood(stored) }
        _customFoods.value = _customFoods.value.map { if (it.id == id) stored else it }
        stored
    }

    suspend fun deleteCustomFood(id: String) = write {
        val current = _customFoods.value.firstOrNull { it.id == id } ?: return@write
        withContext(io) { store.deleteCustomFood(id, deletedAt(current.updatedAt)) }
        _customFoods.value = _customFoods.value.filterNot { it.id == id }
    }

    /** Never older than the deleted version (see the training repository): the backup never brings it back. */
    private fun deletedAt(updatedAt: Long): Long = maxOf(clock(), updatedAt + 1)

    // ---- Health Connect ledger --------------------------------------------------------------

    suspend fun exportLedger(): Map<String, ExportLedgerEntry> = withContext(io) { store.loadExportLedger() }

    suspend fun putExportLedger(entries: Collection<ExportLedgerEntry>) = withContext(io) { store.putExportLedger(entries) }

    suspend fun removeExportLedger(ids: Collection<String>) = withContext(io) { store.removeExportLedger(ids) }

    // ---- cloud backup -----------------------------------------------------------------------

    suspend fun tombstones(): List<Tombstone> {
        awaitLoaded()
        return mutex.withLock { withContext(io) { store.loadTombstones() } }
    }

    /**
     * Entries, foods and tombstones read together under the write lock: an item deleted between
     * separate reads would be neither in the list nor among the tombstones, and the cloud would bring it back.
     */
    suspend fun snapshot(): FoodSnapshot {
        awaitLoaded()
        return mutex.withLock {
            val tombstones = withContext(io) { store.loadTombstones() }
            FoodSnapshot(_entries.value, _customFoods.value, tombstones)
        }
    }

    suspend fun dropTombstones(tombstones: Collection<Tombstone>) {
        if (tombstones.isEmpty()) return
        awaitLoaded()
        mutex.withLock { withContext(io) { store.dropTombstones(tombstones) } }
    }

    /**
     * Applies cloud changes read a while ago: items whose local copy changed since [seen] are left out
     * (checked under the write lock, so an edit made during the sync is never overwritten). An entry
     * from the cloud keeps this phone's photo (photos are never uploaded). Returns what was applied
     * and the photo files of the removed entries that no remaining entry uses.
     */
    suspend fun applyRemoteIfUnchanged(batch: FoodBatch, seen: FoodVersions): Pair<FoodBatch, List<String>> {
        if (batch.isEmpty) return batch to emptyList()
        return write(ChangeOrigin.CLOUD) {
            val entries = _entries.value.associateBy { it.id }
            val foods = _customFoods.value.associateBy { it.id }
            val safe = FoodBatch(
                upsertEntries = batch.upsertEntries.filter { entries[it.id]?.updatedAt == seen.entries[it.id] }
                    .map { e -> e.copy(photo = entries[e.id]?.photo) },
                removeEntries = batch.removeEntries.filter { entries[it]?.updatedAt == seen.entries[it] },
                // A product from the cloud keeps when this phone last asked Open Food Facts about it.
                upsertCustomFoods = batch.upsertCustomFoods.filter { foods[it.id]?.updatedAt == seen.customFoods[it.id] }
                    .map { f -> f.copy(checkedAt = foods[f.id]?.checkedAt) },
                removeCustomFoods = batch.removeCustomFoods.filter { foods[it]?.updatedAt == seen.customFoods[it] },
            )
            if (safe.isEmpty) return@write safe to emptyList()
            withContext(io) { store.applyBatch(safe) }
            val removed = safe.removeEntries.toSet()
            val upserted = safe.upsertEntries.associateBy { it.id }
            _entries.value = sorted(_entries.value.filterNot { it.id in removed || it.id in upserted } + upserted.values.filterNot { it.id in removed })
            // The foods of one plate share its photo: only a photo nothing else needs goes.
            val photos = safe.removeEntries.mapNotNull { entries[it]?.photo }.distinct().filterNot(::photoInUse)
            val removedFoods = safe.removeCustomFoods.toSet()
            val upsertedFoods = safe.upsertCustomFoods.associateBy { it.id }
            _customFoods.value = _customFoods.value.filterNot { it.id in removedFoods || it.id in upsertedFoods } +
                upsertedFoods.values.filterNot { it.id in removedFoods }
            safe to photos
        }
    }

    private fun sorted(list: List<FoodEntry>) = list.sortedWith(compareBy({ it.eatenAt }, { it.id }))
}
