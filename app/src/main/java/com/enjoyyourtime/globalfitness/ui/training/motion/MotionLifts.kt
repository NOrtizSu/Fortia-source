package com.enjoyyourtime.globalfitness.ui.training.motion

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

// Kettlebell, strongman and CrossFit-style lifts added in 1.8 (the Olympic variants are in
// MotionFunctional): the trap bar deadlift, kettlebell halo, bottoms-up press, double front squat and
// swings, carries, the renegade row, devil press, man maker and burpee box jump-over (laid out as key
// poses), the yoke, the atlas stone, the tyre flip and the sandbag to shoulder.

private const val TAU = 2 * PI.toFloat()

/** Hip joints to the shoulder line. */
private const val SPINE = BodySize.LOWER_SPINE + BodySize.UPPER_SPINE - BodySize.SHOULDER_DROP

/** Ankle to the hip joints of a straight leg, as the plank patterns use it. */
private const val LEGS = BodySize.THIGH + BodySize.SHIN - 0.01f

/** Middle of the feet: where a balanced load stays above. */
private const val MID_FOOT = 0.05f

// ---- Key poses -------------------------------------------------------------------------------------

/** A hand of a [BodyKey]: planted at a world point (a dumbbell on the floor), or held [at] relative to the shoulder line; z is always world z. */
internal class KeyHand(val at: V3, val planted: Boolean = false)

/** Both hands of a key, mirrored across the body: [at] is the near one. */
internal fun keyHands(at: V3, planted: Boolean = false): Map<Side, KeyHand> = mapOf(Side.NEAR to KeyHand(at, planted), Side.FAR to KeyHand(at.withZ(-at.z), planted))

/**
 * A key pose of a whole-body movement laid out in time (burpees and their relatives): pelvis, trunk
 * lean, the ankles (world, z added per side), the hands, the sole angle ([foot] 0 flat, negative on
 * the toes) and how high the feet [hop] on the way to the next key.
 */
internal class BodyKey(val time: Float, val pelvis: V3, val lean: Float, val ankle: V3, val hands: Map<Side, KeyHand>, val foot: Float = 0f, val hop: Float = 0f) {
    fun shoulders(): V3 = pelvis + Basis.UPRIGHT.pitch(lean).u * SPINE
}

/** The pose between key poses at one time; [air] is how far the feet are into a hop (0..1). */
internal class KeyFrame(val pelvis: V3, val lean: Float, val ankle: V3, val foot: Float, val lift: Float, val air: Float, val hands: Map<Side, V3>)

/**
 * The pose at [time] between [keys] (times increasing, the last equal in pose to the first). Hands
 * planted in both keys stay put; otherwise they swing around the shoulders (direction and reach
 * blended separately), so the arms never fold through the body on the way.
 */
internal fun blendKeys(keys: List<BodyKey>, time: Float): KeyFrame {
    var i = 1
    while (i < keys.size - 1 && keys[i].time < time) i++
    val a = keys[i - 1]
    val b = keys[i]
    val t = Ease.IN_OUT.apply(((time - a.time) / (b.time - a.time)).coerceIn(0f, 1f))
    val pelvis = lerp(a.pelvis, b.pelvis, t)
    val lean = lerp(a.lean, b.lean, t)
    val shoulders = pelvis + Basis.UPRIGHT.pitch(lean).u * SPINE
    val hands = Side.entries.associateWith { side ->
        val ha = a.hands.getValue(side)
        val hb = b.hands.getValue(side)
        if (ha.planted && hb.planted) {
            lerp(ha.at, hb.at, t)
        } else {
            val ra = if (ha.planted) ha.at - a.shoulders() else ha.at
            val rb = if (hb.planted) hb.at - b.shoulders() else hb.at
            val aa = angleXY(ra)
            var ab = angleXY(rb)
            if (ab - aa > 180f) ab -= 360f else if (aa - ab > 180f) ab += 360f
            val reach = lerp(ra.withZ(0f).length, rb.withZ(0f).length, t)
            (shoulders + dirXY(lerp(aa, ab, t)) * reach).withZ(lerp(ha.at.z, hb.at.z, t))
        }
    }
    val bump = 4 * t * (1 - t)
    return KeyFrame(pelvis, lean, lerp(a.ankle, b.ankle, t), lerp(a.foot, b.foot, t), a.hop * bump, if (a.hop > 0f) bump else 0f, hands)
}

/**
 * Places the body of a [KeyFrame]: the trunk, the feet [feet] apart (pointed while they hop, never
 * below the floor) and the arms to the hands, elbows towards [elbows].
 */
