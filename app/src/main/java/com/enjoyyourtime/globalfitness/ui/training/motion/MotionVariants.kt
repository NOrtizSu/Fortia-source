package com.enjoyyourtime.globalfitness.ui.training.motion

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin

// Machine, cable, free-weight and calisthenics variations added in 1.6: plate-loaded machines, the
// standing leg curl, cable pulldowns, presses, flyes and raises, the Smith reverse lunge, floor press,
// incline fly, walking lunge, archer push-up, ring dip, neutral-grip pull-up, suspension row and the
// sissy squat. They reuse the shared helpers (seats, stacks, handles, benches) like the originals.

// ---- Machines -------------------------------------------------------------------------------------

/**
 * Iso-lateral chest press (plate-loaded): seated against the back pad, each arm presses its own lever
 * forward and slightly in, one after the other; each lever pivots low behind the seat, its plates on
 * a horn by the pivot.
 */
internal fun isoLateralChestPress() = Motion.cycle(4.4f, camera = Camera(28f)) { phase ->
    val seatTop = 0.48f
    seated(seatTop = seatTop, recline = 12f, footX = 0.42f, width = 0.3f, drawSeat = false)
    machineSeat(0f, seatTop)
    backRest(v(-0.14f, seatTop), 12f, length = 0.72f)
    val shoulders = body.shoulders()
    for (side in Side.entries) {
        val start = if (side == Side.NEAR) 0f else 2.2f
        val t = phase * 4.4f - start
        val p = if (t in 0f..2.2f) ramp(t, 0.2f to 0f, 1.0f to 1f, 1.2f to 1f, 2.1f to 0f) else 0f
        val from = body.onChest(0.16f, -0.12f, side.sign * 0.3f)
        val to = (shoulders + dirXY(4f) * 0.55f).withZ(side.sign * 0.17f)
        val hand = lerp(from, to, p)
        body.arm(side, hand, v(-0.4f, -1f, side.sign * 0.9f))
        // Lever from its pivot low behind the seat and outside it, up to the handle; the plates on a
        // horn just behind the pivot, so they stay low and clear of the head.
        val pivot = v(-0.4f, 0.32f, side.sign * 0.55f)
        val grip = hand - V3.Y * 0.08f + V3.Z * (side.sign * 0.04f)
        add(Rod(hand + V3.Y * 0.08f, hand - V3.Y * 0.08f, 0.02f, Ink.METAL, bias = -0.02f))
        add(Rod(pivot, grip, 0.03f, Ink.FRAME, bias = -0.25f))
        val horn = pivot - (grip - pivot).unit() * 0.1f + V3.Z * (side.sign * 0.1f)
        add(Disc(horn, V3.Z, 0.17f, 0.05f, Ink.PLATE, hub = 0.035f, bias = -0.3f))
        add(Rod(pivot.withY(0.02f), pivot, 0.035f, Ink.FRAME, fit = false, bias = -0.5f))
    }
}

/**
 * High row machine: seated, thighs under the pad and the chest against the chest pad, the handles
 * pulled from high in front down and back to the ribs, elbows driving down.
 */
internal fun highRowMachine() = Motion.rep(Tempo.liftFirst(), Camera(30f)) { p ->
    val seatTop = 0.5f
    seated(seatTop = seatTop, recline = -6f, footX = 0.45f, width = 0.32f, drawSeat = false)
    machineSeat(0f, seatTop)
    roller(v(0.3f, seatTop + SEAT_HIP + 0.135f), length = 0.42f)
    add(Block(body.onChest(0.2f, -0.22f), body.chest, v(0.04f, 0.2f, 0.15f), Ink.PAD, bias = -0.05f))
    val shoulders = body.shoulders()
    for (side in Side.entries) {
        val start = (shoulders + dirXY(52f) * 0.55f).withZ(side.sign * 0.24f)
        val end = body.onChest(0.04f, -0.22f, side.sign * 0.27f)
        val hand = lerp(start, end, p)
        body.arm(side, hand, v(-1f, -0.6f, side.sign * 0.5f))
        machineHandle(hand, pivot = v(0.85f, 1.95f, side.sign * 0.3f))
    }
    add(Rod(v(0.85f, 0f), v(0.85f, 2.0f), 0.04f, Ink.FRAME, fit = false, bias = -0.5f))
}

