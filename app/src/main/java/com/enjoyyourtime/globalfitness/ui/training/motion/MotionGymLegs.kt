package com.enjoyyourtime.globalfitness.ui.training.motion

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

// Lower-body patterns added with the larger catalog: squat and lunge variations, hinges, calf
// raises, standing cable hip work, reverse hyperextensions and single-leg bridges. They reuse the
// shared helpers (stance, props, timings) and leave the original patterns untouched.

/**
 * Places the torso for a hinge holding a weight at [bar] with nearly straight arms: the spine
 * leans [lean] degrees and the shoulders sit [ahead] metres in front of the hands.
 */
internal fun FrameBuilder.hingeTo(bar: V3, lean: Float, ahead: Float, handZ: Float) {
    val basis = Basis.UPRIGHT.pitch(lean)
    val lateral = abs(handZ - BodySize.SHOULDER_HALF)
    val shoulderY = bar.y + armReach(lateral, ahead, straightness = 0.985f)
    val toShoulder = basis.u * (BodySize.LOWER_SPINE + BodySize.UPPER_SPINE - BodySize.SHOULDER_DROP)
    val pelvis = v(bar.x + ahead, shoulderY) - toShoulder
    body.torso(pelvis.withZ(0f), basis, nod = -lean * 0.5f)
}

/**
 * Belt squat: standing on the machine's platform with the belt round the hips and its chain
 * between the feet, hands on the front handles; the trunk stays tall while the hips sink.
 */
internal fun beltSquat() = Motion.rep(Tempo.lowerFirst(), Camera(24f)) { p ->
    val platform = 0.12f
    val lean = lerp(4f, 20f, p)
    val basis = Basis.UPRIGHT.pitch(lean)
    val pelvis = v(lerp(0f, -0.12f, p), platform + lerp(0.915f, 0.5f, p))
    body.torso(pelvis, basis, nod = -lean * 0.4f)
    body.standOn(0f, width = 0.36f, out = 16f, floor = platform, pole = v(1f, 0.1f, 0.3f))
    box(v(-0.45f, 0f, -0.45f), v(0.4f, platform, 0.45f), Ink.FRAME, bias = -0.4f)
    val handle = v(0.42f, platform + 1.08f)
    for (side in Side.entries) body.arm(side, handle.withZ(side.sign * 0.22f), v(-0.3f, -1f, side.sign * 0.6f))
    add(Rod(handle.withZ(-0.3f), handle.withZ(0.3f), 0.02f, Ink.METAL))
    add(Rod(v(0.5f, platform), handle + V3.X * 0.08f, 0.035f, Ink.FRAME, bias = -0.3f))
    // The belt across the front of the hips and the chain down to the lever under the platform.
    val front = body.onHips(0.11f, -0.02f)
    add(Rod(body.onHips(0.1f, -0.02f, -0.15f), body.onHips(0.1f, -0.02f, 0.15f), 0.03f, Ink.PAD, bias = 0.05f))
    add(Cord(listOf(front, v(front.x + 0.03f, platform - 0.02f)), 0.014f, Ink.METAL, bias = 0.04f))
}

/** Dumbbell sumo squat: a wide stance with the toes out, one dumbbell held upright between the legs on long arms. */
internal fun sumoSquat() = Motion.rep(Tempo.lowerFirst(), Camera(40f)) { p ->
    val lean = lerp(4f, 18f, p)
    val basis = Basis.UPRIGHT.pitch(lean)
    body.torso(v(lerp(0f, -0.06f, p), lerp(0.86f, 0.52f, p)), basis, nod = -lean * 0.4f)
    body.standOn(0.02f, width = 0.7f, out = 32f, pole = v(1f, 0.1f, 0.9f))
    val s = body.shoulders()
    val hands = v(s.x + 0.02f, s.y - armReach(BodySize.SHOULDER_HALF - 0.05f, 0.02f), 0f)
    for (side in Side.entries) body.arm(side, hands.withZ(side.sign * 0.05f), v(-0.3f, 0f, side.sign * 1f))
    dumbbell(hands - V3.Y * 0.11f, axis = V3.Y, headRadius = 0.075f)
}