internal fun FrameBuilder.keyedBody(k: KeyFrame, feet: Float = 0.24f, elbows: (Side) -> V3 = { v(-1f, -0.2f, it.sign * 0.5f) }) {
    body.torso(k.pelvis, Basis.UPRIGHT.pitch(k.lean), nod = -k.lean * 0.4f)
    val point = lerp(k.foot, -72f, k.air)
    val foot = Foot(dirXY(point), dirXY(point + 90f))
    // Knees forward when standing or squatting, towards the floor in a plank (on the toes).
    val plank = ((-k.foot - 40f) / 25f).coerceIn(0f, 1f)
    for (side in Side.entries) {
        val pole = lerp(v(1f, 0.2f, side.sign * 0.2f), v(0.2f, -1f, side.sign * 0.1f), plank)
        body.leg(side, footAbove(k.ankle + V3.Y * k.lift, foot).withZ(side.sign * feet / 2), pole, foot)
        // Raised past the shoulder (a press, a snatch), the elbows turn to point forward and out.
        val hand = k.hands.getValue(side)
        val overhead = ((hand.y - body.shoulder(side).y) / 0.3f).coerceIn(0f, 1f)
        body.arm(side, hand, lerp(elbows(side), v(0.3f, 0f, side.sign * 1f), overhead))
    }
}

/** The plank of the burpee family: on the toes at [PLANK_ANKLE], the body rising 17° to the shoulders. */
private val PLANK_ANKLE = v(-0.86f, 0.185f)
private val PLANK_PELVIS = PLANK_ANKLE + dirXY(17f) * LEGS
private const val PLANK_LEAN = 73f
private const val PLANK_FOOT = 17f - 85f

/** The feet of the plank with the chest lowered to the floor (the body 7° up from the toes). */
private const val CHEST_DOWN_FOOT = 7f - 85f
private val FLAT = v(0f, BodySize.ANKLE)
private val STANDING = v(0f, BodySize.STAND)

/** Height of a dumbbell's handle lying on the floor. */
private const val DB_FLOOR = 0.07f

/**
 * [target], or the point an arm from [shoulder] reaches towards it when it is further than a nearly
 * straight arm (the hands keep pushing on a tyre or reaching for a bag as it moves off).
 */
internal fun within(shoulder: V3, target: V3, reach: Float = (BodySize.UPPER_ARM + BodySize.FOREARM) * 0.975f): V3 {
    val d = target - shoulder
    return if (d.length <= reach) target else shoulder + d.unit() * reach
}

// ---- Trap bar, kettlebells ---------------------------------------------------------------------------

/** A hexagonal (trap) bar at handle height [at]: the frame round the lifter, the handles at the sides, plates on the sleeves. */
private fun FrameBuilder.trapBar(at: V3, handZ: Float) {
    val r = 0.38f
    val hex = listOf(v(r, 0f, 0f), v(r / 2, 0f, 0.34f), v(-r / 2, 0f, 0.34f), v(-r, 0f, 0f), v(-r / 2, 0f, -0.34f), v(r / 2, 0f, -0.34f), v(r, 0f, 0f))
    add(Cord(hex.map { at.withZ(0f) + it }, 0.028f, Ink.METAL, bias = -0.06f))
    for (s in listOf(-1f, 1f)) {
        add(Rod(at + v(-0.12f, 0f, s * handZ), at + v(0.12f, 0f, s * handZ), 0.018f, Ink.METAL, bias = -0.04f))
        for (x in listOf(-0.12f, 0.12f)) add(Rod(at + v(x, 0f, s * handZ), at + v(x, 0f, s * 0.34f), 0.014f, Ink.METAL, bias = -0.05f))
        add(Rod(at.withZ(s * 0.34f), at.withZ(s * 0.66f), Kit.BAR_RADIUS * 1.35f, bias = -0.04f, halo = false))
        for (i in 0 until 2) add(Disc(at.withZ(s * (0.42f + i * 0.055f)), V3.Z, if (i == 0) Kit.PLATE_RADIUS + 0.025f else 0.16f, 0.045f, Ink.PLATE, hub = 0.035f, bias = -0.03f))
    }
}

/**
 * Trap bar deadlift: standing inside the hexagonal bar, the handles at the sides; the knees and hips
 * extend together, the trunk more upright than in the conventional deadlift.
 */
internal fun trapBarDeadlift() = Motion.rep(Track.rep(go = 1.0f, hold = 0.35f, back = 1.05f, rest = 0.45f), Camera(28f, 8f)) { p ->
    val handZ = 0.29f
    val barY = lerp(Kit.PLATE_RADIUS + 0.025f, 0.8f, Ease.IN_OUT.apply(p))
    hingeTo(v(0.02f, barY), lerp(44f, -2f, p), ahead = lerp(0.05f, -0.02f, p), handZ = handZ)
    body.standOn(-0.02f, width = 0.28f, out = 8f, pole = v(1f, 0.1f, 0.15f))
    for (side in Side.entries) body.arm(side, v(0.02f, barY, side.sign * handZ), v(-0.3f, 0.2f, side.sign * 1f))
    trapBar(v(0.02f, barY), handZ)
}

