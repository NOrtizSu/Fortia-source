package com.enjoyyourtime.globalfitness.ui.training.motion

import com.enjoyyourtime.globalfitness.training.model.Muscle
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Turns a [Frame] into flat shapes: projects the mannequin and its equipment with [camera], draws
 * each body part as rounded capsules (the torso as one smooth outline), tints the [muscles] on the
 * segments they belong to, and orders everything back to front.
 *
 * Every part is drawn in a group: first the halos (the shapes widened in the background colour,
 * so a limb in front of the torso stays readable), then the fills, then the muscle tints. A limb's
 * segments share one group, so no seam shows at its joints.
 *
 * A scene is built for every frame on the main thread, so the outlines are written straight into
 * float arrays of their final size and the curved surfaces are projected point by point, without
 * intermediate vectors or boxed lists (the arithmetic is the same, so the shapes are too).
 */
internal class SceneBuilder(private val camera: Camera, private val muscles: Set<Muscle>) {
    private val groups = ArrayList<Group>()

    /** Projected points of the sections of one [surfaceBand], and each visible section's first and last. */
    private val bandPoints = FloatArray(BAND_SECTIONS * (BAND_STEPS + 1) * 2)
    private val bandFirst = IntArray(BAND_SECTIONS)
    private val bandLast = IntArray(BAND_SECTIONS)

    fun build(frame: Frame): Scene {
        body(frame.pose)
        for (prop in frame.props) prop(prop)
        groups.sortWith(BY_DEPTH)
        val ops = ArrayList<DrawOp>(groups.sumOf { it.size })
        for (g in groups) g.emitTo(ops)
        return Scene(ops)
    }

    // ---- Body ----

    private fun body(pose: Pose) {
        val torsoDepth = camera.depth(pose.waist)
        torso(pose, torsoDepth)
        // The head sorts by its own depth (a bar in front of the face), never behind its own torso.
        Group(max(camera.depth(pose.head), torsoDepth) + 0.02f).also { groups += it }.apply {
            circle(pose.head, BodySize.HEAD_RADIUS, Ink.BODY)
        }
        for (side in Side.entries) {
            arm(pose, side)
            leg(pose, side)
        }
    }

    private fun torso(pose: Pose, depth: Float) {
        val g = Group(depth).also { groups += it }
        val outline = torsoOutline(pose)
        val neckEnd = lerp(pose.neck, pose.head, 0.75f)
        val neckStart = pose.neck - pose.upper.u * 0.04f
        g.halo(outline)
        g.haloCapsule(neckStart, BodySize.NECK_RADIUS, neckEnd, BodySize.NECK_RADIUS)
        g.fill(capsule(neckStart, BodySize.NECK_RADIUS, neckEnd, BodySize.NECK_RADIUS), Ink.BODY)
        g.fill(outline, Ink.BODY)
        for (band in TORSO_BANDS) {
            if (band.muscle in muscles) torsoBand(pose, band)?.let { g.mark(it, Ink.MUSCLE) }
        }
        if (Muscle.NECK in muscles) g.mark(capsule(neckStart, BodySize.NECK_RADIUS * 0.8f, neckEnd, BodySize.NECK_RADIUS * 0.8f), Ink.MUSCLE)
    }

