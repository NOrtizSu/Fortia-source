package com.enjoyyourtime.globalfitness.ui.training.motion

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

// Mobility drills, stretches and foam rolling added in 1.6. Holds breathe (see [breath]); drills
// that move (CARs, 90/90, rocking) are slow cycles; foam rolling slides the body back and forth over
// the roller, which rolls half as far as the body moves.

private const val TAU = 2 * PI.toFloat()

/** Spine length from the hip joints to the shoulder line. */
private const val SPINE = BodySize.LOWER_SPINE + BodySize.UPPER_SPINE - BodySize.SHOULDER_DROP

/** Radius of a foam roller. */
private const val ROLLER = 0.075f

// ---- Foam rolling ---------------------------------------------------------------------------------

/** A foam roller lying on the floor across the body (along [axis]) at [center] (its centre height is its radius). */
private fun FrameBuilder.foamRoller(center: V3, axis: V3 = V3.Z, length: Float = 0.9f) {
    val a = axis.unit() * (length / 2)
    add(Rod(center - a, center + a, ROLLER, Ink.PAD, bias = -0.05f))
    for (s in listOf(-1f, 1f)) add(Disc(center + a * s, axis, ROLLER, 0.01f, Ink.PAD, hub = 0.03f, bias = -0.04f))
}

/** Back and forth over the roller: 0 → 1 → 0 over the phase, slow at the ends. */
private fun roll(phase: Float): Float = 0.5f - 0.5f * cos(phase * TAU)

/** Two hands behind the head, elbows out (supine drills). */
private fun Rig.headInHands() {
    for (side in Side.entries) arm(side, onChest(-0.07f, 0.2f, side.sign * 0.07f), chest.dir(0.3f, 0.2f, side.sign * 1f))
}

/**
 * Foam rolling the quads: face down on the forearms, the roller under the thighs, the body pulled
 * forward and pushed back so it rolls from just above the knees to the hips.
 */
internal fun foamRollQuads() = Motion.cycle(4.4f, camera = Camera(22f, 6f)) { phase ->
    val contact = lerp(0.12f, 0.36f, roll(phase))
    val shift = 2 * (contact - 0.24f)
    val pelvis = v(shift, 0.25f)
    body.torso(pelvis, Basis.PRONE.pitch(-11f), nod = 10f)
    val rollerX = pelvis.x - contact
    // The legs lie straight back over the roller, toes pointed.
    for (side in Side.entries) {
        val hip = body.hip(side)
        val touch = v(rollerX, 2 * ROLLER + BodySize.THIGH_RADIUS, hip.z)
        val dir = (touch - hip).unit()
        // Pointed toes: 25° short of the line of the leg, towards the floor (the front of a prone shin).
        val toes = angleXY(dir) + 25f
        body.legAngles(side, dir, 3f, fold = V3.Y, foot = Foot(dirXY(toes), dirXY(toes + 90f)))
    }
    val shoulders = body.shoulders()
    for (side in Side.entries) body.arm(side, v(shoulders.x + 0.29f, BodySize.FIST_RADIUS, side.sign * 0.15f), v(0f, -1f, side.sign * 0.2f))
    foamRoller(v(rollerX, ROLLER))
}

/**
 * Foam rolling the back of the legs, seated on the hands with the hips lifted: the roller under the
 * hamstrings ([calves] false) or the calves, the body sliding back and forth over it.
 */
internal fun foamRollLegBack(calves: Boolean) = Motion.cycle(4.4f, camera = Camera(-24f, 6f)) { phase ->
    val near = if (calves) 0.5f else 0.12f
    val far = if (calves) 0.78f else 0.36f
    val contact = lerp(near, far, roll(phase))
    val mid = (near + far) / 2
    val shift = -2 * (contact - mid)
    val pelvis = v(shift, if (calves) 0.17f else 0.22f)
    body.torso(pelvis, Basis.UPRIGHT.pitch(-38f), nod = 18f)
    val rollerX = pelvis.x + contact
    val rest = 2 * ROLLER + if (calves) 0.05f else BodySize.THIGH_RADIUS
    for (side in Side.entries) {
        val hip = body.hip(side)
        val touch = v(rollerX, rest, hip.z)
        val dir = (touch - hip).unit()
        val toes = dirXY(80f)
        body.legAngles(side, dir, 3f, fold = -V3.Y, foot = Foot(toes, dirXY(170f)))
    }
    val shoulders = body.shoulders()
    for (side in Side.entries) body.arm(side, v(shoulders.x - 0.08f, BodySize.FIST_RADIUS, side.sign * 0.24f), v(-1f, 0.3f, side.sign * 0.3f))
    foamRoller(v(rollerX, ROLLER))
}

