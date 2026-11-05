package com.enjoyyourtime.globalfitness.ui.training.motion

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

// Walking on treadmills and the conditioning and HIIT drills added in 1.6: steady cycles (walks,
// runs, ropes, jacks, running on the spot, crawls, sleds) and keyed jumps.

private const val TAU = 2 * PI.toFloat()

// ---- Shared pieces --------------------------------------------------------------------------------

/**
 * Where a foot touching a surface at [x] stands, the surface's height being [surface] and its slope
 * [slope] degrees (rising forward): the ankle and the foot's world angle (degrees from +X) when the
 * foot is turned [angle] degrees from lying flat on it (positive: heel up, pivoting on the toes;
 * negative: toes up on the heel). On a flat floor it is the step of [gaitLeg].
 */
private fun footOn(x: Float, angle: Float, surface: (Float) -> Float, slope: (Float) -> Float): Pair<V3, Float> {
    val local = if (angle >= 0f) liftedAnkle(x, 0f, angle) else v(x, BodySize.ANKLE)
    val s = rad(slope(x))
    val dx = local.x - x
    val dy = local.y
    val ankle = v(x + dx * cos(s) - dy * sin(s), surface(x) + dx * sin(s) + dy * cos(s))
    return ankle to slope(x) - angle
}

/** A foot pointing [angle] degrees from +X in the X-Y plane, its sole facing down along [angle] − 90°. */
private fun footAt(angle: Float): Foot = Foot(dirXY(angle), dirXY(angle + 90f))

/**
 * A walking or running step like [gaitLeg] on a sloped or curved surface ([footOn]): stance from
 * [center] + [reach] back to [center] − [reach], heel to toe, then the swing forward lifted [lift].
 */
private fun FrameBuilder.surfaceGaitLeg(
    side: Side,
    phase: Float,
    reach: Float,
    lift: Float,
    stance: Float,
    surface: (Float) -> Float,
    slope: (Float) -> Float,
    center: Float = 0f,
    heelKick: Float = 0f,
) {
    val z = side.sign * 0.1f
    val (ankle, angle) = if (phase < stance) {
        val s = phase / stance
        val roll = when {
            s < 0.15f -> lerp(-10f, 0f, s / 0.15f)
            s > 0.45f -> lerp(0f, 32f, (s - 0.45f) / 0.55f)
            else -> 0f
        }
        footOn(center + lerp(reach, -reach, s), roll, surface, slope)
    } else {
        val s = (phase - stance) / (1 - stance)
        val (from, a0) = footOn(center - reach, 32f, surface, slope)
        val (to, a1) = footOn(center + reach, -10f, surface, slope)
        val arc = sin(PI.toFloat() * s)
        val at = lerp(from, to, Ease.IN_OUT.apply(s)) + V3.Y * (lift * arc + heelKick * arc * arc * (1 - s)) - V3.X * (heelKick * 0.4f * arc)
        at to lerp(a0, a1, s)
    }
    body.leg(side, ankle.withZ(z), v(1f, 0.1f, 0f), footAt(angle))
}

/**
 * Marks on a belt or the ground moving back as the feet in stance do: [travel] metres per [phase]
 * turn, spaced so the loop shows no jump, along the surface [surface] at depth [z].
 */
private fun FrameBuilder.beltMarks(phase: Float, travel: Float, x0: Float, x1: Float, surface: (Float) -> Float, z: Float, ink: Ink = Ink.PLATE_DARK) {
    val n = max(1, (travel / 0.4f).roundToInt())
    val spacing = travel / n
    var x = x1 - (phase * travel) % spacing
    while (x > x0 + 0.08f) {
        add(Rod(v(x - 0.08f, surface(x - 0.08f) - 0.03f, z), v(x, surface(x) - 0.03f, z), 0.008f, ink, fit = false, bias = -0.3f))
        x -= spacing
    }
}

/** Arms swinging against the legs as in a walk: [swing] −1..1 (forward when positive). */
private fun Rig.walkArm(side: Side, swing: Float, amplitude: Float = 24f, elbow: Float = 16f) {
    swingArm(side, amplitude * swing, out = 7f, elbow = elbow)
}

/** A foot on its toes with the ankle above [x] (toes at [x] + toe length) on a floor at [floor], heel raised [lift] degrees. */
private fun Rig.onBall(side: Side, x: Float, z: Float, floor: Float = 0f, lift: Float = 20f, pole: V3 = v(1f, 0.15f, 0f)) {
    leg(side, liftedAnkle(x, floor, lift).withZ(z), pole, footAt(-lift))
}

/**
 * A foot whose toes point along the shin of a leg reaching from [hip] to [ankle] (knee towards
 * [pole]), turned [short] degrees towards the front of the shin: a pointed foot in the air.
 */
