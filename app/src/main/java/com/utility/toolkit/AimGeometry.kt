package com.utility.toolkit

import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan

/**
 * Analytical aiming solver for the six-pocket overlay.
 *
 * All maths run in real table millimetres (9 ft playing surface, 2540 x 1270
 * mm, ball radius 28.575 mm) and every result is projected back into the pixel
 * space of the calibrated boundary rect. Pixel-relative ratios are gone: the
 * jaw offset, the mouth width and the ball radius are physical constants, so a
 * resize of the calibration rect rescales the whole model instead of re-tuning
 * magic numbers.
 *
 * Model
 * -----
 * 1. The calibrated rectangle is the cushion-nose playing surface.
 * 2. Corner jaw tips sit [CORNER_JAW_MM] from the corner along each rail; a
 *    side pocket mouth is [MIDDLE_HALF_MM] either side of the long-rail centre.
 * 3. The ball radius is derived from the table scale (px per mm) times an
 *    optional user correction factor, never typed in as a dp value.
 * 4. Every ray aims at the point inside the mouth that maximises the minimum
 *    clearance to the two jaw tips. Clearance is measured to the jaw *segment*
 *    (point-to-segment distance), so it cannot be overestimated behind the
 *    origin.
 * 5. A shot is only feasible when the mouth still presents at least one ball
 *    diameter to the incoming line: effective aperture = mouth length *
 *    |cos(entry angle)|. Wide cuts that physically cannot drop are flagged
 *    instead of being drawn as a guide.
 * 6. A cushion bank mirrors that gate point across the ball-centre reflection
 *    line (nose +/- one radius) and intersects the incoming centre line, i.e.
 *    exact equal-angle geometry. [rebound] scales the outgoing angle
 *    (out = k * in) and the bounce point is re-solved by bisection.
 */
class AimGeometry {

    enum class Pocket {
        TOP_LEFT, TOP_CENTER, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_CENTER, BOTTOM_RIGHT
    }

    enum class Axis { HORIZONTAL, VERTICAL }

    // Table frame (px)
    private var left = 0.0
    private var top = 0.0
    private var right = 0.0
    private var bottom = 0.0

    // px per mm along each table axis
    private var scaleX = 1.0
    private var scaleY = 1.0

    // Mouth geometry (px, for drawing)
    var cornerJawPx = 0.0
        private set
    var middleHalfPx = 0.0
        private set
    var mouthDepthPx = 0.0
        private set

    /** Ball radius in px, derived from the table scale. */
    var ballRadius = 0.0
        private set

    /** Ball radius in mm, derived from the table scale. */
    var ballRadiusMm = BALL_RADIUS_MM
        private set

    /** User fine-tune of the derived radius. 1.0 = real 57.15 mm ball. */
    var scaleCorrection = 1.0
        private set

    /** Angle ratio of a cushion rebound. 1.0 = perfect specular mirror. */
    var rebound = 1.0

    /**
     * Tuning of the pocket mouth against the hard-coded 9 ft table. A game
     * table is not always a faithful 9 ft replica, so the jaw offset and the
     * side-mouth half width can be scaled independently. 1.0 = real table.
     */
    var jawScale = 1.0
    var mouthScale = 1.0

    /**
     * False until [configure] has built a consistent frame. Guards every solve
     * so a collapsed boundary box cannot leave the previous frame's jaw array
     * in place and report nonsense geometry as a valid aim.
     */
    var frameValid = false
        private set

    // jaw[i * 4 + 0..3] = x1, y1, x2, y2 for the two jaw tips of pocket i (mm)
    private val jaw = DoubleArray(24)

    // Results of the last call (scratch, no allocation)
    var targetX = 0.0
        private set
    var targetY = 0.0
        private set

    /** Jaw clearance at the gate point, in mm. */
    var targetClearance = 0.0
        private set

    /** True when the mouth presents at least one ball diameter to this line. */
    var gateFeasible = false
        private set