/**
 * Foam rolling the glutes: sitting on the roller with the hands behind, the near ankle crossed over
 * the far knee (figure four), leaning onto the near glute and rocking a little on the roller.
 */
internal fun foamRollGlutes() = Motion.cycle(4f, camera = Camera(-30f, 14f)) { phase ->
    val shift = 0.05f * sin(phase * TAU)
    val roller = v(shift / 2 - 0.02f, ROLLER)
    val pelvis = v(shift, 2 * ROLLER + 0.1f, 0.03f)
    body.torso(pelvis, Basis.UPRIGHT.pitch(-44f).roll(10f), nod = 16f)
    body.stand(Side.FAR, pelvis.x + 0.48f, z = -0.13f, pole = v(0.4f, 1f, -0.1f))
    val knee = body.legOf(Side.FAR).knee
    body.leg(Side.NEAR, knee + v(0.0f, BodySize.KNEE_RADIUS + 0.05f, 0.07f), v(0.3f, 0.3f, 1f))
    val shoulders = body.shoulders()
    for (side in Side.entries) body.arm(side, v(shoulders.x - 0.06f, BodySize.FIST_RADIUS, side.sign * 0.24f), v(-1f, 0.3f, side.sign * 0.3f))
    foamRoller(roller)
}

/**
 * Foam rolling lying on the far side, propped on the far forearm and seen from the front: the roller
 * under the outer thigh ([lats] false: the IT band, the top leg crossed in front with its foot down)
 * or under the armpit (the lats, the bottom arm overhead), sliding along the roller.
 */
internal fun foamRollSide(lats: Boolean) = Motion.cycle(4.4f, camera = Camera(84f, 12f)) { phase ->
    val r = roll(phase)
    val shift = lerp(-0.07f, 0.07f, r) * (if (lats) 1f else 1.6f)
    if (!lats) {
        val basis = Basis.UPRIGHT.roll(-68f)
        val pelvis = v(0f, 0.33f, shift)
        body.torso(pelvis, basis, nod = -6f, tilt = 18f)
        val farHip = body.hip(Side.FAR)
        val contact = lerp(0.14f, 0.34f, r)
        val rollerZ = farHip.z + contact
        val touch = v(farHip.x, 2 * ROLLER + BodySize.THIGH_RADIUS, rollerZ)
        body.legAngles(Side.FAR, (touch - farHip).unit(), 4f, fold = -V3.X)
        body.stand(Side.NEAR, 0.4f, z = pelvis.z + 0.24f, out = 30f, pole = v(0.3f, 1f, 0.3f))
        val far = body.shoulder(Side.FAR)
        val elbow = v(far.x, BodySize.ELBOW_RADIUS, far.z)
        body.arm(Side.FAR, elbow + v(BodySize.FOREARM, BodySize.FIST_RADIUS - BodySize.ELBOW_RADIUS, 0f), v(-0.3f, -1f, 0f))
        // The top hand rests on the top hip.
        body.arm(Side.NEAR, body.onHips(0.1f, 0.12f, 0.12f), body.chest.dir(-0.3f, 0f, 1f))
        foamRoller(v(0f, ROLLER, rollerZ), axis = V3.X, length = 0.45f)
    } else {
        val basis = Basis.UPRIGHT.roll(-82f)
        val pelvis = v(0f, BodySize.HIP_HALF + 0.15f, shift)
        body.torso(pelvis, basis, nod = -4f, tilt = 10f)
        val rollerZ = body.neck.z + 0.12f
        for (side in Side.entries) {
            val hip = body.hip(side)
            body.leg(side, v(0.32f, hip.y - 0.02f, pelvis.z + 0.55f), v(1f, 0f, 0.4f), Foot(V3.X, -V3.Z))
        }
        body.armAngles(Side.FAR, (body.chest.u + V3.Y * 0.05f).unit(), 8f, fold = body.chest.f)
        body.arm(Side.NEAR, body.onHips(0.1f, 0.12f, 0.12f), body.chest.dir(-0.3f, 0f, 1f))
        foamRoller(v(0f, ROLLER, rollerZ), axis = V3.X, length = 0.45f)
    }
}