private fun pointedFoot(hip: V3, ankle: V3, pole: V3, short: Float = 28f): Foot {
    val ik = twoBone(hip, ankle, BodySize.THIGH, BodySize.SHIN, pole)
    val shin = (ik.end - ik.mid).unit()
    val front = orthogonal(ik.apex, shin)
    val dir = (shin * cos(rad(short)) + front * sin(rad(short))).unit()
    return Foot(dir, orthogonal(front, dir))
}

/** Blends two foot orientations ([w] 0: [a], 1: [b]). */
private fun blendFoot(a: Foot, b: Foot, w: Float): Foot {
    val dir = lerp(a.dir, b.dir, w).unit()
    return Foot(dir, orthogonal(lerp(a.up, b.up, w), dir))
}

/** A value eased between keys (time to value, times increasing), held before the first and after the last. */
internal fun ramp(t: Float, vararg keys: Pair<Float, Float>): Float {
    if (t <= keys.first().first) return keys.first().second
    for (i in 1 until keys.size) {
        val (t1, v1) = keys[i]
        if (t <= t1) {
            val (t0, v0) = keys[i - 1]
            return lerp(v0, v1, Ease.IN_OUT.apply((t - t0) / (t1 - t0)))
        }
    }
    return keys.last().second
}

// ---- Treadmills -----------------------------------------------------------------------------------

/**
 * Walking on a treadmill, heel to toe with the arms swinging; the belt's marks move back at the
 * feet's speed. [incline]: the deck rises 10° to the front and the walker leans into it.
 */
internal fun treadmillWalk(incline: Boolean) = Motion.cycle(2.2f, cycles = 2, camera = Camera(24f)) { phase ->
    val tilt = if (incline) 10f else 0f
    val rise = tan(rad(tilt))
    val deck = if (incline) 0.27f else 0.18f
    val surface = { x: Float -> deck + x * rise }
    val slope = { _: Float -> tilt }
    val reach = 0.25f
    val stance = 0.6f
    body.torso(v(0f, deck + 0.875f + 0.012f * cos(phase * TAU * 2)), Basis.UPRIGHT.pitch(3f + tilt * 0.7f), nod = -3f - tilt * 0.4f)
    for (side in Side.entries) {
        surfaceGaitLeg(side, (phase + if (side == Side.NEAR) 0f else 0.5f) % 1f, reach, lift = 0.07f, stance = stance, surface = surface, slope = slope)
        body.walkArm(side, -body.legOf(side).ankle.x / reach)
    }
    // The deck (tilted as a block) with its belt marks, the upright and the console.
    val along = dirXY(tilt)
    val normal = dirXY(tilt + 90f)
    val mid = v(-0.03f, surface(-0.03f)) - normal * 0.06f
    add(Block(mid, Basis(along, normal, V3.Z), v(0.85f, 0.06f, 0.36f), Ink.STACK, fit = false, bias = -0.4f))
    beltMarks(phase, 2 * reach / stance, -0.85f, 0.8f, surface, 0.37f)
    val front = v(0.75f, surface(0.75f))
    add(Rod(front, v(0.88f, front.y + 0.95f), 0.03f, Ink.FRAME, fit = false, bias = -0.35f))
    add(Block(v(0.88f, front.y + 1.0f), Basis.UPRIGHT.pitch(-30f), v(0.04f, 0.1f, 0.28f), Ink.STACK, fit = false, bias = -0.35f))
    if (incline) add(Block(v(0.62f, (surface(0.62f) - 0.06f) / 2), Basis.UPRIGHT, v(0.12f, (surface(0.62f) - 0.06f) / 2, 0.3f), Ink.FRAME, fit = false, bias = -0.45f))
}

/**
 * Running on a curved, self-powered treadmill: the feet land on the front of the curve and push the
 * belt back, leaning a little more than on a motorised one; the slats move with the feet.
 */
