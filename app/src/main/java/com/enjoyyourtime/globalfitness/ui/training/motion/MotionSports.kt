package com.enjoyyourtime.globalfitness.ui.training.motion

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

// Sports and outdoor activities (SportsCatalog). Steady activities (walking, running, cycling,
// swimming, dribbling, climbing) are cycles driven by a phase; a play (a spike, a throw, a stroke,
// a punch, a kick, a swing, a yoga flow) is laid out as keyed channels over the loop. Outdoors the
// ground passes by under the feet, as on a treadmill, so the figure stays in the middle.

private const val TAU = 2 * PI.toFloat()

/** The animation of each sport and activity, joined into [MotionCatalog]. */
internal val sportsMotions: Map<String, () -> Motion> = mapOf(
    "football" to { dribble(futsal = false) },
    "futsal" to { dribble(futsal = true) },
    "basketball" to { basketballDribble() },
    "volleyball" to { volleyballSpike() },
    "handball" to { handballThrow() },
    "tennis" to { racketStroke(Racket.TENNIS) },
    "padel" to { racketStroke(Racket.PADEL) },
    "badminton" to { racketStroke(Racket.BADMINTON) },
    "outdoor_walk" to { walk(hiking = false) },
    "hiking" to { walk(hiking = true) },
    "nordic_walking" to { nordicWalk() },
    "stair_climbing" to { stairClimbing() },
    "outdoor_run" to { outdoorRun() },
    "outdoor_cycling" to { roadCycling() },
    "swimming" to { frontCrawl() },
    "yoga" to { yogaFlow() },
    "pilates" to { rollUp() },
    "dance" to { stepTouch() },
    "boxing" to { jabCross() },
    "martial_arts" to { frontKick() },
    "climbing" to { climbing() },
    "golf" to { golfSwing() },
)

// ---- Shared pieces ------------------------------------------------------------------------------

/**
 * Short marks on the ground sliding back as the feet in stance do, [travel] metres per phase; the
 * spacing divides it, so the loop shows no jump.
 */
internal fun FrameBuilder.groundMarks(phase: Float, travel: Float, ink: Ink = Ink.FRAME, z: Float = 0.26f) {
    val n = max(1, (travel / 0.45f).roundToInt())
    val spacing = travel / n
    var x = 1.2f - (phase * travel) % spacing
    while (x > -1.2f) {
        add(Rod(v(x - 0.08f, 0.006f, z), v(x, 0.006f, z), 0.009f, ink, fit = false, bias = BEHIND))
        x -= spacing
    }
}

/**
 * The [side] arm swung [angle] degrees forward from hanging (see [limbDir]), the elbow bent [elbow]
 * degrees the way elbows bend: towards the arm's front, which turns with the swing.
 */
internal fun Rig.swingArm(side: Side, angle: Float, out: Float, elbow: Float) {
    armAngles(side, limbDir(chest, side, angle, out), elbow, fold = limbDir(chest, side, angle + 90f, out))
}

/** A looping point: one [Track] per coordinate over the same key times (the last point equal to the first). */
private class PointTrack(times: List<Float>, points: List<V3>) {
    private val x = Track(times.zip(points) { t, p -> Key(t, p.x) })
    private val y = Track(times.zip(points) { t, p -> Key(t, p.y) })
    private val z = Track(times.zip(points) { t, p -> Key(t, p.z) })

    fun at(t: Float): V3 = V3(x.value(t), y.value(t), z.value(t))
}

/** A ball of the game, in the accent colour. */
private fun FrameBuilder.gameBall(center: V3, radius: Float, fit: Boolean = true, bias: Float = 0.02f) {
    add(Ball(center, radius, Ink.BAND, fit = fit, bias = bias))
}

// ---- On foot, on wheels, in the water -----------------------------------------------------------

/**
 * Walking outdoors, heel to toe, the arms swinging against the legs. [hiking]: a slower, longer
 * stride leaning into the slope, with a backpack and trekking poles planted behind the hands.
 */
private fun walk(hiking: Boolean) = Motion.cycle(if (hiking) 2.6f else 2.2f, cycles = 2, camera = Camera(if (hiking) 26f else 24f)) { phase ->
    val reach = if (hiking) 0.24f else 0.25f
    val stance = if (hiking) 0.62f else 0.6f
    body.torso(v(0f, (if (hiking) 0.865f else 0.875f) + 0.012f * cos(phase * TAU * 2)), Basis.UPRIGHT.pitch(if (hiking) 11f else 3f), nod = if (hiking) -8f else -3f)
    for (side in Side.entries) {
        gaitLeg(side, (phase + if (side == Side.NEAR) 0f else 0.5f) % 1f, reach = reach, lift = if (hiking) 0.09f else 0.07f, stance = stance)
        // Each arm swings against its own leg: forward while the foot is behind.
        val swing = -body.legOf(side).ankle.x / reach
        if (hiking) {
            body.swingArm(side, 22f + 26f * swing, out = 12f, elbow = 50f)
            val hand = body.armOf(side).hand
            add(Rod(hand + V3.Y * 0.06f, v(hand.x - 0.3f, 0.015f, hand.z + side.sign * 0.05f), 0.011f, Ink.METAL))
        } else {
            body.swingArm(side, 24f * swing, out = 7f, elbow = 16f)
        }
    }
    if (hiking) add(Block(body.onChest(-0.17f, -0.2f), body.chest, v(0.08f, 0.21f, 0.15f), Ink.PAD, bias = -0.05f))
    groundMarks(phase, 2 * reach / stance)
}

/**
 * Nordic walking: a long stride with a wide, long-armed swing; each pole's tip planted well behind
 * the hand, so the arm pushes it back past the hip.
 */
private fun nordicWalk() = Motion.cycle(2.2f, cycles = 2, camera = Camera(24f)) { phase ->
    val reach = 0.28f
    val stance = 0.6f
    body.torso(v(0f, 0.865f + 0.012f * cos(phase * TAU * 2)), Basis.UPRIGHT.pitch(7f), nod = -4f)
    for (side in Side.entries) {
        gaitLeg(side, (phase + if (side == Side.NEAR) 0f else 0.5f) % 1f, reach = reach, lift = 0.07f, stance = stance)
        val swing = -body.legOf(side).ankle.x / reach
        body.swingArm(side, 10f + 34f * swing, out = 10f, elbow = 22f)
        val hand = body.armOf(side).hand
        add(Rod(hand + V3.Y * 0.06f, v(hand.x - 0.52f, 0.015f, hand.z + side.sign * 0.06f), 0.011f, Ink.METAL))
    }
    groundMarks(phase, 2 * reach / stance)
}

