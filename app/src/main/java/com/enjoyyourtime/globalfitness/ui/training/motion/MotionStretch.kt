package com.enjoyyourtime.globalfitness.ui.training.motion

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// Stretches and mobility drills: slow holds that breathe (a small, slow change around the stretched
// position) or slow repetitions. Positions on the floor, kneeling, seated and standing.

private const val TAU = 2 * PI.toFloat()

/** 0 → 1 → 0 over the phase of a cycle, easing in and out: a slow breath deepening a stretch. */
internal fun breath(phase: Float): Float = (1 - cos(phase * TAU)) / 2

/** Lying hamstring stretch: on the back, the near leg raised straight with a strap round the foot, the hands drawing it in. */
internal fun hamstringStretch() = Motion.cycle(5f, camera = Camera(-26f, 6f)) { phase ->
    val b = breath(phase)
    body.torso(v(0f, 0.115f), Basis.SUPINE, nod = 10f)
    val raise = lerp(80f, 90f, b)
    body.legAngles(Side.NEAR, v(cos(rad(raise)), sin(rad(raise)), 0.03f), 4f, fold = dirXY(raise - 90f), foot = Foot(dirXY(raise + 90f), dirXY(raise + 180f)))
    body.legAngles(Side.FAR, v(1f, 0.03f, -0.03f), 3f, fold = -V3.Y, foot = Foot(dirXY(70f), dirXY(160f)))
    val leg = body.legOf(Side.NEAR)
    val sole = (leg.heel + leg.toe) * 0.5f
    val shoulders = body.shoulders()
    val grip = shoulders + (sole - shoulders).unit() * 0.48f
    for (side in Side.entries) body.arm(side, grip.withZ(0.09f + side.sign * 0.05f), v(0f, -0.3f, side.sign * 1f))
    band(listOf(body.handOf(Side.NEAR), sole + V3.Z * 0.04f))
    band(listOf(body.handOf(Side.FAR), sole - V3.Z * 0.02f))
    mat()
}

/**
 * Half-kneeling hip flexor stretch (or the couch stretch, [couch]: the rear shin up against a bench
 * behind): the near knee down (the stretched side, nearest the viewer), the far foot forward; the
 * hips ease forward and the trunk stays tall.
 */
internal fun kneelingHipStretch(couch: Boolean) = Motion.cycle(5f, camera = Camera(26f)) { phase ->
    val b = breath(phase)
    val knee = v(-0.28f, BodySize.KNEE_RADIUS + 0.006f, BodySize.HIP_HALF)
    val thigh = if (couch) lerp(70f, 63f, b) else lerp(78f, 66f, b)
    val hip = knee + dirXY(thigh) * BodySize.THIGH
    val pelvis = hip.withZ(0f)
    body.torso(pelvis, Basis.UPRIGHT.pitch(-4f * b), nod = 2f)
    if (couch) {
        val ankle = knee + dirXY(97f) * BodySize.SHIN
        body.leg(Side.NEAR, ankle, v(1f, -0.3f, 0f), Foot(dirXY(124f), dirXY(214f)))
        box(v(knee.x - 0.5f, 0f, -0.32f), v(knee.x - 0.11f, 0.56f, 0.36f), Ink.PAD, bias = -0.4f)
    } else {
        body.leg(Side.NEAR, knee + v(-0.42f, 0.11f, 0f), v(1f, -1.5f, 0f), Foot(dirXY(250f), dirXY(340f)))
    }
    body.stand(Side.FAR, pelvis.x + 0.45f, z = -0.12f, pole = v(1f, 0.25f, -0.1f))
    val front = body.legOf(Side.FAR).knee
    for (side in Side.entries) body.arm(side, front + v(-0.03f, 0.08f, side.sign * 0.06f + 0.06f), v(-0.3f, -1f, side.sign * 0.8f))
    mat()
}

/**
 * Pigeon stretch, folded forward: the far shin across in front of the hips, the near leg long behind
 * (nearest the viewer); the trunk rests forward on the forearms and sinks a little with each breath.
 */
internal fun pigeonStretch() = Motion.cycle(5.4f, camera = Camera(30f, 12f)) { phase ->
    val lean = lerp(70f, 77f, breath(phase))
    body.torso(v(0f, 0.24f), Basis.UPRIGHT.pitch(lean), nod = -lean * 0.4f)
    body.legAngles(Side.FAR, v(0.84f, -0.29f, -0.37f).unit(), 100f, fold = V3.Z, foot = Foot(V3.X, V3.Y))
    body.leg(Side.NEAR, v(-0.81f, 0.17f, 0.12f), v(0.3f, -1.5f, 0f), Foot(dirXY(250f), dirXY(340f)))
    for (side in Side.entries) body.arm(side, v(0.76f, BodySize.FIST_RADIUS, side.sign * 0.16f), v(-0.2f, -1f, side.sign * 0.3f))
    mat()
}

