package com.enjoyyourtime.globalfitness.ui.training.motion

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

// Advanced calisthenics added in 1.8: bar and ring muscle-ups, handstands and the wall handstand
// push-up, front and back levers, planches, the human flag, the harder pull-ups and push-ups, the
// shrimp squat, skin the cat, ring support and push-ups, hollow rocks and windshield wipers.

private const val TAU = 2 * PI.toFloat()

/** Hip joints to the shoulder line. */
private const val SPINE = BodySize.LOWER_SPINE + BodySize.UPPER_SPINE - BodySize.SHOULDER_DROP

/** Ankle to the hip joints of a straight leg, as the plank patterns use it. */
private const val LEGS = BodySize.THIGH + BodySize.SHIN - 0.01f

private const val BAR = 2.3f

/** A point eased between keys (time to point), as [ramp] does for numbers. */
internal fun vramp(t: Float, vararg keys: Pair<Float, V3>): V3 =
    V3(ramp(t, *keys.map { it.first to it.second.x }.toTypedArray()), ramp(t, *keys.map { it.first to it.second.y }.toTypedArray()), ramp(t, *keys.map { it.first to it.second.z }.toTypedArray()))

/** A foot pointed along [shin], turned [short] degrees towards the shin's [front] (never fully straight). */
internal fun pointedAlong(shin: V3, front: V3, short: Float = 28f): Foot {
    val f = orthogonal(front, shin.unit())
    val dir = (shin.unit() * cos(rad(short)) + f * sin(rad(short))).unit()
    return Foot(dir, orthogonal(f, dir))
}

/** A gymnastic ring held at [hand], its strap rising to [top]. */
internal fun FrameBuilder.gymRing(hand: V3, top: Float = 3.4f) {
    add(Disc(hand, V3.X, 0.09f, 0.02f, Ink.METAL, hub = 0.075f, bias = -0.03f))
    add(Cord(listOf(hand + V3.Y * 0.09f, v(hand.x * 0.5f, top, hand.z * 0.9f)), 0.02f, Ink.BAND, fit = false, bias = -0.2f))
}

/**
 * Bends the [side] leg by angles relative to the hips: the thigh [hip] degrees forward (and [out]
 * degrees to the side), the knee folding [knee] degrees towards the back of the thigh, whatever way
 * the thigh points (raised past level, the back of the thigh faces down).
 */
internal fun FrameBuilder.bentLeg(side: Side, hip: Float, knee: Float, out: Float = 3f, foot: Foot? = null) {
    body.legAngles(side, limbDir(body.hips, side, hip, out), knee, fold = limbDir(body.hips, side, hip - 90f), foot = foot)
}

// ---- Muscle-ups ------------------------------------------------------------------------------------

/**
 * Bar muscle-up: from the hang an explosive pull brings the bar to the lower chest, the trunk leans
 * over the bar as the wrists turn over it, then a dip presses up to a straight-arm support; back down
 * under control the same way.
 */
internal fun barMuscleUp() = Motion.rep(Track.rep(go = 1.7f, hold = 0.35f, back = 1.9f, rest = 0.4f), Camera(28f)) { p ->
    val half = 0.28f
    val lateral = half - BodySize.SHOULDER_HALF
    val bar = v(0f, BAR)
    val hang = v(-0.02f, BAR - armReach(lateral, 0.02f, 0.985f))
    val top = v(0.05f, BAR + armReach(lateral, 0.05f, 0.975f))
    // The pull ends with the shoulders behind the bar; the transition swings them round over it at a
    // forearm's distance (the elbows bent deep but not past what they can).
    val shoulder = polarRamp(p, bar, 0f to hang, 0.42f to bar + dirXY(205f) * 0.21f, 0.62f to bar + dirXY(72f) * 0.22f, 1f to top)
    val lean = ramp(p, 0f to 0f, 0.42f to -18f, 0.62f to 30f, 1f to 12f)
    hangFrom(shoulder, lean, nod = ramp(p, 0f to 0f, 0.42f to -10f, 0.62f to 18f, 1f to 4f))
    for (side in Side.entries) {
        val pole = vramp(p, 0f to v(-0.1f, -1f, side.sign * 1f), 0.42f to v(-0.6f, -1f, side.sign * 0.5f), 0.62f to v(-1f, -0.2f, side.sign * 0.4f), 1f to v(-1f, 0.3f, side.sign * 0.3f))
        body.arm(side, v(0f, BAR, side.sign * half), pole)
    }
    val hips = ramp(p, 0f to 16f, 0.3f to 34f, 0.62f to 24f, 1f to 16f)
    val knees = ramp(p, 0f to 48f, 0.3f to 56f, 0.62f to 38f, 1f to 44f)
    for (side in Side.entries) bentLeg(side, hips, knees)
    pullUpBar(0f, BAR)
}