/**
 * Zercher squat: the bar sits in the crooks of the elbows in front of the body, hands together in
 * front of the chest; an upright squat to below parallel.
 */
internal fun zercherSquat() = Motion.rep(Tempo.lowerFirst(), Camera(22f)) { p ->
    val lean = lerp(4f, 24f, p)
    val basis = Basis.UPRIGHT.pitch(lean)
    val pelvis = v(0.05f - 0.25f * sin(rad(lean)) - 0.08f * p, lerp(0.915f, 0.48f, p))
    body.torso(pelvis, basis, nod = -lean * 0.4f)
    body.standOn(0f, width = 0.38f, out = 18f, pole = v(1f, 0.1f, 0.3f))
    for (side in Side.entries) body.arm(side, body.onChest(0.3f, -0.12f, side.sign * 0.05f), basis.dir(0.6f, -1f, side.sign * 0.25f))
    val elbows = (body.armOf(Side.NEAR).elbow + body.armOf(Side.FAR).elbow) * 0.5f
    barbell(elbows.withZ(0f) + basis.u * 0.045f + basis.f * 0.02f)
}

/**
 * Jump squat: down into a squat with the arms back, then an explosive jump with the arms swinging
 * up, landing softly into a quarter squat and standing up.
 */
internal fun jumpSquat() = Motion.cycle(2.8f, camera = Camera(22f)) { phase ->
    fun part(a: Float, b: Float) = Ease.IN_OUT.apply(((phase - a) / (b - a)).coerceIn(0f, 1f))
    val stand = 0.915f
    val bottom = 0.52f
    val air = 1.1f
    val quarter = 0.78f
    // Pelvis height and arm swing (degrees forward) through the jump.
    val (y, arms) = when {
        phase < 0.44f -> part(0.08f, 0.44f).let { lerp(stand, bottom, it) to lerp(10f, -25f, it) }
        phase < 0.62f -> part(0.44f, 0.62f).let { lerp(bottom, air, it) to lerp(-25f, 110f, it) }
        phase < 0.75f -> part(0.62f, 0.75f).let { lerp(air, stand, it) to lerp(110f, 60f, it) }
        phase < 0.86f -> part(0.75f, 0.86f).let { lerp(stand, quarter, it) to lerp(60f, 30f, it) }
        else -> part(0.86f, 0.99f).let { lerp(quarter, stand, it) to lerp(30f, 10f, it) }
    }
    val depth = ((stand - y) / (stand - bottom)).coerceIn(0f, 1f)
    val lean = lerp(4f, 34f, depth)
    val basis = Basis.UPRIGHT.pitch(lean)
    body.torso(v(0.05f - 0.36f * sin(rad(lean)), y), basis, nod = -lean * 0.45f)
    // Off the floor the feet follow the body, toes pointed.
    val lift = (y - stand).coerceAtLeast(0f)
    for (side in Side.entries) {
        body.stand(side, 0f, z = side.sign * 0.17f, floor = lift, lift = (lift / 0.18f).coerceAtMost(1f) * 30f, out = 18f, pole = v(1f, 0.1f, side.sign * 0.3f))
        body.armAngles(side, limbDir(Basis.UPRIGHT, side, arms, 6f), 10f, fold = V3.Y)
    }
}

/**
 * Pistol squat: on the near leg all the way down while the far leg reaches forward straight and the
 * arms go forward for balance.
 */
internal fun pistolSquat() = Motion.rep(Track.rep(go = 1.4f, hold = 0.2f, back = 1.2f, rest = 0.4f), Camera(30f)) { p ->
    val lean = lerp(6f, 40f, p)
    val basis = Basis.UPRIGHT.pitch(lean)
    body.torso(v(lerp(0f, -0.22f, p), lerp(0.91f, 0.4f, p), 0.05f), basis, nod = -lean * 0.4f)
    body.stand(Side.NEAR, 0.02f, z = 0.12f, out = 8f, pole = v(1f, 0.1f, 0.15f))
    body.legAngles(Side.FAR, limbDir(Basis.UPRIGHT, Side.FAR, lerp(34f, 86f, p), 2f), lerp(38f, 4f, p), fold = -V3.Y)
    for (side in Side.entries) body.armAngles(side, limbDir(Basis.UPRIGHT, side, lerp(35f, 88f, p), 6f), 8f, fold = V3.Y)
}