/** Butterfly stretch, seen from the front: seated with the soles together and the knees out, hands on the feet, leaning forward a little. */
internal fun butterflyStretch() = Motion.cycle(5f, camera = Camera(76f, 22f)) { phase ->
    val lean = lerp(12f, 26f, breath(phase))
    body.torso(v(0f, 0.13f), Basis.UPRIGHT.pitch(lean), nod = -lean * 0.3f)
    for (side in Side.entries) {
        body.leg(side, v(0.36f, 0.1f, side.sign * 0.07f), v(0.2f, 0.6f, side.sign * 1f), Foot(v(0.96f, -0.25f, 0f).unit(), V3.Z * side.sign))
        body.arm(side, v(0.42f, 0.13f, side.sign * 0.06f), v(-0.3f, -0.6f, side.sign * 1f))
    }
    mat()
}

/**
 * World's greatest stretch: a deep lunge with the far knee down and the far hand on the floor inside
 * the front foot; the near arm sweeps from the floor up to the ceiling as the chest turns open.
 */
internal fun worldsGreatestStretch() = Motion.rep(Track.rep(go = 1.6f, hold = 0.8f, back = 1.4f, rest = 0.6f), Camera(50f, 14f)) { p ->
    val knee = v(-0.35f, BodySize.KNEE_RADIUS + 0.006f, -BodySize.HIP_HALF)
    val hip = knee + dirXY(50f) * BodySize.THIGH
    body.torso(hip.withZ(0f), Basis.UPRIGHT.pitch(68f), twist = lerp(0f, 50f, p), nod = lerp(-30f, -10f, p))
    body.leg(Side.FAR, knee + v(-0.42f, 0.11f, 0f), v(1f, -1.5f, 0f), Foot(dirXY(250f), dirXY(340f)))
    body.stand(Side.NEAR, 0.5f, z = 0.13f, out = 8f, pole = v(1f, 0.2f, 0.2f))
    body.arm(Side.FAR, v(0.45f, BodySize.FIST_RADIUS, -0.06f), v(-0.4f, 0.2f, -1f))
    val sweep = rad(lerp(-55f, 90f, p))
    body.armAngles(Side.NEAR, v(0.05f, sin(sweep), cos(sweep)), 6f, fold = V3.X)
    mat()
}

/** Wall calf stretch: hands on a wall, the far leg straight behind with the heel down, leaning in. */
internal fun calfStretch() = Motion.cycle(5f, camera = Camera(26f)) { phase ->
    val angle = lerp(68f, 62f, breath(phase))
    val ankle = v(-0.5f, BodySize.ANKLE, -0.12f)
    val pelvis = (ankle + dirXY(angle) * 0.835f).withZ(0f)
    body.torso(pelvis, Basis.UPRIGHT.pitch(90f - angle))
    body.stand(Side.FAR, ankle.x, z = ankle.z, out = 4f, pole = v(1f, 0.1f, 0f))
    body.stand(Side.NEAR, 0.12f, z = 0.12f, out = 6f)
    val y = body.shoulders().y - 0.04f
    for (side in Side.entries) body.arm(side, v(0.54f, y, side.sign * 0.22f), v(-0.4f, -1f, side.sign * 0.8f))
    box(v(0.58f, 0f, -0.6f), v(0.68f, 2.0f, 0.6f), Ink.FRAME, fit = false, bias = -0.6f)
}

/**
 * Child's pose: kneeling, the hips sit back towards the heels and the trunk folds over the thighs,
 * the forehead down and the arms reaching forward on the floor.
 */
internal fun childsPose() = Motion.cycle(5.5f, camera = Camera(24f)) { phase ->
    val b = breath(phase)
    val knee = v(0.05f, BodySize.KNEE_RADIUS + 0.006f)
    val hip = knee + dirXY(lerp(146f, 150f, b)) * BodySize.THIGH
    body.torso(hip, Basis.UPRIGHT.pitch(90f), bend = lerp(16f, 19f, b), nod = -2f)
    for (side in Side.entries) {
        body.leg(side, knee.withZ(side.sign * BodySize.HIP_HALF) + v(-0.43f, 0.07f, 0f), v(1f, -1f, 0f), Foot(dirXY(195f), dirXY(285f)))
        body.arm(side, v(0.7f, BodySize.FIST_RADIUS, side.sign * 0.2f), v(0.2f, 1f, side.sign * 0.5f))
    }
    mat()
}

