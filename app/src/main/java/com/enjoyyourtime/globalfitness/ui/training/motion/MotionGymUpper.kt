package com.enjoyyourtime.globalfitness.ui.training.motion

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin

// Upper-body patterns added with the larger catalog: push-up variations, presses, rows, kettlebell
// lifts and resistance-band work. They reuse the shared helpers and leave the original patterns
// untouched.

private const val TAU = 2 * PI.toFloat()

/** Push-up variations. */
internal enum class PushUp { KNEES, INCLINE, DECLINE, DIAMOND }

/**
 * Push-up variations, the body straight from the pivot to the head: on the knees, hands on a box
 * (incline), feet on a bench (decline), or hands together under the chest (diamond, elbows back).
 */
internal fun pushUpVariant(kind: PushUp) = Motion.rep(Tempo.lowerFirst(), Camera(22f)) { p ->
    val handY = if (kind == PushUp.INCLINE) Kit.BENCH_TOP else 0f
    val handZ = when (kind) {
        PushUp.DIAMOND -> 0.07f
        PushUp.INCLINE -> 0.22f
        else -> 0.27f
    }
    val spine = BodySize.LOWER_SPINE + BodySize.UPPER_SPINE - BodySize.SHOULDER_DROP
    val pivot = when (kind) {
        PushUp.KNEES -> v(-0.45f, BodySize.KNEE_RADIUS + 0.006f)
        PushUp.DECLINE -> v(-0.85f, Kit.BENCH_TOP + 0.185f)
        else -> v(-0.85f, 0.185f)
    }
    val line = spine + if (kind == PushUp.KNEES) BodySize.THIGH else BodySize.THIGH + BodySize.SHIN - 0.01f
    val palm = handY + BodySize.FIST_RADIUS
    val lateral = abs(handZ - BodySize.SHOULDER_HALF)
    val bottom = deg(asin((palm + 0.21f - pivot.y) / line))
    // Hands under the shoulders at the top; when the body swings a lot (knees, incline) they sit just
    // behind the shoulders' lowest point instead, and the top is where the arms are straight.
    val shifted = kind == PushUp.KNEES || kind == PushUp.INCLINE
    val handX: Float
    val top: Float
    if (shifted) {
        handX = pivot.x + line * cos(rad(bottom)) - 0.06f
        top = solve(bottom, 80f) { a ->
            val s = pivot + dirXY(a) * line
            val dx = s.x - handX
            val dy = s.y - palm
            kotlin.math.sqrt(dx * dx + dy * dy + lateral * lateral) - (BodySize.UPPER_ARM + BodySize.FOREARM) * 0.975f
        }
    } else {
        top = deg(asin((palm + armReach(lateral) - pivot.y) / line))
        handX = pivot.x + line * cos(rad(top))
    }
    val angle = lerp(top, bottom, p)
    val dir = dirXY(angle)
    if (kind == PushUp.KNEES) {
        body.torso(pivot + dir * BodySize.THIGH, Basis.UPRIGHT.pitch(90f - angle), nod = 8f)
        for (side in Side.entries) {
            val knee = pivot.withZ(side.sign * BodySize.HIP_HALF)
            body.leg(side, knee + v(-0.42f, 0.11f, 0f), v(1f, -1.5f, 0f), Foot(dirXY(250f), dirXY(340f)))
        }
        mat()
    } else {
        plankBody(pivot, angle, nod = 8f)
    }
    val elbows = if (kind == PushUp.DIAMOND) 0.3f else 0.8f
    for (side in Side.entries) body.arm(side, v(handX, palm, side.sign * handZ), v(-1f, 0.2f, side.sign * elbows))
    when (kind) {
        PushUp.INCLINE -> box(v(handX - 0.2f, 0f, -0.35f), v(handX + 0.16f, Kit.BENCH_TOP, 0.35f), Ink.PAD, bias = -0.3f)
        PushUp.DECLINE -> flatBench(pivot.x - 0.45f, pivot.x + 0.25f)
        else -> Unit
    }
}

