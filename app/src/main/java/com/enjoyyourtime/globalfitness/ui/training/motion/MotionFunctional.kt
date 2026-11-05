package com.enjoyyourtime.globalfitness.ui.training.motion

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

// Functional and Olympic-style lifts added in 1.6 (CrossFit boxes, functional gyms): squats with the
// bar overhead or in a landmine, thrusters and push presses, cleans and snatches, the dumbbell snatch,
// the kettlebell high pull, medicine-ball throws, the sandbag carry, toes-to-bar and the GHD sit-up.

private const val TAU = 2 * PI.toFloat()

/** Middle of the feet: where a balanced load stays above. */
private const val MID_FOOT = 0.05f

/** Spine length from the hip joints to the shoulder line. */
private const val SPINE = BodySize.LOWER_SPINE + BodySize.UPPER_SPINE - BodySize.SHOULDER_DROP

// ---- Squats ---------------------------------------------------------------------------------------

/**
 * Overhead squat: a wide grip on the bar locked out overhead, kept over the middle of the feet while
 * the hips sink between the heels and the trunk leans only a little.
 */
internal fun overheadSquat() = Motion.rep(Tempo.lowerFirst(), Camera(26f, 4f)) { p ->
    val lean = lerp(3f, 30f, p)
    val basis = Basis.UPRIGHT.pitch(lean)
    val pelvis = v(MID_FOOT - SPINE * sin(rad(lean)) - 0.01f, lerp(0.915f, 0.48f, p))
    body.torso(pelvis, basis, nod = -lean * 0.3f)
    body.standOn(0f, width = 0.36f, out = 18f, pole = v(1f, 0.1f, 0.3f))
    val half = 0.46f
    val shoulders = body.shoulders()
    val dx = MID_FOOT - shoulders.x
    val bar = v(MID_FOOT, shoulders.y + armReach(half - BodySize.SHOULDER_HALF, dx, straightness = 0.99f))
    barbell(bar)
    for (side in Side.entries) body.arm(side, bar.withZ(side.sign * half), v(0f, -0.3f, side.sign * 1f))
}

/** Landmine squat: the loaded end of an anchored bar held at the chest, squatting between the knees as the bar arcs. */
internal fun landmineSquat() = Motion.rep(Tempo.lowerFirst(), Camera(26f)) { p ->
    val lean = lerp(6f, 30f, p)
    val basis = Basis.UPRIGHT.pitch(lean)
    val pelvis = v(0.02f - 0.2f * sin(rad(lean)), lerp(0.9f, 0.48f, p))
    body.torso(pelvis, basis, nod = -lean * 0.4f)
    body.standOn(0.02f, width = 0.38f, out = 16f, pole = v(1f, 0.1f, 0.3f))
    val pivot = v(1.55f, 0.05f)
    val end = body.onChest(0.28f, -0.1f)
    for (side in Side.entries) body.arm(side, end + basis.u * 0.02f + V3.Z * (side.sign * 0.05f), basis.dir(0.1f, -1f, side.sign * 0.6f))
    landmine(pivot, (end + basis.u * 0.02f).withZ(0f))
}

// ---- Thruster and push press ----------------------------------------------------------------------

/**
 * What a front-rack lift holds: a barbell, two dumbbells (palms facing in) or a strongman log, which is
 * thicker (it rests further forward on the chest and the head moves further out of its way).
 */
internal enum class RackLoad(val forward: Float, val half: Float, val dodge: Float) {
    BAR(0.14f, 0.24f, 0.06f),
    DUMBBELLS(0.18f, 0.21f, 0.03f),
    LOG(0.22f, 0.2f, 0.1f),
}

/**
 * The front-rack lifts: squat depth [depth] (0 standing, 1 below parallel) with the [load] on the
 * front of the shoulders, pressed [press] of the way to locked out overhead. [split] (0..1) catches it
 * in a split stance, the near foot forward and the far one back on its toes (the split jerk).
 */
private fun FrameBuilder.frontRackPress(depth: Float, press: Float, toes: Float = 0f, split: Float = 0f, load: RackLoad = RackLoad.BAR) {
    val lean = lerp(4f, 24f, depth)
    val basis = Basis.UPRIGHT.pitch(lean)
    val spine = BodySize.LOWER_SPINE + BodySize.UPPER_SPINE
    val rack = basis.u * spine + basis.dir(load.forward, -0.07f, 0f)
    // Balanced as with the bar on the shoulders (a log further forward would put the hips behind the heels).
    val balance = basis.u * spine + basis.dir(RackLoad.BAR.forward, -0.07f, 0f)
    val rise = liftedAnkle(0f, 0f, toes).y - BodySize.ANKLE
    val pelvis = v(MID_FOOT - balance.x - 0.06f * split, lerp(0.915f, 0.47f, depth) + rise - 0.25f * split)
    val dodge = sin(PI.toFloat() * press)
    body.torso(pelvis, basis, nod = -lean * 0.45f - 16f * dodge)
    // As standOn(0, width 0.34, out 18) while the feet are together; the split moves them apart.
    for (side in Side.entries) {
        val front = side == Side.NEAR
        body.stand(
            side,
            if (front) 0.3f * split else -0.55f * split,
            z = side.sign * lerp(0.17f, 0.14f, split),
            lift = if (front) toes * (1 - split) else toes + 58f * split,
            out = lerp(18f, if (front) 10f else 6f, split),
            pole = v(1f, 0.1f, side.sign * 0.3f),
        )
    }
    val shoulders = body.shoulders()
    val half = load.half
    val top = v(shoulders.x - 0.02f, shoulders.y + armReach(half - BodySize.SHOULDER_HALF, -0.02f))
    val bar = lerp(pelvis + rack, top, Ease.IN_OUT.apply(press)) + V3.X * (load.dodge * dodge)
    when (load) {
        RackLoad.BAR -> barbell(bar)
        RackLoad.DUMBBELLS -> for (side in Side.entries) dumbbell(bar.withZ(side.sign * half), axis = V3.X)
        RackLoad.LOG -> {
            add(Rod(bar.withZ(-0.7f), bar.withZ(0.7f), 0.15f, Ink.PLATE, bias = -0.04f))
            for (s in listOf(-1f, 1f)) add(Disc(bar.withZ(s * 0.7f), V3.Z, 0.15f, 0.01f, Ink.PLATE_DARK, hub = 0.1f, bias = -0.03f))
        }
    }
    for (side in Side.entries) {
        // The elbows stay high in front (the rack) as the bar leaves the shoulders, swing out to the sides
        // as it passes the face (never through the line from the shoulder to the hand), and end down and
        // out under it at lockout.
        // In the rack the hand is just in front of the shoulder, so the pole points up (across that line).
        val rack = basis.dir(0.3f, 1f, side.sign * 0.25f)
        val out = v(0.3f, 0.3f, side.sign * 1f)
        val lockout = v(0.6f, -1f, side.sign * 1f)
        val pole = when {
            press < 0.05f -> rack
            press < 0.35f -> lerp(rack, out, (press - 0.05f) / 0.3f)
            else -> lerp(out, lockout, (press - 0.35f) / 0.65f)
        }
        body.arm(side, bar.withZ(side.sign * half), pole)
    }
}

