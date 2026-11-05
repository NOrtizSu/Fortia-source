package com.enjoyyourtime.globalfitness.ui.training.motion

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// Combat drills added in 1.8: punches on the heavy bag, the speed bag, the double-end bag and pads,
// shadow hooks and uppercuts, punches with dumbbells, roundhouse kicks and knee strikes on the bag,
// and the sprawl. The fighter stands in an orthodox guard: the lead (far, left) side forward.

private const val TAU = 2 * PI.toFloat()

/** How far out each punch is (0 in the guard, 1 landed). */
private class Punches(
    val jab: Float = 0f,
    val cross: Float = 0f,
    val leadHook: Float = 0f,
    val rearHook: Float = 0f,
    val leadUpper: Float = 0f,
    val rearUpper: Float = 0f,
)

/** A punch thrown at [start]: out in [out] seconds (fast, settling), back to the guard in [back]. */
private fun punch(t: Float, start: Float, out: Float = 0.17f, back: Float = 0.2f): Float = when {
    t <= start -> 0f
    t <= start + out -> Ease.OUT.apply((t - start) / out)
    t <= start + out + back -> 1f - Ease.IN_OUT.apply((t - start - out) / back)
    else -> 0f
}

/**
 * The fighter in the guard, bouncing [bounce] metres on the balls of the feet, the hips and trunk
 * turning into each punch of [p]; returns where the fists are (lead = far hand, rear = near hand).
 */
private fun FrameBuilder.fighter(p: Punches, bounce: Float): Map<Side, V3> {
    val twist = 8f * p.jab - 34f * p.cross + 26f * p.leadHook - 30f * p.rearHook + 14f * p.leadUpper - 22f * p.rearUpper
    val dip = 0.04f * (sin(PI.toFloat() * p.leadUpper) + sin(PI.toFloat() * p.rearUpper))
    val turn = 26f - 10f * p.cross + 6f * p.leadHook - 8f * p.rearHook
    body.torso(v(-0.02f, 0.85f + bounce - dip), Basis.UPRIGHT.pitch(8f).turn(turn), twist = twist, nod = 6f)
    body.stand(Side.FAR, 0.2f, z = -0.1f, floor = bounce, lift = 12f + 22f * p.leadHook, out = 20f, pole = v(1f, 0.1f, -0.1f))
    body.stand(Side.NEAR, -0.24f, z = 0.16f, floor = bounce, lift = 18f + 18f * (p.cross + p.rearHook + p.rearUpper), out = 35f, pole = v(1f, 0.1f, 0.2f))
    val chest = body.chest
    val lead = body.onChest(0.24f, 0.12f, -0.09f) +
        (body.shoulder(Side.FAR) + v(0.54f, 0.02f, 0.1f) - body.onChest(0.24f, 0.12f, -0.09f)) * p.jab +
        (body.onChest(0.36f, 0f, 0.06f) - body.onChest(0.24f, 0.12f, -0.09f)) * p.leadHook +
        (body.onChest(0.3f, 0.1f, -0.02f) - body.onChest(0.24f, 0.12f, -0.09f)) * p.leadUpper - V3.Y * (0.14f * sin(PI.toFloat() * p.leadUpper))
    val rear = body.onChest(0.2f, 0.13f, 0.1f) +
        (body.shoulder(Side.NEAR) + v(0.55f, 0.02f, -0.14f) - body.onChest(0.2f, 0.13f, 0.1f)) * p.cross +
        (body.onChest(0.36f, 0f, -0.06f) - body.onChest(0.2f, 0.13f, 0.1f)) * p.rearHook +
        (body.onChest(0.3f, 0.1f, 0.02f) - body.onChest(0.2f, 0.13f, 0.1f)) * p.rearUpper - V3.Y * (0.14f * sin(PI.toFloat() * p.rearUpper))
    val leadPole = lerp(lerp(v(-0.2f, -1f, -0.25f), chest.dir(-0.2f, 0.25f, -1f), p.leadHook), v(0f, -1f, -0.4f), p.leadUpper)
    val rearPole = lerp(lerp(v(-0.2f, -1f, 0.25f), chest.dir(-0.2f, 0.25f, 1f), p.rearHook), v(0f, -1f, 0.4f), p.rearUpper)
    body.arm(Side.FAR, lead, leadPole)
    body.arm(Side.NEAR, rear, rearPole)
    return mapOf(Side.FAR to body.handOf(Side.FAR), Side.NEAR to body.handOf(Side.NEAR))
}

