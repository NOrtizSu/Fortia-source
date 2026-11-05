package com.enjoyyourtime.globalfitness.food.pack

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/**
 * The embeddings of the user's scanned products, for "products you already scanned": one file in
 * `noBackupFilesDir` (never in the cloud backup nor in Android's backup or device transfer; a phone
 * restored from a backup embeds the product photos again), read the first time the matching is used
 * (not at app start), as float16 (1.9 KB per 960-value embedding). Each product keeps its Open Food
 * Facts front photo's embedding and up to [PackMath.MAX_CONFIRMED] of the user's own photos logged as
 * it (their vectors only, with the name of the meal photo they came from).
 *
 * All embeddings belong to one [space] (embedding_contract.md): another space (a new model) keeps
 * which photos to embed again and drops every vector. Changes are written at once, except those made
 * with `persist = false` (a batch: written by [flush]). Thread-safe; blocking (call on IO).
 */
class ProductEmbeddings(file: () -> File) {
    /** A photo of the user's logged as the product: its meal photo file (null: unknown) and its vector (null: to embed again). */
    class Confirmed(val photo: String?, val vector: FloatArray?)

    /** What is kept for one product. */
    class Stored(val frontImage: String?, val front: FloatArray?, val confirmed: List<Confirmed>) {
        fun refs(): ProductRefs = ProductRefs(frontImage, front, confirmed.mapNotNull { it.vector })
    }

    private val file by lazy(file)
    private var space: String? = null
    private var products: MutableMap<String, Stored>? = null

    /** Changes not written yet (a batch). */
    private var dirty = false

    /** Whether the file was read already (the matching was used since the app started). */
    val loaded: Boolean
        @Synchronized get() = products != null

    /** Every product with at least one vector of [space]. */
    @Synchronized
    fun refs(space: String): Map<String, ProductRefs> = load(space).mapValues { it.value.refs() }.filterValues { !it.isEmpty }

    /** What is kept of [productId] in [space] (null: nothing). */
    @Synchronized
    fun stored(space: String, productId: String): Stored? = load(space)[productId]

    /** The products whose front photo in [images] (product id → its photo file, null: none) was not embedded in [space] yet. */
    @Synchronized
    fun frontsToEmbed(space: String, images: Map<String, String?>): List<Pair<String, String>> {
        val all = load(space)
        return images.mapNotNull { (id, image) -> image?.takeIf { all[id]?.frontImage != it }?.let { id to it } }
    }

    /** The user's photos to embed again in [space] (after a change of model): product id → meal photo. */
    @Synchronized
    fun confirmedToEmbed(space: String): List<Pair<String, String>> =
        load(space).flatMap { (id, s) -> s.confirmed.filter { it.vector == null && it.photo != null }.map { id to it.photo!! } }

    /** The front photo [image] of [productId] and its [vector] (null: the photo has no usable embedding; not tried again). */
    @Synchronized
    fun setFront(space: String, productId: String, image: String, vector: FloatArray?, persist: Boolean = true) {
        val all = load(space)
        val s = all[productId]
        all[productId] = Stored(image, vector, s?.confirmed.orEmpty())
        changed(persist)
    }

    /** One more photo of the user's logged as [productId] (the newest [cap] are kept; the same meal photo again replaces it). */
    @Synchronized
    fun addConfirmed(space: String, productId: String, photo: String?, vector: FloatArray, cap: Int = PackMath.MAX_CONFIRMED) {
        val all = load(space)
        val s = all[productId]
        val others = s?.confirmed.orEmpty().filter { photo == null || it.photo != photo }
        all[productId] = Stored(s?.frontImage, s?.front, (others + Confirmed(photo, vector)).takeLast(cap.coerceAtLeast(1)))
        changed(true)
    }

    /** The vector of a photo embedded again in [space]; null when it is gone or gave none (then it is dropped). */
    @Synchronized
    fun setConfirmed(space: String, productId: String, photo: String, vector: FloatArray?, persist: Boolean = true) {
        val all = load(space)
        val s = all[productId] ?: return
        val confirmed = s.confirmed.mapNotNull { c ->
            when {
                c.photo != photo || c.vector != null -> c
                vector != null -> Confirmed(photo, vector)
                else -> null
            }
        }
        all[productId] = Stored(s.frontImage, s.front, confirmed)
        changed(persist)
    }