/**
 * A point eased between keys (time to point) as [vramp] does, but going round [center] in the X-Y
 * plane: the direction and the distance from it blend separately, so the path keeps clear of it.
 */
private fun polarRamp(t: Float, center: V3, vararg keys: Pair<Float, V3>): V3 {
    var angle = angleXY(keys.first().second - center)
    val angles = keys.map { (_, p) ->
        var a = angleXY(p - center)
        while (a - angle > 180f) a -= 360f
        while (angle - a > 180f) a += 360f
        angle = a
        a
    }
    val a = ramp(t, *keys.indices.map { keys[it].first to angles[it] }.toTypedArray())
    val r = ramp(t, *keys.map { it.first to (it.second - center).withZ(0f).length }.toTypedArray())
    val z = ramp(t, *keys.map { it.first to it.second.z }.toTypedArray())
    return (center + dirXY(a) * r).withZ(z)
}

/**
 * Ring muscle-up with a false grip: the rings are pulled to the lower chest, the trunk leans over them
 * as the elbows go back, and a ring dip presses up to the support with the rings by the hips.
 */
internal fun ringMuscleUp() = Motion.rep(Track.rep(go = 1.7f, hold = 0.35f, back = 1.9f, rest = 0.4f), Camera(28f)) { p ->
    val ring = 2.3f
    // The rings stay at one height: the shoulders rise past them while the hands move down the body.
    val reach = armReach(0.07f, 0.02f, 0.975f)
    val shoulder = vramp(p, 0f to v(0f, ring - reach), 0.42f to v(-0.06f, ring + 0.17f), 0.62f to v(0.02f, ring + 0.3f), 1f to v(0f, ring + 0.55f))
    // The hands come down in front of the face to the chest, then down the sides to the hips.
    val rel = polarRamp(p, V3.ZERO, 0f to v(0.02f, reach), 0.42f to v(0.13f, -0.17f), 0.62f to v(0.04f, -0.3f), 1f to v(0f, -0.55f))
    val lean = ramp(p, 0f to 0f, 0.42f to -14f, 0.62f to 26f, 1f to 8f)
    hangFrom(shoulder, lean, nod = ramp(p, 0f to 0f, 0.42f to -8f, 0.62f to 16f, 1f to 4f))
    for (side in Side.entries) {
        val hand = (shoulder + rel).withZ(side.sign * ramp(p, 0f to 0.245f, 0.42f to 0.22f, 1f to 0.26f))
        val pole = vramp(p, 0f to v(-0.1f, -1f, side.sign * 1f), 0.42f to v(-0.7f, -1f, side.sign * 0.4f), 0.62f to v(-1f, 0.1f, side.sign * 0.3f), 1f to v(-1f, 0.3f, side.sign * 0.25f))
        body.arm(side, hand, pole)
        gymRing(hand, top = 4.2f)
    }
    val hips = ramp(p, 0f to 16f, 0.3f to 32f, 0.62f to 22f, 1f to 16f)
    val knees = ramp(p, 0f to 48f, 0.3f to 54f, 0.62f to 36f, 1f to 44f)
    for (side in Side.entries) bentLeg(side, hips, knees)
}

