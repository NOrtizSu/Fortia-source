package com.enjoyyourtime.globalfitness.ui.training.motion

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

// Suspension trainer (TRX), bosu, Swiss ball, balance and floor Pilates exercises added in 1.8.

private const val TAU = 2 * PI.toFloat()

/** Hip joints to the shoulder line. */
private const val SPINE = BodySize.LOWER_SPINE + BodySize.UPPER_SPINE - BodySize.SHOULDER_DROP

/** Ankle to the hip joints of a straight leg, as the plank patterns use it. */
private const val LEGS = BodySize.THIGH + BodySize.SHIN - 0.01f

/** The lean (Basis.UPRIGHT.pitch) of a trunk whose up axis points along [u] in the X-Y plane. */
private fun leanOf(u: V3): Float = deg(atan2(u.x, u.y))

// ---- Suspension trainer ------------------------------------------------------------------------------

/** The handles of a suspension trainer at [hands] and its straps up to [anchor]. */
private fun FrameBuilder.straps(hands: Map<Side, V3>, anchor: V3) {
    for (side in Side.entries) {
        val hand = hands.getValue(side)
        add(Rod(hand - V3.Z * (side.sign * 0.06f), hand + V3.Z * (side.sign * 0.06f), 0.02f, Ink.METAL))
        add(Cord(listOf(hand, anchor.withZ(side.sign * 0.03f)), 0.02f, Ink.BAND, fit = false, bias = -0.1f))
    }
}

/** Feet in the foot cradles of a suspension trainer: the straps from the ankles up to [anchor]. */
private fun FrameBuilder.footStraps(anchor: V3) {
    for (side in Side.entries) {
        val ankle = body.legOf(side).ankle
        add(Cord(listOf(ankle, anchor.withZ(side.sign * 0.03f)), 0.02f, Ink.BAND, fit = false, bias = -0.1f))
    }
}

/** Facing the anchor, heels on the floor at [ankle], the straight body leaning back [angle] degrees above the floor. */
private fun FrameBuilder.leanBack(ankle: V3, angle: Float) {
    val dir = dirXY(180f - angle)
    body.torso(ankle + dir * LEGS, Basis.UPRIGHT.pitch(angle - 90f), nod = 10f)
    for (side in Side.entries) body.leg(side, ankle.withZ(side.sign * 0.1f), v(0f, 1f, 0f), Foot(dirXY(90f - angle), dirXY(180f - angle)))
}

/** Facing away from the anchor, on the toes at [ankle], the straight body leaning forward [angle] degrees above the floor. */
private fun FrameBuilder.leanForward(ankle: V3, angle: Float) = plankBody(ankle, angle, nod = 6f)

/** TRX chest press: facing away from the anchor and leaning forward, the chest lowers between the handles like a push-up. */
internal fun suspensionChestPress() = Motion.rep(Tempo.lowerFirst(), Camera(26f)) { p ->
    leanForward(v(-0.42f, 0.185f), lerp(54f, 44f, p))
    val shoulders = body.shoulders()
    val chest = body.chest
    val hands = Side.entries.associateWith { side ->
        val top = shoulders + chest.f * 0.54f - chest.u * 0.12f
        val bottom = shoulders + chest.f * 0.13f - chest.u * 0.1f
        lerp(top, bottom, p).withZ(side.sign * lerp(0.2f, 0.27f, p))
    }
    for (side in Side.entries) body.arm(side, hands.getValue(side), -chest.u + chest.l * (side.sign * 0.8f))
    straps(hands, v(-0.95f, 2.45f))
}

/** TRX Y raise: facing the anchor and leaning back, the straight arms rise from in front up and out into a Y, lifting the body. */
internal fun suspensionYRaise() = Motion.rep(Tempo.liftFirst(), Camera(-30f, 6f)) { p ->
    val anchor = v(0.65f, 2.45f)
    leanBack(v(0.34f, 0.1f), lerp(50f, 64f, p))
    val hands = Side.entries.associateWith { side ->
        val s = body.shoulder(side)
        val start = s + (anchor.withZ(side.sign * 0.12f) - s).unit() * 0.555f
        val end = s + limbDir(body.chest, side, 162f, 30f) * 0.555f
        lerp(start, end, Ease.IN_OUT.apply(p))
    }
    for (side in Side.entries) body.arm(side, hands.getValue(side), v(0.2f, -1f, side.sign * 0.6f))
    straps(hands, anchor)
}

