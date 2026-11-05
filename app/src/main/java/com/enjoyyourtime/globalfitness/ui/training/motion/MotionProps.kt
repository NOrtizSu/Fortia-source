package com.enjoyyourtime.globalfitness.ui.training.motion

/**
 * A piece of equipment in world space, drawn by [SceneBuilder]. [fit] false marks context that
 * the fitted view may crop (tall machine frames, rails); [bias] nudges its depth order (positive:
 * drawn later, in front).
 */
internal sealed class Prop(val ink: Ink, val fit: Boolean, val bias: Float)

/** A cylinder seen as a capsule: bars, posts, handles, rollers. Long rods are split so depth order holds along them. */
internal class Rod(val a: V3, val b: V3, val radius: Float, ink: Ink = Ink.METAL, fit: Boolean = true, bias: Float = 0f, val halo: Boolean = true) :
    Prop(ink, fit, bias)

/** A coin: weight plates, dumbbell heads, wheels. [axis] is its normal; [hub] the radius of a darker centre. */
internal class Disc(val center: V3, val axis: V3, val radius: Float, val thickness: Float, ink: Ink = Ink.PLATE, val hub: Float = 0f, fit: Boolean = true, bias: Float = 0f) :
    Prop(ink, fit, bias)

/** A box with half sizes [half] along [basis] (forward, up, lateral): benches, pads, seats, stacks. */
internal class Block(val center: V3, val basis: Basis, val half: V3, ink: Ink = Ink.PAD, fit: Boolean = true, bias: Float = 0f) :
    Prop(ink, fit, bias)

/** A sphere: kettlebell bodies, medicine balls. */
internal class Ball(val center: V3, val radius: Float, ink: Ink = Ink.PLATE, fit: Boolean = true, bias: Float = 0f) : Prop(ink, fit, bias)

/** A thin line through [points]: cables, ropes, bands, belts. */
internal class Cord(val points: List<V3>, val width: Float, ink: Ink = Ink.CABLE, fit: Boolean = true, bias: Float = 0f) : Prop(ink, fit, bias)

/**
 * A spherical cap standing on the floor (or a level surface) with its flat base centred at [base]:
 * the dome of a balance trainer (bosu), [radius] wide at the base and [height] tall.
 */
internal class Dome(val base: V3, val radius: Float, val height: Float, ink: Ink = Ink.PAD, fit: Boolean = true, bias: Float = 0f) :
    Prop(ink, fit, bias) {
    /** Radius of the sphere the cap is cut from, and how far its centre sits below the base. */
    val sphere: Float get() = (radius * radius + height * height) / (2 * height)
    val drop: Float get() = sphere - height

    /** Height of the dome's surface above the base at [distance] metres from its axis (the base at the rim). */
    fun surface(distance: Float): Float = kotlin.math.sqrt((sphere * sphere - distance * distance).coerceAtLeast(0f)) - drop
}

// Equipment built from the primitives above. Sizes are real-world-ish, a little exaggerated where
// it helps reading (plates, handles) at the size of a bottom sheet.

internal object Kit {
    const val BAR_RADIUS = 0.017f
    const val PLATE_RADIUS = 0.2f
    const val BENCH_TOP = 0.45f
    const val PAD_HALF_THICK = 0.04f
}

/**
 * A barbell centred at [center] along [axis] (across the body by default) with [plates] plates a
 * side of [plateRadius]; [length] is the whole bar. [fit] false for a bar resting in a rack.
 */
internal fun FrameBuilder.barbell(center: V3, axis: V3 = V3.Z, length: Float = 1.9f, plateRadius: Float = Kit.PLATE_RADIUS, plates: Int = 2, bias: Float = 0f) {
    val a = axis.unit()
    val half = length / 2
    val inner = half * 0.66f
    // The shaft between the collars; the sleeves through the plates have no halo, so no slot shows on them.
    add(Rod(center - a * inner, center + a * inner, Kit.BAR_RADIUS, bias = bias - 0.05f))
    for (s in listOf(-1f, 1f)) {
        add(Rod(center + a * (s * inner), center + a * (s * half), Kit.BAR_RADIUS * 1.35f, bias = bias, halo = false))
        // Collar, then the plates from the inside out, smaller ones outside.
        add(Disc(center + a * (s * (inner - 0.015f)), a, 0.04f, 0.03f, Ink.METAL, bias = bias))
        for (i in 0 until plates) {
            val r = if (i == 0) plateRadius else plateRadius * 0.72f
            add(Disc(center + a * (s * (inner + 0.03f + i * 0.055f)), a, r, 0.045f, Ink.PLATE, hub = 0.035f, bias = bias))
        }
    }
}

/** A dumbbell centred at [center] (the fist) with its handle along [axis]. */
internal fun FrameBuilder.dumbbell(center: V3, axis: V3 = V3.Z, headRadius: Float = 0.068f, bias: Float = 0f) {
    val a = axis.unit()
    add(Rod(center - a * 0.11f, center + a * 0.11f, 0.016f, bias = bias - 0.02f))
    for (s in listOf(-1f, 1f)) {
        add(Disc(center + a * (s * 0.1f), a, headRadius, 0.05f, Ink.PLATE, bias = bias))
    }
}

/** A kettlebell hanging from a handle held at [grip]; [down] points from the handle to the bell. */
internal fun FrameBuilder.kettlebell(grip: V3, down: V3 = -V3.Y, across: V3 = V3.Z, radius: Float = 0.1f, bias: Float = 0f) {
    val d = down.unit()
    val a = orthogonal(across, d)
    val body = grip + d * (radius + 0.07f)
    add(Rod(grip - a * 0.06f, grip + a * 0.06f, 0.014f, bias = bias - 0.02f))
    add(Cord(listOf(grip - a * 0.06f, grip - a * 0.07f + d * 0.06f, body - a * 0.06f), 0.022f, Ink.PLATE, bias = bias))
    add(Cord(listOf(grip + a * 0.06f, grip + a * 0.07f + d * 0.06f, body + a * 0.06f), 0.022f, Ink.PLATE, bias = bias))
    add(Ball(body, radius, Ink.PLATE, bias = bias))
}