/**
 * Chest-supported T-bar row: lying face down on the incline pad, feet on the platform, the handles of
 * the loaded lever rowed from straight arms up to the lower chest.
 */
internal fun tBarRowMachine() = Motion.rep(Tempo.liftFirst(), Camera(28f)) { p ->
    val lean = 50f
    val basis = Basis.UPRIGHT.pitch(lean)
    body.torso(v(-0.34f, 0.76f), basis, nod = -22f, protract = lerp(0.03f, -0.03f, p))
    body.standOn(-0.64f, width = 0.32f, out = 8f, floor = 0.08f, lift = 18f, pole = v(1f, 0.1f, 0.2f))
    add(Block(v(-0.6f, 0.04f), Basis.UPRIGHT, v(0.18f, 0.04f, 0.26f), Ink.FRAME, bias = -0.3f))
    val padStart = body.onHips(0.17f, -0.05f)
    pad(padStart, padStart + basis.u * 0.62f, width = 0.32f, thick = 0.08f, up = -basis.f, bias = -0.15f)
    add(Rod(v(padStart.x + 0.05f, 0.02f), padStart + basis.u * 0.3f + basis.f * 0.05f, 0.03f, Ink.FRAME, bias = -0.3f))
    val shoulders = body.shoulders()
    val hang = v(shoulders.x + 0.04f, shoulders.y - armReach(0.04f, 0.04f))
    val top = body.onChest(0.04f, -0.26f)
    val handle = lerp(hang, v(top.x, top.y), p)
    for (side in Side.entries) body.arm(side, handle.withZ(side.sign * 0.21f), v(-1f, 1f, side.sign * 0.3f))
    add(Rod(handle.withZ(-0.24f), handle.withZ(0.24f), 0.02f, Ink.METAL))
    // The lever pivots in front on the floor; plates on its end beyond the handles.
    val pivot = v(1.05f, 0.25f)
    val along = (handle - pivot).unit()
    add(Rod(pivot, handle - along * 0.06f, 0.03f, Ink.FRAME, bias = -0.2f))
    add(Disc(handle + along * 0.14f, along, 0.2f, 0.05f, Ink.PLATE, hub = 0.035f, bias = -0.25f))
    add(Rod(handle, handle + along * 0.2f, 0.02f, Ink.METAL, bias = -0.25f))
    add(Block(pivot - V3.Y * 0.12f, Basis.UPRIGHT, v(0.08f, 0.12f, 0.1f), Ink.FRAME, bias = -0.3f))
}

/** Standing leg curl: on the far leg with the near thigh against the pad, the near heel curled up to the glute against the roller. */
internal fun standingLegCurl() = Motion.rep(Tempo.liftFirst(), Camera(26f)) { p ->
    // The standing foot on the machine's platform, so the working foot hangs clear of the floor.
    val platform = 0.12f
    body.torso(v(0.02f, platform + BodySize.STAND - 0.01f, -0.03f), Basis.UPRIGHT.pitch(10f), nod = -4f)
    body.stand(Side.FAR, 0f, z = -0.12f, floor = platform, out = 6f)
    box(v(-0.2f, 0f, -0.32f), v(0.3f, platform, -0.02f), Ink.FRAME, bias = -0.3f)
    val thigh = limbDir(Basis.UPRIGHT, Side.NEAR, 6f, 2f)
    body.legAngles(Side.NEAR, thigh, lerp(10f, 112f, p), fold = orthogonal(-V3.X, thigh))
    val leg = body.legOf(Side.NEAR)
    val shin = (leg.ankle - leg.knee).unit()
    val roller = leg.ankle - shin * 0.05f - orthogonal(leg.apex, shin) * 0.075f
    add(Rod(roller.withZ(0.0f), roller.withZ(0.24f), 0.05f, Ink.PAD))
    add(Rod(leg.knee.withZ(0.3f), roller.withZ(0.3f), 0.025f, Ink.FRAME, bias = 0.1f))
    add(Block(leg.knee + v(0.11f, 0.06f, 0f), Basis.UPRIGHT, v(0.04f, 0.1f, 0.1f), Ink.PAD, bias = -0.1f))
    val handle = v(0.34f, platform + 1.12f)
    for (side in Side.entries) body.arm(side, handle.withZ(side.sign * 0.2f), v(-0.3f, -1f, side.sign * 0.6f))
    add(Rod(handle.withZ(-0.26f), handle.withZ(0.26f), 0.02f, Ink.METAL))
    add(Rod(v(0.42f, 0f), v(0.42f, 1.3f), 0.04f, Ink.FRAME, fit = false, bias = -0.5f))
}