/**
 * Foam rolling the upper back: on the back with the roller across the shoulder blades, hands behind
 * the head, hips just off the floor and feet flat; the body rolls from mid-back to the top of the shoulders.
 */
internal fun foamRollUpperBack() = Motion.cycle(4.4f, camera = Camera(-26f, 8f)) { phase ->
    val contact = lerp(0.3f, 0.46f, roll(phase))
    val shift = 2 * (0.38f - contact)
    val pelvis = v(shift, 0.25f)
    body.torso(pelvis, Basis.SUPINE, nod = 22f)
    for (side in Side.entries) body.stand(side, pelvis.x + 0.45f, z = side.sign * 0.13f, pole = v(0.3f, 1f, 0.2f))
    body.headInHands()
    foamRoller(v(pelvis.x - contact, ROLLER))
}

// ---- Mobility drills ------------------------------------------------------------------------------

/**
 * Knee-to-wall ankle mobilisation: half kneeling facing a wall, the front heel down while the knee
 * pushes forward over the toes and eases back.
 */
internal fun ankleMobilization() = Motion.cycle(4f, camera = Camera(26f)) { phase ->
    val b = breath(phase)
    val knee = v(-0.1f, BodySize.KNEE_RADIUS + 0.006f, BodySize.HIP_HALF)
    val px = lerp(0.06f, 0.2f, b)
    val thigh = deg(kotlin.math.acos(((px - knee.x) / BodySize.THIGH).coerceIn(-1f, 1f)))
    val hip = knee + dirXY(thigh) * BodySize.THIGH
    body.torso(hip.withZ(0f), Basis.UPRIGHT.pitch(4f + 8f * b), nod = 4f)
    body.leg(Side.NEAR, knee + v(-0.42f, 0.11f, 0f), v(1f, -1.5f, 0f), Foot(dirXY(250f), dirXY(340f)))
    body.stand(Side.FAR, 0.42f, z = -0.12f, out = 6f, pole = v(1f, 0.25f, -0.1f))
    val front = body.legOf(Side.FAR).knee
    for (side in Side.entries) body.arm(side, front + v(-0.02f, 0.09f, side.sign * 0.06f + 0.05f), v(-0.3f, -1f, side.sign * 0.8f))
    box(v(0.72f, 0f, -0.6f), v(0.82f, 2.0f, 0.6f), Ink.FRAME, fit = false, bias = -0.6f)
    mat()
}

/**
 * 90/90 hip switch, seen from the front and above: seated tall with the feet planted wide, both
 * knees fall to one side (one hip turned out, the other in), then sweep over to the other side.
 */
internal fun hip9090() = Motion.cycle(6f, camera = Camera(60f, 24f)) { phase ->
    val sweep = sin(phase * TAU)
    body.torso(v(-0.06f, 0.13f), Basis.UPRIGHT.pitch(-6f).turn(8f * sweep), twist = 6f * sweep, nod = 6f)
    val a = rad(72f * sweep)
    for (side in Side.entries) {
        val pole = v(0.15f, cos(a), sin(a))
        body.leg(side, v(0.4f, BodySize.ANKLE, side.sign * 0.3f), pole, Foot.flat(side, 10f))
        body.arm(side, v(-0.27f, BodySize.FIST_RADIUS, side.sign * 0.22f), v(-1f, 0.2f, side.sign * 0.4f))
    }
    mat()
}