/**
 * Pike push-up: hands and feet on the floor with the hips high, the legs straight (on the balls of
 * the feet) and the trunk in line with the arms; the elbows bend back at about 45° from the trunk
 * and the head drops towards the floor between the hands, the hips easing a little forward.
 */
internal fun pikePushUp() = Motion.rep(Tempo.lowerFirst(), Camera(24f)) { p ->
    val hands = v(0.48f, BodySize.FIST_RADIUS)
    val lift = 35f
    val ankle = v(-0.6f, BodySize.FOOT_RADIUS + BodySize.SOLE * cos(rad(lift)) + BodySize.TOE * sin(rad(lift)))
    // Straight legs: the hips turn about the ankles, forward and a little lower as the head goes down.
    val pelvis = ankle + dirXY(lerp(58f, 52f, p)) * (BodySize.THIGH + BodySize.SHIN - 0.004f)
    val reach = lerp(0.57f, 0.36f, p)
    val spine = BodySize.LOWER_SPINE + BodySize.UPPER_SPINE - BodySize.SHOULDER_DROP
    // The shoulders sit [spine] from the hips and [reach] from the hands (two circles meeting).
    val toHands = hands - pelvis
    val d = toHands.length
    val along = toHands * (1f / d)
    val a = (spine * spine - reach * reach + d * d) / (2 * d)
    val h = kotlin.math.sqrt((spine * spine - a * a).coerceAtLeast(0f))
    val shoulders = pelvis + along * a + v(-along.y, along.x) * h
    val up = shoulders - pelvis
    body.torso(pelvis, Basis.UPRIGHT.pitch(deg(kotlin.math.atan2(up.x, up.y))), nod = -20f)
    // The knee caps face the hands: the legs (all but straight) bend that way, never backwards.
    for (side in Side.entries) body.leg(side, ankle.withZ(side.sign * 0.1f), v(1f, -0.6f, 0f), Foot(dirXY(-lift), dirXY(90f - lift)))
    for (side in Side.entries) body.arm(side, hands.withZ(side.sign * 0.22f), v(-1f, 0.15f, side.sign * 0.4f))
}

/**
 * JM press: lying on a flat bench with a close grip, the bar comes down towards the chin with the
 * elbows pointing at the feet, then is pressed back up.
 */
internal fun jmPress() = Motion.rep(Tempo.lowerFirst(), Camera(-32f)) { p ->
    val basis = Basis.SUPINE
    val junction = v(0.05f, Kit.BENCH_TOP)
    val pelvis = junction + basis.f * 0.115f - basis.u * 0.03f
    body.torso(pelvis, basis)
    bench(junction, 0f)
    body.standOn(pelvis.x + 0.52f, width = 0.5f, out = 10f, pole = v(0.35f, 1f, 0.35f))
    val shoulders = body.shoulders()
    val half = 0.2f
    val top = v(shoulders.x + 0.03f, shoulders.y + armReach(half - BodySize.SHOULDER_HALF, ahead = 0.03f))
    val low = body.onChest(0.2f, 0.04f)
    val bar = lerp(top, v(low.x, low.y), p)
    barbell(bar, plateRadius = 0.18f)
    for (side in Side.entries) body.arm(side, bar.withZ(side.sign * half), v(1f, 0.3f, side.sign * 0.3f))
    rack(shoulders.x - 0.12f, top.y - 0.05f)
}

/**
 * Landmine press: standing in a split stance, the near hand presses the end of a bar anchored on the
 * floor in front, up and forward along its arc until the arm is straight.
 */
internal fun landminePress() = Motion.rep(Tempo.liftFirst(), Camera(26f)) { p ->
    body.torso(v(-0.04f, BodySize.STAND - 0.045f), Basis.UPRIGHT.pitch(8f))
    body.stand(Side.NEAR, -0.16f, z = 0.13f)
    body.stand(Side.FAR, 0.2f, z = -0.13f)
    val pivot = v(1.55f, 0.05f)
    val s = body.shoulder(Side.NEAR)
    val start = body.onChest(0.16f, -0.08f, 0.13f)
    val arm = (BodySize.UPPER_ARM + BodySize.FOREARM) * 0.97f
    fun end(a: Float) = pivot + rotate(start - pivot, V3.Z, -rad(a))
    val reach = solve(0f, 45f) { a -> end(a).distanceTo(s) - arm }
    val hand = end(lerp(0f, reach, p))
    body.arm(Side.NEAR, hand, v(0.2f, -1f, 0.8f))
    body.arm(Side.FAR, body.onHips(0.02f, 0.12f, -0.2f), v(-0.6f, 0f, -1f))
    landmine(pivot, hand)
}