/** Kettlebell halo: the bell held upside down by the horns circles round the head at ear height, the trunk still. */
internal fun kettlebellHalo() = Motion.cycle(3.2f, camera = Camera(48f, 12f)) { phase ->
    body.torso(v(0f, BodySize.STAND - 0.005f), Basis.UPRIGHT.pitch(2f), nod = -3f)
    body.standOn(0f, width = 0.3f, out = 10f, pole = v(1f, 0.1f, 0.2f))
    val a = phase * TAU
    val radial = v(cos(a), 0f, sin(a))
    val tangent = v(-sin(a), 0f, cos(a))
    val grip = body.neck + v(0.015f, 0.1f, 0f) + radial * 0.27f
    // The elbows point away from the bell: down and out while it is in front of the face, forward and up while it is
    // behind the head, and the arm on the bell's side points its elbow down while the other one reaches across.
    for (side in Side.entries) {
        val pole = v(
            0.2f - 0.6f * radial.x,
            0.4f * maxOf(0f, -radial.x) - 0.5f * maxOf(0f, radial.x) - 0.4f * maxOf(0f, side.sign * radial.z),
            side.sign * 0.6f - 0.4f * radial.z,
        )
        body.arm(side, grip + tangent * (side.sign * 0.045f), pole)
    }
    kettlebell(grip, down = V3.Y, across = tangent, radius = 0.095f)
}

/** Kettlebell bottoms-up press: the bell balanced upside down above the fist, pressed from the shoulder to overhead. */
internal fun kettlebellBottomsUpPress() = Motion.rep(Track.rep(go = 1.1f, hold = 0.3f, back = 1.3f, rest = 0.5f), Camera(26f)) { p ->
    body.torso(v(0f, BodySize.STAND), Basis.UPRIGHT.pitch(2f), nod = -2f)
    body.standOn(0f, width = 0.4f, out = 12f, pole = v(1f, 0.1f, 0.3f))
    val s = body.shoulder(Side.NEAR)
    val rack = s + v(0.19f, -0.11f, -0.03f)
    val overhead = s + v(-0.02f, 0.565f, 0.02f)
    val hand = lerp(rack, overhead, Ease.IN_OUT.apply(p))
    body.arm(Side.NEAR, hand, v(0.2f, -1f, lerp(0.3f, 1f, p)))
    kettlebell(hand, down = v(0.04f, 1f, 0f), radius = 0.09f)
    body.armAngles(Side.FAR, limbDir(body.chest, Side.FAR, 10f, 24f), 12f, fold = body.chest.f)
}

/** Double kettlebell front squat: two bells in the rack, resting on the forearms, a squat between the heels. */
internal fun doubleKettlebellFrontSquat() = Motion.rep(Tempo.lowerFirst(), Camera(26f)) { p ->
    val lean = lerp(4f, 22f, p)
    val basis = Basis.UPRIGHT.pitch(lean)
    val rack = basis.u * (BodySize.LOWER_SPINE + BodySize.UPPER_SPINE) + basis.dir(0.19f, -0.06f, 0f)
    val pelvis = v(MID_FOOT - rack.x, lerp(0.915f, 0.47f, p))
    body.torso(pelvis, basis, nod = -lean * 0.45f)
    body.standOn(0f, width = 0.36f, out = 18f, pole = v(1f, 0.1f, 0.3f))
    for (side in Side.entries) {
        val hand = body.onChest(0.19f, -0.06f, side.sign * 0.09f)
        body.arm(side, hand, basis.dir(0.3f, -1f, side.sign * 0.4f))
        kettlebell(hand, down = basis.dir(-0.2f, -0.5f, side.sign * 0.85f), across = basis.f, radius = 0.09f)
    }
}

/** One-arm kettlebell swing: the near hand swings the bell from between the legs to chest height with a hip snap, the far arm alongside. */
internal fun singleArmKettlebellSwing() = Motion.rep(
    Track.rep(go = 0.55f, hold = 0.12f, back = 0.6f, rest = 0.1f, goEase = Ease.OUT, backEase = Ease.IN),
    Camera(24f),
) { p ->
    val lean = lerp(55f, -3f, p)
    body.torso(v(lerp(-0.24f, 0.01f, p), lerp(0.79f, 0.915f, p)), Basis.UPRIGHT.pitch(lean), nod = -lean * 0.5f)
    body.standOn(0f, width = 0.42f, out = 14f, pole = v(1f, 0.1f, 0.35f))
    val swing = lerp(-28f, 92f, p)
    val dir = dirXY(swing - 90f)
    val grip = body.shoulder(Side.NEAR) + dir * 0.555f - V3.Z * 0.1f
    body.arm(Side.NEAR, grip, dirXY(swing + 180f) + V3.Z * 0.3f)
    body.swingArm(Side.FAR, lerp(-20f, 60f, p), out = 12f, elbow = 20f)
    kettlebell(grip, down = dir)
}

