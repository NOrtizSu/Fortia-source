package com.enjoyyourtime.globalfitness.ui.training.motion

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

// Sports added in 1.8 (joined into sportsMotions): skiing, snowboarding, surfing, rowing, kayaking,
// rugby and skating. As with the other activities, the ground or the water passes by under the
// figure, which stays in the middle of the picture.

private const val TAU = 2 * PI.toFloat()

/** A ski under a boot whose toe is at [toe]: a long flat strip on the snow with its tip curling up. */
private fun FrameBuilder.ski(toe: V3) {
    val y = 0.018f
    add(Rod(v(toe.x - 0.95f, y, toe.z), v(toe.x + 0.75f, y, toe.z), 0.014f, Ink.BAND, bias = -0.02f))
    add(Rod(v(toe.x + 0.75f, y, toe.z), v(toe.x + 0.88f, y + 0.07f, toe.z), 0.014f, Ink.BAND, bias = -0.02f))
}

/** A ski pole held at [hand] with its tip at [tip]. */
private fun FrameBuilder.skiPole(hand: V3, tip: V3) {
    add(Rod(hand + V3.Y * 0.06f, tip, 0.011f, Ink.METAL))
    add(Disc(lerp(tip, hand, 0.06f), V3.Y, 0.04f, 0.01f, Ink.METAL))
}

/**
 * Alpine skiing, seen from in front: knees and hips flexed, hands forward with the poles; the legs
 * tip from one edge to the other through linked turns while the upper body stays quiet over the
 * skis, the snow going by.
 */
internal fun alpineSkiing() = Motion.cycle(2.4f, camera = Camera(64f, 12f)) { phase ->
    val turn = sin(phase * TAU)
    val boot = 0.035f
    // Low through each turn, rising through the transition between turns to change edges.
    val pelvisY = 0.74f + 0.04f * cos(phase * TAU * 2)
    body.torso(v(-0.1f, pelvisY, 0.05f * turn), Basis.UPRIGHT.pitch(20f).roll(-7f * turn), twist = -9f * turn, sideways = 8f * turn, nod = -16f)
    for (side in Side.entries) {
        val z = side.sign * 0.12f - 0.2f * turn
        val edge = rad(24f * turn)
        val up = v(0f, cos(edge), sin(edge))
        val foot = Foot(V3.X, up)
        val ankle = v(0f, boot + BodySize.ANKLE, z)
        body.leg(side, ankle, v(1f, 0.15f, -0.5f * turn), foot)
        ski(body.legOf(side).toe.withY(0f))
        val plant = max(0f, side.sign * cos(phase * TAU))
        val hand = body.onChest(0.38f, -0.3f, side.sign * 0.3f) + V3.X * (0.06f * plant)
        body.arm(side, hand, v(-0.3f, -1f, side.sign * 0.6f))
        skiPole(hand, v(hand.x - 0.35f + 0.25f * plant, 0.02f, hand.z + side.sign * 0.12f))
    }
    groundMarks(phase, 3f, z = 0.6f)
}

/**
 * Cross-country skiing, classic diagonal stride: one ski kicks and the other glides, the heel of the
 * kicking foot lifting off the ski, the opposite pole planted behind; the snow goes by.
 */
internal fun crossCountrySkiing() = Motion.cycle(1.8f, cycles = 2, camera = Camera(22f)) { phase ->
    body.torso(v(0f, 0.85f + 0.012f * cos(phase * TAU * 2)), Basis.UPRIGHT.pitch(12f), nod = -8f)
    for (side in Side.entries) {
        val psi = phase * TAU + if (side == Side.NEAR) 0f else PI.toFloat()
        val x = 0.32f * sin(psi)
        val lift = 28f * max(0f, (-sin(psi) - 0.25f) / 0.75f)
        val ankle = liftedAnkle(x, 0.03f, lift).withZ(side.sign * 0.1f)
        val a = rad(lift)
        body.leg(side, ankle, v(1f, 0.1f, 0f), Foot(v(cos(a), -sin(a)), v(sin(a), cos(a))))
        ski(v(x + BodySize.TOE, 0f, side.sign * 0.1f))
        val swing = sin(psi)
        body.swingArm(side, 8f - 40f * swing, out = 9f, elbow = 24f)
        val hand = body.armOf(side).hand
        skiPole(hand, v(hand.x - 0.5f, 0.02f, hand.z + side.sign * 0.06f))
    }
    groundMarks(phase, 1.6f)
}