internal fun curvedTreadmill() = Motion.cycle(1.44f, cycles = 2, camera = Camera(22f)) { phase ->
    val base = 0.2f
    val curve = 0.42f
    val surface = { x: Float -> base + curve * x * x }
    val slope = { x: Float -> deg(atan(2 * curve * x)) }
    val reach = 0.28f
    val stance = 0.38f
    val center = 0.06f
    body.torso(v(-0.02f, base + 0.86f + 0.025f * cos(phase * TAU * 2)), Basis.UPRIGHT.pitch(12f), nod = -9f)
    for (side in Side.entries) {
        val legPhase = (phase + if (side == Side.NEAR) 0f else 0.5f) % 1f
        surfaceGaitLeg(side, legPhase, reach, lift = 0.1f, stance = stance, surface = surface, slope = slope, center = center, heelKick = 0.22f)
        val swing = 34f * sin((legPhase + 0.5f) * TAU + 0.6f)
        body.armAngles(side, limbDir(body.chest, side, swing, 6f), 92f, fold = body.chest.f + body.chest.u * 0.2f)
    }
    // The curved belt, its slats, the frame and the front rail.
    add(Cord(List(25) { i -> val x = -0.85f + 1.7f * i / 24; v(x, surface(x) - 0.03f, 0.3f) }, 0.05f, Ink.STACK, fit = false, bias = -0.4f))
    add(Cord(List(25) { i -> val x = -0.85f + 1.7f * i / 24; v(x, surface(x) - 0.03f, -0.3f) }, 0.05f, Ink.STACK, fit = false, bias = -0.45f))
    beltMarks(phase, 2 * reach / stance, -0.85f, 0.85f, surface, 0.33f)
    add(Rod(v(-0.95f, 0.06f), v(0.95f, 0.06f), 0.04f, Ink.FRAME, fit = false, bias = -0.5f))
    for (x in listOf(-0.9f, 0.9f)) add(Rod(v(x, 0.06f), v(x, surface(x) - 0.04f), 0.035f, Ink.FRAME, fit = false, bias = -0.5f))
    add(Rod(v(0.9f, surface(0.9f)), v(0.95f, 1.2f), 0.03f, Ink.FRAME, fit = false, bias = -0.35f))
    add(Rod(v(0.95f, 1.2f, -0.3f), v(0.95f, 1.2f, 0.3f), 0.025f, Ink.METAL, fit = false, bias = -0.35f))
}

// ---- Rope, jacks, running on the spot ---------------------------------------------------------------

/**
 * Jump rope: small bounces on the balls of the feet, the wrists turning the rope over the head and
 * under the feet once per jump, at the top of each bounce. [double]: double-unders, a higher jump
 * with the rope passing under the feet twice.
 */
internal fun jumpRope(double: Boolean = false) = Motion.cycle(if (double) 1.3f else 1.0f, cycles = 2, camera = Camera(20f, 4f)) { phase ->
    val hop = 0.5f - 0.5f * cos(phase * TAU)
    val h = (if (double) 0.11f else 0.05f) * hop
    val lift = 25f + 8f * hop
    val ankleY = liftedAnkle(0f, h, lift).y
    body.torso(v(0f, ankleY + 0.79f), Basis.UPRIGHT.pitch(4f), nod = -4f)
    for (side in Side.entries) body.onBall(side, 0f, side.sign * 0.1f, floor = h, lift = lift)
    // The rope turns forward: overhead at the start of the jump, under the feet at its top.
    val turn = rad(90f - (if (double) 720f else 360f) * phase)
    val handY = ankleY + 0.85f
    val hands = Side.entries.associateWith { side -> v(0.12f + 0.03f * cos(turn), handY + 0.03f * sin(turn), side.sign * 0.27f) }
    for (side in Side.entries) body.arm(side, hands.getValue(side), v(-1f, -0.3f, side.sign * 0.4f))
    val near = body.handOf(Side.NEAR)
    val far = body.handOf(Side.FAR)
    val mid = (near + far) * 0.5f
    val center = v(0.1f, handY - 0.13f)
    val loop = center + v(0.42f * cos(turn), 0.9f * sin(turn))
    val rope = List(17) { i ->
        val u = i / 16f
        val w = sin(PI.toFloat() * u).coerceAtLeast(0f).let { sqrt(sqrt(it)) }
        val p = lerp(far, near, u) + (loop - mid).withZ(0f) * w
        if (p.y < 0.008f) p.withY(0.008f) else p
    }
    add(Cord(rope, 0.012f, Ink.CABLE))
    for (side in Side.entries) {
        val hand = hands.getValue(side)
        add(Rod(hand - V3.Y * 0.05f, hand + V3.Y * 0.06f, 0.02f, Ink.BAND, bias = 0.02f))
    }
}

/** Jumping jacks, seen from the front: the feet jump out and in while the arms sweep out to overhead and back down. */
internal fun jumpingJack() = Motion.cycle(1.4f, cycles = 2, camera = Camera(80f, 4f)) { phase ->
    val open = 0.5f - 0.5f * cos(phase * TAU)
    val h = 0.05f * abs(sin(phase * TAU))
    val lift = 15f + 15f * (h / 0.05f)
    val feet = lerp(0.1f, 0.36f, open)
    val ankleY = liftedAnkle(0f, h, lift).y
    val dz = feet - BodySize.HIP_HALF
    body.torso(v(-0.02f, ankleY + sqrt(0.82f * 0.82f - dz * dz)), nod = -2f)
    for (side in Side.entries) {
        body.onBall(side, 0f, side.sign * feet, floor = h, lift = lift, pole = v(1f, 0.1f, side.sign * 0.3f))
        body.armAngles(side, limbDir(body.chest, side, lerp(6f, -8f, open), lerp(10f, 168f, open)), 12f, fold = body.chest.f)
    }
}