    private fun arm(pose: Pose, side: Side) {
        val a = pose.arm(side)
        val far = side == Side.FAR
        val k = if (far) BodySize.FAR_SCALE else 1f
        val ink = if (far) Ink.BODY_FAR else Ink.BODY
        val muscle = if (far) Ink.MUSCLE_FAR else Ink.MUSCLE
        val depth = (camera.depth(a.shoulder) + camera.depth(a.elbow) + camera.depth(a.hand)) / 3 + ARM_BIAS
        val g = Group(depth).also { groups += it }
        val rs = BodySize.SHOULDER_RADIUS * k
        val re = BodySize.ELBOW_RADIUS * k
        val rw = BodySize.WRIST_RADIUS * k
        val rf = BodySize.FIST_RADIUS * k
        val upper = capsule(a.shoulder, rs, a.elbow, re)
        val fore = capsule(a.elbow, re, a.wrist, rw)
        val hand = capsule(a.wrist, rw, a.hand, rf * 0.8f)
        g.halo(upper)
        g.halo(fore)
        g.halo(hand)
        g.haloCircle(a.hand, rf)
        g.fill(upper, ink)
        g.fill(fore, ink)
        g.fill(hand, ink)
        g.circle(a.hand, rf, ink, halo = false)
        if (Muscle.SHOULDERS in muscles) {
            g.mark(capsule(a.shoulder, rs * 1.12f, lerp(a.shoulder, a.elbow, 0.38f), rs * 0.95f), muscle)
        }
        if (Muscle.BICEPS in muscles) limbBand(a.shoulder, rs, a.elbow, re, -a.apex, 0.22f, 0.9f, 88f)?.let { g.mark(it, muscle) }
        if (Muscle.TRICEPS in muscles) limbBand(a.shoulder, rs, a.elbow, re, a.apex, 0.2f, 0.92f, 88f)?.let { g.mark(it, muscle) }
        if (Muscle.FOREARMS in muscles) g.mark(capsule(a.elbow, re * 0.92f, lerp(a.elbow, a.wrist, 0.9f), rw * 0.95f), muscle)
    }

    private fun leg(pose: Pose, side: Side) {
        val l = pose.leg(side)
        val far = side == Side.FAR
        val k = if (far) BodySize.FAR_SCALE else 1f
        val ink = if (far) Ink.BODY_FAR else Ink.BODY
        val muscle = if (far) Ink.MUSCLE_FAR else Ink.MUSCLE
        val depth = (camera.depth(l.hip) + camera.depth(l.knee) + camera.depth(l.ankle)) / 3 + LEG_BIAS
        val g = Group(depth).also { groups += it }
        val rt = BodySize.THIGH_RADIUS * k
        val rk = BodySize.KNEE_RADIUS * k
        val ra = BodySize.ANKLE_RADIUS * k
        val rfoot = BodySize.FOOT_RADIUS * k
        val thigh = capsule(l.hip, rt, l.knee, rk)
        val shin = capsule(l.knee, rk, l.ankle, ra)
        val heel = capsule(l.ankle, ra, l.heel, rfoot)
        val foot = capsule(l.heel, rfoot, l.toe, rfoot * 0.85f)
        for (shape in listOf(thigh, shin, heel, foot)) g.halo(shape)
        for (shape in listOf(thigh, shin, heel, foot)) g.fill(shape, ink)
        if (Muscle.QUADS in muscles) limbBand(l.hip, rt, l.knee, rk, l.apex, 0.12f, 0.86f, 62f)?.let { g.mark(it, muscle) }
        if (Muscle.HAMSTRINGS in muscles) limbBand(l.hip, rt, l.knee, rk, -l.apex, 0.14f, 0.9f, 70f)?.let { g.mark(it, muscle) }
        if (Muscle.ADDUCTORS in muscles) {
            val medial = -pose.lower.l * side.sign
            limbBand(l.hip, rt, l.knee, rk, medial, 0.06f, 0.72f, 62f)?.let { g.mark(it, muscle) }
        }
        if (Muscle.CALVES in muscles) limbBand(l.knee, rk, l.ankle, ra, -l.apex, 0.06f, 0.62f, 80f)?.let { g.mark(it, muscle) }
    }

    /** The torso's silhouette: cross-sections along the spine, their extreme points on each side, smoothed. */
    private fun torsoOutline(pose: Pose): FloatArray {
        val sections = TORSO.map { torsoSection(pose, it.s) }
        val axis = spineAxis(pose, sections)
        // Up the left side, the top cap, down the right side, the bottom cap.
        val n = sections.size
        val pts = FloatArray((2 * n + 2) * 2)
        for (i in 0 until n) {
            val sec = sections[i]
            val normal = perpendicularOf(sec.u, axis)
            silhouette(pts, i, sec, normal.first, normal.second)
            silhouette(pts, 2 * n - i, sec, -normal.first, -normal.second)
        }
        val top = sections.last()
        putPoint(pts, n, top.c + top.u * 0.035f)
        val bottom = sections.first()
        putPoint(pts, 2 * n + 1, bottom.c - bottom.u * 0.03f)
        return chaikin(chaikin(pts))
    }

