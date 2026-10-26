package com.utility.toolkit

import android.graphics.PointF
import kotlin.math.sqrt

/**
 * Shared Euclidean helpers used by the overlay for hit-testing and alignment.
 *
 * The legacy ray-box reflection engine (A -> B -> wall) was removed together with
 * the unreachable Auto-Reflect / Manual 3-Point / Edge-Marker modes in v2.6.
 * All live aiming geometry (pocket rays and cushion bank rays) is computed
 * directly in [CanvasOverlayView] against the calibrated boundary box.
 */
object GeometryUtils {

    /**
     * Compute Euclidean distance between two coordinate pairs.
     */
    fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x2 - x1
        val dy = y2 - y1
        return sqrt(dx * dx + dy * dy)
    }

    fun distance(p1: PointF, p2: PointF): Float {
        return distance(p1.x, p1.y, p2.x, p2.y)
    }
}