// ---- Cable --------------------------------------------------------------------------------------

/** Reverse-grip lat pulldown: palms facing the body, hands shoulder-width; the bar comes down to the upper chest with the elbows close and forward. */
internal fun reverseGripPulldown() = Motion.rep(Tempo.liftFirst(), Camera(26f)) { p ->
    val seatTop = 0.44f
    seated(seatTop = seatTop, recline = lerp(8f, 18f, p), footX = 0.42f, width = 0.3f, nod = -6f)
    roller(v(0.3f, seatTop + SEAT_HIP + 0.135f), length = 0.42f)
    val shoulders = body.shoulders()
    val handZ = 0.2f
    val top = v(shoulders.x + 0.06f, shoulders.y + armReach(abs(handZ - BodySize.SHOULDER_HALF), 0.06f))
    val bottom = body.onChest(0.18f, -0.04f)
    val bar = lerp(top, bottom.withZ(0f), p)
    for (side in Side.entries) body.arm(side, bar.withZ(side.sign * handZ), v(0.8f, -1f, side.sign * 0.25f))
    handleBar(bar + V3.Y * 0.02f, 1.2f)
    val pulley = v(top.x, 2.3f)
    cableStack(-0.5f, pulley, bar + V3.Y * 0.02f, lift = p * 0.22f)
    add(Rod(v(-0.5f, 2.3f), pulley, 0.035f, Ink.FRAME, fit = false, bias = -0.5f))
}

/** Standing cable chest press: in a split stance with the back to two pulleys at chest height, the handles pressed from the chest to arm's length. */
internal fun cableChestPress() = Motion.rep(Tempo.liftFirst(), Camera(28f)) { p ->
    body.torso(v(0f, BodySize.STAND - 0.02f), Basis.UPRIGHT.pitch(8f))
    body.stand(Side.NEAR, 0.16f, z = 0.13f)
    body.stand(Side.FAR, -0.18f, z = -0.13f)
    val shoulders = body.shoulders()
    for (side in Side.entries) {
        val start = body.onChest(0.16f, -0.12f, side.sign * 0.3f)
        val end = (shoulders + dirXY(2f) * 0.55f).withZ(side.sign * 0.18f)
        val hand = lerp(start, end, p)
        body.arm(side, hand, v(-0.4f, -1f, side.sign * 0.9f))
        add(Rod(hand - V3.Y * 0.06f, hand + V3.Y * 0.06f, 0.02f, Ink.METAL))
        add(Cord(listOf(v(-0.8f, shoulders.y - 0.1f, side.sign * 0.75f), hand), 0.012f, Ink.CABLE, fit = false, bias = -0.1f))
        add(Block(v(-0.9f, 1.05f, side.sign * 0.8f), Basis.UPRIGHT, v(0.1f, 1.05f, 0.08f), Ink.FRAME, fit = false, bias = -0.6f))
    }
}

/**
 * Cable reverse fly, seen from the front: each hand holds the cable of the opposite pulley at shoulder
 * height, the arms crossed in front; they open out to the sides, elbows soft.
 */
internal fun cableReverseFly() = Motion.rep(Tempo.liftFirst(), Camera(78f, 8f)) { p ->
    body.torso(v(-0.04f, BodySize.STAND - 0.01f), Basis.UPRIGHT.pitch(6f))
    body.standOn(0f, width = 0.3f)
    val sweep = lerp(104f, 4f, p)
    for (side in Side.entries) {
        val dir = v(sin(rad(sweep)), -0.05f, side.sign * cos(rad(sweep)))
        body.armAngles(side, dir, 10f, fold = V3.Y)
        val hand = body.handOf(side)
        add(Rod(hand - V3.Y * 0.05f, hand + V3.Y * 0.05f, 0.02f, Ink.METAL))
        add(Cord(listOf(v(-0.1f, 1.4f, -side.sign * 1.1f), hand), 0.012f, Ink.CABLE, fit = false, bias = -0.1f))
    }
}

/**
 * Cable Y-raise, seen from the front: holding the cables of the opposite low pulleys with the hands
 * crossed in front of the hips, the straight arms rise up and out into a Y.
 */