    var hitX = 0.0
        private set
    var hitY = 0.0
        private set
    var hitValid = false
        private set

    /** Second cushion contact for a two-rail bank. */
    var hit2X = 0.0
        private set
    var hit2Y = 0.0
        private set

    /** Effective mouth width for the last gate, in mm (aperture * cos). */
    var gateApertureMm = 0.0
        private set

    /**
     * Rebuild the pocket frame for the current boundary box.
     *
     * @param ballScaleCorrection user fine-tune of the derived ball radius
     *        (1.0 = a real 57.15 mm ball on a correctly calibrated table).
     */
    fun configure(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        ballScaleCorrection: Float = 1.0f
    ) {
        this.left = left.toDouble()
        this.top = top.toDouble()
        this.right = right.toDouble()
        this.bottom = bottom.toDouble()

        val w = this.right - this.left
        val h = this.bottom - this.top
        if (w < 4.0 || h < 4.0) {
            frameValid = false
            return
        }

        scaleX = w / TABLE_LENGTH_MM
        scaleY = h / TABLE_WIDTH_MM
        scaleCorrection = ballScaleCorrection.toDouble().coerceIn(0.5, 1.5)

        // One physical ball: average the two axis scales so a slightly
        // mis-proportioned calibration does not produce an elliptical ball.
        val pxPerMm = (scaleX + scaleY) * 0.5
        ballRadiusMm = BALL_RADIUS_MM * scaleCorrection
        ballRadius = ballRadiusMm * pxPerMm

        cornerJawPx = CORNER_JAW_MM * jawScale.coerceIn(0.5, 2.0) * scaleX
        middleHalfPx = MIDDLE_HALF_MM * mouthScale.coerceIn(0.5, 2.0) * scaleX
        mouthDepthPx = MOUTH_DEPTH_MM * pxPerMm

        val d = CORNER_JAW_MM * jawScale.coerceIn(0.5, 2.0)
        val m = MIDDLE_HALF_MM * mouthScale.coerceIn(0.5, 2.0)
        val cx = TABLE_LENGTH_MM * 0.5

        putJaw(Pocket.TOP_LEFT, d, 0.0, 0.0, d)
        putJaw(Pocket.TOP_CENTER, cx - m, 0.0, cx + m, 0.0)
        putJaw(Pocket.TOP_RIGHT, TABLE_LENGTH_MM - d, 0.0, TABLE_LENGTH_MM, d)
        putJaw(Pocket.BOTTOM_LEFT, d, TABLE_WIDTH_MM, 0.0, TABLE_WIDTH_MM - d)
        putJaw(Pocket.BOTTOM_CENTER, cx - m, TABLE_WIDTH_MM, cx + m, TABLE_WIDTH_MM)
        putJaw(Pocket.BOTTOM_RIGHT, TABLE_LENGTH_MM - d, TABLE_WIDTH_MM, TABLE_LENGTH_MM, TABLE_WIDTH_MM - d)

        frameValid = true
    }

    private fun putJaw(pocket: Pocket, x1: Double, y1: Double, x2: Double, y2: Double) {
        val i = pocket.ordinal * 4
        jaw[i] = x1
        jaw[i + 1] = y1
        jaw[i + 2] = x2
        jaw[i + 3] = y2
    }

    /** px -> mm along the table long axis. */
    private fun toMmX(px: Double): Double = (px - left) / scaleX

    /** px -> mm along the table short axis. */
    private fun toMmY(px: Double): Double = (px - top) / scaleY

    private fun toPxX(mm: Double): Double = left + mm * scaleX

    private fun toPxY(mm: Double): Double = top + mm * scaleY