/**
 * Climbing stairs: the staircase moves down and back one step per half loop while the figure climbs
 * in place, one step per foot, the near hand sliding along the rail and the far arm swinging.
 */
private fun stairClimbing() = Motion.cycle(1.8f, camera = Camera(24f)) { phase ->
    val rise = 0.17f
    val depth = 0.28f
    fun level(l: Float) = v(depth * l, rise * l)
    body.torso(level(0.5f) + v(-0.17f, 0.8f), Basis.UPRIGHT.pitch(10f), nod = -10f)
    for (side in Side.entries) {
        val psi = (phase + if (side == Side.NEAR) 0f else 0.5f) % 1f
        val foot = if (psi < 0.5f) {
            level(1f - 2 * psi)
        } else {
            val s = Ease.IN_OUT.apply(2 * psi - 1f)
            level(s) + V3.Y * (0.12f * sin(PI.toFloat() * s))
        }
        body.leg(side, foot + V3.Y * BodySize.ANKLE + V3.Z * (side.sign * 0.11f), v(1f, 0.2f, 0f), Foot.flat(side, 4f))
    }
    // The near hand on the rail (a line along the stairs, so the hand stays put while they move); the far arm swings.
    val rail = { x: Float -> 0.95f + x * rise / depth }
    body.arm(Side.NEAR, v(0.28f, rail(0.28f), 0.42f), v(-0.3f, -1f, 0.6f))
    body.swingArm(Side.FAR, 20f * sin(phase * TAU * 2), out = 8f, elbow = 30f)
    val sigma = (2 * phase) % 1f
    for (j in -2..4) {
        val top = level(j - sigma)
        if (top.y > 0f) box(v(top.x - depth / 2, 0f, -0.4f), v(top.x + depth / 2, top.y, 0.4f), Ink.PAD, fit = false, bias = -0.3f)
    }
    add(Rod(v(-0.6f, rail(-0.6f), 0.46f), v(1.2f, rail(1.2f), 0.46f), 0.02f, Ink.METAL, fit = false, bias = -0.2f))
}

/** Running outdoors: two strides per loop with a flight phase, arms bent and swinging, the ground going by. */
private fun outdoorRun() = Motion.cycle(1.44f, cycles = 2, camera = Camera(22f)) { phase ->
    body.torso(v(0f, 0.85f + 0.025f * cos(phase * TAU * 2)), Basis.UPRIGHT.pitch(9f), nod = -8f)
    for (side in Side.entries) {
        val legPhase = (phase + if (side == Side.NEAR) 0f else 0.5f) % 1f
        gaitLeg(side, legPhase, reach = 0.3f, lift = 0.1f, stance = 0.38f, heelKick = 0.22f)
        val swing = 34f * sin((legPhase + 0.5f) * TAU + 0.6f)
        body.armAngles(side, limbDir(body.chest, side, swing, 6f), 92f, fold = body.chest.f + body.chest.u * 0.2f)
    }
    groundMarks(phase, 2 * 0.3f / 0.38f)
}

/** Road cycling: hands on the hoods, trunk forward, pedalling; the wheels turn and the road passes by. */
private fun roadCycling() = Motion.cycle(1.8f, cycles = 2, camera = Camera(18f)) { phase ->
    val crank = v(0f, 0.3f)
    val seat = v(-0.2f, 0.92f)
    val rear = v(-0.42f, 0.34f)
    val front = v(0.6f, 0.34f)
    val wheel = 0.34f
    body.torso(seat + V3.Y * SEAT_HIP, Basis.UPRIGHT.pitch(42f), nod = -32f)
    val angle = -phase * TAU
    for (side in Side.entries) {
        val a = angle + if (side == Side.NEAR) 0f else PI.toFloat()
        val pedal = crank + v(cos(a), sin(a)) * 0.17f + V3.Z * (side.sign * 0.12f)
        val foot = body.ballOn(side, pedal, v(1f, 0.3f, side.sign * 0.1f))
        add(Rod(crank + V3.Z * (side.sign * 0.08f), pedal, 0.016f, Ink.METAL, bias = 0.05f))
        add(Rod(pedal - foot.dir * 0.06f, pedal + foot.dir * 0.06f, 0.014f, Ink.METAL, bias = 0.05f))
    }
    val hoods = v(0.47f, 1.0f)
    for (side in Side.entries) body.arm(side, hoods + V3.Z * (side.sign * 0.2f), v(-0.3f, -1f, side.sign * 0.7f))
    add(Rod(hoods.withZ(-0.21f), hoods.withZ(0.21f), 0.016f, Ink.METAL, bias = -0.1f))
    // Frame: stays, seat tube, top and down tubes, fork and stem.
    val head = v(0.5f, 0.86f)
    val headLow = v(0.53f, 0.72f)
    for (tube in listOf(rear to crank, rear to seat + v(0.02f, -0.06f), crank to seat + v(0.02f, -0.06f), seat + v(0.02f, -0.06f) to head, crank to headLow, headLow to head, headLow to front, head to hoods)) {
        add(Rod(tube.first, tube.second, 0.022f, Ink.FRAME, bias = -0.3f))
    }
    add(Block(seat + v(0f, -0.02f), Basis.UPRIGHT, v(0.12f, 0.025f, 0.06f), Ink.PAD, bias = -0.2f))
    // Wheels: tyre and three spokes turning once per pedal stroke, as far as the road moves.
    for (hub in listOf(rear, front)) {
        add(Cord(List(25) { i -> hub + v(cos(i * TAU / 24), sin(i * TAU / 24)) * wheel }, 0.028f, Ink.METAL, bias = -0.35f))
        for (k in 0 until 3) {
            val s = angle + k * TAU / 3
            add(Rod(hub, hub + v(cos(s), sin(s)) * wheel, 0.006f, Ink.FRAME, bias = -0.36f))
        }
    }
    add(Disc(crank, V3.Z, 0.08f, 0.02f, Ink.METAL, bias = 0.02f))
    groundMarks(phase, TAU * wheel, z = 0.3f)
}