/**
 * Running on the spot: the stance foot on its ball under the hip, the other knee driven up to hip
 * height ([high], high knees) or the heel kicked up to the glutes (butt kicks); the arms swing as in a run.
 */
internal fun runOnTheSpot(high: Boolean) = Motion.cycle(if (high) 1.3f else 2.2f, cycles = 2, camera = Camera(26f)) { phase ->
    val pelvisY = (if (high) 0.87f else 0.885f) + 0.015f * cos(phase * TAU * 2)
    body.torso(v(-0.01f, pelvisY), Basis.UPRIGHT.pitch(if (high) 2f else 5f), nod = -4f)
    for (side in Side.entries) {
        val local = (phase + if (side == Side.NEAR) 0f else 0.5f) % 1f
        val z = side.sign * 0.1f
        val floorAnkle = liftedAnkle(0.02f, 0f, 20f).withZ(z)
        val ground = footAt(-20f)
        if (local < 0.5f) {
            body.leg(side, floorAnkle, v(1f, 0.15f, 0f), ground)
        } else {
            val b = sin(PI.toFloat() * (local - 0.5f) / 0.5f)
            val hip = body.hip(side)
            val top = if (high) v(0.33f, pelvisY - 0.42f, z) else v(-0.24f, pelvisY - 0.08f, z)
            val pole = if (high) v(1f, 0.3f, 0f) else v(0.3f, -1f, 0f)
            val ankle = lerp(floorAnkle, top, b)
            val air = if (high) footAt(-35f) else pointedFoot(hip, ankle, pole)
            val foot = blendFoot(ground, air, b)
            body.leg(side, footAbove(ankle, foot), pole, foot)
        }
        val swing = -45f * sin(TAU * (local - 0.5f))
        body.armAngles(side, limbDir(body.chest, side, swing, 6f), 90f, fold = body.chest.f + body.chest.u * 0.2f)
    }
}

// ---- Jumps ------------------------------------------------------------------------------------------

/**
 * Tuck jump: a quick dip with the arms back, an explosive jump, the knees pulled up to the hands at
 * the top, the legs straightening to land softly on the balls of the feet.
 */
internal fun tuckJump() = Motion.cycle(2.2f, camera = Camera(22f)) { phase ->
    val t = phase * 2.2f
    val stand = 0.915f
    val y = ramp(t, 0.2f to stand, 0.55f to 0.7f, 0.75f to 0.97f, 0.97f to 1.24f, 1.2f to 0.97f, 1.42f to 0.76f, 1.75f to stand)
    val tuck = ramp(t, 0.8f to 0f, 0.98f to 1f, 1.12f to 1f, 1.3f to 0f)
    val arms = ramp(t, 0.15f to 10f, 0.5f to -30f, 0.92f to 72f, 1.05f to 48f, 1.3f to 50f, 1.75f to 10f)
    val depth = ((stand - y) / (stand - 0.7f)).coerceIn(0f, 1f)
    val lean = lerp(4f, 30f, depth) + 6f * tuck
    body.torso(v(0.05f - 0.36f * sin(rad(lean)), y), Basis.UPRIGHT.pitch(lean), nod = -lean * 0.4f)
    val air = (y - stand).coerceAtLeast(0f)
    for (side in Side.entries) {
        val z = side.sign * 0.15f
        val toes = (air / 0.06f).coerceAtMost(1f) * 30f
        val pole = lerp(v(1f, 0.1f, side.sign * 0.3f), v(1f, 0.6f, side.sign * 0.25f), tuck)
        // Feet under the body (on the floor, or pointed below it in the air), pulled up into the tuck.
        val hanging = liftedAnkle(0f, air, toes).withZ(z)
        val tucked = body.onHips(0.3f, -0.4f, side.sign * 0.12f).withZ(side.sign * 0.14f)
        val ankle = lerp(hanging, tucked, tuck)
        val foot = if (tuck > 0f) blendFoot(footAt(-toes), pointedFoot(body.hip(side), ankle, pole, 40f), tuck) else footAt(-toes)
        body.leg(side, ankle, pole, foot)
        body.armAngles(side, limbDir(Basis.UPRIGHT, side, arms, 8f), 14f + 30f * tuck, fold = V3.Y)
    }
}

/** The channels of [boxJump] over its 4.2 s loop: pelvis, lean, arm swing and each foot. */
private object BoxJump {
    const val LOOP = 4.2f
    const val BOX_X0 = 0.4f
    const val BOX_X1 = 0.95f
    const val TOP = 0.55f
    const val START = 0.0f
    const val ON = 0.66f