    /**
     * Keeps only the products of [images] (product id → its front photo file, null: none): a product
     * deleted (here or by the cloud backup) loses everything, a product whose photo changed loses that
     * photo's vector (it is embedded again), and its own photos stay. Without [load], nothing happens
     * while the file was not read yet (it is checked again at first use).
     */
    @Synchronized
    fun retain(images: Map<String, String?>, load: Boolean = true) {
        val all = products ?: (if (load) loadAny() else null) ?: return
        var removed = false
        val it = all.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            val s = entry.value
            if (entry.key !in images) {
                it.remove()
                removed = true
            } else if (s.frontImage != null && s.frontImage != images[entry.key]) {
                if (s.confirmed.isEmpty()) it.remove() else entry.setValue(Stored(null, null, s.confirmed))
                removed = true
            }
        }
        if (removed) changed(true)
    }

    /** Writes the changes of a batch (`persist = false`), if any. */
    @Synchronized
    fun flush() {
        if (dirty) save()
    }

    private fun changed(persist: Boolean) {
        dirty = true
        if (persist) save()
    }

    /** The products of [space], read from the file the first time (another space: vectors dropped, photos kept to embed again). */
    private fun load(space: String): MutableMap<String, Stored> {
        val all = products ?: loadAny() ?: mutableMapOf<String, Stored>().also { products = it; this.space = space }
        if (this.space != space) {
            val again = all.mapValues { (_, s) -> Stored(null, null, s.confirmed.filter { it.photo != null }.map { Confirmed(it.photo, null) }) }
                .filterValues { it.confirmed.isNotEmpty() }
            all.clear()
            all.putAll(again)
            this.space = space
            changed(true)
        }
        return all
    }

    /** The file's products (and their space), or null when there is no file; an unreadable file counts as empty. */
    private fun loadAny(): MutableMap<String, Stored>? {
        if (!file.isFile) return null
        val read = try {
            DataInputStream(FileInputStream(file).buffered()).use(::read)
        } catch (e: IOException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
        if (read == null) {
            file.delete()
            return null
        }
        space = read.first
        return read.second.also { products = it }
    }

    private fun save() {
        val all = products ?: return
        val tmp = File(file.path + ".tmp")
        try {
            file.parentFile?.mkdirs()
            DataOutputStream(FileOutputStream(tmp).buffered()).use { out -> write(out, space.orEmpty(), all) }
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
            dirty = false
        } catch (e: IOException) {
            // Kept in memory; written again at the next change.
            tmp.delete()
        }
    }

    companion object {
        private const val MAGIC = 0x46504532 // "FPE2": float16 vectors
        private const val MAX_DIM = 8192
        private const val MAX_PRODUCTS = 100_000

        internal fun write(out: DataOutputStream, space: String, products: Map<String, Stored>) {
            out.writeInt(MAGIC)
            out.writeUTF(space)
            out.writeInt(products.size)
            for ((id, s) in products) {
                out.writeUTF(id)
                writeText(out, s.frontImage)
                writeVector(out, s.front)
                out.writeInt(s.confirmed.size)
                for (c in s.confirmed) {
                    writeText(out, c.photo)
                    writeVector(out, c.vector)
                }
            }
        }

        /** The space and products written by [write]; throws [IOException] / [IllegalArgumentException] for anything else. */
        internal fun read(input: DataInputStream): Pair<String, MutableMap<String, Stored>> {
            try {
                if (input.readInt() != MAGIC) throw IOException("not an embeddings file")
                val space = input.readUTF()
                val n = input.readInt()
                require(n in 0..MAX_PRODUCTS) { "bad count" }
                val out = LinkedHashMap<String, Stored>()
                repeat(n) {
                    val id = input.readUTF()
                    val image = readText(input)
                    val front = readVector(input)
                    val c = input.readInt()
                    require(c in 0..PackMath.MAX_CONFIRMED * 4) { "bad count" }
                    val confirmed = List(c) { Confirmed(readText(input), readVector(input)) }
                    out[id] = Stored(image, front, confirmed)
                }
                if (input.read() != -1) throw IOException("trailing bytes")
                return space to out
            } catch (e: EOFException) {
                throw IOException("cut short", e)
            }
        }

        private fun writeText(out: DataOutputStream, s: String?) {
            out.writeBoolean(s != null)
            if (s != null) out.writeUTF(s)
        }

        private fun readText(input: DataInputStream): String? = if (input.readBoolean()) input.readUTF() else null

        private fun writeVector(out: DataOutputStream, v: FloatArray?) {
            out.writeInt(v?.size ?: -1)
            v?.forEach { out.writeShort(toHalf(it).toInt()) }
        }

        private fun readVector(input: DataInputStream): FloatArray? {
            val n = input.readInt()
            if (n == -1) return null
            require(n in 1..MAX_DIM) { "bad size" }
            return FloatArray(n) { fromHalf(input.readShort()) }
        }

        /** [f] as an IEEE 754 half-precision value (round to nearest, ties to even). */
        internal fun toHalf(f: Float): Short {
            val bits = java.lang.Float.floatToRawIntBits(f)
            val sign = (bits ushr 16) and 0x8000
            val exp = (bits ushr 23) and 0xFF
            val mant = bits and 0x7FFFFF
            if (exp == 0xFF) return (sign or 0x7C00 or (if (mant != 0) 0x200 else 0)).toShort()
            val e = exp - 127 + 15
            if (e >= 0x1F) return (sign or 0x7C00).toShort()
            if (e <= 0) {
                if (e < -10) return sign.toShort()
                val m = mant or 0x800000
                val shift = 14 - e
                var half = m ushr shift
                val rest = m and ((1 shl shift) - 1)
                val halfway = 1 shl (shift - 1)
                if (rest > halfway || rest == halfway && half and 1 == 1) half++
                return (sign or half).toShort()
            }
            var half = (e shl 10) or (mant ushr 13)
            val rest = mant and 0x1FFF
            // A carry into the exponent is right (it rounds up to the next power of two, or to infinity).
            if (rest > 0x1000 || rest == 0x1000 && half and 1 == 1) half++
            return (sign or half).toShort()
        }

        /** The float of an IEEE 754 half-precision value. */
        internal fun fromHalf(h: Short): Float {
            val x = h.toInt() and 0xFFFF
            val negative = x and 0x8000 != 0
            val exp = (x ushr 10) and 0x1F
            val mant = x and 0x3FF
            val v = when (exp) {
                0 -> mant * SUBNORMAL
                0x1F -> if (mant == 0) Float.POSITIVE_INFINITY else Float.NaN
                else -> java.lang.Float.intBitsToFloat(((exp - 15 + 127) shl 23) or (mant shl 13))
            }
            return if (negative) -v else v
        }

        /** 2^-24: a half-precision subnormal's step. */
        private const val SUBNORMAL = 5.9604645E-8f
    }
}