/** TRX biceps curl: facing the anchor and leaning back, the hands curl to the forehead with the elbows high, lifting the body. */
internal fun suspensionCurl() = Motion.rep(Tempo.liftFirst(), Camera(-30f, 6f)) { p ->
    val anchor = v(0.65f, 2.45f)
    leanBack(v(0.34f, 0.1f), lerp(46f, 58f, p))
    val shoulders = body.shoulders()
    val reach = shoulders + (anchor - shoulders).unit() * 0.55f
    val forehead = body.head(0.15f, 0.02f, 0f)
    val hands = Side.entries.associateWith { side -> lerp(reach.withZ(side.sign * 0.15f), forehead.withZ(side.sign * 0.09f), Ease.IN_OUT.apply(p)) }
    for (side in Side.entries) body.arm(side, hands.getValue(side), (anchor - shoulders).unit() + V3.Y * 0.4f + V3.Z * (side.sign * 0.3f))
    straps(hands, anchor)
}

/** TRX triceps extension: facing away and leaning forward, the hands overhead bend to the forehead and press out again. */
internal fun suspensionTricepsExtension() = Motion.rep(Tempo.lowerFirst(), Camera(26f)) { p ->
    leanForward(v(-0.42f, 0.185f), lerp(58f, 50f, p))
    val chest = body.chest
    val hands = Side.entries.associateWith { side ->
        val s = body.shoulder(side)
        val top = s + (chest.u * 0.55f + chest.f * 0.3f).unit() * 0.555f
        val bottom = body.head(0.17f, 0.04f, side.sign * 0.07f)
        lerp(top, bottom, Ease.IN_OUT.apply(p)).withZ(side.sign * 0.12f)
    }
    for (side in Side.entries) body.arm(side, hands.getValue(side), chest.dir(0.6f, 0.8f, side.sign * 0.2f))
    straps(hands, v(-0.95f, 2.45f))
}

/** TRX pistol squat: holding the straps in front, down on one leg with the other straight out in front, and back up. */
internal fun suspensionPistolSquat() = Motion.rep(Track.rep(go = 1.4f, hold = 0.2f, back = 1.2f, rest = 0.4f), Camera(30f)) { p ->
    val lean = lerp(6f, 32f, p)
    body.torso(v(lerp(0f, -0.24f, p), lerp(0.91f, 0.4f, p), 0.05f), Basis.UPRIGHT.pitch(lean), nod = -lean * 0.4f)
    body.stand(Side.NEAR, 0.02f, z = 0.12f, out = 8f, pole = v(1f, 0.1f, 0.15f))
    body.legAngles(Side.FAR, limbDir(Basis.UPRIGHT, Side.FAR, lerp(34f, 86f, p), 2f), lerp(38f, 4f, p), fold = -V3.Y)
    val hands = Side.entries.associateWith { side -> body.shoulder(side) + v(0.42f, lerp(-0.1f, 0.15f, p), -side.sign * 0.03f) }
    for (side in Side.entries) body.arm(side, hands.getValue(side), v(-0.3f, -1f, side.sign * 0.5f))
    straps(hands, v(1.1f, 2.45f))
}

/**
 * Feet in the cradles (or on a ball), hands on the floor: the ankles at [ankle], the shoulder line over
 * the hands at [shoulders] and the pelvis at [pelvis]; the feet pointed along the shins.
 */
private fun FrameBuilder.cradlePlank(shoulders: V3, pelvis: V3, ankle: V3, kneePole: V3) {
    val u = (shoulders - pelvis).unit()
    body.torso(pelvis, Basis.UPRIGHT.pitch(leanOf(u)), nod = 8f)
    for (side in Side.entries) {
        val at = ankle.withZ(side.sign * 0.1f)
        val ik = twoBone(body.hip(side), at, BodySize.THIGH, BodySize.SHIN, kneePole)
        val shin = ik.end - ik.mid
        body.leg(side, at, kneePole, pointedAlong(shin, if (shin.length > 0f) orthogonal(ik.apex, shin.unit()) else -V3.Y, 36f))
    }
}

/** Hands of the cradle exercises, on the floor under the shoulders. */
private val CRADLE_HANDS = 0.38f

/**
 * TRX atomic push-up: feet in the cradles, a push-up, then the knees pulled in to the chest and
 * pushed back out to the plank.
 */
internal fun suspensionAtomicPushUp() = Motion.cycle(3.0f, camera = Camera(24f)) { phase ->
    val t = phase * 3.0f
    val anchor = v(-0.95f, 2.45f)
    val far = v(-0.92f, 0.33f)
    val line = LEGS + SPINE
    val top = deg(asin((0.62f - far.y) / line))
    val angle = ramp(t, 0.2f to top, 0.75f to deg(asin((0.3f - far.y) / line)), 1.25f to top)
    val tuck = ramp(t, 1.4f to 0f, 1.95f to 1f, 2.15f to 1f, 2.7f to 0f)
    val plankPelvis = far + dirXY(angle) * LEGS
    val shoulders = plankPelvis + dirXY(angle) * SPINE
    // The tuck: the hips rise a little and the knees come in under the chest, the straps swinging forward.
    val pelvis = lerp(plankPelvis, shoulders + dirXY(198f) * SPINE, tuck)
    val ankle = lerp(far, v(-0.36f, 0.56f), tuck)
    cradlePlank(shoulders, pelvis, ankle, lerp(v(0f, -1f, 0f), v(0.6f, -1f, 0f), tuck))
    val handX = far.x + line * cos(rad(top))
    for (side in Side.entries) body.arm(side, v(handX, BodySize.FIST_RADIUS, side.sign * 0.27f), v(-1f, 0.2f, side.sign * 0.8f))
    footStraps(anchor)
}

