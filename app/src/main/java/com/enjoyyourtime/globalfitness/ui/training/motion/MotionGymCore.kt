package com.enjoyyourtime.globalfitness.ui.training.motion

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

// Core, neck and full-body patterns added with the larger catalog: anti-rotation and rotation work,
// kneeling (all fours) drills, floor core exercises, neck work and holds. They reuse the shared
// helpers and leave the original patterns untouched.

private const val TAU = 2 * PI.toFloat()

/** Height of the hip joints of a figure on all fours (knees on the floor under the hips). */
private const val ALL_FOURS_HIP = BodySize.KNEE_RADIUS + 0.004f + BodySize.THIGH

/**
 * On all fours: knees under the hips with the toes tucked, hands on the floor under the shoulders
 * (arms [reach] long), the trunk rising slightly towards the head. The upper spine [bend]s (positive:
 * rounds up) and [twist]s; the lower spine tilts [tilt] degrees. The [free] arm is left for the
 * pattern to place. Returns where the hands are planted; patterns that move a leg place it again.
 */
internal fun FrameBuilder.allFours(bend: Float = 0f, twist: Float = 0f, tilt: Float = 0f, nod: Float = 0f, reach: Float = 0.55f, free: Side? = null): Map<Side, V3> {
    val spine = BodySize.LOWER_SPINE + BodySize.UPPER_SPINE - BodySize.SHOULDER_DROP
    val rise = deg(asin((BodySize.FIST_RADIUS + reach - ALL_FOURS_HIP) / spine))
    val pelvis = v(0f, ALL_FOURS_HIP)
    body.torso(pelvis, Basis.UPRIGHT.pitch(90f - rise + tilt), bend = bend, twist = twist, nod = nod)
    for (side in Side.entries) {
        val knee = v(0f, BodySize.KNEE_RADIUS + 0.004f, side.sign * BodySize.HIP_HALF)
        body.leg(side, knee + v(-0.42f, 0.11f, 0f), v(1f, -1.5f, 0f), Foot(dirXY(250f), dirXY(340f)))
    }
    val shoulderX = spine * cos(rad(rise))
    val hands = Side.entries.associateWith { v(shoulderX, BodySize.FIST_RADIUS, it.sign * 0.18f) }
    for (side in Side.entries) if (side != free) body.arm(side, hands.getValue(side), v(-1f, 0.2f, side.sign * 0.5f))
    mat()
    return hands
}

/**
 * A leg of a figure on all fours lifting from the kneeling position ([lift] 0) to where its thigh
 * points at [thigh] degrees (XY plane) with the knee bent [knee] degrees; the foot keeps its angle to
 * the shin (toes tucked when kneeling, flexed in the air).
 */
internal fun FrameBuilder.allFoursLeg(side: Side, lift: Float, thigh: Float, knee: Float) {
    val t = lerp(270f, thigh, lift)
    val k = lerp(105f, knee, lift)
    val shin = t - k
    body.legAngles(side, dirXY(t), k, fold = dirXY(t - 90f), foot = Foot(dirXY(shin + 85f), dirXY(shin + 175f)))
}

/** Donkey kick: on all fours, the near knee bent 90°, driving the sole up towards the ceiling. */
internal fun donkeyKick() = Motion.rep(Tempo.liftFirst(), Camera(24f)) { p ->
    allFours()
    allFoursLeg(Side.NEAR, Ease.IN_OUT.apply(p), thigh = 172f, knee = 90f)
}

/** Bird dog: on all fours, the near arm reaches forward while the far leg extends back, both level. */
internal fun birdDog() = Motion.rep(Track.rep(go = 1.1f, hold = 0.6f, back = 1.0f, rest = 0.5f), Camera(30f, 8f)) { p ->
    val hands = allFours(free = Side.NEAR)
    val s = body.shoulder(Side.NEAR)
    body.arm(Side.NEAR, lerp(hands.getValue(Side.NEAR), s + v(0.56f, 0.03f, 0f), p), v(-0.2f, -1f, 0.4f))
    allFoursLeg(Side.FAR, p, thigh = 182f, knee = 4f)
}

/** Cat-cow: on all fours, the spine rounds up with the head down, then arches with the chest and head up. */
internal fun catCow() = Motion.cycle(5.2f, camera = Camera(24f)) { phase ->
    val s = sin(phase * TAU)
    allFours(bend = 26f * s, tilt = -10f * s, nod = 24f * s)
}

