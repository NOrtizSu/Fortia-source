package com.enjoyyourtime.globalfitness.food

import com.enjoyyourtime.globalfitness.food.data.AdjustmentsColumn
import com.enjoyyourtime.globalfitness.food.data.FoodBatch
import com.enjoyyourtime.globalfitness.food.data.FoodRepository
import com.enjoyyourtime.globalfitness.food.data.FoodRows
import com.enjoyyourtime.globalfitness.food.data.FoodSchema
import com.enjoyyourtime.globalfitness.food.data.FoodVersions
import com.enjoyyourtime.globalfitness.food.domain.FoodRecall
import com.enjoyyourtime.globalfitness.food.model.CustomFood
import com.enjoyyourtime.globalfitness.food.model.CustomFoodSource
import com.enjoyyourtime.globalfitness.food.model.FoodItem
import com.enjoyyourtime.globalfitness.food.model.FoodTombstones
import com.enjoyyourtime.globalfitness.food.model.FoodUnit
import com.enjoyyourtime.globalfitness.food.model.IngredientAdjustment
import com.enjoyyourtime.globalfitness.food.model.Nutrients
import com.enjoyyourtime.globalfitness.food.off.OffCache
import com.enjoyyourtime.globalfitness.food.off.OffProduct
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

@OptIn(ExperimentalCoroutinesApi::class)
class FoodDataTest {
    // ---- schema on a real SQLite ---------------------------------------------------------------

    private fun columns(db: Connection, table: String): List<String> =
        db.createStatement().executeQuery("PRAGMA table_info($table)").use { rs ->
            buildList { while (rs.next()) add(rs.getString("name")) }
        }