    /** Projected spine direction used where a section's own axis points at the viewer. */
    private fun spineAxis(pose: Pose, sections: List<Section>): Pair<Float, Float> {
        val a = sections.first().c
        val b = sections.last().c
        val dx = camera.x(b) - camera.x(a)
        val dy = camera.y(b) - camera.y(a)
        val len = sqrt(dx * dx + dy * dy)
        if (len > 0.05f) return Pair(dx / len, dy / len)
        val lx = camera.x(pose.waist + pose.upper.l) - camera.x(pose.waist)
        val ly = camera.y(pose.waist + pose.upper.l) - camera.y(pose.waist)
        val ll = sqrt(lx * lx + ly * ly)
        return if (ll > 0.05f) Pair(-ly / ll, lx / ll) else Pair(0f, 1f)
    }

    /** Unit normal (pointing left of the spine) on the picture plane for a section whose spine is [u]. */
    private fun perpendicularOf(u: V3, fallback: Pair<Float, Float>): Pair<Float, Float> {
        var ax = u dot camera.right
        var ay = u dot camera.up
        val len = sqrt(ax * ax + ay * ay)
        if (len < 0.3f) {
            ax = fallback.first
            ay = fallback.second
        } else {
            ax /= len
            ay /= len
        }
        return Pair(-ay, ax)
    }

    /**
     * The point of an elliptical cross-section that sticks out furthest along the picture direction
     * ([nx], [ny]), written as point [index] of [out].
     */
    private fun silhouette(out: FloatArray, index: Int, sec: Section, nx: Float, ny: Float) {
        val fn = (sec.f dot camera.right) * nx + (sec.f dot camera.up) * ny
        val ln = (sec.l dot camera.right) * nx + (sec.l dot camera.up) * ny
        val a = if (fn >= 0f) sec.front else sec.back
        val amp = sqrt((a * fn) * (a * fn) + (sec.lat * ln) * (sec.lat * ln))
        if (amp < 1e-6f) {
            putPoint(out, index, sec.c)
        } else {
            putPoint(out, index, sec.c, sec.f, a * (a * fn) / amp, sec.l, sec.lat * (sec.lat * ln) / amp)
        }
    }

    private fun torsoSection(pose: Pose, s: Float): Section {
        var i = 1
        while (i < TORSO.size - 1 && TORSO[i].s < s) i++
        val r0 = TORSO[i - 1]
        val r1 = TORSO[i]
        val t = ((s - r0.s) / (r1.s - r0.s)).coerceIn(0f, 1f)
        val lowerLen = BodySize.LOWER_SPINE
        val basis = when {
            s <= lowerLen - WAIST_BLEND -> pose.lower
            s >= lowerLen + WAIST_BLEND -> pose.upper
            else -> blend(pose.lower, pose.upper, (s - (lowerLen - WAIST_BLEND)) / (2 * WAIST_BLEND))
        }
        val c = if (s <= lowerLen) pose.pelvis + pose.lower.u * s else pose.waist + pose.upper.u * (s - lowerLen)
        return Section(c, basis.f, basis.u, basis.l, lerp(r0.front, r1.front, t), lerp(r0.back, r1.back, t), lerp(r0.lat, r1.lat, t))
    }

    private fun torsoBand(pose: Pose, band: TorsoBand): FloatArray? {
        val sections = List(BAND_SECTIONS) { torsoSection(pose, lerp(band.from, band.to, it / (BAND_SECTIONS - 1f))) }
        return surfaceBand(sections, band.center, band.half)
    }

    /**
     * A muscle tint on a limb segment from [a] (radius [ra]) to [b] ([rb]): the part of its surface
     * between [from] and [to] along it, within [half] degrees around [facing].
     */
    private fun limbBand(a: V3, ra: Float, b: V3, rb: Float, facing: V3, from: Float, to: Float, half: Float): FloatArray? {
        val axis = (b - a).unit()
        val f = orthogonal(facing, axis)
        val l = axis cross f
        val sections = List(BAND_SECTIONS) {
            val s = lerp(from, to, it / (BAND_SECTIONS - 1f))
            val r = lerp(ra, rb, s) * 0.97f
            Section(lerp(a, b, s), f, axis, l, r, r, r)
        }
        return surfaceBand(sections, 0f, half)
    }