/** TRX pike: feet in the cradles from a high plank, the hips rise with straight legs into an upside-down V and lower again. */
internal fun suspensionPike() = Motion.rep(Track.rep(go = 1.2f, hold = 0.3f, back = 1.2f, rest = 0.5f), Camera(24f)) { p ->
    pike(p, ankleY = 0.33f) { footStraps(v(-0.95f, 2.45f)) }
}

/**
 * A pike from a high plank on straight arms ([p] 0 plank, 1 pike), the feet at height [ankleY] (in
 * cradles or on a ball); [props] draws what holds the feet once the legs are placed.
 */
private fun FrameBuilder.pike(p: Float, ankleY: Float, props: FrameBuilder.(V3) -> Unit) {
    val shoulders = v(CRADLE_HANDS + 0.01f, BodySize.FIST_RADIUS + armReach(0.27f - BodySize.SHOULDER_HALF, 0.01f, 0.985f))
    val line = LEGS + SPINE
    val plank = 180f + deg(asin((shoulders.y - ankleY) / line))
    val toPelvis = lerp(plank, 112f, Ease.IN_OUT.apply(p))
    val pelvis = shoulders + dirXY(toPelvis) * SPINE
    val drop = pelvis.y - ankleY
    val ankle = v(pelvis.x - sqrt((LEGS * LEGS - drop * drop).coerceAtLeast(0f)), ankleY)
    cradlePlank(shoulders, pelvis, ankle, v(0f, -1f, 0f))
    for (side in Side.entries) body.arm(side, v(CRADLE_HANDS, BodySize.FIST_RADIUS, side.sign * 0.27f), v(-1f, 0.2f, side.sign * 0.8f))
    props(ankle)
}

/**
 * On the back with the shoulders on the floor at [shoulders] and the trunk rising [angle] degrees to
 * the pelvis (a bridge); the head stays on the floor.
 */
private fun FrameBuilder.bridgeTrunk(shoulders: V3, angle: Float) {
    val pelvis = shoulders + dirXY(angle) * SPINE
    body.torso(pelvis, Basis.UPRIGHT.pitch(-90f - angle), nod = 10f + angle)
    // The arms long on the floor by the sides, the elbows (slightly bent) pointing out.
    for (side in Side.entries) body.arm(side, v(shoulders.x + 0.5f, BodySize.FIST_RADIUS + 0.01f, side.sign * 0.3f), v(0f, 0f, side.sign * 1f))
}

/** TRX hamstring curl: heels in the cradles, hips up in a straight line, the heels pulled in towards the glutes and pushed out. */
internal fun suspensionHamstringCurl() = Motion.rep(Tempo.liftFirst(), Camera(-24f)) { p ->
    val heel = v(0.8f, 0.36f)
    val shoulders = v(heel.x - sqrt((LEGS + SPINE) * (LEGS + SPINE) - (heel.y - 0.11f) * (heel.y - 0.11f)), 0.11f)
    val straight = deg(asin((heel.y - shoulders.y) / (LEGS + SPINE)))
    bridgeTrunk(shoulders, lerp(straight, 34f, p))
    val ankle = lerp(heel, v(0.3f, 0.44f), Ease.IN_OUT.apply(p))
    for (side in Side.entries) body.leg(side, ankle.withZ(side.sign * 0.1f), v(0.3f, 1f, 0f), Foot(dirXY(80f), dirXY(170f)))
    footStraps(v(0.95f, 2.45f))
}

/** TRX fallout: facing away, on the toes, the body falls forward as the straight arms rise from in front to overhead, then pulls back. */
internal fun suspensionFallout() = Motion.rep(Tempo.lowerFirst(), Camera(26f)) { p ->
    leanForward(v(-0.3f, 0.185f), lerp(70f, 44f, p))
    val hands = Side.entries.associateWith { side -> body.shoulder(side) + limbDir(body.chest, side, lerp(92f, 168f, p), 8f) * 0.555f }
    for (side in Side.entries) body.arm(side, hands.getValue(side), v(0f, -1f, side.sign * 0.6f))
    straps(hands, v(-0.95f, 2.45f))
}

// ---- Bosu --------------------------------------------------------------------------------------------

private const val BOSU_RADIUS = 0.315f
private const val BOSU_HEIGHT = 0.24f

