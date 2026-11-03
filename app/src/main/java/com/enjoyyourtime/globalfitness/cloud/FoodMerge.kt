package com.enjoyyourtime.globalfitness.cloud

import com.enjoyyourtime.globalfitness.food.data.FoodBatch
import com.enjoyyourtime.globalfitness.food.model.CustomFood
import com.enjoyyourtime.globalfitness.food.model.FoodEntry
import com.enjoyyourtime.globalfitness.food.model.Nutrient
import com.enjoyyourtime.globalfitness.food.model.Nutrients
import com.enjoyyourtime.globalfitness.training.model.Tombstone as LocalTombstone

/**
 * Pure merge of the food log (meal entries, custom foods and cached products) with the cloud copy:
 * the training data's per-document last-writer-wins rules ([TrainingMerge.mergeKind]) and dataset
 * transitions, for two collections without photos. Meal photos are never part of a document (they
 * stay on the phone), so an entry's version ignores them. No Android, no Firebase; unit-tested.
 */
object FoodMerge {
    class Plan internal constructor(
        val mode: TrainingMode,
        /** Apply these on the phone (one transaction). */
        val apply: FoodBatch,
        /** Documents and tombstones to upload. */
        val writes: List<FoodWrite>,
        /** Local tombstones that need no upload. */
        val dropNow: List<LocalTombstone>,
        /** Local tombstones to forget once the server confirmed their tombstone write. */
        val dropOnConfirm: Map<FoodDocKey, LocalTombstone>,
        internal val settled: Map<FoodDocKey, String?>,
        internal val onConfirm: Map<FoodDocKey, String?>,
        /** Collections to read whole next time. */
        val refetch: Set<FoodKind>,
        val readOnly: Set<FoodDocKey>,
    ) {
        val isNoOp: Boolean get() = apply.isEmpty && writes.isEmpty() && dropNow.isEmpty()
    }

    /** A pending transition of this very dataset is finished first; otherwise a merge. */
    fun modeFor(base: FoodBase?, datasetId: String): TrainingMode =
        base?.pending?.takeIf { base.datasetKey == datasetId } ?: TrainingMode.MERGE

    /**
     * "<updatedAt>.<sha1 of the content>"; the local photo does not count. The data 1.6.0 added (the
     * values beyond the label, changed ingredients, the photo's grams estimate) counts only when there
     * is some: an entry without it has the version 1.5.0 agreed on, so updating the app re-uploads nothing.
     * The same for 1.7.0's mark of values typed by hand: it counts only when set.
     */
    fun versionOf(e: FoodEntry): String {
        val c = TrainingMerge.Canon().l(e.eatenAt).l(e.zoneOffsetSeconds.toLong()).s(e.meal.code).s(e.foodId).s(e.customFoodId).s(e.name)
            .d(e.amount).s(e.unit.code).s(e.portionKey?.code).d(e.portionCount)
        nutrients(c, e.nutrients)
        c.s(e.source.code).s(e.modelLabel).d(e.modelConfidence).l(e.createdAt)
        extraNutrients(c, e.nutrients)
        if (e.adjustments.isNotEmpty()) {
            c.s("adjustments")
            for (a in e.adjustments) c.s(a.ingredient).d(a.baseGrams).d(a.grams).d(a.factor)
        }
        if (e.estimatedAmount != null) c.s("estimated").d(e.estimatedAmount)
        if (e.valuesEdited) c.s("edited")
        return "${e.updatedAt}.${c.digest()}"
    }

    fun versionOf(f: CustomFood): String {
        val c = TrainingMerge.Canon().s(f.name).s(f.brand).s(f.barcode).s(f.unit.code)
        nutrients(c, f.per100)
        c.d(f.portion).s(f.source.code).l(f.createdAt)
        extraNutrients(c, f.per100)
        return "${f.updatedAt}.${c.digest()}"
    }

    private fun nutrients(c: TrainingMerge.Canon, n: Nutrients) {
        for (nutrient in Nutrient.LABEL) c.d(n[nutrient])
    }

    private fun extraNutrients(c: TrainingMerge.Canon, n: Nutrients) {
        if (!n.hasExtra) return
        c.s("extra")
        for (nutrient in Nutrient.EXTRA) c.d(n[nutrient])
    }