    /**
     * The visible part of a band of surface over [sections] (at most [BAND_SECTIONS]), [half]
     * degrees either side of the angle [center] (0: the sections' forward axis, 90: their lateral
     * axis); null when it faces away.
     */
    private fun surfaceBand(sections: List<Section>, center: Float, half: Float): FloatArray? {
        val arc = BAND_STEPS + 1
        val tv = camera.toViewer
        var visible = 0
        for (sec in sections) {
            var first = -1
            var last = -1
            val f = sec.f
            val l = sec.l
            for (k in 0..BAND_STEPS) {
                val t = rad(center - half + 2 * half * k / BAND_STEPS)
                val c = cos(t)
                val s = sin(t)
                val a = if (c >= 0f) sec.front else sec.back
                // The surface normal there, faced towards the viewer or not.
                val ca = c / a
                val sl = s / sec.lat
                val nx = f.x * ca + l.x * sl
                val ny = f.y * ca + l.y * sl
                val nz = f.z * ca + l.z * sl
                if (nx * tv.x + ny * tv.y + nz * tv.z > 0f) {
                    if (first < 0) first = k
                    last = k
                }
                putPoint(bandPoints, visible * arc + k, sec.c, f, a * c, l, sec.lat * s)
            }
            // Sections turned away are left out (the next one reuses their slot); the band shows
            // where at least two are visible.
            if (first < 0 || last == first) continue
            bandFirst[visible] = first
            bandLast[visible] = last
            visible++
        }
        if (visible < 2) return null
        // Along the first edge, across the last section, back along the other edge, across the first.
        val end = visible - 1
        val out = FloatArray((2 * visible + (bandLast[end] - bandFirst[end] + 1) + (bandLast[0] - bandFirst[0] + 1)) * 2)
        var o = 0
        for (i in 0..end) o = copyBandPoint(out, o, i, bandFirst[i])
        for (k in bandFirst[end]..bandLast[end]) o = copyBandPoint(out, o, end, k)
        for (i in end downTo 0) o = copyBandPoint(out, o, i, bandLast[i])
        for (k in bandLast[0] downTo bandFirst[0]) o = copyBandPoint(out, o, 0, k)
        return out
    }

    /** Copies point [k] of the band's section [section] into [out] at [o]; returns the next position. */
    private fun copyBandPoint(out: FloatArray, o: Int, section: Int, k: Int): Int {
        val from = 2 * (section * (BAND_STEPS + 1) + k)
        out[o] = bandPoints[from]
        out[o + 1] = bandPoints[from + 1]
        return o + 2
    }

    // ---- Equipment ----

    private fun prop(prop: Prop) {
        when (prop) {
            is Rod -> rod(prop)
            is Disc -> disc(prop)
            is Block -> block(prop)
            is Ball -> Group(camera.depth(prop.center) + prop.bias, prop.fit).also { groups += it }.circle(prop.center, prop.radius, prop.ink)
            is Dome -> dome(prop)
            is Cord -> {
                var sum = 0.0
                for (p in prop.points) sum += camera.depth(p)
                val depth = (sum / prop.points.size).toFloat() + prop.bias
                val pts = FloatArray(prop.points.size * 2)
                prop.points.forEachIndexed { i, p ->
                    pts[2 * i] = camera.x(p)
                    pts[2 * i + 1] = camera.y(p)
                }
                Group(depth, prop.fit).also { groups += it }.line(pts, prop.width, prop.ink)
            }
        }
    }

    /** A rod, split in pieces of at most [ROD_PIECE] so long bars sort correctly against the body. */
    private fun rod(rod: Rod) {
        val length = rod.a.distanceTo(rod.b)
        val pieces = max(1, ceil(length / ROD_PIECE).toInt())
        for (i in 0 until pieces) {
            val a = lerp(rod.a, rod.b, i.toFloat() / pieces)
            val b = lerp(rod.a, rod.b, (i + 1).toFloat() / pieces)
            val g = Group((camera.depth(a) + camera.depth(b)) / 2 + rod.bias, rod.fit).also { groups += it }
            if (rod.halo) {
                // Pieces after the first meet the previous one flat, so the halo never notches the bar.
                if (pieces == 1) g.haloCapsule(a, rod.radius, b, rod.radius) else g.halo(slab(a, b, rod.radius + HALO, i == 0, i == pieces - 1))
            }
            g.fill(capsule(a, rod.radius, b, rod.radius), rod.ink)
        }
    }