// ---- Handstands ------------------------------------------------------------------------------------

/**
 * Upside down on straight or bending arms: hands on the floor at [handX], the shoulder line at height
 * [shoulderY] and [ahead] of the hands, the straight body leaning [lean] degrees towards +X (a wall
 * there), legs [apart] degrees out, toes pointed.
 */
private fun FrameBuilder.handstand(handX: Float, shoulderY: Float, lean: Float, ahead: Float = 0f, nod: Float = -24f, apart: Float = 2f, shrug: Float = 0.02f) {
    val basis = Basis.UPRIGHT.pitch(180f + lean)
    val shoulders = v(handX + ahead, shoulderY)
    body.torso(shoulders - basis.u * (SPINE + shrug), basis, nod = nod, shrug = shrug)
    for (side in Side.entries) {
        body.arm(side, v(handX, BodySize.FIST_RADIUS, side.sign * 0.21f), v(-0.7f, 0f, side.sign * 0.7f))
        val dir = (-basis.u + basis.l * (side.sign * sin(rad(apart)))).unit()
        val shin = rotate(dir, basis.l, rad(3f))
        body.legAngles(side, dir, 3f, fold = -basis.f, foot = pointedAlong(shin, basis.f))
    }
}

/** Shoulder height of a handstand on straight arms. */
private val HANDSTAND_SHOULDERS = BodySize.FIST_RADIUS + armReach(0.21f - BodySize.SHOULDER_HALF, 0f, 0.985f)

/** Hands this far in front of the wall in the back-to-wall handstand, and how much the body leans onto it. */
private const val WALL_GAP = 0.235f
private const val WALL_LEAN = 6f

/** A wall at [x], behind the heels of a handstand (context, behind everything). */
private fun FrameBuilder.wall(x: Float) {
    box(v(x, 0f, -0.9f), v(x + 0.1f, 2.3f, 0.9f), Ink.FRAME, fit = false, bias = BEHIND)
}

/** Wall handstand: back to the wall, heels resting on it, arms locked and the body tight, breathing. */
internal fun wallHandstand() = Motion.cycle(4f, camera = Camera(30f)) { phase ->
    val breath = sin(phase * TAU)
    handstand(0f, HANDSTAND_SHOULDERS - 0.004f * breath, WALL_LEAN, nod = -24f - 3f * breath, shrug = 0.02f + 0.008f * breath)
    wall(WALL_GAP)
}

/** Freestanding handstand: balancing on the hands, the fingers correcting small sways forward and back. */
internal fun handstand() = Motion.cycle(4.4f, camera = Camera(30f)) { phase ->
    val sway = sin(phase * TAU)
    handstand(0f, HANDSTAND_SHOULDERS, lean = 1.6f * sway, ahead = 0.012f * sway, nod = -22f - 2f * sway, apart = 1f)
}

/**
 * Wall handstand push-up: back to the wall, the elbows bend forward and out until the head nearly
 * touches the floor, the heels sliding down the wall, then press back up.
 */
internal fun wallHandstandPushUp() = Motion.rep(Tempo.lowerFirst(), Camera(30f)) { p ->
    handstand(0f, lerp(HANDSTAND_SHOULDERS, 0.35f, p), WALL_LEAN, ahead = lerp(0f, -0.05f, p), nod = lerp(-24f, -38f, p))
    wall(WALL_GAP)
}

// ---- Levers and planches ---------------------------------------------------------------------------

/**
 * Front lever: from the dead hang the straight arms press the bar down and the body rises, face up,
 * to level with the floor; held, then lowered. [tuck] pulls the knees to the chest first.
 */
