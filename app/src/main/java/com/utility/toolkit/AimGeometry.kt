package com.utility.toolkit

import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan

/**
 * Analytical aiming solver for the six-pocket overlay.
 *
 * Coordinate space is the same pixel space as the calibrated boundary rect
 * (landscape). All maths run in Double and the solver allocates nothing per
 * frame, so the full 6 direct + 10 bank solve still fits in one vsync.
 *
 * Model
 * -----
 * 1. The calibrated rectangle is treated as the cushion-nose playing surface.
 * 2. Pocket mouths follow real 9 ft proportions: a corner jaw tip sits
 *    [CORNER_JAW_RATIO] of the short side away from the corner along each rail;
 *    a side pocket mouth is [MIDDLE_HALF_RATIO] of the short side either side
 *    of the long-rail centre line.
 * 3. Every ray aims at the point inside the mouth that maximises the minimum
 *    clearance to the two jaw tips for the ball radius. This replaces the old
 *    fixed 9.0dp / 11.2dp inset and the hand-tuned tilt heuristic: a wide or
 *    angled cut is automatically bent away from the near jaw.
 * 4. A cushion bank mirrors that gate point across the ball-centre reflection
 *    line (nose +/- one ball radius) and intersects the incoming centre line,
 *    i.e. exact equal-angle geometry. [rebound] scales the outgoing angle
 *    (out = k * in) and the bounce point is re-solved by bisection, so a real
 *    "short" cushion can be dialled in without touching the closed-form mirror
 *    case at k = 1.
 *
 * Tuning: if a title uses deeper/shallower pockets than a real table, only the
 * two ratios below need to move. Everything else derives from them.
 */
class AimGeometry {

    enum class Pocket {
        TOP_LEFT, TOP_CENTER, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_CENTER, BOTTOM_RIGHT
    }

    enum class Axis { HORIZONTAL, VERTICAL }

    /** Corner jaw-tip offset from the corner, as a fraction of the short side. */
    private val cornerJawRatio = CORNER_JAW_RATIO
    /** Half a side-pocket mouth, as a fraction of the short side. */
    private val middleHalfRatio = MIDDLE_HALF_RATIO

    // Table frame (px)
    private var left = 0.0
    private var top = 0.0
    private var right = 0.0
    private var bottom = 0.0

    // Mouth geometry (px)
    var cornerJawPx = 0.0
        private set
    var middleHalfPx = 0.0
        private set
    var mouthDepthPx = 0.0
        private set
    var ballRadius = 0.0
        private set

    /** Angle ratio of a cushion rebound. 1.0 = perfect specular mirror. */
    var rebound = 1.0

    // jaw[i * 4 + 0..3] = x1, y1, x2, y2 for the two jaw tips of pocket i
    private val jaw = DoubleArray(24)

    // Results of the last call (scratch, no allocation)
    var targetX = 0.0
        private set
    var targetY = 0.0
        private set
    var targetClearance = 0.0
        private set
    var hitX = 0.0
        private set
    var hitY = 0.0
        private set
    var hitValid = false
        private set

    /**
     * Rebuild the pocket frame for the current boundary box and ball radius.
     * Cheap enough to call once per frame.
     */
    fun configure(left: Float, top: Float, right: Float, bottom: Float, ballRadiusPx: Float) {
        this.left = left.toDouble()
        this.top = top.toDouble()
        this.right = right.toDouble()
        this.bottom = bottom.toDouble()
        this.ballRadius = ballRadiusPx.toDouble()

        val w = this.right - this.left
        val h = this.bottom - this.top
        if (w < 4.0 || h < 4.0) return

        val shortSide = min(w, h)
        cornerJawPx = shortSide * cornerJawRatio
        middleHalfPx = shortSide * middleHalfRatio
        mouthDepthPx = shortSide * MOUTH_DEPTH_RATIO

        val d = cornerJawPx
        val m = middleHalfPx
        val cx = (this.left + this.right) * 0.5

        putJaw(Pocket.TOP_LEFT, this.left + d, this.top, this.left, this.top + d)
        putJaw(Pocket.TOP_CENTER, cx - m, this.top, cx + m, this.top)
        putJaw(Pocket.TOP_RIGHT, this.right - d, this.top, this.right, this.top + d)
        putJaw(Pocket.BOTTOM_LEFT, this.left + d, this.bottom, this.left, this.bottom - d)
        putJaw(Pocket.BOTTOM_CENTER, cx - m, this.bottom, cx + m, this.bottom)
        putJaw(Pocket.BOTTOM_RIGHT, this.right - d, this.bottom, this.right, this.bottom - d)
    }