/**
 * Front crawl seen from the side: the arms pull under the body and recover over the water half a
 * stroke apart, the body rolls towards the pulling arm, the legs flutter-kick three times a stroke.
 */
private fun frontCrawl() = Motion.cycle(2.2f, camera = Camera(18f, 14f)) { phase ->
    // High enough above the floor line that the view frames the swimmer and the water only.
    val water = 1.72f
    body.torso(v(-0.45f, 1.6f), Basis.PRONE.turn(26f * sin(phase * TAU)), nod = -14f)
    for (side in Side.entries) {
        val p = (phase + if (side == Side.NEAR) 0f else 0.5f) % 1f
        if (p < 0.5f) {
            // Pull: from reaching forward, down and back to the hip, the hand sweeping in under the body.
            val s = p / 0.5f
            val a = lerp(0f, -180f, Ease.IN_OUT.apply(s))
            val dir = (dirXY(a) + V3.Z * (side.sign * 0.18f)).unit()
            body.armAngles(side, dir, 28f * sin(PI.toFloat() * s), fold = V3.Z * -side.sign)
        } else {
            // Recovery over the water, the elbow bending to the arm's front (the hand trailing it),
            // straightening as the hand reaches forward to enter the water.
            val s = (p - 0.5f) / 0.5f
            val a = lerp(-180f, -360f, Ease.IN_OUT.apply(s))
            val dir = (dirXY(a) + V3.Z * (side.sign * (0.18f + 0.3f * sin(PI.toFloat() * s)))).unit()
            body.armAngles(side, dir, 95f * sin(PI.toFloat() * s) * (1f - 0.5f * s), fold = dirXY(a + 90f))
        }
    }
    for (side in Side.entries) {
        val k = sin((phase * 3f + if (side == Side.NEAR) 0f else 0.5f) * TAU)
        // Down-beat: the thigh drops while the knee bends a little; the shin stays near the surface.
        val thigh = v(-1f, -0.1f * k, side.sign * 0.05f).unit()
        val knee = 10f + 8f * k
        // Pointed toes: 25° short of the line of the shin, which bends up towards the surface.
        val fold = orthogonal(V3.Y, thigh)
        val shin = thigh * cos(rad(knee)) + fold * sin(rad(knee))
        val front = orthogonal(-V3.Y, shin)
        val toes = (shin * cos(rad(25f)) + front * sin(rad(25f))).unit()
        body.legAngles(side, thigh, knee, fold = V3.Y, foot = Foot(toes, orthogonal(front, toes)))
    }
    // The surface: a gentle wave behind the swimmer.
    add(Cord(List(25) { i -> val x = -1.7f + 2.8f * i / 24; v(x, water + 0.015f * sin(x * 9f - phase * TAU * 2), -0.35f) }, 0.018f, Ink.FRAME, fit = false, bias = BEHIND))
}

// ---- Ball games ---------------------------------------------------------------------------------

/**
 * Dribbling at a jog: the near foot touches the ball forward at each of its steps; the ball rolls
 * ahead and comes back to the foot as the runner catches up, turning as it rolls. [futsal]: the
 * smaller, heavier ball kept closer with shorter steps, on a court line instead of grass.
 */
private fun dribble(futsal: Boolean) = Motion.cycle(if (futsal) 1.4f else 1.6f, cycles = 2, camera = Camera(26f)) { phase ->
    val reach = if (futsal) 0.22f else 0.27f
    val stance = 0.45f
    body.torso(v(0f, 0.86f + 0.015f * cos(phase * TAU * 2)), Basis.UPRIGHT.pitch(12f), nod = 10f)
    for (side in Side.entries) {
        val legPhase = (phase + if (side == Side.NEAR) 0f else 0.5f) % 1f
        gaitLeg(side, legPhase, reach = reach, lift = 0.08f, stance = stance, heelKick = 0.1f)
        val swing = 26f * sin((legPhase + 0.5f) * TAU + 0.6f)
        body.armAngles(side, limbDir(body.chest, side, swing, 14f), 70f, fold = body.chest.f + body.chest.u * 0.2f)
    }
    val r = if (futsal) 0.1f else 0.11f
    val touch = reach + BodySize.TOE + r + 0.005f
    val x = touch + (if (futsal) 0.14f else 0.24f) * sin(PI.toFloat() * phase)
    val ball = v(x, r, 0.1f)
    gameBall(ball, r)
    val travel = 2 * reach / stance
    val spin = -(((travel / (TAU * r)).roundToInt() * TAU) * phase + (x - touch) / r)
    add(Ball(ball + v(sin(spin), cos(spin)) * (r * 0.55f) + V3.Z * (r * 0.7f), r * 0.3f, Ink.PLATE_DARK, bias = 0.04f))
    groundMarks(phase, travel, ink = if (futsal) Ink.BAND else Ink.FRAME)
}

/** Basketball dribble in an athletic stance: the near hand pushes the ball down, it bounces back up into the hand. */
private fun basketballDribble() = Motion.cycle(1.5f, cycles = 2, camera = Camera(30f)) { phase ->
    body.torso(v(-0.06f, 0.8f + 0.015f * cos(phase * TAU)), Basis.UPRIGHT.pitch(20f), nod = -12f)
    body.standOn(0f, width = 0.38f, out = 12f, pole = v(1f, 0.1f, 0.3f))
    val r = 0.12f
    val top = 0.8f
    val ball = v(0.38f, r + (top - r) * abs(cos(PI.toFloat() * phase)), 0.26f)
    // The hand follows the ball down a little, waits, and meets it on the way up.
    val hand = v(ball.x - 0.02f, max(ball.y + r + 0.03f, 0.78f), ball.z)
    body.arm(Side.NEAR, hand, v(-0.3f, -0.4f, 1f))
    body.swingArm(Side.FAR, 50f, out = 25f, elbow = 80f)
    gameBall(ball, r)
}

/** The channels of [volleyballSpike], in seconds over its 2.4 s loop. */
private object Spike {
    const val LOOP = 2.4f
    const val HIT = 1.32f