internal fun frontLever(tuck: Boolean) = Motion.rep(Track.rep(go = 1.3f, hold = 1.6f, back = 1.3f, rest = 0.6f), Camera(24f)) { p ->
    val half = 0.3f
    val reach = armReach(half - BodySize.SHOULDER_HALF, 0f, 0.985f)
    val tucked = if (tuck) Ease.IN_OUT.apply((p / 0.35f).coerceIn(0f, 1f)) else 0f
    val raise = if (tuck) Ease.IN_OUT.apply(((p - 0.25f) / 0.75f).coerceIn(0f, 1f)) else Ease.IN_OUT.apply(p)
    val swing = rad(lerp(0f, -11f, raise))
    val shoulder = v(sin(swing) * reach, BAR - cos(swing) * reach)
    hangFrom(shoulder, lean = lerp(0f, -90f, raise), nod = lerp(0f, 16f, raise))
    for (side in Side.entries) body.arm(side, v(0f, BAR, side.sign * half), v(0f, 0f, side.sign * 1f))
    val hips = if (tuck) lerp(5f, 128f, tucked) else lerp(5f, 2f, raise)
    val knees = if (tuck) lerp(10f, 138f, tucked) else lerp(10f, 3f, raise)
    for (side in Side.entries) bentLeg(side, hips, knees)
    pullUpBar(0f, BAR)
}

/**
 * Back lever: hanging face down under the bar with the arms straight behind the body, level with the
 * floor, held while breathing. [tuck] keeps the knees tucked under the chest.
 */
internal fun backLever(tuck: Boolean) = Motion.cycle(4f, camera = Camera(24f)) { phase ->
    val breath = sin(phase * TAU)
    val half = 0.3f
    val ahead = 0.42f
    val shoulder = v(ahead, BAR - armReach(half - BodySize.SHOULDER_HALF, ahead, 0.985f))
    hangFrom(shoulder, lean = 90f + 1.2f * breath, nod = -12f)
    for (side in Side.entries) body.arm(side, v(0f, BAR, side.sign * half), v(0f, 0f, side.sign * 1f))
    for (side in Side.entries) {
        if (tuck) {
            bentLeg(side, 112f, 122f, out = 4f)
        } else {
            bentLeg(side, 0f, 3f, out = 2f, foot = pointedAlong(-body.hips.u, body.hips.f))
        }
    }
    pullUpBar(0f, BAR)
}

/**
 * Planche on the floor: arms locked and leaning forward, shoulders well past the hands, the body held
 * off the floor; [straddle] holds it straight and level with the legs wide, otherwise the knees are
 * tucked to the chest with the hips high. A small sway over the hands while held.
 */
internal fun planche(straddle: Boolean) = Motion.cycle(4f, camera = Camera(if (straddle) 30f else 26f, 6f)) { phase ->
    val sway = sin(phase * TAU)
    val handZ = 0.22f
    val reach = armReach(handZ - BodySize.SHOULDER_HALF, 0f, 0.985f)
    val tilt = rad(32f + 1.5f * sway)
    val shoulder = v(sin(tilt) * reach, BodySize.FIST_RADIUS + cos(tilt) * reach)
    val basis = Basis.UPRIGHT.pitch(if (straddle) 90f else 112f)
    body.torso(shoulder - basis.u * SPINE, basis, bend = if (straddle) 0f else 14f, nod = -18f)
    for (side in Side.entries) body.arm(side, v(0f, BodySize.FIST_RADIUS, side.sign * handZ), v(-1f, 0f, side.sign * 0.4f))
    for (side in Side.entries) {
        if (straddle) {
            val dir = limbDir(body.hips, side, -3f, 28f)
            body.legAngles(side, dir, 3f, fold = -body.hips.f, foot = pointedAlong(dir, body.hips.f))
        } else {
            bentLeg(side, 140f, 145f, out = 8f)
        }
    }
}

/**
 * Human flag, seen from the front: the top hand pulls and the bottom hand pushes on a vertical pole,
 * the shoulder line upright and the body held level to the side; the legs extend from a tuck, hold
 * and tuck again.
 */