/** Thoracic rotation on all fours: the near hand behind the head, the upper back turns the elbow down and then up to the ceiling. */
internal fun thoracicRotation() = Motion.rep(Track.rep(go = 1.6f, hold = 0.6f, back = 1.4f, rest = 0.4f), Camera(40f, 18f)) { p ->
    allFours(twist = lerp(-10f, 42f, p), nod = lerp(10f, -6f, p), reach = 0.52f, free = Side.NEAR)
    body.arm(Side.NEAR, body.head(-0.07f, 0.02f, 0.07f), body.chest.dir(-0.2f, 0.3f, 1f))
}

/** Superman: lying face down, arms overhead, lifting the chest, arms and legs off the floor. */
internal fun superman() = Motion.rep(Track.rep(go = 0.9f, hold = 0.6f, back = 1.0f, rest = 0.5f), Camera(24f)) { p ->
    body.torso(v(0f, 0.115f), Basis.PRONE.pitch(-7f * p), bend = -14f * p, nod = -4f - 12f * p)
    val legs = rad(lerp(2f, 13f, p))
    for (side in Side.entries) {
        body.legAngles(side, v(-cos(legs), sin(legs), side.sign * 0.05f), 4f, fold = V3.Y, foot = Foot(dirXY(200f), dirXY(290f)))
        val arms = rad(lerp(-3f, 16f, p))
        body.armAngles(side, v(cos(arms), sin(arms), side.sign * 0.25f), 6f, fold = -V3.Y)
    }
    mat()
}

/** Hanging knee raise: from a dead hang the knees come up to hip height or higher, then lower slowly. */
internal fun hangingKneeRaise() = Motion.rep(Tempo.liftFirst(), Camera(26f)) { p ->
    val barY = 2.3f
    val shoulder = v(-0.02f, barY - armReach(0.3f - BodySize.SHOULDER_HALF, 0.02f, 0.985f))
    hangFrom(shoulder, lean = lerp(0f, -8f, p))
    for (side in Side.entries) body.arm(side, v(0f, barY, side.sign * 0.3f), v(-0.1f, -1f, side.sign * 1f))
    val raise = lerp(4f, 104f, p)
    for (side in Side.entries) body.legAngles(side, limbDir(Basis.UPRIGHT, side, raise, 2f), lerp(8f, 110f, p), fold = dirXY(raise - 180f))
    pullUpBar(0f, barY)
}

/** Reverse crunch: on the back with the knees bent at 90°, the hips curl up off the floor bringing the knees to the chest. */
internal fun reverseCrunch() = Motion.rep(Tempo.liftFirst(), Camera(-24f)) { p ->
    val curl = lerp(0f, 28f, p)
    val waist = v(-0.22f, 0.115f)
    val lower = Basis.UPRIGHT.pitch(-90f - curl)
    body.torso(waist - lower.u * BodySize.LOWER_SPINE, lower, bend = curl, nod = 8f)
    val thigh = lerp(96f, 132f, p)
    for (side in Side.entries) {
        body.legAngles(side, v(cos(rad(thigh)), sin(rad(thigh)), side.sign * 0.03f), 92f, fold = dirXY(thigh - 90f))
        body.arm(side, v(0.08f, BodySize.FIST_RADIUS, side.sign * 0.27f), v(0f, -0.2f, side.sign * 1f))
    }
    mat()
}

/** Hands behind the head, elbows out (crunch variations). */
internal fun FrameBuilder.handsBehindHead() {
    for (side in Side.entries) body.arm(side, body.onChest(-0.07f, 0.2f, side.sign * 0.07f), body.chest.dir(0.3f, 0.2f, side.sign * 1f))
}

/** Bicycle crunch: shoulders curled up, hands behind the head; each elbow turns towards the opposite knee as that knee comes in. */
internal fun bicycleCrunch() = Motion.cycle(2.8f, camera = Camera(-34f, 14f)) { phase ->
    val s = sin(phase * TAU)
    body.torso(v(0f, 0.115f), Basis.SUPINE, bend = 32f, twist = 28f * s, nod = 14f)
    handsBehindHead()
    for (side in Side.entries) {
        val drive = (side.sign * s + 1f) / 2f
        val thigh = lerp(32f, 112f, drive)
        body.legAngles(side, v(cos(rad(thigh)), sin(rad(thigh)), side.sign * 0.04f), lerp(10f, 104f, drive), fold = dirXY(thigh - 90f))
    }
    mat()
}

