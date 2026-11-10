package com.utility.toolkit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/**
 * JVM tests for the pure-Kotlin table cloth estimator and radial circle detector
 * ([BallVisionDetector]).
 */
class BallVisionDetectorTest {

    private fun rgb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)

    private fun paintSolidCircle(
        pixels: IntArray,
        w: Int,
        h: Int,
        cx: Float,
        cy: Float,
        radius: Float,
        color: Int
    ) {
        val r2 = radius * radius
        for (y in 0 until h) {
            val dy = y - cy
            for (x in 0 until w) {
                val dx = x - cx
                if (dx * dx + dy * dy <= r2) {
                    pixels[y * w + x] = color
                }
            }
        }
    }

    @Test
    fun estimateClothColorRecoversDominantFeltRgb() {
        val w = 240
        val h = 120
        val felt = rgb(28, 134, 72)
        val pixels = IntArray(w * h) { felt }

        // Add two balls so the table isn't 100% uniform
        paintSolidCircle(pixels, w, h, 70f, 60f, 12f, rgb(240, 210, 20))
        paintSolidCircle(pixels, w, h, 170f, 65f, 12f, rgb(245, 245, 245))

        val est = BallVisionDetector.estimateClothColor(pixels, w, h)
        assertEquals(28, est[0])
        assertEquals(134, est[1])
        assertEquals(72, est[2])
    }

    @Test
    fun detectBallsLocksOntoTargetBallAndWhiteCueBall() {
        val w = 320
        val h = 160
        val ballR = 14f
        val felt = rgb(24, 128, 68)
        val pixels = IntArray(w * h) { felt }

        // Paint an object ball (solid yellow-orange) at (210, 80)
        paintSolidCircle(pixels, w, h, 210f, 80f, ballR, rgb(245, 190, 15))
        // Paint the white cue ball at (95, 85)
        paintSolidCircle(pixels, w, h, 95f, 85f, ballR, rgb(248, 248, 248))

        val res = BallVisionDetector.detectBalls(
            pixels = pixels,
            width = w,
            height = h,
            tableLeft = 100f,
            tableTop = 50f,
            ballRadiusPx = ballR,
            currentTargetX = 100f + 204f, // Slightly off-center from (210, 80)
            currentTargetY = 50f + 84f,
            currentCueX = 100f + 90f,
            currentCueY = 50f + 82f
        )

        assertNotNull("Target ball should be detected", res.targetBall)
        assertNotNull("White cue ball should be detected", res.cueBall)

        val tb = res.targetBall!!
        val cb = res.cueBall!!

        // Target ball should snap within 2.5 px of (100 + 210, 50 + 80) = (310, 130)
        assertTrue(
            "Target ball centroid accuracy (got ${tb.x}, ${tb.y})",
            hypot(tb.x - 310f, tb.y - 130f) < 2.5f
        )

        // Cue ball should snap within 2.5 px of (100 + 95, 50 + 85) = (195, 135)
        assertTrue(
            "Cue ball centroid accuracy (got ${cb.x}, ${cb.y})",
            hypot(cb.x - 195f, cb.y - 135f) < 2.5f
        )
        assertTrue("Cue ball must be flagged as cue candidate", cb.isCueCandidate)
    }
}