/**
 * American kettlebell swing: the same hip snap, but the bell carries on up on straight arms until it
 * is overhead, bottom up, then falls back through the arc between the legs.
 */
internal fun americanKettlebellSwing() = Motion.rep(
    Track.rep(go = 0.8f, hold = 0.15f, back = 0.8f, rest = 0.1f, goEase = Ease.OUT, backEase = Ease.IN),
    Camera(24f),
) { p ->
    val hips = Ease.IN_OUT.apply((p / 0.6f).coerceIn(0f, 1f))
    val lean = lerp(55f, -3f, hips)
    body.torso(v(lerp(-0.24f, 0.01f, hips), lerp(0.79f, 0.915f, hips)), Basis.UPRIGHT.pitch(lean), nod = -lean * 0.5f)
    body.standOn(0f, width = 0.42f, out = 14f, pole = v(1f, 0.1f, 0.35f))
    val swing = lerp(-28f, 176f, p)
    val dir = dirXY(swing - 90f)
    val grip = body.shoulders() + dir * 0.56f
    for (side in Side.entries) body.arm(side, grip.withZ(side.sign * 0.045f), dirXY(swing + 180f) + V3.Z * (side.sign * 0.3f))
    kettlebell(grip, down = dir)
}

/** Overhead carry: walking tall with a kettlebell locked out overhead in the near hand, the far arm swinging. */
internal fun overheadCarry() = Motion.cycle(2.2f, cycles = 2, camera = Camera(26f)) { phase ->
    val bob = cos(phase * TAU * 2)
    body.torso(v(0f, 0.875f + 0.012f * bob), Basis.UPRIGHT.pitch(1f), nod = -2f)
    for (side in Side.entries) gaitLeg(side, (phase + if (side == Side.NEAR) 0f else 0.5f) % 1f, reach = 0.24f, lift = 0.07f, stance = 0.6f)
    val top = body.shoulder(Side.NEAR) + v(-0.01f, 0.565f, 0.02f)
    body.arm(Side.NEAR, top, v(0.3f, 0f, 1f))
    kettlebell(top, down = v(-0.3f, -0.5f, 0.1f), radius = 0.1f)
    body.hang(Side.FAR, forward = 14f * sin((phase + 0.5f) * TAU), out = 8f)
}

/** Renegade row: in a wide-footed plank on two dumbbells, each dumbbell rowed to the hip in turn while the hips stay level. */
internal fun renegadeRow() = Motion.cycle(3.2f, camera = Camera(34f, 12f)) { phase ->
    val t = phase * 3.2f
    val line = LEGS + SPINE
    val angle = deg(asin((0.64f - PLANK_ANKLE.y) / line))
    // Wide feet: the straight legs reach less far along the body.
    val feet = 0.3f
    val legs = sqrt(LEGS * LEGS - (feet - BodySize.HIP_HALF) * (feet - BodySize.HIP_HALF))
    body.torso(PLANK_ANKLE + dirXY(angle) * legs, Basis.UPRIGHT.pitch(90f - angle), nod = 8f)
    for (side in Side.entries) body.leg(side, PLANK_ANKLE.withZ(side.sign * feet), v(0f, -1f, 0f), onToes(angle))
    val handX = PLANK_ANKLE.x + line * cos(rad(angle))
    for (side in Side.entries) {
        val start = if (side == Side.NEAR) 0.3f else 1.9f
        val row = ramp(t, start to 0f, start + 0.45f to 1f, start + 0.65f to 1f, start + 1.1f to 0f)
        val floor = v(handX, DB_FLOOR, side.sign * 0.24f)
        val hip = body.onHips(0.06f, 0.2f, side.sign * 0.22f)
        val hand = lerp(floor, hip, row)
        body.arm(side, hand, v(-0.4f, 1f, side.sign * 0.3f))
        dumbbell(hand, axis = V3.X)
    }
}

// ---- Burpee family -----------------------------------------------------------------------------------

/** Dumbbells planted on the floor beside the hands' plank position. */
private fun dbFloor(x: Float = 0.3f): Map<Side, KeyHand> = keyHands(v(x, DB_FLOOR, 0.22f), planted = true)

/**
 * Devil press: dumbbells on the floor, a burpee with the hands on them (chest to the floor), the feet
 * jump in, and in one swing from between the legs both dumbbells go all the way overhead; then down.
 */