    private fun disc(disc: Disc) {
        val n = disc.axis.unit()
        val facing = n dot camera.toViewer
        val front = if (facing >= 0f) n else -n
        val back = disc.center - front * (disc.thickness / 2)
        val face = disc.center + front * (disc.thickness / 2)
        val g = Group(camera.depth(disc.center) + disc.bias, disc.fit).also { groups += it }
        val backShape = ellipse(back, n, disc.radius)
        val faceShape = ellipse(face, n, disc.radius)
        val rim = rim(back, face, n, disc.radius)
        g.halo(backShape)
        g.halo(faceShape)
        g.fill(backShape, disc.ink.dark)
        if (rim != null) g.fill(rim, disc.ink.dark)
        g.fill(faceShape, disc.ink)
        if (disc.hub > 0f) g.fill(ellipse(face + front * 0.002f, n, disc.hub), disc.ink.dark)
    }

    /**
     * A spherical cap on a level base: under the orthographic camera the sphere's outline is a circle,
     * so the cap is the arc of that circle above its base, closed by the front half of the base's rim.
     */
    private fun dome(d: Dome) {
        val g = Group(camera.depth(d.base + V3.Y * (d.height * 0.4f)) + d.bias, d.fit).also { groups += it }
        val bx = camera.x(d.base)
        val by = camera.y(d.base)
        val cp = camera.up.y
        val sp = abs(V3.Y dot camera.toViewer)
        val rs = d.sphere
        val cy = by - d.drop * cp
        val a0 = kotlin.math.asin(((by - cy) / rs).coerceIn(-1f, 1f))
        val n = ELLIPSE_POINTS
        val out = FloatArray((2 * n + 2) * 2)
        var k = 0
        for (i in 0..n) {
            val a = a0 + (PI.toFloat() - 2 * a0) * i / n
            out[k++] = bx + rs * cos(a)
            out[k++] = cy + rs * sin(a)
        }
        for (i in 0..n) {
            val b = PI.toFloat() * (1f - i.toFloat() / n)
            out[k++] = bx + d.radius * cos(b)
            out[k++] = by - d.radius * sp * sin(b)
        }
        g.halo(out)
        g.fill(out, d.ink)
    }

    private fun block(block: Block) {
        val g = Group(camera.depth(block.center) + block.bias, block.fit).also { groups += it }
        val b = block.basis
        val h = block.half
        var bestFacing = -1f
        var best = -1
        val faces = ArrayList<Pair<FloatArray, Float>>(3)
        for ((normal, corners) in blockFaces(block.center, b, h)) {
            val facing = normal dot camera.toViewer
            if (facing <= 1e-3f) continue
            val pts = FloatArray(8)
            corners.forEachIndexed { i, p ->
                pts[2 * i] = camera.x(p)
                pts[2 * i + 1] = camera.y(p)
            }
            val top = normal dot V3.Y
            faces += Pair(pts, if (top > 0.7f) 2f else facing)
            if (facing > bestFacing && top <= 0.7f) {
                bestFacing = facing
                best = faces.size - 1
            }
        }
        for ((pts, _) in faces) g.halo(pts)
        faces.forEachIndexed { i, (pts, key) ->
            g.fill(pts, if (i == best || key == 2f) block.ink else block.ink.dark)
        }
    }

    private fun blockFaces(c: V3, b: Basis, h: V3): List<Pair<V3, List<V3>>> {
        fun corner(sf: Float, su: Float, sl: Float) = c + b.f * (sf * h.x) + b.u * (su * h.y) + b.l * (sl * h.z)
        return listOf(
            b.f to listOf(corner(1f, -1f, -1f), corner(1f, 1f, -1f), corner(1f, 1f, 1f), corner(1f, -1f, 1f)),
            -b.f to listOf(corner(-1f, -1f, -1f), corner(-1f, -1f, 1f), corner(-1f, 1f, 1f), corner(-1f, 1f, -1f)),
            b.u to listOf(corner(-1f, 1f, -1f), corner(-1f, 1f, 1f), corner(1f, 1f, 1f), corner(1f, 1f, -1f)),
            -b.u to listOf(corner(-1f, -1f, -1f), corner(1f, -1f, -1f), corner(1f, -1f, 1f), corner(-1f, -1f, 1f)),
            b.l to listOf(corner(-1f, -1f, 1f), corner(1f, -1f, 1f), corner(1f, 1f, 1f), corner(-1f, 1f, 1f)),
            -b.l to listOf(corner(-1f, -1f, -1f), corner(-1f, 1f, -1f), corner(1f, 1f, -1f), corner(1f, -1f, -1f)),
        )
    }