/** A bosu, dome up, centred at [x]. Returns the dome (its surface height). */
private fun FrameBuilder.bosu(x: Float): Dome {
    val dome = Dome(v(x, 0.03f), BOSU_RADIUS, BOSU_HEIGHT - 0.03f, Ink.PAD, bias = -0.25f)
    add(Disc(v(x, 0.015f), V3.Y, BOSU_RADIUS, 0.03f, Ink.PLATE, bias = -0.26f))
    add(dome)
    return dome
}

/** Bosu squat: both feet on the dome, arms forward, a squat with the hips back and the knees over the toes. */
internal fun bosuSquat() = Motion.rep(Tempo.lowerFirst(), Camera(22f, 6f)) { p ->
    val dome = Dome(v(0f, 0.03f), BOSU_RADIUS, BOSU_HEIGHT - 0.03f)
    val floor = 0.03f + dome.surface(0.13f)
    val lean = lerp(4f, 34f, p)
    val basis = Basis.UPRIGHT.pitch(lean)
    body.torso(v(0.05f - 0.36f * sin(rad(lean)), floor + lerp(0.915f, 0.53f, p)), basis, nod = -lean * 0.45f)
    body.standOn(0f, width = 0.26f, out = 12f, floor = floor, pole = v(1f, 0.1f, 0.25f))
    for (side in Side.entries) body.armAngles(side, limbDir(Basis.UPRIGHT, side, lerp(30f, 88f, p), 4f), elbow = 6f, fold = V3.Y)
    bosu(0f)
}

/** Bosu push-up: hands on the dome under the chest, the straight body lowering to it and pressing up. */
internal fun bosuPushUp() = Motion.rep(Tempo.lowerFirst(), Camera(24f, 6f)) { p ->
    val ankle = v(-0.85f, 0.185f)
    val line = LEGS + SPINE
    val probe = Dome(V3.ZERO, BOSU_RADIUS, BOSU_HEIGHT - 0.03f)
    val handY = 0.03f + probe.surface(0.15f) + BodySize.FIST_RADIUS - 0.01f
    val top = asin((handY + 0.55f - ankle.y) / line)
    val bottom = asin((handY + 0.24f - ankle.y) / line)
    plankBody(ankle, deg(lerp(top, bottom, p)), nod = 8f)
    val handX = ankle.x + line * cos(top)
    for (side in Side.entries) body.arm(side, v(handX, handY, side.sign * 0.15f), v(-1f, 0.2f, side.sign * 0.8f))
    bosu(handX)
}

/** Single-leg balance on a bosu: on the near foot in the middle of the dome, the far knee up, the arms correcting small sways. */
internal fun bosuBalance() = Motion.cycle(4f, camera = Camera(36f, 6f)) { phase ->
    val sway = sin(phase * TAU)
    val probe = Dome(V3.ZERO, BOSU_RADIUS, BOSU_HEIGHT - 0.03f)
    val floor = 0.03f + probe.surface(0.06f)
    body.torso(v(-0.01f, floor + 0.9f, 0.05f + 0.012f * sway), Basis.UPRIGHT.pitch(3f).roll(-2f * sway), nod = -4f)
    body.stand(Side.NEAR, 0f, z = 0.06f, floor = floor, out = 6f, pole = v(1f, 0.1f, 0.1f))
    bentLeg(Side.FAR, 62f, 80f, out = 4f)
    for (side in Side.entries) body.armAngles(side, limbDir(body.chest, side, 30f, 38f + side.sign * 8f * sway), 20f, fold = body.chest.f)
    bosu(0.02f)
}

// ---- Swiss ball ------------------------------------------------------------------------------------

private const val BALL = 0.32f

/** A Swiss ball (fitball) at [center]. */
private fun FrameBuilder.swissBall(center: V3, radius: Float = BALL) {
    add(Ball(center, radius, Ink.PAD, bias = -0.08f))
    add(Ball(center + v(0.05f, radius * 0.45f, radius * 0.6f), radius * 0.16f, Ink.PAD_DARK, bias = -0.07f))
}

/**
 * Swiss ball crunch: the lower back on the ball and the feet on the floor; the trunk starts arched
 * back over the ball and curls up, hands behind the head.
 */
internal fun swissBallCrunch() = Motion.rep(Tempo.liftFirst(), Camera(-26f, 6f)) { p ->
    val ball = v(0.1f, BALL)
    val basis = Basis.UPRIGHT.pitch(-80f)
    // The lower back rests on the top of the ball.
    val lumbar = basis.u * 0.15f - basis.f * 0.11f
    val pelvis = v(ball.x, ball.y + BALL) - lumbar
    body.torso(pelvis, basis, bend = lerp(-22f, 36f, p), nod = lerp(-10f, 20f, p))
    body.standOn(0.78f, width = 0.32f, out = 10f, pole = v(0.3f, 1f, 0.2f))
    handsBehindHead()
    swissBall(ball)
}