/**
 * Lateral lunge and Cossack squat, seen from the front: feet wide, the hips sit over the near foot
 * while the far leg stays long. [deep] goes lower and turns the far foot up onto its heel (Cossack);
 * otherwise a dumbbell is held goblet-style at the chest.
 */
internal fun lateralLunge(deep: Boolean) = Motion.rep(Tempo.lowerFirst(), Camera(78f, 6f)) { p ->
    val feet = if (deep) 0.5f else 0.46f
    val shift = lerp(0f, if (deep) 0.34f else 0.28f, p)
    val legs = BodySize.THIGH + BodySize.SHIN - 0.015f
    val top = BodySize.ANKLE + sqrt(legs * legs - (feet - BodySize.HIP_HALF) * (feet - BodySize.HIP_HALF))
    val lean = lerp(6f, if (deep) 26f else 30f, p)
    val basis = Basis.UPRIGHT.pitch(lean)
    body.torso(v(lerp(0f, -0.08f, p), lerp(top, if (deep) 0.42f else 0.5f, p), shift), basis, nod = -lean * 0.4f)
    body.stand(Side.NEAR, 0f, z = feet, out = 20f, pole = v(1f, 0.1f, 0.35f))
    if (deep) {
        // The long leg's foot rolls onto its heel, toes up.
        val out = rad(14f)
        val flat = v(cos(out), 0f, -sin(out))
        val heel = v(0f, BodySize.ANKLE - BodySize.SOLE, -feet) - flat * BodySize.HEEL
        val a = rad(lerp(0f, 55f, p))
        val dir = flat * cos(a) + V3.Y * sin(a)
        val up = V3.Y * cos(a) - flat * sin(a)
        body.leg(Side.FAR, heel + dir * BodySize.HEEL + up * BodySize.SOLE, v(1f, 0.2f, 0f), Foot(dir, up))
    } else {
        body.stand(Side.FAR, 0f, z = -feet, out = 14f, pole = v(1f, 0.2f, -0.1f))
    }
    if (deep) {
        for (side in Side.entries) body.arm(side, body.onChest(0.3f, -0.16f, side.sign * 0.04f), basis.dir(0.2f, -1f, side.sign * 0.8f))
    } else {
        val load = body.onChest(0.25f, -0.2f)
        for (side in Side.entries) body.arm(side, load + basis.u * 0.05f + V3.Z * (side.sign * 0.06f), basis.dir(0.1f, -1f, side.sign * 0.6f))
        dumbbell(load - basis.u * 0.03f, axis = basis.u, headRadius = 0.075f)
    }
}

/**
 * Reverse lunge with dumbbells: the far foot steps back and the rear knee drops towards the floor
 * while the front (near) foot stays planted; then back to standing.
 */
internal fun reverseLunge() = Motion.rep(Track.rep(go = 1.3f, hold = 0.2f, back = 1.1f, rest = 0.4f), Camera(24f)) { p ->
    val step = Ease.IN_OUT.apply((p / 0.42f).coerceIn(0f, 1f))
    val sink = Ease.IN_OUT.apply(((p - 0.3f) / 0.7f).coerceIn(0f, 1f))
    body.torso(v(lerp(0f, -0.3f, step), lerp(lerp(BodySize.STAND, 0.86f, step), 0.48f, sink)), Basis.UPRIGHT.pitch(4f))
    body.stand(Side.NEAR, 0f, z = 0.11f)
    body.stand(
        Side.FAR,
        lerp(-0.02f, -0.68f, step),
        z = -0.11f,
        floor = 0.07f * sin(PI.toFloat() * step) * (1 - sink),
        lift = lerp(0f, 52f, Ease.IN_OUT.apply(((p - 0.15f) / 0.85f).coerceIn(0f, 1f))),
        pole = v(0.4f, -1f, 0f),
    )
    for (side in Side.entries) {
        body.hang(side, forward = 0f, out = 8f)
        dumbbell(body.handOf(side), axis = V3.X)
    }
}