internal fun cableYRaise() = Motion.rep(Tempo.liftFirst(), Camera(80f, 6f)) { p ->
    body.torso(v(0f, BodySize.STAND), Basis.UPRIGHT.pitch(4f))
    body.standOn(0f, width = 0.3f)
    for (side in Side.entries) {
        body.armAngles(side, limbDir(body.chest, side, lerp(24f, -22f, p), lerp(-14f, 148f, p)), 8f, fold = body.chest.f)
        val hand = body.handOf(side)
        add(Rod(hand - V3.Y * 0.05f, hand + V3.Y * 0.05f, 0.02f, Ink.METAL))
        add(Cord(listOf(v(0.05f, 0.16f, -side.sign * 0.75f), hand), 0.012f, Ink.CABLE, fit = false, bias = -0.1f))
    }
}

/** Cable upright row: from a low pulley in front, the bar pulled up along the body to the chest with the elbows leading up and out. */
internal fun cableUprightRow() = Motion.rep(Tempo.liftFirst(), Camera(36f)) { p ->
    body.torso(v(0f, BodySize.STAND), nod = 4f)
    body.standOn(0f, width = 0.28f)
    val shoulders = body.shoulders()
    val half = 0.14f
    val bottom = v(shoulders.x + 0.08f, shoulders.y - armReach(abs(half - BodySize.SHOULDER_HALF), 0.08f))
    val top = v(shoulders.x + 0.13f, shoulders.y + 0.02f)
    val bar = lerp(bottom, top, p)
    for (side in Side.entries) body.arm(side, bar.withZ(side.sign * half), v(-0.2f, 1f, side.sign * 1f))
    handleBar(bar, 0.4f)
    cable(v(0.42f, 0.12f), bar)
    add(Block(v(0.52f, 1.0f), Basis.UPRIGHT, v(0.1f, 1.0f, 0.18f), Ink.FRAME, fit = false, bias = -0.6f))
}

// ---- Smith, dumbbells -----------------------------------------------------------------------------

/**
 * Smith machine reverse lunge: the bar on the upper back runs straight down the rails while the far
 * foot steps back and the rear knee drops; the front foot stays planted a little ahead of the bar.
 */
internal fun smithReverseLunge() = Motion.rep(Track.rep(go = 1.3f, hold = 0.2f, back = 1.1f, rest = 0.4f), Camera(24f)) { p ->
    // The bar cannot travel back, so the hips sink from the start of the step instead.
    val step = Ease.IN_OUT.apply((p / 0.5f).coerceIn(0f, 1f))
    val sink = Ease.IN_OUT.apply((p / 0.8f).coerceIn(0f, 1f))
    body.torso(v(0.1f, lerp(BodySize.STAND, 0.48f, sink)), Basis.UPRIGHT.pitch(4f))
    body.stand(Side.NEAR, 0.18f, z = 0.11f)
    body.stand(
        Side.FAR,
        lerp(0.05f, -0.5f, step),
        z = -0.11f,
        floor = 0.07f * sin(PI.toFloat() * step) * (1 - sink),
        // The rear heel rises early, as the hips start sinking with the step.
        lift = lerp(0f, 55f, Ease.IN_OUT.apply(((p - 0.05f) / 0.6f).coerceIn(0f, 1f))),
        pole = v(0.4f, -1f, 0f),
    )
    val bar = body.onChest(-0.13f, -0.06f)
    barbell(bar)
    for (side in Side.entries) body.arm(side, bar.withZ(side.sign * 0.44f), body.chest.dir(-0.7f, -1f, side.sign * 0.4f))
    smithRails(bar.x, 2.1f)
}

/**
 * Dumbbell floor press: on the back on the floor, knees bent, the dumbbells pressed from the elbows
 * resting on the floor (about 45° from the body) to straight arms above the chest.
 */
internal fun dumbbellFloorPress() = Motion.rep(Tempo.lowerFirst(), Camera(-32f, 6f)) { p ->
    body.torso(v(0f, 0.115f), Basis.SUPINE, nod = 8f)
    body.standOn(0.42f, width = 0.3f, out = 6f, pole = v(0.3f, 1f, 0.1f))
    for (side in Side.entries) {
        val s = body.shoulder(side)
        val top = s + v(0.03f, armReach(0.04f, 0.03f), side.sign * 0.04f)
        val bottom = s + v(0.105f, 0.215f, side.sign * 0.205f)
        val hand = lerp(top, bottom, p)
        body.arm(side, hand, v(0.3f, -1f, side.sign * 1f))
        dumbbell(hand)
    }
    mat()
}