/** A barbell anchored in a landmine at [pivot] on the floor, its free end (with a plate) in the hands at [end]. */
internal fun FrameBuilder.landmine(pivot: V3, end: V3) {
    val along = (end - pivot).unit()
    add(Rod(pivot, end + along * 0.06f, Kit.BAR_RADIUS * 1.3f, fit = false))
    add(Disc(end - along * 0.16f, along, 0.17f, 0.045f, Ink.PLATE, hub = 0.03f))
    add(Block(pivot + V3.Y * 0.02f, Basis.UPRIGHT, v(0.08f, 0.03f, 0.08f), Ink.FRAME, fit = false, bias = -0.4f))
}

/**
 * Landmine rotation, seen from the front: both hands on the end of the bar at arm's length, the
 * trunk turns it in an arc from one hip to the other.
 */
internal fun landmineRotation() = Motion.cycle(3.6f, camera = Camera(64f, 10f)) { phase ->
    val swing = sin(phase * TAU)
    val pivot = v(1.6f, 0.05f)
    body.torso(v(-0.02f, BodySize.STAND - 0.06f), Basis.UPRIGHT.pitch(8f).turn(12f * swing), twist = 33f * swing)
    body.standOn(0f, width = 0.44f, out = 12f, pole = v(1f, 0.1f, 0.3f))
    val radius = 1.7f
    val azimuth = rad(13f * swing)
    val elevation = rad(lerp(46f, 39f, abs(swing)))
    val end = pivot + v(-cos(elevation) * cos(azimuth), sin(elevation), cos(elevation) * sin(azimuth)) * radius
    val along = (end - pivot).unit()
    for (side in Side.entries) body.arm(side, end - along * (if (side == Side.NEAR) 0.02f else 0.1f) + V3.Z * (side.sign * 0.03f), v(-0.3f, -1f, side.sign * 0.6f))
    landmine(pivot, end)
}

/**
 * Smith machine row: hinged with a flat back, the bar travels straight up the rails to the lower
 * ribs; only the arms move (the shoulders do not round or rise, which reads as the trunk moving).
 */
internal fun smithRow() = Motion.rep(Tempo.liftFirst(), Camera(24f)) { p ->
    val lean = 55f
    val basis = Basis.UPRIGHT.pitch(lean)
    body.torso(v(-0.2f, 0.83f), basis, nod = -lean * 0.45f)
    body.standOn(-0.02f, width = 0.28f, out = 10f, pole = v(1f, 0.1f, 0.2f))
    val shoulders = body.shoulders()
    val half = 0.27f
    val lateral = half - BodySize.SHOULDER_HALF
    val chest = body.onChest(0.14f + Kit.BAR_RADIUS, -0.3f)
    val low = shoulders.y - armReach(lateral, ahead = chest.x - shoulders.x)
    val bar = v(chest.x, lerp(low, chest.y, p))
    barbell(bar)
    for (side in Side.entries) body.arm(side, bar.withZ(side.sign * half), v(-1f, 0.5f, side.sign * 0.35f))
    smithRails(bar.x, 2.1f)
}

/**
 * Chest-supported dumbbell row: lying face down on an incline bench, the dumbbells hang straight
 * down and are rowed up beside the ribs.
 */