/** Swiss ball pike: hands on the floor and the shins on the ball, the hips rise with straight legs rolling the ball in to the toes. */
internal fun swissBallPike() = Motion.rep(Track.rep(go = 1.2f, hold = 0.3f, back = 1.2f, rest = 0.5f), Camera(24f)) { p ->
    val top = 2 * BALL
    pike(p, ankleY = top + 0.05f) { ankle -> swissBall(v(ankle.x + 0.02f, BALL)) }
}

/** Swiss ball hamstring curl: heels on the ball and hips up, the ball rolled in towards the glutes and out again. */
internal fun swissBallHamstringCurl() = Motion.rep(Tempo.liftFirst(), Camera(-24f)) { p ->
    val heel = v(0.86f, 2 * BALL + 0.04f)
    val shoulders = v(heel.x - sqrt((LEGS + SPINE) * (LEGS + SPINE) - (heel.y - 0.11f) * (heel.y - 0.11f)), 0.11f)
    val straight = deg(asin((heel.y - shoulders.y) / (LEGS + SPINE)))
    bridgeTrunk(shoulders, lerp(straight, 36f, p))
    val ankle = lerp(heel, v(0.42f, heel.y), Ease.IN_OUT.apply(p))
    for (side in Side.entries) body.leg(side, ankle.withZ(side.sign * 0.1f), v(0.3f, 1f, 0f), Foot(dirXY(80f), dirXY(170f)))
    swissBall(v(ankle.x - 0.02f, BALL))
}

/**
 * Swiss ball V-pass: lying on the back with the ball overhead, arms and legs rise to meet above the
 * hips and the ball passes to the feet, which lower it while the arms go back overhead; then back.
 */
internal fun swissBallPass() = Motion.cycle(4.8f, camera = Camera(-30f, 10f)) { phase ->
    val t = phase * 4.8f
    val r = 0.27f
    val up = ramp(t, 0.3f to 0f, 1.2f to 1f, 1.5f to 1f, 2.4f to 0f, 2.7f to 0f, 3.6f to 1f, 3.9f to 1f, 4.8f to 0f)
    val inFeet = ramp(t, 1.2f to 0f, 1.5f to 1f, 3.6f to 1f, 3.9f to 0f)
    body.torso(v(0f, 0.115f), Basis.SUPINE, bend = 28f * up, nod = 6f + 16f * up)
    val arm = lerp(174f, 62f, up)
    val leg = lerp(12f, 100f, up)
    for (side in Side.entries) {
        body.armAngles(side, (dirXY(arm) + V3.Z * (side.sign * 0.42f)).unit(), 8f, fold = body.chest.f)
        body.legAngles(side, (dirXY(leg) + V3.Z * (side.sign * 0.16f)).unit(), 4f, fold = dirXY(leg - 90f), foot = pointedAlong(dirXY(leg), dirXY(leg + 90f), 40f))
    }
    val hands = (body.handOf(Side.NEAR) + body.handOf(Side.FAR)) * 0.5f
    val feet = (body.legOf(Side.NEAR).toe + body.legOf(Side.FAR).toe) * 0.5f
    val ball = lerp(hands + dirXY(arm) * 0.08f, feet - dirXY(leg) * 0.12f, inFeet)
    swissBall(ball.withY(ball.y.coerceAtLeast(r)), r)
    mat()
}

/** Stir the pot: a plank on the forearms on a Swiss ball, the forearms drawing small circles, the hips still. */
internal fun stirThePot() = Motion.cycle(3.2f, camera = Camera(30f, 10f)) { phase ->
    val a = phase * TAU
    val circle = v(0.045f * cos(a), 0f, 0.045f * sin(a))
    val ball = v(0.36f, BALL) + circle
    val elbowY = 2 * BALL + 0.03f
    val ankle = v(-0.73f, 0.185f)
    val line = LEGS + SPINE
    plankBody(ankle, deg(asin((elbowY + BodySize.UPPER_ARM - ankle.y) / line)), nod = 6f)
    for (side in Side.entries) {
        val hand = v(ball.x + 0.24f, elbowY + 0.03f, side.sign * 0.05f + circle.z)
        body.arm(side, hand, v(0f, -1f, side.sign * 0.4f))
    }
    swissBall(ball)
}

// ---- Balance -------------------------------------------------------------------------------------

/** Single-leg balance: on the near leg, the far knee up at hip height, hands on the hips, small sways. */
internal fun singleLegBalance() = Motion.cycle(4f, camera = Camera(36f, 6f)) { phase ->
    val sway = sin(phase * TAU)
    body.torso(v(-0.01f, 0.905f, 0.05f + 0.01f * sway), Basis.UPRIGHT.pitch(2f).roll(-1.5f * sway), nod = -3f)
    body.stand(Side.NEAR, 0f, z = 0.07f, out = 6f, pole = v(1f, 0.1f, 0.1f))
    bentLeg(Side.FAR, 88f, 90f, out = 4f)
    for (side in Side.entries) body.arm(side, body.onHips(0.03f, 0.1f, side.sign * 0.17f), v(-0.3f, 0f, side.sign * 1f))
}