internal fun humanFlag() = Motion.rep(Track.rep(go = 1.0f, hold = 1.6f, back = 1.0f, rest = 0.6f), Camera(80f, 6f)) { p ->
    val y = 1.25f
    val pole = 0.5f
    // Chest towards the viewer, the head towards the pole, the right (near) side down.
    val basis = Basis(V3.X, V3.Z, -V3.Y)
    val neck = v(0f, y, BodySize.SHOULDER_DROP)
    body.torso(neck - basis.u * (BodySize.LOWER_SPINE + BodySize.UPPER_SPINE), basis, nod = 10f)
    val rise = armReach(0f, pole, 0.985f)
    body.arm(Side.FAR, v(0f, y + BodySize.SHOULDER_HALF + rise, pole), v(0.3f, 1f, 0f))
    body.arm(Side.NEAR, v(0f, y - BodySize.SHOULDER_HALF - rise, pole), v(0.3f, -1f, 0f))
    val e = Ease.IN_OUT.apply(p)
    for (side in Side.entries) {
        bentLeg(side, lerp(105f, 0f, e), lerp(115f, 3f, e), out = lerp(6f, 4f, e))
    }
    add(Rod(v(0f, 0f, pole + 0.02f), v(0f, 2.7f, pole + 0.02f), 0.03f, Ink.METAL, fit = false, bias = -0.08f))
}

/** Tuck L-sit on parallettes: arms straight beside the hips, the knees pulled up and the feet off the floor. */
internal fun tuckLSit() = Motion.cycle(3.6f, camera = Camera(30f)) { phase ->
    val breath = sin(phase * TAU)
    val barY = 0.34f
    val handZ = 0.24f
    val shoulder = v(0.02f, barY + armReach(handZ - BodySize.SHOULDER_HALF, 0.02f, 0.98f))
    hangFrom(shoulder, lean = 8f + 0.8f * breath)
    for (side in Side.entries) {
        bentLeg(side, 132f + 2f * breath, 138f, out = 6f)
        body.arm(side, v(0f, barY, side.sign * handZ), v(-1f, 0f, side.sign * 0.2f))
        add(Rod(v(-0.15f, barY, side.sign * handZ), v(0.15f, barY, side.sign * handZ), 0.02f, Ink.METAL, bias = -0.05f))
        for (x in listOf(-0.13f, 0.13f)) add(Rod(v(x, 0.02f, side.sign * handZ), v(x, barY, side.sign * handZ), 0.018f, Ink.FRAME, bias = -0.1f))
    }
}

// ---- Pull-ups and push-ups -------------------------------------------------------------------------

/** Hanging from a wide grip with the shoulder line centred [across] metres towards the near hand, at height [y]. */
private fun FrameBuilder.wideHang(y: Float, across: Float, half: Float) {
    val basis = Basis.UPRIGHT.pitch(lerp(2f, -6f, ((BAR - y - 0.2f) / 0.3f).let { 1f - it.coerceIn(0f, 1f) })).roll(28f * across)
    val shoulders = v(-0.03f, y, across)
    body.torso(shoulders - basis.u * SPINE, basis, nod = -6f)
    for (side in Side.entries) body.arm(side, v(0f, BAR, side.sign * half), v(-0.1f, -1f, side.sign * 1.2f))
    for (side in Side.entries) bentLeg(side, 10f, 28f)
    pullUpBar(0f, BAR)
}

/** Shoulder height of a dead hang from a grip [half] wide. */
private fun hangHeight(half: Float) = BAR - armReach(half - BodySize.SHOULDER_HALF, 0.03f, 0.985f)

/**
 * Archer pull-up, seen from the front: a very wide grip; the body rises towards the near hand while the
 * far arm stays nearly straight, lowers, then rises towards the far hand.
 */
