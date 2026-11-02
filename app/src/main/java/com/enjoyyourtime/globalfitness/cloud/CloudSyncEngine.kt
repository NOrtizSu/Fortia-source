package com.enjoyyourtime.globalfitness.cloud

import android.app.Activity
import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import com.enjoyyourtime.globalfitness.R
import com.enjoyyourtime.globalfitness.UiText
import com.enjoyyourtime.globalfitness.account.AccountManager
import com.enjoyyourtime.globalfitness.account.AuthResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The sync is paused until the user answers. */
data class SyncConflict(val kind: ConflictKind, val count: Int = 0)

/** What the account screen shows about the backup. */
data class CloudSyncStatus(
    val signedIn: Boolean = false,
    val syncing: Boolean = false,
    /** Wall-clock time of the last successful sync. */
    val lastSyncAt: Long? = null,
    /** Error of the last attempt, null when it succeeded. */
    val error: UiText? = null,
    /** A retry with backoff is scheduled. */
    val retrying: Boolean = false,
    /** Some changes are saved in the offline queue and will be sent when there is connection. */
    val pendingUpload: Boolean = false,
    /** Paused waiting for the user's choice (see [CloudSyncEngine.resolveConflict]). */
    val conflict: SyncConflict? = null,
    /** "Delete my cloud data and account" is running. */
    val deleting: Boolean = false,
    /** A cloud deletion did not finish: sync is paused until it is retried or the user signs out. */
    val deletionPending: Boolean = false,
)

/**
 * Keeps the local data and the user's cloud copy in sync while signed in: a merge right after
 * sign-in and on app start, a pull/merge when the app comes back to the foreground or on
 * "Sync now", and a debounced upload after local changes. Two different datasets are never
 * merged automatically (see [DatasetGate]). Everything runs in the background with retries; errors
 * are surfaced in [status] and [messages], never thrown.
 *
 * The training data (workouts, custom machines, machine notes and photos, routines; see [TrainingSync]) is
 * synced in the same cycle, right after the measurements, following the same dataset decision; then
 * the food log (meal entries and custom foods, never the meal photos; see [FoodSync]).
 */