/**
 * Dead bug: on the back, arms up and knees over the hips bent 90°; the near arm lowers overhead and
 * the far leg straightens out low, then the other pair.
 */
internal fun deadBug() = Motion.cycle(4.6f, camera = Camera(-34f, 12f)) { phase ->
    val s = sin(phase * TAU)
    body.torso(v(0f, 0.115f), Basis.SUPINE, nod = 8f)
    for (side in Side.entries) {
        val arm = Ease.IN_OUT.apply(max(0f, side.sign * s))
        val leg = Ease.IN_OUT.apply(max(0f, -side.sign * s))
        body.armAngles(side, lerp(V3.Y, v(-0.96f, 0.28f, 0f), arm).unit() + V3.Z * (side.sign * 0.08f), 6f, fold = V3.X)
        val thigh = lerp(92f, 22f, leg)
        body.legAngles(side, v(cos(rad(thigh)), sin(rad(thigh)), side.sign * 0.03f), lerp(90f, 5f, leg), fold = dirXY(thigh - 90f))
    }
    mat()
}

/**
 * Dragon flag: upper back on a bench, hands gripping it behind the head; the body, straight from the
 * shoulders to the toes, rises towards vertical and lowers slowly.
 */
internal fun dragonFlag() = Motion.rep(Track.rep(go = 1.0f, hold = 0.4f, back = 1.8f, rest = 0.5f), Camera(-26f)) { p ->
    val top = Kit.BENCH_TOP
    val shoulders = v(-0.45f, top + 0.13f)
    val angle = lerp(14f, 70f, p)
    val spine = BodySize.LOWER_SPINE + BodySize.UPPER_SPINE - BodySize.SHOULDER_DROP
    body.torso(shoulders + dirXY(angle) * spine, Basis.UPRIGHT.pitch(-90f - angle), nod = lerp(20f, 62f, p))
    for (side in Side.entries) body.legAngles(side, dirXY(angle) + V3.Z * (side.sign * 0.03f), 3f, fold = dirXY(angle - 90f))
    flatBench(-1.0f, 0.25f)
    for (side in Side.entries) body.arm(side, v(-0.82f, top + 0.02f, side.sign * 0.13f), v(0.3f, 1f, side.sign * 0.6f))
}

/** Neck exercise set-ups lying on a bench with the head past its end. */
internal enum class LyingNeck { FLEXION, EXTENSION, SIDE }

/**
 * Neck raises lying on a bench, the head past its end: on the back lifting the chin to the chest
 * (a plate held on the forehead), face down lifting the head to look ahead, or on the side lifting
 * the ear towards the shoulder.
 */
internal fun lyingNeck(kind: LyingNeck) = Motion.rep(Tempo.liftFirst(), if (kind == LyingNeck.SIDE) Camera(10f, 10f) else Camera(-26f)) { p ->
    val top = Kit.BENCH_TOP
    when (kind) {
        LyingNeck.FLEXION -> {
            val nod = lerp(-30f, 34f, p)
            body.torso(v(0.05f, top + 0.115f), Basis.SUPINE, nod = nod)
            flatBench(-0.38f, 0.75f)
            body.standOn(0.6f, width = 0.42f, out = 10f, pole = v(0.35f, 1f, 0.35f))
            val head = Basis.SUPINE.pitch(nod)
            val face = body.neck + head.u * BodySize.NECK + head.f * (BodySize.HEAD_RADIUS + 0.03f)
            add(Disc(face, head.f, 0.11f, 0.03f, Ink.PLATE, hub = 0.025f, bias = 0.1f))
            for (side in Side.entries) body.arm(side, face + head.l * (side.sign * 0.1f), v(0.4f, 0.2f, side.sign * 1f))
        }
        LyingNeck.EXTENSION -> {
            body.torso(v(-0.05f, top + 0.11f), Basis.PRONE, nod = lerp(36f, -28f, p))
            flatBench(-0.95f, 0.38f)
            for (side in Side.entries) {
                body.legAngles(side, v(-1f, -0.02f, side.sign * 0.03f), 4f, fold = V3.Y, foot = Foot(dirXY(200f), dirXY(290f)))
                body.arm(side, v(0.28f, top - 0.12f, side.sign * 0.2f), v(-0.3f, 0f, side.sign * 1f))
            }
        }
        LyingNeck.SIDE -> {
            // On the far side, facing the viewer: the body's lateral axis points up.
            val basis = Basis(V3.Z, V3.X, V3.Y)
            body.torso(v(-0.05f, top + 0.18f), basis, tilt = lerp(-28f, 32f, p))
            flatBench(-0.95f, 0.42f)
            for (side in Side.entries) body.legAngles(side, v(-1f, 0f, 0.04f), 5f, fold = -V3.Z)
            // The bottom arm reaches forward along the bench; the top hand rests on the hip, elbow back.
            body.arm(Side.FAR, v(0.45f, top + 0.03f, 0.57f), v(0f, -1f, 0f))
            body.arm(Side.NEAR, body.onHips(0.06f, 0.06f, 0.14f), v(0f, 0.6f, -1f))
        }
    }
}