    fun pelvisX(t: Float) = ramp(t, 0.3f to -0.02f, 0.75f to -0.16f, 1.0f to 0.08f, 1.25f to 0.36f, 1.45f to 0.46f, 2.0f to 0.62f, 2.6f to 0.62f, 3.1f to 0.32f, 3.6f to 0.05f, 3.9f to -0.02f)
    fun pelvisY(t: Float) = ramp(t, 0.3f to 0.915f, 0.75f to 0.66f, 1.0f to 0.97f, 1.25f to 1.36f, 1.45f to 1.13f, 2.0f to TOP + 0.915f, 2.6f to TOP + 0.915f, 3.1f to 0.84f, 3.6f to 0.9f, 3.9f to 0.915f)
    fun lean(t: Float) = ramp(t, 0.3f to 4f, 0.75f to 40f, 1.0f to 2f, 1.25f to 10f, 1.45f to 32f, 2.0f to 4f, 2.6f to 4f, 3.1f to 18f, 3.6f to 6f, 3.9f to 4f)
    fun arms(t: Float) = ramp(t, 0.25f to 8f, 0.68f to -40f, 1.25f to 112f, 1.4f to 90f, 1.55f to 55f, 2.0f to 10f, 3.9f to 8f)

    /** A foot's ankle x, its floor height and heel lift: on the floor, in the air with both feet, or stepping down. */
    fun foot(side: Side, t: Float): Triple<Float, Float, Float> {
        // Jump up together (0.95 → 1.45 s), then step down: the far foot first, then the near one.
        val down0 = if (side == Side.FAR) 2.65f else 3.15f
        val down1 = down0 + 0.45f
        return when {
            t < 0.95f -> Triple(START, 0f, ramp(t, 0.75f to 0f, 0.95f to 25f))
            t < 1.45f -> {
                // Up and over the front edge, toes clear of it, then down onto the box.
                val s = (t - 0.95f) / 0.5f
                Triple(ramp(s, 0f to START, 0.3f to 0.08f, 0.7f to 0.5f, 1f to ON), ramp(s, 0f to 0f, 0.3f to 0.35f, 0.6f to TOP + 0.25f, 1f to TOP), ramp(s, 0f to 25f, 1f to 0f))
            }
            t < down0 -> Triple(ON, TOP, 0f)
            t < down1 -> {
                // Back off the box: up and over the edge first, then down to the floor.
                val s = (t - down0) / (down1 - down0)
                Triple(ramp(s, 0f to ON, 0.35f to 0.45f, 0.6f to 0.2f, 1f to START), ramp(s, 0f to TOP, 0.35f to TOP + 0.08f, 0.6f to TOP * 0.6f, 1f to 0f), 12f * sin(PI.toFloat() * s))
            }
            else -> Triple(START, 0f, 0f)
        }
    }
}

/**
 * Box jump: a dip with the arms back, a jump onto the box landing in a squat, standing tall on it,
 * then stepping back down one foot at a time.
 */
internal fun boxJump() = Motion.cycle(BoxJump.LOOP, camera = Camera(24f)) { phase ->
    val t = phase * BoxJump.LOOP
    val lean = BoxJump.lean(t)
    body.torso(v(BoxJump.pelvisX(t), BoxJump.pelvisY(t)), Basis.UPRIGHT.pitch(lean), nod = -lean * 0.4f)
    for (side in Side.entries) {
        val (x, floor, lift) = BoxJump.foot(side, t)
        val z = side.sign * 0.13f
        val ankle = if (lift >= 0f) liftedAnkle(x, floor, lift) else v(x, floor + BodySize.ANKLE)
        body.leg(side, ankle.withZ(z), v(1f, 0.15f, side.sign * 0.15f), footAt(-lift))
        body.armAngles(side, limbDir(Basis.UPRIGHT, side, BoxJump.arms(t), 8f), 14f, fold = V3.Y)
    }
    box(v(BoxJump.BOX_X0, 0f, -0.34f), v(BoxJump.BOX_X1, BoxJump.TOP, 0.34f), Ink.PAD, bias = -0.3f)
}

/**
 * Skater jumps, seen from the front: bounding from one leg to the other, the free leg swinging
 * behind and across the landing one, the trunk forward and the arms swinging across.
 */