@OptIn(FlowPreview::class)
class CloudSyncEngine(
    context: Context,
    private val scope: CoroutineScope,
    private val account: AccountManager,
    private val source: BackupDataSource,
    private val isPremium: StateFlow<Boolean>,
    private val remote: FirestoreBackupRemote = FirestoreBackupRemote(),
    private val store: SyncStateStore = SyncStateStore(context),
    private val identityStore: DatasetIdentityStore = DatasetIdentityStore(context),
    private val clock: () -> Long = System::currentTimeMillis,
    private val retry: RetryPolicy = RetryPolicy(),
    /** Training data to back up too; null leaves it out. */
    training: TrainingBackupSource? = null,
    /** The food log to back up too; null leaves it out. */
    food: FoodBackupSource? = null,
) {
    private val trainingSync = training?.let { TrainingSync(it.repository, it.photos, remote.training, store, clock) }
    private val foodSync = food?.let { FoodSync(it.repository, it.deletePhotos, remote.food, store, clock) }
    private val mutex = Mutex()
    private val requests = Channel<Unit>(Channel.CONFLATED)
    private var stamps: EditStamps? = null
    private var lastForegroundSync = 0L
    private var pendingChoice: ConflictChoice? = null

    @Volatile
    private var deleting = false

    private val _status = MutableStateFlow(CloudSyncStatus(signedIn = account.user.value != null))
    val status: StateFlow<CloudSyncStatus> = _status.asStateFlow()

    private val _messages = MutableStateFlow<UiText?>(null)

    /** One-shot notices (account deleted, cloud copy gone...), cleared with [consumeMessage]. */
    val messages: StateFlow<UiText?> = _messages.asStateFlow()

    init {
        scope.launch { watchLocalChanges() }
        trainingSync?.let { t -> scope.launch { watchLoggedChanges(t.localChanges) } }
        foodSync?.let { f -> scope.launch { watchLoggedChanges(f.localChanges) } }
        scope.launch { runWhileSignedIn() }
        scope.launch {
            // Mirror the informational premium flag on the next sync.
            isPremium.collect { premium -> if (premium && account.user.value != null) requests.trySend(Unit) }
        }
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_START) onAppForeground() },
        )
    }

    /** "Sync now" (also re-checks a paused conflict). */
    fun syncNow() {
        requests.trySend(Unit)
    }

    /** The user's answer to [CloudSyncStatus.conflict]. */
    fun resolveConflict(choice: ConflictChoice) {
        pendingChoice = choice
        _status.update { it.copy(conflict = null) }
        requests.trySend(Unit)
    }

    fun consumeMessage() {
        _messages.value = null
    }

    /** Signs out; local data stays. Also gives up a pending cloud deletion of this account. */
    suspend fun signOut() {
        account.currentUid()?.let { store.setDeletionPending(it, false) }
        pendingChoice = null
        account.signOut()
    }

    /**
     * Deletes the whole cloud backup and the Firebase account (Play policy: account deletion).
     * Runs in the app scope (survives the screen), re-authenticates first so nothing is deleted if
     * the user backs out, and pauses sync until it finishes. The result arrives in [messages].
     */
    fun deleteCloudDataAndAccount(activity: Activity) {
        val uid = account.currentUid()
        if (uid == null) {
            _messages.value = UiText.res(R.string.auth_error_not_signed_in)
            return
        }
        if (deleting) return
        deleting = true
        _status.update { it.copy(deleting = true) }
        var prompt: Activity? = activity
        scope.launch {
            try {
                val result = mutex.withLock {
                    val reauth = account.reauthenticate(prompt ?: return@withLock AuthResult.Cancelled)
                    prompt = null
                    if (reauth != AuthResult.Success) return@withLock reauth
                    store.setDeletionPending(uid, true)
                    // Forget what was synced: if anything fails, the next sync starts from scratch.
                    store.clearBase(uid)
                    try {
                        remote.deleteAll(uid)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Cloud deletion failed", e)
                        return@withLock AuthResult.Failure(UiText.res(R.string.cloud_delete_failed, CloudErrors.message(e)))
                    }
                    account.deleteUser().also { if (it == AuthResult.Success) store.setDeletionPending(uid, false) }
                }
                _messages.value = when (result) {
                    AuthResult.Success -> UiText.res(R.string.cloud_deleted)
                    AuthResult.Cancelled -> UiText.res(R.string.cloud_delete_cancelled)
                    is AuthResult.Failure -> result.message
                }
            } finally {
                prompt = null
                deleting = false
                val pending = store.isDeletionPending(uid)
                _status.update { it.copy(deleting = false, deletionPending = pending && account.currentUid() == uid) }
            }
        }
    }

    private fun onAppForeground() {
        val now = SystemClock.elapsedRealtime()
        if (account.user.value == null || _status.value.conflict != null) return
        if (now - lastForegroundSync < FOREGROUND_SYNC_MIN_INTERVAL_MS) return
        lastForegroundSync = now
        requests.trySend(Unit)
    }

    /** Records identity / edit times of local changes and schedules a debounced upload while signed in. */
    private suspend fun watchLocalChanges() {
        source.changes.debounce(LOCAL_CHANGE_DEBOUNCE_MS).collect {
            try {
                mutex.withLock { prepare(source.snapshot()) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Could not observe the local data", e)
            }
            if (account.user.value != null && _status.value.conflict == null) requests.trySend(Unit)
        }
    }

    /**
     * Training edits come in bursts (every set of a workout): they are uploaded after a pause, and at
     * most once a minute while the user keeps logging, to stay within the shared Firestore quota.
     * The data is safe on the phone meanwhile; the sync on app start or "Sync now" sends the rest.
     * Meals logged one after another (a plate of three foods) are paced the same way.
     */
    private suspend fun watchLoggedChanges(localChanges: Flow<Unit>) {
        // conflate first: this subscriber never makes the repository's change flow wait.
        localChanges.conflate().debounce(TRAINING_CHANGE_DEBOUNCE_MS).collect {
            if (account.user.value != null && _status.value.conflict == null) requests.trySend(Unit)
            delay(TRAINING_SYNC_MIN_INTERVAL_MS)
        }
    }

    private suspend fun runWhileSignedIn() {
        account.user.map { it?.uid }.distinctUntilChanged().collectLatest { uid ->
            pendingChoice = null
            if (uid == null) {
                _status.value = CloudSyncStatus()
                return@collectLatest
            }
            val base = store.loadBase(uid)
            _status.value = CloudSyncStatus(
                signedIn = true,
                lastSyncAt = base?.lastSyncAt,
                deletionPending = store.isDeletionPending(uid),
            )
            lastForegroundSync = SystemClock.elapsedRealtime()
            requests.trySend(Unit) // first sync after sign-in / app start
            for (request in requests) syncWithRetry(uid)
        }
    }

    private suspend fun syncWithRetry(uid: String) {
        var failures = 0
        while (!deleting) {
            _status.update { it.copy(syncing = true) }
            try {
                val queued = syncOnce(uid)
                if (queued == null) {
                    _status.update { it.copy(syncing = false) }
                    return
                }
                _status.update {
                    it.copy(syncing = false, retrying = false, error = null, lastSyncAt = clock(), pendingUpload = queued)
                }
                return
            } catch (e: CancellationException) {
                _status.update { it.copy(syncing = false) }
                throw e
            } catch (e: Exception) {
                failures++
                Log.w(TAG, "Sync failed (attempt $failures)", e)
                val again = CloudErrors.isRetryable(e) && retry.canRetry(failures)
                _status.update { it.copy(syncing = false, retrying = again, error = CloudErrors.message(e)) }
                if (!again) return
                delay(retry.delayFor(failures))
            }
        }
    }

    /** One sync. Returns whether writes are still queued offline, or null if it was skipped or paused. */
    private suspend fun syncOnce(uid: String): Boolean? = mutex.withLock {
        if (deleting || account.currentUid() != uid) return@withLock null
        if (store.isDeletionPending(uid)) {
            _status.update { it.copy(deletionPending = true) }
            return@withLock null
        }
        val local = prepare(source.snapshot())
        val base = store.loadBase(uid)
        val trusted = SyncMerge.isBaseTrusted(base, local)
        var remoteSnapshot = remote.fetch(uid, if (trusted) base?.lastPullAt else null)
        val choice = pendingChoice
        val mode = DatasetGate.decide(local, remoteSnapshot, base, choice)
        if ((mode == SyncMode.AdoptCloud || mode == SyncMode.ReplaceCloud) && !remoteSnapshot.complete) {
            remoteSnapshot = remote.fetch(uid, null)
        }
        val now = clock()
        val plan = when (mode) {
            SyncMode.Idle -> return@withLock false
            SyncMode.RemoteWiped -> {
                onRemoteWiped(uid)
                return@withLock null
            }
            is SyncMode.NeedsChoice -> {
                _status.update { it.copy(conflict = SyncConflict(mode.kind)) }
                return@withLock null
            }
            SyncMode.Merge -> {
                val effectiveBase = if (choice == ConflictChoice.UNDO_DELETIONS && base != null) SyncMerge.withoutLiveEntries(base) else base
                val merge = withContext(Dispatchers.Default) { SyncMerge.plan(local, remoteSnapshot, effectiveBase, now) }
                if (choice != ConflictChoice.CONFIRM_DELETIONS && SyncMerge.isMassDeletion(merge, effectiveBase)) {
                    _status.update { it.copy(conflict = SyncConflict(ConflictKind.MASS_DELETE, merge.measurementDeletions)) }
                    return@withLock null
                }
                merge
            }
            SyncMode.AdoptCloud -> SyncMerge.planAdoptCloud(local, remoteSnapshot)
            SyncMode.ReplaceCloud -> SyncMerge.planReplaceCloud(local, remoteSnapshot, now)
        }
        val datasetId = if (mode == SyncMode.AdoptCloud) {
            (remoteSnapshot.profileDoc as? RemoteProfileDoc.Live)?.datasetId ?: local.datasetId
        } else {
            local.datasetId
        }
        // Before anything changes: if this sync fails, the retry finishes the same training transition.
        TrainingMerge.transitionFor(mode, choice)?.let { transition ->
            trainingSync?.prepareTransition(uid, transition, datasetId)
            foodSync?.prepareTransition(uid, transition, datasetId)
        }

        var after = local
        if (!plan.localChanges.isEmpty) {
            val changes = if (mode == SyncMode.Merge) {
                SyncMerge.dropStaleSingles(plan.localChanges, local, source.snapshot())
            } else {
                plan.localChanges
            }
            source.apply(changes)
            after = source.snapshot() // right after applying, before the (slow) upload
        }
        if (datasetId != local.datasetId) {
            withContext(Dispatchers.IO) { identityStore.load()?.let { identityStore.save(it.adopt(datasetId)) } }
        }
        val confirmed = if (plan.uploads.isEmpty()) true else remote.write(uid, plan.uploads)
        rememberAppliedEdits(plan.localChanges, after)

        var next = SyncMerge.nextBase(base, local.copy(datasetId = datasetId), remoteSnapshot, plan, after, now)
        if (isPremium.value && !next.premiumMirrored && !deleting) next = next.copy(premiumMirrored = mirrorPremium(uid))
        store.saveBase(uid, next)
        pendingChoice = null
        _status.update { it.copy(conflict = null) }
        val trainingQueued = trainingSync?.sync(uid, datasetId) ?: false
        val foodQueued = foodSync?.sync(uid, datasetId) ?: false
        !confirmed || trainingQueued || foodQueued
    }

    /** The cloud copy this phone synced is gone (account deleted elsewhere): stop and sign out, keep local data. */
    private suspend fun onRemoteWiped(uid: String) {
        store.clearBase(uid)
        _messages.value = UiText.res(R.string.cloud_copy_gone)
        // Outside this coroutine: signing out cancels the sync worker that is running here.
        scope.launch { account.signOut() }
    }

    private suspend fun mirrorPremium(uid: String): Boolean = try {
        remote.markPremium(uid)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Could not mirror the premium flag; retried on the next sync", e)
        false
    }

    /** Dataset identity (and deletion journal) plus profile / settings edit times. Call under [mutex]. */
    private suspend fun prepare(snapshot: LocalSnapshot): LocalSnapshot {
        val identity = withContext(Dispatchers.IO) { identityStore.observe(snapshot.ownerKey, clock()) }
        return stamped(snapshot.copy(datasetId = identity.datasetId, profileDeletedAt = identity.profileDeletedAt))
    }

    /** Fills profile / settings edit times from the stamps (see [EditStamp.observe]). */
    private suspend fun stamped(local: LocalSnapshot): LocalSnapshot {
        val current = stamps ?: store.loadStamps()
        val now = clock()
        val profileStamp = local.profile?.let { EditStamp.observe(current.profile, SyncMerge.hashOf(it), now, it.editedAt) }
        val settingsStamp = local.settings?.let { EditStamp.observe(current.settings, SyncMerge.hashOf(it), now, it.editedAt) }
        val updated = EditStamps(profileStamp ?: current.profile, settingsStamp ?: current.settings)
        stamps = updated
        if (updated != current) store.saveStamps(updated)
        return local.copy(
            profile = local.profile?.let { p -> p.copy(editedAt = maxOf(p.editedAt, profileStamp?.at ?: 0L)) },
            settings = local.settings?.let { s -> s.copy(editedAt = maxOf(s.editedAt, settingsStamp?.at ?: 0L)) },
        )
    }

    /** Content that came from the cloud keeps the cloud's edit time instead of looking like a new local edit. */
    private suspend fun rememberAppliedEdits(applied: RemoteChanges, after: LocalSnapshot) {
        val current = stamps ?: store.loadStamps()
        val profile = applied.profile?.let { remoteProfile ->
            after.profile?.let { EditStamp(SyncMerge.hashOf(it), remoteProfile.editedAt) }
        }
        val settings = applied.settings?.let { remoteSettings ->
            after.settings?.let { EditStamp(SyncMerge.hashOf(it), remoteSettings.editedAt) }
        }
        if (profile == null && settings == null) return
        val updated = EditStamps(profile ?: current.profile, settings ?: current.settings)
        stamps = updated
        store.saveStamps(updated)
    }

    private companion object {
        const val TAG = "CloudSync"
        const val LOCAL_CHANGE_DEBOUNCE_MS = 3_000L
        const val TRAINING_CHANGE_DEBOUNCE_MS = 10_000L
        const val TRAINING_SYNC_MIN_INTERVAL_MS = 60_000L
        const val FOREGROUND_SYNC_MIN_INTERVAL_MS = 60_000L
    }
}