internal fun chestSupportedRow() = Motion.rep(Tempo.liftFirst(), Camera(28f)) { p ->
    val lean = 52f
    val basis = Basis.UPRIGHT.pitch(lean)
    body.torso(v(-0.32f, 0.78f), basis, nod = -22f, protract = lerp(0.03f, -0.03f, p))
    body.standOn(-0.62f, width = 0.32f, out = 8f, lift = 20f, pole = v(1f, 0.1f, 0.2f))
    // The incline pad under the chest, on its frame.
    val padStart = body.onHips(0.17f, -0.05f)
    pad(padStart, padStart + basis.u * 0.66f, width = 0.32f, thick = 0.08f, up = -basis.f, bias = -0.15f)
    add(Rod(v(padStart.x + 0.05f, 0.02f), padStart + basis.u * 0.3f + basis.f * 0.05f, 0.03f, Ink.FRAME, bias = -0.3f))
    add(Rod(v(padStart.x - 0.25f, 0.02f), v(padStart.x + 0.6f, 0.02f), 0.025f, Ink.FRAME, bias = -0.3f))
    for (side in Side.entries) {
        val s = body.shoulder(side)
        val hang = s + v(0.02f, -armReach(0.03f, 0.02f), side.sign * 0.03f)
        val top = body.onChest(0.0f, -0.24f, side.sign * 0.25f)
        val hand = lerp(hang, top, p)
        body.arm(side, hand, v(-1f, 1f, side.sign * 0.3f))
        dumbbell(hand, axis = V3.X)
    }
}

/** One-arm kettlebell row: far knee and hand on a bench, the near arm rows the bell from straight down to the hip. */
internal fun kettlebellRow() = Motion.rep(Tempo.liftFirst(), Camera(26f)) { p ->
    val basis = Basis.UPRIGHT.pitch(80f)
    body.torso(v(-0.12f, 0.9f), basis, nod = -30f, protract = lerp(0.02f, -0.03f, p))
    val benchZ = -0.16f
    body.leg(Side.FAR, v(-0.7f, 0.53f, -0.14f), v(0f, -1f, 0f), Foot(dirXY(205f), dirXY(295f)))
    body.stand(Side.NEAR, -0.06f, z = 0.24f, out = 4f)
    body.arm(Side.FAR, v(0.45f, 0.5f, -0.2f), v(-1f, 0f, -0.3f))
    val s = body.shoulder(Side.NEAR)
    val hang = s + v(0.02f, -armReach(0.03f, 0.02f), 0.03f)
    val hand = lerp(hang, body.onHips(0.12f, 0.15f, 0.24f), p)
    body.arm(Side.NEAR, hand, v(-1f, 1f, 0.2f))
    kettlebell(hand, down = -V3.Y, across = V3.X, radius = 0.095f)
    add(Block(v(0.05f, Kit.BENCH_TOP - 0.04f, benchZ), Basis.UPRIGHT, v(0.62f, 0.04f, 0.15f), Ink.PAD, bias = -0.4f))
    for (x in listOf(-0.42f, 0.52f)) add(Rod(v(x, 0.03f, benchZ), v(x, Kit.BENCH_TOP - 0.08f, benchZ), 0.028f, Ink.FRAME, bias = -0.45f))
}

/** Smith machine shrug: straight arms on the bar while the shoulders lift towards the ears and lower. */
internal fun smithShrug() = Motion.rep(Tempo.short(), Camera(38f)) { p ->
    body.torso(v(0f, BodySize.STAND), shrug = lerp(0f, 0.075f, p))
    body.standOn(0f, width = 0.28f)
    for (side in Side.entries) {
        val s = body.shoulder(side)
        body.arm(side, s + v(0.05f, -armReach(0.08f, 0.05f, 0.99f), side.sign * 0.08f), v(-1f, 0f, side.sign * 0.4f))
    }
    val bar = (body.handOf(Side.NEAR) + body.handOf(Side.FAR)) * 0.5f
    barbell(bar)
    smithRails(bar.x, 2.1f)
}

/**
 * One-arm kettlebell work from the hang: the clean brings the bell to the rack at the shoulder
 * ([stage] 0 → 1), the press takes it overhead (1 → 2). Same body mechanics as the clean and press.
 */