/**
 * Single-leg Romanian deadlift: standing on the near leg with the knee soft, the trunk hinges
 * forward as the far leg rises behind in line with it; a dumbbell hangs from the far hand.
 */
internal fun singleLegRomanianDeadlift() = Motion.rep(Tempo.lowerFirst(), Camera(26f)) { p ->
    val lean = lerp(2f, 78f, p)
    val basis = Basis.UPRIGHT.pitch(lean)
    body.torso(v(lerp(0f, -0.1f, p), lerp(0.9f, 0.84f, p), 0.06f), basis, nod = -lean * 0.5f)
    body.stand(Side.NEAR, 0f, z = 0.12f, out = 6f, pole = v(1f, 0.1f, 0.1f))
    body.legAngles(Side.FAR, limbDir(Basis.UPRIGHT, Side.FAR, -lerp(16f, lean + 2f, p), 2f), lerp(42f, 6f, p * p), fold = -V3.X)
    for (side in Side.entries) {
        val s = body.shoulder(side)
        val out = if (side == Side.FAR) 0.02f else 0.06f
        body.arm(side, s + v(0.02f, -armReach(out, 0.02f), side.sign * out), v(-0.3f, 0.2f, side.sign * 1f))
    }
    dumbbell(body.handOf(Side.FAR))
}

/** Rack pull: a deadlift from the safety pins of a rack, the bar starting just above the knees. */
internal fun rackPull() = Motion.rep(Track.rep(go = 0.9f, hold = 0.35f, back = 1.0f, rest = 0.45f), Camera(22f)) { p ->
    val handZ = 0.27f
    val barX = 0.12f
    val pins = 0.55f
    val barY = lerp(pins, 0.8f, Ease.IN_OUT.apply(p))
    hingeTo(v(barX, barY), lerp(38f, -2f, p), ahead = lerp(0.05f, -0.03f, p), handZ = handZ)
    body.standOn(-0.03f, width = 0.26f, out = 6f, pole = v(1f, 0.1f, 0.12f))
    barbell(v(barX, barY), plateRadius = 0.225f)
    for (side in Side.entries) body.arm(side, v(barX, barY, side.sign * handZ), v(-0.3f, 0.2f, side.sign * 1f))
    for (s in listOf(-1f, 1f)) {
        post(barX - 0.3f, 2.0f, z = s * 0.62f)
        post(barX + 0.3f, 2.0f, z = s * 0.62f)
        add(Rod(v(barX - 0.3f, pins - 0.04f, s * 0.62f), v(barX + 0.3f, pins - 0.04f, s * 0.62f), 0.022f, Ink.FRAME, fit = false, bias = BEHIND))
    }
}

/** Kettlebell deadlift: the bell between the feet, both hands on the handle; hinge down and stand tall. */
internal fun kettlebellDeadlift() = Motion.rep(Track.rep(go = 1.0f, hold = 0.3f, back = 1.05f, rest = 0.45f), Camera(30f)) { p ->
    val grip = v(0.06f, lerp(0.29f, 0.75f, Ease.IN_OUT.apply(p)))
    hingeTo(grip, lerp(50f, -2f, p), ahead = lerp(0.06f, -0.03f, p), handZ = 0.05f)
    body.standOn(0f, width = 0.42f, out = 14f, pole = v(1f, 0.1f, 0.35f))
    for (side in Side.entries) body.arm(side, grip.withZ(side.sign * 0.045f), v(-0.3f, 0.2f, side.sign * 1f))
    kettlebell(grip, down = -V3.Y, radius = 0.1f)
}