/** Where the fists land for punches [p] thrown at full extension (to put bags and pads there). */
private fun landing(p: Punches, side: Side): V3 = FrameBuilder().apply { fighter(p, 0f) }.body.armOf(side).hand

/** Boxing gloves on both fists. */
private fun FrameBuilder.gloves(fists: Map<Side, V3>) {
    for ((_, fist) in fists) add(Ball(fist + V3.X * 0.015f, 0.072f, Ink.BAND, bias = 0.03f))
}

/** A small bump after [hit]: 0 before, up to 1 at [hit] + [rise], back to 0 by [hit] + [fall]. */
private fun knock(t: Float, hit: Float, rise: Float = 0.1f, fall: Float = 0.6f): Float = when {
    t < hit -> 0f
    t < hit + rise -> Ease.OUT.apply((t - hit) / rise)
    t < hit + fall -> 1f - Ease.IN_OUT.apply((t - hit - rise) / (fall - rise))
    else -> 0f
}

/** A heavy bag hanging from a chain at [pivot], swung [angle] degrees away from the fighter. */
private fun FrameBuilder.hangingBag(pivot: V3, angle: Float) {
    val dir = v(sin(rad(angle)), -cos(rad(angle)))
    add(Cord(listOf(pivot, pivot + dir * 0.55f), 0.012f, Ink.METAL, fit = false, bias = -0.3f))
    add(Rod(pivot + dir * 0.62f, pivot + dir * 1.7f, 0.17f, Ink.PLATE, fit = false, bias = -0.2f))
}

/** Heavy bag: in gloves, two jab-cross combinations on the hanging bag, which swings a little with each landed punch. */
internal fun heavyBag(): Motion {
    val jab = landing(Punches(jab = 1f), Side.FAR)
    val cross = landing(Punches(cross = 1f), Side.NEAR)
    // Both punches land: the glove of the shorter one just touches the bag, the longer one sinks in a little.
    val front = minOf(jab.x, cross.x) + 0.06f
    val pivot = v(front + 0.17f, 2.35f, (jab.z + cross.z) / 2)
    val hits = listOf(0.47f, 0.85f, 1.47f, 1.85f)
    return Motion.cycle(2.4f, camera = Camera(34f)) { phase ->
        val t = phase * 2.4f
        val bounce = 0.012f * (0.5f - 0.5f * cos(phase * TAU * 4))
        val fists = fighter(Punches(jab = punch(t, 0.3f) + punch(t, 1.3f), cross = punch(t, 0.68f) + punch(t, 1.68f)), bounce)
        gloves(fists)
        hangingBag(pivot, 4f * hits.sumOf { knock(t, it).toDouble() }.toFloat().coerceAtMost(1.4f))
    }
}

/** Speed bag: square to the platform, fists up by the face circling in turn, the small bag rebounding off the board. */
internal fun speedBag() = Motion.cycle(1.0f, camera = Camera(34f, 4f)) { phase ->
    body.torso(v(-0.02f, 0.89f), Basis.UPRIGHT.pitch(4f), nod = -6f)
    body.standOn(0f, width = 0.36f, out = 12f, pole = v(1f, 0.1f, 0.25f))
    val swivel = v(0.44f, 1.86f)
    for (side in Side.entries) {
        val a = phase * TAU * 2 + if (side == Side.NEAR) 0f else PI.toFloat()
        val fist = v(0.27f + 0.06f * cos(a), 1.5f + 0.06f * sin(a), side.sign * 0.08f)
        body.arm(side, fist, v(-0.2f, 0f, side.sign * 1f))
        add(Ball(fist + V3.X * 0.01f, 0.05f, Ink.BAND, bias = 0.03f))
    }
    val swing = rad(38f * sin(phase * TAU * 4))
    add(Block(v(0.44f, 1.9f), Basis.UPRIGHT, v(0.3f, 0.025f, 0.3f), Ink.FRAME, fit = false, bias = -0.4f))
    add(Ball(swivel + v(sin(swing), -cos(swing)) * 0.17f, 0.1f, Ink.PLATE, bias = -0.05f))
}