internal fun skaterJump() = Motion.cycle(2.0f, camera = Camera(76f, 10f)) { phase ->
    val t = phase * 2.0f
    val wide = 0.42f
    val behind = v(-0.3f, 0.22f, 0f)
    val pz = ramp(t, 0.35f to 0.32f, 0.75f to -0.32f, 1.35f to -0.32f, 1.75f to 0.32f)
    val py = ramp(t, 0f to 0.8f, 0.25f to 0.76f, 0.55f to 0.92f, 0.8f to 0.77f, 1.05f to 0.8f, 1.25f to 0.76f, 1.55f to 0.92f, 1.8f to 0.77f, 2.0f to 0.8f)
    val s = pz / 0.32f
    body.torso(v(-0.08f, py, pz), Basis.UPRIGHT.pitch(24f).roll(-6f * s), nod = -8f)
    // Each foot between its landing spot and the place behind and across the other one.
    // 1: the near leg is the free one, behind the far leg the body stands on.
    val nearW = ramp(t, 0.35f to 0f, 0.75f to 1f, 1.35f to 1f, 1.75f to 0f)
    for (side in Side.entries) {
        val w = if (side == Side.NEAR) nearW else 1f - nearW
        val planted = v(0f, BodySize.ANKLE, side.sign * wide)
        // Behind the other foot, the shin swung back.
        val air = v(behind.x, behind.y, -side.sign * wide * 0.9f)
        val ankle = lerp(planted, air, w) + V3.Y * (0.06f * sin(PI.toFloat() * w))
        val pole = v(1f, 0f, side.sign * 0.2f)
        val foot = blendFoot(Foot.flat(side, 10f), pointedFoot(body.hip(side), ankle, pole, 35f), Ease.IN_OUT.apply((w * 1.6f).coerceAtMost(1f)))
        body.leg(side, footAbove(ankle, foot), pole, foot)
    }
    body.swingArm(Side.NEAR, 30f - 45f * s, out = -10f * (1 - s), elbow = 30f)
    body.swingArm(Side.FAR, 30f + 45f * s, out = -10f * (1 + s), elbow = 30f)
}

// ---- Crawl, boxing, step, ropes, sleds --------------------------------------------------------------

/**
 * Bear crawl: on hands and toes with the knees hovering just off the floor, moving a hand and the
 * opposite foot together; the floor passes by under the hands.
 */
internal fun bearCrawl() = Motion.cycle(1.8f, camera = Camera(24f)) { phase ->
    val step = 0.13f
    val pelvisY = 0.53f + 0.01f * cos(phase * TAU * 2)
    val shoulderY = 0.6f
    val spine = BodySize.LOWER_SPINE + BodySize.UPPER_SPINE - BodySize.SHOULDER_DROP
    val rise = deg(kotlin.math.asin((shoulderY - pelvisY) / spine))
    body.torso(v(0f, pelvisY), Basis.UPRIGHT.pitch(90f - rise), twist = 4f * sin(phase * TAU), nod = -18f)
    val shoulderX = spine * cos(rad(rise))
    /** Offset along the floor (+ ahead) and the lift of a limb at its own [p] of the stride. */
    fun limb(p: Float): Pair<Float, Float> = if (p < 0.5f) {
        lerp(step, -step, p / 0.5f) to 0f
    } else {
        val s = (p - 0.5f) / 0.5f
        lerp(-step, step, Ease.IN_OUT.apply(s)) to 0.06f * sin(PI.toFloat() * s)
    }
    for (side in Side.entries) {
        val handPhase = (phase + if (side == Side.NEAR) 0f else 0.5f) % 1f
        val footPhase = (phase + if (side == Side.NEAR) 0.5f else 0f) % 1f
        val (hx, hy) = limb(handPhase)
        body.arm(side, v(shoulderX + hx, BodySize.FIST_RADIUS + hy, side.sign * 0.18f), v(-1f, 0.2f, side.sign * 0.5f))
        val (fx, fy) = limb(footPhase)
        val foot = Foot(dirXY(238f), dirXY(328f))
        body.leg(side, footAbove(v(-0.38f + fx, 0.12f + fy, side.sign * 0.1f), foot), v(1f, -1.5f, 0f), foot)
    }
    groundMarks(phase, 4 * step)
}

/** The channels of [shadowBoxing] over its 2.4 s loop: how far each punch is out, and the trunk's turn. */
private object Shadow {
    const val LOOP = 2.4f
    val jab = Track(listOf(Key(0f, 0f), Key(0.3f, 0f), Key(0.45f, 1f, Ease.OUT), Key(0.62f, 0f), Key(LOOP, 0f)))
    val cross = Track(listOf(Key(0f, 0f), Key(0.68f, 0f), Key(0.86f, 1f, Ease.OUT), Key(1.08f, 0f), Key(LOOP, 0f)))
    val hook = Track(listOf(Key(0f, 0f), Key(1.12f, 0f), Key(1.32f, 1f, Ease.OUT), Key(1.58f, 0f), Key(LOOP, 0f)))
    val twist = Track.keys(0f to 0f, 0.3f to 0f, 0.45f to 8f, 0.62f to 0f, 0.68f to 0f, 0.86f to -34f, 1.08f to -6f, 1.12f to -6f, 1.32f to 26f, 1.6f to 0f, LOOP to 0f)
}