/**
 * Snowboarding, seen from in front: side-on on the board, knees bent and arms out, rocking from the
 * heel edge to the toe edge through turns; the snow goes by along the board.
 */
internal fun snowboarding() = Motion.cycle(3.0f, camera = Camera(80f, 10f)) { phase ->
    val turn = sin(phase * TAU)
    val edge = 10f * turn
    val board = Basis.UPRIGHT.pitch(edge)
    val top = 0.045f
    body.torso(v(-0.08f + 0.07f * turn, 0.72f + 0.03f * cos(phase * TAU * 2)), Basis.UPRIGHT.pitch(14f + 10f * turn), nod = -10f - 6f * turn)
    for (side in Side.entries) {
        val z = side.sign * 0.26f
        val dir = rotate(board.f, board.u, rad(side.sign * 14f))
        body.leg(side, v(0f, top + BodySize.ANKLE, z), v(1f, 0.1f, side.sign * 0.4f), Foot(dir, board.u))
        body.armAngles(side, limbDir(body.chest, side, 34f + 10f * side.sign * turn, 48f), 26f, fold = body.chest.f)
    }
    add(Block(v(0f, 0.022f, 0f), board, v(0.13f, 0.016f, 0.78f), Ink.BAND, bias = -0.05f))
    // Snow marks sliding along the board's direction.
    val spacing = 0.5f
    var z = 1.4f - (phase * 3f) % spacing
    while (z > -1.4f) {
        add(Rod(v(0.45f, 0.006f, z - 0.08f), v(0.45f, 0.006f, z), 0.009f, Ink.FRAME, fit = false, bias = BEHIND))
        z -= spacing
    }
}

/**
 * Surfing: riding a wave side-on on the board, the front arm forward and the back arm behind for
 * balance, knees bent and pumping as the board pitches over the water.
 */
internal fun surfing() = Motion.cycle(3.0f, camera = Camera(30f, 8f)) { phase ->
    val w = 4f * sin(phase * TAU)
    val board = Basis.UPRIGHT.pitch(w)
    val center = v(0f, 0.07f + 0.02f * sin(phase * TAU + 1f))
    val pump = 0.04f * sin(phase * TAU * 2)
    // Facing the viewer's side (+Z): the near (right) foot at the back of the board.
    body.torso(v(0f, center.y + 0.71f + pump, -0.06f), Basis.UPRIGHT.turn(90f).pitch(16f), nod = 6f, twist = 20f)
    for (side in Side.entries) {
        val x = if (side == Side.NEAR) -0.32f else 0.3f
        val on = center + board.f * x + board.u * 0.03f
        val dir = rotate(V3.Z, board.u, rad(side.sign * 12f))
        body.leg(side, on + board.u * BodySize.ANKLE, v(-side.sign * 0.4f, 0.1f, 1f), Foot(dir, board.u))
    }
    body.armAngles(Side.FAR, v(1f, -0.1f + 0.1f * sin(phase * TAU), 0.35f).unit(), 18f, fold = V3.Y)
    body.armAngles(Side.NEAR, v(-1f, -0.35f, 0.4f).unit(), 24f, fold = V3.Y)
    add(Block(center, board, v(1.0f, 0.03f, 0.14f), Ink.BAND, bias = -0.1f))
    add(Cord(List(25) { i -> val x = -1.8f + 3.6f * i / 24; v(x, 0.02f + 0.02f * sin(x * 5f - phase * TAU), -0.3f) }, 0.018f, Ink.FRAME, fit = false, bias = BEHIND))
    add(Cord(List(25) { i -> val x = -1.8f + 3.6f * i / 24; v(x, 0.5f + 0.35f * cos(x * 1.4f) - 0.05f * sin(phase * TAU), -1.4f) }, 0.03f, Ink.FRAME, fit = false, bias = BEHIND))
}