    /**
     * Solve the pocket entry point seen from [ox], [oy] (px). The gate point is
     * the point on the mouth line that leaves the most room to both jaw tips;
     * [targetClearance] is the resulting jaw clearance in mm.
     */
    fun gate(pocket: Pocket, ox: Double, oy: Double) {
        if (!frameValid) {
            targetX = ox
            targetY = oy
            targetClearance = 0.0
            gateFeasible = false
            gateApertureMm = 0.0
            return
        }
        val i = pocket.ordinal * 4
        val ax = jaw[i]
        val ay = jaw[i + 1]
        val bx = jaw[i + 2]
        val by = jaw[i + 3]
        val dx = bx - ax
        val dy = by - ay

        val oxm = toMmX(ox)
        val oym = toMmY(oy)

        val mouthLen = hypot(dx, dy)
        if (mouthLen < 1e-6) {
            targetX = toPxX(ax)
            targetY = toPxY(ay)
            targetClearance = 0.0
            gateFeasible = false
            gateApertureMm = 0.0
            return
        }

        // Coarse scan, then a golden-section refine around the best sample.
        var bestLam = 0.5
        var bestScore = -1.0
        val steps = 24
        for (s in 0..steps) {
            val lam = s.toDouble() / steps
            val score = clearance(oxm, oym, ax + dx * lam, ay + dy * lam, ax, ay, bx, by)
            if (score > bestScore) {
                bestScore = score
                bestLam = lam
            }
        }

        var lo = max(0.0, bestLam - 1.0 / steps)
        var hi = min(1.0, bestLam + 1.0 / steps)
        repeat(16) {
            val m1 = hi - GOLDEN * (hi - lo)
            val m2 = lo + GOLDEN * (hi - lo)
            val s1 = clearance(oxm, oym, ax + dx * m1, ay + dy * m1, ax, ay, bx, by)
            val s2 = clearance(oxm, oym, ax + dx * m2, ay + dy * m2, ax, ay, bx, by)
            if (s2 > s1) lo = m1 else hi = m2
        }

        val lam = (lo + hi) * 0.5
        val tx = ax + dx * lam
        val ty = ay + dy * lam
        targetClearance = clearance(oxm, oym, tx, ty, ax, ay, bx, by)

        // Entry-angle criterion: the mouth presents mouthLen * |cos| of opening
        // to the incoming line. Less than one ball diameter cannot drop.
        val vx = tx - oxm
        val vy = ty - oym
        val vlen = hypot(vx, vy)
        if (vlen < 1e-6) {
            targetX = toPxX(tx)
            targetY = toPxY(ty)
            gateFeasible = false
            gateApertureMm = 0.0
            return
        }
        // Mouth normal, then |cos| of the angle between the ray and the normal.
        val nx = -dy / mouthLen
        val ny = dx / mouthLen
        val cosEntry = abs((vx * nx + vy * ny) / vlen)
        gateApertureMm = mouthLen * cosEntry

        // A ball centre that starts behind the mouth line is not a pot. The
        // mouth normal has no fixed orientation per pocket, so the table
        // centre is used as the reference for "inside".
        val refSide = (TABLE_LENGTH_MM * 0.5 - ax) * nx +
            (TABLE_WIDTH_MM * 0.5 - ay) * ny
        val side = (oxm - ax) * nx + (oym - ay) * ny
        val minAperture = 2.0 * ballRadiusMm
        gateFeasible = gateApertureMm >= minAperture &&
            side * refSide > 0.0 &&
            targetClearance >= ballRadiusMm

        targetX = toPxX(tx)
        targetY = toPxY(ty)
    }

    /**
     * Point-to-segment distance from the jaw tip [ax],[ay] to the ray
     * origin -> target. Clamping the projection parameter stops the infinite
     * line from reporting clearance to a jaw that is already behind the ball.
     */
    private fun clearance(
        ox: Double, oy: Double,
        tx: Double, ty: Double,
        ax: Double, ay: Double,
        bx: Double, by: Double
    ): Double {
        val vx = tx - ox
        val vy = ty - oy
        val len2 = vx * vx + vy * vy
        if (len2 < 1e-9) return -1.0

        val w1x = ax - ox
        val w1y = ay - oy
        val t1 = ((w1x * vx + w1y * vy) / len2).coerceIn(0.0, 1.0)
        val d1 = hypot(w1x - vx * t1, w1y - vy * t1)

        val w2x = bx - ox
        val w2y = by - oy
        val t2 = ((w2x * vx + w2y * vy) / len2).coerceIn(0.0, 1.0)
        val d2 = hypot(w2x - vx * t2, w2y - vy * t2)

        return min(d1, d2)
    }