    @Test
    fun schemaCreatesEveryColumnTheDatabaseReads() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { db ->
            FoodSchema.CREATE.forEach { db.createStatement().execute(it) }
            assertEquals(FoodSchema.ENTRY_COLUMNS, columns(db, "food_entries"))
            assertEquals(FoodSchema.CUSTOM_FOOD_COLUMNS, columns(db, "custom_foods"))
            assertEquals(listOf("kind", "id", "deleted_at"), columns(db, "tombstones"))
            assertEquals(listOf("entry_id", "version", "record_id", "exported_at", "device"), columns(db, "hc_export"))
            // The reads select these lists: they work, NULLs included.
            val placeholders = FoodSchema.ENTRY_COLUMNS.joinToString { "?" }
            db.prepareStatement("INSERT INTO food_entries (${FoodSchema.ENTRY_COLUMNS.joinToString()}) VALUES ($placeholders)").use { st ->
                FoodSchema.ENTRY_COLUMNS.forEachIndexed { i, c ->
                    when (c) {
                        "id" -> st.setString(i + 1, "e1")
                        "eaten_at", "created_at", "updated_at" -> st.setLong(i + 1, 1L)
                        "zone_offset" -> st.setInt(i + 1, 3600)
                        "meal", "name", "unit", "source" -> st.setString(i + 1, "x")
                        "amount" -> st.setDouble(i + 1, 150.0)
                        else -> st.setNull(i + 1, java.sql.Types.NULL)
                    }
                }
                st.executeUpdate()
            }
            db.createStatement().executeQuery("SELECT ${FoodSchema.ENTRY_COLUMNS.joinToString()} FROM food_entries ORDER BY eaten_at, id").use { rs ->
                assertTrue(rs.next())
                assertEquals("e1", rs.getString(1))
                assertNull(rs.getObject(12)) // kcal unknown
            }
        }
    }

    /** [FoodSchema.upgrade]'s view of a JDBC connection (what FoodDatabase gives it on the phone). */
    private fun sql(db: Connection) = object : FoodSchema.Sql {
        val statements = mutableListOf<String>()

        override fun exec(sql: String) {
            statements += sql
            db.createStatement().execute(sql)
        }

        override fun columns(table: String): Set<String> = columns(db, table).toSet()
    }

    /** food.db as 1.5.0 created it (version 1), verbatim. */
    private val v1Schema = listOf(
        """
        CREATE TABLE IF NOT EXISTS food_entries (
            id TEXT PRIMARY KEY, eaten_at INTEGER NOT NULL, zone_offset INTEGER NOT NULL, meal TEXT NOT NULL,
            food_id TEXT, custom_food_id TEXT, name TEXT NOT NULL, amount REAL NOT NULL, unit TEXT NOT NULL,
            portion_key TEXT, portion_count REAL,
            kcal REAL, protein REAL, carbs REAL, sugars REAL, fat REAL, sat_fat REAL, fiber REAL, salt REAL,
            source TEXT NOT NULL, photo TEXT, model_label TEXT, model_confidence REAL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL
        )
        """,
        "CREATE INDEX IF NOT EXISTS idx_food_entries_eaten ON food_entries(eaten_at)",
        """
        CREATE TABLE IF NOT EXISTS custom_foods (
            id TEXT PRIMARY KEY, name TEXT NOT NULL, brand TEXT, barcode TEXT, unit TEXT NOT NULL,
            kcal REAL, protein REAL, carbs REAL, sugars REAL, fat REAL, sat_fat REAL, fiber REAL, salt REAL,
            portion REAL, source TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL
        )
        """,
        "CREATE INDEX IF NOT EXISTS idx_custom_foods_barcode ON custom_foods(barcode)",
        "CREATE TABLE IF NOT EXISTS tombstones (kind TEXT NOT NULL, id TEXT NOT NULL, deleted_at INTEGER NOT NULL, PRIMARY KEY (kind, id))",
        "CREATE TABLE IF NOT EXISTS hc_export (entry_id TEXT PRIMARY KEY, version INTEGER NOT NULL, record_id TEXT, exported_at INTEGER NOT NULL, device TEXT NOT NULL)",
    )

    @Test
    fun aFileOf150UpgradesKeepingItsRows() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { db ->
            v1Schema.forEach { db.createStatement().execute(it) }
            db.createStatement().execute(
                "INSERT INTO food_entries (id, eaten_at, zone_offset, meal, name, amount, unit, kcal, protein, source, created_at, updated_at) " +
                    "VALUES ('e1', 5, 3600, 'lunch', 'Paella', 350, 'g', 553, 26, 'photo', 5, 6)",
            )
            db.createStatement().execute("INSERT INTO custom_foods (id, name, unit, kcal, source, created_at, updated_at) VALUES ('c1', 'Galletas', 'g', 436, 'off', 1, 2)")
            db.createStatement().execute("INSERT INTO tombstones (kind, id, deleted_at) VALUES ('food_entry', 'a', 5)")
            val sql = sql(db)
            FoodSchema.upgrade(sql, 1)
            // Same columns, in the same order, as a file created by this version.
            assertEquals(FoodSchema.ENTRY_COLUMNS, columns(db, "food_entries"))
            assertEquals(FoodSchema.CUSTOM_FOOD_COLUMNS, columns(db, "custom_foods"))
            db.createStatement().executeQuery("SELECT ${FoodSchema.ENTRY_COLUMNS.joinToString()} FROM food_entries").use { rs ->
                assertTrue(rs.next())
                assertEquals("Paella", rs.getString("name"))
                assertEquals(553.0, rs.getDouble("kcal"), 0.0)
                assertNull("unknown, not zero", rs.getObject("calcium"))
                assertNull(rs.getObject("adjustments"))
                assertNull(rs.getObject("estimated_amount"))
            }
            db.createStatement().executeQuery("SELECT ${FoodSchema.CUSTOM_FOOD_COLUMNS.joinToString()} FROM custom_foods").use { rs ->
                assertTrue(rs.next())
                assertNull(rs.getObject("vit_c"))
            }
            // 1.5.0 opened the file again (it marks it version 1) and this version upgrades it once more: nothing fails or is lost.
            sql.statements.clear()
            FoodSchema.upgrade(sql, 1)
            assertTrue("no column added twice: ${sql.statements}", sql.statements.none { it.startsWith("ALTER") })
            sql.statements.clear()
            FoodSchema.upgrade(sql, FoodSchema.VERSION)
            assertEquals(emptyList<String>(), sql.statements)
            db.createStatement().executeQuery("SELECT COUNT(*) FROM tombstones").use { rs ->
                rs.next()
                assertEquals(1, rs.getInt(1))
            }
        }
    }

    /** food_entries as 1.6.0 created it (version 2), verbatim; its other tables are those of [v1Schema] plus the extra nutrients. */
    private val v2Entries = """
        CREATE TABLE IF NOT EXISTS food_entries (
            id TEXT PRIMARY KEY, eaten_at INTEGER NOT NULL, zone_offset INTEGER NOT NULL, meal TEXT NOT NULL,
            food_id TEXT, custom_food_id TEXT, name TEXT NOT NULL, amount REAL NOT NULL, unit TEXT NOT NULL,
            portion_key TEXT, portion_count REAL,
            kcal REAL, protein REAL, carbs REAL, sugars REAL, fat REAL, sat_fat REAL, fiber REAL, salt REAL,
            source TEXT NOT NULL, photo TEXT, model_label TEXT, model_confidence REAL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL,
            mono_fat REAL, poly_fat REAL, cholesterol REAL, potassium REAL, calcium REAL, iron REAL, magnesium REAL, zinc REAL,
            vit_a REAL, vit_c REAL, vit_d REAL, vit_e REAL, vit_b12 REAL, folate REAL, adjustments TEXT, estimated_amount REAL
        )
        """

    private fun v2Schema(db: Connection) {
        db.createStatement().execute(v2Entries)
        v1Schema.drop(1).forEach { db.createStatement().execute(it) }
        for (c in FoodSchema.EXTRA_NUTRIENT_COLUMNS) db.createStatement().execute("ALTER TABLE custom_foods ADD COLUMN $c REAL")
    }

    @Test
    fun aFileOf160UpgradesKeepingItsRows() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { db ->
            v2Schema(db)
            db.createStatement().execute(
                "INSERT INTO food_entries (id, eaten_at, zone_offset, meal, name, amount, unit, kcal, calcium, source, created_at, updated_at, adjustments) " +
                    "VALUES ('e1', 5, 3600, 'lunch', 'Paella', 350, 'g', 553, 40, 'photo', 5, 6, 'olive_oil,17.5,35.0,2.0')",
            )
            val sql = sql(db)
            FoodSchema.upgrade(sql, 2)
            assertEquals(
                listOf(
                    "ALTER TABLE food_entries ADD COLUMN values_edited INTEGER",
                    "ALTER TABLE custom_foods ADD COLUMN checked_at INTEGER",
                    "ALTER TABLE custom_foods ADD COLUMN image_url TEXT",
                    "ALTER TABLE custom_foods ADD COLUMN image TEXT",
                ),
                sql.statements.filter { it.startsWith("ALTER") },
            )
            assertEquals("the same columns as a file created by this version", FoodSchema.ENTRY_COLUMNS, columns(db, "food_entries"))
            assertEquals(FoodSchema.CUSTOM_FOOD_COLUMNS, columns(db, "custom_foods"))
            db.createStatement().executeQuery("SELECT ${FoodSchema.ENTRY_COLUMNS.joinToString()} FROM food_entries").use { rs ->
                assertTrue(rs.next())
                assertEquals(40.0, rs.getDouble("calcium"), 0.0)
                assertEquals("olive_oil,17.5,35.0,2.0", rs.getString("adjustments"))
                assertNull("an old entry: not typed by hand", rs.getObject("values_edited"))
            }
        }
    }

    @Test
    fun aFileOf170OpenedBy160AndBackAgain() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { db ->
            FoodSchema.CREATE.forEach { db.createStatement().execute(it) }
            db.createStatement().execute(
                "INSERT INTO food_entries (id, eaten_at, zone_offset, meal, name, amount, unit, kcal, source, created_at, updated_at, values_edited) " +
                    "VALUES ('typed', 5, 3600, 'lunch', 'Paella', 350, 'g', 600, 'search', 5, 6, 1)",
            )
            // 1.6.0 opens it as it is (no downgrade step) and rewrites a row with the columns it knows (REPLACE).
            val known = FoodSchema.ENTRY_COLUMNS - "values_edited"
            db.createStatement().execute(
                "INSERT OR REPLACE INTO food_entries (id, eaten_at, zone_offset, meal, name, amount, unit, kcal, source, created_at, updated_at) " +
                    "VALUES ('typed', 5, 3600, 'dinner', 'Paella', 350, 'g', 600, 'search', 5, 7)",
            )
            db.createStatement().executeQuery("SELECT ${known.joinToString()} FROM food_entries").use { rs ->
                assertTrue("1.6.0 still reads every column it knows", rs.next())
                assertEquals("dinner", rs.getString("meal"))
            }
            // Android then marks the file version 2: this version upgrades it again without failing or adding twice.
            val sql = sql(db)
            FoodSchema.upgrade(sql, 2)
            assertTrue(sql.statements.none { it.startsWith("ALTER") })
            assertEquals(FoodSchema.ENTRY_COLUMNS, columns(db, "food_entries"))
            db.createStatement().executeQuery("SELECT values_edited, kcal FROM food_entries WHERE id = 'typed'").use { rs ->
                assertTrue(rs.next())
                assertNull("the rewritten row lost the mark (its values stay)", rs.getObject(1))
                assertEquals(600.0, rs.getDouble(2), 0.0)
            }
        }
    }

    /** food.db as 1.7.0 created it (version 3): that of 1.6.0 plus its two columns. */
    private fun v3Schema(db: Connection) {
        v2Schema(db)
        db.createStatement().execute("ALTER TABLE food_entries ADD COLUMN values_edited INTEGER")
        db.createStatement().execute("ALTER TABLE custom_foods ADD COLUMN checked_at INTEGER")
    }

    @Test
    fun aFileOf170UpgradesKeepingItsProducts() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { db ->
            v3Schema(db)
            db.createStatement().execute(
                "INSERT INTO custom_foods (id, name, barcode, unit, kcal, source, created_at, updated_at, checked_at) " +
                    "VALUES ('c1', 'Nutella', '3017620422003', 'g', 539, 'off', 1, 2, 3)",
            )
            val sql = sql(db)
            FoodSchema.upgrade(sql, 3)
            assertEquals(
                listOf("ALTER TABLE custom_foods ADD COLUMN image_url TEXT", "ALTER TABLE custom_foods ADD COLUMN image TEXT"),
                sql.statements.filter { it.startsWith("ALTER") },
            )
            assertEquals("the same columns as a file created by this version", FoodSchema.CUSTOM_FOOD_COLUMNS, columns(db, "custom_foods"))
            assertEquals(FoodSchema.ENTRY_COLUMNS, columns(db, "food_entries"))
            val kept = readAll(db, "custom_foods", FoodSchema.CUSTOM_FOOD_COLUMNS, FoodRows::customFoodOf).single()
            assertEquals("Nutella", kept.name)
            assertEquals(3L, kept.checkedAt)
            assertNull("not asked for its photo yet", kept.imageUrl)
            assertNull(kept.image)
            assertTrue(OffCache.needsImageCheck(kept))
            // Run again (a file this version upgraded, opened by 1.7.0, upgraded again): nothing added twice.
            sql.statements.clear()
            FoodSchema.upgrade(sql, 3)
            assertTrue(sql.statements.none { it.startsWith("ALTER") })
        }
    }

    @Test
    fun aFileOfThisVersionOpenedBy170AndBackAgain() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { db ->
            FoodSchema.CREATE.forEach { db.createStatement().execute(it) }
            val product = CustomFood("c1", "Nutella", "Ferrero", "3017620422003", FoodUnit.G, Nutrients(kcal = 539.0), 15.0, CustomFoodSource.OFF, 1, 2, checkedAt = 3, imageUrl = "https://images.openfoodfacts.org/a.jpg", image = "off_1.jpg")
            insert(db, "custom_foods", FoodRows.customFoodRow(product))
            // 1.7.0 opens it as it is and rewrites the row with the columns it knows (REPLACE).
            val known = FoodSchema.CUSTOM_FOOD_COLUMNS - listOf("image_url", "image")
            insert(db, "custom_foods", FoodRows.customFoodRow(product.copy(name = "Nutella 400 g")).filterKeys { it in known })
            // Android then marks the file version 3: this version upgrades it again without failing or adding twice.
            val sql = sql(db)
            FoodSchema.upgrade(sql, 3)
            assertTrue(sql.statements.none { it.startsWith("ALTER") })
            val back = readAll(db, "custom_foods", FoodSchema.CUSTOM_FOOD_COLUMNS, FoodRows::customFoodOf).single()
            assertEquals("Nutella 400 g", back.name)
            assertNull("the rewritten row lost its photo: asked again at its next scan", back.imageUrl)
            assertNull(back.image)
            assertTrue(OffCache.needsImageCheck(back))
        }
    }

    @Test
    fun upgradeStepsAreSafeToRunAgain() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { db ->
            FoodSchema.CREATE.forEach { db.createStatement().execute(it) }
            // A newer file opened by this version, then upgraded again: nothing fails, nothing is lost.
            db.createStatement().execute("INSERT INTO tombstones (kind, id, deleted_at) VALUES ('food_entry', 'a', 5)")
            FoodSchema.upgrade(sql(db), 0)
            assertEquals(FoodSchema.ENTRY_COLUMNS, columns(db, "food_entries"))
            db.createStatement().executeQuery("SELECT COUNT(*) FROM tombstones").use { rs ->
                rs.next()
                assertEquals(1, rs.getInt(1))
            }
        }
    }

    /** [row] stored in [table] with a plain INSERT (as FoodDatabase's insert does it on the phone). */
    private fun insert(db: Connection, table: String, row: Map<String, Any?>) {
        val columns = row.keys.toList()
        db.prepareStatement("INSERT OR REPLACE INTO $table (${columns.joinToString()}) VALUES (${columns.joinToString { "?" }})").use { st ->
            columns.forEachIndexed { i, c -> st.setObject(i + 1, row[c]) }
            st.executeUpdate()
        }
    }

    private fun <T> readAll(db: Connection, table: String, columns: List<String>, of: ((String) -> Any?) -> T): List<T> =
        db.createStatement().executeQuery("SELECT ${columns.joinToString()} FROM $table ORDER BY id").use { rs ->
            buildList { while (rs.next()) add(of { name -> rs.getObject(name) }) }
        }

    @Test
    fun rowsRoundTripThroughARealDatabase() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { db ->
            FoodSchema.CREATE.forEach { db.createStatement().execute(it) }
            val typed = testEntry(id = "e1", updatedAt = 7).copy(
                nutrients = Nutrients(kcal = 640.0, protein = 30.0, calcium = 40.0),
                adjustments = listOf(IngredientAdjustment("olive_oil", 17.5, 35.0, 2.0)),
                estimatedAmount = 180.0,
                valuesEdited = true,
                photo = "meal_1.jpg",
            )
            val plain = testEntry(id = "e2", updatedAt = 8)
            insert(db, "food_entries", FoodRows.entryRow(typed))
            insert(db, "food_entries", FoodRows.entryRow(plain))
            assertEquals(listOf(typed, plain), readAll(db, "food_entries", FoodSchema.ENTRY_COLUMNS, FoodRows::entryOf))
            // A row written by 1.6.0 (no values_edited) reads as not typed by hand.
            db.createStatement().execute("UPDATE food_entries SET values_edited = NULL WHERE id = 'e1'")
            assertFalse(readAll(db, "food_entries", FoodSchema.ENTRY_COLUMNS, FoodRows::entryOf).first().valuesEdited)

            val checked = CustomFood("c1", "Galletas", "Marca", "841", FoodUnit.G, Nutrients(kcal = 436.0, iron = 2.0), 25.0, CustomFoodSource.OFF, 1, 2, checkedAt = 99)
            val never = checked.copy(id = "c2", checkedAt = null)
            val photo = checked.copy(id = "c3", imageUrl = "https://images.openfoodfacts.org/a.jpg", image = "off_1.jpg")
            val noPhoto = checked.copy(id = "c4", imageUrl = "")
            for (f in listOf(checked, never, photo, noPhoto)) insert(db, "custom_foods", FoodRows.customFoodRow(f))
            assertEquals(listOf(checked, never, photo, noPhoto), readAll(db, "custom_foods", FoodSchema.CUSTOM_FOOD_COLUMNS, FoodRows::customFoodOf))
        }
    }

    @Test
    fun adjustmentsColumnRoundTrip() {
        val list = listOf(
            IngredientAdjustment("olive_oil", 17.5, 35.0, 2.0),
            IngredientAdjustment("bread", 40.0, 12.5, null),
        )
        assertEquals(list, AdjustmentsColumn.decode(AdjustmentsColumn.encode(list)))
        assertNull(AdjustmentsColumn.encode(emptyList()))
        assertEquals(emptyList<IngredientAdjustment>(), AdjustmentsColumn.decode(null))
        // Broken parts are skipped, the rest kept.
        assertEquals(listOf(IngredientAdjustment("rice", 10.0, 0.0, 0.0)), AdjustmentsColumn.decode("rice,10,0,0;Bad Id,1,2,;oil,x,1,"))
    }

    // ---- repository --------------------------------------------------------------------------------

    private var now = 1_000_000L
    private var ids = 0
    private val scopes = mutableListOf<CoroutineScope>()

    @After
    fun cancelScopes() = scopes.forEach { it.cancel() }

    private fun TestScope.repo(store: FakeFoodStore = FakeFoodStore()): FoodRepository {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher).also { scopes += it }
        return FoodRepository(store, scope, io = dispatcher, clock = { now }, newId = { "id${ids++}" })
    }

    @Test
    fun addEditDeleteAndUndo() = runTest {
        val store = FakeFoodStore()
        val repo = repo(store)
        advanceUntilIdle()
        val e = repo.addEntry(testEntry(name = "  Paella "))
        assertEquals("id0", e.id)
        assertEquals("Paella", e.name)
        assertEquals(now, e.updatedAt)
        val edited = repo.updateEntry(e.id) { it.copy(amount = 200.0) }!!
        assertEquals(now + 1, edited.updatedAt) // past the version it replaced, even at the same instant
        assertEquals(edited, repo.entries.value.single())
        assertEquals(edited, repo.deleteEntry(e.id))
        assertTrue(repo.entries.value.isEmpty())
        val tomb = store.tombstones.getValue(FoodTombstones.ENTRY to e.id)
        assertTrue("the deletion is newer than the version it deletes", tomb.deletedAt > edited.updatedAt)
        val back = repo.restoreEntry(edited)
        assertTrue(back.updatedAt > tomb.deletedAt)
        assertEquals(listOf(back), repo.entries.value)
        assertTrue("undo removes the tombstone", store.tombstones.isEmpty())
        assertNull(repo.updateEntry("missing") { it })
    }

    private fun food(id: String = "", barcode: String? = "841", updatedAt: Long = 0) =
        CustomFood(id, "Galletas", "Marca", barcode, FoodUnit.G, Nutrients(kcal = 436.0), 25.0, CustomFoodSource.OFF, updatedAt, updatedAt)

    @Test
    fun aPlatesPhotoStaysWhileAnotherFoodOfItRemains() = runTest {
        val repo = repo()
        advanceUntilIdle()
        val steak = repo.addEntry(testEntry(name = "Steak", photo = "plate.jpg"))
        repo.addEntry(testEntry(name = "Chips", photo = "plate.jpg"))
        val seen = FoodVersions(repo.entries.value.associate { it.id to it.updatedAt })
        // Another phone deleted the steak: the chips keep the photo.
        val (_, photos) = repo.applyRemoteIfUnchanged(FoodBatch(removeEntries = listOf(steak.id)), seen)
        assertTrue(photos.isEmpty())
        val chips = repo.entries.value.single()
        val (_, last) = repo.applyRemoteIfUnchanged(FoodBatch(removeEntries = listOf(chips.id)), FoodVersions(mapOf(chips.id to chips.updatedAt)))
        assertEquals(listOf("plate.jpg"), last)
    }

    @Test
    fun customFoodsAndBarcodeLookup() = runTest {
        val repo = repo()
        advanceUntilIdle()
        val f = repo.addCustomFood(food())
        now += 10
        val newer = repo.addCustomFood(food())
        assertEquals("the latest product of a barcode", newer, repo.customFoodByBarcode("841"))
        val upc = repo.addCustomFood(food(barcode = "0036000291452"))
        assertEquals("a UPC-A read as 12 digits finds its EAN-13", upc, repo.customFoodByBarcode("036000291452"))
        assertNull(repo.customFoodByBarcode("000"))
        repo.deleteCustomFood(f.id)
        assertEquals(listOf(newer, upc), repo.customFoods.value)
    }

    @Test
    fun aProductAskedAgainThatDidNotChangeKeepsItsVersionAndTheValuesTypedForIt() = runTest {
        val repo = repo()
        advanceUntilIdle()
        // A product Open Food Facts gives without kcal: the user typed them when logging it.
        val product = repo.addCustomFood(food().copy(per100 = Nutrients(salt = 0.5), checkedAt = now))
        val item = FoodItem.of(product)
        now += 10
        val typed = repo.addEntry(
            testEntry().copy(foodId = null, customFoodId = product.id, amount = 25.0, nutrients = Nutrients(kcal = 110.0, salt = 0.125), valuesEdited = true),
        )
        assertTrue(OffCache.needsRefresh(product, now))
        // Scanned again: Open Food Facts still has no kcal. Only the check time moves.
        now += 10
        val same = OffProduct("841", "Galletas", "Marca", FoodUnit.G, Nutrients(salt = 0.5), 25.0)
        val checked = repo.updateCustomFood(product.id) { OffCache.refreshed(it, same, now) }!!
        assertEquals("no new version: nothing to back up", product.updatedAt, checked.updatedAt)
        assertEquals(now, checked.checkedAt)
        val again = FoodRecall.recall(item, typed, emptyList(), { null }, foodUpdatedAt = checked.updatedAt)
        assertEquals("the kcal typed last time are kept", 440.0, again.per100!!.kcal!!, 1e-9)
        // Open Food Facts knows the kcal now: a new version, so the food's own values from now on.
        now += 10
        val changed = repo.updateCustomFood(product.id) { OffCache.refreshed(it, same.copy(per100 = Nutrients(kcal = 450.0, salt = 0.5)), now) }!!
        assertTrue(changed.updatedAt > typed.updatedAt)
        assertNull(FoodRecall.recall(FoodItem.of(changed), typed, emptyList(), { null }, foodUpdatedAt = changed.updatedAt).per100)
        // The cloud copy of the product never carries the check time; this phone's stays.
        val fromCloud = changed.copy(name = "Galletas María", checkedAt = null, updatedAt = changed.updatedAt + 100)
        repo.applyRemoteIfUnchanged(FoodBatch(upsertCustomFoods = listOf(fromCloud)), FoodVersions(customFoods = mapOf(changed.id to changed.updatedAt)))
        assertEquals(now, repo.customFood(changed.id)!!.checkedAt)
        assertEquals("Galletas María", repo.customFood(changed.id)!!.name)
    }

    @Test
    fun aProductsPhotoStaysOnThePhoneAndIsNoNewVersion() = runTest {
        val store = FakeFoodStore()
        val repo = repo(store)
        advanceUntilIdle()
        val url = "https://images.openfoodfacts.org/a.jpg"
        val product = repo.addCustomFood(food().copy(imageUrl = url))
        now += 10
        // The download finished: only this phone's photo changed, nothing to back up.
        val withPhoto = repo.updateCustomFood(product.id) { it.copy(image = "off_1.jpg") }!!
        assertEquals("no new version", product.updatedAt, withPhoto.updatedAt)
        assertEquals("off_1.jpg", store.foods.getValue(product.id).image)
        // Open Food Facts gives another address: still no new version.
        val moved = repo.updateCustomFood(product.id) { OffCache.withImage(it, "https://images.openfoodfacts.org/b.jpg") }!!
        assertEquals(product.updatedAt, moved.updatedAt)
        assertNull(moved.image)
        repo.updateCustomFood(product.id) { it.copy(imageUrl = url, image = "off_2.jpg") }
        // The user renames it: a new version, the photo stays.
        now += 10
        val renamed = repo.updateCustomFood(product.id) { it.copy(name = "Galletas María") }!!
        assertTrue(renamed.updatedAt > product.updatedAt)
        assertEquals("off_2.jpg", renamed.image)
        // A newer copy from the cloud (never carrying the photo) keeps this phone's photo and address.
        val fromCloud = renamed.copy(brand = "Otra", checkedAt = null, imageUrl = null, image = null, updatedAt = renamed.updatedAt + 100)
        repo.applyRemoteIfUnchanged(FoodBatch(upsertCustomFoods = listOf(fromCloud)), FoodVersions(customFoods = mapOf(renamed.id to renamed.updatedAt)))
        val kept = repo.customFood(product.id)!!
        assertEquals("Otra", kept.brand)
        assertEquals(url, kept.imageUrl)
        assertEquals("off_2.jpg", kept.image)
        assertEquals("off_2.jpg", store.foods.getValue(product.id).image)
        // A product new to this phone from the cloud has none yet: asked at its first scan here.
        val other = food(id = "c9", barcode = "3017620422003", updatedAt = 5)
        repo.applyRemoteIfUnchanged(FoodBatch(upsertCustomFoods = listOf(other)), FoodVersions())
        assertNull(repo.customFood("c9")!!.imageUrl)
        assertTrue(OffCache.needsImageCheck(repo.customFood("c9")!!))
    }

    @Test
    fun cloudChangesKeepThePhotoAndNeverOverwriteALocalEdit() = runTest {
        val repo = repo()
        advanceUntilIdle()
        val a = repo.addEntry(testEntry(photo = "meal_1.jpg"))
        val b = repo.addEntry(testEntry(photo = "meal_2.jpg"))
        val seen = FoodVersions(repo.entries.value.associate { it.id to it.updatedAt })
        // The user edits b while the sync is running.
        now += 5
        repo.updateEntry(b.id) { it.copy(amount = 90.0) }
        val fromCloud = a.copy(amount = 300.0, photo = null, updatedAt = a.updatedAt + 100)
        val (applied, photos) = repo.applyRemoteIfUnchanged(FoodBatch(upsertEntries = listOf(fromCloud), removeEntries = listOf(b.id)), seen)
        assertEquals(listOf(fromCloud.copy(photo = "meal_1.jpg")), applied.upsertEntries)
        assertTrue("b changed since the sync read it: left for the next sync", applied.removeEntries.isEmpty())
        assertTrue(photos.isEmpty())
        assertEquals("meal_1.jpg", repo.entry(a.id)!!.photo)
        assertEquals(300.0, repo.entry(a.id)!!.amount, 0.0)
        assertNotNull(repo.entry(b.id))
        // A removal of an unchanged entry hands back its photo to delete.
        val seen2 = FoodVersions(repo.entries.value.associate { it.id to it.updatedAt })
        val (_, gone) = repo.applyRemoteIfUnchanged(FoodBatch(removeEntries = listOf(a.id)), seen2)
        assertEquals(listOf("meal_1.jpg"), gone)
        assertNull(repo.entry(a.id))
    }

    @Test
    fun aDatabaseThatCannotOpenFailsEveryWriteAtOnce() = runTest {
        val repo = repo(FakeFoodStore().apply { failLoad = true })
        advanceUntilIdle()
        assertEquals("disk I/O error", repo.loadError.value)
        // Before: the write waited forever for a load that never comes (and the page closed as if saved).
        try {
            repo.addEntry(testEntry())
            fail("the write must fail")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("disk I/O error"))
        }
        try {
            repo.snapshot()
            fail("the backup must not read an empty log")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("food database"))
        }
    }

    @Test
    fun aPhotoTheScreensStillNeedIsNeverHandedOutForDeletion() = runTest {
        val repo = repo()
        advanceUntilIdle()
        val meal = repo.addEntry(testEntry(photo = "plate.jpg"))
        assertTrue(repo.photoInUse("plate.jpg"))
        // Deleted here with "Undo" offered (or the plate being logged again): a sync deleting it keeps the file.
        val undo = repo.registerPhotoUser { it == "plate.jpg" }
        val (applied, photos) = repo.applyRemoteIfUnchanged(FoodBatch(removeEntries = listOf(meal.id)), FoodVersions(mapOf(meal.id to meal.updatedAt)))
        assertEquals(listOf(meal.id), applied.removeEntries)
        assertTrue(photos.isEmpty())
        assertTrue(repo.photoInUse("plate.jpg"))
        undo.close()
        assertFalse(repo.photoInUse("plate.jpg"))
    }

    @Test
    fun theBackupReadsEntriesFoodsAndTombstonesTogether() = runTest {
        val repo = repo()
        advanceUntilIdle()
        val kept = repo.addEntry(testEntry(name = "Kept"))
        val gone = repo.addEntry(testEntry(name = "Gone"))
        val f = repo.addCustomFood(food())
        repo.deleteEntry(gone.id)
        val s = repo.snapshot()
        assertEquals(listOf(kept), s.entries)
        assertEquals(listOf(f), s.customFoods)
        assertEquals(listOf(gone.id), s.tombstones.map { it.id })
    }
}
