package com.enjoyyourtime.globalfitness.cloud

import com.enjoyyourtime.globalfitness.cloud.FirestoreCodec.DEVICES
import com.enjoyyourtime.globalfitness.cloud.FirestoreCodec.Decoded
import com.enjoyyourtime.globalfitness.cloud.FirestoreCodec.F_DELETED
import com.enjoyyourtime.globalfitness.cloud.FirestoreCodec.F_DELETED_AT
import com.enjoyyourtime.globalfitness.cloud.FirestoreCodec.F_PREMIUM
import com.enjoyyourtime.globalfitness.cloud.FirestoreCodec.F_UPDATED_AT
import com.enjoyyourtime.globalfitness.cloud.FirestoreCodec.MEASUREMENTS
import com.enjoyyourtime.globalfitness.cloud.FirestoreCodec.META
import com.enjoyyourtime.globalfitness.cloud.FirestoreCodec.PROFILE_DOC
import com.enjoyyourtime.globalfitness.cloud.FirestoreCodec.PROFILE_FIELDS
import com.enjoyyourtime.globalfitness.cloud.FirestoreCodec.SETTINGS_DOC
import com.enjoyyourtime.globalfitness.cloud.FirestoreCodec.USERS
import com.google.android.gms.tasks.Task
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.PersistentCacheSettings
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Date

/** Thrown when the server did not confirm an operation in time. */
class CloudTimeoutException : Exception("timeout")

/**
 * Firestore access for the backup tree `users/{uid}/...` (see [FirestoreCodec]). Reads always go to
 * the server, so a sync never merges against a stale offline cache. Measurement uploads run in
 * transactions that never overwrite a newer tombstone; the other writes are batched and, if the
 * confirmation does not arrive in time, stay queued in the persistent offline cache.
 */
class FirestoreBackupRemote {
    private val db: FirebaseFirestore by lazy {
        FirebaseFirestore.getInstance().also { firestore ->
            firestore.firestoreSettings = FirebaseFirestoreSettings.Builder()
                .setLocalCacheSettings(PersistentCacheSettings.newBuilder().build())
                .build()
        }
    }

    /** Workouts, custom exercises, machine notes and photos, routines, on the same Firestore instance. */
    val training: FirestoreTrainingRemote by lazy { FirestoreTrainingRemote(firestore = { db }) }

    /** The food log (meal entries, custom foods), on the same Firestore instance. */
    val food: FirestoreFoodRemote by lazy { FirestoreFoodRemote(firestore = { db }) }

    private fun user(uid: String) = db.collection(USERS).document(uid)
    private fun profileRef(uid: String) = user(uid).collection(META).document(PROFILE_DOC)
    private fun settingsRef(uid: String) = user(uid).collection(META).document(SETTINGS_DOC)
    private fun measurementRef(uid: String, id: String): DocumentReference = user(uid).collection(MEASUREMENTS).document(id)
    private fun deviceRef(uid: String, id: String): DocumentReference = user(uid).collection(DEVICES).document(id)