internal fun archerPullUp() = Motion.cycle(4.6f, camera = Camera(64f, 8f)) { phase ->
    val t = phase * 4.6f
    val half = 0.45f
    val hang = hangHeight(half)
    val y = ramp(t, 0.4f to hang, 1.3f to BAR - 0.2f, 1.6f to BAR - 0.2f, 2.3f to hang, 2.7f to hang, 3.6f to BAR - 0.2f, 3.9f to BAR - 0.2f, 4.6f to hang)
    val across = ramp(t, 0.4f to 0f, 1.3f to 0.26f, 1.6f to 0.26f, 2.3f to 0f, 2.7f to 0f, 3.6f to -0.26f, 3.9f to -0.26f, 4.6f to 0f)
    wideHang(y, across, half)
}

/**
 * Typewriter pull-up, seen from the front: up to the bar with a wide grip, the chest slides over to
 * one hand and across to the other with the chin above the bar, back to the middle and down.
 */
internal fun typewriterPullUp() = Motion.cycle(5.2f, camera = Camera(64f, 8f)) { phase ->
    val t = phase * 5.2f
    val half = 0.45f
    val hang = hangHeight(half)
    val top = BAR - 0.2f
    val y = ramp(t, 0.4f to hang, 1.3f to top, 4.0f to top, 4.8f to hang, 5.2f to hang)
    val across = ramp(t, 1.3f to 0f, 2.0f to 0.25f, 2.25f to 0.25f, 3.15f to -0.25f, 3.4f to -0.25f, 4.0f to 0f)
    wideHang(y, across, half)
}

/**
 * One-arm push-up, seen from the side and above: feet wide, the near hand under the middle of the
 * chest and the far hand on the lower back; the straight body lowers and presses up on one arm.
 */
internal fun oneArmPushUp() = Motion.rep(Tempo.lowerFirst(), Camera(46f, 20f)) { p ->
    val ankle = v(-0.85f, 0.185f)
    val line = LEGS + SPINE
    val top = asin((0.6f - ankle.y) / line)
    val bottom = asin((0.31f - ankle.y) / line)
    val angle = deg(lerp(top, bottom, p))
    // The feet are wide apart, so the straight legs reach less far along the body.
    val feet = 0.32f
    val pelvis = ankle + dirXY(angle) * sqrt(LEGS * LEGS - (feet - BodySize.HIP_HALF) * (feet - BodySize.HIP_HALF))
    body.torso(pelvis, Basis.UPRIGHT.pitch(90f - angle), nod = 8f)
    for (side in Side.entries) body.leg(side, ankle.withZ(side.sign * feet), v(0f, -1f, 0f), onToes(angle))
    val handX = ankle.x + line * cos(top)
    body.arm(Side.NEAR, v(handX, BodySize.FIST_RADIUS, 0.04f), v(-0.6f, 0.2f, 0.9f))
    body.arm(Side.FAR, body.onHips(-0.16f, 0.1f, -0.04f), v(0f, 1f, -0.6f))
}

/**
 * Shrimp squat: on the near leg, the far knee bent with that foot held behind by the far hand; the
 * hips sink until the back knee touches the floor behind the heel, the near arm forward for balance.
 */
internal fun shrimpSquat() = Motion.rep(Track.rep(go = 1.5f, hold = 0.25f, back = 1.3f, rest = 0.45f), Camera(30f)) { p ->
    val lean = lerp(10f, 22f, p)
    body.torso(v(lerp(0f, -0.04f, p), lerp(0.9f, 0.52f, p), 0.04f), Basis.UPRIGHT.pitch(lean), nod = -lean * 0.4f)
    body.stand(Side.NEAR, 0.02f, z = 0.13f, out = 8f, pole = v(1f, 0.1f, 0.15f))
    val thigh = lerp(-104f, -88f, p)
    body.legAngles(Side.FAR, dirXY(thigh) + V3.Z * -0.04f, lerp(150f, 156f, p), fold = -V3.X)
    // The far hand holds the back foot by its instep, sliding up it as the hips sink.
    val foot = body.legOf(Side.FAR)
    body.arm(Side.FAR, foot.ankle + lerp(v(-0.02f, 0.05f, -0.02f), v(0.05f, 0.11f, -0.02f), p), v(0.2f, -0.3f, -1f))
    body.armAngles(Side.NEAR, limbDir(Basis.UPRIGHT, Side.NEAR, lerp(45f, 85f, p), 6f), 8f, fold = V3.Y)
}