    /**
     * [base] must be trusted (same dataset, no pending transition) or null; in the transition modes and
     * without base, [remote] must be a complete read. [pendingSince]: when the transition was decided.
     */
    fun plan(mode: TrainingMode, local: FoodLocal, remote: FoodCloudSnapshot, base: FoodBase?, now: Long, pendingSince: Long? = null): Plan {
        val trusted = mode == TrainingMode.MERGE
        val entries = if (trusted) base?.entries.orEmpty() else emptyMap()
        val readOnlyBefore = if (trusted) base?.readOnly.orEmpty() else emptySet()
        val since = pendingSince.takeIf { mode != TrainingMode.MERGE }
        val dead = local.tombstones.groupBy { FoodKind.ofTombstone(it.kind) }

        fun baseOf(kind: FoodKind): Map<String, String> = HashMap<String, String>().apply { for ((k, v) in entries) if (k.kind == kind) put(k.id, v) }
        fun <T> readOnlyIds(kind: FoodKind, docs: RemoteDocs<T>, id: (T) -> String): Set<String> {
            val seen = docs.live.mapTo(HashSet(), id) + docs.dead.map { it.id }
            return readOnlyBefore.filter { it.kind == kind && it.id !in seen }.mapTo(HashSet()) { it.id } + docs.readOnly
        }
        val roEntries = readOnlyIds(FoodKind.ENTRY, remote.entries) { it.id }
        val roFoods = readOnlyIds(FoodKind.CUSTOM_FOOD, remote.customFoods) { it.id }

        val f = TrainingMerge.mergeKind(
            mode, local.customFoods, dead[FoodKind.CUSTOM_FOOD].orEmpty(), remote.customFoods, baseOf(FoodKind.CUSTOM_FOOD), now,
            id = { it.id }, updatedAt = { it.updatedAt }, version = ::versionOf, since = since, readOnly = roFoods,
        )
        val e = TrainingMerge.mergeKind(
            mode, local.entries, dead[FoodKind.ENTRY].orEmpty(), remote.entries, baseOf(FoodKind.ENTRY), now,
            id = { it.id }, updatedAt = { it.updatedAt }, version = ::versionOf, since = since, readOnly = roEntries,
        )
        val writes = ArrayList<FoodWrite>()
        f.put.mapTo(writes) { FoodWrite.PutCustomFood(it) }
        e.put.mapTo(writes) { FoodWrite.PutEntry(it) }
        f.delete.mapTo(writes) { FoodWrite.Delete(FoodKind.CUSTOM_FOOD, it.id, it.deletedAt) }
        e.delete.mapTo(writes) { FoodWrite.Delete(FoodKind.ENTRY, it.id, it.deletedAt) }

        val settled = LinkedHashMap<FoodDocKey, String?>()
        val onConfirm = LinkedHashMap<FoodDocKey, String?>()
        val dropOnConfirm = LinkedHashMap<FoodDocKey, LocalTombstone>()
        for ((kind, m) in listOf(FoodKind.ENTRY to e, FoodKind.CUSTOM_FOOD to f)) {
            m.settled.forEach { (id, v) -> settled[FoodDocKey(kind, id)] = v }
            m.onConfirm.forEach { (id, v) -> onConfirm[FoodDocKey(kind, id)] = v }
            m.dropOnConfirm.forEach { (id, t) -> dropOnConfirm[FoodDocKey(kind, id)] = t }
        }
        val refetch = HashSet<FoodKind>()
        if (e.lost) refetch += FoodKind.ENTRY
        if (f.lost) refetch += FoodKind.CUSTOM_FOOD
        val readOnly = HashSet<FoodDocKey>()
        roEntries.mapTo(readOnly) { FoodDocKey(FoodKind.ENTRY, it) }
        roFoods.mapTo(readOnly) { FoodDocKey(FoodKind.CUSTOM_FOOD, it) }
        return Plan(
            mode = mode,
            apply = FoodBatch(
                upsertEntries = e.apply.toList(),
                removeEntries = e.removeLocal.toList(),
                upsertCustomFoods = f.apply.toList(),
                removeCustomFoods = f.removeLocal.toList(),
            ),
            writes = writes,
            dropNow = e.dropNow + f.dropNow,
            dropOnConfirm = dropOnConfirm,
            settled = settled,
            onConfirm = onConfirm,
            refetch = refetch,
            readOnly = readOnly,
        )
    }

    /**
     * The base to store after the plan ran ([previous]: the trusted base it was made with, or null).
     * [skipped]: documents whose local apply was dropped because the user changed them meanwhile.
     * [overrides]: versions to store whatever the plan said (a document food.db refused keeps the
     * previous agreement, null forgets it).
     */
    fun nextBase(
        previous: FoodBase?,
        datasetKey: String,
        remote: FoodCloudSnapshot,
        plan: Plan,
        result: FoodWriteResult,
        skipped: Set<FoodDocKey>,
        now: Long,
        overrides: Map<FoodDocKey, String?> = emptyMap(),
    ): FoodBase {
        val entries = LinkedHashMap(previous?.entries.orEmpty())
        fun set(key: FoodDocKey, version: String?) {
            if (version == null) entries.remove(key) else entries[key] = version
        }
        for ((key, version) in plan.settled) if (key !in skipped) set(key, version)
        for ((key, version) in plan.onConfirm) if (key in result.confirmedDocs) set(key, version)
        for ((key, version) in overrides) set(key, version)
        entries.entries.removeAll { (_, v) -> TrainingMerge.deadAtOf(v)?.let { it < now - TrainingMerge.DEAD_ENTRY_TTL_MS } == true }

        val skippedKinds = skipped.mapTo(HashSet()) { it.kind }
        val cursors = LinkedHashMap<FoodKind, Long>()
        for ((kind, docs) in listOf(FoodKind.ENTRY to remote.entries, FoodKind.CUSTOM_FOOD to remote.customFoods)) {
            val before = previous?.cursors?.get(kind)
            val cursor = when {
                kind in plan.refetch -> null
                kind in skippedKinds -> before
                docs.complete -> docs.maxUpdatedAt
                else -> listOfNotNull(before, docs.maxUpdatedAt).maxOrNull()
            }
            if (cursor != null) cursors[kind] = cursor
        }
        return FoodBase(datasetKey = datasetKey, cursors = cursors, entries = entries, readOnly = plan.readOnly)
    }
}