/** Seated good morning: sitting on a bench with the bar on the upper back, the trunk hinges forward from the hips and back up. */
internal fun seatedGoodMorning() = Motion.rep(Tempo.lowerFirst(), Camera(24f)) { p ->
    val lean = lerp(0f, 60f, p)
    seated(seatTop = Kit.BENCH_TOP, recline = -lean, footX = 0.45f, width = 0.6f, nod = -lean * 0.5f, drawSeat = false)
    flatBench(-0.7f, 0.25f)
    val bar = body.onChest(-0.13f, -0.06f)
    barbell(bar)
    for (side in Side.entries) body.arm(side, bar.withZ(side.sign * 0.44f), body.chest.dir(-0.7f, -1f, side.sign * 0.4f))
}

/** Band good morning: standing on the band with its loop behind the neck, hinging forward with a flat back. */
internal fun bandGoodMorning() = Motion.rep(Tempo.lowerFirst(), Camera(24f)) { p ->
    val lean = lerp(0f, 74f, p)
    val basis = Basis.UPRIGHT.pitch(lean)
    val ankleX = -0.02f
    val hipX = lerp(0f, -0.21f, p)
    val legReach = 0.84f
    val dx = hipX - ankleX
    body.torso(v(hipX, BodySize.ANKLE + sqrt(legReach * legReach - dx * dx)), basis, nod = -lean * 0.5f)
    body.standOn(ankleX, width = 0.28f, out = 8f)
    for (side in Side.entries) {
        val hand = body.onChest(0.18f, -0.14f, side.sign * 0.28f)
        body.arm(side, hand, basis.dir(0.3f, -1f, side.sign * 0.6f))
        band(listOf(v(ankleX + 0.05f, 0.03f, side.sign * 0.14f), hand, body.onChest(-0.1f, 0.0f, side.sign * 0.1f)))
    }
}

/** Smith machine Romanian deadlift: the bar runs straight down the rails to mid-shin as the hips push back. */
internal fun smithRomanianDeadlift() = Motion.rep(Tempo.lowerFirst(), Camera(22f)) { p ->
    val barX = 0.13f
    val barY = lerp(0.8f, 0.38f, p)
    hingeTo(v(barX, barY), lerp(-2f, 72f, p), ahead = lerp(-0.03f, 0.06f, p), handZ = 0.25f)
    body.standOn(-0.03f, width = 0.24f, out = 4f, pole = v(1f, 0.1f, 0.1f))
    barbell(v(barX, barY))
    for (side in Side.entries) body.arm(side, v(barX, barY, side.sign * 0.25f), v(-0.3f, 0.2f, side.sign * 1f))
    smithRails(barX, 1.9f)
}

/** Calf raise set-ups added with the larger catalog. */
internal enum class CalfRaiseKind { DUMBBELLS, SEATED_DUMBBELLS, SINGLE_LEG, DONKEY }

/**
 * Calf raises on a step, heels dropping below it and rising high: standing with dumbbells, seated
 * with dumbbells on the knees, on one leg holding a post, or hinged forward in the donkey machine.
 */