private fun FrameBuilder.kettlebellStage(stage: Float) {
    val clean = stage.coerceIn(0f, 1f)
    val press = (stage - 1f).coerceIn(0f, 1f)
    val lean = lerp(30f, 2f, clean)
    body.torso(v(lerp(-0.12f, 0f, clean), lerp(0.8f, BodySize.STAND, clean)), Basis.UPRIGHT.pitch(lean), nod = -lean * 0.5f)
    body.standOn(0f, width = 0.4f, out = 12f, pole = v(1f, 0.1f, 0.3f))
    val s = body.shoulder(Side.NEAR)
    val hang = s + v(0.06f, -0.56f, -0.1f)
    val rack = body.onChest(0.13f, -0.03f, 0.1f)
    val overhead = s + v(-0.02f, 0.565f, 0.02f)
    val hand = if (stage <= 1f) lerp(hang, rack, clean) + V3.X * (0.12f * sin(PI.toFloat() * clean)) else lerp(rack, overhead, press)
    body.arm(Side.NEAR, hand, if (stage <= 1f) v(-0.4f, -1f, 0.3f) else v(0.2f, -1f, lerp(0.3f, 1f, press)))
    body.armAngles(Side.FAR, limbDir(body.chest, Side.FAR, 8f, 24f), 12f, fold = body.chest.f)
    val down = if (stage <= 1f) lerp(-V3.Y, v(-0.6f, -0.6f, 0f), clean) else lerp(v(-0.6f, -0.6f, 0f), v(-0.35f, -0.6f, 0f), press)
    kettlebell(hand, down = down)
}

/** Kettlebell clean: from between the legs to the rack at the shoulder, and back down. */
internal fun kettlebellClean() = Motion.rep(Track.keys(0f to 0f, 0.35f to 0f, 0.95f to 1f, 1.4f to 1f, 2.1f to 0f), Camera(26f)) { stage ->
    kettlebellStage(stage)
}

/** Kettlebell press: from the rack at the shoulder to locked out overhead, and back to the rack. */
internal fun kettlebellPress() = Motion.rep(Track.keys(0f to 1f, 0.4f to 1f, 1.1f to 2f, 1.4f to 2f, 2.3f to 1f), Camera(26f)) { stage ->
    kettlebellStage(stage)
}

/**
 * Kettlebell snatch: from the hike between the legs the hips snap forward and the bell travels
 * close to the body straight up to lockout overhead, turning over the hand; then back down.
 */
internal fun kettlebellSnatch() = Motion.rep(
    Track.rep(go = 0.85f, hold = 0.35f, back = 0.9f, rest = 0.15f, goEase = Ease.OUT, backEase = Ease.IN),
    Camera(26f),
) { p ->
    val hips = Ease.IN_OUT.apply((p / 0.55f).coerceIn(0f, 1f))
    val lean = lerp(52f, -2f, hips)
    body.torso(v(lerp(-0.22f, 0f, hips), lerp(0.8f, 0.915f, hips)), Basis.UPRIGHT.pitch(lean), nod = -lean * 0.5f)
    body.standOn(0f, width = 0.42f, out = 14f, pole = v(1f, 0.1f, 0.35f))
    val s = body.shoulder(Side.NEAR)
    val swing = lerp(-25f, 178f, p)
    val dir = dirXY(swing - 90f)
    // The elbow bends as the bell passes the chest (a high pull), then punches straight.
    val bend = sin(PI.toFloat() * ((swing - 20f) / 150f).coerceIn(0f, 1f))
    val reach = 0.56f - 0.14f * bend
    val inward = 1f - Ease.IN_OUT.apply((swing / 120f).coerceIn(0f, 1f))
    val hand = s + dir * reach - V3.Z * (0.12f * inward)
    body.arm(Side.NEAR, hand, v(-0.3f, 0.6f, 1f))
    body.armAngles(Side.FAR, limbDir(body.chest, Side.FAR, lerp(10f, 25f, hips), 20f), 12f, fold = body.chest.f)
    val flip = Ease.IN_OUT.apply(((swing - 115f) / 60f).coerceIn(0f, 1f))
    kettlebell(hand, down = lerp(dir, v(-0.35f, -0.6f, 0f), flip).unit())
}