    // ---- Geometry on the picture plane ----

    /** Closed outline of two circles joined by their outer tangents (a tapered capsule). */
    private fun capsule(a: V3, ra: Float, b: V3, rb: Float): FloatArray =
        capsule2(camera.x(a), camera.y(a), ra, camera.x(b), camera.y(b), rb)

    /** A flat-ended band of half width [r] from [a] to [b], with round ends only where asked. */
    private fun slab(a: V3, b: V3, r: Float, roundStart: Boolean, roundEnd: Boolean): FloatArray {
        val ax = camera.x(a)
        val ay = camera.y(a)
        val bx = camera.x(b)
        val by = camera.y(b)
        val dx = bx - ax
        val dy = by - ay
        val len = sqrt(dx * dx + dy * dy)
        if (len < 1e-4f) return circle2(ax, ay, r)
        val nx = -dy / len * r
        val ny = dx / len * r
        val ex = dx / len * r
        val ey = dy / len * r
        val out = FloatArray(8 + (if (roundStart) 6 else 0) + (if (roundEnd) 6 else 0))
        var o = 0
        out[o++] = ax + nx
        out[o++] = ay + ny
        if (roundStart) {
            out[o++] = ax + nx * 0.7f - ex * 0.7f
            out[o++] = ay + ny * 0.7f - ey * 0.7f
            out[o++] = ax - ex
            out[o++] = ay - ey
            out[o++] = ax - nx * 0.7f - ex * 0.7f
            out[o++] = ay - ny * 0.7f - ey * 0.7f
        }
        out[o++] = ax - nx
        out[o++] = ay - ny
        out[o++] = bx - nx
        out[o++] = by - ny
        if (roundEnd) {
            out[o++] = bx - nx * 0.7f + ex * 0.7f
            out[o++] = by - ny * 0.7f + ey * 0.7f
            out[o++] = bx + ex
            out[o++] = by + ey
            out[o++] = bx + nx * 0.7f + ex * 0.7f
            out[o++] = by + ny * 0.7f + ey * 0.7f
        }
        out[o++] = bx + nx
        out[o] = by + ny
        return out
    }

    /** A disc of [radius] at [c] with normal [n], as seen by the camera (an ellipse). */
    private fun ellipse(c: V3, n: V3, radius: Float): FloatArray {
        val nx = n dot camera.right
        val ny = n dot camera.up
        val nLen = sqrt(nx * nx + ny * ny)
        val cx = camera.x(c)
        val cy = camera.y(c)
        if (nLen < 1e-3f) return circle2(cx, cy, radius)
        // Minor axis along the projected normal, shortened by how much the disc faces the viewer.
        val ux = nx / nLen
        val uy = ny / nLen
        val minor = radius * abs(n dot camera.toViewer)
        val out = FloatArray(ELLIPSE_POINTS * 2)
        for (i in 0 until ELLIPSE_POINTS) {
            val t = 2 * PI.toFloat() * i / ELLIPSE_POINTS
            val p = cos(t) * minor
            val q = sin(t) * radius
            out[2 * i] = cx + ux * p - uy * q
            out[2 * i + 1] = cy + uy * p + ux * q
        }
        return out
    }

    /** The band joining a disc's two faces (its thickness), or null when it would not show. */
    private fun rim(back: V3, face: V3, n: V3, radius: Float): FloatArray? {
        val nx = n dot camera.right
        val ny = n dot camera.up
        val nLen = sqrt(nx * nx + ny * ny)
        if (nLen < 1e-3f) return null
        val mx = -ny / nLen * radius
        val my = nx / nLen * radius
        val bx = camera.x(back)
        val by = camera.y(back)
        val fx = camera.x(face)
        val fy = camera.y(face)
        return floatArrayOf(bx + mx, by + my, fx + mx, fy + my, fx - mx, fy - my, bx - mx, by - my)
    }

