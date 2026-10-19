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
            putInt(KEY_CALIB_VERSION, CURRENT_CALIB_VERSION)
            apply()
        }
    }

    /**
     * Retrieve the calibrated boundary box. If not calibrated yet or saved by an older
     * version with cutout skew, computes default margins matching standard 8 Ball Pool table.
     */
    fun getBoundaryBox(screenWidth: Float, screenHeight: Float): RectF {
        val landW = kotlin.math.max(screenWidth, screenHeight)
        val landH = kotlin.math.min(screenWidth, screenHeight)

        val calibVersion = prefs.getInt(KEY_CALIB_VERSION, 0)
        val isCalibrated = prefs.getBoolean(KEY_BOUND_CALIBRATED, false)

        if (isCalibrated && calibVersion >= CURRENT_CALIB_VERSION) {
            val left = prefs.getFloat(KEY_BOUND_LEFT, landW * 0.191f)
            val top = prefs.getFloat(KEY_BOUND_TOP, landH * 0.228f)
            val right = prefs.getFloat(KEY_BOUND_RIGHT, landW * (1f - 0.191f))
            val bottom = prefs.getFloat(KEY_BOUND_BOTTOM, landH * 0.921f)

            // Sanity check to avoid inverted or collapsed rectangles and auto-clamp to screen
            val minMargin = 20f
            val clampedL = left.coerceIn(minMargin, landW * 0.45f)
            val clampedT = top.coerceIn(minMargin, landH * 0.45f)
            val clampedR = right.coerceIn(landW * 0.55f, landW - minMargin)
            val clampedB = bottom.coerceIn(landH * 0.55f, landH - minMargin)

            if (clampedR > clampedL + 50f && clampedB > clampedT + 50f) {
                return RectF(clampedL, clampedT, clampedR, clampedB)
            }
        }

        // If calibration was from an older version with cutout offset, reset it
        if (isCalibrated && calibVersion < CURRENT_CALIB_VERSION) {
            resetBoundaryBox()
        }

        // Default bounds: 8 Ball Pool table proportions (2:1 playing surface centered horizontally)
        val insetX = (landW * 0.191f).coerceAtLeast(40f)
        val topY = (landH * 0.228f).coerceAtLeast(40f)
        val botY = (landH * 0.921f).coerceAtLeast(topY + 100f)
        return RectF(insetX, topY, landW - insetX, botY)
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
            remove(KEY_CALIB_VERSION)
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
     * Save and retrieve line color (ARGB / Int).
     */
    fun saveLineColor(color: Int) {
        prefs.edit().putInt(KEY_LINE_COLOR, color).apply()
    }

    fun getLineColor(): Int {
        return prefs.getInt(KEY_LINE_COLOR, DEFAULT_LINE_COLOR)
    }

    /**
     * Save and retrieve mirror bank line color (ARGB / Int).
     */
    fun saveSecondaryRayColor(color: Int) {
        prefs.edit().putInt(KEY_BANK_LINE_COLOR, color).apply()
    }

    fun getSecondaryRayColor(): Int {
        return prefs.getInt(KEY_BANK_LINE_COLOR, DEFAULT_BANK_LINE_COLOR)
    }

    fun saveBankLineColor(color: Int) = saveSecondaryRayColor(color)
    fun getBankLineColor(): Int = getSecondaryRayColor()

    /**
     * Save and retrieve radial lines visibility toggle.
     */
    fun saveShowRadialGuides(show: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_SIX_LINES, show).apply()
    }

    fun getShowRadialGuides(): Boolean {
        return prefs.getBoolean(KEY_SHOW_SIX_LINES, true)
    }

    /**
     * Save and retrieve target ring radius.
     */
    fun saveRingRadius(radius: Float) {
        prefs.edit().putFloat(KEY_BALL_RADIUS, radius).apply()
    }

    fun getRingRadius(): Float {
        return prefs.getFloat(KEY_BALL_RADIUS, 13.5f)
    }

    /**
     * Save and retrieve full length guideline toggle (panjang garis sampai ke 6 lubang).
     */
    fun saveFullLengthGuides(isFull: Boolean) {
        prefs.edit().putBoolean(KEY_FULL_LENGTH_GUIDES, isFull).apply()
    }

    fun isFullLengthGuides(): Boolean {
        return prefs.getBoolean(KEY_FULL_LENGTH_GUIDES, false)
    }

    /**
     * Save and retrieve bank/reflection rays visibility toggle.
     */
    fun saveShowBankGuides(show: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_BANK_GUIDES, show).apply()
    }

    fun getShowBankGuides(): Boolean {
        return prefs.getBoolean(KEY_SHOW_BANK_GUIDES, true)
    }

    companion object {
        private const val PREFS_NAME = "screen_protractor_prefs"
        private const val DEFAULT_MARGIN_RATIO = 0.08f
        const val DEFAULT_LINE_COLOR = 0xFF00E5FF.toInt() // Cyan #00E5FF
        const val DEFAULT_BANK_LINE_COLOR = 0xFFFFAB00.toInt() // Amber/Gold #FFAB00

        private const val KEY_BOUND_LEFT = "bound_left"
        private const val KEY_BOUND_TOP = "bound_top"
        private const val KEY_BOUND_RIGHT = "bound_right"
        private const val KEY_BOUND_BOTTOM = "bound_bottom"
        private const val KEY_BOUND_CALIBRATED = "bound_calibrated"
        private const val KEY_CALIB_VERSION = "calib_version"
        private const val CURRENT_CALIB_VERSION = 3

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
        private const val KEY_LINE_COLOR = "line_color"
        private const val KEY_BANK_LINE_COLOR = "secondary_ray_color"

        private const val KEY_SHOW_SIX_LINES = "show_radial_guides"
        private const val KEY_SHOW_BANK_GUIDES = "show_bank_guides"
        private const val KEY_FULL_LENGTH_GUIDES = "full_length_guides"
        private const val KEY_BALL_RADIUS = "target_ring_radius"
    }
}