/**
 * Skin the cat on rings: from the hang the knees tuck and pass between the arms, the body rolls
 * backwards through upside down to the German hang, arms behind, then rolls back the same way.
 */
internal fun skinTheCat() = Motion.rep(Track.rep(go = 2.4f, hold = 0.45f, back = 2.4f, rest = 0.4f), Camera(26f)) { p ->
    val ring = 2.35f
    val half = 0.25f
    val shoulder = v(0f, ring - armReach(half - BodySize.SHOULDER_HALF, 0f, 0.985f))
    val turn = lerp(0f, 300f, p)
    val basis = Basis.UPRIGHT.pitch(-turn)
    body.torso(shoulder - basis.u * SPINE, basis, nod = 10f * sin(PI.toFloat() * p))
    for (side in Side.entries) {
        val hand = v(0f, ring, side.sign * half)
        body.arm(side, hand, body.chest.l * side.sign)
        gymRing(hand)
    }
    val tuck = ramp(turn, 25f to 0f, 100f to 1f, 225f to 1f, 300f to 0.25f)
    for (side in Side.entries) bentLeg(side, lerp(5f, 132f, tuck), lerp(8f, 130f, tuck), out = 4f)
}

/** Ring support hold: on straight arms above the rings, rings by the hips and turned out a little, breathing. */
internal fun ringSupportHold() = Motion.cycle(3.6f, camera = Camera(24f)) { phase ->
    val breath = sin(phase * TAU)
    val ring = 1.25f
    val handZ = 0.26f
    val shoulder = v(0.02f, ring + armReach(handZ - BodySize.SHOULDER_HALF, 0.02f) + 0.004f * breath)
    val basis = Basis.UPRIGHT.pitch(6f + 0.8f * breath)
    body.torso(shoulder - basis.u * SPINE, basis, nod = -2f)
    // Legs long and together under the body, knees barely soft.
    for (side in Side.entries) bentLeg(side, 6f, 6f)
    for (side in Side.entries) {
        val hand = v(0.04f, ring, side.sign * handZ)
        body.arm(side, hand, v(-1f, 0f, side.sign * 0.25f))
        gymRing(hand, top = 2.7f)
    }
}

/** Ring push-up: hands on rings hanging just off the floor, the straight body lowering the chest between them. */
internal fun ringPushUp() = Motion.rep(Tempo.lowerFirst(), Camera(22f)) { p ->
    val ring = 0.16f
    val ankle = v(-0.85f, 0.185f)
    val line = LEGS + SPINE
    val top = asin((0.66f - ankle.y) / line)
    val bottom = asin((0.34f - ankle.y) / line)
    val angle = deg(lerp(top, bottom, p))
    plankBody(ankle, angle, nod = 8f)
    val handX = ankle.x + line * cos(top)
    for (side in Side.entries) {
        val hand = v(handX, ring, side.sign * lerp(0.25f, 0.22f, p))
        body.arm(side, hand, v(-1f, 0.2f, side.sign * 0.8f))
        gymRing(hand, top = 2.6f)
    }
}

/**
 * Hollow body rock: the hollow shape (lower back down, shoulders and straight legs off the floor,
 * arms by the ears) rocking from the upper back to the hips without changing.
 */