/** Double-end bag: a jab, a cross and a jab at the small ball held by cords from floor to ceiling, which flies back and returns. */
internal fun doubleEndBag(): Motion {
    val base = landing(Punches(jab = 1f), Side.FAR) + v(0.1f, 0f)
    return Motion.cycle(2.0f, camera = Camera(34f)) { phase ->
        val t = phase * 2.0f
        val bounce = 0.012f * (0.5f - 0.5f * cos(phase * TAU * 4))
        val fists = fighter(Punches(jab = punch(t, 0.3f) + punch(t, 1.35f), cross = punch(t, 0.82f)), bounce)
        gloves(fists)
        val ball = base + V3.X * (0.2f * (knock(t, 0.45f, fall = 0.4f) + knock(t, 0.97f, fall = 0.4f) + knock(t, 1.5f, fall = 0.4f)))
        add(Cord(listOf(v(base.x, 0f, base.z), ball, v(base.x, 2.6f, base.z)), 0.008f, Ink.CABLE, fit = false, bias = -0.2f))
        add(Ball(ball, 0.09f, Ink.PLATE, bias = 0.01f))
    }
}

/** Pad work: a jab, a cross and a lead hook into focus mitts held where each punch lands (the holder is not shown); each mitt gives a little. */
internal fun padWork(): Motion {
    val jab = landing(Punches(jab = 1f), Side.FAR)
    val cross = landing(Punches(cross = 1f), Side.NEAR)
    val hook = landing(Punches(leadHook = 1f), Side.FAR)
    return Motion.cycle(2.4f, camera = Camera(34f)) { phase ->
        val t = phase * 2.4f
        val bounce = 0.012f * (0.5f - 0.5f * cos(phase * TAU * 4))
        val fists = fighter(Punches(jab = punch(t, 0.3f), cross = punch(t, 0.68f), leadHook = punch(t, 1.12f, out = 0.2f, back = 0.26f)), bounce)
        gloves(fists)
        add(Disc(jab + V3.X * (0.12f + 0.04f * knock(t, 0.47f, fall = 0.3f)), V3.X, 0.11f, 0.06f, Ink.PAD, hub = 0.06f, bias = 0.01f))
        add(Disc(cross + V3.X * (0.12f + 0.04f * knock(t, 0.85f, fall = 0.3f)), V3.X, 0.11f, 0.06f, Ink.PAD, hub = 0.06f, bias = 0.01f))
        add(Disc(hook + V3.Z * (0.11f + 0.04f * knock(t, 1.32f, fall = 0.3f)), V3.Z, 0.11f, 0.06f, Ink.PAD, hub = 0.06f, bias = 0.01f))
    }
}

/** Hooks at the air: a lead hook, then a rear hook, the elbow up at shoulder height and the hips and feet turning into each. */
internal fun hooks() = Motion.cycle(2.0f, camera = Camera(40f, 4f)) { phase ->
    val t = phase * 2.0f
    val bounce = 0.012f * (0.5f - 0.5f * cos(phase * TAU * 4))
    fighter(Punches(leadHook = punch(t, 0.3f, out = 0.2f, back = 0.3f), rearHook = punch(t, 1.1f, out = 0.2f, back = 0.3f)), bounce)
}