/** Band chest press: standing in a split stance with the band anchored behind, the handles pressed from the chest to arm's length. */
internal fun bandChestPress() = Motion.rep(Tempo.liftFirst(), Camera(28f)) { p ->
    body.torso(v(0f, BodySize.STAND - 0.02f), Basis.UPRIGHT.pitch(8f))
    body.stand(Side.NEAR, 0.16f, z = 0.13f)
    body.stand(Side.FAR, -0.18f, z = -0.13f)
    val shoulders = body.shoulders()
    val anchor = v(-0.78f, shoulders.y - 0.12f)
    for (side in Side.entries) {
        val start = body.onChest(0.16f, -0.12f, side.sign * 0.3f)
        val end = (shoulders + dirXY(2f) * 0.55f).withZ(side.sign * 0.2f)
        val hand = lerp(start, end, p)
        body.arm(side, hand, v(-0.4f, -1f, side.sign * 0.9f))
        band(listOf(anchor.withZ(side.sign * 0.04f), hand))
    }
    post(anchor.x - 0.04f, 2.0f)
}

/** Band row: standing with the band anchored in front at chest height, the handles pulled from arm's length to the ribs. */
internal fun bandRow() = Motion.rep(Tempo.liftFirst(), Camera(26f)) { p ->
    body.torso(v(-0.04f, 0.9f), Basis.UPRIGHT.pitch(lerp(10f, 2f, p)), protract = lerp(0.03f, -0.03f, p))
    body.standOn(0f, width = 0.3f, out = 8f, pole = v(1f, 0.1f, 0.2f))
    val shoulders = body.shoulders()
    val anchor = v(1.1f, shoulders.y - 0.2f)
    for (side in Side.entries) {
        val start = v(shoulders.x + 0.52f, shoulders.y - 0.18f, side.sign * 0.15f)
        val end = body.onChest(0.12f, -0.3f, side.sign * 0.2f)
        val hand = lerp(start, end, p)
        body.arm(side, hand, v(-1f, -0.2f, side.sign * 0.4f))
        band(listOf(anchor.withZ(side.sign * 0.03f), hand))
    }
    post(anchor.x + 0.04f, 2.0f)
}

/** Band face pull: the band anchored at head height pulled towards the face, elbows high and wide. */
internal fun bandFacePull() = Motion.rep(Tempo.liftFirst(), Camera(36f)) { p ->
    body.torso(v(-0.06f, BodySize.STAND - 0.01f), Basis.UPRIGHT.pitch(-4f))
    body.stand(Side.NEAR, 0.1f, z = 0.13f)
    body.stand(Side.FAR, -0.2f, z = -0.13f)
    val anchor = v(1.05f, 1.68f)
    val shoulders = body.shoulders()
    for (side in Side.entries) {
        val start = shoulders + (anchor - shoulders).unit() * 0.56f + V3.Z * (side.sign * 0.06f)
        val end = body.onChest(0.12f, 0.2f, side.sign * 0.24f)
        body.arm(side, lerp(start, end, p), v(-0.4f, 0.5f, side.sign * 1f))
        band(listOf(anchor.withZ(side.sign * 0.03f), body.handOf(side)))
    }
    post(anchor.x + 0.04f, 2.0f)
}

/** Band lateral raise, seen from the front: standing on the band, both arms rise out to the sides to shoulder height. */
internal fun bandLateralRaise() = Motion.rep(Tempo.liftFirst(), Camera(84f)) { p ->
    body.torso(v(0f, BodySize.STAND), Basis.UPRIGHT.pitch(4f))
    body.standOn(0f, width = 0.3f)
    val raise = lerp(10f, 84f, p)
    for (side in Side.entries) {
        body.armAngles(side, limbDir(body.chest, side, 14f, raise), 16f, fold = body.chest.f)
        band(listOf(v(0.03f, 0.03f, side.sign * 0.1f), body.handOf(side)))
    }
}

/**
 * Band external rotation, seen from above the front: the near elbow pinned at the side and bent
 * 90°, the forearm turns out from across the waist against a band anchored on the far side.
 */
internal fun bandExternalRotation() = Motion.rep(Tempo.liftFirst(), Camera(55f, 40f)) { p ->
    body.torso(v(0f, BodySize.STAND))
    body.standOn(0f, width = 0.3f)
    val a = rad(lerp(-55f, 50f, p))
    body.armAngles(Side.NEAR, v(0.03f, -1f, 0.08f), 90f, fold = v(cos(a), 0f, sin(a)))
    body.hang(Side.FAR)
    val hand = body.handOf(Side.NEAR)
    band(listOf(v(0.1f, hand.y, -0.92f), hand))
    post(0.1f, 1.9f, z = -0.96f)
}

