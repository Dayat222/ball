package com.utility.toolkit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
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

    private fun paintStripedCircle(
        pixels: IntArray,
        w: Int,
        h: Int,
        cx: Float,
        cy: Float,
        radius: Float,
        stripeColor: Int
    ) {
        val r2 = radius * radius
        val white = rgb(245, 245, 245)
        for (y in 0 until h) {
            val dy = y - cy
            for (x in 0 until w) {
                val dx = x - cx
                if (dx * dx + dy * dy <= r2) {
                    pixels[y * w + x] = if (abs(dy) <= radius * 0.42f) stripeColor else white
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
            currentTargetX = 100f + 204f,
            currentTargetY = 50f + 84f,
            currentCueX = 100f + 90f,
            currentCueY = 50f + 82f
        )

        assertNotNull("Target ball should be detected", res.targetBall)
        assertNotNull("White cue ball should be detected", res.cueBall)

        val tb = res.targetBall!!
        val cb = res.cueBall!!

        assertTrue(
            "Target ball centroid accuracy (got ${tb.x}, ${tb.y})",
            hypot(tb.x - 310f, tb.y - 130f) < 1.8f
        )
        assertTrue(
            "Cue ball centroid accuracy (got ${cb.x}, ${cb.y})",
            hypot(cb.x - 195f, cb.y - 135f) < 1.8f
        )
        assertTrue("Cue ball must be flagged as cue candidate", cb.isCueCandidate)
    }

    @Test
    fun detectBallsHandlesStripedBallWithoutMisclassifyingAsCue() {
        val w = 320
        val h = 160
        val ballR = 14f
        val felt = rgb(20, 118, 65)
        val pixels = IntArray(w * h) { felt }

        // Striped ball #11 (red stripe + white caps) at (200, 80)
        paintStripedCircle(pixels, w, h, 200f, 80f, ballR, rgb(215, 35, 35))
        // True white cue ball at (80, 80)
        paintSolidCircle(pixels, w, h, 80f, 80f, ballR, rgb(245, 245, 245))

        val res = BallVisionDetector.detectBalls(
            pixels = pixels,
            width = w,
            height = h,
            tableLeft = 0f,
            tableTop = 0f,
            ballRadiusPx = ballR,
            currentTargetX = 203f,
            currentTargetY = 78f,
            currentCueX = 76f,
            currentCueY = 82f
        )

        assertNotNull("Striped target ball should be detected", res.targetBall)
        assertNotNull("Cue ball should be detected", res.cueBall)

        val tb = res.targetBall!!
        val cb = res.cueBall!!

        assertFalse("Striped ball must NOT be classified as cue ball", tb.isCueCandidate)
        assertTrue("True cue ball must be flagged as cue candidate", cb.isCueCandidate)
        assertTrue(
            "Striped ball centroid accuracy (got ${tb.x}, ${tb.y})",
            hypot(tb.x - 200f, tb.y - 80f) < 2.0f
        )
    }

    @Test
    fun detectBallsHandlesAdjacentTouchingCluster() {
        val w = 320
        val h = 160
        val ballR = 14f
        val felt = rgb(18, 108, 60)
        val pixels = IntArray(w * h) { felt }

        // Two adjacent balls nearly touching (2.08R separation)
        paintSolidCircle(pixels, w, h, 140f, 80f, ballR, rgb(230, 50, 40))
        paintSolidCircle(pixels, w, h, 140f + ballR * 2.08f, 80f, ballR, rgb(35, 105, 235))

        val res = BallVisionDetector.detectBalls(
            pixels = pixels,
            width = w,
            height = h,
            tableLeft = 0f,
            tableTop = 0f,
            ballRadiusPx = ballR,
            currentTargetX = 142f,
            currentTargetY = 81f
        )

        assertTrue("Both touching balls should be detected (found ${res.allBalls.size})", res.allBalls.size >= 2)
        assertNotNull("Target ball in cluster should be locked", res.targetBall)
        val tb = res.targetBall!!
        assertTrue(
            "Cluster ball accuracy (got ${tb.x}, ${tb.y})",
            hypot(tb.x - 140f, tb.y - 80f) < 2.0f
        )
    }

    @Test
    fun detectBallsHandlesToneOnToneGreenBallWithSpecularHighlight() {
        val w = 320
        val h = 160
        val ballR = 14f
        val felt = rgb(24, 128, 68)
        val pixels = IntArray(w * h) { felt }

        // Dark green #6 ball on green felt, with specular highlight and drop shadow rim
        paintSolidCircle(pixels, w, h, 180f, 80f, ballR + 1.5f, rgb(8, 45, 22))
        paintSolidCircle(pixels, w, h, 180f, 80f, ballR, rgb(22, 118, 60))
        paintSolidCircle(pixels, w, h, 176f, 75f, 4f, rgb(245, 250, 245))

        val res = BallVisionDetector.detectBalls(
            pixels = pixels,
            width = w,
            height = h,
            tableLeft = 0f,
            tableTop = 0f,
            ballRadiusPx = ballR,
            currentTargetX = 183f,
            currentTargetY = 82f
        )

        assertNotNull("Green tone-on-tone ball should be detected via highlight/shading", res.targetBall)
        val tb = res.targetBall!!
        assertTrue(
            "Tone-on-tone centroid accuracy (got ${tb.x}, ${tb.y})",
            hypot(tb.x - 180f, tb.y - 80f) < 2.5f
        )
    }
}