/**
 * Thruster: a front squat that drives straight into a press overhead, then the load back to the
 * shoulders for the next squat; with a barbell or two dumbbells.
 */
internal fun thruster(load: RackLoad = RackLoad.BAR) = Motion.cycle(2.8f, camera = Camera(24f)) { phase ->
    val t = phase * 2.8f
    val depth = ramp(t, 0.15f to 0f, 0.95f to 1f, 1.35f to 0f)
    val press = ramp(t, 1.1f to 0f, 1.6f to 1f, 1.95f to 1f, 2.5f to 0f)
    frontRackPress(depth, press, load = load)
}

/**
 * Push press: a short dip and an explosive leg drive that sends the load from the shoulders to locked
 * out overhead, then back to the rack; with a barbell or a strongman log.
 */
internal fun pushPress(load: RackLoad = RackLoad.BAR) = Motion.cycle(2.6f, camera = Camera(24f)) { phase ->
    val t = phase * 2.6f
    val depth = ramp(t, 0.35f to 0f, 0.7f to 0.26f, 0.9f to 0f, 1.95f to 0f, 2.2f to 0.12f, 2.5f to 0f)
    val toes = ramp(t, 0.85f to 0f, 0.95f to 14f, 1.15f to 0f)
    val press = ramp(t, 0.85f to 0f, 1.3f to 1f, 1.6f to 1f, 2.2f to 0f)
    frontRackPress(depth, press, toes, load = load)
}

/**
 * Push jerk: the dip and drive of the push press, but the lifter drops under the rising bar and
 * catches it on locked arms in a quarter squat, then stands up with it and lowers it to the rack.
 */
internal fun pushJerk() = Motion.cycle(2.8f, camera = Camera(24f)) { phase ->
    val t = phase * 2.8f
    val depth = ramp(t, 0.3f to 0f, 0.6f to 0.26f, 0.8f to 0f, 1.0f to 0.3f, 1.35f to 0.3f, 1.75f to 0f, 2.1f to 0f, 2.4f to 0.12f, 2.65f to 0f)
    val toes = ramp(t, 0.72f to 0f, 0.8f to 14f, 0.95f to 0f)
    val press = ramp(t, 0.68f to 0f, 1.16f to 1f, 2.1f to 1f, 2.5f to 0f)
    frontRackPress(depth, press, toes)
}

/**
 * Split jerk: dip and drive, then the feet split (near foot forward, far foot back on its toes) as the
 * arms lock the bar out overhead; the feet come back together and the bar returns to the rack.
 */
internal fun splitJerk() = Motion.cycle(3.0f, camera = Camera(24f)) { phase ->
    val t = phase * 3.0f
    val depth = ramp(t, 0.3f to 0f, 0.6f to 0.24f, 0.8f to 0f, 2.2f to 0f, 2.45f to 0.1f, 2.7f to 0f)
    val toes = ramp(t, 0.72f to 0f, 0.8f to 14f, 0.95f to 0f)
    val split = ramp(t, 0.8f to 0f, 1.0f to 1f, 1.45f to 1f, 1.95f to 0f)
    val press = ramp(t, 0.62f to 0f, 1.2f to 1f, 2.15f to 1f, 2.55f to 0f)
    frontRackPress(depth, press, toes, split)
}

// ---- Cleans and snatches --------------------------------------------------------------------------

/**
 * The stages of a barbell clean or snatch, laid out on a 0..6 driver: the first pull from the start
 * (floor or hang) to the hips (0 → 1), the jump and high pull (→ 2), the turnover and the catch in a
 * quarter squat (→ 3), standing up (→ 4), lowering the bar to the hips (→ 5) and back to the start
 * (→ 6, the same pose as 0). [snatch] takes a wide grip and catches the bar overhead. [deep] catches
 * it in a full squat (the squat clean and snatch); [pullOnly] keeps the arms straight in the jump
 * (the clean and snatch pulls), which then goes back down without a catch.
 */
private class Olympic(val fromFloor: Boolean, val snatch: Boolean, val deep: Boolean = false, val pullOnly: Boolean = false) {
    val half = if (snatch) 0.45f else 0.27f
    private val catchPelvis = when {
        !deep -> v(-0.1f, 0.76f)
        snatch -> v(-0.19f, 0.49f)
        else -> v(-0.33f, 0.49f)
    }
    private val catchLean = when {
        !deep -> 14f
        snatch -> 30f
        else -> 24f
    }
    private val start = if (fromFloor) v(0.12f, 0.225f) else v(0.13f, 0.56f)
    private val startLean = when {
        fromFloor && snatch -> 62f
        fromFloor -> 60f
        else -> 36f
    }
    private val power = v(0.12f, if (snatch) 0.76f else 0.68f)