    // The arms swing up over most of the take-off, so the hands never outrun a frame.
    val hip = Track.keys(0f to 0.9f, 0.45f to 0.62f, 0.95f to 1.15f, 1.25f to 1.1f, 1.6f to 0.66f, 2.05f to 0.9f, LOOP to 0.9f)
    val hipX = Track.keys(0f to 0f, 0.45f to -0.12f, 0.95f to 0.02f, 1.6f to -0.1f, 2.05f to 0f, LOOP to 0f)
    val lean = Track.keys(0f to 5f, 0.45f to 26f, 0.95f to -4f, HIT to 14f, 1.6f to 24f, 2.05f to 5f, LOOP to 5f)
    val near = Track.keys(0f to 10f, 0.3f to -20f, 1.0f to 166f, HIT to 128f, 1.6f to 40f, 2.0f to 12f, LOOP to 10f)
    val nearElbow = Track.keys(0f to 10f, 0.3f to 12f, 0.8f to 30f, 1.05f to 100f, HIT to 8f, 1.6f to 20f, 2.0f to 12f, LOOP to 10f)
    val far = Track.keys(0f to 10f, 0.3f to -20f, 1.0f to 152f, 1.15f to 148f, 1.6f to 50f, 2.0f to 12f, LOOP to 10f)

    /** Height of the feet above the floor while the body is airborne. */
    fun feet(t: Float): Float = (hip.value(t) - 0.9f).coerceAtLeast(0f)

    fun place(rig: Rig, t: Float) {
        val lift = feet(t)
        rig.torso(v(hipX.value(t), hip.value(t)), Basis.UPRIGHT.pitch(lean.value(t)), nod = -lean.value(t) * 0.3f - 10f)
        rig.stand(Side.NEAR, -0.05f, z = 0.13f, floor = lift, lift = 120f * lift, out = 10f)
        rig.stand(Side.FAR, 0.08f, z = -0.13f, floor = lift, lift = 120f * lift, out = 10f)
        rig.swingArm(Side.NEAR, near.value(t), out = 12f, elbow = nearElbow.value(t))
        rig.swingArm(Side.FAR, far.value(t), out = 14f, elbow = 14f)
    }
}

/**
 * Volleyball spike: arms swing back as the legs load, both arms sweep up in the jump, the near arm
 * cocks and hits the ball at the top of the jump, and the landing is soft on both feet.
 */
private fun volleyballSpike(): Motion {
    val contact = Rig().apply { Spike.place(this, Spike.HIT) }.armOf(Side.NEAR).hand + v(0.07f, 0.07f)
    return Motion.cycle(Spike.LOOP, camera = Camera(28f)) { phase ->
        val t = phase * Spike.LOOP
        Spike.place(body, t)
        // The ball, set high, drops into the hit and leaves fast, forward and down.
        when {
            t in 0.85f..Spike.HIT -> gameBall(contact + V3.Y * (0.25f * (Spike.HIT - t) / 0.45f), 0.105f, fit = false)
            t in Spike.HIT..1.6f -> gameBall(contact + v(3.2f, -2.2f) * (t - Spike.HIT), 0.105f, fit = false)
        }
    }
}

/** The channels of [handballThrow] over its 2.2 s loop. */
private object Throw {
    const val LOOP = 2.2f
    const val RELEASE = 1.0f
    // The arm swings out to the side and up, so the ball is cocked with the elbow out at shoulder
    // height and the forearm up (never with the hand behind a lowered elbow).
    private val times = listOf(0f, 0.35f, 0.7f, RELEASE, 1.25f, 1.45f, 1.9f, LOOP)
    val hand = PointTrack(
        times,
        listOf(v(0.15f, -0.5f, 0.05f), v(-0.12f, -0.3f, 0.45f), v(-0.08f, 0.28f, 0.24f), v(0.42f, 0.3f, -0.05f), v(0.3f, -0.4f, -0.2f), v(0.3f, -0.4f, -0.2f), v(0.15f, -0.5f, 0.05f), v(0.15f, -0.5f, 0.05f)),
    )
    val twist = Track.keys(0f to 0f, 0.7f to 35f, RELEASE to -25f, 1.3f to -15f, 1.9f to 0f, LOOP to 0f)
    val lean = Track.keys(0f to 3f, 0.7f to -4f, 1.05f to 18f, 1.4f to 8f, 1.9f to 3f, LOOP to 3f)
    val pelvisX = Track.keys(0f to 0f, 0.7f to 0.15f, RELEASE to 0.22f, 1.3f to 0.15f, 1.9f to 0f, LOOP to 0f)
    val pelvisY = Track.keys(0f to 0.88f, 0.7f to 0.85f, RELEASE to 0.82f, 1.3f to 0.84f, 1.9f to 0.88f, LOOP to 0.88f)
    val step = Track.keys(0f to 0.05f, 0.3f to 0.05f, 0.7f to 0.45f, 1.4f to 0.45f, 1.9f to 0.05f, LOOP to 0.05f)
    val stepUp = Track.keys(0f to 0f, 0.3f to 0f, 0.5f to 0.07f, 0.7f to 0f, 1.4f to 0f, 1.65f to 0.07f, 1.9f to 0f, LOOP to 0f)
    val heel = Track.keys(0f to 0f, 0.8f to 0f, 1.05f to 30f, 1.3f to 30f, 1.7f to 0f, LOOP to 0f)
    val guide = Track.keys(0f to 20f, 0.7f to 75f, 1.1f to 20f, LOOP to 20f)

    fun trunk(rig: Rig, t: Float) {
        rig.torso(v(pelvisX.value(t), pelvisY.value(t)), Basis.UPRIGHT.pitch(lean.value(t)), twist = twist.value(t), nod = -6f)
    }
}

/**
 * Handball standing throw: step forward with the far foot while the ball is cocked behind the
 * head, then the hips and trunk turn and the arm whips the ball forward over the shoulder.
 */
private fun handballThrow(): Motion {
    val release = Rig().apply { Throw.trunk(this, Throw.RELEASE) }.shoulder(Side.NEAR) + Throw.hand.at(Throw.RELEASE)
    return Motion.cycle(Throw.LOOP, camera = Camera(30f)) { phase -> throwFrame(phase * Throw.LOOP, release) }
}