/** Incline dumbbell fly: on a 30° bench, the arms open in a wide arc with soft elbows until level with the bench, and close above the chest. */
internal fun inclineDumbbellFly() = Motion.rep(Tempo.lowerFirst(), Camera(64f, 24f)) { p ->
    val incline = 30f
    val basis = Basis.UPRIGHT.pitch(incline - 90f)
    val junction = v(0.05f, Kit.BENCH_TOP)
    body.torso(junction + basis.f * 0.115f - basis.u * 0.03f, basis, nod = 10f)
    bench(junction, incline)
    body.standOn(junction.x + 0.52f, width = 0.5f, out = 10f, pole = v(0.35f, 1f, 0.35f))
    val open = lerp(8f, 78f, p)
    val chest = body.chest
    for (side in Side.entries) {
        val s = body.shoulder(side)
        val hand = s + (chest.f * cos(rad(open)) + chest.l * (side.sign * sin(rad(open))) + chest.u * 0.02f) * 0.54f
        body.arm(side, hand, chest.dir(-1f, -0.5f, side.sign * 0.2f))
        dumbbell(hand, axis = -chest.u)
    }
}

/**
 * Walking lunges with dumbbells: down into a lunge, then up over the front foot while the back foot
 * swings through into the next long step; the floor goes by as the body travels.
 */
internal fun walkingLunge() = Motion.cycle(3.2f, camera = Camera(24f)) { phase ->
    val front = 0.32f
    val back = -0.36f
    val down = 0.35f
    // One lunge per half loop: the near leg leads the first, the far leg the second.
    val half = if (phase < 0.5f) 0 else 1
    val s = phase * 2 - half
    val lead = if (half == 0) Side.NEAR else Side.FAR
    val pelvisY = ramp(s, 0f to 0.83f, down to 0.48f, 0.68f to 0.88f, 1f to 0.83f)
    body.torso(v(-0.03f, pelvisY), Basis.UPRIGHT.pitch(4f))
    // The leading foot is planted in front, then slides back as the body travels over it.
    for (side in Side.entries) {
        val leading = side == lead
        val z = side.sign * 0.11f
        if (leading) {
            val x = if (s < down) front else lerp(front, back, (s - down) / (1 - down))
            val lift = if (s < 0.75f) 0f else lerp(0f, 42f, Ease.IN_OUT.apply((s - 0.75f) / 0.25f))
            body.leg(side, liftedAnkle(x, 0f, lift).withZ(z), v(1f, 0.15f, 0f), Foot(dirXY(-lift), dirXY(90f - lift)))
        } else if (s < down) {
            val lift = lerp(42f, 58f, s / down)
            body.leg(side, liftedAnkle(back, 0f, lift).withZ(z), v(0.4f, -1f, 0f), Foot(dirXY(-lift), dirXY(90f - lift)))
        } else {
            val u = (s - down) / (1 - down)
            val e = Ease.IN_OUT.apply(u)
            val from = liftedAnkle(back, 0f, 58f)
            val to = v(front, BodySize.ANKLE)
            val ankle = lerp(from, to, e) + V3.Y * (0.1f * sin(PI.toFloat() * u))
            val angle = lerp(-58f, 0f, e)
            val foot = Foot(dirXY(angle), dirXY(angle + 90f))
            body.leg(side, footAbove(ankle.withZ(z), foot), lerp(v(0.4f, -1f, 0f), v(1f, 0.15f, 0f), e), foot)
        }
        body.hang(side, forward = 0f, out = 8f)
        dumbbell(body.handOf(side), axis = V3.X)
    }
    // The floor moves back by one step while the body travels over the front foot.
    val travel = front - back
    val moved = if (s < down) 0f else (s - down) / (1 - down) * travel
    walkMarks(moved + half * travel, 2 * travel)
}

/** Floor marks [offset] metres back (moving with the travel), [loop] metres a loop so they close without a jump. */
private fun FrameBuilder.walkMarks(offset: Float, loop: Float) {
    val spacing = loop / 3
    var x = 1.2f - offset % spacing
    while (x > -1.2f) {
        add(Rod(v(x - 0.08f, 0.006f, 0.26f), v(x, 0.006f, 0.26f), 0.009f, Ink.FRAME, fit = false, bias = BEHIND))
        x -= spacing
    }
}