/** A box from [min] to [max] corners (axis aligned). */
internal fun FrameBuilder.box(min: V3, max: V3, ink: Ink = Ink.PAD, fit: Boolean = true, bias: Float = 0f) {
    add(Block((min + max) * 0.5f, Basis.UPRIGHT, (max - min) * 0.5f, ink, fit, bias))
}

/**
 * A pad (bench top, back rest, seat) as a slab: its long side from [from] to [to], [width] across
 * the body, [thick] thick, its top face towards [up] (default: whatever is perpendicular, upwards).
 */
internal fun FrameBuilder.pad(from: V3, to: V3, width: Float = 0.3f, thick: Float = 0.08f, up: V3 = V3.Y, ink: Ink = Ink.PAD, bias: Float = 0f) {
    val along = (to - from).unit()
    val top = orthogonal(up, along)
    val side = along cross top
    add(Block((from + to) * 0.5f, Basis(along, top, side), V3((to - from).length / 2, thick / 2, width / 2), ink, bias = bias))
}

/** A cylindrical roller pad across the body, centred at [center]. */
internal fun FrameBuilder.roller(center: V3, length: Float = 0.3f, radius: Float = 0.055f, bias: Float = 0f) {
    add(Rod(center - V3.Z * (length / 2), center + V3.Z * (length / 2), radius, Ink.PAD, bias = bias))
}

/** A flat bench: pad from [x0] to [x1] with its top at [top], two legs. */
internal fun FrameBuilder.flatBench(x0: Float, x1: Float, top: Float = Kit.BENCH_TOP) {
    pad(v(x0, top - Kit.PAD_HALF_THICK), v(x1, top - Kit.PAD_HALF_THICK), bias = -0.1f)
    val inset = 0.12f
    for (x in listOf(x0 + inset, x1 - inset)) {
        add(Rod(v(x, 0.03f), v(x, top - 0.08f), 0.028f, Ink.FRAME, bias = -0.12f))
        add(Rod(v(x, 0.03f, -0.14f), v(x, 0.03f, 0.14f), 0.025f, Ink.FRAME, bias = -0.12f))
    }
    add(Rod(v(x0 + inset, top - 0.1f), v(x1 - inset, top - 0.1f), 0.025f, Ink.FRAME, bias = -0.12f))
}

/**
 * A vertical post from the floor to [top] at ([x], [z]): uprights, rails, frames. Posts are drawn
 * behind everything else, even the one nearer the viewer than the figure, so none cuts across it.
 */
internal fun FrameBuilder.post(x: Float, top: Float, z: Float = 0f, radius: Float = 0.03f, fit: Boolean = false) {
    add(Rod(v(x, 0f, z), v(x, top, z), radius, Ink.FRAME, fit = fit, bias = BEHIND))
}

/** Depth bias that keeps context (uprights, rails) behind the figure and its equipment. */
internal const val BEHIND = -1.5f

/**
 * A cable machine column at ([x], [z]): the weight stack (its top plates rise by [lift] metres),
 * a pulley at [pulley] and the cable from the stack over the pulley to [handle].
 */
internal fun FrameBuilder.cableStack(x: Float, pulley: V3, handle: V3, lift: Float, z: Float = 0f, height: Float = 2.2f, fit: Boolean = false) {
    val w = 0.13f
    add(Block(v(x, height / 2, z), Basis.UPRIGHT, v(0.11f, height / 2, 0.2f), Ink.FRAME, fit = fit, bias = -0.6f))
    // The resting stack, and the selected plates lifted off it.
    add(Block(v(x, 0.32f, z), Basis.UPRIGHT, v(w * 0.55f, 0.25f, 0.15f), Ink.STACK, fit = fit, bias = -0.55f))
    add(Block(v(x, 0.64f + lift, z), Basis.UPRIGHT, v(w * 0.55f, 0.06f, 0.15f), Ink.STACK, fit = fit, bias = -0.55f))
    add(Disc(pulley, V3.Z, 0.045f, 0.03f, Ink.FRAME, fit = fit, bias = -0.2f))
    add(Cord(listOf(v(x, 0.7f + lift, z), v(x, height - 0.05f, z), pulley, handle), 0.012f, Ink.CABLE, fit = false, bias = -0.15f))
}

/** A cable from a pulley at [pulley] to [handle], with the pulley wheel drawn. */
internal fun FrameBuilder.cable(pulley: V3, handle: V3, fit: Boolean = true) {
    add(Disc(pulley, V3.Z, 0.04f, 0.03f, Ink.FRAME, fit = fit, bias = -0.2f))
    add(Cord(listOf(pulley, handle), 0.012f, Ink.CABLE, fit = fit, bias = -0.15f))
}

/** A straight handle bar across the body centred at [center], [length] long. */
internal fun FrameBuilder.handleBar(center: V3, length: Float, axis: V3 = V3.Z, bias: Float = 0f) {
    val a = axis.unit() * (length / 2)
    add(Rod(center - a, center + a, 0.017f, Ink.METAL, bias = bias - 0.05f))
}

/** A resistance band through [points]. */
internal fun FrameBuilder.band(points: List<V3>, bias: Float = 0f) {
    add(Cord(points, 0.03f, Ink.BAND, bias = bias))
}