internal fun devilPress(): Motion {
    val atSides = keyHands(v(0.03f, -0.555f, 0.22f))
    val keys = listOf(
        BodyKey(0f, STANDING, 3f, FLAT, atSides),
        BodyKey(0.45f, v(-0.2f, 0.48f), 72f, FLAT, dbFloor(), hop = 0.1f),
        BodyKey(0.8f, PLANK_PELVIS, PLANK_LEAN, PLANK_ANKLE, dbFloor(), PLANK_FOOT),
        BodyKey(1.15f, PLANK_ANKLE + dirXY(7f) * LEGS, 83f, PLANK_ANKLE, dbFloor(), CHEST_DOWN_FOOT),
        BodyKey(1.45f, PLANK_PELVIS, PLANK_LEAN, PLANK_ANKLE, dbFloor(), PLANK_FOOT, hop = 0.1f),
        BodyKey(1.8f, v(-0.2f, 0.48f), 76f, FLAT, dbFloor()),
        BodyKey(2.15f, v(-0.25f, 0.62f), 58f, FLAT, keyHands(v(-0.12f, -0.5f, 0.15f))),
        BodyKey(2.55f, v(0f, 0.93f), -2f, FLAT, keyHands(v(0.38f, 0.1f, 0.2f))),
        BodyKey(2.8f, STANDING, 0f, FLAT, keyHands(v(-0.02f, 0.56f, 0.2f))),
        BodyKey(3.1f, STANDING, 0f, FLAT, keyHands(v(-0.02f, 0.56f, 0.2f))),
        BodyKey(3.6f, STANDING, 2f, FLAT, keyHands(v(0.3f, -0.25f, 0.21f))),
        BodyKey(4.0f, STANDING, 3f, FLAT, atSides),
    )
    return Motion.cycle(4.0f, camera = Camera(24f)) { phase ->
        val k = blendKeys(keys, phase * 4.0f)
        keyedBody(k, feet = 0.26f)
        for (side in Side.entries) dumbbell(k.hands.getValue(side), axis = V3.X)
    }
}

/**
 * Man maker: from a plank on two dumbbells, a push-up, a row with each arm, the feet jump in, the
 * dumbbells are cleaned to the shoulders and pressed overhead; then down to the floor and back to the plank.
 */
internal fun manMaker(): Motion {
    val rack = keyHands(v(0.14f, -0.06f, 0.21f))
    val rowed = KeyHand(v(-0.24f, -0.25f, 0.24f))
    val floor = dbFloor()
    fun rowing(side: Side) = floor + (side to if (side == Side.NEAR) rowed else KeyHand(rowed.at.withZ(-rowed.at.z)))
    val squat = v(-0.2f, 0.48f)
    val keys = listOf(
        BodyKey(0f, PLANK_PELVIS, PLANK_LEAN, PLANK_ANKLE, floor, PLANK_FOOT),
        BodyKey(0.45f, PLANK_ANKLE + dirXY(7f) * LEGS, 83f, PLANK_ANKLE, floor, CHEST_DOWN_FOOT),
        BodyKey(0.9f, PLANK_PELVIS, PLANK_LEAN, PLANK_ANKLE, floor, PLANK_FOOT),
        BodyKey(1.2f, PLANK_PELVIS, PLANK_LEAN, PLANK_ANKLE, rowing(Side.NEAR), PLANK_FOOT),
        BodyKey(1.5f, PLANK_PELVIS, PLANK_LEAN, PLANK_ANKLE, floor, PLANK_FOOT),
        BodyKey(1.8f, PLANK_PELVIS, PLANK_LEAN, PLANK_ANKLE, rowing(Side.FAR), PLANK_FOOT),
        BodyKey(2.1f, PLANK_PELVIS, PLANK_LEAN, PLANK_ANKLE, floor, PLANK_FOOT, hop = 0.1f),
        BodyKey(2.5f, squat, 72f, FLAT, floor),
        BodyKey(3.0f, STANDING, 3f, FLAT, rack),
        BodyKey(3.5f, STANDING, 0f, FLAT, keyHands(v(-0.02f, 0.56f, 0.2f))),
        BodyKey(3.75f, STANDING, 0f, FLAT, keyHands(v(-0.02f, 0.56f, 0.2f))),
        BodyKey(4.2f, STANDING, 3f, FLAT, rack),
        BodyKey(4.9f, squat, 72f, FLAT, floor, hop = 0.1f),
        BodyKey(5.4f, PLANK_PELVIS, PLANK_LEAN, PLANK_ANKLE, floor, PLANK_FOOT),
        BodyKey(5.8f, PLANK_PELVIS, PLANK_LEAN, PLANK_ANKLE, floor, PLANK_FOOT),
    )
    return Motion.cycle(5.8f, camera = Camera(26f, 6f)) { phase ->
        val k = blendKeys(keys, phase * 5.8f)
        keyedBody(k, feet = 0.3f) { side -> v(-1f, 0.2f, side.sign * 0.5f) }
        for (side in Side.entries) dumbbell(k.hands.getValue(side), axis = V3.X)
    }
}

/**
 * Burpee box jump-over, the lateral style, seen from the front: side-on to a box, a burpee, then a
 * jump sideways over the box with the knees tucked, a burpee on the other side and a jump back. The
 * picture follows the athlete, so it is the box that passes under the jump.
 */