// ---- Calisthenics ---------------------------------------------------------------------------------

/**
 * Archer push-up: hands planted very wide; the body lowers towards one hand, that elbow bending while
 * the other arm stays straight, back up, then to the other side.
 */
internal fun archerPushUp() = Motion.cycle(4.4f, camera = Camera(56f, 22f)) { phase ->
    val t = phase * 4.4f
    val shift = ramp(t, 0.4f to 0f, 1.3f to 0.15f, 1.5f to 0.15f, 2.2f to 0f, 2.6f to 0f, 3.5f to -0.15f, 3.7f to -0.15f, 4.4f to 0f)
    val top = 12.5f
    val bottom = 4f
    val angle = ramp(t, 0.4f to top, 1.3f to bottom, 1.5f to bottom, 2.2f to top, 2.6f to top, 3.5f to bottom, 3.7f to bottom, 4.4f to top)
    val ankle = v(-0.85f, 0.185f)
    val legs = BodySize.THIGH + BodySize.SHIN - 0.01f
    val line = legs + SPINE_LINE
    // The straight body turned about the feet (about the vertical) so the shoulders move [shift] towards the near side.
    val yaw = -asin((shift / line).coerceIn(-1f, 1f))
    val pitched = Basis.UPRIGHT.pitch(90f - angle)
    val basis = Basis(rotate(pitched.f, V3.Y, yaw), rotate(pitched.u, V3.Y, yaw), rotate(pitched.l, V3.Y, yaw))
    body.torso(ankle + rotate(dirXY(angle), V3.Y, yaw) * legs, basis, nod = 8f)
    for (side in Side.entries) body.leg(side, ankle.withZ(side.sign * 0.1f), v(0f, -1f, 0f), onToes(angle))
    val handX = ankle.x + line * cos(rad(top))
    for (side in Side.entries) body.arm(side, v(handX, BodySize.FIST_RADIUS, side.sign * 0.55f), v(-1f, 0.2f, side.sign * 0.6f))
}

/** Hip joints to the shoulder line, the trunk part of a plank's straight line. */
private const val SPINE_LINE = BodySize.LOWER_SPINE + BodySize.UPPER_SPINE - BodySize.SHOULDER_DROP

/**
 * Ring dip: supported on two gymnastic rings hanging from straps, the body lowers until the upper
 * arms are about level and presses back up, the rings kept close to the body.
 */
internal fun ringDip() = Motion.rep(Tempo.lowerFirst(), Camera(24f)) { p ->
    val ringY = 1.25f
    val handX = 0.04f
    val handZ = 0.26f
    val lateral = handZ - BodySize.SHOULDER_HALF
    val lean = lerp(6f, 24f, p)
    val shoulder = v(handX + lerp(-0.02f, -0.1f, p), ringY + lerp(armReach(lateral, 0.02f), 0.09f, p))
    val basis = Basis.UPRIGHT.pitch(lean)
    body.torso(shoulder - basis.u * SPINE_LINE, basis, nod = -lean * 0.4f)
    for (side in Side.entries) body.legAngles(side, limbDir(body.hips, side, 18f, 3f), 78f, fold = -body.hips.f)
    for (side in Side.entries) {
        val hand = v(handX, ringY, side.sign * (handZ + 0.02f * p))
        body.arm(side, hand, v(-1f, 0f, side.sign * 0.25f))
        add(Disc(hand, V3.X, 0.09f, 0.02f, Ink.METAL, hub = 0.075f, bias = -0.03f))
        add(Cord(listOf(hand + V3.Y * 0.09f, v(handX, 2.7f, hand.z * 0.9f)), 0.02f, Ink.BAND, fit = false, bias = -0.2f))
    }
}