/**
 * Crow pose: squatting with the hands planted and the knees resting on the backs of the upper arms,
 * the body leans forward until the feet lift off the floor; held, then the feet come down.
 */
internal fun crowPose() = Motion.rep(Track.rep(go = 1.2f, hold = 1.4f, back = 1.0f, rest = 0.6f), Camera(30f, 6f)) { p ->
    val handX = 0.26f
    val shoulders = lerp(v(0.2f, 0.63f), v(0.34f, 0.52f), p)
    val pelvis = shoulders + dirXY(lerp(146f, 156f, p)) * SPINE
    body.torso(pelvis, Basis.UPRIGHT.pitch(leanOf((shoulders - pelvis).unit())), bend = 14f, nod = -30f)
    for (side in Side.entries) body.arm(side, v(handX, BodySize.FIST_RADIUS, side.sign * 0.18f), v(-1f, 0.1f, side.sign * 0.35f))
    for (side in Side.entries) {
        val elbow = body.armOf(side).elbow
        val ankle = lerp(v(-0.02f, BodySize.ANKLE, side.sign * 0.1f), v(-0.1f, 0.36f, side.sign * 0.07f), Ease.IN_OUT.apply(p))
        val hip = body.hip(side)
        val pole = (elbow + V3.Y * 0.06f - (hip + ankle) * 0.5f).unit()
        val air = Ease.IN_OUT.apply(p)
        val foot = Foot(lerp(V3.X, dirXY(-60f), air).unit(), lerp(V3.Y, dirXY(30f), air).unit())
        body.leg(side, footAbove(ankle, foot), pole, foot)
    }
}

/** V-up: from lying long, the straight legs and the trunk rise together, the hands reaching for the feet, then lower slowly. */
internal fun vUp() = Motion.rep(Track.rep(go = 0.9f, hold = 0.2f, back = 1.1f, rest = 0.4f), Camera(-26f, 8f)) { p ->
    val e = Ease.IN_OUT.apply(p)
    body.torso(v(0f, lerp(0.115f, 0.125f, e)), Basis.SUPINE.pitch(lerp(0f, 60f, e)), bend = 18f * e, nod = lerp(4f, 16f, e))
    val leg = lerp(3f, 58f, e)
    for (side in Side.entries) {
        body.legAngles(side, (dirXY(leg) + V3.Z * (side.sign * 0.03f)).unit(), 3f, fold = dirXY(leg - 90f), foot = pointedAlong(dirXY(leg), dirXY(leg + 90f)))
        body.armAngles(side, (dirXY(lerp(176f, 52f, e)) + V3.Z * (side.sign * 0.12f)).unit(), 6f, fold = body.chest.f)
    }
    mat()
}

// ---- Floor Pilates ------------------------------------------------------------------------------------

/** Pilates hundred: head and shoulders curled up, legs long at an angle, the straight arms pumping up and down beside the hips. */
internal fun pilatesHundred() = Motion.cycle(2f, camera = Camera(-30f, 8f)) { phase ->
    val beat = sin(phase * TAU * 5)
    body.torso(v(0f, 0.115f), Basis.SUPINE, bend = 30f, nod = 18f)
    for (side in Side.entries) {
        body.legAngles(side, (dirXY(48f) + V3.Z * (side.sign * 0.03f)).unit(), 3f, fold = dirXY(-42f), foot = pointedAlong(dirXY(48f), dirXY(138f)))
        body.armAngles(side, v(1f, 0.02f + 0.1f * beat, side.sign * 0.3f).unit(), 4f, fold = V3.Y)
    }
    mat()
}

/** Pilates roll-up: from lying with the arms overhead, curling up one vertebra at a time to reach past the toes, and rolling back down. */
internal fun pilatesRollUp() = Motion.rep(Track.rep(go = 2.2f, hold = 0.4f, back = 2.2f, rest = 0.5f), Camera(-34f, 10f)) { p ->
    val curl = sin(PI.toFloat() * p)
    body.torso(v(0f, lerp(0.115f, 0.125f, p)), Basis.SUPINE.pitch(104f * Ease.IN_OUT.apply(p)), bend = 40f * curl + 22f * p, nod = 10f + 15f * curl)
    for (side in Side.entries) {
        val toes = dirXY(80f)
        body.leg(side, v(0.855f, 0.085f, side.sign * 0.08f), v(0f, 1f, 0f), Foot(toes, rotate(toes, V3.Z, rad(90f))))
    }
    val angle = lerp(172f, -6f, Ease.IN_OUT.apply(p))
    for (side in Side.entries) body.armAngles(side, (dirXY(angle) + V3.Z * (side.sign * 0.12f)).unit(), 4f, fold = body.chest.f)
    mat()
}

