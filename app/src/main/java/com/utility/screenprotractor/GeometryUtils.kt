package com.utility.screenprotractor

import android.graphics.PointF
import android.graphics.RectF
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Geometric calculation engine for ray-box intersections, vector reflections,
 * and 2D angular projections.
 */
object GeometryUtils {

    enum class BoundarySide {
        NONE, TOP, BOTTOM, LEFT, RIGHT
    }

    data class Intersection(
        val point: PointF,
        val side: BoundarySide,
        val distance: Float
    )

    data class ReflectionPath(
        val startPoint: PointF,
        val hitPoint: PointF,
        val hitSide: BoundarySide,
        val reflectedPoint: PointF,
        val angleOfIncidenceDeg: Float
    )

    /**
     * Compute Euclidean distance between two points.
     */
    fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x2 - x1
        val dy = y2 - y1
        return sqrt(dx * dx + dy * dy)
    }

    fun distance(p1: PointF, p2: PointF): Float {
        return distance(p1.x, p1.y, p2.x, p2.y)
    }

    /**
     * Calculate ray-box intersection from [origin] in direction of [directionPoint]
     * hitting the 4 edges of [box].
     *
     * @param origin Starting point of ray (Point A)
     * @param directionPoint Aiming point (Point B)
     * @param box Boundary rectangle
     * @param minDistance Minimum forward distance to avoid immediate self-collision
     * @return Intersection result with hit point, hit side, and distance, or null if no hit
     */
    fun computeRayBoxIntersection(
        origin: PointF,
        directionPoint: PointF,
        box: RectF,
        minDistance: Float = 0.5f
    ): Intersection? {
        val rawDx = directionPoint.x - origin.x
        val rawDy = directionPoint.y - origin.y
        val len = sqrt(rawDx * rawDx + rawDy * rawDy)

        if (len < 1e-5f) {
            return null
        }

        // Unit vector
        val ux = rawDx / len
        val uy = rawDy / len

        var closestT = Float.MAX_VALUE
        var hitSide = BoundarySide.NONE
        var hitX = 0f
        var hitY = 0f

        // 1. Check Horizontal Top Edge: y = box.top
        if (uy < -1e-5f) { // Moving upwards
            val t = (box.top - origin.y) / uy
            if (t > minDistance && t < closestT) {
                val x = origin.x + t * ux
                if (x in (box.left - 1f)..(box.right + 1f)) {
                    closestT = t
                    hitSide = BoundarySide.TOP
                    hitX = x.coerceIn(box.left, box.right)
                    hitY = box.top
                }
            }
        }

        // 2. Check Horizontal Bottom Edge: y = box.bottom
        if (uy > 1e-5f) { // Moving downwards
            val t = (box.bottom - origin.y) / uy
            if (t > minDistance && t < closestT) {
                val x = origin.x + t * ux
                if (x in (box.left - 1f)..(box.right + 1f)) {
                    closestT = t
                    hitSide = BoundarySide.BOTTOM
                    hitX = x.coerceIn(box.left, box.right)
                    hitY = box.bottom
                }
            }
        }

        // 3. Check Vertical Left Edge: x = box.left
        if (ux < -1e-5f) { // Moving leftwards
            val t = (box.left - origin.x) / ux
            if (t > minDistance && t < closestT) {
                val y = origin.y + t * uy
                if (y in (box.top - 1f)..(box.bottom + 1f)) {
                    closestT = t
                    hitSide = BoundarySide.LEFT
                    hitX = box.left
                    hitY = y.coerceIn(box.top, box.bottom)
                }
            }
        }

        // 4. Check Vertical Right Edge: x = box.right
        if (ux > 1e-5f) { // Moving rightwards
            val t = (box.right - origin.x) / ux
            if (t > minDistance && t < closestT) {
                val y = origin.y + t * uy
                if (y in (box.top - 1f)..(box.bottom + 1f)) {
                    closestT = t
                    hitSide = BoundarySide.RIGHT
                    hitX = box.right
                    hitY = y.coerceIn(box.top, box.bottom)
                }
            }
        }

        return if (hitSide != BoundarySide.NONE && closestT < Float.MAX_VALUE) {
            Intersection(PointF(hitX, hitY), hitSide, closestT)
        } else {
            null
        }
    }

    /**
     * Compute full reflection path:
     * 1. Primary ray from [pointA] towards [pointB] hitting [box] at [hitPoint].
     * 2. Reflected vector based on hit edge:
     *    - Horizontal (Top/Bottom): (dx', dy') = (dx, -dy)
     *    - Vertical (Left/Right): (dx', dy') = (-dx, dy)
     * 3. Reflected ray continues to the next boundary wall.
     */
    fun computeReflection(
        pointA: PointF,
        pointB: PointF,
        box: RectF
    ): ReflectionPath? {
        val firstHit = computeRayBoxIntersection(pointA, pointB, box) ?: return null

        val rawDx = pointB.x - pointA.x
        val rawDy = pointB.y - pointA.y
        val len = sqrt(rawDx * rawDx + rawDy * rawDy)
        if (len < 1e-5f) return null

        val ux = rawDx / len
        val uy = rawDy / len

        // Invert vector according to reflection surface normal
        val (refUx, refUy) = when (firstHit.side) {
            BoundarySide.TOP, BoundarySide.BOTTOM -> Pair(ux, -uy)
            BoundarySide.LEFT, BoundarySide.RIGHT -> Pair(-ux, uy)
            BoundarySide.NONE -> Pair(ux, uy)
        }

        // Calculate angle of incidence (with surface normal, between 0° and 90°)
        val normalX: Float
        val normalY: Float
        when (firstHit.side) {
            BoundarySide.TOP -> { normalX = 0f; normalY = 1f }
            BoundarySide.BOTTOM -> { normalX = 0f; normalY = -1f }
            BoundarySide.LEFT -> { normalX = 1f; normalY = 0f }
            BoundarySide.RIGHT -> { normalX = -1f; normalY = 0f }
            BoundarySide.NONE -> { normalX = 0f; normalY = 0f }
        }

        // Vector from hit point back towards incoming ray
        val incomingRevX = -ux
        val incomingRevY = -uy
        val dot = (incomingRevX * normalX + incomingRevY * normalY).coerceIn(-1f, 1f)
        val angleDeg = Math.toDegrees(acos(dot).toDouble()).toFloat()

        // Calculate bounce end point by intersecting reflected ray with next boundary wall
        val reflectedAimPoint = PointF(
            firstHit.point.x + refUx * 100f,
            firstHit.point.y + refUy * 100f
        )

        val secondHit = computeRayBoxIntersection(
            firstHit.point,
            reflectedAimPoint,
            box,
            minDistance = 2.0f
        )

        val bounceEnd = secondHit?.point ?: PointF(
            firstHit.point.x + refUx * 300f,
            firstHit.point.y + refUy * 300f
        )

        return ReflectionPath(
            startPoint = pointA,
            hitPoint = firstHit.point,
            hitSide = firstHit.side,
            reflectedPoint = bounceEnd,
            angleOfIncidenceDeg = angleDeg
        )
    }

    /**
     * Compute angle between two lines: (A -> B) and (B -> C) in degrees.
     */
    fun computeAngleBetweenLines(a: PointF, b: PointF, c: PointF): Float {
        val angle1 = atan2(a.y - b.y, a.x - b.x)
        val angle2 = atan2(c.y - b.y, c.x - b.x)
        var diff = Math.toDegrees((angle2 - angle1).toDouble()).toFloat()
        if (diff < 0) diff += 360f
        return diff
    }
}