    /** The bar hanging at arm's length in front of the thighs when standing tall. */
    private val hang = v(0.12f, if (snatch) 0.87f else 0.81f)

    /** The pelvis standing tall with the bar at [hang] (and with the bar caught, once stood up). */
    private val standPelvis = hingePelvis(hang, 2f, -0.03f)

    fun frame(b: FrameBuilder, s: Float) = with(b) {
        val stance = { lift: Float -> body.standOn(-0.03f, width = if (snatch) 0.3f else 0.27f, out = 8f, lift = lift, pole = v(1f, 0.1f, 0.15f)) }
        val straight = { side: Side -> v(-0.3f, 0.2f, side.sign * 1f) }
        val highPull = { side: Side -> v(-0.1f, 1f, side.sign * 0.9f) }
        val rackPole = { side: Side -> body.chest.dir(0.3f, 1f, side.sign * 0.25f) }
        val overheadPole = { side: Side -> v(0f, -0.3f, side.sign * 1f) }
        when {
            s <= 1f -> {
                // First pull: from the start to the power position, arms straight.
                val e = Ease.IN_OUT.apply(s)
                val bar = lerp(start, power, e)
                hingeTo(bar, lerp(startLean, 12f, e), ahead = lerp(0.07f, 0.0f, e), handZ = half)
                stance(0f)
                barbell(bar, plateRadius = 0.225f)
                for (side in Side.entries) body.arm(side, bar.withZ(side.sign * half), straight(side))
            }
            s <= 2f -> {
                // The jump: hips and knees extend onto the toes with a shrug; the elbows rise and pull the bar up.
                val e = Ease.IN_OUT.apply(s - 1f)
                val from = powerPelvis()
                val pelvis = lerp(from, v(0.0f, 0.97f), e)
                body.torso(pelvis, Basis.UPRIGHT.pitch(lerp(12f, -6f, e)), nod = lerp(-6f, 4f, e), shrug = 0.06f * e)
                stance(26f * e)
                if (pullOnly) {
                    // Arms straight: the bar rides up the thighs at arm's length below the shoulders.
                    val lateral = abs(half - BodySize.SHOULDER_HALF)
                    val rel = lerp(v(0f, -armReach(lateral, 0f, 0.985f)), v(0.02f, -armReach(lateral, 0.02f, 0.985f)), e)
                    val bar = body.shoulders().withZ(0f) + rel
                    barbell(bar, plateRadius = 0.225f)
                    for (side in Side.entries) body.arm(side, bar.withZ(side.sign * half), straight(side))
                } else {
                    val bar = lerp(power, v(0.12f, if (snatch) 1.18f else 1.05f), e)
                    barbell(bar, plateRadius = 0.225f)
                    for (side in Side.entries) body.arm(side, bar.withZ(side.sign * half), lerp(straight(side), highPull(side), e))
                }
            }
            s <= 4f -> {
                // Turnover and catch in a quarter (or full) squat (2 → 3), then standing up (3 → 4).
                val catch = Ease.IN_OUT.apply((s - 2f).coerceAtMost(1f))
                val up = Ease.IN_OUT.apply((s - 3f).coerceAtLeast(0f))
                val lean = lerp(lerp(-6f, catchLean, catch), 2f, up)
                val basis = Basis.UPRIGHT.pitch(lean)
                val pelvis = lerp(lerp(v(0.0f, 0.97f), catchPelvis, catch), standPelvis, up)
                body.torso(pelvis, basis, nod = lerp(lerp(4f, -6f, catch), -1f, up), shrug = 0.06f * (1 - catch))
                stance(26f * (1 - catch))
                val pull = v(0.12f, if (snatch) 1.18f else 1.05f)
                if (snatch) {
                    val shoulders = body.shoulders()
                    val over = v(MID_FOOT, shoulders.y + armReach(half - BodySize.SHOULDER_HALF, MID_FOOT - shoulders.x, 0.99f))
                    // In front of the face on the way up.
                    val bar = lerp(pull, over, catch) + V3.X * (0.12f * sin(PI.toFloat() * catch))
                    barbell(bar, plateRadius = 0.225f)
                    for (side in Side.entries) body.arm(side, bar.withZ(side.sign * half), lerp(highPull(side), overheadPole(side), catch))
                } else {
                    val rack = body.onChest(0.14f, -0.07f)
                    val bar = lerp(pull, rack, catch)
                    barbell(bar, plateRadius = 0.225f)
                    for (side in Side.entries) body.arm(side, bar.withZ(side.sign * 0.24f + side.sign * (half - 0.24f) * (1 - catch)), lerp(highPull(side), rackPole(side), catch))
                }
            }
            s <= 5f -> {
                // Down to the hips: standing, the bar comes down in front of the body.
                val e = Ease.IN_OUT.apply(s - 4f)
                body.torso(standPelvis, Basis.UPRIGHT.pitch(2f), nod = -1f)
                stance(0f)
                if (snatch) {
                    val shoulders = body.shoulders()
                    val over = v(MID_FOOT, shoulders.y + armReach(half - BodySize.SHOULDER_HALF, MID_FOOT - shoulders.x, 0.99f))
                    val bar = lerp(over, hang, e) + V3.X * (0.2f * sin(PI.toFloat() * e))
                    barbell(bar, plateRadius = 0.225f)
                    for (side in Side.entries) body.arm(side, bar.withZ(side.sign * half), lerp(overheadPole(side), straight(side), e))
                } else {
                    // The elbows come down out of the rack round the sides (the bar still on the shoulders),
                    // then the bar is lowered in front like a reverse curl, the elbows going back as the arms straighten.
                    val rack = body.onChest(0.14f, -0.07f)
                    val drop = Ease.IN_OUT.apply(((e - 0.45f) / 0.55f).coerceIn(0f, 1f))
                    val bar = lerp(rack, hang, drop) + V3.X * (0.06f * sin(PI.toFloat() * drop))
                    barbell(bar, plateRadius = 0.225f)
                    for (side in Side.entries) {
                        val out = v(0.2f, 0.1f, side.sign * 1f)
                        val under = v(0.2f, -1f, side.sign * 0.4f)
                        val behind = v(-0.6f, -0.3f, side.sign * 1f)
                        val pole = when {
                            e < 0.22f -> lerp(rackPole(side), out, e / 0.22f)
                            e < 0.45f -> lerp(out, under, (e - 0.22f) / 0.23f)
                            e < 0.85f -> lerp(under, behind, (e - 0.45f) / 0.4f)
                            else -> lerp(behind, straight(side), (e - 0.85f) / 0.15f)
                        }
                        body.arm(side, bar.withZ(side.sign * lerp(0.24f, half, drop)), pole)
                    }
                }
            }
            else -> {
                // Back to the start, arms straight.
                val e = Ease.IN_OUT.apply(s - 5f)
                val bar = lerp(hang, start, e)
                hingeTo(bar, lerp(2f, startLean, e), ahead = lerp(-0.03f, 0.07f, e), handZ = half)
                stance(0f)
                barbell(bar, plateRadius = 0.225f)
                for (side in Side.entries) body.arm(side, bar.withZ(side.sign * half), straight(side))
            }
        }
    }