/** A leg of a figure on its back: the thigh [thigh] degrees above the floor, the knee bent [knee] (folding towards the back of the thigh). */
private fun FrameBuilder.backLeg(side: Side, thigh: Float, knee: Float, spread: Float = 0.03f) {
    body.legAngles(side, (dirXY(thigh) + V3.Z * (side.sign * spread)).unit(), knee, fold = dirXY(thigh - 90f), foot = if (knee < 20f) pointedAlong(dirXY(thigh), dirXY(thigh + 90f)) else null)
}

/** Pilates single-leg stretch: curled up, one knee hugged to the chest while the other leg stretches out long and low, switching. */
internal fun pilatesSingleLegStretch() = Motion.cycle(2.4f, camera = Camera(-30f, 10f)) { phase ->
    body.torso(v(0f, 0.115f), Basis.SUPINE, bend = 30f, nod = 18f)
    val hug = Side.entries.associateWith { side -> 0.5f + 0.5f * sin(phase * TAU + if (side == Side.NEAR) 0f else PI.toFloat()) }
    for (side in Side.entries) backLeg(side, lerp(28f, 118f, hug.getValue(side)), lerp(3f, 118f, hug.getValue(side)))
    val nearKnee = body.legOf(Side.NEAR).knee
    val farKnee = body.legOf(Side.FAR).knee
    val bent = lerp(farKnee, nearKnee, hug.getValue(Side.NEAR))
    for (side in Side.entries) body.arm(side, bent + v(-0.04f, -0.02f, side.sign * 0.09f), v(0f, -0.3f, side.sign * 1f))
    mat()
}

/** Pilates double-leg stretch: curled up hugging both knees, then arms overhead and legs long at once, the arms circling back round to the knees. */
internal fun pilatesDoubleLegStretch() = Motion.cycle(3.2f, camera = Camera(-34f, 12f)) { phase ->
    val t = phase * 3.2f
    val long = ramp(t, 0.3f to 0f, 0.9f to 1f, 1.3f to 1f, 2.4f to 0f)
    body.torso(v(0f, 0.115f), Basis.SUPINE, bend = lerp(32f, 26f, long), nod = 18f)
    for (side in Side.entries) {
        backLeg(side, lerp(112f, 34f, long), lerp(112f, 3f, long))
        val dir = vramp(t, 0.3f to v(0.78f, 0.55f, side.sign * 0.25f), 0.9f to v(-0.86f, 0.5f, side.sign * 0.15f), 1.3f to v(-0.86f, 0.5f, side.sign * 0.15f), 1.85f to v(0f, 0.35f, side.sign * 0.94f), 2.4f to v(0.78f, 0.55f, side.sign * 0.25f))
        body.armAngles(side, dir.unit(), lerp(40f, 6f, long), fold = body.chest.f)
    }
    mat()
}

/** Pilates swimming: face down with arms and legs long and lifted, the opposite arm and leg fluttering up and down. */
internal fun pilatesSwimming() = Motion.cycle(1.6f, cycles = 2, camera = Camera(24f, 6f)) { phase ->
    body.torso(v(0f, 0.115f), Basis.PRONE.pitch(-5f), bend = -10f, nod = -8f)
    for (side in Side.entries) {
        val beat = sin(phase * TAU + if (side == Side.NEAR) 0f else PI.toFloat())
        val legs = rad(8f + 6f * beat)
        body.legAngles(side, v(-cos(legs), sin(legs), side.sign * 0.06f), 4f, fold = V3.Y, foot = Foot(dirXY(200f), dirXY(290f)))
        val arms = rad(12f - 7f * beat)
        body.armAngles(side, v(cos(arms), sin(arms), side.sign * 0.25f), 6f, fold = -V3.Y)
    }
    mat()
}

/** Pilates teaser: legs held up at an angle, the trunk rolls up into a V balance with the arms reaching along the legs, and back down. */
internal fun pilatesTeaser() = Motion.rep(Track.rep(go = 1.6f, hold = 0.6f, back = 1.8f, rest = 0.5f), Camera(-26f, 8f)) { p ->
    val e = Ease.IN_OUT.apply(p)
    body.torso(v(0f, lerp(0.115f, 0.125f, e)), Basis.SUPINE.pitch(lerp(0f, 58f, e)), bend = 24f * sin(PI.toFloat() * p) + 6f * e, nod = lerp(6f, 14f, e))
    for (side in Side.entries) {
        backLeg(side, 46f, 3f)
        body.armAngles(side, (dirXY(lerp(172f, 50f, e)) + V3.Z * (side.sign * 0.12f)).unit(), 6f, fold = body.chest.f)
    }
    mat()
}

