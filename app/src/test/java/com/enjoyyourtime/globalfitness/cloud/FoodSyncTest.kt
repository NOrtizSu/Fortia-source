package com.enjoyyourtime.globalfitness.cloud

import com.enjoyyourtime.globalfitness.cloud.FirestoreCodec.Decoded
import com.enjoyyourtime.globalfitness.food.FakeFoodStore
import com.enjoyyourtime.globalfitness.food.data.FoodRepository
import com.enjoyyourtime.globalfitness.food.model.CustomFood
import com.enjoyyourtime.globalfitness.food.model.CustomFoodSource
import com.enjoyyourtime.globalfitness.food.model.FoodUnit
import com.enjoyyourtime.globalfitness.food.model.Nutrients
import com.enjoyyourtime.globalfitness.food.model.PortionKey
import com.enjoyyourtime.globalfitness.food.testEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The food log's backup round trip between phones sharing one fake cloud that stores what
 * [FoodCodec] writes (merged like Firestore) and decodes it back.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FoodSyncTest {
    private val uid = "user-1"
    private val dataset = "ds-1"
    private var now = 1_000_000L
    private var ids = 0
    private val scopes = mutableListOf<CoroutineScope>()

    @After
    fun cancelScopes() = scopes.forEach { it.cancel() }

    private class FakeCloud : FoodCloud {
        var serverTime = 1_700_000_000_000L
        val docs = HashMap<FoodKind, LinkedHashMap<String, Pair<Map<String, Any?>, Long>>>()
        var confirm = true

        override suspend fun fetchFood(uid: String, since: FoodCursors): FoodCloudSnapshot =
            FoodCloudSnapshot(read(FoodKind.ENTRY, since.entries, FoodCodec::entryFromMap), read(FoodKind.CUSTOM_FOOD, since.customFoods, FoodCodec::customFoodFromMap))

        private fun <T> read(kind: FoodKind, since: Long?, decode: (String, Map<String, Any?>, Long?) -> Decoded<T>?): RemoteDocs<T> {
            val raw = docs[kind].orEmpty()
                .filter { (_, e) -> since == null || e.second > since - FirestoreBackupRemote.PULL_OVERLAP_MS }
                .map { (id, e) -> TrainingCodec.RawDoc(id, e.first, e.second) }
            return FoodCodec.collect(raw, complete = since == null, decode = decode)
        }

        override suspend fun writeFood(uid: String, writes: List<FoodWrite>): FoodWriteResult {
            if (!confirm) return FoodWriteResult(emptySet(), allConfirmed = false)
            for (w in writes) {
                serverTime += 3_600_000L
                val collection = docs.getOrPut(w.key.kind) { LinkedHashMap() }
                val id = TrainingCodec.docId(w.key.id)
                when (w) {
                    is FoodWrite.PutEntry -> collection[id] = ((collection[id]?.first.orEmpty() + FoodCodec.entryToMap(w.entry)) - FirestoreCodec.F_DELETED_AT) to serverTime
                    is FoodWrite.PutCustomFood -> collection[id] = ((collection[id]?.first.orEmpty() + FoodCodec.customFoodToMap(w.food)) - FirestoreCodec.F_DELETED_AT) to serverTime
                    is FoodWrite.Delete -> collection[id] = FoodCodec.tombstoneToMap(w.deletedAt) to serverTime
                }
            }
            return FoodWriteResult(writes.mapTo(HashSet()) { it.key }, allConfirmed = true)
        }

        fun putRaw(kind: FoodKind, id: String, data: Map<String, Any?>) {
            serverTime += 3_600_000L
            docs.getOrPut(kind) { LinkedHashMap() }[TrainingCodec.docId(id)] = data to serverTime
        }

        fun live(kind: FoodKind): Map<String, Map<String, Any?>> =
            docs[kind].orEmpty().filterValues { it.first[FirestoreCodec.F_DELETED] != true }.entries.associate { (k, v) -> TrainingCodec.rawId(k)!! to v.first }

        fun dead(kind: FoodKind): Set<String> =
            docs[kind].orEmpty().filterValues { it.first[FirestoreCodec.F_DELETED] == true }.keys.mapTo(HashSet()) { TrainingCodec.rawId(it)!! }
    }

    private class FakeBases : FoodBaseStore {
        val texts = HashMap<String, String>()

        override suspend fun loadFoodBase(uid: String): FoodBase? = texts[uid]?.let(FoodBaseCodec::decode)

        override suspend fun saveFoodBase(uid: String, base: FoodBase) {
            texts[uid] = FoodBaseCodec.encode(base)
        }
    }

    private class Phone(val store: FakeFoodStore, val repo: FoodRepository, val sync: FoodSync, val deletedPhotos: MutableList<String>)

    private fun TestScope.phone(cloud: FakeCloud): Phone {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher).also { scopes += it }
        val store = FakeFoodStore()
        val repo = FoodRepository(store, scope, io = dispatcher, clock = { now }, newId = { "id${ids++}" })
        val deleted = mutableListOf<String>()
        val sync = FoodSync(repo, { deleted += it }, cloud, FakeBases(), clock = { now }, io = dispatcher, compute = dispatcher)
        return Phone(store, repo, sync, deleted)
    }

    private suspend fun Phone.syncNow() = sync.sync(uid, dataset)

    private fun food() = CustomFood("", "Galletas", "Marca", "841", FoodUnit.G, Nutrients(kcal = 436.0, salt = 0.75), 25.0, CustomFoodSource.OFF, 0, 0)

    @Test
    fun aNewPhoneRestoresTheFoodLogWithoutPhotos() = runTest {
        val cloud = FakeCloud()
        val a = phone(cloud)
        advanceUntilIdle()
        val custom = a.repo.addCustomFood(food())
        val meal = a.repo.addEntry(testEntry(photo = "meal_1.jpg").copy(portionKey = PortionKey.PLATE, portionCount = 1.5, modelLabel = "paella", modelConfidence = 0.8))
        assertFalse(a.syncNow())
        assertEquals(setOf(meal.id), cloud.live(FoodKind.ENTRY).keys)
        assertEquals(setOf(custom.id), cloud.live(FoodKind.CUSTOM_FOOD).keys)
        assertTrue("the photo never goes to the cloud", cloud.live(FoodKind.ENTRY).values.none { doc -> doc.values.any { it == "meal_1.jpg" } })

        now += 86_400_000
        val b = phone(cloud)
        advanceUntilIdle()
        assertFalse(b.syncNow())
        assertEquals(listOf(meal.copy(photo = null)), b.repo.entries.value)
        assertEquals(listOf(custom), b.repo.customFoods.value)
        // Nothing to do on the next sync of either phone (no loops).
        val writesBefore = cloud.serverTime
        assertFalse(a.syncNow())
        assertFalse(b.syncNow())
        assertEquals(writesBefore, cloud.serverTime)
        assertEquals("the photo stays on the phone that took it", "meal_1.jpg", a.repo.entries.value.single().photo)
    }

    @Test
    fun editsAndDeletionsTravelAndThePhotoGoesWithTheEntry() = runTest {
        val cloud = FakeCloud()
        val a = phone(cloud)
        advanceUntilIdle()
        val meal = a.repo.addEntry(testEntry(photo = "meal_1.jpg"))
        val other = a.repo.addEntry(testEntry(name = "Ensalada"))
        a.syncNow()
        val b = phone(cloud)
        advanceUntilIdle()
        b.syncNow()

        // Edited on B: A gets the new amount and keeps its photo.
        now += 60_000
        b.repo.updateEntry(meal.id) { it.copy(amount = 250.0) }
        b.syncNow()
        a.syncNow()
        assertEquals(250.0, a.repo.entry(meal.id)!!.amount, 0.0)
        assertEquals("meal_1.jpg", a.repo.entry(meal.id)!!.photo)

        // Deleted on B: gone on A, with its photo; the tombstone is in the cloud and dropped on B.
        now += 60_000
        b.repo.deleteEntry(meal.id)
        b.syncNow()
        assertEquals(setOf(meal.id), cloud.dead(FoodKind.ENTRY))
        assertTrue("confirmed tombstones are forgotten", b.store.tombstones.isEmpty())
        a.syncNow()
        assertNull(a.repo.entry(meal.id))
        assertEquals(listOf("meal_1.jpg"), a.deletedPhotos)
        assertEquals(listOf(other.id), a.repo.entries.value.map { it.id })
    }

    @Test
    fun lastWriterWinsAndOfflineWritesAreRetried() = runTest {
        val cloud = FakeCloud()
        val a = phone(cloud)
        val b = phone(cloud)
        advanceUntilIdle()
        val meal = a.repo.addEntry(testEntry())
        a.syncNow()
        b.syncNow()
        now += 1_000
        a.repo.updateEntry(meal.id) { it.copy(amount = 111.0) }
        now += 1_000
        b.repo.updateEntry(meal.id) { it.copy(amount = 222.0) }
        a.syncNow()
        b.syncNow()
        a.syncNow()
        assertEquals("the later edit wins on both phones", 222.0, a.repo.entry(meal.id)!!.amount, 0.0)
        assertEquals(222.0, b.repo.entry(meal.id)!!.amount, 0.0)

        // No confirmation from the server: queued, uploaded again next time.
        cloud.confirm = false
        now += 1_000
        val queued = a.repo.addEntry(testEntry(name = "Plátano"))
        assertTrue(a.syncNow())
        cloud.confirm = true
        assertFalse(a.syncNow())
        assertTrue(queued.id in cloud.live(FoodKind.ENTRY))
    }

    @Test
    fun documentsOfANewerAppVersionAreNeverOverwritten() = runTest {
        val cloud = FakeCloud()
        val a = phone(cloud)
        advanceUntilIdle()
        val meal = a.repo.addEntry(testEntry())
        a.syncNow()
        val newer = FoodCodec.entryToMap(meal.copy(amount = 500.0, updatedAt = meal.updatedAt + 10)) + (FirestoreCodec.F_SCHEMA to 2) + ("futureField" to "x")
        cloud.putRaw(FoodKind.ENTRY, meal.id, newer)
        a.syncNow()
        assertEquals("read", 500.0, a.repo.entry(meal.id)!!.amount, 0.0)
        now += 60_000
        a.repo.updateEntry(meal.id) { it.copy(amount = 1.0) }
        a.syncNow()
        assertEquals("never written back", "x", cloud.live(FoodKind.ENTRY).getValue(meal.id)["futureField"])
        assertEquals(500.0, cloud.live(FoodKind.ENTRY).getValue(meal.id)["amount"])
    }

    @Test
    fun oneDocumentThePhoneRefusesDoesNotStopTheOthers() = runTest {
        val cloud = FakeCloud()
        val a = phone(cloud)
        advanceUntilIdle()
        val bad = a.repo.addEntry(testEntry(name = "Bad"))
        val good = a.repo.addEntry(testEntry(name = "Good"))
        val f = a.repo.addCustomFood(food())
        a.syncNow()
        val b = phone(cloud)
        advanceUntilIdle()
        b.store.refuse = setOf(bad.id)
        // The batch is refused as a whole, then applied item by item: only the bad one is left out.
        assertFalse(b.syncNow())
        assertEquals(listOf(good.id), b.repo.entries.value.map { it.id })
        assertEquals(listOf(f.id), b.repo.customFoods.value.map { it.id })
        // ...and the next syncs neither fail nor try it again and again, nor write anything.
        val writes = cloud.serverTime
        assertFalse(b.syncNow())
        assertFalse(b.syncNow())
        assertEquals(writes, cloud.serverTime)
        assertEquals(setOf(bad.id, good.id), cloud.live(FoodKind.ENTRY).keys)
    }

    @Test
    fun aPhotoTheScreensStillNeedSurvivesADeletionFromAnotherPhone() = runTest {
        val cloud = FakeCloud()
        val a = phone(cloud)
        advanceUntilIdle()
        val meal = a.repo.addEntry(testEntry(photo = "meal_1.jpg"))
        a.syncNow()
        val b = phone(cloud)
        advanceUntilIdle()
        b.syncNow()
        now += 60_000
        b.repo.deleteEntry(meal.id)
        b.syncNow()
        // On A the same plate is being logged again (or its "Undo" is offered): the file stays.
        val screens = a.repo.registerPhotoUser { it == "meal_1.jpg" }
        a.syncNow()
        assertNull(a.repo.entry(meal.id))
        assertTrue(a.deletedPhotos.isEmpty())
        screens.close()
    }

    @Test
    fun codecRoundTripsAndRejectsBadDocuments() {
        val e = testEntry(id = "e1", updatedAt = 77).copy(portionKey = PortionKey.SLICE, portionCount = 2.0, modelLabel = "pizza", modelConfidence = 0.4, photo = "x.jpg")
        val decoded = (FoodCodec.entryFromMap("e1", FoodCodec.entryToMap(e), 123L) as Decoded.Live).record
        assertEquals(e.copy(photo = null), decoded)
        val f = CustomFood("c1", "Galletas", null, "841", FoodUnit.ML, Nutrients(kcal = 1.0), null, CustomFoodSource.USER, 5, 9)
        assertEquals(f, (FoodCodec.customFoodFromMap("c1", FoodCodec.customFoodToMap(f), null) as Decoded.Live).record)
        assertNull("no name", FoodCodec.entryFromMap("e2", FoodCodec.entryToMap(e) - "name", null))
        assertNull("no amount", FoodCodec.entryFromMap("e2", FoodCodec.entryToMap(e) + ("amount" to 0.0), null))
        val dead = FoodCodec.entryFromMap("e3", FoodCodec.tombstoneToMap(42), null) as Decoded.Dead
        assertEquals(42L, dead.tombstone.deletedAt)
        // Every nutrient is written, unknown ones as null (a merged write must clear a removed value).
        assertTrue(FoodCodec.entryToMap(e.copy(nutrients = Nutrients(kcal = 1.0))).containsKey("sugars"))
    }

    @Test
    fun baseCodecRoundTrip() {
        val base = FoodBase(
            datasetKey = "ds",
            pending = TrainingMode.ADOPT_MERGE,
            pendingSince = 5,
            cursors = mapOf(FoodKind.ENTRY to 10L),
            entries = mapOf(FoodDocKey(FoodKind.ENTRY, "a") to "1.abc", FoodDocKey(FoodKind.CUSTOM_FOOD, "b") to TrainingMerge.dead(3)),
            readOnly = setOf(FoodDocKey(FoodKind.ENTRY, "c")),
        )
        assertEquals(base, FoodBaseCodec.decode(FoodBaseCodec.encode(base)))
        assertNull(FoodBaseCodec.decode("v\t9\ndataset\tx\n"))
    }
}