    /**
     * Bounce point of the ball centre off one cushion.
     *
     * [axis] selects the rail direction, [plane] is the ball-centre reflection
     * line in px (cushion nose offset by one radius), [side] is +1 when ball
     * and target sit on the larger-coordinate side of the plane and -1
     * otherwise. [railMin]..[railMax] is the usable stretch of that rail in
     * along-axis px. The pocket gate already in [targetX]/[targetY] is used as
     * the destination. The solve runs in table mm; the hit point is projected
     * back to px.
     */
    fun bankFrom(
        bx: Double,
        by: Double,
        pocketX: Double,
        pocketY: Double,
        axis: Axis,
        plane: Float,
        side: Int,
        railMin: Double,
        railMax: Double
    ): Boolean {
        if (!frameValid || side == 0 || rebound <= 0.0) {
            hitValid = false
            return false
        }
        val horizontal = axis == Axis.HORIZONTAL

        val alongBall = if (horizontal) toMmX(bx) else toMmY(by)
        val normBall = if (horizontal) toMmY(by) else toMmX(bx)
        val alongPocket = if (horizontal) toMmX(pocketX) else toMmY(pocketY)
        val normPocket = if (horizontal) toMmY(pocketY) else toMmX(pocketX)

        val planeMm = if (horizontal) {
            (plane.toDouble() - top) / scaleY
        } else {
            (plane.toDouble() - left) / scaleX
        }
        val railMinMm = if (horizontal) toMmX(railMin) else toMmY(railMin)
        val railMaxMm = if (horizontal) toMmX(railMax) else toMmY(railMax)

        val nBall = side * normBall
        val nPocket = side * normPocket
        val c = side * planeMm

        // Both the ball and the destination must sit in front of the plane.
        if (nPocket <= c + 0.5 || nBall < c - 0.5) {
            hitValid = false
            return false
        }

        val offset = nBall - c
        val hitAlong: Double

        if (offset < 0.75) {
            // Frozen on (or inside) the cushion plane: the centre is already at
            // the contact line, so the bounce point is directly in front of it.
            hitAlong = alongBall
        } else {
            val flat = abs(alongPocket - alongBall)
            if (flat < 1e-6) {
                hitAlong = alongBall
            } else {
                val sigma = if (alongPocket >= alongBall) 1.0 else -1.0

                // theta = incidence angle measured from the cushion normal.
                // f(theta) = remaining horizontal travel - tan(k * theta) term.
                // f(0) = flat > 0, strictly decreasing while it is defined.
                var lo = 0.0
                var hi = atan(flat / offset)
                val fHi = flat - offset * tan(hi) - (nPocket - c) * tan(rebound * hi)
                if (fHi >= 0.0) {
                    // k * theta crosses the tangent asymptote before the flat
                    // travel is consumed: no real bounce on this rail.
                    hitValid = false
                    return false
                }
                repeat(48) {
                    val mid = (lo + hi) * 0.5
                    val f = flat - offset * tan(mid) - (nPocket - c) * tan(rebound * mid)
                    if (f > 0.0) lo = mid else hi = mid
                }
                hitAlong = alongBall + sigma * offset * tan((lo + hi) * 0.5)
            }
        }

        if (hitAlong < railMinMm || hitAlong > railMaxMm) {
            hitValid = false
            return false
        }

        hitValid = true
        if (horizontal) {
            hitX = toPxX(hitAlong)
            hitY = plane.toDouble()
        } else {
            hitX = plane.toDouble()
            hitY = toPxY(hitAlong)
        }
        return true
    }