/** Deep squat hold, seen from the front: all the way down with the heels flat, hands together at the chest and the elbows easing the knees out. */
internal fun deepSquatHold() = Motion.cycle(5f, camera = Camera(56f, 10f)) { phase ->
    val b = breath(phase)
    val lean = 34f + 3f * b
    body.torso(v(-0.12f, 0.31f - 0.01f * b), Basis.UPRIGHT.pitch(lean), nod = -lean * 0.4f)
    body.standOn(0f, width = 0.42f, out = 22f, pole = v(1f, 0.1f, 0.55f))
    val hands = body.onChest(0.3f, -0.2f)
    for (side in Side.entries) body.arm(side, hands + V3.Z * (side.sign * 0.035f), v(0.2f, -0.6f, side.sign * 1f))
}

/**
 * Hip CARs: standing on the far leg with the far hand on a post, the near knee bent 90° draws the
 * widest circle it can: up in front, out to the side, back behind and down to the start.
 */
internal fun hipCars() = Motion.cycle(5.6f, camera = Camera(48f, 14f)) { phase ->
    val a = phase * TAU
    body.torso(v(0f, BodySize.STAND - 0.01f, -0.04f), Basis.UPRIGHT.roll(-3f), nod = -2f)
    body.stand(Side.FAR, 0f, z = -0.12f, out = 6f)
    val forward = 35f + 55f * cos(a)
    val out = 55f * max(0f, sin(a))
    val dir = limbDir(Basis.UPRIGHT, Side.NEAR, forward, out)
    // The back of the thigh, where the shin folds to.
    val back = v(-cos(rad(forward)), -sin(rad(forward)), 0f)
    body.legAngles(Side.NEAR, dir, lerp(90f, 70f, max(0f, -cos(a))), fold = orthogonal(back, dir))
    body.arm(Side.FAR, v(0.05f, 1.05f, -0.5f), v(-0.3f, -1f, -0.6f))
    body.arm(Side.NEAR, body.onHips(0.02f, 0.12f, 0.2f), v(-0.6f, 0f, 1f))
    post(0.08f, 2.0f, z = -0.55f, radius = 0.035f)
}

/**
 * Shoulder CARs: the near arm straight rises in front to overhead, then turns and comes down out to
 * the side in the widest circle it can, back to the start; the trunk stays still.
 */
internal fun shoulderCars() = Motion.cycle(5.6f, camera = Camera(44f, 6f)) { phase ->
    body.torso(v(0f, BodySize.STAND))
    body.standOn(0f, width = 0.3f)
    val dir = if (phase < 0.5f) {
        limbDir(body.chest, Side.NEAR, 172f * Ease.IN_OUT.apply(phase / 0.5f), 8f)
    } else {
        val s = Ease.IN_OUT.apply((phase - 0.5f) / 0.5f)
        limbDir(body.chest, Side.NEAR, lerp(-8f, 0f, s), lerp(172f, 8f, s))
    }
    body.armAngles(Side.NEAR, dir, 4f, fold = body.chest.f)
    body.hang(Side.FAR)
}

/**
 * Thread the needle: on all fours, the near arm slides under the body palm up, the near shoulder and
 * the side of the head sinking towards the floor while the far arm bends; then back.
 */
internal fun threadTheNeedle() = Motion.rep(Track.rep(go = 1.6f, hold = 1.2f, back = 1.4f, rest = 0.6f), Camera(30f, 14f)) { p ->
    val hands = allFours(twist = lerp(0f, -38f, p), nod = lerp(8f, 20f, p), reach = lerp(0.55f, 0.4f, p), free = Side.NEAR)
    val planted = hands.getValue(Side.NEAR)
    val threaded = v(planted.x - 0.04f, BodySize.FIST_RADIUS + 0.01f, -0.32f)
    body.arm(Side.NEAR, lerp(planted, threaded, Ease.IN_OUT.apply(p)), lerp(v(-1f, 0.2f, 0.5f), v(-1f, 0.6f, 0f), p))
}