internal fun hollowBodyRock() = Motion.cycle(1.8f, camera = Camera(-26f, 8f)) { phase ->
    val rock = 11f * sin(phase * TAU)
    val basis = Basis.SUPINE.pitch(rock)
    val bend = 22f
    // Resting on the curve of the back: the lowest point of the back touches the floor.
    val trial = Rig().apply { torso(V3.ZERO, basis, bend = bend) }
    val low = listOf(
        trial.onHips(-0.11f, -0.02f),
        trial.onHips(-0.1f, BodySize.LOWER_SPINE),
        trial.onChest(-0.1f, -0.16f),
        trial.onChest(-0.09f, -0.04f),
    ).minOf { it.y }
    body.torso(v(0f, -low), basis, bend = bend, nod = 16f)
    for (side in Side.entries) {
        val legs = body.hips.dir(sin(rad(18f)), -cos(rad(18f)), side.sign * 0.03f)
        body.legAngles(side, legs, 3f, fold = body.hips.f, foot = pointedAlong(legs, body.hips.f, 34f))
        body.armAngles(side, body.chest.dir(0.3f, 0.95f, side.sign * 0.12f).unit(), 6f, fold = body.chest.f)
    }
    mat()
}

/**
 * Pseudo planche push-up: hands by the waist with the fingers turned out, the shoulders leaning well
 * past the hands; the chest lowers keeping the lean, the elbows going back along the body.
 */
internal fun pseudoPlanchePushUp() = Motion.rep(Tempo.lowerFirst(), Camera(24f)) { p ->
    val ankle = v(-0.85f, 0.185f)
    val line = LEGS + SPINE
    val top = asin((0.56f - ankle.y) / line)
    val bottom = asin((0.28f - ankle.y) / line)
    val angle = deg(lerp(top, bottom, p))
    plankBody(ankle, angle, nod = 4f)
    val handX = ankle.x + line * cos(top) - 0.24f
    for (side in Side.entries) body.arm(side, v(handX, BodySize.FIST_RADIUS, side.sign * 0.22f), v(-1f, 0.6f, side.sign * 0.25f))
}

/**
 * Clap push-up: down, an explosive press that lifts the hands off the floor for a clap under the
 * chest, a soft landing on bent elbows, and up again.
 */
internal fun clapPushUp() = Motion.cycle(2.2f, camera = Camera(30f, 6f)) { phase ->
    val t = phase * 2.2f
    val ankle = v(-0.85f, 0.185f)
    val line = LEGS + SPINE
    fun at(y: Float) = deg(asin((y - ankle.y) / line))
    val top = at(0.62f)
    val angle = ramp(t, 0.2f to top, 0.75f to at(0.27f), 0.98f to top, 1.18f to at(0.72f), 1.42f to top, 1.58f to at(0.52f), 1.85f to top)
    plankBody(ankle, angle, nod = 8f)
    val handX = ankle.x + line * cos(rad(top))
    val clap = ramp(t, 1.0f to 0f, 1.2f to 1f, 1.4f to 0f)
    for (side in Side.entries) {
        val planted = v(handX, BodySize.FIST_RADIUS, side.sign * 0.27f)
        val together = v(handX + 0.02f, BodySize.FIST_RADIUS + 0.16f, side.sign * 0.04f)
        body.arm(side, lerp(planted, together, clap), v(-1f, 0.2f, side.sign * 0.8f))
    }
}

/**
 * Hanging windshield wiper, seen from the front: hanging with the straight legs raised above the
 * hips, the hips turn to sweep the legs down to one side and across to the other.
 */
internal fun hangingWindshieldWiper() = Motion.cycle(3.4f, camera = Camera(64f, 12f)) { phase ->
    val turn = 55f * sin(phase * TAU)
    val half = 0.3f
    val shoulder = v(-0.02f, BAR - armReach(half - BodySize.SHOULDER_HALF, 0.02f, 0.985f))
    val basis = Basis.UPRIGHT.pitch(-14f).turn(turn)
    body.torso(shoulder - basis.u * SPINE, basis, twist = -turn, nod = 8f)
    for (side in Side.entries) body.arm(side, v(0f, BAR, side.sign * half), v(-0.1f, -1f, side.sign * 1f))
    for (side in Side.entries) bentLeg(side, 128f, 6f)
    pullUpBar(0f, BAR)
}