/**
 * Shadow boxing: bouncing on the balls of the feet in a guard, a jab, a cross and a lead hook thrown at
 * the air, the hips and the back foot turning into each punch, then back to the guard.
 */
internal fun shadowBoxing() = Motion.cycle(Shadow.LOOP, camera = Camera(34f)) { phase ->
    val t = phase * Shadow.LOOP
    val jab = Shadow.jab.value(t)
    val cross = Shadow.cross.value(t)
    val hook = Shadow.hook.value(t)
    val bounce = 0.012f * (0.5f - 0.5f * cos(phase * TAU * 4))
    body.torso(v(-0.02f, 0.85f + bounce), Basis.UPRIGHT.pitch(8f).turn(26f - 10f * cross + 6f * hook), twist = Shadow.twist.value(t), nod = 6f)
    body.onBall(Side.FAR, 0.2f, -0.1f, floor = bounce, lift = 12f, pole = v(1f, 0.1f, -0.1f))
    body.onBall(Side.NEAR, -0.24f, 0.16f, floor = bounce, lift = 18f + 18f * cross, pole = v(1f, 0.1f, 0.2f))
    val leadGuard = body.onChest(0.24f, 0.12f, -0.09f)
    val rearGuard = body.onChest(0.2f, 0.13f, 0.1f)
    val jabEnd = body.shoulder(Side.FAR) + v(0.54f, 0.02f, 0.1f)
    val hookEnd = body.onChest(0.36f, 0.0f, 0.06f)
    val lead = lerp(lerp(leadGuard, jabEnd, jab), hookEnd, hook)
    body.arm(Side.FAR, lead, lerp(v(-0.2f, -1f, -0.25f), body.chest.dir(-0.2f, 0.25f, -1f), hook))
    body.arm(Side.NEAR, lerp(rearGuard, body.shoulder(Side.NEAR) + v(0.55f, 0.02f, -0.14f), cross), v(-0.2f, -1f, 0.25f))
}

/**
 * Aerobic step: up with the near foot, then the far one, down with the near foot, then the far one,
 * one beat each, on a low step; the arms swing with the legs.
 */
internal fun stepAerobics() = Motion.cycle(2.0f, camera = Camera(26f)) { phase ->
    val t = phase * 2.0f
    val top = 0.18f
    val floorX = -0.12f
    val stepX = 0.24f
    /** Up during its beat [up], down during [down] (each 0.5 s), an arc on the way. */
    fun foot(up: Float, down: Float): Triple<Float, Float, Float> {
        val u = ((t - up) / 0.5f).coerceIn(0f, 1f)
        val d = ((t - down) / 0.5f).coerceIn(0f, 1f)
        val on = Ease.IN_OUT.apply(u) - Ease.IN_OUT.apply(d)
        val arc = 0.08f * (sin(PI.toFloat() * u) + sin(PI.toFloat() * d))
        return Triple(lerp(floorX, stepX, on), top * on + arc, on)
    }
    val feet = mapOf(Side.NEAR to foot(0f, 1.0f), Side.FAR to foot(0.5f, 1.5f))
    val ankles = feet.mapValues { (side, f) -> v(f.first, f.second + BodySize.ANKLE, side.sign * 0.11f) }
    val px = (ankles.getValue(Side.NEAR).x + ankles.getValue(Side.FAR).x) / 2 - 0.04f
    val legs = 0.82f
    val py = ankles.values.minOf { a -> a.y + sqrt(legs * legs - (a.x - px) * (a.x - px)) }
    body.torso(v(px, py), Basis.UPRIGHT.pitch(6f), nod = -6f)
    for (side in Side.entries) {
        body.leg(side, ankles.getValue(side), v(1f, 0.2f, side.sign * 0.05f), Foot.flat(side, 4f))
        body.swingArm(side, 30f * (feet.getValue(side.other).third - 0.5f) * 2f, out = 8f, elbow = 70f)
    }
    box(v(0.05f, 0f, -0.36f), v(0.5f, top, 0.36f), Ink.PAD, bias = -0.3f)
}

/**
 * Battle ropes: a half squat with the chest up, the arms raising and lowering in turn so each rope
 * makes waves towards the anchor in front (out of the picture).
 */