private fun FrameBuilder.throwFrame(t: Float, release: V3) {
    Throw.trunk(body, t)
    body.stand(Side.NEAR, -0.15f, z = 0.13f, lift = Throw.heel.value(t), out = 20f)
    body.stand(Side.FAR, Throw.step.value(t), z = -0.13f, floor = Throw.stepUp.value(t), out = 6f)
    val hand = body.shoulder(Side.NEAR) + Throw.hand.at(t)
    body.arm(Side.NEAR, hand, v(-0.3f, -1f, 0.6f))
    body.swingArm(Side.FAR, Throw.guide.value(t), out = 22f, elbow = 35f)
    when {
        t < Throw.RELEASE -> {
            val arm = body.armOf(Side.NEAR)
            gameBall(hand + (hand - arm.elbow).unit() * 0.08f, 0.095f)
        }
        t < 1.3f -> gameBall(release + v(4f, 0.4f) * (t - Throw.RELEASE), 0.095f, fit = false)
    }
}

// ---- Racket sports ------------------------------------------------------------------------------

/** Racket strokes: a tennis forehand, a compact padel forehand with its solid racket, a badminton overhead clear. */
private enum class Racket { TENNIS, PADEL, BADMINTON }

/** The keyed channels of a racket stroke over its loop. */
private class Stroke(val loop: Float, times: List<Float>, hands: List<V3>, dirs: List<V3>, val twist: Track, val hips: Track, val guide: Track) {
    val hand = PointTrack(times, hands)
    val racket = PointTrack(times, dirs)
}

private fun stroke(kind: Racket): Stroke = when (kind) {
    // The take-back goes out to the side (never across the shoulder), then behind.
    Racket.TENNIS -> Stroke(
        loop = 2.2f,
        times = listOf(0f, 0.2f, 0.38f, 0.55f, 0.85f, 1.1f, 1.3f, 1.9f, 2.2f),
        hands = listOf(v(0.32f, -0.3f, -0.1f), v(0.32f, -0.3f, -0.1f), v(0.1f, -0.28f, 0.42f), v(-0.3f, -0.15f, 0.32f), v(0.35f, -0.38f, 0.22f), v(0.05f, 0.05f, -0.45f), v(0.05f, 0.05f, -0.45f), v(0.32f, -0.3f, -0.1f), v(0.32f, -0.3f, -0.1f)),
        dirs = listOf(v(0.3f, 0.6f, -0.5f), v(0.3f, 0.6f, -0.5f), v(0f, 0.7f, 0.5f), v(-0.3f, 0.75f, 0.2f), v(0.15f, 0.05f, 0.98f), v(-0.4f, 0.75f, -0.3f), v(-0.4f, 0.75f, -0.3f), v(0.3f, 0.6f, -0.5f), v(0.3f, 0.6f, -0.5f)),
        twist = Track.keys(0f to 0f, 0.2f to 0f, 0.55f to 30f, 0.85f to -10f, 1.1f to -35f, 1.3f to -35f, 1.9f to 0f, 2.2f to 0f),
        hips = Track.keys(0f to 0f, 0.2f to 0f, 0.55f to 25f, 0.85f to -12f, 1.1f to -20f, 1.3f to -20f, 1.9f to 0f, 2.2f to 0f),
        guide = Track.keys(0f to 30f, 0.2f to 30f, 0.55f to 80f, 0.85f to 40f, 1.1f to 25f, 1.9f to 30f, 2.2f to 30f),
    )
    Racket.PADEL -> Stroke(
        loop = 2.0f,
        times = listOf(0f, 0.2f, 0.35f, 0.5f, 0.75f, 0.95f, 1.15f, 1.7f, 2.0f),
        hands = listOf(v(0.3f, -0.3f, -0.08f), v(0.3f, -0.3f, -0.08f), v(0.12f, -0.28f, 0.4f), v(-0.15f, -0.2f, 0.32f), v(0.32f, -0.42f, 0.2f), v(0.28f, -0.15f, -0.3f), v(0.28f, -0.15f, -0.3f), v(0.3f, -0.3f, -0.08f), v(0.3f, -0.3f, -0.08f)),
        dirs = listOf(v(0.3f, 0.7f, -0.4f), v(0.3f, 0.7f, -0.4f), v(0f, 0.75f, 0.45f), v(-0.2f, 0.85f, 0.3f), v(0.2f, 0.2f, 0.95f), v(0.1f, 0.7f, -0.6f), v(0.1f, 0.7f, -0.6f), v(0.3f, 0.7f, -0.4f), v(0.3f, 0.7f, -0.4f)),
        twist = Track.keys(0f to 0f, 0.2f to 0f, 0.5f to 22f, 0.75f to -5f, 0.95f to -20f, 1.15f to -20f, 1.7f to 0f, 2.0f to 0f),
        hips = Track.keys(0f to 0f, 0.2f to 0f, 0.5f to 18f, 0.75f to -6f, 0.95f to -12f, 1.15f to -12f, 1.7f to 0f, 2.0f to 0f),
        guide = Track.keys(0f to 30f, 0.2f to 30f, 0.5f to 70f, 0.75f to 40f, 0.95f to 30f, 2.0f to 30f),
    )
    Racket.BADMINTON -> Stroke(
        loop = 2.2f,
        // Up by the side of the head to behind it, so the elbow stays high and out.
        times = listOf(0f, 0.2f, 0.4f, 0.6f, 0.75f, 0.85f, 1.1f, 1.3f, 1.9f, 2.2f),
        hands = listOf(v(0.3f, -0.2f, 0f), v(0.3f, -0.2f, 0f), v(0.1f, 0.05f, 0.42f), v(-0.18f, 0.3f, 0.2f), v(-0.04f, 0.47f, 0.12f), v(0.18f, 0.54f, 0f), v(0.25f, -0.4f, -0.25f), v(0.25f, -0.4f, -0.25f), v(0.3f, -0.2f, 0f), v(0.3f, -0.2f, 0f)),
        dirs = listOf(v(0.4f, 0.8f, 0f), v(0.4f, 0.8f, 0f), v(0f, 0.8f, 0.5f), v(-0.3f, -0.7f, 0.3f), v(-0.5f, 0.3f, 0.8f), v(0.3f, 0.95f, 0f), v(0.5f, -0.6f, -0.4f), v(0.5f, -0.6f, -0.4f), v(0.4f, 0.8f, 0f), v(0.4f, 0.8f, 0f)),
        twist = Track.keys(0f to 0f, 0.2f to 0f, 0.6f to 35f, 0.85f to 0f, 1.1f to -30f, 1.3f to -30f, 1.9f to 0f, 2.2f to 0f),
        hips = Track.keys(0f to 0f, 0.2f to 0f, 0.6f to 25f, 0.85f to 0f, 1.1f to -18f, 1.3f to -18f, 1.9f to 0f, 2.2f to 0f),
        guide = Track.keys(0f to 30f, 0.2f to 30f, 0.6f to 120f, 0.85f to 90f, 1.1f to 20f, 1.9f to 30f, 2.2f to 30f),
    )
}