/** Uppercuts at the air: a dip, then the rear fist drives up from below with the legs; then the lead one. */
internal fun uppercuts() = Motion.cycle(2.0f, camera = Camera(40f, 4f)) { phase ->
    val t = phase * 2.0f
    val bounce = 0.012f * (0.5f - 0.5f * cos(phase * TAU * 4))
    fighter(Punches(rearUpper = punch(t, 0.3f, out = 0.25f, back = 0.3f), leadUpper = punch(t, 1.1f, out = 0.25f, back = 0.3f)), bounce)
}

/** Punches with light dumbbells: quick straight punches, lead and rear in turn, from the guard. */
internal fun dumbbellPunches() = Motion.cycle(1.2f, camera = Camera(34f)) { phase ->
    val t = phase * 1.2f
    val fists = fighter(Punches(jab = punch(t, 0.1f, out = 0.18f, back = 0.22f), cross = punch(t, 0.65f, out = 0.18f, back = 0.22f)), 0f)
    for ((_, fist) in fists) dumbbell(fist, axis = V3.Z, headRadius = 0.05f)
}

/**
 * Roundhouse kick on the heavy bag: the rear (near) leg chambers out to the side and swings round
 * to strike the bag with the shin, the support foot pivoting and the trunk leaning away; back to the guard.
 */
internal fun bagKicks() = Motion.cycle(2.2f, camera = Camera(50f, 6f)) { phase ->
    val t = phase * 2.2f
    val k = ramp(t, 0.3f to 0f, 0.64f to 1f, 0.98f to 2f, 1.1f to 2f, 1.4f to 1f, 1.75f to 0f)
    val swing = (k - 1f).coerceAtLeast(0f)
    val up = k.coerceAtMost(1f)
    body.torso(v(-0.02f, 0.86f - 0.03f * up), Basis.UPRIGHT.pitch(8f - 20f * k / 2f).turn(26f - 50f * k / 2f), twist = -10f * swing, sideways = -14f * k / 2f, nod = 6f + 8f * swing)
    body.stand(Side.FAR, 0.12f, z = -0.1f, lift = 18f * up, out = 20f + 70f * k / 2f, pole = v(1f, 0.1f, -0.2f))
    val planted = v(-0.24f, BodySize.ANKLE, 0.16f)
    val chamber = v(0.02f, 0.55f, 0.46f)
    val strike = v(0.4f, 0.92f, 0.2f)
    val ankle = if (k <= 1f) lerp(planted, chamber, Ease.IN_OUT.apply(up)) + V3.Y * (0.05f * sin(PI.toFloat() * up)) else lerp(chamber, strike, Ease.IN_OUT.apply(swing))
    val pole = lerp(v(1f, 0.1f, 0.2f), v(0.5f, 0.6f, -0.6f), up)
    val ik = twoBone(body.hip(Side.NEAR), ankle, BodySize.THIGH, BodySize.SHIN, pole)
    val shin = (ik.end - ik.mid).unit()
    val air = pointedAlong(shin, ik.apex, 40f)
    val flat = Foot.flat(Side.NEAR, 35f)
    val foot = Foot(lerp(flat.dir, air.dir, up).unit(), lerp(flat.up, air.up, up).unit())
    body.leg(Side.NEAR, footAbove(ankle, foot), pole, foot)
    body.arm(Side.FAR, body.onChest(0.26f, 0.12f, -0.1f), v(-0.2f, -1f, -0.3f))
    body.arm(Side.NEAR, within(body.shoulder(Side.NEAR), lerp(body.onChest(0.18f, 0.12f, 0.1f), body.onHips(0.1f, 0f, 0.26f), swing)), v(-0.2f, -1f, 0.4f))
    gloves(mapOf(Side.FAR to body.handOf(Side.FAR), Side.NEAR to body.handOf(Side.NEAR)))
    val hit = knock(t, 0.98f, fall = 0.7f)
    hangingBag(v(0.66f, 2.35f, 0.06f), -6f * hit)
}

/**
 * Knee strikes on the heavy bag: holding the bag with both hands as in a clinch, the near knee drives
 * up into it, the hips pushing forward and the support foot on its toes; then the far knee.
 */
