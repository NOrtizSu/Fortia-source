package com.enjoyyourtime.globalfitness.cloud

import com.enjoyyourtime.globalfitness.cloud.FirestoreCodec.Decoded
import com.enjoyyourtime.globalfitness.cloud.FirestoreCodec.F_DELETED_AT
import com.enjoyyourtime.globalfitness.cloud.FirestoreCodec.F_UPDATED_AT
import com.enjoyyourtime.globalfitness.cloud.FirestoreCodec.USERS
import com.google.android.gms.tasks.Task
import com.google.firebase.Timestamp
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Date

/**
 * Firestore access for the food log's part of the backup (layout in [FoodCodec]), on the same
 * Firestore instance as the rest. Reads go to the server; writes are batched and, when the
 * confirmation does not arrive in time, stay queued in the offline cache (as for the training data).
 */
class FirestoreFoodRemote internal constructor(
    private val firestore: () -> FirebaseFirestore,
    private val compute: CoroutineDispatcher = Dispatchers.Default,
) : FoodCloud {
    private val db: FirebaseFirestore get() = firestore()

    private fun collection(uid: String, kind: FoodKind): CollectionReference = db.collection(USERS).document(uid).collection(kind.collection)

    override suspend fun fetchFood(uid: String, since: FoodCursors): FoodCloudSnapshot = coroutineScope {
        val entries = async { read(uid, FoodKind.ENTRY, since.entries, FoodCodec::entryFromMap) }
        val foods = async { read(uid, FoodKind.CUSTOM_FOOD, since.customFoods, FoodCodec::customFoodFromMap) }
        FoodCloudSnapshot(entries.await(), foods.await())
    }

    private suspend fun <T> read(uid: String, kind: FoodKind, since: Long?, decode: (String, Map<String, Any?>, Long?) -> Decoded<T>?): RemoteDocs<T> {
        val query: Query = if (since == null) {
            collection(uid, kind)
        } else {
            collection(uid, kind).whereGreaterThan(F_UPDATED_AT, Timestamp(Date(since - FirestoreBackupRemote.PULL_OVERLAP_MS)))
        }
        val snapshot = query.get(Source.SERVER).await()
        return withContext(compute) {
            val docs = snapshot.documents.map { TrainingCodec.RawDoc(it.id, it.data.orEmpty(), it.getTimestamp(F_UPDATED_AT)?.toDate()?.time) }
            FoodCodec.collect(docs, complete = since == null, decode = decode)
        }
    }

    override suspend fun writeFood(uid: String, writes: List<FoodWrite>): FoodWriteResult {
        var allConfirmed = true
        var waiting = true
        val confirmed = HashSet<FoodDocKey>()
        for (chunk in writes.chunked(MAX_DOCS_PER_BATCH)) {
            val batch = withContext(compute) {
                db.batch().apply {
                    for (w in chunk) {
                        val ref = collection(uid, w.key.kind).document(TrainingCodec.docId(w.key.id))
                        when (w) {
                            is FoodWrite.PutEntry -> set(ref, FoodCodec.entryToMap(w.entry).stamped() + (F_DELETED_AT to FieldValue.delete()), SetOptions.merge())
                            is FoodWrite.PutCustomFood -> set(ref, FoodCodec.customFoodToMap(w.food).stamped() + (F_DELETED_AT to FieldValue.delete()), SetOptions.merge())
                            // Tombstones replace the whole document: nothing of a deleted meal stays in the cloud.
                            is FoodWrite.Delete -> set(ref, FoodCodec.tombstoneToMap(w.deletedAt).stamped())
                        }
                    }
                }
            }
            // Once one commit is not confirmed in time, the following ones are queued without waiting.
            val ok = waiting && confirmedInTime(batch.commit())
            if (ok) chunk.mapTo(confirmed) { it.key } else {
                waiting = false
                allConfirmed = false
            }
        }
        return FoodWriteResult(confirmed, allConfirmed)
    }

    /** Deletes every food document of [uid] (account deletion). Throws if the server does not confirm. */
    suspend fun deleteAll(uid: String) {
        for (kind in FoodKind.entries) {
            while (true) {
                val page = collection(uid, kind).limit(DELETE_PAGE.toLong()).get(Source.SERVER).await()
                if (page.isEmpty) break
                val batch = db.batch()
                for (doc in page.documents) batch.delete(doc.reference)
                if (!confirmedInTime(batch.commit())) throw CloudTimeoutException()
            }
        }
    }

    private suspend fun <T> confirmedInTime(task: Task<T>): Boolean = withTimeoutOrNull(FirestoreBackupRemote.COMMIT_TIMEOUT_MS) {
        task.await()
        true
    } ?: false

    private fun Map<String, Any?>.stamped(): Map<String, Any?> = this + (F_UPDATED_AT to FieldValue.serverTimestamp())

    private companion object {
        /** Entries are a few hundred bytes; stay far below Firestore's 500 writes per batch. */
        const val MAX_DOCS_PER_BATCH = 200
        const val DELETE_PAGE = 300
    }
}