    /**
     * Reads the profile, settings, all devices and the measurements changed since [since] (all if
     * null). When the profile document is missing it also probes whether any measurement exists.
     */
    suspend fun fetch(uid: String, since: Long?): RemoteSnapshot = coroutineScope {
        val profile = async { profileRef(uid).get(Source.SERVER).await() }
        val settings = async { settingsRef(uid).get(Source.SERVER).await() }
        val devices = async { user(uid).collection(DEVICES).get(Source.SERVER).await() }
        val measurementsQuery: Query = if (since == null) {
            user(uid).collection(MEASUREMENTS)
        } else {
            user(uid).collection(MEASUREMENTS).whereGreaterThan(F_UPDATED_AT, Timestamp(Date(since - PULL_OVERLAP_MS)))
        }
        val measurements = async { measurementsQuery.get(Source.SERVER).await() }

        val liveMeasurements = ArrayList<MeasurementRecord>()
        val deadMeasurements = ArrayList<Tombstone>()
        var maxUpdatedAt: Long? = null
        val measurementDocs = measurements.await().documents
        for (doc in measurementDocs) {
            val updatedAt = doc.updatedAtMillis()
            if (updatedAt != null) maxUpdatedAt = maxOf(maxUpdatedAt ?: updatedAt, updatedAt)
            when (val decoded = FirestoreCodec.measurementFromMap(doc.id, doc.data.orEmpty(), updatedAt)) {
                is Decoded.Live -> liveMeasurements += decoded.record
                is Decoded.Dead -> deadMeasurements += decoded.tombstone
                null -> Unit
            }
        }
        val liveDevices = ArrayList<DeviceRecord>()
        val deadDevices = ArrayList<Tombstone>()
        for (doc in devices.await().documents) {
            when (val decoded = FirestoreCodec.deviceFromMap(doc.id, doc.data.orEmpty(), doc.updatedAtMillis())) {
                is Decoded.Live -> liveDevices += decoded.record
                is Decoded.Dead -> deadDevices += decoded.tombstone
                null -> Unit
            }
        }
        val profileSnapshot = profile.await()
        val profileDoc = FirestoreCodec.profileDocFromMap(
            profileSnapshot.data.takeIf { profileSnapshot.exists() },
            profileSnapshot.updatedAtMillis(),
        )
        val anyMeasurementDoc = when {
            measurementDocs.isNotEmpty() -> true
            profileDoc != RemoteProfileDoc.Absent -> null
            else -> !user(uid).collection(MEASUREMENTS).limit(1).get(Source.SERVER).await().isEmpty
        }
        val settingsDoc = settings.await()
        RemoteSnapshot(
            profileDoc = profileDoc,
            settings = settingsDoc.data?.let { FirestoreCodec.settingsFromMap(it, settingsDoc.updatedAtMillis()) },
            measurements = liveMeasurements,
            measurementTombstones = deadMeasurements,
            devices = liveDevices,
            deviceTombstones = deadDevices,
            complete = since == null,
            maxUpdatedAt = maxUpdatedAt,
            anyMeasurementDoc = anyMeasurementDoc,
        )
    }

    /**
     * Uploads [writes]. Measurement puts go through transactions (never over a newer tombstone,
     * unless forced); everything else in batches of at most [MAX_BATCH_WRITES]. Returns true when
     * the server confirmed everything, false when some batch is still queued offline.
     */
    suspend fun write(uid: String, writes: List<CloudWrite>): Boolean {
        val (guardedPuts, batched) = writes.partition { it is CloudWrite.PutMeasurement && !it.force }
        for (chunk in guardedPuts.filterIsInstance<CloudWrite.PutMeasurement>().chunked(TRANSACTION_PUTS)) {
            putMeasurementsGuarded(uid, chunk)
        }

        var allConfirmed = true
        for (chunk in batched.chunked(MAX_BATCH_WRITES)) {
            val batch = db.batch()
            for (w in chunk) {
                when (w) {
                    is CloudWrite.PutProfile -> {
                        val data = FirestoreCodec.profileToMap(w.record, w.datasetId).stamped() + (F_DELETED_AT to FieldValue.delete())
                        batch.set(profileRef(uid), data, SetOptions.mergeFields(PROFILE_FIELDS + F_UPDATED_AT))
                    }
                    is CloudWrite.DeleteProfile -> {
                        val cleared = PROFILE_FIELDS.associateWith { FieldValue.delete() }
                        val data = cleared + FirestoreCodec.profileTombstoneToMap(w.deletedAt, w.datasetId).stamped()
                        batch.set(profileRef(uid), data, SetOptions.mergeFields(PROFILE_FIELDS + F_UPDATED_AT))
                    }
                    is CloudWrite.PutSettings -> {
                        // mergeFields replaces the whole "values" map, so removed keys disappear too.
                        val data = FirestoreCodec.settingsToMap(w.record).stamped()
                        batch.set(settingsRef(uid), data, SetOptions.mergeFields(data.keys.toList()))
                    }
                    is CloudWrite.PutMeasurement -> {
                        val data = FirestoreCodec.measurementToMap(w.record).stamped() + (F_DELETED_AT to FieldValue.delete())
                        batch.set(measurementRef(uid, w.record.id), data, SetOptions.merge())
                    }
                    is CloudWrite.DeleteMeasurement ->
                        batch.set(measurementRef(uid, w.tombstone.id), FirestoreCodec.tombstoneToMap(w.tombstone).stamped())
                    is CloudWrite.PutDevice -> {
                        val data = FirestoreCodec.deviceToMap(w.record).stamped() + (F_DELETED_AT to FieldValue.delete())
                        batch.set(deviceRef(uid, w.record.id), data, SetOptions.mergeFields(data.keys.toList()))
                    }
                    is CloudWrite.DeleteDevice ->
                        batch.set(deviceRef(uid, w.tombstone.id), FirestoreCodec.tombstoneToMap(w.tombstone).stamped())
                }
            }
            if (!confirmedInTime(batch.commit())) allConfirmed = false
        }
        return allConfirmed
    }