/** Cobra stretch: face down, hands under the shoulders, pressing the chest up with the hips on the floor; it rises with each breath. */
internal fun cobraStretch() = Motion.cycle(5f, camera = Camera(24f)) { phase ->
    val b = breath(phase)
    body.torso(v(0f, 0.115f), Basis.PRONE.pitch(-10f), bend = -lerp(24f, 38f, b), nod = -lerp(4f, 12f, b))
    for (side in Side.entries) {
        body.legAngles(side, v(-1f, 0f, side.sign * 0.05f), 3f, fold = V3.Y, foot = Foot(dirXY(205f), dirXY(295f)))
        body.arm(side, v(0.4f, BodySize.FIST_RADIUS, side.sign * 0.24f), v(-1f, 0.3f, side.sign * 0.3f))
    }
    mat()
}

/** Downward dog: hands and feet on the floor, hips high, arms and back in one line; the heels pedal down in turn. */
internal fun downwardDog() = Motion.cycle(5f, camera = Camera(24f)) { phase ->
    val b = breath(phase)
    val hands = v(0.42f, BodySize.FIST_RADIUS)
    val lifts = Side.entries.associateWith { side -> 18f + 14f * max(0f, side.sign * sin(phase * TAU)) }
    val ankle = liftedAnkle(-0.58f, 0f, 18f)
    val pelvis = ankle + dirXY(lerp(60f, 63.5f, b)) * (BodySize.THIGH + BodySize.SHIN - 0.02f)
    val reach = 0.57f
    val toHands = hands - pelvis
    val d = toHands.length
    val along = toHands * (1f / d)
    val a = (SPINE * SPINE - reach * reach + d * d) / (2 * d)
    val h = sqrt((SPINE * SPINE - a * a).coerceAtLeast(0f))
    val shoulders = pelvis + along * a + v(-along.y, along.x) * h
    val up = shoulders - pelvis
    body.torso(pelvis, Basis.UPRIGHT.pitch(deg(atan2(up.x, up.y))), nod = 12f)
    for (side in Side.entries) {
        val lift = lifts.getValue(side)
        body.leg(side, liftedAnkle(-0.58f, 0f, lift).withZ(side.sign * 0.1f), v(1f, -0.6f, 0f), Foot(dirXY(-lift), dirXY(90f - lift)))
        body.arm(side, hands.withZ(side.sign * 0.2f), v(-1f, 0.15f, side.sign * 0.4f))
    }
    mat()
}

/** Standing quad stretch: on the far leg with the far hand on a post, the near hand holding the near ankle behind, heel to the glute. */
internal fun standingQuadStretch() = Motion.cycle(5f, camera = Camera(30f, 4f)) { phase ->
    val b = breath(phase)
    body.torso(v(0.01f, BodySize.STAND - 0.01f, -0.03f), Basis.UPRIGHT.pitch(2f), nod = -2f)
    body.stand(Side.FAR, 0f, z = -0.12f, out = 6f)
    val thigh = limbDir(Basis.UPRIGHT, Side.NEAR, lerp(-2f, -8f, b), 2f)
    val knee = 154f
    val back = v(-1f, 0f, 0f)
    val fold = orthogonal(back, thigh)
    val shin = (thigh * cos(rad(knee)) + fold * sin(rad(knee))).unit()
    val front = (thigh * sin(rad(knee)) - fold * cos(rad(knee))).unit()
    val toes = (shin * cos(rad(28f)) + front * sin(rad(28f))).unit()
    body.legAngles(Side.NEAR, thigh, knee, fold = fold, foot = Foot(toes, orthogonal(front, toes)))
    val ankle = body.legOf(Side.NEAR).ankle
    body.arm(Side.NEAR, ankle + v(0.01f, 0.02f, 0.03f), v(-1f, 0f, 0.4f))
    body.arm(Side.FAR, v(0.22f, 1.08f, -0.46f), v(-0.3f, -1f, -0.6f))
    post(0.27f, 2.0f, z = -0.5f, radius = 0.035f)
}