/** Band biceps curl: standing on the band, elbows by the sides, curling the handles to the shoulders. */
internal fun bandCurl() = Motion.rep(Tempo.liftFirst(), Camera(34f)) { p ->
    body.torso(v(0f, BodySize.STAND), nod = 4f)
    body.standOn(0f, width = 0.26f)
    val elbow = lerp(12f, 138f, p)
    for (side in Side.entries) {
        body.armAngles(side, limbDir(body.chest, side, lerp(2f, 12f, p), 7f), elbow, fold = body.chest.f)
        band(listOf(v(0.04f, 0.03f, side.sign * 0.1f), body.handOf(side)))
    }
}

/** Rope hammer curl at a low pulley: palms facing each other on the rope, curling to the shoulders. */
internal fun ropeHammerCurl() = Motion.rep(Tempo.liftFirst(), Camera(34f)) { p ->
    body.torso(v(0f, BodySize.STAND), nod = 4f)
    body.standOn(0f, width = 0.26f)
    val elbow = lerp(12f, 136f, p)
    for (side in Side.entries) body.armAngles(side, limbDir(body.chest, side, lerp(2f, 12f, p), 5f), elbow, fold = body.chest.f)
    val near = body.handOf(Side.NEAR)
    val far = body.handOf(Side.FAR)
    val pulley = v(0.42f, 0.12f)
    val knot = (near + far) * 0.5f + (pulley - (near + far) * 0.5f).unit() * 0.14f
    add(Cord(listOf(knot, near), 0.026f, Ink.CABLE))
    add(Cord(listOf(knot, far), 0.026f, Ink.CABLE))
    cable(pulley, knot)
    add(Block(v(0.5f, 1.0f), Basis.UPRIGHT, v(0.1f, 1.0f, 0.18f), Ink.FRAME, fit = false, bias = -0.6f))
}

/** Band triceps pushdown: facing a band anchored high, elbows pinned by the ribs, forearms pushed down to straight arms. */
internal fun bandPushdown() = Motion.rep(Tempo.liftFirst(), Camera(24f)) { p ->
    body.torso(v(-0.05f, BodySize.STAND - 0.01f), Basis.UPRIGHT.pitch(12f), nod = 4f)
    body.standOn(0f, width = 0.26f, out = 6f)
    val elbow = lerp(100f, 4f, p)
    for (side in Side.entries) body.armAngles(side, limbDir(Basis.UPRIGHT, side, 2f, -2f), elbow, fold = V3.X)
    val anchor = v(0.62f, 2.0f)
    for (side in Side.entries) band(listOf(anchor.withZ(side.sign * 0.03f), body.handOf(side)))
    post(anchor.x + 0.06f, 2.1f)
}

/**
 * Dumbbell wrist curl: seated, forearms along the thighs with the wrists past the knees; only the
 * hands move. [reverse] holds the dumbbells palms down.
 */
internal fun dumbbellWristCurl(reverse: Boolean) = Motion.rep(Tempo.short(), Camera(34f)) { p ->
    val seatTop = Kit.BENCH_TOP
    body.torso(v(-0.15f, seatTop + SEAT_HIP), Basis.UPRIGHT.pitch(38f), nod = 18f)
    body.standOn(0.33f, width = 0.32f, out = 6f)
    flatBench(-0.6f, 0.05f, seatTop)
    val bend = if (reverse) lerp(-25f, 50f, p) else lerp(-55f, 62f, p)
    for (side in Side.entries) {
        val leg = body.legOf(side)
        val fore = (leg.knee - leg.hip).unit()
        val wrist = leg.knee + fore * 0.06f + V3.Y * 0.075f
        body.armWrist(side, wrist.withZ(side.sign * 0.13f), v(-0.3f, -1f, side.sign * 0.5f), dirXY(angleXY(fore) + bend))
        dumbbell(body.handOf(side), headRadius = 0.06f)
    }
}