    /** One transaction per chunk: a live put never replaces a tombstone newer than the record. */
    private suspend fun putMeasurementsGuarded(uid: String, puts: List<CloudWrite.PutMeasurement>) {
        val task = db.runTransaction { tx ->
            val existing = puts.map { tx.get(measurementRef(uid, it.record.id)) }
            puts.forEachIndexed { i, put ->
                val doc = existing[i]
                val deletedAt = if (doc.exists() && doc.getBoolean(F_DELETED) == true) doc.getLong(F_DELETED_AT) ?: 0L else null
                if (deletedAt == null || put.record.updatedAt > deletedAt) {
                    val data = FirestoreCodec.measurementToMap(put.record).stamped() + (F_DELETED_AT to FieldValue.delete())
                    tx.set(measurementRef(uid, put.record.id), data, SetOptions.merge())
                }
            }
            null
        }
        if (!confirmedInTime(task)) throw CloudTimeoutException()
    }

    /** Informational only: Google Play stays the source of truth for the purchase. */
    suspend fun markPremium(uid: String): Boolean = confirmedInTime(
        profileRef(uid).set(
            mapOf(F_PREMIUM to true, "premiumUpdatedAt" to FieldValue.serverTimestamp()),
            SetOptions.mergeFields(F_PREMIUM, "premiumUpdatedAt"),
        ),
    )

    /**
     * Deletes the whole backup tree of [uid] (measurements, devices, the training collections with
     * the routines and machine photos, the food log, settings, profile). Needs a connection; throws if
     * the server does not confirm.
     */
    suspend fun deleteAll(uid: String) {
        for (collection in listOf(MEASUREMENTS, DEVICES)) {
            while (true) {
                val page = user(uid).collection(collection).limit(MAX_BATCH_WRITES.toLong()).get(Source.SERVER).await()
                if (page.isEmpty) break
                val batch = db.batch()
                for (doc in page.documents) batch.delete(doc.reference)
                if (!confirmedInTime(batch.commit())) throw CloudTimeoutException()
            }
        }
        training.deleteAll(uid)
        food.deleteAll(uid)
        // The profile goes last: while it exists, other phones do not mistake a half-deleted copy for a wipe.
        val batch = db.batch()
        batch.delete(settingsRef(uid))
        batch.delete(profileRef(uid))
        if (!confirmedInTime(batch.commit())) throw CloudTimeoutException()
    }

    private suspend fun <T> confirmedInTime(task: Task<T>): Boolean = withTimeoutOrNull(COMMIT_TIMEOUT_MS) {
        task.await()
        true
    } ?: false

    private fun Map<String, Any?>.stamped(): Map<String, Any?> = this + (F_UPDATED_AT to FieldValue.serverTimestamp())

    private fun DocumentSnapshot.updatedAtMillis(): Long? = getTimestamp(F_UPDATED_AT)?.toDate()?.time

    companion object {
        /** Firestore allows 500 writes per batch; stay below it. */
        const val MAX_BATCH_WRITES = 400

        /** Measurement puts per transaction (each one also reads its document). */
        const val TRANSACTION_PUTS = 100

        /** Incremental pulls re-read this window before the cursor, to absorb clock and commit-order skew. */
        const val PULL_OVERLAP_MS = 10 * 60_000L

        const val COMMIT_TIMEOUT_MS = 30_000L
    }
}