    /**
     * The jerk after a clean, from the pose the clean stands up in (stage 4): a [depth] dip and drive
     * onto the [toes], the bar pressed [press] of the way overhead while the feet [split]; then
     * [lower] brings it down in front to the hang (the pose of stage 5), for the rest of the loop.
     */
    fun jerk(b: FrameBuilder, depth: Float, press: Float, split: Float, toes: Float, lower: Float) = with(b) {
        val rise = liftedAnkle(0f, 0f, toes).y - BodySize.ANKLE
        val dodge = sin(PI.toFloat() * press) * (1f - lower)
        body.torso(standPelvis + v(-0.04f * split, rise - 0.445f * depth - 0.25f * split), Basis.UPRIGHT.pitch(2f + 10f * depth), nod = -1f - 16f * dodge)
        for (side in Side.entries) {
            val front = side == Side.NEAR
            body.stand(
                side,
                -0.03f + if (front) 0.3f * split else -0.55f * split,
                z = side.sign * 0.135f,
                lift = if (front) toes * (1 - split) else toes + 58f * split,
                out = 8f,
                pole = v(1f, 0.1f, side.sign * 0.15f),
            )
        }
        val shoulders = body.shoulders()
        val top = v(shoulders.x - 0.02f, shoulders.y + armReach(0.24f - BodySize.SHOULDER_HALF, -0.02f))
        if (lower <= 0f) {
            val bar = lerp(body.onChest(0.14f, -0.07f), top, Ease.IN_OUT.apply(press)) + V3.X * (0.06f * dodge)
            barbell(bar, plateRadius = 0.225f)
            for (side in Side.entries) {
                val rack = body.chest.dir(0.3f, 1f, side.sign * 0.25f)
                val out = v(0.3f, 0.3f, side.sign * 1f)
                val lockout = v(0.6f, -1f, side.sign * 1f)
                val pole = when {
                    press < 0.05f -> rack
                    press < 0.35f -> lerp(rack, out, (press - 0.05f) / 0.3f)
                    else -> lerp(out, lockout, (press - 0.35f) / 0.65f)
                }
                body.arm(side, bar.withZ(side.sign * 0.24f), pole)
            }
        } else {
            // Down in front of the face and the chest to the hang, the grip widening to the clean's.
            val e = Ease.IN_OUT.apply(lower)
            val bar = lerp(top, hang, e) + V3.X * (0.2f * sin(PI.toFloat() * e))
            barbell(bar, plateRadius = 0.225f)
            for (side in Side.entries) body.arm(side, bar.withZ(side.sign * lerp(0.24f, half, e)), lerp(v(0.6f, -1f, side.sign * 1f), v(-0.3f, 0.2f, side.sign * 1f), e))
        }
    }

    /** Pelvis of the power position (the end of the first pull). */
    private fun powerPelvis(): V3 = hingePelvis(power, 12f, 0f)

    /** Where [hingeTo] puts the pelvis for a bar at [bar] held on straight arms, leaning [lean] with the shoulders [ahead] of it. */
    private fun hingePelvis(bar: V3, lean: Float, ahead: Float): V3 {
        val shoulderY = bar.y + armReach(abs(half - BodySize.SHOULDER_HALF), ahead, straightness = 0.985f)
        return v(bar.x + ahead, shoulderY) - Basis.UPRIGHT.pitch(lean).u * SPINE
    }
}

/**
 * A clean or snatch on the 0..6 stage driver ([Olympic]) over [loop] seconds; [deep] catches it in a
 * full squat, which takes longer to stand up from.
 */
private fun olympicLift(fromFloor: Boolean, snatch: Boolean, deep: Boolean = false): Motion {
    val lift = Olympic(fromFloor, snatch, deep)
    val up = if (deep) 0.35f else 0f
    val loop = (if (fromFloor) 5.0f else 4.6f) + up
    val keys = if (fromFloor) {
        listOf(0f to 0f, 0.4f to 0f, 1.05f to 1f, 1.3f to 2f, 1.58f + up * 0.6f to 3f, 2.15f + up to 4f, 2.6f + up to 4f, 3.75f + up to 5f, 4.7f + up to 6f, loop to 6f)
    } else {
        listOf(0f to 0f, 0.4f to 0f, 0.85f to 1f, 1.1f to 2f, 1.38f + up * 0.6f to 3f, 1.95f + up to 4f, 2.4f + up to 4f, 3.55f + up to 5f, 4.3f + up to 6f, loop to 6f)
    }
    return Motion.cycle(loop, camera = Camera(24f)) { phase ->
        lift.frame(this, ramp(phase * loop, *keys.toTypedArray()))
    }
}