    /** Writes [p] on the picture plane as point [index] of [out]. */
    private fun putPoint(out: FloatArray, index: Int, p: V3) {
        out[2 * index] = camera.x(p)
        out[2 * index + 1] = camera.y(p)
    }

    /** Writes [c] + [f] × [a] + [l] × [b] on the picture plane as point [index] of [out], without building vectors. */
    private fun putPoint(out: FloatArray, index: Int, c: V3, f: V3, a: Float, l: V3, b: Float) {
        val x = c.x + f.x * a + l.x * b
        val y = c.y + f.y * a + l.y * b
        val z = c.z + f.z * a + l.z * b
        out[2 * index] = camera.x(x, z)
        out[2 * index + 1] = camera.y(x, y, z)
    }

    private fun Group.halo(shape: FloatArray) = halos.add(DrawOp(OpType.OUTLINE, shape, Ink.HALO, 2 * HALO, fit))

    private fun Group.haloCapsule(a: V3, ra: Float, b: V3, rb: Float) = halos.add(DrawOp(OpType.FILL, capsule(a, ra + HALO, b, rb + HALO), Ink.HALO, fit = fit))

    private fun Group.haloCircle(c: V3, r: Float) = halos.add(DrawOp(OpType.CIRCLE, floatArrayOf(camera.x(c), camera.y(c), r + HALO), Ink.HALO, fit = fit))

    private fun Group.fill(shape: FloatArray, ink: Ink) = fills.add(DrawOp(OpType.FILL, shape, ink, fit = fit))

    private fun Group.mark(shape: FloatArray, ink: Ink) = marks.add(DrawOp(OpType.FILL, shape, ink, fit = fit))

    private fun Group.circle(c: V3, r: Float, ink: Ink, halo: Boolean = true) {
        if (halo) haloCircle(c, r)
        fills.add(DrawOp(OpType.CIRCLE, floatArrayOf(camera.x(c), camera.y(c), r), ink, fit = fit))
    }

    private fun Group.line(pts: FloatArray, width: Float, ink: Ink) {
        fills.add(DrawOp(OpType.LINE, pts, ink, width, fit))
    }

    private class Group(val depth: Float, val fit: Boolean = true) {
        val halos = ArrayList<DrawOp>(4)
        val fills = ArrayList<DrawOp>(4)
        val marks = ArrayList<DrawOp>(2)
        val size: Int get() = halos.size + fills.size + marks.size

        fun emitTo(out: MutableList<DrawOp>) {
            out += halos
            out += fills
            out += marks
        }
    }

    /** A cross-section of the body: centre, axes (forward, spine, lateral) and half sizes. */
    private class Section(val c: V3, val f: V3, val u: V3, val l: V3, val front: Float, val back: Float, val lat: Float)

    /** Torso cross-section sizes at spine position [s] (metres above the hip joints). */
    private class TorsoRow(val s: Float, val lat: Float, val front: Float, val back: Float)

    /** A muscle on the torso: spine positions [from]..[to], [half] degrees around the angle [center]. */
    private class TorsoBand(val muscle: Muscle, val from: Float, val to: Float, val center: Float, val half: Float)

