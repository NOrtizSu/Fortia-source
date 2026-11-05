package com.enjoyyourtime.globalfitness.cloud

import com.enjoyyourtime.globalfitness.cloud.FirestoreCodec.Decoded
import com.enjoyyourtime.globalfitness.food.FakeFoodStore
import com.enjoyyourtime.globalfitness.food.data.FoodRepository
import com.enjoyyourtime.globalfitness.food.model.CustomFood
import com.enjoyyourtime.globalfitness.food.model.CustomFoodSource
import com.enjoyyourtime.globalfitness.food.model.FoodUnit
import com.enjoyyourtime.globalfitness.food.model.IngredientAdjustment
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

    private class Phone(val store: FakeFoodStore, val repo: FoodRepository, val sync: FoodSync, val deletedPhotos: MutableList<String>, val bases: FakeBases)

    private fun TestScope.phone(cloud: FakeCloud): Phone {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher).also { scopes += it }
        val store = FakeFoodStore()
        val repo = FoodRepository(store, scope, io = dispatcher, clock = { now }, newId = { "id${ids++}" })
        val deleted = mutableListOf<String>()
        val bases = FakeBases()
        val sync = FoodSync(repo, { deleted += it }, cloud, bases, clock = { now }, io = dispatcher, compute = dispatcher)
        return Phone(store, repo, sync, deleted, bases)
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
        val newer = FoodCodec.entryToMap(meal.copy(amount = 500.0, updatedAt = meal.updatedAt + 10)) + (FirestoreCodec.F_SCHEMA to FoodCodec.FOOD_SCHEMA_VERSION + 1) + ("futureField" to "x")
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
        assertTrue(FoodCodec.entryToMap(e.copy(nutrients = Nutrients(kcal = 1.0))).containsKey("vitB12"))
        assertEquals(emptyList<Any>(), FoodCodec.entryToMap(e)["adjustments"])
    }

    /** An entry with everything 1.6.0 adds: the values beyond the label, changed ingredients, the photo's estimate. */
    private fun extendedEntry(id: String = "") = testEntry(id = id, amount = 367.5, updatedAt = 50).copy(
        nutrients = Nutrients(kcal = 710.0, protein = 26.0, fat = 30.0, monoFat = 19.0, calcium = 64.0, vitB12 = 0.8, folate = 41.0),
        adjustments = listOf(IngredientAdjustment("olive_oil", 17.5, 35.0, 2.0), IngredientAdjustment("bread", 30.0, 0.0, 0.0), IngredientAdjustment("rice", 87.5, 70.0, null)),
        estimatedAmount = 350.0,
    )

    @Test
    fun extendedDataRoundTripsAndIsReadOnlyForOlderVersions() {
        val e = extendedEntry("e1")
        val map = FoodCodec.entryToMap(e)
        // 1.5.0 reads documents up to version 1 in full: this one is above it, so it never writes it back.
        assertEquals(FoodCodec.EXTENDED_SCHEMA_VERSION, map[FirestoreCodec.F_SCHEMA])
        assertTrue(((map[FirestoreCodec.F_SCHEMA] as Int)) > 1)
        assertEquals(e, (FoodCodec.entryFromMap("e1", map, 9L) as Decoded.Live).record)
        assertFalse("this version reads it in full", FoodCodec.isNewerSchema(map))
        // Without anything new it stays at version 1: 1.5.0 keeps writing it.
        assertEquals(1, FoodCodec.entryToMap(testEntry(id = "e2"))[FirestoreCodec.F_SCHEMA])
        assertEquals(1, FoodCodec.entryToMap(testEntry(id = "e3").copy(estimatedAmount = 200.0))[FirestoreCodec.F_SCHEMA])
        val f = CustomFood("c1", "Galletas", null, "841", FoodUnit.G, Nutrients(kcal = 436.0, iron = 2.1), 25.0, CustomFoodSource.USER, 5, 9)
        assertEquals(2, FoodCodec.customFoodToMap(f)[FirestoreCodec.F_SCHEMA])
        assertEquals(f, (FoodCodec.customFoodFromMap("c1", FoodCodec.customFoodToMap(f), null) as Decoded.Live).record)
        assertEquals(1, FoodCodec.customFoodToMap(f.copy(per100 = Nutrients(kcal = 436.0)))[FirestoreCodec.F_SCHEMA])
        // A document of a later version is read but kept read-only.
        assertTrue(FoodCodec.isNewerSchema(map + (FirestoreCodec.F_SCHEMA to FoodCodec.FOOD_SCHEMA_VERSION + 1)))
    }

    @Test
    fun aDocumentOf150ReadsWithoutTheNewData() {
        // What 1.5.0 wrote (its field names, no new ones).
        val old = mapOf<String, Any?>(
            "schemaVersion" to 1L, "deleted" to false, "eatenAt" to 1_700_000_000_000L, "zoneOffset" to 3600L, "meal" to "lunch",
            "foodId" to "paella", "customFoodId" to null, "name" to "Paella", "amount" to 350.0, "unit" to "g", "portionKey" to "plate",
            "portionCount" to 1.0, "kcal" to 553.0, "protein" to 26.0, "carbs" to 70.0, "sugars" to null, "fat" to 18.0, "satFat" to 3.0,
            "fiber" to 2.0, "salt" to 1.5, "source" to "photo", "modelLabel" to "paella", "modelConfidence" to 0.7, "createdAt" to 5L, "editedAt" to 6L,
        )
        val e = (FoodCodec.entryFromMap("e1", old, null) as Decoded.Live).record
        assertEquals(553.0, e.nutrients.kcal!!, 0.0)
        assertFalse(e.nutrients.hasExtra)
        assertTrue(e.adjustments.isEmpty())
        assertNull(e.estimatedAmount)
        // Broken adjustments are skipped, the rest kept.
        val odd = old + ("adjustments" to listOf(mapOf("ingredient" to "olive_oil", "baseGrams" to 10L, "grams" to 20.0, "factor" to 2L), "x", mapOf("ingredient" to "BAD")))
        assertEquals(listOf(IngredientAdjustment("olive_oil", 10.0, 20.0, 2.0)), (FoodCodec.entryFromMap("e1", odd, null) as Decoded.Live).record.adjustments)
    }

    @Test
    fun theVersionOfAnEntryWithoutNewDataIsThe150One() {
        // 1.5.0's FoodMerge.versionOf, verbatim: an unchanged entry must not look edited after the update (no re-upload of the log).
        fun v150(e: com.enjoyyourtime.globalfitness.food.model.FoodEntry): String {
            val c = TrainingMerge.Canon().l(e.eatenAt).l(e.zoneOffsetSeconds.toLong()).s(e.meal.code).s(e.foodId).s(e.customFoodId).s(e.name)
                .d(e.amount).s(e.unit.code).s(e.portionKey?.code).d(e.portionCount)
            val n = e.nutrients
            c.d(n.kcal).d(n.protein).d(n.carbs).d(n.sugars).d(n.fat).d(n.satFat).d(n.fiber).d(n.salt)
            c.s(e.source.code).s(e.modelLabel).d(e.modelConfidence).l(e.createdAt)
            return "${e.updatedAt}.${c.digest()}"
        }
        val e = testEntry(id = "e1", updatedAt = 9).copy(modelLabel = "paella", modelConfidence = 0.5)
        assertEquals(v150(e), FoodMerge.versionOf(e))
        val f = CustomFood("c1", "Galletas", "Marca", "841", FoodUnit.G, Nutrients(kcal = 436.0, salt = 0.75), 25.0, CustomFoodSource.OFF, 3, 4)
        val c = TrainingMerge.Canon().s(f.name).s(f.brand).s(f.barcode).s(f.unit.code)
        val n = f.per100
        c.d(n.kcal).d(n.protein).d(n.carbs).d(n.sugars).d(n.fat).d(n.satFat).d(n.fiber).d(n.salt).d(f.portion).s(f.source.code).l(f.createdAt)
        assertEquals("${f.updatedAt}.${c.digest()}", FoodMerge.versionOf(f))
        // Each new part changes the version.
        val x = extendedEntry("e1")
        val versions = setOf(
            FoodMerge.versionOf(x),
            FoodMerge.versionOf(x.copy(adjustments = emptyList())),
            FoodMerge.versionOf(x.copy(estimatedAmount = null)),
            FoodMerge.versionOf(x.copy(nutrients = x.nutrients.copy(calcium = 65.0))),
        )
        assertEquals(4, versions.size)
    }

    @Test
    fun extendedEntriesSyncBetweenPhones() = runTest {
        val cloud = FakeCloud()
        val a = phone(cloud)
        advanceUntilIdle()
        val meal = a.repo.addEntry(extendedEntry())
        assertFalse(a.syncNow())
        val b = phone(cloud)
        advanceUntilIdle()
        assertFalse(b.syncNow())
        assertEquals(listOf(meal), b.repo.entries.value)
        // Ingredients changed again on B: A gets the new ones (the list is replaced, not merged).
        now += 60_000
        b.repo.updateEntry(meal.id) { it.copy(adjustments = it.adjustments.take(1)) }
        assertFalse(b.syncNow())
        assertFalse(a.syncNow())
        assertEquals(1, a.repo.entry(meal.id)!!.adjustments.size)
    }

    @Test
    fun valuesTypedByHandRoundTripAndAreReadOnlyForOlderVersions() {
        val e = extendedEntry("e1").copy(valuesEdited = true)
        val map = FoodCodec.entryToMap(e)
        assertEquals(FoodCodec.EDITED_SCHEMA_VERSION, map[FirestoreCodec.F_SCHEMA])
        assertEquals(true, map["valuesEdited"])
        assertEquals(e, (FoodCodec.entryFromMap("e1", map, 9L) as Decoded.Live).record)
        assertFalse("this version reads and writes it in full", FoodCodec.isNewerSchema(map))
        // 1.6.0 writes up to version 2 and 1.5.0 up to 1: above that a document is read-only for them,
        // so neither can rewrite it without the mark.
        assertTrue((map[FirestoreCodec.F_SCHEMA] as Int) > FoodCodec.EXTENDED_SCHEMA_VERSION)
        assertEquals(FoodCodec.EDITED_SCHEMA_VERSION, FoodCodec.entryToMap(testEntry(id = "e2").copy(valuesEdited = true))[FirestoreCodec.F_SCHEMA])
        // Without the mark: versions 1 and 2 as before, the field written as false (a merged write clears it).
        val plain = FoodCodec.entryToMap(testEntry(id = "e3"))
        assertEquals(1, plain[FirestoreCodec.F_SCHEMA])
        assertEquals(false, plain["valuesEdited"])
        assertEquals(FoodCodec.EXTENDED_SCHEMA_VERSION, FoodCodec.entryToMap(extendedEntry("e4"))[FirestoreCodec.F_SCHEMA])
        // A document of 1.5.0 or 1.6.0 (no field) reads as not typed; the mark changes the version only when set.
        assertFalse((FoodCodec.entryFromMap("e3", plain - "valuesEdited", null) as Decoded.Live).record.valuesEdited)
        val x = extendedEntry("e1")
        assertTrue(FoodMerge.versionOf(x) != FoodMerge.versionOf(x.copy(valuesEdited = true)))
    }

    @Test
    fun valuesTypedByHandSyncAndTheirMarkIsCleared() = runTest {
        val cloud = FakeCloud()
        val a = phone(cloud)
        advanceUntilIdle()
        val meal = a.repo.addEntry(testEntry().copy(nutrients = Nutrients(kcal = 640.0, protein = 30.0), valuesEdited = true))
        assertFalse(a.syncNow())
        assertEquals(3, cloud.live(FoodKind.ENTRY).getValue(meal.id)[FirestoreCodec.F_SCHEMA])
        val b = phone(cloud)
        advanceUntilIdle()
        assertFalse(b.syncNow())
        assertEquals(listOf(meal), b.repo.entries.value)
        assertTrue(b.repo.entry(meal.id)!!.valuesEdited)
        // "Restore" on B: the document goes back to version 1 and loses the mark on A too.
        now += 60_000
        b.repo.updateEntry(meal.id) { it.copy(nutrients = testEntry().nutrients, valuesEdited = false) }
        assertFalse(b.syncNow())
        val doc = cloud.live(FoodKind.ENTRY).getValue(meal.id)
        assertEquals(1, doc[FirestoreCodec.F_SCHEMA])
        assertEquals(false, doc["valuesEdited"])
        assertFalse(a.syncNow())
        assertFalse(a.repo.entry(meal.id)!!.valuesEdited)
        assertEquals(300.0, a.repo.entry(meal.id)!!.nutrients.kcal!!, 0.0)
    }

    @Test
    fun aPhoneUpdatedFrom160ReadsAgainWhatItKeptReadOnly() = runTest {
        val cloud = FakeCloud()
        val a = phone(cloud)
        advanceUntilIdle()
        val meal = a.repo.addEntry(testEntry().copy(nutrients = Nutrients(kcal = 640.0, protein = 30.0), valuesEdited = true))
        assertFalse(a.syncNow())
        // An incremental read from there no longer returns the document (well past the overlap of the pulls).
        val past = 10 * 3_600_000L
        // Phone B as 1.6.0 left it: the document (version 3) read without the mark it does not know,
        // kept read-only, its cursor past it, and a base without the reader's version.
        val b = phone(cloud)
        b.store.upsertEntry(meal.copy(valuesEdited = false))
        val old = FoodBase(
            datasetKey = dataset,
            cursors = mapOf(FoodKind.ENTRY to cloud.serverTime + past, FoodKind.CUSTOM_FOOD to cloud.serverTime + past),
            entries = mapOf(FoodDocKey(FoodKind.ENTRY, meal.id) to FoodMerge.versionOf(meal.copy(valuesEdited = false))),
            readOnly = setOf(FoodDocKey(FoodKind.ENTRY, meal.id)),
        )
        b.bases.texts[uid] = FoodBaseCodec.encode(old).lineSequence().filterNot { it.startsWith("schema") }.joinToString("\n")
        assertEquals(0, b.bases.loadFoodBase(uid)!!.schema)
        advanceUntilIdle()
        // Updated: the first sync reads everything again, so the mark arrives and the document is no longer read-only.
        assertFalse(b.syncNow())
        assertTrue(b.repo.entry(meal.id)!!.valuesEdited)
        val saved = b.bases.loadFoodBase(uid)!!
        assertEquals(FoodCodec.FOOD_SCHEMA_VERSION, saved.schema)
        assertTrue(saved.readOnly.isEmpty())
        // A later edit on B goes up.
        now += 60_000
        b.repo.updateEntry(meal.id) { it.copy(amount = 200.0) }
        assertFalse(b.syncNow())
        assertEquals(200.0, cloud.live(FoodKind.ENTRY).getValue(meal.id)["amount"])
        assertEquals(true, cloud.live(FoodKind.ENTRY).getValue(meal.id)["valuesEdited"])
    }

    @Test
    fun aProductsCheckTimeStaysOnThePhone() {
        val f = CustomFood("c1", "Galletas", null, "841", FoodUnit.G, Nutrients(kcal = 436.0), 25.0, CustomFoodSource.OFF, 5, 9, checkedAt = 50)
        val map = FoodCodec.customFoodToMap(f)
        assertTrue(map.keys.none { it.contains("check", ignoreCase = true) })
        assertEquals("not part of the version either", FoodMerge.versionOf(f.copy(checkedAt = null)), FoodMerge.versionOf(f))
        assertEquals(f.copy(checkedAt = null), (FoodCodec.customFoodFromMap("c1", map, null) as Decoded.Live).record)
    }

    @Test
    fun aProductsPhotoStaysOnThePhone() {
        val plain = CustomFood("c1", "Nutella", "Ferrero", "3017620422003", FoodUnit.G, Nutrients(kcal = 539.0), 15.0, CustomFoodSource.OFF, 5, 9)
        val f = plain.copy(imageUrl = "https://images.openfoodfacts.org/a.jpg", image = "off_1.jpg")
        val map = FoodCodec.customFoodToMap(f)
        assertEquals("nothing of the photo in the cloud document", FoodCodec.customFoodToMap(plain), map)
        assertTrue(map.values.none { it is String && (it.contains("openfoodfacts") || it.contains(".jpg")) })
        assertEquals("not part of the version either", FoodMerge.versionOf(plain), FoodMerge.versionOf(f))
        assertEquals(plain, (FoodCodec.customFoodFromMap("c1", map, null) as Decoded.Live).record)
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
        // A base of 1.6.0 (no reader version): read whole once, its read-only marks forgotten.
        val old = FoodBaseCodec.decode("v\t1\ndataset\tds\ncursor\te\t10\nro\te\tc\n")!!
        assertEquals(0, old.schema)
        val updated = old.forReader()
        assertEquals(FoodCursors.FULL, updated.cursorsOrFull(trusted = true))
        assertTrue(updated.readOnly.isEmpty())
        assertEquals(FoodCodec.FOOD_SCHEMA_VERSION, updated.schema)
        assertEquals("a base of this version stays as it is", base, base.forReader())
    }
}