    private fun putJaw(pocket: Pocket, x1: Double, y1: Double, x2: Double, y2: Double) {
        val i = pocket.ordinal * 4
        jaw[i] = x1
        jaw[i + 1] = y1
        jaw[i + 2] = x2
        jaw[i + 3] = y2
    }

    /**
     * Solve the pocket entry point seen from [ox], [oy]: the point on the mouth
     * line that leaves the most room to both jaw tips. Fills [targetX] and
     * [targetY]; [targetClearance] is the resulting jaw clearance in px.
     *
     * The clearance function is the perpendicular distance from a jaw tip to
     * the ball-centre line, so clearance >= [ballRadius] means the ball passes
     * the jaw without touching it.
     */
    fun gate(pocket: Pocket, ox: Double, oy: Double) {
        val i = pocket.ordinal * 4
        val ax = jaw[i]
        val ay = jaw[i + 1]
        val bx = jaw[i + 2]
        val by = jaw[i + 3]
        val dx = bx - ax
        val dy = by - ay

        if (abs(dx) < 1e-6 && abs(dy) < 1e-6) {
            targetX = ax
            targetY = ay
            targetClearance = 0.0
            return
        }

        // Coarse scan, then a golden-section refine around the best sample.
        var bestLam = 0.5
        var bestScore = -1.0
        val steps = 24
        for (s in 0..steps) {
            val lam = s.toDouble() / steps
            val score = clearance(ox, oy, ax + dx * lam, ay + dy * lam, ax, ay, bx, by)
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
            val s1 = clearance(ox, oy, ax + dx * m1, ay + dy * m1, ax, ay, bx, by)
            val s2 = clearance(ox, oy, ax + dx * m2, ay + dy * m2, ax, ay, bx, by)
            if (s2 > s1) lo = m1 else hi = m2
        }

        val lam = (lo + hi) * 0.5
        targetX = ax + dx * lam
        targetY = ay + dy * lam
        targetClearance = clearance(ox, oy, targetX, targetY, ax, ay, bx, by)

        if (targetClearance < 0.0) {
            targetX = (ax + bx) * 0.5
            targetY = (ay + by) * 0.5
            targetClearance = 0.0
        }
    }

    private fun clearance(
        ox: Double, oy: Double,
        tx: Double, ty: Double,
        ax: Double, ay: Double,
        bx: Double, by: Double
    ): Double {
        val vx = tx - ox
        val vy = ty - oy
        val len = hypot(vx, vy)
        if (len < 1e-3) return -1.0
        val d1 = abs(vx * (ay - oy) - vy * (ax - ox)) / len
        val d2 = abs(vx * (by - oy) - vy * (bx - ox)) / len
        return min(d1, d2)
    }

    /**
     * Bounce point of the ball centre off one cushion.
     *
     * [axis] selects the rail direction, [plane] is the ball-centre reflection
     * line (cushion nose offset by one radius), [side] is +1 when ball and
     * target sit on the larger-coordinate side of the plane and -1 otherwise.
     * [railMin]..[railMax] is the usable stretch of that rail in along-axis
     * coordinates. The pocket gate already in [targetX]/[targetY] is used as
     * the destination.
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
        val alongBall = if (axis == Axis.HORIZONTAL) bx else by
        val normBall = if (axis == Axis.HORIZONTAL) by else bx
        val alongPocket = if (axis == Axis.HORIZONTAL) pocketX else pocketY
        val normPocket = if (axis == Axis.HORIZONTAL) pocketY else pocketX

        val planeD = plane.toDouble()
        val nBall = side * normBall
        val nPocket = side * normPocket
        val c = side * planeD

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
                // f(0) = flat > 0, f(thetaMax) < 0, strictly decreasing: bisection.
                var lo = 0.0
                var hi = atan(flat / offset)
                repeat(48) {
                    val mid = (lo + hi) * 0.5
                    val f = flat - offset * tan(mid) - (nPocket - c) * tan(rebound * mid)
                    if (f > 0.0) lo = mid else hi = mid
                }
                hitAlong = alongBall + sigma * offset * tan((lo + hi) * 0.5)
            }
        }

        if (hitAlong < railMin || hitAlong > railMax) {
            hitValid = false
            return false
        }

        hitValid = true
        if (axis == Axis.HORIZONTAL) {
            hitX = hitAlong
            hitY = planeD
        } else {
            hitX = planeD
            hitY = hitAlong
        }
        return true
    }

    companion object {
        /** 8.06 cm jaw offset on a 127 cm short side. */
        const val CORNER_JAW_RATIO = 0.0635
        /** 6.35 cm half mouth on a 127 cm short side. */
        const val MIDDLE_HALF_RATIO = 0.0500
        /** How far the guide may run past the jaw line, as a fraction of the short side. */
        private const val MOUTH_DEPTH_RATIO = 0.0180

        private const val GOLDEN = 0.6180339887498949
    }
}