internal fun calfRaiseVariant(kind: CalfRaiseKind) = Motion.rep(Tempo.short(), Camera(24f)) { p ->
    val step = 0.12f
    val lift = lerp(-16f, 34f, p)
    val footX = if (kind == CalfRaiseKind.SEATED_DUMBBELLS) 0.3f else -0.07f
    box(v(footX + 0.08f, 0f, -0.35f), v(footX + 0.5f, step, 0.35f), Ink.PAD, bias = -0.25f)
    when (kind) {
        CalfRaiseKind.SEATED_DUMBBELLS -> {
            val seatTop = Kit.BENCH_TOP
            body.torso(v(-0.1f, seatTop + SEAT_HIP), Basis.UPRIGHT.pitch(8f))
            flatBench(-0.8f, 0.05f, seatTop)
            for (side in Side.entries) body.stand(side, footX, z = side.sign * 0.13f, floor = step, lift = lift, out = 4f, pole = v(1f, 0.2f, 0f))
            for (side in Side.entries) {
                val knee = body.legOf(side).knee
                val bell = knee + v(-0.07f, BodySize.KNEE_RADIUS + 0.11f, 0f)
                dumbbell(bell, axis = V3.Y, headRadius = 0.07f)
                body.arm(side, bell + V3.Y * 0.1f + V3.Z * (side.sign * 0.03f), v(-0.3f, -1f, side.sign * 0.6f))
            }
        }
        CalfRaiseKind.DONKEY -> {
            val ankle = liftedAnkle(footX, step, lift)
            val pelvis = v(ankle.x - 0.03f, ankle.y + 0.84f)
            body.torso(pelvis, Basis.UPRIGHT.pitch(82f), nod = -30f)
            body.standOn(footX, width = 0.26f, out = 4f, floor = step, lift = lift)
            // The machine's pad presses on the hips; the hands hold the front handles.
            add(Block(pelvis + V3.Y * 0.16f, Basis.UPRIGHT, v(0.16f, 0.04f, 0.2f), Ink.PAD, bias = 0.05f))
            add(Rod(pelvis + v(0.12f, 0.2f), v(0.75f, 0.95f), 0.03f, Ink.FRAME, bias = -0.3f))
            val handle = v(0.78f, 0.78f)
            for (side in Side.entries) body.arm(side, handle.withZ(side.sign * 0.2f), v(0f, 1f, side.sign * 0.8f))
            add(Rod(handle.withZ(-0.26f), handle.withZ(0.26f), 0.02f, Ink.METAL))
            add(Rod(v(0.82f, 0f), v(0.82f, 1.0f), 0.04f, Ink.FRAME, fit = false, bias = -0.5f))
        }
        else -> {
            val single = kind == CalfRaiseKind.SINGLE_LEG
            val ankle = liftedAnkle(footX, step, lift)
            body.torso(v(ankle.x - 0.01f, ankle.y + 0.85f, if (single) 0.06f else 0f))
            if (single) {
                body.stand(Side.NEAR, footX, z = 0.12f, floor = step, lift = lift, out = 4f)
                body.legAngles(Side.FAR, limbDir(Basis.UPRIGHT, Side.FAR, -4f, 2f), 78f, fold = -V3.X)
                // A hand on a post for balance, the other on the hip.
                val grip = v(0.42f, 1.22f, -0.18f)
                body.arm(Side.FAR, grip, v(-0.3f, -1f, -0.6f))
                body.arm(Side.NEAR, body.onHips(0.02f, 0.12f, 0.2f), v(-0.6f, 0f, 1f))
                post(0.47f, 2.0f, z = -0.18f, radius = 0.035f)
            } else {
                body.standOn(footX, width = 0.26f, out = 4f, floor = step, lift = lift)
                for (side in Side.entries) {
                    body.hang(side, forward = 2f, out = 8f)
                    dumbbell(body.handOf(side), axis = V3.X)
                }
            }
        }
    }
}

/**
 * Standing cable hip adduction / abduction, seen from the front: on the far leg, the near ankle
 * strapped to a low pulley. [adduct] pulls the leg in across the standing one against a pulley on the
 * near side; otherwise it lifts out to the side against a pulley on the far side.
 */
internal fun cableHip(adduct: Boolean) = Motion.rep(Tempo.liftFirst(), Camera(80f, 6f)) { p ->
    body.torso(v(0f, BodySize.STAND, -0.05f), Basis.UPRIGHT.roll(-3f))
    body.stand(Side.FAR, 0f, z = -0.14f, out = 6f)
    val out = if (adduct) lerp(30f, -10f, p) else lerp(-6f, 34f, p)
    body.legAngles(Side.NEAR, limbDir(Basis.UPRIGHT, Side.NEAR, 10f, out), 10f, fold = -V3.X, foot = Foot.flat(Side.NEAR, 4f))
    val towerZ = if (adduct) 0.72f else -0.72f
    val holdSide = if (adduct) Side.NEAR else Side.FAR
    body.arm(holdSide, v(0.04f, 1.15f, towerZ - holdSide.sign * 0.14f), v(-0.3f, -1f, holdSide.sign * 0.6f))
    body.arm(holdSide.other, body.onHips(0.02f, 0.12f, holdSide.other.sign * 0.2f), v(-0.6f, 0f, holdSide.other.sign * 1f))
    val ankle = body.legOf(Side.NEAR).ankle
    add(Rod(ankle - V3.X * 0.06f, ankle + V3.X * 0.06f, 0.03f, Ink.PAD, bias = 0.05f))
    cable(v(0.04f, 0.14f, towerZ), ankle)
    add(Block(v(0.04f, 1.05f, towerZ + (if (adduct) 0.08f else -0.08f)), Basis.UPRIGHT, v(0.2f, 1.05f, 0.08f), Ink.FRAME, fit = false, bias = -0.6f))
}