/**
 * Rowing a scull on the water: the legs drive, the trunk swings back and the hands pull the oar
 * handles to the ribs, the blades sweeping through the water; then the slow recovery with the
 * blades out of it.
 */
internal fun rowingBoat() = Motion.rep(Track.rep(go = 0.8f, hold = 0.1f, back = 1.3f, rest = 0.15f), Camera(34f, 16f)) { r ->
    val legs = Ease.IN_OUT.apply((r / 0.6f).coerceIn(0f, 1f))
    val swing = Ease.IN_OUT.apply(((r - 0.3f) / 0.4f).coerceIn(0f, 1f))
    val arms = Ease.IN_OUT.apply(((r - 0.55f) / 0.45f).coerceIn(0f, 1f))
    val seatTop = 0.3f
    val seatX = lerp(0.1f, -0.19f, legs)
    body.torso(v(seatX, seatTop + SEAT_HIP), Basis.UPRIGHT.pitch(lerp(26f, -22f, swing)), nod = -6f)
    for (side in Side.entries) body.leg(side, v(0.62f, 0.3f, side.sign * 0.1f), v(0.3f, 1f, 0f), Foot(dirXY(58f), dirXY(148f)))
    val shoulders = body.shoulders()
    val reach = v(shoulders.x + armReach(0.08f, 0f) * 0.9f, shoulders.y - 0.2f)
    val ribs = body.onChest(0.2f, -0.3f)
    val handle = lerp(reach, ribs.withZ(0f), arms) - V3.Y * (0.05f * (1f - r))
    for (side in Side.entries) {
        val hand = handle.withZ(side.sign * 0.13f)
        body.arm(side, hand, v(-1f, -0.3f, side.sign * 0.5f))
        val pin = v(0.18f, 0.5f, side.sign * 0.8f)
        val out = (pin - hand).unit()
        add(Rod(hand, pin + out * 1.55f, 0.014f, Ink.METAL, fit = false, bias = -0.05f))
        add(Disc(pin + out * 1.7f, v(1f, 0f, 0f), 0.11f, 0.02f, Ink.BAND, fit = false, bias = -0.05f))
        add(Rod(v(0.1f, 0.24f, side.sign * 0.2f), pin, 0.012f, Ink.FRAME, fit = false, bias = -0.15f))
    }
    pad(v(seatX - 0.14f, seatTop - 0.04f), v(seatX + 0.14f, seatTop - 0.04f), width = 0.26f, bias = -0.2f)
    add(Block(v(0.1f, 0.15f), Basis.UPRIGHT, v(1.7f, 0.07f, 0.17f), Ink.PAD, fit = false, bias = -0.3f))
    add(Cord(List(25) { i -> val x = -2.2f + 4.4f * i / 24; v(x, 0.1f, -0.5f) }, 0.018f, Ink.FRAME, fit = false, bias = BEHIND))
}

/**
 * Kayaking: seated in the kayak with the legs forward, the double-bladed paddle dips on one side and
 * then the other, the trunk turning with each stroke.
 */
internal fun kayaking() = Motion.cycle(2.0f, camera = Camera(40f, 18f)) { phase ->
    val a = phase * TAU
    body.torso(v(0f, 0.22f), Basis.UPRIGHT.pitch(6f), twist = 22f * cos(a), nod = -6f)
    for (side in Side.entries) body.legAngles(side, v(1f, 0.12f, side.sign * 0.1f).unit(), 24f, fold = dirXY(-90f))
    val shaft = v(0.2f * cos(a), -0.42f * cos(a), 0.88f).unit()
    val center = body.onChest(0.3f, -0.24f) - V3.X * (0.05f * sin(a))
    for (side in Side.entries) body.arm(side, center + shaft * (side.sign * 0.31f), v(-0.3f, -1f, side.sign * 0.6f))
    add(Rod(center - shaft * 1.0f, center + shaft * 1.0f, 0.016f, Ink.METAL, fit = false))
    for (s in listOf(-1f, 1f)) add(Disc(center + shaft * (s * 1.08f), orthogonal(V3.X, shaft), 0.1f, 0.015f, Ink.BAND, fit = false))
    add(Block(v(0.15f, 0.13f), Basis.UPRIGHT, v(1.6f, 0.08f, 0.3f), Ink.PAD, fit = false, bias = -0.3f))
    add(Cord(List(25) { i -> val x = -2.2f + 4.4f * i / 24; v(x, 0.06f + 0.015f * sin(x * 6f - a), -0.6f) }, 0.018f, Ink.FRAME, fit = false, bias = BEHIND))
}