/**
 * A racket stroke from the ready position: shoulders and hips turn back with the racket, then
 * unwind through the contact in front of the body to the follow-through, and back to ready.
 */
private fun racketStroke(kind: Racket): Motion {
    val s = stroke(kind)
    val overhead = kind == Racket.BADMINTON
    return Motion.cycle(s.loop, camera = Camera(if (overhead) 40f else 56f, 6f)) { phase ->
        val t = phase * s.loop
        body.torso(v(-0.02f, 0.84f + 0.01f * cos(phase * TAU)), Basis.UPRIGHT.pitch(if (overhead) 4f else 14f).turn(s.hips.value(t)), twist = s.twist.value(t), nod = -8f)
        body.stand(Side.NEAR, -0.14f, z = 0.2f, out = 25f)
        body.stand(Side.FAR, 0.16f, z = -0.18f, out = 15f)
        val hand = body.shoulder(Side.NEAR) + s.hand.at(t)
        body.arm(Side.NEAR, hand, if (overhead) v(0.1f, -0.2f, 1f) else v(-0.2f, -1f, 0.6f))
        body.swingArm(Side.FAR, s.guide.value(t), out = 20f, elbow = 30f)
        val dir = s.racket.at(t).unit()
        val face = orthogonal(V3.X, dir)
        when (kind) {
            Racket.TENNIS -> racket(hand, dir, face, handle = 0.27f, head = 0.13f, thick = 0.02f)
            Racket.PADEL -> racket(hand, dir, face, handle = 0.15f, head = 0.15f, thick = 0.035f)
            Racket.BADMINTON -> racket(hand, dir, face, handle = 0.36f, head = 0.1f, thick = 0.012f)
        }
    }
}

/** A racket held at [hand]: the handle along [dir], the head a disc facing [face]. */
private fun FrameBuilder.racket(hand: V3, dir: V3, face: V3, handle: Float, head: Float, thick: Float) {
    val neck = hand + dir * handle
    add(Rod(hand - dir * 0.05f, neck, 0.014f, Ink.METAL, bias = 0.05f))
    add(Disc(neck + dir * head, face, head, thick, Ink.PAD, hub = head * 0.55f, bias = 0.05f))
}

// ---- Mind and body, dance -----------------------------------------------------------------------

/** A yoga mat under a figure standing or lying on it, from [x0] to [x1]. */
private fun FrameBuilder.yogaMat(x0: Float, x1: Float) {
    box(v(x0, 0f, -0.32f), v(x1, 0.012f, 0.32f), Ink.PAD, fit = false, bias = -0.8f)
}

/**
 * A yoga flow on the mat: standing tall, the arms rise overhead on an inhale, the trunk folds
 * forward with them until the hands reach the floor, holds, rolls back up with the arms overhead
 * and lowers them.
 */
private fun yogaFlow(): Motion {
    val arms = Track.keys(0f to 0f, 0.6f to 0f, 1.6f to 1f, 4.9f to 1f, 5.6f to 0f, 6f to 0f)
    val fold = Track.keys(0f to 0f, 1.6f to 0f, 2.9f to 1f, 3.6f to 1f, 4.9f to 0f, 6f to 0f)
    return Motion.cycle(6f, camera = Camera(30f)) { phase ->
        val t = phase * 6f
        val f = fold.value(t)
        val px = -0.16f * f
        val legReach = 0.845f - 0.03f * f
        body.torso(v(px, BodySize.ANKLE + sqrt(legReach * legReach - px * px)), Basis.UPRIGHT.pitch(98f * f), bend = 22f * f, nod = lerp(-4f, 14f, f))
        body.standOn(0f, width = 0.2f, out = 4f, pole = v(1f, 0.15f, 0.05f))
        // The arms rise forward to overhead, then go down with the trunk towards the floor.
        val angle = lerp(lerp(-88f, 92f, arms.value(t)), -76f, f)
        for (side in Side.entries) body.armAngles(side, (dirXY(angle) + V3.Z * (side.sign * 0.12f)).unit(), 6f, fold = body.chest.f)
        yogaMat(-0.55f, 0.65f)
    }
}

/**
 * Pilates roll-up: from lying with the arms overhead, the head and chest curl up first, then the
 * lower back, until sitting rounded over the legs with the arms reaching past the toes; then it
 * rolls back down one vertebra at a time.
 */
private fun rollUp() = Motion.rep(Track.rep(go = 2.4f, hold = 0.5f, back = 2.4f, rest = 0.6f), Camera(-24f)) { p ->
    val curl = sin(PI.toFloat() * p)
    body.torso(v(0f, lerp(0.115f, 0.125f, p)), Basis.SUPINE.pitch(105f * Ease.IN_OUT.apply(p)), bend = 40f * curl + 22f * p, nod = 10f + 15f * curl)
    for (side in Side.entries) {
        val toes = dirXY(85f)
        body.leg(side, v(0.855f, 0.085f, side.sign * 0.1f), v(0f, 1f, 0f), Foot(toes, rotate(toes, V3.Z, rad(90f))))
    }
    val angle = lerp(172f, -8f, Ease.IN_OUT.apply(p))
    for (side in Side.entries) body.armAngles(side, (dirXY(angle) + V3.Z * (side.sign * 0.1f)).unit(), 4f, fold = body.chest.f)
    yogaMat(-1.1f, 1.0f)
}