    private companion object {
        const val HALO = 0.016f
        const val ROD_PIECE = 0.3f
        const val ELLIPSE_POINTS = 20
        const val WAIST_BLEND = 0.05f

        /** Cross-sections of a muscle band along its segment, and steps around each one. */
        const val BAND_SECTIONS = 4
        const val BAND_STEPS = 10

        /** Back to front; compares the depths unboxed (a stable sort, like `sortBy`). */
        val BY_DEPTH = Comparator<Group> { a, b -> a.depth.compareTo(b.depth) }

        /** Arms sort a little in front of the torso and legs a little behind it, when level. */
        const val ARM_BIAS = 0.03f
        const val LEG_BIAS = -0.1f

        val TORSO = listOf(
            TorsoRow(-0.075f, 0.14f, 0.085f, 0.11f),
            TorsoRow(0.02f, 0.158f, 0.1f, 0.125f),
            TorsoRow(0.13f, 0.14f, 0.1f, 0.092f),
            TorsoRow(0.22f, 0.13f, 0.098f, 0.085f),
            TorsoRow(0.34f, 0.15f, 0.115f, 0.095f),
            TorsoRow(0.44f, 0.168f, 0.125f, 0.1f),
            TorsoRow(0.505f, 0.158f, 0.085f, 0.095f),
        )

        val TORSO_BANDS = listOf(
            TorsoBand(Muscle.CHEST, 0.33f, 0.48f, 0f, 68f),
            TorsoBand(Muscle.ABS, 0.06f, 0.31f, 0f, 48f),
            TorsoBand(Muscle.OBLIQUES, 0.05f, 0.32f, 80f, 32f),
            TorsoBand(Muscle.OBLIQUES, 0.05f, 0.32f, -80f, 32f),
            TorsoBand(Muscle.LATS, 0.25f, 0.45f, 180f, 80f),
            TorsoBand(Muscle.TRAPS, 0.42f, 0.53f, 180f, 75f),
            TorsoBand(Muscle.LOWER_BACK, 0.06f, 0.25f, 180f, 50f),
            TorsoBand(Muscle.GLUTES, -0.07f, 0.09f, 180f, 62f),
        )
    }
}

/** Averages two orientations (used where the two spine segments meet). */
internal fun blend(a: Basis, b: Basis, t: Float): Basis {
    val f = lerp(a.f, b.f, t).unit()
    val u = orthogonal(lerp(a.u, b.u, t), f)
    return Basis(f, u, f cross u)
}

/** Closed outline of a tapered capsule between circles ([ax], [ay], [ra]) and ([bx], [by], [rb]). */
internal fun capsule2(ax: Float, ay: Float, ra: Float, bx: Float, by: Float, rb: Float): FloatArray {
    val dx = bx - ax
    val dy = by - ay
    val d = sqrt(dx * dx + dy * dy)
    if (d <= abs(ra - rb) + 1e-4f) return if (ra >= rb) circle2(ax, ay, ra) else circle2(bx, by, rb)
    val ex = dx / d
    val ey = dy / d
    // The outer tangents touch both circles where the outward normal makes acos((ra - rb) / d) with the axis.
    val k = ((ra - rb) / d).coerceIn(-0.999f, 0.999f)
    val base = atan2(ey, ex)
    val spread = acos(k)
    val out = FloatArray((CAPSULE_ARC + 1) * 4)
    var o = 0
    // Arc around the start circle from the left tangent through the back to the right tangent.
    for (i in 0..CAPSULE_ARC) {
        val t = base + spread + (2 * PI.toFloat() - 2 * spread) * i / CAPSULE_ARC
        out[o++] = ax + ra * cos(t)
        out[o++] = ay + ra * sin(t)
    }
    // Arc around the end circle from the right tangent through the front to the left tangent.
    for (i in 0..CAPSULE_ARC) {
        val t = base - spread + 2 * spread * i / CAPSULE_ARC
        out[o++] = bx + rb * cos(t)
        out[o++] = by + rb * sin(t)
    }
    return out
}

internal fun circle2(cx: Float, cy: Float, r: Float, points: Int = 16): FloatArray {
    val out = FloatArray(points * 2)
    for (i in 0 until points) {
        val t = 2 * PI.toFloat() * i / points
        out[2 * i] = cx + r * cos(t)
        out[2 * i + 1] = cy + r * sin(t)
    }
    return out
}

/** One round of Chaikin corner cutting on a closed polygon: a smoother outline with twice the points. */
internal fun chaikin(pts: FloatArray): FloatArray {
    val n = pts.size / 2
    val out = FloatArray(n * 4)
    for (i in 0 until n) {
        val j = (i + 1) % n
        val x0 = pts[2 * i]
        val y0 = pts[2 * i + 1]
        val x1 = pts[2 * j]
        val y1 = pts[2 * j + 1]
        out[4 * i] = 0.75f * x0 + 0.25f * x1
        out[4 * i + 1] = 0.75f * y0 + 0.25f * y1
        out[4 * i + 2] = 0.25f * x0 + 0.75f * x1
        out[4 * i + 3] = 0.25f * y0 + 0.75f * y1
    }
    return out
}

private const val CAPSULE_ARC = 7