internal fun kneeStrikes() = Motion.cycle(2.0f, camera = Camera(30f, 4f)) { phase ->
    val t = phase * 2.0f
    val knees = Side.entries.associateWith { side -> ramp(t, *(if (side == Side.NEAR) arrayOf(0.2f to 0f, 0.5f to 1f, 0.75f to 0f) else arrayOf(1.2f to 0f, 1.5f to 1f, 1.75f to 0f))) }
    val drive = knees.values.max()
    body.torso(v(-0.06f + 0.06f * drive, 0.88f + 0.03f * drive), Basis.UPRIGHT.pitch(10f - 18f * drive), nod = 8f + 10f * drive)
    for (side in Side.entries) {
        val k = knees.getValue(side)
        val other = knees.getValue(side.other)
        val planted = v(-0.06f, BodySize.ANKLE, side.sign * 0.13f)
        val raised = v(0.12f, 0.6f, side.sign * 0.08f)
        val ankle = lerp(planted, raised, k)
        val flat = Foot.flat(side, 6f)
        val air = Foot(dirXY(-55f), dirXY(35f))
        val foot = Foot(lerp(flat.dir, air.dir, k).unit(), lerp(flat.up, air.up, k).unit())
        if (k > 0f) {
            body.leg(side, footAbove(ankle, foot), v(1f, 0.6f, 0f), foot)
        } else {
            body.stand(side, -0.06f, z = side.sign * 0.13f, lift = 24f * other, out = 6f, pole = v(1f, 0.1f, side.sign * 0.1f))
        }
        body.arm(side, v(0.5f, 1.48f, side.sign * 0.15f), v(-0.3f, -0.4f, side.sign * 1f))
    }
    gloves(mapOf(Side.FAR to body.handOf(Side.FAR), Side.NEAR to body.handOf(Side.NEAR)))
    hangingBag(v(0.48f, 2.35f), 5f * (knock(t, 0.5f, fall = 0.5f) + knock(t, 1.5f, fall = 0.5f)))
}

/**
 * Sprawl: from the fighting stance the hands drop to the floor and the feet jump back (as in a
 * burpee), then the hips drop to the floor with the legs long behind and the chest up; back up the
 * same way to the stance.
 */
internal fun sprawl(): Motion {
    val guard = keyHands(v(0.24f, -0.06f, 0.13f))
    val floor = keyHands(v(0.3f, BodySize.FIST_RADIUS, 0.26f), planted = true)
    val stance = v(-0.02f, 0.84f)
    val crouch = v(-0.2f, 0.48f)
    val flat = v(0f, BodySize.ANKLE)
    val legs = BodySize.THIGH + BodySize.SHIN - 0.01f
    val plankAnkle = v(-0.86f, 0.185f)
    val plank = plankAnkle + dirXY(17f) * legs
    // Hips down, the legs long behind on the toes.
    val downAnkle = v(-1.2f, 0.12f)
    val down = downAnkle + dirXY(8f) * (legs - 0.02f)
    val keys = listOf(
        BodyKey(0f, stance, 10f, flat, guard),
        BodyKey(0.3f, crouch, 78f, flat, floor, hop = 0.1f),
        BodyKey(0.7f, plank, 73f, plankAnkle, floor, 17f - 85f),
        BodyKey(0.95f, down, 66f, downAnkle, floor, 8f - 85f),
        BodyKey(1.2f, down, 66f, downAnkle, floor, 8f - 85f),
        BodyKey(1.45f, plank, 73f, plankAnkle, floor, 17f - 85f, hop = 0.1f),
        BodyKey(1.85f, crouch, 78f, flat, floor),
        BodyKey(2.3f, stance, 10f, flat, guard),
    )
    return Motion.cycle(2.3f, camera = Camera(26f, 6f)) { phase ->
        keyedBody(blendKeys(keys, phase * 2.3f), feet = 0.3f) { side -> v(-1f, -0.3f, side.sign * 0.5f) }
    }
}
