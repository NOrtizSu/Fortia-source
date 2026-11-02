package com.enjoyyourtime.globalfitness.cloud

import com.enjoyyourtime.globalfitness.food.model.CustomFood
import com.enjoyyourtime.globalfitness.food.model.FoodEntry
import com.enjoyyourtime.globalfitness.food.model.FoodTombstones
import com.enjoyyourtime.globalfitness.training.model.Tombstone as LocalTombstone

/**
 * The food log's documents of the backup, one Firestore collection each under `users/{uid}/` (new
 * collections: older app versions never read them). [tag] is the short code of the bookkeeping
 * file: never change it.
 */
enum class FoodKind(val collection: String, val tombstoneKind: String, internal val tag: String) {
    ENTRY("foodEntries", FoodTombstones.ENTRY, "e"),
    CUSTOM_FOOD("customFoods", FoodTombstones.CUSTOM_FOOD, "c"),
    ;

    companion object {
        fun ofTombstone(kind: String): FoodKind? = entries.firstOrNull { it.tombstoneKind == kind }

        internal fun ofTag(tag: String): FoodKind? = entries.firstOrNull { it.tag == tag }
    }
}

data class FoodDocKey(val kind: FoodKind, val id: String)

/** What the phone has, as the food planner sees it. */
data class FoodLocal(
    val entries: List<FoodEntry>,
    val customFoods: List<CustomFood>,
    val tombstones: List<LocalTombstone>,
)

data class FoodCloudSnapshot(val entries: RemoteDocs<FoodEntry>, val customFoods: RemoteDocs<CustomFood>) {
    companion object {
        val EMPTY = FoodCloudSnapshot(RemoteDocs.empty(), RemoteDocs.empty())
    }
}

/** Server-time cursors for incremental pulls; null reads that whole collection. */
data class FoodCursors(val entries: Long? = null, val customFoods: Long? = null) {
    companion object {
        val FULL = FoodCursors()
    }
}

/** One cloud write of the food sync. */
sealed interface FoodWrite {
    data class PutEntry(val entry: FoodEntry) : FoodWrite

    data class PutCustomFood(val food: CustomFood) : FoodWrite

    /** A tombstone: only `{deleted, deletedAt}` remains of the document. */
    data class Delete(val kind: FoodKind, val id: String, val deletedAt: Long) : FoodWrite

    val key: FoodDocKey
        get() = when (this) {
            is PutEntry -> FoodDocKey(FoodKind.ENTRY, entry.id)
            is PutCustomFood -> FoodDocKey(FoodKind.CUSTOM_FOOD, food.id)
            is Delete -> FoodDocKey(kind, id)
        }
}

data class FoodWriteResult(val confirmedDocs: Set<FoodDocKey>, val allConfirmed: Boolean) {
    companion object {
        val NOTHING = FoodWriteResult(emptySet(), allConfirmed = true)
    }
}

/** Cloud access of the food backup (Firestore on the phone, a fake in tests). */
interface FoodCloud {
    suspend fun fetchFood(uid: String, since: FoodCursors): FoodCloudSnapshot

    /** Writes in order; what the server confirmed in time (the rest stays queued offline). */
    suspend fun writeFood(uid: String, writes: List<FoodWrite>): FoodWriteResult
}

/** Where the food sync keeps its bookkeeping ([SyncStateStore] on the phone). */
interface FoodBaseStore {
    suspend fun loadFoodBase(uid: String): FoodBase?

    suspend fun saveFoodBase(uid: String, base: FoodBase)
}

/**
 * Food sync bookkeeping for one account on this phone, next to the training base in
 * `noBackupFilesDir` (never restored to another phone). Same meaning as [TrainingBase], without photos.
 */
data class FoodBase(
    val datasetKey: String,
    /** A dataset transition decided by the measurement part not completed yet (see [TrainingBase.pending]). */
    val pending: TrainingMode? = null,
    val pendingSince: Long? = null,
    val cursors: Map<FoodKind, Long> = emptyMap(),
    /** Agreed version ([FoodMerge.versionOf]) or deletion ([TrainingMerge.dead]) of each document. */
    val entries: Map<FoodDocKey, String> = emptyMap(),
    /** Documents written by a newer app version: never overwritten by this one. */
    val readOnly: Set<FoodDocKey> = emptySet(),
) {
    fun trustedFor(datasetId: String): Boolean = pending == null && datasetId.isNotEmpty() && datasetKey == datasetId

    fun cursorsOrFull(trusted: Boolean): FoodCursors =
        if (!trusted) FoodCursors.FULL else FoodCursors(cursors[FoodKind.ENTRY], cursors[FoodKind.CUSTOM_FOOD])
}

/** Plain-text, line-based encoding of [FoodBase] (same style as [TrainingBaseCodec]). */
object FoodBaseCodec {
    private const val VERSION = "1"
    private const val SEP = '\t'

    fun encode(base: FoodBase): String = buildString {
        line("v", VERSION)
        line("dataset", base.datasetKey)
        base.pending?.let { pending ->
            if (base.pendingSince != null) line("pending", pending.code, base.pendingSince.toString()) else line("pending", pending.code)
        }
        for ((kind, at) in base.cursors) line("cursor", kind.tag, at.toString())
        for ((key, version) in base.entries) line("e", key.kind.tag, key.id, version)
        for (key in base.readOnly) line("ro", key.kind.tag, key.id)
    }

    /** Null for an unknown version or a file without dataset (the next sync reads everything). */
    fun decode(text: String): FoodBase? {
        var version: String? = null
        var dataset: String? = null
        var pending: TrainingMode? = null
        var pendingSince: Long? = null
        val cursors = HashMap<FoodKind, Long>()
        val entries = HashMap<FoodDocKey, String>()
        val readOnly = HashSet<FoodDocKey>()
        for (raw in text.lineSequence()) {
            if (raw.isBlank()) continue
            val parts = raw.split(SEP)
            when (parts[0]) {
                "v" -> version = parts.getOrNull(1)
                "dataset" -> dataset = parts.getOrNull(1)
                "pending" -> {
                    pending = TrainingMode.fromCode(parts.getOrNull(1))
                    pendingSince = parts.getOrNull(2)?.toLongOrNull()
                }
                "cursor" -> {
                    val kind = parts.getOrNull(1)?.let(FoodKind::ofTag)
                    val at = parts.getOrNull(2)?.toLongOrNull()
                    if (kind != null && at != null) cursors[kind] = at
                }
                "e" -> {
                    val kind = parts.getOrNull(1)?.let(FoodKind::ofTag)
                    if (kind != null && parts.size >= 4) entries[FoodDocKey(kind, parts[2])] = parts[3]
                }
                "ro" -> {
                    val kind = parts.getOrNull(1)?.let(FoodKind::ofTag)
                    if (kind != null && parts.size >= 3) readOnly += FoodDocKey(kind, parts[2])
                }
            }
        }
        if (version != VERSION || dataset.isNullOrEmpty()) return null
        return FoodBase(dataset, pending, pendingSince, cursors, entries, readOnly)
    }

    private fun StringBuilder.line(vararg fields: String) {
        fields.joinTo(this, SEP.toString()) { it.replace(SEP, ' ').replace('\n', ' ').replace('\r', ' ') }
        append('\n')
    }
}