internal fun powerClean() = olympicLift(fromFloor = true, snatch = false)

internal fun hangClean() = olympicLift(fromFloor = false, snatch = false)

internal fun powerSnatch() = olympicLift(fromFloor = true, snatch = true)

internal fun squatClean() = olympicLift(fromFloor = true, snatch = false, deep = true)

internal fun hangPowerSnatch() = olympicLift(fromFloor = false, snatch = true)

internal fun squatSnatch() = olympicLift(fromFloor = true, snatch = true, deep = true)

/**
 * Clean or snatch pull: the first pull from the floor and the jump onto the toes with a shrug, the
 * arms straight throughout, then the bar back down to the floor.
 */
internal fun olympicPull(snatch: Boolean): Motion {
    val lift = Olympic(fromFloor = true, snatch = snatch, pullOnly = true)
    val keys = listOf(0f to 0f, 0.4f to 0f, 1.05f to 1f, 1.35f to 2f, 1.6f to 2f, 2.3f to 1f, 3.1f to 0f, 3.4f to 0f)
    return Motion.cycle(3.4f, camera = Camera(24f)) { phase -> lift.frame(this, ramp(phase * 3.4f, *keys.toTypedArray())) }
}

/**
 * Clean and jerk: a squat clean to standing, then a split jerk, the feet back together with the bar
 * overhead, and the bar lowered in front to the hips and to the floor.
 */
internal fun cleanAndJerk(): Motion {
    val lift = Olympic(fromFloor = true, snatch = false, deep = true)
    val loop = 5.9f
    val clean = listOf(0f to 0f, 0.35f to 0f, 0.95f to 1f, 1.2f to 2f, 1.5f to 3f, 2.15f to 4f)
    return Motion.cycle(loop, camera = Camera(24f)) { phase ->
        val t = phase * loop
        when {
            t < 2.15f -> lift.frame(this, ramp(t, *clean.toTypedArray()))
            t < 4.85f -> {
                val depth = ramp(t, 2.3f to 0f, 2.55f to 0.24f, 2.75f to 0f)
                val toes = ramp(t, 2.68f to 0f, 2.76f to 14f, 2.9f to 0f)
                val press = ramp(t, 2.62f to 0f, 3.12f to 1f)
                val split = ramp(t, 2.76f to 0f, 2.95f to 1f, 3.3f to 1f, 3.75f to 0f)
                lift.jerk(this, depth, press, split, toes, lower = ramp(t, 3.95f to 0f, 4.85f to 1f))
            }
            else -> lift.frame(this, ramp(t, 4.85f to 5f, 5.6f to 6f))
        }
    }
}

/**
 * Snatch balance: the bar on the upper back with a snatch grip; a short dip, then the lifter drives
 * the bar up a little and drops under it into a full overhead squat, arms locking, stands up and
 * lowers the bar back to the upper back.
 */
internal fun snatchBalance() = Motion.cycle(3.2f, camera = Camera(26f, 4f)) { phase ->
    val t = phase * 3.2f
    val depth = ramp(t, 0.3f to 0f, 0.55f to 0.18f, 0.75f to 0.05f, 1.15f to 1f, 1.45f to 1f, 2.15f to 0f)
    val over = ramp(t, 0.72f to 0f, 1.15f to 1f, 2.4f to 1f, 3.0f to 0f)
    val lean = lerp(3f, 30f, depth)
    val basis = Basis.UPRIGHT.pitch(lean)
    val pelvis = v(MID_FOOT - SPINE * sin(rad(lean)) - 0.01f, lerp(0.915f, 0.48f, depth))
    body.torso(pelvis, basis, nod = -lean * 0.3f)
    body.standOn(0f, width = 0.36f, out = 18f, pole = v(1f, 0.1f, 0.3f))
    val half = 0.46f
    val shoulders = body.shoulders()
    val back = pelvis + basis.u * (BodySize.LOWER_SPINE + BodySize.UPPER_SPINE) + basis.dir(-0.13f, -0.06f, 0f)
    val top = v(MID_FOOT, shoulders.y + armReach(half - BodySize.SHOULDER_HALF, MID_FOOT - shoulders.x, straightness = 0.99f))
    val bar = lerp(back, top, Ease.IN_OUT.apply(over)) + V3.Y * (0.06f * sin(PI.toFloat() * over))
    barbell(bar)
    for (side in Side.entries) body.arm(side, bar.withZ(side.sign * half), lerp(basis.dir(-0.7f, -1f, side.sign * 0.4f), v(0f, -0.3f, side.sign * 1f), over))
}

/**
 * One-arm dumbbell snatch: squatting to the dumbbell between the feet, the hips snap forward and the
 * dumbbell travels close to the body straight up to lockout overhead; then back down to the floor.
 */
internal fun dumbbellSnatch() = Motion.rep(
    Track.rep(go = 0.9f, hold = 0.35f, back = 1.1f, rest = 0.3f, goEase = Ease.OUT),
    Camera(26f),
) { p ->
    val hips = body.dumbbellSnatchTorso(p)
    body.standOn(0f, width = 0.42f, out = 14f, pole = v(1f, 0.1f, 0.35f))
    val s = body.shoulder(Side.NEAR)
    // At arm's length below the shoulder while the hips drive (off the floor at the start), then a
    // high pull past the chest and a punch up to lockout.
    val hanging = { at: V3 -> at + v(0.02f, -armReach(0.075f, 0.02f), -0.075f) }
    val pull = Rig().apply { dumbbellSnatchTorso(0.5f) }.shoulder(Side.NEAR)
    val chest = pull + v(0.2f, -0.18f, -0.02f)
    val top = s + v(-0.02f, 0.565f, 0.02f)
    val hand = when {
        p < 0.5f -> hanging(s)
        p < 0.78f -> lerp(hanging(pull), chest, Ease.IN_OUT.apply((p - 0.5f) / 0.28f))
        else -> lerp(chest, top, Ease.IN_OUT.apply((p - 0.78f) / 0.22f))
    }
    body.arm(Side.NEAR, hand, lerp(v(-0.3f, 0.2f, 1f), v(-0.3f, 0.6f, 1f), Ease.IN_OUT.apply(((p - 0.3f) / 0.4f).coerceIn(0f, 1f))))
    body.armAngles(Side.FAR, limbDir(body.chest, Side.FAR, lerp(25f, 20f, hips), 24f), 14f, fold = body.chest.f)
    val axis = lerp(V3.X, v(0.2f, 0f, 1f), Ease.IN_OUT.apply(((p - 0.6f) / 0.4f).coerceIn(0f, 1f))).unit()
    dumbbell(hand, axis = axis)
}