internal fun burpeeBoxJumpOver(): Motion {
    val floorHands = keyHands(v(0.3f, BodySize.FIST_RADIUS, 0.26f), planted = true)
    val down = keyHands(v(0.06f, -0.5f, 0.26f))
    val squat = v(-0.2f, 0.48f)
    val keys = listOf(
        BodyKey(0f, STANDING, 3f, FLAT, down),
        BodyKey(0.35f, squat, 78f, FLAT, floorHands, hop = 0.1f),
        BodyKey(0.7f, PLANK_PELVIS, PLANK_LEAN, PLANK_ANKLE, floorHands, PLANK_FOOT),
        BodyKey(0.85f, PLANK_PELVIS, PLANK_LEAN, PLANK_ANKLE, floorHands, PLANK_FOOT, hop = 0.1f),
        BodyKey(1.2f, squat, 78f, FLAT, floorHands),
        BodyKey(1.6f, STANDING, 3f, FLAT, down),
        BodyKey(1.75f, v(-0.08f, 0.7f), 26f, FLAT, keyHands(v(-0.12f, -0.48f, 0.26f))),
        BodyKey(2.0f, v(0f, 1.02f), 6f, v(0f, 0.2f), keyHands(v(0.3f, -0.2f, 0.26f)), -40f),
        BodyKey(2.2f, v(0.03f, 1.2f), 16f, v(0.1f, 0.66f), keyHands(v(0.36f, 0.02f, 0.26f)), -30f),
        BodyKey(2.4f, v(0f, 0.98f), 8f, v(0.02f, 0.2f), keyHands(v(0.32f, -0.12f, 0.26f)), -30f),
        BodyKey(2.6f, v(-0.08f, 0.7f), 24f, FLAT, keyHands(v(0.25f, -0.3f, 0.26f))),
        BodyKey(3.0f, STANDING, 3f, FLAT, down),
    )
    return Motion.cycle(6.0f, camera = Camera(76f, 10f)) { phase ->
        val t = phase * 6.0f
        keyedBody(blendKeys(keys, t % 3.0f))
        // The box is beside the athlete, on the near side, then (after the jump) on the far side, and back.
        val z = ramp(t, 2.0f to BOX_SIDE, 2.4f to -BOX_SIDE, 5.0f to -BOX_SIDE, 5.4f to BOX_SIDE)
        box(v(-0.3f, 0f, z - 0.2f), v(0.3f, 0.45f, z + 0.2f), Ink.PAD, fit = false, bias = -0.3f)
    }
}

/** How far the box stands beside the athlete. */
private const val BOX_SIDE = 0.5f

// ---- Strongman ---------------------------------------------------------------------------------------

/**
 * Yoke carry: the crossbar of the loaded frame across the upper back, the hands on its uprights,
 * walking with short quick steps and an upright trunk.
 */
internal fun yokeCarry() = Motion.cycle(1.6f, cycles = 2, camera = Camera(30f, 8f)) { phase ->
    val bob = cos(phase * TAU * 2)
    val basis = Basis.UPRIGHT.pitch(6f)
    body.torso(v(-0.02f, 0.86f + 0.01f * bob), basis, nod = -4f, shrug = 0.02f)
    for (side in Side.entries) gaitLeg(side, (phase + if (side == Side.NEAR) 0f else 0.5f) % 1f, reach = 0.16f, lift = 0.05f, stance = 0.65f)
    val bar = body.neck + basis.dir(-0.11f, -0.04f, 0f)
    val upright = 0.5f
    for (side in Side.entries) {
        val top = bar.withZ(side.sign * upright)
        add(Rod(top + V3.Y * 0.15f, v(top.x, 0.18f, top.z), 0.035f, Ink.FRAME, bias = if (side == Side.NEAR) 0.1f else -0.2f))
        add(Disc(v(top.x, 0.3f, side.sign * (upright + 0.08f)), V3.Z, Kit.PLATE_RADIUS, 0.05f, Ink.PLATE, hub = 0.035f, bias = if (side == Side.NEAR) 0.1f else -0.2f))
        body.arm(side, bar.withZ(side.sign * (upright - 0.02f)) - V3.Y * 0.18f, v(-0.3f, -1f, side.sign * 0.5f))
    }
    add(Rod(bar.withZ(-upright), bar.withZ(upright), 0.03f, Ink.FRAME, bias = -0.05f))
    groundMarks(phase, 4 * 0.16f / 0.65f)
}

/**
 * Atlas stone: squatting over the stone with the arms round it, the stone is lapped onto the thighs,
 * the hips drive up hugging it to the chest and roll it onto the platform; then it is taken back
 * down and set on the floor.
 */
