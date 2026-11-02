package com.enjoyyourtime.globalfitness.cloud

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Persists the sync bookkeeping in `noBackupFilesDir`, which Android never backs up or transfers:
 * after a restore or on a new phone the engine starts without a base, so it merges instead of
 * mistaking the missing history for deletions.
 */
class SyncStateStore(context: Context) : TrainingBaseStore, FoodBaseStore {
    private val dir = File(context.noBackupFilesDir, "cloud_sync")

    suspend fun loadBase(uid: String): SyncBase? = read(baseFile(uid))?.let(SyncStateCodec::decodeBase)

    suspend fun saveBase(uid: String, base: SyncBase) = write(baseFile(uid), SyncStateCodec.encodeBase(base))

    /** Forgets what was synced for [uid]: measurements, training and food (the next sync reads everything). */
    suspend fun clearBase(uid: String) = withContext(Dispatchers.IO) {
        baseFile(uid).delete()
        trainingFile(uid).delete()
        foodFile(uid).delete()
        Unit
    }

    override suspend fun loadTrainingBase(uid: String): TrainingBase? = read(trainingFile(uid))?.let(TrainingBaseCodec::decode)

    override suspend fun saveTrainingBase(uid: String, base: TrainingBase) = write(trainingFile(uid), TrainingBaseCodec.encode(base))

    private fun trainingFile(uid: String) = File(dir, "training_${FirestoreCodec.safeDocId(uid)}.txt")

    override suspend fun loadFoodBase(uid: String): FoodBase? = read(foodFile(uid))?.let(FoodBaseCodec::decode)

    override suspend fun saveFoodBase(uid: String, base: FoodBase) = write(foodFile(uid), FoodBaseCodec.encode(base))

    private fun foodFile(uid: String) = File(dir, "food_${FirestoreCodec.safeDocId(uid)}.txt")

    /** A cloud deletion started for [uid] and has not finished: sync stays paused until it does. */
    suspend fun isDeletionPending(uid: String): Boolean = withContext(Dispatchers.IO) { pendingFile(uid).isFile }

    suspend fun setDeletionPending(uid: String, pending: Boolean) = withContext(Dispatchers.IO) {
        if (pending) {
            dir.mkdirs()
            pendingFile(uid).writeText("1")
        } else {
            pendingFile(uid).delete()
        }
        Unit
    }

    private fun pendingFile(uid: String) = File(dir, "deletion_pending_${FirestoreCodec.safeDocId(uid)}")

    suspend fun loadStamps(): EditStamps =
        read(File(dir, STAMPS_FILE))?.let(SyncStateCodec::decodeStamps) ?: EditStamps(null, null)

    suspend fun saveStamps(stamps: EditStamps) = write(File(dir, STAMPS_FILE), SyncStateCodec.encodeStamps(stamps))

    private fun baseFile(uid: String) = File(dir, "base_${FirestoreCodec.safeDocId(uid)}.txt")

    private suspend fun read(file: File): String? = withContext(Dispatchers.IO) {
        if (file.isFile) file.readText() else null
    }

    /** Write-then-rename, so a crash never leaves a half-written file. */
    private suspend fun write(file: File, text: String) = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val tmp = File(dir, "${file.name}.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
        Unit
    }

    private companion object {
        const val STAMPS_FILE = "edit_stamps.txt"
    }
}