/** The trunk of [dumbbellSnatch] at [p]: from the squat over the dumbbell to standing tall; returns how far the hips have extended. */
private fun Rig.dumbbellSnatchTorso(p: Float): Float {
    val hips = Ease.IN_OUT.apply((p / 0.55f).coerceIn(0f, 1f))
    val lean = lerp(64f, -2f, hips)
    torso(v(lerp(-0.22f, 0f, hips), lerp(0.44f, 0.915f, hips)), Basis.UPRIGHT.pitch(lean), nod = -lean * 0.45f)
    return hips
}

/**
 * Kettlebell sumo high pull: from a wide squat the legs drive up first, then the bell is pulled up
 * along the body to chest height with the elbows high and out; back down into the squat.
 */
internal fun kettlebellHighPull() = Motion.rep(Track.rep(go = 0.9f, hold = 0.2f, back = 1.0f, rest = 0.35f), Camera(34f)) { p ->
    val legs = Ease.IN_OUT.apply((p / 0.6f).coerceIn(0f, 1f))
    val arms = Ease.IN_OUT.apply(((p - 0.45f) / 0.55f).coerceIn(0f, 1f))
    val lean = lerp(36f, 0f, legs)
    // The wide stance stands a little lower than with the feet together.
    body.torso(v(lerp(-0.08f, 0f, legs), lerp(0.48f, 0.85f, legs)), Basis.UPRIGHT.pitch(lean), nod = -lean * 0.4f)
    body.standOn(0.02f, width = 0.62f, out = 30f, pole = v(1f, 0.1f, 0.9f))
    val shoulders = body.shoulders()
    val hang = v(shoulders.x + 0.03f, shoulders.y - armReach(BodySize.SHOULDER_HALF - 0.045f, 0.03f))
    val top = v(shoulders.x + 0.14f, shoulders.y - 0.06f)
    val grip = lerp(hang, top, arms)
    for (side in Side.entries) body.arm(side, grip.withZ(side.sign * 0.045f), lerp(v(-0.3f, 0.2f, side.sign * 1f), v(-0.2f, 1f, side.sign * 1f), arms))
    kettlebell(grip, down = -V3.Y, radius = 0.1f)
}

// ---- Medicine ball, sandbag -----------------------------------------------------------------------

/** A medicine ball (or wall ball, slam ball) at [center]. */
private fun FrameBuilder.medicineBall(center: V3, radius: Float = 0.13f, fit: Boolean = true) {
    add(Ball(center, radius, Ink.PLATE, fit = fit, bias = 0.04f))
    add(Ball(center + v(0.03f, 0.04f, radius * 0.7f), radius * 0.28f, Ink.PLATE_DARK, fit = fit, bias = 0.05f))
}

/** Both hands on the sides of a ball at [ball] (radius [r]), elbows out and down. */
private fun Rig.holdBall(ball: V3, r: Float, across: V3 = V3.Z) {
    for (side in Side.entries) arm(side, ball + across * (side.sign * (r + 0.02f)) - V3.Y * 0.02f, v(0.1f, -1f, side.sign * 0.7f))
}

/** The channels of [wallBall] over its 2.6 s loop. */
private object WallBall {
    const val LOOP = 2.6f
    const val RELEASE = 1.25f
    const val CATCH = 1.95f
    const val TARGET = 2.95f
    fun depth(t: Float) = ramp(t, 0.2f to 0f, 0.85f to 1f, 1.2f to 0f)
}

/**
 * Wall ball: a squat holding the ball at the chest, an explosive drive up that throws it to the
 * target high on the wall in front, catching it on the way down and back to the chest.
 */
internal fun wallBall() = Motion.cycle(WallBall.LOOP, camera = Camera(26f)) { phase ->
    val t = phase * WallBall.LOOP
    val depth = WallBall.depth(t)
    val lean = lerp(4f, 24f, depth)
    val basis = Basis.UPRIGHT.pitch(lean)
    val spine = BodySize.LOWER_SPINE + BodySize.UPPER_SPINE
    val held = basis.u * (spine - 0.17f) + basis.dir(0.27f, 0f, 0f)
    val pelvis = v(MID_FOOT - held.x * 0.55f, lerp(0.915f, 0.47f, depth))
    body.torso(pelvis, basis, nod = -lean * 0.45f - 10f * ramp(t, 1.0f to 0f, 1.3f to 1f, 1.9f to 1f, 2.2f to 0f))
    body.standOn(0f, width = 0.36f, out = 18f, pole = v(1f, 0.1f, 0.3f))
    val chest = pelvis + held
    val shoulders = body.shoulders()
    val overhead = shoulders + v(0.16f, 0.5f)
    // Up from the chest to the release, held up until the catch, then down to the chest.
    val up = ramp(t, 0.95f to 0f, WallBall.RELEASE to 1f, WallBall.CATCH to 1f, 2.35f to 0f)
    val hands = lerp(chest, overhead, up)
    body.holdBall(hands, 0.13f)
    val ball = if (t > WallBall.RELEASE && t < WallBall.CATCH) {
        val mid = (WallBall.RELEASE + WallBall.CATCH) / 2
        val k = (t - mid) / (mid - WallBall.RELEASE)
        v(hands.x + 0.12f * sin(PI.toFloat() * (t - WallBall.RELEASE) / (WallBall.CATCH - WallBall.RELEASE)), WallBall.TARGET - (WallBall.TARGET - hands.y) * k * k)
    } else {
        hands
    }
    medicineBall(ball, fit = false)
    box(v(0.78f, 0f, -0.7f), v(0.9f, 3.2f, 0.7f), Ink.FRAME, fit = false, bias = BEHIND)
}