/** The step-touch channels over its 2 s loop: each foot's lateral position, its rise while it moves and its heel lift while it touches. */
private object StepTouch {
    const val LOOP = 2f
    val nearZ = Track.keys(0f to -0.06f, 0.5f to 0.24f, 1.5f to 0.24f, LOOP to -0.06f)
    val farZ = Track.keys(0f to -0.24f, 0.5f to -0.24f, 1f to 0.06f, 1.5f to -0.24f, LOOP to -0.24f)
    val nearUp = Track.keys(0f to 0f, 0.25f to 0.07f, 0.5f to 0f, 1.5f to 0f, 1.75f to 0.07f, LOOP to 0f)
    val farUp = Track.keys(0f to 0f, 0.5f to 0f, 0.75f to 0.07f, 1f to 0f, 1.25f to 0.07f, 1.5f to 0f, LOOP to 0f)
    val nearHeel = Track.keys(0f to 30f, 0.5f to 0f, 1.5f to 0f, LOOP to 30f)
    val farHeel = Track.keys(0f to 0f, 0.5f to 0f, 1f to 30f, 1.5f to 0f, LOOP to 0f)
}

/** A dance step-touch seen from the front: step to one side, close the other foot, the hips sway and the arms swing up with the weight. */
private fun stepTouch() = Motion.cycle(StepTouch.LOOP, camera = Camera(72f, 4f)) { phase ->
    val t = phase * StepTouch.LOOP
    val nearZ = StepTouch.nearZ.value(t)
    val farZ = StepTouch.farZ.value(t)
    val center = (nearZ + farZ) * 0.4f
    val dip = sin(phase * TAU * 2)
    body.torso(v(0f, 0.87f - 0.03f * dip * dip, center), sideways = -40f * center, twist = 8f * sin(phase * TAU), nod = -4f)
    body.stand(Side.NEAR, 0f, z = nearZ, floor = StepTouch.nearUp.value(t), lift = StepTouch.nearHeel.value(t), out = 10f)
    body.stand(Side.FAR, 0f, z = farZ, floor = StepTouch.farUp.value(t), lift = StepTouch.farHeel.value(t), out = 10f)
    // The arm on the side the weight is on swings up.
    val weight = (center / 0.12f).coerceIn(-1f, 1f) * 0.5f + 0.5f
    body.swingArm(Side.NEAR, 30f + 80f * weight, out = 35f, elbow = 80f)
    body.swingArm(Side.FAR, 30f + 80f * (1 - weight), out = 35f, elbow = 80f)
}

// ---- Combat sports ------------------------------------------------------------------------------

/** The jab-cross channels over the 2 s loop: how far each punch is out (0 guard, 1 extended), and the trunk's turn. */
private object JabCross {
    const val LOOP = 2f
    val jab = Track(listOf(Key(0f, 0f), Key(0.25f, 0f), Key(0.42f, 1f, Ease.OUT), Key(0.6f, 0f), Key(LOOP, 0f)))
    val cross = Track(listOf(Key(0f, 0f), Key(0.75f, 0f), Key(0.95f, 1f, Ease.OUT), Key(1.2f, 0f), Key(LOOP, 0f)))
    val twist = Track.keys(0f to 0f, 0.25f to 0f, 0.42f to 8f, 0.6f to 0f, 0.75f to 0f, 0.95f to -38f, 1.2f to 0f, LOOP to 0f)
}

/**
 * Boxing jab and cross from an orthodox guard: the lead (far) fist shoots straight out and back,
 * then the rear fist follows as the hips and the rear foot turn.
 */
private fun jabCross() = Motion.cycle(JabCross.LOOP, camera = Camera(34f)) { phase ->
    val t = phase * JabCross.LOOP
    val jab = JabCross.jab.value(t)
    val cross = JabCross.cross.value(t)
    body.torso(v(-0.02f, 0.86f + 0.012f * sin(phase * TAU * 2)), Basis.UPRIGHT.pitch(8f).turn(28f - 12f * cross), twist = JabCross.twist.value(t), nod = 6f)
    body.stand(Side.FAR, 0.22f, z = -0.1f, out = 20f)
    body.stand(Side.NEAR, -0.22f, z = 0.16f, lift = 28f * cross, out = 35f)
    val leadGuard = body.onChest(0.24f, 0.12f, -0.09f)
    val rearGuard = body.onChest(0.2f, 0.13f, 0.1f)
    val leadHand = lerp(leadGuard, body.shoulder(Side.FAR) + v(0.54f, 0.02f, 0.1f), jab)
    val rearHand = lerp(rearGuard, body.shoulder(Side.NEAR) + v(0.55f, 0.02f, -0.14f), cross)
    body.arm(Side.FAR, leadHand, v(-0.2f, -1f, -0.25f))
    body.arm(Side.NEAR, rearHand, v(-0.2f, -1f, 0.25f))
    for (side in Side.entries) add(Ball(body.armOf(side).hand + V3.X * 0.015f, 0.072f, Ink.BAND, bias = 0.03f))
}

/** The front-kick channel over the 2.2 s loop: 0 standing in guard, 1 knee chambered, 2 leg extended. */
private val KICK = Track.keys(0f to 0f, 0.35f to 0f, 0.62f to 1f, 0.88f to 2f, 1.1f to 1f, 1.45f to 0f, 2.2f to 0f)

/**
 * Martial-arts front kick: from the guard the rear (near) knee chambers up, the leg snaps out at
 * waist height with the ball of the foot, chambers again and steps back down.
 */