internal fun atlasStone() = Motion.cycle(4.8f, camera = Camera(30f, 6f)) { phase ->
    val t = phase * 4.8f
    val r = 0.23f
    val floor = v(0.14f, r)
    val lap = v(0.04f, 0.6f)
    val chest = v(0.22f, 1.04f)
    val platform = v(0.66f, 1.0f + r)
    val stone = vramp(t, 0.3f to floor, 1.0f to lap, 1.3f to lap, 1.75f to chest, 2.05f to platform, 2.4f to platform, 3.05f to chest, 3.7f to lap, 4.3f to floor)
    val pelvis = vramp(t, 0.3f to v(-0.2f, 0.47f), 1.0f to v(-0.32f, 0.5f), 1.3f to v(-0.3f, 0.52f), 1.75f to v(0.0f, 0.9f), 2.05f to v(0.12f, 0.88f), 2.4f to v(0.12f, 0.88f), 3.05f to v(0.0f, 0.9f), 3.7f to v(-0.3f, 0.52f), 4.3f to v(-0.2f, 0.47f))
    val lean = ramp(t, 0.3f to 66f, 1.0f to 44f, 1.3f to 40f, 1.75f to -8f, 2.05f to 8f, 2.4f to 8f, 3.05f to -4f, 3.7f to 40f, 4.3f to 66f)
    body.torso(pelvis, Basis.UPRIGHT.pitch(lean), nod = -lean * 0.4f)
    body.standOn(0f, width = 0.52f, out = 22f, pole = v(1f, 0.1f, 0.6f))
    for (side in Side.entries) body.arm(side, stone + v(-0.04f, -0.09f, side.sign * 0.2f), v(-0.5f, -0.3f, side.sign * 1f))
    add(Ball(stone, r, Ink.PLATE, bias = 0.02f))
    box(v(0.42f, 0f, -0.36f), v(0.95f, 1.0f, 0.36f), Ink.PAD, fit = false, bias = -0.4f)
}

/**
 * Tyre flip: fingers under the near edge in a deep squat, the legs drive the tyre up and forward,
 * the hands switch to push it over past upright and it falls flat; the lifter steps up to it (the
 * ground slides back as in a walk) and squats for the next flip.
 */
internal fun tyreFlip() = Motion.cycle(5.0f, camera = Camera(28f, 6f)) { phase ->
    val t = phase * 5.0f
    val radius = 0.6f
    val thick = 0.24f
    val near = 0.18f
    val travel = 2 * radius + thick
    // World positions; the picture moves with the lifter by [shift].
    val shift = ramp(t, 1.5f to 0f, 2.2f to 0.6f, 2.6f to 0.75f, 3.9f to travel)
    val flip = ramp(t, 0.4f to 0f, 1.3f to 50f, 2.1f to 92f) + 88f * Ease.IN.apply(((t - 2.1f) / 0.4f).coerceIn(0f, 1f))
    val pivot = v(near + 2 * radius, 0f)
    val a = rad(flip.coerceAtMost(90f))
    fun turned(x: Float, y: Float, angle: Float) = v(x * cos(angle) + y * sin(angle), -x * sin(angle) + y * cos(angle))
    val center = if (flip <= 90f) {
        pivot + turned(-radius, thick / 2, a)
    } else {
        v(pivot.x + thick, 0f) + turned(-thick / 2, radius, rad(flip - 90f))
    }
    val axis = turned(0f, 1f, rad(flip))
    add(Disc(center - V3.X * shift, axis, radius, thick, Ink.PLATE, hub = 0.32f, bias = -0.1f))
    // The lifter, in world terms.
    val px = ramp(t, 0.4f to -0.3f, 1.3f to 0.15f, 1.45f to 0.18f, 2.2f to 0.85f, 2.6f to 0.85f, 3.75f to 1.2f, 4.6f to -0.3f + travel)
    val py = ramp(t, 0.4f to 0.4f, 1.3f to 0.8f, 1.45f to 0.8f, 2.2f to 0.84f, 2.6f to 0.86f, 3.75f to 0.86f, 4.6f to 0.4f)
    val lean = ramp(t, 0.4f to 70f, 1.3f to 24f, 1.5f to 22f, 2.2f to 14f, 2.6f to 6f, 3.9f to 6f, 4.6f to 70f)
    body.torso(v(px - shift, py), Basis.UPRIGHT.pitch(lean), nod = -lean * 0.4f)
    // Feet: planted in world terms, stepping one at a time.
    fun step(t0: Float, t1: Float, from: Float, to: Float): Pair<Float, Float> {
        val s = ((t - t0) / (t1 - t0)).coerceIn(0f, 1f)
        return lerp(from, to, Ease.IN_OUT.apply(s)) to 0.08f * sin(PI.toFloat() * s)
    }
    val start = -0.05f
    val nearFoot = when {
        t < 1.8f -> step(1.45f, 1.75f, start, 0.5f)
        t < 3.2f -> step(2.65f, 3.0f, 0.5f, 1.0f)
        else -> step(3.3f, 3.7f, 1.0f, start + travel)
    }
    val farFoot = if (t < 2.5f) step(1.72f, 2.05f, start, 0.62f) else step(2.95f, 3.35f, 0.62f, start + travel)
    for ((side, foot) in listOf(Side.NEAR to nearFoot, Side.FAR to farFoot)) {
        body.stand(side, foot.first - shift, z = side.sign * 0.15f, floor = foot.second, out = 10f, pole = v(1f, 0.15f, side.sign * 0.2f))
    }
    // Hands: under the near edge while lifting, then on the near face pushing, then free.
    val edge = pivot + turned(-2 * radius, 0f, a) + v(0.02f, 0.04f)
    val face = pivot + turned(-lerp(1.0f, 0.62f, ((flip - 50f) / 42f).coerceIn(0f, 1f)), -0.05f, a)
    val push = Ease.IN_OUT.apply(((t - 1.3f) / 0.2f).coerceIn(0f, 1f))
    val free = Ease.IN_OUT.apply(((t - 2.1f) / 0.4f).coerceIn(0f, 1f)) * (1f - Ease.IN_OUT.apply(((t - 3.9f) / 0.7f).coerceIn(0f, 1f)))
    val shoulders = body.shoulders()
    val idle = shoulders + v(0.32f, -0.38f)
    val reachEdge = v(near + travel + 0.02f, 0.04f) - V3.X * shift
    for (side in Side.entries) {
        val contact = if (t < 3.9f) lerp(edge, face, push) - V3.X * shift else reachEdge
        val hand = lerp(contact, idle, free).withZ(side.sign * 0.28f)
        body.arm(side, within(body.shoulder(side), hand), v(-0.4f, -1f, side.sign * 0.6f))
    }
    val spacing = 0.48f
    var x = 1.6f - shift % spacing
    while (x > -1.4f) {
        add(Rod(v(x - 0.08f, 0.006f, 0.4f), v(x, 0.006f, 0.4f), 0.009f, Ink.FRAME, fit = false, bias = BEHIND))
        x -= spacing
    }
}

