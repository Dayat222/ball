package com.utility.screenprotractor

import android.content.Context
import android.content.SharedPreferences
import android.graphics.PointF
import android.graphics.RectF

/**
 * Storage manager for persisting ruler calibration coordinates, point positions,
 * and user preferences using Android SharedPreferences.
 */
class PrefsManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    /**
     * Save the 4 calibrated boundary edges.
     */
    fun saveBoundaryBox(left: Float, top: Float, right: Float, bottom: Float) {
        prefs.edit().apply {
            putFloat(KEY_BOUND_LEFT, left)
            putFloat(KEY_BOUND_TOP, top)
            putFloat(KEY_BOUND_RIGHT, right)
            putFloat(KEY_BOUND_BOTTOM, bottom)
            putBoolean(KEY_BOUND_CALIBRATED, true)
            apply()
        }
    }

    /**
     * Retrieve the calibrated boundary box. If not calibrated yet, computes default
     * margins based on screen dimensions.
     */
    fun getBoundaryBox(screenWidth: Float, screenHeight: Float): RectF {
        val isCalibrated = prefs.getBoolean(KEY_BOUND_CALIBRATED, false)
        if (isCalibrated) {
            val left = prefs.getFloat(KEY_BOUND_LEFT, screenWidth * DEFAULT_MARGIN_RATIO)
            val top = prefs.getFloat(KEY_BOUND_TOP, screenHeight * DEFAULT_MARGIN_RATIO)
            val right = prefs.getFloat(KEY_BOUND_RIGHT, screenWidth * (1f - DEFAULT_MARGIN_RATIO))
            val bottom = prefs.getFloat(KEY_BOUND_BOTTOM, screenHeight * (1f - DEFAULT_MARGIN_RATIO))

            // Sanity check to avoid inverted or collapsed rectangles
            if (right > left + 50f && bottom > top + 50f) {
                return RectF(left, top, right, bottom)
            }
        }

        // Default bounds: 8% inset from screen margins
        val insetX = (screenWidth * DEFAULT_MARGIN_RATIO).coerceAtLeast(40f)
        val insetY = (screenHeight * DEFAULT_MARGIN_RATIO).coerceAtLeast(80f)
        return RectF(insetX, insetY, screenWidth - insetX, screenHeight - insetY)
    }

    /**
     * Reset boundary box calibration to default margins.
     */
    fun resetBoundaryBox() {
        prefs.edit().apply {
            remove(KEY_BOUND_LEFT)
            remove(KEY_BOUND_TOP)
            remove(KEY_BOUND_RIGHT)
            remove(KEY_BOUND_BOTTOM)
            remove(KEY_BOUND_CALIBRATED)
            apply()
        }
    }

    /**
     * Save the last active ruler mode index.
     */
    fun saveMode(modeOrdinal: Int) {
        prefs.edit().putInt(KEY_ACTIVE_MODE, modeOrdinal).apply()
    }

    /**
     * Retrieve the last active ruler mode index.
     */
    fun getMode(): Int {
        return prefs.getInt(KEY_ACTIVE_MODE, 0)
    }

    /**
     * Save last handle positions to persist between overlay launches.
     */
    fun savePoints(pointA: PointF, pointB: PointF, pointC: PointF) {
        prefs.edit().apply {
            putFloat(KEY_PT_A_X, pointA.x)
            putFloat(KEY_PT_A_Y, pointA.y)
            putFloat(KEY_PT_B_X, pointB.x)
            putFloat(KEY_PT_B_Y, pointB.y)
            putFloat(KEY_PT_C_X, pointC.x)
            putFloat(KEY_PT_C_Y, pointC.y)
            putBoolean(KEY_POINTS_INITIALIZED, true)
            apply()
        }
    }

    /**
     * Retrieve last handle positions, or initialize to reasonable defaults inside screen.
     */
    fun getPoints(screenWidth: Float, screenHeight: Float): Triple<PointF, PointF, PointF> {
        val hasPoints = prefs.getBoolean(KEY_POINTS_INITIALIZED, false)
        if (hasPoints) {
            val ax = prefs.getFloat(KEY_PT_A_X, screenWidth * 0.5f)
            val ay = prefs.getFloat(KEY_PT_A_Y, screenHeight * 0.7f)
            val bx = prefs.getFloat(KEY_PT_B_X, screenWidth * 0.5f)
            val by = prefs.getFloat(KEY_PT_B_Y, screenHeight * 0.4f)
            val cx = prefs.getFloat(KEY_PT_C_X, screenWidth * 0.8f)
            val cy = prefs.getFloat(KEY_PT_C_Y, screenHeight * 0.4f)
            return Triple(PointF(ax, ay), PointF(bx, by), PointF(cx, cy))
        }

        // Sensible default positions
        val defaultA = PointF(screenWidth * 0.5f, screenHeight * 0.65f)
        val defaultB = PointF(screenWidth * 0.5f, screenHeight * 0.35f)
        val defaultC = PointF(screenWidth * 0.75f, screenHeight * 0.35f)
        return Triple(defaultA, defaultB, defaultC)
    }

    /**
     * Save line stroke width and opacity alpha.
     */
    fun saveLineStyle(strokeWidth: Float, alpha: Int) {
        prefs.edit().apply {
            putFloat(KEY_LINE_STROKE_WIDTH, strokeWidth)
            putInt(KEY_LINE_ALPHA, alpha)
            apply()
        }
    }

    fun getLineStrokeWidth(): Float {
        return prefs.getFloat(KEY_LINE_STROKE_WIDTH, 3.5f)
    }

    fun getLineAlpha(): Int {
        return prefs.getInt(KEY_LINE_ALPHA, 220)
    }

    /**
     * Save and retrieve 6-Pocket Radial lines visibility toggle.
     */
    fun saveShowSixLines(show: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_SIX_LINES, show).apply()
    }

    fun getShowSixLines(): Boolean {
        return prefs.getBoolean(KEY_SHOW_SIX_LINES, true)
    }

    /**
     * Save and retrieve ball radius.
     */
    fun saveBallRadius(radius: Float) {
        prefs.edit().putFloat(KEY_BALL_RADIUS, radius).apply()
    }

    fun getBallRadius(): Float {
        return prefs.getFloat(KEY_BALL_RADIUS, 28f)
    }

    companion object {
        private const val PREFS_NAME = "screen_protractor_prefs"
        private const val DEFAULT_MARGIN_RATIO = 0.08f

        private const val KEY_BOUND_LEFT = "bound_left"
        private const val KEY_BOUND_TOP = "bound_top"
        private const val KEY_BOUND_RIGHT = "bound_right"
        private const val KEY_BOUND_BOTTOM = "bound_bottom"
        private const val KEY_BOUND_CALIBRATED = "bound_calibrated"

        private const val KEY_ACTIVE_MODE = "active_mode"

        private const val KEY_PT_A_X = "pt_a_x"
        private const val KEY_PT_A_Y = "pt_a_y"
        private const val KEY_PT_B_X = "pt_b_x"
        private const val KEY_PT_B_Y = "pt_b_y"
        private const val KEY_PT_C_X = "pt_c_x"
        private const val KEY_PT_C_Y = "pt_c_y"
        private const val KEY_POINTS_INITIALIZED = "points_initialized"

        private const val KEY_LINE_STROKE_WIDTH = "line_stroke_width"
        private const val KEY_LINE_ALPHA = "line_alpha"

        private const val KEY_SHOW_SIX_LINES = "show_six_lines"
        private const val KEY_BALL_RADIUS = "ball_radius"
    }
}