/**
 * Isometric neck hold, seated: the palms press on the forehead while the neck holds still, then
 * the near hand presses on the side of the head.
 */
internal fun neckIsometric() = Motion.cycle(6f, camera = Camera(50f, 6f)) { phase ->
    val side = (1 - cos(phase * TAU)) / 2
    val press = sin(phase * TAU * 2) * 0.5f + 0.5f
    seated(seatTop = Kit.BENCH_TOP, footX = 0.42f, width = 0.32f, nod = 1.5f * press, drawSeat = false)
    flatBench(-0.6f, 0.3f)
    val front = Side.entries.associateWith { body.head(BodySize.HEAD_RADIUS + 0.03f, 0.03f, it.sign * 0.05f) }
    val temple = body.head(0.01f, 0.03f, BodySize.HEAD_RADIUS + 0.03f)
    val lap = body.onHips(0.3f, 0.06f, -0.12f)
    body.arm(Side.NEAR, lerp(front.getValue(Side.NEAR), temple, side), v(0f, -0.6f, 1f))
    body.arm(Side.FAR, lerp(front.getValue(Side.FAR), lap, side), v(0f, -1f, -1f))
}

/** Hollow body hold: on the back, lower back pressed down, shoulders and straight legs held just off the floor, arms by the ears. */
internal fun hollowHold() = Motion.cycle(3.6f, camera = Camera(-26f, 8f)) { phase ->
    val breath = sin(phase * TAU)
    body.torso(v(0f, 0.115f), Basis.SUPINE, bend = 22f + 1.5f * breath, nod = 16f)
    val legs = rad(18f + 0.8f * breath)
    for (side in Side.entries) {
        body.legAngles(side, v(cos(legs), sin(legs), side.sign * 0.03f), 3f, fold = dirXY(deg(legs) - 90f))
        body.armAngles(side, v(-0.95f, 0.3f, side.sign * 0.12f), 6f, fold = V3.Y)
    }
    mat()
}

/** L-sit on parallettes: arms straight beside the hips, the legs held straight out level in front. */
internal fun lSit() = Motion.cycle(3.6f, camera = Camera(30f)) { phase ->
    val breath = sin(phase * TAU)
    val barY = 0.3f
    val handZ = 0.24f
    val shoulder = v(0.02f, barY + armReach(handZ - BodySize.SHOULDER_HALF, 0.02f, 0.98f))
    hangFrom(shoulder, lean = 4f + 0.8f * breath)
    for (side in Side.entries) {
        body.legAngles(side, v(1f, 0.04f + 0.01f * breath, side.sign * 0.02f), 3f, fold = -V3.Y)
        body.arm(side, v(0f, barY, side.sign * handZ), v(-1f, 0f, side.sign * 0.2f))
        add(Rod(v(-0.15f, barY, side.sign * handZ), v(0.15f, barY, side.sign * handZ), 0.02f, Ink.METAL, bias = -0.05f))
        for (x in listOf(-0.13f, 0.13f)) add(Rod(v(x, 0.02f, side.sign * handZ), v(x, barY, side.sign * handZ), 0.018f, Ink.FRAME, bias = -0.1f))
    }
}