/** Doorway chest stretch, seen from above the front: the near forearm up on the door frame, stepping through until the chest stretches. */
internal fun doorwayChestStretch() = Motion.cycle(5f, camera = Camera(40f, 30f)) { phase ->
    val b = breath(phase)
    body.torso(v(lerp(0f, 0.06f, b), BodySize.STAND - 0.02f), Basis.UPRIGHT.pitch(lerp(2f, 7f, b)), twist = lerp(0f, -10f, b))
    body.stand(Side.FAR, 0.22f, z = -0.12f)
    body.stand(Side.NEAR, -0.12f, z = 0.13f)
    val s = body.shoulder(Side.NEAR)
    val frame = v(0.02f, BodySize.STAND + 0.45f, 0.47f)
    body.armAngles(Side.NEAR, (frame - s).unit(), 90f, fold = v(0.4f, 1f, 0f))
    body.hang(Side.FAR, forward = 4f, out = 6f)
    post(frame.x - 0.04f, 2.1f, z = frame.z + 0.06f, radius = 0.04f)
}

/** Cross-body shoulder stretch, seen from the front: the near arm straight across the chest, the far forearm drawing it in. */
internal fun crossBodyShoulderStretch() = Motion.cycle(5f, camera = Camera(64f, 12f)) { phase ->
    val b = breath(phase)
    body.torso(v(0f, BodySize.STAND), twist = -4f * b)
    body.standOn(0f, width = 0.3f)
    body.armAngles(Side.NEAR, v(lerp(0.6f, 0.7f, b), -0.08f, -1f).unit(), 6f, fold = V3.Y)
    val near = body.armOf(Side.NEAR)
    body.arm(Side.FAR, lerp(near.shoulder, near.elbow, 0.85f) + v(0.05f, -0.03f, 0f), v(-0.3f, -1f, -0.4f))
}

/** Overhead triceps stretch: the near elbow up beside the head with the hand behind it, the far hand easing the elbow back. */
internal fun tricepsStretch() = Motion.cycle(5f, camera = Camera(56f, 8f)) { phase ->
    val b = breath(phase)
    body.torso(v(0f, BodySize.STAND))
    body.standOn(0f, width = 0.3f)
    body.armAngles(Side.NEAR, limbDir(body.chest, Side.NEAR, lerp(166f, 173f, b), 6f), 146f, fold = -V3.X)
    body.arm(Side.FAR, body.armOf(Side.NEAR).elbow + v(0.02f, 0.03f, -0.03f), v(0.2f, 0.4f, -1f))
}

/** Band shoulder dislocates: a wide grip on a band, straight arms sweep from in front of the hips over the head to behind the back and return. */
internal fun shoulderDislocate() = Motion.rep(Track.rep(go = 2.2f, hold = 0.3f, back = 2.2f, rest = 0.4f), Camera(30f, 6f)) { p ->
    body.torso(v(0f, BodySize.STAND))
    body.standOn(0f, width = 0.3f)
    val sweep = lerp(14f, 300f, p)
    for (side in Side.entries) body.armAngles(side, limbDir(body.chest, side, sweep, 44f), 6f, fold = body.chest.f)
    band(listOf(body.handOf(Side.NEAR), body.handOf(Side.FAR)))
}

/** Side neck stretch, seated and seen from the front: the near hand over the head draws the ear towards the near shoulder. */
internal fun neckStretch() = Motion.cycle(5f, camera = Camera(80f, 4f)) { phase ->
    val b = breath(phase)
    body.torso(v(0f, Kit.BENCH_TOP + SEAT_HIP), tilt = lerp(26f, 44f, b))
    body.standOn(0.42f, width = 0.32f, out = 6f, pole = v(1f, 0.25f, 0.15f))
    flatBench(-0.6f, 0.3f)
    val head = Basis.UPRIGHT.roll(lerp(26f, 44f, b))
    val center = body.neck + head.u * BodySize.NECK + head.f * 0.015f
    body.arm(Side.NEAR, center + head.dir(0f, 0.07f, -0.09f), v(0f, 0.6f, 1f))
    body.arm(Side.FAR, v(0f, Kit.BENCH_TOP + 0.02f, -0.24f), v(-0.3f, 0f, -1f))
}