/** The channels of [medicineBallSlam] over its 3.0 s loop. */
private object Slam {
    const val LOOP = 3.0f
    const val UP = 0.55f
    const val RELEASE = 0.95f
    const val LAND = 1.05f
    const val PICK = 1.6f
    const val STAND = 2.4f
    fun lean(t: Float) = ramp(t, 0.1f to 3f, UP to -3f, 1.0f to 55f, 1.15f to 58f, PICK to 64f, STAND to 3f)
    fun pelvisY(t: Float) = ramp(t, 0.1f to 0.915f, UP to 0.93f, 1.0f to 0.66f, PICK to 0.47f, STAND to 0.915f)
    fun toes(t: Float) = ramp(t, 0.1f to 0f, UP to 20f, 0.8f to 0f)
}

/** Places the trunk of [medicineBallSlam] at [t] seconds: hinged over the ball, or tall on the toes with it overhead. */
private fun slamTorso(rig: Rig, t: Float) {
    val lean = Slam.lean(t)
    val rise = liftedAnkle(0f, 0f, Slam.toes(t)).y - BodySize.ANKLE
    rig.torso(v(0.04f - 0.27f * sin(rad(lean)), Slam.pelvisY(t) + rise), Basis.UPRIGHT.pitch(lean), nod = -lean * 0.45f)
}

/** The shoulders of [medicineBallSlam] at [t] seconds. */
private fun slamShoulders(t: Float): V3 = Rig().also { slamTorso(it, t) }.shoulders()

/**
 * Medicine ball slam: the ball lifted overhead rising onto the toes, slammed into the floor in front
 * of the feet with a hip hinge, picked up again with a flat back and brought back to the chest.
 */
internal fun medicineBallSlam() = Motion.cycle(Slam.LOOP, camera = Camera(24f)) { phase ->
    val t = phase * Slam.LOOP
    val lean = Slam.lean(t)
    val toes = Slam.toes(t)
    slamTorso(body, t)
    body.standOn(0f, width = 0.38f, out = 14f, lift = toes, pole = v(1f, 0.1f, 0.3f))
    val r = 0.14f
    val shoulders = body.shoulders()
    val chest = body.onChest(0.32f, -0.18f)
    val ground = v(0.34f, r)
    // The ball's path while held: chest, overhead, swung down in front to the release; on the floor until picked up.
    val angle = ramp(t, 0.1f to -60f, Slam.UP to 95f, Slam.RELEASE to -40f)
    val swung = shoulders + dirXY(angle) * 0.52f
    val released = shoulders + dirXY(-40f) * 0.52f
    val ball = when {
        t < 0.1f || t >= Slam.STAND -> chest
        t < Slam.UP -> lerp(chest, swung, Ease.IN_OUT.apply((t - 0.1f) / (Slam.UP - 0.1f)))
        t < Slam.RELEASE -> swung
        t < Slam.LAND -> lerp(released, ground, Ease.IN.apply((t - Slam.RELEASE) / (Slam.LAND - Slam.RELEASE)))
        t < Slam.PICK -> ground
        // Up with the body as it stands, at arm's length below the shoulders, and to the chest at the end.
        else -> {
            val rise = (Slam.lean(Slam.PICK) - lean) / (Slam.lean(Slam.PICK) - Slam.lean(Slam.STAND))
            val picked = ground - slamShoulders(Slam.PICK)
            val hanging = shoulders + lerp(picked, v(0.06f, -0.52f), Ease.IN_OUT.apply((rise / 0.4f).coerceAtMost(1f)))
            if (rise < 0.7f) hanging else lerp(hanging, chest, Ease.IN_OUT.apply((rise - 0.7f) / 0.3f))
        }
    }
    // The hands follow the ball, let go of it at the release and take it again on the floor.
    val free = ramp(t, Slam.RELEASE to 0f, Slam.LAND to 1f, 1.3f to 1f, Slam.PICK to 0f)
    val hands = lerp(ball, lerp(released, ground, 0.6f) + V3.Y * 0.08f, free)
    body.holdBall(hands, r)
    medicineBall(ball, r)
}

/**
 * Medicine ball rotational throw, seen from the front: side-on to a wall on the near side, the ball
 * loaded by the far hip, the hips and trunk turn explosively to throw it against the wall; it
 * rebounds into the hands and goes back to the far hip.
 */