/** Suitcase carry: walking tall with one heavy dumbbell in the near hand, the trunk upright, not leaning. */
internal fun suitcaseCarry() = Motion.cycle(2.2f, cycles = 2, camera = Camera(26f)) { phase ->
    val bob = cos(phase * TAU * 2)
    body.torso(v(0f, 0.875f + 0.012f * bob), Basis.UPRIGHT.pitch(2f), shrug = 0.01f)
    for (side in Side.entries) {
        val offset = if (side == Side.NEAR) 0f else 0.5f
        gaitLeg(side, (phase + offset) % 1f, reach = 0.25f, lift = 0.07f, stance = 0.6f)
        val swing = sin((phase + offset) * TAU)
        if (side == Side.NEAR) body.hang(side, forward = 2f * swing, out = 9f) else body.hang(side, forward = 12f * swing, out = 6f)
    }
    dumbbell(body.handOf(Side.NEAR), axis = V3.X, headRadius = 0.08f)
}

/** Pallof press: standing side-on to a cable at chest height, the handle pressed straight out from the chest, resisting the turn. */
internal fun pallofPress() = Motion.rep(Track.rep(go = 0.9f, hold = 0.8f, back = 0.9f, rest = 0.5f), Camera(46f, 14f)) { p ->
    body.torso(v(-0.02f, 0.89f), Basis.UPRIGHT.pitch(4f))
    body.standOn(0f, width = 0.42f, out = 10f, pole = v(1f, 0.1f, 0.3f))
    val chest = body.onChest(0.17f, -0.2f)
    val hands = lerp(chest, v(body.shoulders().x + 0.5f, chest.y, 0f), p)
    for (side in Side.entries) body.arm(side, hands.withZ(side.sign * 0.03f), v(-0.4f, -1f, side.sign * 0.8f))
    add(Rod(hands - V3.Y * 0.05f, hands + V3.Y * 0.05f, 0.02f, Ink.METAL))
    cable(v(0.25f, chest.y, 1.0f), hands, fit = false)
    add(Block(v(0.25f, 1.05f, 1.12f), Basis.UPRIGHT, v(0.2f, 1.05f, 0.08f), Ink.FRAME, fit = false, bias = -0.6f))
}

/** Low-to-high cable woodchop, seen from the front: a diagonal pull from low on the near side to high past the far shoulder. */
internal fun reverseWoodchop() = Motion.rep(Tempo.liftFirst(), Camera(70f)) { p ->
    body.torso(v(-0.03f, lerp(0.78f, 0.9f, p)), Basis.UPRIGHT.pitch(lerp(22f, 6f, p)), twist = lerp(34f, -42f, p))
    body.standOn(0f, width = 0.5f, out = 14f, pole = v(1f, 0.1f, 0.3f))
    val hands = lerp(v(0.3f, 0.76f, 0.2f), v(0.18f, 1.62f, -0.42f), p)
    for (side in Side.entries) body.arm(side, hands + V3.Z * (side.sign * 0.04f), v(-0.3f, -1f, side.sign * 0.5f))
    add(Rod(hands - V3.Y * 0.05f, hands + V3.Y * 0.05f, 0.02f, Ink.METAL))
    cable(v(-0.1f, 0.22f, 0.95f), hands, fit = false)
}

/**
 * Kettlebell windmill, seen from the front: the bell locked out overhead in the near hand, the hips
 * push out to the near side while the trunk folds down towards the far foot, the far hand sliding
 * down the leg; the bell arm stays vertical.
 */
internal fun kettlebellWindmill() = Motion.rep(Track.rep(go = 1.6f, hold = 0.3f, back = 1.4f, rest = 0.4f), Camera(76f, 8f)) { p ->
    val fold = lerp(0f, 1f, p)
    body.torso(v(-0.03f * fold, lerp(0.9f, 0.86f, fold), 0.1f * fold), Basis.UPRIGHT.pitch(18f * fold).roll(-30f * fold), sideways = -30f * fold, nod = 0f)
    body.standOn(0f, width = 0.5f, out = 18f, pole = v(1f, 0.1f, 0.35f))
    val top = body.shoulder(Side.NEAR) + V3.Y * 0.565f
    body.arm(Side.NEAR, top, v(0.3f, 0f, 1f))
    kettlebell(top, down = v(-0.3f, -0.5f, 0.15f), radius = 0.1f)
    val leg = body.legOf(Side.FAR)
    body.arm(Side.FAR, lerp(leg.hip, leg.knee, 0.15f + 0.45f * fold) + v(0.12f, 0f, 0.02f), v(0f, -0.4f, -1f))
}
