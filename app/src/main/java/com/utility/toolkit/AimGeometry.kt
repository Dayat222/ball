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
     * Horizontal sidespin (English) in [-1.0, 1.0].
     * -1.0 = full left English, +1.0 = full right English.
     * Modifies cushion rebound angle (running vs check English) and Spin-Induced Throw (SIT).
     */
    var spinX = 0.0

    /**
     * Vertical spin (Top/Back) in [-1.0, 1.0].
     * -1.0 = full draw/backspin, 0.0 = stun, +1.0 = full follow/topspin.
     * Drives the post-impact cue ball deflection path from the 90-degree tangent line.
     */
    var spinY = 0.0

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

    /** Third cushion contact for a three-rail bank. */
    var hit3X = 0.0
        private set
    var hit3Y = 0.0
        private set

    /** Post-impact cue-ball deflection trajectory (stun tangent + follow/draw curve). */
    var deflectStartX = 0.0
        private set
    var deflectStartY = 0.0
        private set
    var deflectCtrlX = 0.0
        private set
    var deflectCtrlY = 0.0
        private set
    var deflectEndX = 0.0
        private set
    var deflectEndY = 0.0
        private set
    var deflectTangentEndX = 0.0
        private set
    var deflectTangentEndY = 0.0
        private set
    var deflectValid = false
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
        scaleCorrection = ballScaleCorrection.toDouble().coerceIn(0.5, 1.6)

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
        val isMiddle = pocket == Pocket.TOP_CENTER || pocket == Pocket.BOTTOM_CENTER
        // Middle pockets require a wider safety threshold due to sharp jaw rattle.
        val minAperture = if (isMiddle) 2.12 * ballRadiusMm else 2.0 * ballRadiusMm
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

                // English (sidespin) angle shift on cushion bounce.
                // Determine whether +along is to the right of the incoming normal:
                // - Horizontal rail: normal points -side * Y. Right of (0, -side) is (side, 0).
                // - Vertical rail: normal points -side * X. Right of (-side, 0) is (0, -side).
                val rightAlongSign = if (horizontal) side.toDouble() else -side.toDouble()
                val spinShiftAlong = spinX.coerceIn(-1.0, 1.0) * MAX_SPIN_BANK_SHIFT_RAD * rightAlongSign
                val signedSpinShift = sigma * spinShiftAlong

                fun outAngle(theta: Double): Double =
                    (rebound * theta + signedSpinShift).coerceIn(-MAX_OUT_ANGLE_RAD, MAX_OUT_ANGLE_RAD)

                // theta = incidence angle measured from the cushion normal.
                // f(theta) = remaining horizontal travel - tan(outAngle(theta)) term.
                var lo = 0.0
                var hi = atan(flat / offset)
                val fHi = flat - offset * tan(hi) - (nPocket - c) * tan(outAngle(hi))
                if (fHi >= 0.0) {
                    // Outgoing angle crosses the tangent asymptote before the flat
                    // travel is consumed: no real bounce on this rail.
                    hitValid = false
                    return false
                }
                repeat(48) {
                    val mid = (lo + hi) * 0.5
                    val f = flat - offset * tan(mid) - (nPocket - c) * tan(outAngle(mid))
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

    /**
     * Three-cushion bank: ball -> [axis1] rail -> [axis2] rail -> [axis3] rail -> gate.
     *
     * Unfolds the pocket gate across rail3, rail2, and rail1 in sequence so the
     * triple bounce collapses into one straight segment in thrice-reflected space.
     * Each crossing parameter t1 < t2 < t3 is then folded back across the preceding
     * planes to recover the real contact points [hitX]/[hitY], [hit2X]/[hit2Y],
     * and [hit3X]/[hit3Y] in pixels.
     */
    fun bankFrom3(
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
        railMax2: Double,
        axis3: Axis,
        plane3: Double,
        railMin3: Double,
        railMax3: Double
    ): Boolean {
        hitValid = false
        if (!frameValid) return false
        if (axis1 == axis2 && abs(plane1 - plane2) < 1e-6) return false
        if (axis2 == axis3 && abs(plane2 - plane3) < 1e-6) return false

        // Unfold: mirror gate across rail3 -> rail2 -> rail1.
        var gx = pocketX
        var gy = pocketY
        if (axis3 == Axis.VERTICAL) gx = 2.0 * plane3 - gx else gy = 2.0 * plane3 - gy
        if (axis2 == Axis.VERTICAL) gx = 2.0 * plane2 - gx else gy = 2.0 * plane2 - gy
        if (axis1 == Axis.VERTICAL) gx = 2.0 * plane1 - gx else gy = 2.0 * plane1 - gy

        val dx = gx - bx
        val dy = gy - by
        if (abs(dx) < 1e-9 && abs(dy) < 1e-9) return false

        // Plane2 in unfolded space is reflected across rail1.
        val unfoldedPlane2 = if (axis1 == axis2) 2.0 * plane1 - plane2 else plane2
        // Plane3 in unfolded space is reflected across rail2 then rail1.
        var unfoldedPlane3 = if (axis2 == axis3) 2.0 * plane2 - plane3 else plane3
        if (axis1 == axis3) unfoldedPlane3 = 2.0 * plane1 - unfoldedPlane3

        fun crossT(axis: Axis, plane: Double): Double? =
            if (axis == Axis.HORIZONTAL) {
                if (abs(dy) < 1e-12) null else (plane - by) / dy
            } else {
                if (abs(dx) < 1e-12) null else (plane - bx) / dx
            }

        val t1 = crossT(axis1, plane1) ?: return false
        val t2 = crossT(axis2, unfoldedPlane2) ?: return false
        val t3 = crossT(axis3, unfoldedPlane3) ?: return false
        if (t1 <= 1e-6 || t2 <= t1 + 1e-6 || t3 <= t2 + 1e-6 || t3 >= 1.0) return false

        // Contact 1 (real space)
        val p1x = bx + dx * t1
        val p1y = by + dy * t1

        // Contact 2: fold back across rail1
        val q2x = bx + dx * t2
        val q2y = by + dy * t2
        val p2x = if (axis1 == Axis.VERTICAL) 2.0 * plane1 - q2x else q2x
        val p2y = if (axis1 == Axis.HORIZONTAL) 2.0 * plane1 - q2y else q2y

        // Contact 3: fold back across rail1, then rail2
        val q3x = bx + dx * t3
        val q3y = by + dy * t3
        val r3x = if (axis1 == Axis.VERTICAL) 2.0 * plane1 - q3x else q3x
        val r3y = if (axis1 == Axis.HORIZONTAL) 2.0 * plane1 - q3y else q3y
        val p3x = if (axis2 == Axis.VERTICAL) 2.0 * plane2 - r3x else r3x
        val p3y = if (axis2 == Axis.HORIZONTAL) 2.0 * plane2 - r3y else r3y

        val along1 = if (axis1 == Axis.HORIZONTAL) p1x else p1y
        val along2 = if (axis2 == Axis.HORIZONTAL) p2x else p2y
        val along3 = if (axis3 == Axis.HORIZONTAL) p3x else p3y
        if (along1 < railMin1 || along1 > railMax1) return false
        if (along2 < railMin2 || along2 > railMax2) return false
        if (along3 < railMin3 || along3 > railMax3) return false

        hitX = p1x
        hitY = p1y
        hit2X = p2x
        hit2Y = p2y
        hit3X = p3x
        hit3Y = p3y
        hitValid = true
        return true
    }

    /**
     * Checks whether an obstacle ball at ([obsX], [obsY]) blocks the travelling
     * ball path from ([startX], [startY]) to ([endX], [endY]).
     *
     * Since both balls have radius [ballRadius], a collision occurs when the
     * centre-to-segment distance is strictly less than 2 * [ballRadius] (minus a
     * small epsilon so touching endpoints do not self-trigger).
     */
    fun isPathObstructed(
        startX: Double,
        startY: Double,
        endX: Double,
        endY: Double,
        obsX: Double,
        obsY: Double
    ): Boolean {
        if (!frameValid) return false
        val vx = endX - startX
        val vy = endY - startY
        val len2 = vx * vx + vy * vy
        if (len2 < 1e-6) return false

        val wx = obsX - startX
        val wy = obsY - startY
        val t = ((wx * vx + wy * vy) / len2).coerceIn(0.0, 1.0)
        // Ignore if the obstacle is basically at the start or end point itself
        if (t <= 1e-3 || t >= 0.999) return false

        val closestX = startX + vx * t
        val closestY = startY + vy * t
        val dist = hypot(obsX - closestX, obsY - closestY)
        return dist < (2.0 * ballRadius * 0.96)
    }

    /**
     * Computes the post-impact cue-ball deflection path after striking the target
     * ball at ([bx], [by]) from cue ball ([cx], [cy]) towards ([targetAimX], [targetAimY]).
     *
     * Physics:
     * 1. At impact, the cue ball is at the ghost-ball position `G`.
     * 2. The line of centres unit vector is `S = normalize(B - G)`.
     * 3. The 90-degree stun tangent direction `T` is perpendicular to `S`, chosen
     *    on the same side as the incoming cue ball velocity `I = normalize(G - C)`.
     * 4. With stun (`spinY == 0`), the cue ball slides purely along `T`.
     * 5. With topspin (`spinY > 0`), the cue ball curves forward from `T` toward `S`.
     *    With backspin (`spinY < 0`), the cue ball draws backward from `T` toward `-S`
     *    (or straight back along `-I` on a dead-straight shot).
     *
     * Populates [deflectStartX]..[deflectTangentEndY] and sets [deflectValid].
     */
    fun solveCueDeflection(
        bx: Double,
        by: Double,
        cx: Double,
        cy: Double,
        targetAimX: Double = targetX,
        targetAimY: Double = targetY,
        guideLengthPx: Double = ballRadius * 5.5
    ): Boolean {
        deflectValid = false
        if (!frameValid) return false

        val (gxF, gyF) = ghostBall(
            bx.toFloat(), by.toFloat(),
            cx.toFloat(), cy.toFloat(),
            targetAimX, targetAimY
        )
        val gx = gxF.toDouble()
        val gy = gyF.toDouble()

        // Line of centres from ghost ball to target ball
        val sDx = bx - gx
        val sDy = by - gy
        val sLen = hypot(sDx, sDy)
        if (sLen < 1e-4) return false
        val sx = sDx / sLen
        val sy = sDy / sLen

        // Incoming cue ball vector C -> G
        val inDx = gx - cx
        val inDy = gy - cy
        val inLen = hypot(inDx, inDy)
        if (inLen < 1e-4) return false
        val ix = inDx / inLen
        val iy = inDy / inLen

        // Must hit the front hemisphere of the ghost ball (cosCut > 0.05)
        val cosCut = ix * sx + iy * sy
        if (cosCut <= 0.05) return false

        // Two candidate normals to S: N1 = (-sy, sx), N2 = (sy, -sx).
        // Pick the one with positive dot product with incoming vector I.
        val cross = ix * (-sy) + iy * sx
        val tx: Double
        val ty: Double
        if (abs(cross) < 1e-3) {
            // Almost dead-straight shot: tangent component is minimal
            tx = -sy
            ty = sx
        } else if (cross > 0.0) {
            tx = -sy
            ty = sx
        } else {
            tx = sy
            ty = -sx
        }

        val sinCut = abs(cross).coerceIn(0.0, 1.0)
        val syClamped = spinY.coerceIn(-1.0, 1.0)

        deflectStartX = gx
        deflectStartY = gy

        // Pure stun tangent line endpoint
        val stunScale = sinCut.coerceAtLeast(0.22)
        deflectTangentEndX = gx + tx * guideLengthPx * stunScale
        deflectTangentEndY = gy + ty * guideLengthPx * stunScale

        // Initial tangent departure (control point of quadratic Bezier)
        val ctrlDist = guideLengthPx * 0.42 * stunScale
        deflectCtrlX = gx + tx * ctrlDist
        deflectCtrlY = gy + ty * ctrlDist

        // Final asymptotic direction combines tangent carry (tx, ty) and longitudinal spin (sx, sy)
        val tangentWeight = sinCut * (1.0 - 0.35 * abs(syClamped))
        val forwardWeight = syClamped * (0.45 + 0.55 * cosCut)
        var endVx = tx * tangentWeight + sx * forwardWeight
        var endVy = ty * tangentWeight + sy * forwardWeight
        val endVLen = hypot(endVx, endVy)
        if (endVLen < 1e-4) {
            endVx = tx
            endVy = ty
        } else {
            endVx /= endVLen
            endVy /= endVLen
        }

        deflectEndX = deflectCtrlX + endVx * (guideLengthPx * 0.68)
        deflectEndY = deflectCtrlY + endVy * (guideLengthPx * 0.68)
        deflectValid = true
        return true
    }

    /**
     * Calculates the ghost-ball centre for [pocket] including Cut-Induced Throw (CIT)
     * and Spin-Induced Throw (SIT) compensation when the cue ball position ([cx], [cy])
     * or horizontal sidespin [spinX] is active.
     *
     * @return Pair(ghostX, ghostY) in pixels.
     */
    fun ghostBall(
        bx: Float,
        by: Float,
        cx: Float? = null,
        cy: Float? = null,
        targetAimX: Double = targetX,
        targetAimY: Double = targetY
    ): Pair<Float, Float> {
        val dx = (targetAimX - bx).toFloat()
        val dy = (targetAimY - by).toFloat()
        val dist = hypot(dx, dy)
        if (dist < 1e-4f) return Pair(bx, by)

        var sx = dx / dist
        var sy = dy / dist

        var totalThrowRad = 0f

        if (cx != null && cy != null) {
            val inDx = bx - cx
            val inDy = by - cy
            val inDist = hypot(inDx, inDy)
            if (inDist > 1e-4f) {
                val inUx = inDx / inDist
                val inUy = inDy / inDist
                val sinCut = inUx * sy - inUy * sx
                val cosCut = inUx * sx + inUy * sy

                if (cosCut > 0.0) {
                    val maxCitRad = (1.8 * Math.PI / 180.0).toFloat()
                    totalThrowRad += maxCitRad * (2f * sinCut * cosCut.toFloat())
                }
            }
        }

        // Spin-Induced Throw (SIT): sidespin gears the object ball in the opposite
        // rotational sense. Compensate the ghost-ball aim point accordingly.
        if (abs(spinX) > 1e-3) {
            val maxSitRad = MAX_SIT_THROW_RAD.toFloat()
            totalThrowRad += (-spinX.toFloat() * maxSitRad)
        }

        if (abs(totalThrowRad) > 1e-6f) {
            val cosT = kotlin.math.cos(-totalThrowRad)
            val sinT = kotlin.math.sin(-totalThrowRad)
            val rotX = sx * cosT - sy * sinT
            val rotY = sx * sinT + sy * cosT
            sx = rotX
            sy = rotY
        }

        val r = ballRadius.toFloat()
        return Pair(bx - sx * 2f * r, by - sy * 2f * r)
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

        /** Max cushion rebound angle shift at full sidespin (~12 degrees). */
        const val MAX_SPIN_BANK_SHIFT_RAD = 12.0 * Math.PI / 180.0
        /** Max Spin-Induced Throw (SIT) angle at full English (~2.2 degrees). */
        const val MAX_SIT_THROW_RAD = 2.2 * Math.PI / 180.0
        /** Asymptote clamp for outgoing cushion angle (~84 degrees). */
        private const val MAX_OUT_ANGLE_RAD = 84.0 * Math.PI / 180.0

        private const val GOLDEN = 0.6180339887498949
    }
}
