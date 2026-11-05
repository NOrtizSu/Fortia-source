package com.enjoyyourtime.globalfitness.food

import com.enjoyyourtime.globalfitness.food.data.ProductPhotos
import com.enjoyyourtime.globalfitness.food.model.CustomFood
import com.enjoyyourtime.globalfitness.food.model.CustomFoodSource
import com.enjoyyourtime.globalfitness.food.model.FoodUnit
import com.enjoyyourtime.globalfitness.food.model.Nutrients
import com.enjoyyourtime.globalfitness.training.data.ExercisePhotos
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The product photos' files: one per product, deleted with it or when it gets another one. */
@OptIn(ExperimentalCoroutinesApi::class)
class ProductPhotosTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun photos(): Pair<ProductPhotos, File> {
        val dir = File(folder.root, ProductPhotos.DIR)
        return ProductPhotos { dir } to dir
    }

    private fun File.write(name: String, modified: Long = 0L): File = File(this, name).apply {
        parentFile!!.mkdirs()
        writeBytes(byteArrayOf(1, 2, 3))
        if (modified > 0) setLastModified(modified)
    }

    private fun product(id: String, image: String?) =
        CustomFood(id, "Nutella", null, "3017620422003", FoodUnit.G, Nutrients(kcal = 539.0), 15.0, CustomFoodSource.OFF, 1, 2, imageUrl = "https://images.openfoodfacts.org/$id.jpg", image = image)

    @Test
    fun existsAndDeleteOnlyTakeFileNames() {
        val (photos, dir) = photos()
        dir.write("off_1.jpg")
        val outside = folder.root.resolve("food.db").apply { writeBytes(byteArrayOf(9)) }
        assertTrue(photos.exists("off_1.jpg"))
        assertFalse(photos.exists("off_2.jpg"))
        assertFalse("a path is never a photo", photos.exists("../food.db"))
        photos.delete("../food.db")
        photos.delete("..\\food.db")
        photos.delete("")
        photos.delete(null)
        assertTrue("nothing outside the folder is deleted", outside.isFile)
        photos.delete("off_1.jpg")
        assertFalse(dir.resolve("off_1.jpg").exists())
    }

    @Test
    fun theCleanUpKeepsUsedAndRecentFiles() {
        val (photos, dir) = photos()
        val now = 10_000_000_000L
        dir.write("off_used.jpg", modified = now - 10 * HOUR)
        dir.write("off_old.jpg", modified = now - 10 * HOUR)
        dir.write("off_new.jpg", modified = now - 1 * HOUR)
        photos.deleteUnused(setOf("off_used.jpg"), graceMs = 2 * HOUR, now = now)
        assertEquals(setOf("off_used.jpg", "off_new.jpg"), dir.list()!!.toSet())
    }

    @Test
    fun aPhotoGoesWithItsProductOrWhenReplaced() = runTest {
        val (photos, dir) = photos()
        listOf("off_a.jpg", "off_b.jpg", "off_b2.jpg").forEach { dir.write(it) }
        val products = MutableStateFlow(emptyList<CustomFood>())
        val job = launch { photos.deleteDropped(products) }
        runCurrent()
        products.value = listOf(product("a", "off_a.jpg"), product("b", "off_b.jpg"))
        runCurrent()
        assertEquals("nothing dropped yet", setOf("off_a.jpg", "off_b.jpg", "off_b2.jpg"), dir.list()!!.toSet())
        // b gets another photo (a new address, downloaded again).
        products.value = listOf(product("a", "off_a.jpg"), product("b", null))
        runCurrent()
        products.value = listOf(product("a", "off_a.jpg"), product("b", "off_b2.jpg"))
        runCurrent()
        assertEquals(setOf("off_a.jpg", "off_b2.jpg"), dir.list()!!.toSet())
        // a is deleted (here, or by the cloud backup).
        products.value = listOf(product("b", "off_b2.jpg"))
        runCurrent()
        assertEquals(setOf("off_b2.jpg"), dir.list()!!.toSet())
        job.cancel()
    }

    @Test
    fun photosAreStoredAtMost400PxOnTheirLongSide() {
        assertEquals("Open Food Facts' 400 px photo stays as it is", 336 to 400, ProductPhotos.targetSize(336, 400))
        assertEquals("a small one too", 200 to 150, ProductPhotos.targetSize(200, 150))
        assertEquals(400 to 300, ProductPhotos.targetSize(800, 600))
        assertEquals(300 to 400, ProductPhotos.targetSize(1200, 1600))
        assertEquals("a long thin one keeps 1 px", 1 to 400, ProductPhotos.targetSize(2, 5000))
        assertEquals(400 to 400, ProductPhotos.targetSize(401, 401))
        assertNull("no size: not an image", ProductPhotos.targetSize(0, 300))
        assertNull(ProductPhotos.targetSize(300, -1))
        assertEquals("another limit", 100 to 50, ProductPhotos.targetSize(400, 200, maxSide = 100))
        // The photo is first decoded at a power-of-two fraction that never goes below the stored size.
        assertEquals(1, ExercisePhotos.sampleSize(336, 400, ProductPhotos.MAX_SIDE_PX))
        for (side in listOf(400, 799, 800, 1600, 4000, 12000)) {
            val sample = ExercisePhotos.sampleSize(side, side / 2, ProductPhotos.MAX_SIDE_PX)
            assertTrue("$side px decoded at 1/$sample", side / sample >= ProductPhotos.MAX_SIDE_PX)
        }
    }

    @Test
    fun bytesThatAreNotAnImageAreNotStored() {
        val (photos, dir) = photos()
        assertNull(photos.store("<html>".toByteArray()))
        assertTrue("no file left behind", dir.list().isNullOrEmpty())
    }

    private companion object {
        const val HOUR = 60 * 60_000L
    }
}