/**
 * Sandbag to shoulder: squatting over the bag with the arms round it, it is lapped, then the hips
 * drive it up onto the near shoulder; held, then dropped in front and picked up again.
 */
internal fun sandbagToShoulder() = Motion.cycle(4.2f, camera = Camera(30f, 6f)) { phase ->
    val t = phase * 4.2f
    val floor = v(0.14f, 0.14f)
    val lap = v(0.04f, 0.56f)
    val shoulder = v(0.0f, 1.54f, 0.14f)
    val bag = vramp(t, 0.3f to floor, 0.95f to lap, 1.25f to lap, 1.7f to shoulder, 2.1f to shoulder)
    val fall = Ease.IN.apply(((t - 2.1f) / 0.45f).coerceIn(0f, 1f))
    val held = if (t < 2.1f) bag else lerp(shoulder, floor, fall)
    val turn = ramp(t, 1.25f to 0f, 1.7f to 1f, 2.1f to 1f, 2.55f to 0f)
    val axis = lerp(V3.Z, V3.X, turn).unit()
    val pelvis = vramp(t, 0.3f to v(-0.26f, 0.42f), 0.95f to v(-0.3f, 0.5f), 1.25f to v(-0.28f, 0.52f), 1.7f to v(0f, 0.92f), 2.1f to v(0f, 0.92f), 2.7f to v(-0.05f, 0.86f), 3.6f to v(-0.26f, 0.42f))
    val lean = ramp(t, 0.3f to 70f, 0.95f to 40f, 1.25f to 36f, 1.7f to 0f, 2.1f to 0f, 2.7f to 10f, 3.6f to 70f)
    body.torso(pelvis, Basis.UPRIGHT.pitch(lean), nod = -lean * 0.4f)
    body.standOn(0f, width = 0.46f, out = 18f, pole = v(1f, 0.1f, 0.4f))
    // The hands let go of the bag as it drops from the shoulder and take it again on the floor.
    val release = ramp(t, 2.05f to 0f, 2.25f to 1f, 3.2f to 1f, 3.6f to 0f)
    val shoulders = body.shoulders()
    for (side in Side.entries) {
        val grip = lerp(v(-0.04f, -0.09f, side.sign * 0.2f), if (side == Side.NEAR) v(0.24f, -0.02f, 0f) else v(0.12f, -0.1f, -0.16f), turn)
        val idle = shoulders + v(0.25f, -0.45f, side.sign * 0.2f)
        body.arm(side, within(body.shoulder(side), lerp(held + grip, idle, release)), v(-0.5f, -0.4f, side.sign * 1f))
    }
    add(Rod(held - axis * 0.26f, held + axis * 0.26f, 0.14f, Ink.PAD, bias = 0.02f))
}
