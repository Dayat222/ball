package com.utility.toolkit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the millimetre-space aiming solver.
 *
 * The calibrated rectangle is treated as the real 9 ft playing surface, so a
 * boundary box of exactly 2540 x 1270 px maps 1 px == 1 mm and every expected
 * value below can be checked by hand.
 */
class AimGeometryTest {

    private fun table(scaleCorrection: Float = 1f): AimGeometry =
        AimGeometry().apply { configure(0f, 0f, 2540f, 1270f, scaleCorrection) }

    // ---------------------------------------------------------------- scale

    @Test
    fun ballRadiusIsDerivedFromTableScale() {
        val g = table()
        // 1 px per mm on both axes.
        assertEquals(1.0, g.ballRadiusMm / AimGeometry.BALL_RADIUS_MM, 1e-9)
        assertEquals(AimGeometry.BALL_RADIUS_MM, g.ballRadius, 1e-6)
    }

    @Test
    fun halfSizeCalibrationHalvesEveryPixelConstant() {
        val g = AimGeometry().apply { configure(0f, 0f, 1270f, 635f) }
        assertEquals(AimGeometry.BALL_RADIUS_MM * 0.5, g.ballRadius, 1e-6)
        assertEquals(AimGeometry.CORNER_JAW_MM * 0.5, g.cornerJawPx, 1e-6)
        assertEquals(AimGeometry.MIDDLE_HALF_MM * 0.5, g.middleHalfPx, 1e-6)
        // The ball radius must stay in mm, independent of the projection.
        assertEquals(AimGeometry.BALL_RADIUS_MM, g.ballRadiusMm, 1e-9)
    }

    @Test
    fun scaleCorrectionIsAppliedAndClamped() {
        // The correction arrives as a Float, so compare with a 1e-3 mm slack.
        val big = table(1.2f)
        assertEquals(AimGeometry.BALL_RADIUS_MM * 1.2, big.ballRadius, 1e-3)

        val clamped = table(5f)
        assertEquals(1.5, clamped.scaleCorrection, 1e-9)
        assertEquals(AimGeometry.BALL_RADIUS_MM * 1.5, clamped.ballRadius, 1e-3)
    }

    // ----------------------------------------------------------------- gate

    @Test
    fun centreShotToSidePocketIsSymmetric() {
        val g = table()
        val cx = AimGeometry.TABLE_LENGTH_MM * 0.5
        val cy = AimGeometry.TABLE_WIDTH_MM * 0.5

        g.gate(AimGeometry.Pocket.TOP_CENTER, cx, cy)
        assertEquals(cx, g.targetX, 0.5)
        assertEquals(0.0, g.targetY, 0.01)
        assertTrue(g.gateFeasible)

        g.gate(AimGeometry.Pocket.BOTTOM_CENTER, cx, cy)
        assertEquals(cx, g.targetX, 0.5)
        assertEquals(AimGeometry.TABLE_WIDTH_MM, g.targetY, 0.01)
        assertTrue(g.gateFeasible)
    }

    @Test
    fun straightSidePocketSeesFullAperture() {
        val g = table()
        val cx = AimGeometry.TABLE_LENGTH_MM * 0.5
        g.gate(AimGeometry.Pocket.TOP_CENTER, cx, AimGeometry.TABLE_WIDTH_MM * 0.5)

        // Square-on approach: aperture equals the whole mouth width.
        assertEquals(2.0 * AimGeometry.MIDDLE_HALF_MM, g.gateApertureMm, 0.01)
        // Clearance to the nearest jaw tip is half the mouth.
        assertEquals(AimGeometry.MIDDLE_HALF_MM, g.targetClearance, 0.5)
    }

    @Test
    fun cornerPocketFromCentreIsFeasible() {
        val g = table()
        g.gate(
            AimGeometry.Pocket.TOP_LEFT,
            AimGeometry.TABLE_LENGTH_MM * 0.5,
            AimGeometry.TABLE_WIDTH_MM * 0.5
        )
        assertTrue(g.gateFeasible)
        assertTrue(g.gateApertureMm >= 2.0 * AimGeometry.BALL_RADIUS_MM)
        assertTrue(g.targetClearance >= AimGeometry.BALL_RADIUS_MM)
    }

    @Test
    fun grazingApproachIsRejectedByEntryAngle() {
        val g = table()
        // Almost parallel to the top rail: the mouth presents a sliver.
        g.gate(AimGeometry.Pocket.TOP_CENTER, 100.0, 2.0)

        assertFalse(g.gateFeasible)
        assertTrue(g.gateApertureMm < 2.0 * AimGeometry.BALL_RADIUS_MM)
    }

    @Test
    fun originBehindTheMouthLineIsRejected() {
        val g = table()
        // Outside the table, level with the top rail: the "inside" test fails.
        g.gate(AimGeometry.Pocket.TOP_CENTER, AimGeometry.TABLE_LENGTH_MM * 0.5, -200.0)
        assertFalse(g.gateFeasible)
    }

    // ----------------------------------------------------------------- bank

    @Test
    fun bankOnEvenRailIsSpecularForUnitRebound() {
        val g = table()
        val plane = (AimGeometry.TABLE_WIDTH_MM - AimGeometry.BALL_RADIUS_MM).toFloat()

        val ok = g.bankFrom(
            bx = 1000.0, by = 400.0,
            pocketX = 2000.0, pocketY = 400.0,
            axis = AimGeometry.Axis.HORIZONTAL,
            plane = plane,
            side = -1,
            railMin = 0.0,
            railMax = AimGeometry.TABLE_LENGTH_MM
        )

        assertTrue(ok)
        // Equal angles: incoming x-travel == outgoing x-travel.
        assertEquals(1500.0, g.hitX, 1e-3)
        assertEquals(plane.toDouble(), g.hitY, 1e-6)
    }

    @Test
    fun reducedReboundMovesTheBounceFurtherAlongTheRail() {
        val g = table()
        val plane = (AimGeometry.TABLE_WIDTH_MM - AimGeometry.BALL_RADIUS_MM).toFloat()

        g.bankFrom(1000.0, 400.0, 2000.0, 400.0, AimGeometry.Axis.HORIZONTAL, plane, -1, 0.0, 2540.0)
        val specular = g.hitX

        g.rebound = 0.9
        assertTrue(
            g.bankFrom(1000.0, 400.0, 2000.0, 400.0, AimGeometry.Axis.HORIZONTAL, plane, -1, 0.0, 2540.0)
        )
        assertTrue(g.hitX > specular + 1.0)
    }

    @Test
    fun bounceOutsideTheUsableRailIsRejected() {
        val g = table()
        val plane = (AimGeometry.TABLE_WIDTH_MM - AimGeometry.BALL_RADIUS_MM).toFloat()

        val ok = g.bankFrom(
            1000.0, 400.0, 2000.0, 400.0,
            AimGeometry.Axis.HORIZONTAL, plane, -1,
            railMin = 0.0, railMax = 1200.0
        )

        assertFalse(ok)
        assertFalse(g.hitValid)
    }

    @Test
    fun degenerateBoundaryBoxDoesNotThrow() {
        val g = AimGeometry()
        g.configure(0f, 0f, 3f, 3f)
        assertEquals(0.0, g.ballRadius, 0.0)

        // Jaw tips were never built for this box; the solver must bail out.
        g.gate(AimGeometry.Pocket.TOP_LEFT, 1.0, 1.0)
        assertFalse(g.gateFeasible)
    }
}