/** Neutral-grip pull-up: on parallel handles, palms facing each other; from a dead hang until the chin clears the handles, elbows driving down by the sides. */
internal fun neutralGripPullUp() = Motion.rep(Tempo.liftFirst(), Camera(36f)) { p ->
    val barY = 2.3f
    val barX = 0.02f
    val half = 0.24f
    val lateral = abs(half - BodySize.SHOULDER_HALF)
    val shoulder = v(barX + lerp(-0.03f, -0.1f, p), barY - lerp(armReach(lateral, 0.03f, 0.985f), 0.2f, p))
    hangFrom(shoulder, lean = lerp(2f, -10f, p), nod = lerp(0f, -12f, p))
    for (side in Side.entries) {
        body.arm(side, v(barX, barY, side.sign * half), v(0.6f, -1f, side.sign * 0.5f))
        add(Rod(v(barX - 0.13f, barY, side.sign * half), v(barX + 0.13f, barY, side.sign * half), 0.02f, Ink.METAL, bias = -0.05f))
        add(Rod(v(barX + 0.13f, barY, side.sign * half), v(barX + 0.13f, barY + 0.1f, side.sign * half), 0.02f, Ink.FRAME, bias = -0.06f))
    }
    for (side in Side.entries) body.legAngles(side, limbDir(body.hips, side, 10f, 2f), lerp(25f, 45f, p), fold = -body.hips.f)
    add(Rod(v(barX + 0.13f, barY + 0.1f, -0.7f), v(barX + 0.13f, barY + 0.1f, 0.7f), 0.025f, Ink.FRAME, bias = -0.07f))
    for (s in listOf(-1f, 1f)) post(barX + 0.13f, barY + 0.15f, z = s * 0.72f, fit = false)
}

/**
 * Suspension trainer (TRX) row: holding the handles of straps anchored high in front, heels on the
 * floor and the body straight and leaning back; the chest rows up to the hands and lowers slowly.
 */
internal fun suspensionRow() = Motion.rep(Tempo.liftFirst(), Camera(-24f)) { p ->
    val anchor = v(0.62f, 2.4f)
    val ankle = v(0.34f, 0.1f)
    val angle = lerp(40f, 52f, p)
    val dir = dirXY(180f - angle)
    body.torso(ankle + dir * (BodySize.THIGH + BodySize.SHIN - 0.01f), Basis.UPRIGHT.pitch(angle - 90f), nod = 10f)
    for (side in Side.entries) body.leg(side, ankle.withZ(side.sign * 0.1f), v(0f, 1f, 0f), Foot(dirXY(90f - angle), dirXY(180f - angle)))
    val shoulders = body.shoulders()
    val toAnchor = (anchor - shoulders).unit()
    val reach = lerp((BodySize.UPPER_ARM + BodySize.FOREARM) * 0.96f, 0.26f, p)
    val hands = shoulders + toAnchor * reach
    for (side in Side.entries) {
        val hand = hands.withZ(side.sign * 0.17f)
        body.arm(side, hand, v(1f, -0.6f, side.sign * 0.4f))
        add(Rod(hand - V3.Z * (side.sign * 0.06f), hand + V3.Z * (side.sign * 0.06f), 0.02f, Ink.METAL))
        add(Cord(listOf(hand, anchor.withZ(side.sign * 0.03f)), 0.02f, Ink.BAND, fit = false, bias = -0.1f))
    }
}

/**
 * Sissy squat: on the balls of the feet, the knees travel far forward while the hips stay straight, so
 * the thighs and trunk lean back in one line; the far hand holds a post beside.
 */
internal fun sissySquat() = Motion.rep(Track.rep(go = 1.4f, hold = 0.2f, back = 1.2f, rest = 0.4f), Camera(26f)) { p ->
    val lift = lerp(15f, 48f, p)
    val shinTilt = lerp(4f, 58f, p)
    val thighBack = lerp(2f, 44f, p)
    val ankle = liftedAnkle(0f, 0f, lift)
    val knee = ankle + dirXY(90f - shinTilt) * BodySize.SHIN
    val hip = knee + dirXY(90f + thighBack) * BodySize.THIGH
    body.torso(hip.withZ(0f), Basis.UPRIGHT.pitch(-thighBack), nod = lerp(0f, 18f, p))
    for (side in Side.entries) body.leg(side, ankle.withZ(side.sign * 0.12f), v(1f, 0f, side.sign * 0.1f), Foot(dirXY(-lift), dirXY(90f - lift)))
    body.arm(Side.FAR, v(0.08f, 0.98f, -0.42f), v(-0.3f, -1f, -0.6f))
    body.armAngles(Side.NEAR, limbDir(Basis.UPRIGHT, Side.NEAR, lerp(30f, 80f, p), 6f), 10f, fold = V3.Y)
    post(0.12f, 2.0f, z = -0.46f, radius = 0.035f)
}