    /** Radial depth the full-length guide runs past the jaw line, in px. */
    fun mouthDepthPxFor(scale: Float): Float = mouthDepthPx.toFloat() * scale

    /**
     * Two-cushion bank: ball -> [axis1] rail -> [axis2] rail -> gate.
     *
     * Classic billiard unfolding. Reflecting the gate across rail2 and then
     * that image across rail1 turns the double bounce into a single straight
     * line, so both contact points are plane crossings of that line. Because
     * the second crossing happens in a space already reflected across rail1,
     * its real contact is the crossing point reflected back across rail1.
     *
     * This is exact equal-angle geometry. [rebound] (the cloth "short" ratio)
     * is deliberately ignored here: a two-rail path already accumulates its own
     * error and mixing a non-unit mirror with unfolding needs an iterative
     * solve that is not worth it for a secondary guide.
     *
     * Results: [hitX]/[hitY] first contact, [hit2X]/[hit2Y] second contact.
     */
    fun bankFrom2(
        bx: Double,
        by: Double,
        pocketX: Double,
        pocketY: Double,
        axis1: Axis,
        plane1: Double,
        railMin1: Double,
        railMax1: Double,
        axis2: Axis,
        plane2: Double,
        railMin2: Double,
        railMax2: Double
    ): Boolean {
        hitValid = false
        if (!frameValid) return false
        if (axis1 == axis2 && abs(plane1 - plane2) < 1e-6) return false

        // Unfold: mirror the gate across rail2, then across rail1.
        var gx = pocketX
        var gy = pocketY
        if (axis2 == Axis.VERTICAL) gx = 2.0 * plane2 - gx else gy = 2.0 * plane2 - gy
        if (axis1 == Axis.VERTICAL) gx = 2.0 * plane1 - gx else gy = 2.0 * plane1 - gy

        val dx = gx - bx
        val dy = gy - by
        if (abs(dx) < 1e-9 && abs(dy) < 1e-9) return false

        fun crossT(axis: Axis, plane: Double): Double? =
            if (axis == Axis.HORIZONTAL) {
                if (abs(dy) < 1e-12) null else (plane - by) / dy
            } else {
                if (abs(dx) < 1e-12) null else (plane - bx) / dx
            }

        val t1 = crossT(axis1, plane1) ?: return false
        val t2 = crossT(axis2, plane2) ?: return false
        if (t1 <= 1e-6 || t2 <= t1 + 1e-6 || t2 >= 1.0) return false

        val p1x = bx + dx * t1
        val p1y = by + dy * t1

        // Undo the rail1 reflection to get the real second contact.
        val q2x = bx + dx * t2
        val q2y = by + dy * t2
        val p2x = if (axis1 == Axis.VERTICAL) 2.0 * plane1 - q2x else q2x
        val p2y = if (axis1 == Axis.HORIZONTAL) 2.0 * plane1 - q2y else q2y

        val along1 = if (axis1 == Axis.HORIZONTAL) p1x else p1y
        val along2 = if (axis2 == Axis.HORIZONTAL) p2x else p2y
        if (along1 < railMin1 || along1 > railMax1) return false
        if (along2 < railMin2 || along2 > railMax2) return false

        hitX = p1x
        hitY = p1y
        hit2X = p2x
        hit2Y = p2y
        hitValid = true
        return true
    }

    companion object {
        /** 9 ft playing surface, long axis. */
        const val TABLE_LENGTH_MM = 2540.0
        /** 9 ft playing surface, short axis. */
        const val TABLE_WIDTH_MM = 1270.0
        /** 2 1/4 in ball diameter. */
        const val BALL_RADIUS_MM = 28.575
        /** Corner jaw-tip offset from the corner along each rail. */
        const val CORNER_JAW_MM = 80.6
        /** Half a side-pocket mouth. */
        const val MIDDLE_HALF_MM = 63.5
        /** How far the full-length guide may run past the jaw line. */
        const val MOUTH_DEPTH_MM = 22.9

        private const val GOLDEN = 0.6180339887498949
    }
}