private fun frontKick() = Motion.cycle(2.2f, camera = Camera(26f)) { phase ->
    val k = KICK.value(phase * 2.2f)
    val out = (k - 1f).coerceAtLeast(0f)
    body.torso(v(-0.02f, 0.86f - 0.02f * min(k, 1f)), Basis.UPRIGHT.pitch(6f - 16f * out), nod = 4f + 10f * out)
    body.stand(Side.FAR, 0.08f, z = -0.12f, out = 25f, pole = v(1f, 0.1f, -0.1f))
    val planted = v(-0.28f, BodySize.ANKLE, 0.12f)
    val chamber = v(0.22f, 0.5f, 0.08f)
    val extended = v(0.78f, 1.0f, 0.05f)
    val ankle = if (k <= 1f) lerp(planted, chamber, k) + V3.Y * (0.08f * sin(PI.toFloat() * k)) else lerp(chamber, extended, out)
    // The foot turns from flat on the floor to square to the shin (toes pulled back) as it leaves it.
    val pole = v(1f, 0.6f, 0.1f)
    val ik = twoBone(body.hip(Side.NEAR), ankle, BodySize.THIGH, BodySize.SHIN, pole)
    val shin = (ik.end - ik.mid).unit()
    val square = rotate(shin, V3.Z, rad(80f)).withZ(0f).unit()
    val w = Ease.IN_OUT.apply(((k - 0.1f) / 0.6f).coerceIn(0f, 1f))
    val dir = lerp(V3.X, square, w).unit()
    val foot = Foot(dir, rotate(dir, V3.Z, rad(90f)))
    body.leg(Side.NEAR, footAbove(ankle, foot), pole, foot)
    body.arm(Side.FAR, body.onChest(0.28f, 0.1f, -0.1f), v(-0.2f, -1f, -0.3f))
    body.arm(Side.NEAR, body.onChest(0.18f, 0.12f, 0.1f), v(-0.2f, -1f, 0.3f))
}

// ---- Others -------------------------------------------------------------------------------------

/**
 * Climbing a wall, seen from behind one side: each hand holds while the wall moves down, then
 * reaches up to the next hold; the feet step up the same way, diagonally to the hands.
 */
private fun climbing() = Motion.cycle(3.2f, camera = Camera(-28f, 8f)) { phase ->
    val wall = 0.42f
    val drop = 0.5f
    val stance = 0.7f
    /** Height (and distance off the wall while moving) of a limb at [p] that holds from [top] while the wall drops. */
    fun limb(p: Float, top: Float): Pair<Float, Float> =
        if (p < stance) {
            (top - drop * p) to 0f
        } else {
            val s = (p - stance) / (1 - stance)
            lerp(top - drop * stance, top, Ease.IN_OUT.apply(s)) to sin(PI.toFloat() * s)
        }
    body.torso(v(0.08f, 1.0f + 0.025f * sin(phase * TAU * 2)), Basis.UPRIGHT.pitch(8f), nod = -22f)
    for (side in Side.entries) {
        val handPhase = (phase + if (side == Side.NEAR) 0f else 0.5f) % 1f
        val footPhase = (phase + if (side == Side.NEAR) 0.65f else 0.15f) % 1f
        val (hy, hOff) = limb(handPhase, 1.88f)
        body.arm(side, v(wall - 0.06f - 0.1f * hOff, hy, side.sign * 0.2f), v(0f, -1f, side.sign * 0.6f))
        val (fy, fOff) = limb(footPhase, 0.72f)
        body.leg(side, v(wall - 0.19f - 0.08f * fOff, fy + BodySize.ANKLE, side.sign * 0.14f), v(0.3f, 0.2f, side.sign * 1f), Foot.flat(side, 4f))
        // This limb's holds, one per loop, moving down with the wall.
        for ((offset, top, z) in listOf(Triple(handPhase, 1.88f, 0.2f), Triple(footPhase, 0.72f - 0.02f, 0.14f))) {
            var y = top - drop * offset - drop * 3
            while (y < 2.6f) {
                if (y > 0.05f) add(Ball(v(wall - 0.01f, y, side.sign * z), 0.035f, Ink.BAND, fit = false, bias = -1f))
                y += drop
            }
        }
    }
    box(v(wall, 0f, -0.9f), v(wall + 0.12f, 2.6f, 0.9f), Ink.FRAME, fit = false, bias = BEHIND)
}

/** The golf swing channel over the 4.2 s loop: the hands' angle around the shoulders (+ backswing, − follow-through). */
private val SWING = Track(
    listOf(
        Key(0f, 0f), Key(0.5f, 0f), Key(1.6f, 150f), Key(1.75f, 150f), Key(2.15f, 0f, Ease.IN), Key(2.55f, -155f, Ease.OUT),
        Key(3.1f, -155f), Key(3.8f, 0f), Key(4.2f, 0f),
    ),
)

/**
 * Golf swing seen from the front: from the address, the shoulders turn and the wrists hinge to the
 * top of the backswing, the hips lead the downswing through the ball to a tall, balanced finish;
 * then back to the address with a new ball.
 */
private fun golfSwing() = Motion.cycle(4.2f, camera = Camera(80f, 6f)) { phase ->
    val t = phase * 4.2f
    val angle = SWING.value(t)
    val finish = (-angle / 155f).coerceAtLeast(0f)
    body.torso(v(-0.12f, 0.88f), Basis.UPRIGHT.pitch(35f - 18f * finish).turn(0.2f * angle), twist = 0.45f * angle, nod = 25f - 15f * finish)
    body.stand(Side.NEAR, 0f, z = 0.22f, lift = 40f * finish, out = 12f, pole = v(1f, 0.1f, 0.25f))
    body.stand(Side.FAR, 0f, z = -0.22f, out = 18f, pole = v(1f, 0.1f, -0.25f))
    // The hands circle the shoulders in the swing plane; the club hinges at the wrists.
    val down = v(0.25f, -0.97f).unit()
    val normal = (V3.Z cross down).unit()
    val a = rad(angle)
    val radial = down * cos(a) + V3.Z * sin(a)
    val tangent = down * (-sin(a)) + V3.Z * cos(a)
    val grip = body.shoulders() + radial * 0.4f
    val hinge = rad(90f * (abs(angle) / 120f).coerceAtMost(1f).let { it * sqrt(it) }) * if (angle < 0f) -1f else 1f
    val club = (radial * cos(hinge) + tangent * sin(hinge) + normal * 0.4f).unit()
    body.arm(Side.FAR, grip, v(0f, -1f, -0.3f))
    body.arm(Side.NEAR, grip + club * 0.075f, v(0f, -1f, 0.3f))
    // The club may leave the frame at the top and the finish: the view fits the golfer.
    val head = grip + club * 1.02f
    add(Rod(grip - club * 0.04f, head, 0.011f, Ink.METAL, fit = false, bias = 0.04f))
    add(Rod(head, head + normal * 0.08f, 0.022f, Ink.METAL, fit = false, bias = 0.04f))
    if (t < 2.15f || t > 3.7f) add(Ball(v(0.87f, 0.021f), 0.021f, Ink.CABLE, bias = 0.02f))
}