/**
 * Pilates side kick, seen from above: lying on the far side with the head on the lower arm, the top
 * leg at hip height kicks forward and sweeps back behind, the trunk still.
 */
internal fun pilatesSideKick() = Motion.cycle(2.6f, camera = Camera(30f, 52f)) { phase ->
    val t = phase * 2.6f
    // On the far side, facing the viewer's side of the mat: chest towards +Z, head towards +X, the near side up.
    val basis = Basis(V3.Z, V3.X, V3.Y)
    body.torso(v(-0.1f, 0.3f), basis, nod = 4f)
    val kick = ramp(t, 0.2f to 0f, 0.6f to 62f, 0.75f to 50f, 0.9f to 66f, 1.6f to -24f, 2.2f to 0f)
    val top = -basis.u * cos(rad(kick)) + basis.f * sin(rad(kick))
    body.legAngles(Side.NEAR, top, 3f, fold = -basis.f, foot = pointedAlong(top, basis.f, 34f))
    val bottom = (-basis.u + basis.f * 0.18f).unit()
    body.legAngles(Side.FAR, bottom, 3f, fold = -basis.f, foot = pointedAlong(bottom, basis.f, 34f))
    // The lower arm long overhead under the head (its elbow towards the chest side), the top hand on the mat in front (elbow up and back).
    body.arm(Side.FAR, v(0.62f, BodySize.FIST_RADIUS + 0.04f, -0.04f), v(0f, 0.4f, 1f))
    body.arm(Side.NEAR, v(0.26f, BodySize.FIST_RADIUS, 0.3f), v(0f, 0.6f, -0.6f))
    mat()
}

/**
 * Pilates shoulder bridge: the hips roll up into a bridge on the shoulders, one leg rises straight up
 * to the ceiling and back, and the spine rolls back down to the mat.
 */
internal fun pilatesShoulderBridge() = Motion.rep(
    Track.keys(0f to 0f, 0.4f to 0f, 1.3f to 1f, 1.5f to 1f, 2.2f to 2f, 2.5f to 2f, 3.2f to 1f, 3.4f to 1f, 4.3f to 0f),
    Camera(-26f, 8f),
) { stage ->
    val bridge = stage.coerceIn(0f, 1f)
    val kick = (stage - 1f).coerceIn(0f, 1f)
    val shoulders = v(-0.5f, 0.11f)
    bridgeTrunk(shoulders, lerp(4f, 30f, bridge))
    for (side in Side.entries) {
        val planted = v(0.3f, BodySize.ANKLE, side.sign * 0.11f)
        val pole = v(1f, 1f, side.sign * 0.1f)
        if (side == Side.FAR || kick <= 0f) {
            body.leg(side, planted, pole, Foot.flat(side, 4f))
            continue
        }
        // The near leg leaves the floor: from the planted angles to straight up, by angles so the knee
        // keeps folding the right way, the foot turning from flat to pointed.
        val hip = body.hip(side)
        val ik = twoBone(hip, planted, BodySize.THIGH, BodySize.SHIN, pole)
        val thigh0 = angleXY(ik.mid - hip)
        val knee0 = 180f - deg(kotlin.math.acos(((hip - ik.mid).unit() dot (ik.end - ik.mid).unit()).coerceIn(-1f, 1f)))
        // The thigh rises first, then the knee straightens, so the foot never drops into the floor.
        val e = Ease.IN_OUT.apply(kick)
        val thigh = lerp(thigh0, 84f, Ease.IN_OUT.apply((kick / 0.6f).coerceIn(0f, 1f)))
        val knee = lerp(knee0, 3f, Ease.IN_OUT.apply(((kick - 0.3f) / 0.7f).coerceIn(0f, 1f)))
        val dir = (dirXY(thigh) + V3.Z * (side.sign * 0.02f)).unit()
        val fold = dirXY(thigh - 90f)
        val shin = dir * cos(rad(knee)) + orthogonal(fold, dir) * sin(rad(knee))
        // The front of the shin: the front of the thigh turned by the knee's bend.
        val air = pointedAlong(shin, dirXY(thigh + 90f - knee), 40f)
        val flat = Foot.flat(side, 4f)
        // Flat while the foot is on the floor, pointed once it is off it.
        val ankleY = (hip + dir * BodySize.THIGH + shin.unit() * BodySize.SHIN).y
        val f = Ease.IN_OUT.apply(((ankleY - BodySize.ANKLE) / 0.12f).coerceIn(0f, 1f))
        val footDir = lerp(flat.dir, air.dir, f).unit()
        body.legAngles(side, dir, knee, fold = fold, foot = Foot(footDir, orthogonal(lerp(flat.up, air.up, f), footDir)))
    }
    mat()
}