internal fun battleRopes() = Motion.cycle(1.2f, cycles = 2, camera = Camera(26f, 6f)) { phase ->
    body.torso(v(-0.1f, 0.74f), Basis.UPRIGHT.pitch(24f), nod = -12f)
    body.standOn(0f, width = 0.44f, out = 12f, pole = v(1f, 0.1f, 0.3f))
    for (side in Side.entries) {
        val wave = phase * TAU + if (side == Side.NEAR) 0f else PI.toFloat()
        val hand = v(0.42f, 0.95f + 0.15f * sin(wave), side.sign * 0.2f)
        body.arm(side, hand, v(-0.3f, -1f, side.sign * 0.5f))
        add(Rod(hand - V3.X * 0.07f, hand + V3.X * 0.08f, 0.03f, Ink.FRAME))
        val rope = List(30) { i ->
            val s = i / 29f
            val x = hand.x + 0.08f + 2.6f * s
            v(x, lerp(hand.y, 0.2f, sqrt(s)) + 0.16f * (1 - s) * sin(wave - 9f * s) * sin(PI.toFloat() * s.coerceAtMost(0.5f)), hand.z * (1 - 0.5f * s))
        }
        add(Cord(rope, 0.035f, Ink.FRAME, fit = false))
    }
}

/**
 * Sled push: leaning into the sled's posts with the arms bent, the body in a straight line from the
 * back foot, driving with short steps; the floor goes by.
 */
internal fun sledPush() = Motion.cycle(2.0f, cycles = 2, camera = Camera(22f)) { phase ->
    val reach = 0.2f
    val stance = 0.66f
    body.torso(v(-0.12f, 0.78f + 0.01f * cos(phase * TAU * 2)), Basis.UPRIGHT.pitch(50f), nod = -26f)
    val flat = { _: Float -> 0f }
    for (side in Side.entries) {
        surfaceGaitLeg(side, (phase + if (side == Side.NEAR) 0f else 0.5f) % 1f, reach, lift = 0.07f, stance = stance, surface = flat, slope = flat, center = -0.36f)
        body.arm(side, v(0.62f, 0.98f, side.sign * 0.2f), v(-0.2f, -1f, side.sign * 0.5f))
    }
    sled(0.64f)
    groundMarks(phase, 2 * reach / stance)
}

/** A sled from [x] forward: the skid with two plates and the two upright posts with their handles. */
private fun FrameBuilder.sled(x: Float, posts: Boolean = true) {
    add(Rod(v(x, 0.03f, -0.28f), v(x + 0.55f, 0.03f, -0.28f), 0.025f, Ink.FRAME, bias = -0.2f))
    add(Rod(v(x, 0.03f, 0.28f), v(x + 0.55f, 0.03f, 0.28f), 0.025f, Ink.FRAME, bias = -0.1f))
    add(Block(v(x + 0.3f, 0.08f), Basis.UPRIGHT, v(0.22f, 0.04f, 0.26f), Ink.FRAME, bias = -0.15f))
    add(Rod(v(x + 0.3f, 0.12f), v(x + 0.3f, 0.36f), 0.02f, Ink.METAL, bias = -0.15f))
    for (i in 0 until 2) add(Disc(v(x + 0.3f, 0.15f + i * 0.05f), V3.Y, 0.2f, 0.045f, Ink.PLATE, hub = 0.035f, bias = -0.12f))
    if (posts) {
        for (s in listOf(-1f, 1f)) {
            add(Rod(v(x + 0.06f, 0.08f, s * 0.2f), v(x - 0.02f, 1.02f, s * 0.2f), 0.025f, Ink.FRAME, bias = -0.2f))
        }
    }
}

/**
 * Backward sled drag: facing the sled in a quarter squat, straps in the hands, walking backwards
 * toe to heel; the floor goes by the other way.
 */
internal fun sledDrag() = Motion.cycle(2.0f, cycles = 2, camera = Camera(22f)) { phase ->
    val reach = 0.18f
    val stance = 0.62f
    body.torso(v(0f, 0.8f + 0.01f * cos(phase * TAU * 2)), Basis.UPRIGHT.pitch(14f), nod = -10f)
    val flat = { _: Float -> 0f }
    for (side in Side.entries) {
        // Walking backwards is walking forwards played in reverse: toes first, the foot moving forward under the body.
        val p = 1f - (phase + if (side == Side.NEAR) 0f else 0.5f) % 1f
        surfaceGaitLeg(side, p % 1f, reach, lift = 0.06f, stance = stance, surface = flat, slope = flat, center = 0.02f)
        val hand = v(0.44f, 0.82f, side.sign * 0.13f)
        body.arm(side, hand, v(-0.3f, -1f, side.sign * 0.6f))
        add(Cord(listOf(hand, v(1.15f, 0.18f, side.sign * 0.1f)), 0.018f, Ink.BAND, bias = -0.05f))
    }
    sled(1.15f, posts = false)
    groundMarks(1f - phase, 2 * reach / stance)
}