/**
 * Lying spinal twist, seen from above: on the back with the arms out, the near knee comes up and
 * drops across the body to the far side while both shoulders stay down; the head turns the other way.
 */
internal fun lyingSpinalTwist() = Motion.rep(Track.rep(go = 1.8f, hold = 1.6f, back = 1.4f, rest = 0.6f), Camera(-22f, 52f)) { p ->
    val turn = lerp(0f, -46f, p)
    body.torso(v(0f, 0.115f), Basis.SUPINE.turn(turn), twist = -turn, nod = 6f, tilt = 24f * p)
    val up = v(-0.15f, 1f, 0f).unit()
    val across = v(0.28f, 0.16f, -0.95f).unit()
    val thigh = lerp(up, across, Ease.IN_OUT.apply(p)).unit()
    body.legAngles(Side.NEAR, thigh, 92f, fold = V3.X)
    // The far leg stays long on the floor; its hip sinks as the pelvis turns, so it angles up a little.
    body.legAngles(Side.FAR, v(1f, lerp(0.02f, 0.1f, p), -0.02f), 4f, fold = -V3.Y, foot = Foot(dirXY(75f), dirXY(165f)))
    for (side in Side.entries) {
        val s = body.shoulder(side)
        body.arm(side, v(s.x + 0.02f, BodySize.FIST_RADIUS, side.sign * 0.74f), v(0.2f, -0.2f, side.sign * 1f))
    }
    mat()
}

/**
 * Figure-four stretch: on the back, the near ankle crossed over the far knee, both hands pulling the
 * far thigh towards the chest; the pull deepens with each breath.
 */
internal fun figureFourStretch() = Motion.cycle(5f, camera = Camera(-26f, 12f)) { phase ->
    val b = breath(phase)
    body.torso(v(0f, 0.115f), Basis.SUPINE, nod = 12f)
    val flex = lerp(104f, 116f, b)
    val thigh = v(cos(rad(flex)), sin(rad(flex)), -0.05f).unit()
    body.legAngles(Side.FAR, thigh, 92f, fold = dirXY(flex - 90f))
    val farKnee = body.legOf(Side.FAR).knee
    body.leg(Side.NEAR, farKnee + v(0.04f, 0.07f, 0.06f), v(0.3f, 0.3f, 1f))
    val grip = lerp(body.legOf(Side.FAR).hip, farKnee, 0.85f)
    for (side in Side.entries) body.arm(side, grip + v(0.03f, -0.06f, side.sign * 0.06f - 0.02f), v(0.2f, 0.3f, side.sign * 1f))
    mat()
}

/**
 * Wrist mobility on all fours: the hands flat (fingers forward), the body rocks forward over them
 * and back, the wrists bending more as the shoulders pass in front of them.
 */
internal fun wristMobility() = Motion.cycle(4f, camera = Camera(30f, 8f)) { phase ->
    val rock = roll(phase)
    val knee = v(-0.02f, BodySize.KNEE_RADIUS + 0.004f)
    val tilt = lerp(0f, 16f, rock)
    val hip = knee + dirXY(90f - tilt) * BodySize.THIGH
    val wristX = 0.47f
    val shoulderY = BodySize.FIST_RADIUS + 0.5f
    val dx = sqrt((SPINE * SPINE - (shoulderY - hip.y) * (shoulderY - hip.y)).coerceAtLeast(0f))
    val rise = deg(atan2(shoulderY - hip.y, dx))
    body.torso(hip, Basis.UPRIGHT.pitch(90f - rise), nod = 10f)
    for (side in Side.entries) {
        body.leg(side, knee.withZ(side.sign * BodySize.HIP_HALF) + v(-0.42f, 0.11f, 0f), v(1f, -1.5f, 0f), Foot(dirXY(195f), dirXY(285f)))
        val wrist = v(wristX, BodySize.FIST_RADIUS + BodySize.HAND * sin(rad(30f)), side.sign * 0.18f)
        body.armWrist(side, wrist, v(-1f, 0.2f, side.sign * 0.5f), dirXY(-30f))
    }
    mat()
}