/** Rugby: running with the ball held in both hands in front of the chest, ready to pass; the pitch goes by. */
internal fun rugby() = Motion.cycle(1.44f, cycles = 2, camera = Camera(24f)) { phase ->
    body.torso(v(0f, 0.85f + 0.025f * cos(phase * TAU * 2)), Basis.UPRIGHT.pitch(12f), nod = -6f)
    for (side in Side.entries) gaitLeg(side, (phase + if (side == Side.NEAR) 0f else 0.5f) % 1f, reach = 0.3f, lift = 0.1f, stance = 0.38f, heelKick = 0.22f)
    val ball = body.onChest(0.34f, -0.3f) + V3.Y * (0.015f * sin(phase * TAU * 2))
    for (side in Side.entries) body.arm(side, ball + v(-0.02f, -0.03f, side.sign * 0.12f), v(-0.3f, -1f, side.sign * 0.6f))
    val along = v(1f, 0.15f, 0f).unit()
    add(Rod(ball - along * 0.07f, ball + along * 0.07f, 0.095f, Ink.BAND, bias = 0.03f))
    groundMarks(phase, 2 * 0.3f / 0.38f)
}

/**
 * Skating stride, seen from the front: low with the trunk forward, each skate pushes out to the side
 * and back, then comes back in under the body lifted, while the weight shifts onto the gliding
 * skate and the arms swing; [ice] skates on blades, otherwise on inline wheels.
 */
internal fun skating(ice: Boolean) = Motion.cycle(2.0f, camera = Camera(52f, 10f)) { phase ->
    val shift = sin(phase * TAU)
    val lift = if (ice) 0.06f else 0.075f
    body.torso(v(-0.1f, 0.78f, 0.08f * shift), Basis.UPRIGHT.pitch(32f).roll(-4f * shift), twist = -8f * shift, nod = -22f)
    for (side in Side.entries) {
        val p = (phase + if (side == Side.NEAR) 0f else 0.5f) % 1f
        val (x, z, up) = if (p < 0.5f) {
            val s = Ease.IN_OUT.apply(p / 0.5f)
            Triple(lerp(0.04f, -0.24f, s), lerp(0.08f, 0.42f, s), 0f)
        } else {
            val s = Ease.IN_OUT.apply((p - 0.5f) / 0.5f)
            Triple(lerp(-0.24f, 0.04f, s), lerp(0.42f, 0.08f, s), 0.08f * sin(PI.toFloat() * s))
        }
        val out = rad(lerp(5f, 24f, ((0.42f - (z - 0.08f)) / 0.34f).let { 1f - it.coerceIn(0f, 1f) }))
        val foot = Foot(v(cos(out), 0f, side.sign * sin(out)), V3.Y)
        body.leg(side, v(x, lift + up + BodySize.ANKLE, side.sign * z), v(1f, 0.15f, side.sign * 0.3f), foot)
        val l = body.legOf(side)
        val sole = l.ankle - V3.Y * (BodySize.SOLE + BodySize.FOOT_RADIUS)
        if (ice) {
            add(Rod(sole + foot.dir * -0.1f - V3.Y * 0.035f, sole + foot.dir * 0.16f - V3.Y * 0.035f, 0.008f, Ink.METAL, bias = 0.02f))
        } else {
            for (k in 0 until 4) add(Disc(sole + foot.dir * (-0.07f + 0.065f * k) - V3.Y * 0.035f, foot.dir cross V3.Y, 0.033f, 0.02f, Ink.CABLE, bias = 0.02f))
        }
        body.swingArm(side, 30f * side.sign * shift, out = 14f, elbow = if (ice) 20f else 34f)
    }
    groundMarks(phase, 1.6f, z = 0.6f)
}
