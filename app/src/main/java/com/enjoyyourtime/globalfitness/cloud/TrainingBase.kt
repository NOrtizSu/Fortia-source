package com.enjoyyourtime.globalfitness.cloud

/**
 * Training sync bookkeeping for one account on this phone, stored next to the measurement
 * [SyncBase] in `noBackupFilesDir` (never restored to another phone, so a restored phone reads the
 * whole cloud copy and merges instead of trusting stale history).
 */
data class TrainingBase(
    /** The dataset this base describes (see [LocalSnapshot.datasetId]); another dataset never trusts it. */
    val datasetKey: String,
    /**
     * A dataset transition (restore / replace) decided by the measurement part that the training part
     * has not completed yet. It survives a failed sync, so the retry finishes the same transition
     * instead of falling back to a plain merge.
     */
    val pending: TrainingMode? = null,
    /**
     * Client time the [pending] transition was decided. Documents changed after it are merged
     * normally when the transition is completed (a retry never removes a workout logged since).
     */
    val pendingSince: Long? = null,
    /** Server-time cursor of each collection for incremental pulls; missing forces a full read. */
    val cursors: Map<TrainingKind, Long> = emptyMap(),
    /**
     * What both sides agreed on for each document: a live version ([TrainingMerge.versionOf]) or a
     * deletion ([TrainingMerge.dead], kept for a while so a late, older write cannot resurrect it).
     */
    val entries: Map<DocKey, String> = emptyMap(),
    /** Photo hashes known to be in the cloud and used by some note. */
    val photos: Set<String> = emptySet(),
    /**
     * Local photo file name -> SHA-256 of its bytes. Photo files are never rewritten (a new photo
     * gets a new name), so each file is hashed once.
     */
    val photoFiles: Map<String, String> = emptyMap(),
    /**
     * Photo uploads handed to Firestore's offline queue without confirmation, with the client time:
     * not queued again for a while (they are ~0.5 MB each). Facts about this phone's queue, kept
     * whatever the dataset.
     */
    val photosInFlight: Map<String, Long> = emptyMap(),
    /** Documents whose cloud copy was written by a newer app version: never overwritten by this one. */
    val readOnly: Set<DocKey> = emptySet(),
    /** The highest document version ([TrainingCodec.READABLE_SCHEMA_VERSION]) of the app that saved this base; 0 before 1.7.0. */
    val schema: Int = TrainingCodec.READABLE_SCHEMA_VERSION,
) {
    /** True when this base describes [datasetId] and no transition is pending: incremental sync allowed. */
    fun trustedFor(datasetId: String): Boolean = pending == null && datasetId.isNotEmpty() && datasetKey == datasetId

    /**
     * This base as this app uses it: one saved by an app that read fewer document versions marked
     * documents read-only that this one reads in full, and its cursors skip them. Those marks are
     * dropped and the next read is whole, so they are read again and can be written.
     */
    fun forReader(readerSchema: Int = TrainingCodec.READABLE_SCHEMA_VERSION): TrainingBase =
        if (schema >= readerSchema) this else copy(cursors = emptyMap(), readOnly = emptySet(), schema = readerSchema)

    fun cursorsOrFull(trusted: Boolean): TrainingCursors = if (!trusted) {
        TrainingCursors.FULL
    } else {
        // A base saved before a collection was synced has no cursor for it: that one is read whole.
        TrainingCursors(cursors[TrainingKind.WORKOUT], cursors[TrainingKind.CUSTOM_EXERCISE], cursors[TrainingKind.NOTE], cursors[TrainingKind.ROUTINE])
    }
}

/** Plain-text, line-based encoding of [TrainingBase] (pure Kotlin, same style as [SyncStateCodec]). */
object TrainingBaseCodec {
    private const val VERSION = "1"
    private const val SEP = '\t'

    fun encode(base: TrainingBase): String = buildString {
        line("v", VERSION)
        line("dataset", base.datasetKey)
        line("schema", base.schema.toString())
        base.pending?.let { pending ->
            if (base.pendingSince != null) line("pending", pending.code, base.pendingSince.toString()) else line("pending", pending.code)
        }
        for ((kind, at) in base.cursors) line("cursor", kind.tag, at.toString())
        for ((key, version) in base.entries) line("e", key.kind.tag, key.id, version)
        for (hash in base.photos) line("p", hash)
        for ((file, hash) in base.photoFiles) line("f", file, hash)
        for ((hash, at) in base.photosInFlight) line("q", hash, at.toString())
        for (key in base.readOnly) line("ro", key.kind.tag, key.id)
    }

    /** Null for an unknown version or a file without dataset (the next sync starts from a full read). */
    fun decode(text: String): TrainingBase? {
        var version: String? = null
        var dataset: String? = null
        // A base saved before 1.7.0 has no such line.
        var schema = 0
        var pending: TrainingMode? = null
        var pendingSince: Long? = null
        val cursors = HashMap<TrainingKind, Long>()
        val entries = HashMap<DocKey, String>()
        val photos = HashSet<String>()
        val files = HashMap<String, String>()
        val inFlight = HashMap<String, Long>()
        val readOnly = HashSet<DocKey>()
        for (raw in text.lineSequence()) {
            if (raw.isBlank()) continue
            val parts = raw.split(SEP)
            when (parts[0]) {
                "v" -> version = parts.getOrNull(1)
                "dataset" -> dataset = parts.getOrNull(1)
                "schema" -> schema = parts.getOrNull(1)?.toIntOrNull() ?: 0
                "pending" -> {
                    pending = TrainingMode.fromCode(parts.getOrNull(1))
                    pendingSince = parts.getOrNull(2)?.toLongOrNull()
                }
                "cursor" -> {
                    val kind = parts.getOrNull(1)?.let(TrainingKind::ofTag)
                    val at = parts.getOrNull(2)?.toLongOrNull()
                    if (kind != null && at != null) cursors[kind] = at
                }
                "e" -> {
                    val kind = parts.getOrNull(1)?.let(TrainingKind::ofTag)
                    if (kind != null && parts.size >= 4) entries[DocKey(kind, parts[2])] = parts[3]
                }
                "p" -> parts.getOrNull(1)?.takeIf(TrainingCodec::isPhotoHash)?.let { photos += it }
                "f" -> if (parts.size >= 3 && TrainingCodec.isPhotoHash(parts[2])) files[parts[1]] = parts[2]
                "q" -> {
                    val at = parts.getOrNull(2)?.toLongOrNull()
                    if (TrainingCodec.isPhotoHash(parts.getOrNull(1)) && at != null) inFlight[parts[1]] = at
                }
                "ro" -> {
                    val kind = parts.getOrNull(1)?.let(TrainingKind::ofTag)
                    if (kind != null && parts.size >= 3) readOnly += DocKey(kind, parts[2])
                }
            }
        }
        if (version != VERSION || dataset.isNullOrEmpty()) return null
        return TrainingBase(dataset, pending, pendingSince, cursors, entries, photos, files, inFlight, readOnly, schema)
    }

    private fun StringBuilder.line(vararg fields: String) {
        fields.joinTo(this, SEP.toString()) { clean(it) }
        append('\n')
    }

    /** Ids, hashes and file names never contain separators, but stay safe if they ever do. */
    private fun clean(s: String) = s.replace(SEP, ' ').replace('\n', ' ').replace('\r', ' ')
}