/**
 * Reverse hyperextension: lying face down on the high pad with the hips at its edge, the legs
 * swing from hanging down to level behind, against the pendulum's roller at the ankles.
 */
internal fun reverseHyperextension() = Motion.rep(Tempo.liftFirst(), Camera(24f)) { p ->
    val top = 1.0f
    body.torso(v(-0.05f, top + 0.1f), Basis.PRONE, nod = -10f)
    pad(v(-0.08f, top - 0.04f), v(0.62f, top - 0.04f), width = 0.38f, bias = -0.2f)
    for (x in listOf(0.05f, 0.5f)) add(Rod(v(x, 0.02f), v(x, top - 0.08f), 0.035f, Ink.FRAME, bias = -0.3f))
    val handle = v(0.78f, top - 0.02f)
    for (side in Side.entries) body.arm(side, handle.withZ(side.sign * 0.2f), v(0f, 1f, side.sign * 0.8f))
    add(Rod(handle.withZ(-0.26f), handle.withZ(0.26f), 0.02f, Ink.METAL))
    val a = rad(lerp(-88f, 6f, p))
    val dir = v(-cos(a), sin(a), 0f)
    val fold = dirXY(angleXY(dir) - 90f)
    for (side in Side.entries) body.legAngles(side, dir + V3.Z * (side.sign * 0.03f), lerp(10f, 4f, p), fold = fold)
    val leg = body.legOf(Side.NEAR)
    val roller = leg.ankle + fold * 0.07f
    add(Rod(roller.withZ(-0.2f), roller.withZ(0.2f), 0.045f, Ink.PAD))
    add(Rod(roller.withZ(0.24f), v(-0.1f, 0.42f, 0.24f), 0.025f, Ink.FRAME, bias = 0.1f))
    add(Rod(v(-0.6f, 0.03f), v(0.7f, 0.03f), 0.03f, Ink.FRAME, bias = -0.4f))
}

/** Single-leg glute bridge: one foot planted, the other leg held straight in line with the thigh while the hips rise. */
internal fun singleLegGluteBridge() = Motion.rep(Tempo.liftFirst(), Camera(-24f)) { p ->
    val shoulders = v(-0.47f, 0.145f)
    val angle = lerp(2f, 30f, p)
    val spine = BodySize.LOWER_SPINE + BodySize.UPPER_SPINE - BodySize.SHOULDER_DROP
    val pelvis = shoulders + dirXY(angle) * spine
    body.torso(pelvis, Basis.UPRIGHT.pitch(-90f - angle), nod = lerp(12f, 40f, p))
    body.stand(Side.NEAR, 0.3f, z = 0.12f, out = 8f, pole = v(0.3f, 1f, 0.2f))
    val near = body.legOf(Side.NEAR)
    val thigh = (near.knee - near.hip).unit()
    body.legAngles(Side.FAR, (thigh - V3.Z * 0.04f).unit(), 4f, fold = -V3.Y)
    // Arms flat on the floor beside the body, nearly straight.
    for (side in Side.entries) body.arm(side, v(0.06f, BodySize.FIST_RADIUS, side.sign * 0.28f), v(0f, -0.2f, side.sign * 1f))
    mat()
}