internal fun medicineBallRotationalThrow() = Motion.cycle(2.6f, camera = Camera(70f, 8f)) { phase ->
    val t = phase * 2.6f
    val turn = ramp(t, 0.1f to 0f, 0.6f to -45f, 0.95f to 42f, 1.6f to 30f, 2.4f to 0f)
    val release = 0.95f
    val back = 1.55f
    body.torso(v(-0.03f, 0.87f), Basis.UPRIGHT.pitch(10f).turn(0.35f * turn), twist = 0.65f * turn, nod = -6f)
    body.stand(Side.NEAR, 0f, z = 0.24f, out = 12f, pole = v(1f, 0.1f, 0.3f))
    body.stand(Side.FAR, 0f, z = -0.24f, lift = ramp(t, 0.6f to 0f, 0.95f to 28f, 1.4f to 0f), out = 14f, pole = v(1f, 0.1f, -0.3f))
    val r = 0.12f
    val chest = body.chest
    val held = body.neck + chest.dir(0.32f, -0.38f, 0f)
    val wall = 1.15f
    val ball = when {
        t < release || t > back -> held
        else -> {
            val s = (t - release) / (back - release)
            val out = held.withZ(wall - r)
            if (s < 0.5f) lerp(held, out, s / 0.5f) else lerp(out, held, (s - 0.5f) / 0.5f)
        }
    }
    val free = ramp(t, release to 0f, 1.05f to 1f, 1.45f to 1f, back to 0f)
    val hands = lerp(ball, held + chest.dir(0.05f, 0.05f, 0f), free)
    for (side in Side.entries) body.arm(side, hands + chest.l * (side.sign * (r + 0.02f)) - V3.Y * 0.02f, v(0f, -1f, side.sign * 0.4f))
    medicineBall(ball, r, fit = false)
    box(v(-0.5f, 0f, wall), v(0.6f, 2.2f, wall + 0.1f), Ink.FRAME, fit = false, bias = BEHIND)
}

/** Sandbag bear-hug carry: walking tall with the bag hugged high against the chest, elbows in. */
internal fun sandbagCarry() = Motion.cycle(2.2f, cycles = 2, camera = Camera(26f)) { phase ->
    val bob = cos(phase * TAU * 2)
    body.torso(v(0f, 0.87f + 0.012f * bob), Basis.UPRIGHT.pitch(-2f), nod = 2f)
    for (side in Side.entries) gaitLeg(side, (phase + if (side == Side.NEAR) 0f else 0.5f) % 1f, reach = 0.22f, lift = 0.06f, stance = 0.6f)
    val bag = body.onChest(0.22f, -0.2f)
    for (side in Side.entries) body.arm(side, bag + v(0.12f, -0.1f, side.sign * 0.09f), v(-0.3f, -1f, side.sign * 0.7f))
    add(Rod(bag.withZ(-0.24f), bag.withZ(0.24f), 0.14f, Ink.PAD, bias = 0.02f))
}

// ---- Gymnastics: toes-to-bar, GHD sit-up -----------------------------------------------------------

/**
 * Toes-to-bar: from a dead hang the lats press the bar down so the trunk tips back, and the legs rise
 * nearly straight until the toes reach the bar; then they lower without a swing.
 */
internal fun toesToBar() = Motion.rep(Track.rep(go = 1.2f, hold = 0.2f, back = 1.5f, rest = 0.4f), Camera(26f)) { p ->
    val barY = 2.3f
    val swing = rad(lerp(0f, 26f, p))
    val reach = armReach(0.3f - BodySize.SHOULDER_HALF, 0f, 0.985f)
    val shoulder = v(-sin(swing) * reach, barY - cos(swing) * reach)
    hangFrom(shoulder, lean = lerp(0f, -34f, p), nod = lerp(0f, 14f, p))
    for (side in Side.entries) body.arm(side, v(0f, barY, side.sign * 0.3f), v(-0.1f, -1f, side.sign * 1f))
    val raise = lerp(4f, 168f, p)
    val knee = lerp(6f, 14f, p) + 22f * sin(PI.toFloat() * p)
    for (side in Side.entries) body.legAngles(side, limbDir(Basis.UPRIGHT, side, raise, 2f), knee, fold = dirXY(raise - 180f))
    pullUpBar(0f, barY)
}

/**
 * GHD sit-up: seated on the glute-ham developer with the feet hooked under the rollers in front, the
 * trunk leans back to level with the arms reaching overhead, then comes back up past upright with the
 * arms reaching for the feet.
 */
internal fun ghdSitUp() = Motion.rep(Track.rep(go = 1.4f, hold = 0.2f, back = 1.3f, rest = 0.35f), Camera(24f)) { p ->
    val seat = 1.0f
    val lean = lerp(18f, -86f, p)
    val pelvis = v(lerp(0.02f, -0.04f, p), seat + SEAT_HIP)
    body.torso(pelvis, Basis.UPRIGHT.pitch(lean), nod = lerp(16f, 4f, p))
    val rollers = v(0.78f, 0.9f)
    for (side in Side.entries) body.leg(side, rollers.withZ(side.sign * 0.1f) - V3.Y * 0.03f, v(0.3f, 1f, 0f))
    // Arms from reaching for the feet (upright) over the top to overhead in line with the trunk (back at level).
    val feet = angleXY(rollers - body.shoulders())
    val overhead = angleXY(Basis.UPRIGHT.pitch(-86f).u).let { if (it < feet) it + 360f else it }
    for (side in Side.entries) {
        val dir = dirXY(lerp(feet, overhead, p)) + body.chest.l * (side.sign * 0.12f)
        body.armAngles(side, dir.unit(), 8f, fold = body.chest.f)
    }
    // Seat pad, the rollers above and below the ankles, the frame.
    pad(v(-0.22f, seat - 0.05f), v(0.18f, seat - 0.05f), width = 0.42f, thick = 0.1f, bias = -0.2f)
    for (y in listOf(rollers.y + 0.05f, rollers.y - 0.12f)) add(Rod(v(rollers.x + 0.02f, y, -0.22f), v(rollers.x + 0.02f, y, 0.22f), 0.05f, Ink.PAD, bias = 0.1f))
    add(Rod(v(-0.05f, 0.02f), v(-0.05f, seat - 0.1f), 0.04f, Ink.FRAME, bias = -0.3f))
    add(Rod(v(-0.05f, seat - 0.12f), v(rollers.x + 0.02f, rollers.y - 0.18f), 0.035f, Ink.FRAME, bias = -0.3f))
    add(Rod(v(rollers.x + 0.02f, 0.02f), v(rollers.x + 0.02f, rollers.y - 0.18f), 0.035f, Ink.FRAME, bias = -0.3f))
    add(Rod(v(-0.45f, 0.02f), v(1.0f, 0.02f), 0.03f, Ink.FRAME, bias = -0.4f))
}
