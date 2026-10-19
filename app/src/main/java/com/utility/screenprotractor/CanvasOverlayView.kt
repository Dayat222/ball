package com.utility.screenprotractor

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * High-performance full-screen transparent canvas rendering:
 * 1. Primary & Reflected geometry lines.
 * 2. 6-Pocket Radial Short Lines from target ball (Point B).
 * 3. Connecting stem/tether lines from offset finger grips to actual aim coordinates.
 * 4. Interactive 8-handle boundary box calibration (auto-clamped for Landscape & Portrait,
 *    and completely hidden when not calibrating for clean view).
 */
class CanvasOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class RulerMode {
        AUTO_REFLECT,
        MANUAL_3_POINT,
        EDGE_MARKER_ONLY
    }

    private enum class DragTarget {
        NONE,
        CORNER_TOP_LEFT, CORNER_TOP_RIGHT, CORNER_BOTTOM_LEFT, CORNER_BOTTOM_RIGHT,
        HANDLE_TOP_CENTER, HANDLE_BOTTOM_CENTER, HANDLE_LEFT_CENTER, HANDLE_RIGHT_CENTER
    }

    private val prefsManager = PrefsManager(context)

    // Current Mode & Calibration
    var currentMode: RulerMode = RulerMode.AUTO_REFLECT
        private set
    var isCalibrating: Boolean = false
        private set

    // Feature 1: Radial Lines & Target Ring Radius
    var showRadialGuides: Boolean = true
        private set

    var showBankGuides: Boolean = true
        private set

    var isFullLengthGuides: Boolean = false
        private set

    var ringRadiusDp: Float = 13.5f
        private set

    // Window Offset for Table-Bounded Window Cropping
    private var windowOffsetX: Float = 0f
    private var windowOffsetY: Float = 0f

    fun setWindowOffset(offsetX: Float, offsetY: Float) {
        windowOffsetX = offsetX
        windowOffsetY = offsetY
        invalidate()
    }

    /**
     * Helper to obtain landscape full-screen dimensions regardless of device/activity orientation.
     */
    fun getLandscapeScreenSize(): PointF {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val rawW: Int
        val rawH: Int
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            rawW = bounds.width()
            rawH = bounds.height()
        } else {
            val pt = Point()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealSize(pt)
            rawW = pt.x
            rawH = pt.y
        }
        val landW = kotlin.math.max(rawW, rawH).toFloat()
        val landH = kotlin.math.min(rawW, rawH).toFloat()
        return PointF(landW, landH)
    }

    // Actual Aim Coordinates (where rays originate and reflect)
    var pointA = PointF()
        private set
    var pointB = PointF()
        private set
    var pointC = PointF()
        private set

    // Finger Grip Coordinates (HandleView touch pad positions)
    private var gripA = PointF()
    private var gripB = PointF()
    private var gripC = PointF()
    private var hasGripA = false
    private var hasGripB = false
    private var hasGripC = false

    var boundaryBox = RectF()
        private set

    // Touch Dragging State for Calibration
    private var activeDragTarget = DragTarget.NONE
    private var touchOffset = PointF(0f, 0f)

    // Density scaled dimensions
    private val density = resources.displayMetrics.density
    private val edgeMarkerRadius = 8f * density
    private val edgeHandleRadius = 14f * density

    // Paint: Primary Line (Point A -> Point B / Hit Point)
    private val primaryLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        style = Paint.Style.STROKE
        strokeWidth = 3.5f * density
        pathEffect = DashPathEffect(floatArrayOf(15f * density, 10f * density), 0f)
    }

    // Paint: Reflected Line (Hit Point -> Reflected End / Point B -> C)
    private val reflectedLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        style = Paint.Style.STROKE
        strokeWidth = 3.5f * density
        pathEffect = DashPathEffect(floatArrayOf(15f * density, 10f * density), 0f)
    }

    // Paint: 6-Pocket Radial Short Lines from Target Ball (sixLinePaint)
    private val sixLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        style = Paint.Style.STROKE
        strokeWidth = 3.5f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val pocketLinePaint get() = sixLinePaint

    // Paint: 6 Mirror Bank Rays from Target Ball (bankLinePaint) - Dashed styling
    private val bankLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFAB00")
        style = Paint.Style.STROKE
        strokeWidth = 3.5f * density
        strokeCap = Paint.Cap.ROUND
        pathEffect = DashPathEffect(floatArrayOf(10f * density, 7f * density), 0f)
    }

    private val bankRayPath = Path()

    private val bankDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFAB00")
        style = Paint.Style.FILL
    }

    // Active line styling state
    var currentLineColor: Int = PrefsManager.DEFAULT_LINE_COLOR
        private set
    var currentBankLineColor: Int = PrefsManager.DEFAULT_BANK_LINE_COLOR
        private set
    var currentAlpha: Int = 220
        private set
    var currentStrokeWidth: Float = 3.5f
        private set

    // Paint: Connecting Stem/Tether Line between Grip Pad and Aim Point
    private val stemLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#99FFFFFF")
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        pathEffect = DashPathEffect(floatArrayOf(6f * density, 6f * density), 0f)
    }

    private val aimDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    // Paints: High-Contrast Hollow Bullseye & Ball-Fit Ring for Point B
    private val aimShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb((220 * 0.85f).roundToInt(), 0, 0, 0)
        strokeWidth = 2.8f * density
    }

    private val aimRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(220, 255, 255, 255)
        strokeWidth = 1.4f * density
    }

    private val aimCenterShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb((220 * 0.85f).roundToInt(), 0, 0, 0)
    }

    private val aimCenterDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(220, 255, 255, 255)
    }

    // Paint: Boundary Box Active Calibration Highlight
    private val boundaryCalibPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFE082")
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
    }

    // Feature 1: 6 Vertex Calibration Rings (Vertex Guides in Calibration Mode)
    private val vertexRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }

    private val vertexCrossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        strokeCap = Paint.Cap.ROUND
    }

    // Paint: 8 Handles in Calibration Mode
    private val handleOuterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E6FFE082")
        style = Paint.Style.FILL
    }

    private val handleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFB300")
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }

    private val handleCenterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    // Paint: Edge-Marker Dot (Mode 3)
    private val edgeMarkerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFD600")
        style = Paint.Style.FILL
    }

    private val edgeMarkerStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }

    // Paint: Text Badges (Labels & Angle Info)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 13f * density
    }

    private val textBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#B3000000")
        style = Paint.Style.FILL
    }

    private val linePath = Path()
    private val textBounds = Rect()
    private val badgeRect = RectF()
    private val innerInsetBox = RectF()

    var onCanvasSizeChanged: ((w: Int, h: Int) -> Unit)? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
    }

    init {
        val landSize = getLandscapeScreenSize()
        boundaryBox = prefsManager.getBoundaryBox(landSize.x, landSize.y)
        clampBoundaryBox(landSize.x, landSize.y)

        val (savedA, savedB, savedC) = prefsManager.getPoints(landSize.x, landSize.y)
        pointA = savedA
        pointB = savedB
        pointC = savedC

        val savedModeOrdinal = prefsManager.getMode()
        if (savedModeOrdinal in RulerMode.values().indices) {
            currentMode = RulerMode.values()[savedModeOrdinal]
        }

        currentLineColor = prefsManager.getLineColor()
        currentBankLineColor = prefsManager.getSecondaryRayColor()
        currentAlpha = prefsManager.getLineAlpha()
        currentStrokeWidth = prefsManager.getLineStrokeWidth()
        updateLineColor(currentLineColor)
        updateBankLineColor(currentBankLineColor)
        updateLineStyle(currentStrokeWidth, currentAlpha)

        showRadialGuides = prefsManager.getShowRadialGuides()
        showBankGuides = prefsManager.getShowBankGuides()
        isFullLengthGuides = prefsManager.isFullLengthGuides()
        ringRadiusDp = prefsManager.getRingRadius()
    }

    /**
     * Requirement 2: Auto-clamp boundaryBox to prevent overflowing screen edges in Landscape mode.
     */
    fun clampBoundaryBox(w: Float, h: Float) {
        if (w <= 0 || h <= 0) return
        val minMargin = 20f * density
        val minWidth = 80f * density
        val minHeight = 80f * density

        var l = boundaryBox.left.coerceIn(minMargin, (w * 0.45f).coerceAtLeast(minMargin))
        var t = boundaryBox.top.coerceIn(minMargin, (h * 0.45f).coerceAtLeast(minMargin))
        var r = boundaryBox.right.coerceIn((w * 0.55f).coerceAtMost(w - minMargin), w - minMargin)
        var b = boundaryBox.bottom.coerceIn((h * 0.55f).coerceAtMost(h - minMargin), h - minMargin)

        if (r <= l + minWidth) {
            l = minMargin
            r = w - minMargin
        }
        if (b <= t + minHeight) {
            t = minMargin
            b = h - minMargin
        }
        boundaryBox.set(l, t, r, b)
    }

    /**
     * Requirement 2: Reset boundary box to proportional center rectangle.
     */
    fun resetToProportionalCenter() {
        val landSize = getLandscapeScreenSize()
        val w = landSize.x.coerceAtLeast(200f)
        val h = landSize.y.coerceAtLeast(200f)
        val l = w * 0.191f
        val t = h * 0.228f
        val r = w * (1f - 0.191f)
        val b = h * 0.921f
        boundaryBox.set(l, t, r, b)
        prefsManager.saveBoundaryBox(l, t, r, b)
        invalidate()
    }

    /**
     * Requirement 2: Dynamically adjust line color across primary, reflected, and 6-pocket lines,
     * preserving currently active opacity alpha.
     */
    fun updateLineColor(color: Int) {
        currentLineColor = color
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        val argb = Color.argb(currentAlpha, r, g, b)

        primaryLinePaint.color = argb
        reflectedLinePaint.color = argb
        sixLinePaint.color = argb

        invalidate()
    }

    /**
     * Adjust contrasting color for 6 Mirror Bank rays, preserving currently active alpha.
     */
    fun updateBankLineColor(color: Int) {
        currentBankLineColor = color
        val br = Color.red(color)
        val bg = Color.green(color)
        val bb = Color.blue(color)
        val bankArgb = Color.argb(currentAlpha, br, bg, bb)

        bankLinePaint.color = bankArgb
        bankDotPaint.color = bankArgb

        invalidate()
    }

    /**
     * Dynamically adjust line thickness and opacity.
     */
    fun updateLineStyle(strokeWidth: Float, alpha: Int) {
        currentAlpha = alpha.coerceIn(25, 255)
        currentStrokeWidth = strokeWidth
        val strokePx = strokeWidth * density

        primaryLinePaint.strokeWidth = strokePx
        reflectedLinePaint.strokeWidth = strokePx
        sixLinePaint.strokeWidth = strokePx
        bankLinePaint.strokeWidth = strokePx

        val r = Color.red(currentLineColor)
        val g = Color.green(currentLineColor)
        val b = Color.blue(currentLineColor)
        val argb = Color.argb(currentAlpha, r, g, b)

        primaryLinePaint.color = argb
        reflectedLinePaint.color = argb
        sixLinePaint.color = argb

        val br = Color.red(currentBankLineColor)
        val bg = Color.green(currentBankLineColor)
        val bb = Color.blue(currentBankLineColor)
        val bankArgb = Color.argb(currentAlpha, br, bg, bb)

        bankLinePaint.color = bankArgb
        bankDotPaint.color = bankArgb
        bankLinePaint.pathEffect = DashPathEffect(floatArrayOf(10f * density, 7f * density), 0f)

        // Synchronize stem/tether line and center aim dot alpha
        val stemAlpha = (currentAlpha * 0.7f).roundToInt().coerceIn(20, 255)
        stemLinePaint.color = Color.argb(stemAlpha, 255, 255, 255)
        aimDotPaint.color = Color.argb(currentAlpha, 255, 255, 255)

        val shadowAlpha = (currentAlpha * 0.85f).roundToInt().coerceIn(20, 255)
        aimShadowPaint.color = Color.argb(shadowAlpha, 0, 0, 0)
        aimCenterShadowPaint.color = Color.argb(shadowAlpha, 0, 0, 0)
        aimRingPaint.color = Color.argb(currentAlpha, 255, 255, 255)
        aimCenterDotPaint.color = Color.argb(currentAlpha, 255, 255, 255)

        invalidate()
    }

    fun setShowRadialGuides(show: Boolean) {
        showRadialGuides = show
        prefsManager.saveShowRadialGuides(show)
        invalidate()
    }

    fun toggleRadialGuides(): Boolean {
        setShowRadialGuides(!showRadialGuides)
        return showRadialGuides
    }

    fun setShowBankGuides(show: Boolean) {
        showBankGuides = show
        prefsManager.saveShowBankGuides(show)
        invalidate()
    }

    fun toggleBankGuides(): Boolean {
        setShowBankGuides(!showBankGuides)
        return showBankGuides
    }

    fun setFullLengthGuides(isFull: Boolean) {
        isFullLengthGuides = isFull
        prefsManager.saveFullLengthGuides(isFull)
        invalidate()
    }

    fun toggleFullLengthGuides(): Boolean {
        setFullLengthGuides(!isFullLengthGuides)
        return isFullLengthGuides
    }

    fun setRingRadius(radius: Float) {
        ringRadiusDp = radius
        prefsManager.saveRingRadius(radius)
        invalidate()
    }

    fun setPointA(aimX: Float, aimY: Float, gripX: Float = aimX, gripY: Float = aimY) {
        pointA.set(aimX, aimY)
        gripA.set(gripX, gripY)
        hasGripA = (GeometryUtils.distance(aimX, aimY, gripX, gripY) > 5f)
        invalidate()
    }

    fun setPointB(aimX: Float, aimY: Float, gripX: Float = aimX, gripY: Float = aimY) {
        pointB.set(aimX, aimY)
        gripB.set(gripX, gripY)
        hasGripB = (GeometryUtils.distance(aimX, aimY, gripX, gripY) > 5f)
        invalidate()
    }

    fun setPointC(aimX: Float, aimY: Float, gripX: Float = aimX, gripY: Float = aimY) {
        pointC.set(aimX, aimY)
        gripC.set(gripX, gripY)
        hasGripC = (GeometryUtils.distance(aimX, aimY, gripX, gripY) > 5f)
        invalidate()
    }

    fun setRulerMode(mode: RulerMode) {
        currentMode = mode
        prefsManager.saveMode(mode.ordinal)
        invalidate()
    }

    fun cycleRulerMode(): RulerMode {
        val nextOrdinal = (currentMode.ordinal + 1) % RulerMode.values().size
        val nextMode = RulerMode.values()[nextOrdinal]
        setRulerMode(nextMode)
        return nextMode
    }

    fun setCalibrating(calibrating: Boolean) {
        isCalibrating = calibrating
        if (!calibrating) {
            prefsManager.saveBoundaryBox(boundaryBox.left, boundaryBox.top, boundaryBox.right, boundaryBox.bottom)
            activeDragTarget = DragTarget.NONE
        }
        invalidate()
    }

    fun resetBoundaryCalibration() {
        val landSize = getLandscapeScreenSize()
        val screenW = landSize.x.coerceAtLeast(100f)
        val screenH = landSize.y.coerceAtLeast(100f)
        prefsManager.resetBoundaryBox()
        boundaryBox = prefsManager.getBoundaryBox(screenW, screenH)
        clampBoundaryBox(screenW, screenH)
        invalidate()
    }

    override fun onConfigurationChanged(newConfig: Configuration?) {
        super.onConfigurationChanged(newConfig)
        val landSize = getLandscapeScreenSize()
        val screenW = landSize.x.coerceAtLeast(100f)
        val screenH = landSize.y.coerceAtLeast(100f)
        boundaryBox = prefsManager.getBoundaryBox(screenW, screenH)
        clampBoundaryBox(screenW, screenH)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val saveCount = canvas.save()
        if (!isCalibrating) {
            canvas.translate(-windowOffsetX, -windowOffsetY)
        }

        // Requirement 1: Clean View. ONLY draw boundary box and handles when isCalibrating == true
        if (isCalibrating) {
            drawBoundaryBox(canvas)
            drawEightCalibrationHandles(canvas)
        }

        if (!isCalibrating) {
            if (showRadialGuides) {
                // Requirement 2: Mode 1-Titik Fokus (Single-Point Target Mode)
                // Hide connecting line A-B and reflection rays.
                // Only render Point B's stem tether (from grip pad to Point B)
                if (hasGripB) {
                    canvas.drawLine(gripB.x, gripB.y, pointB.x, pointB.y, stemLinePaint)
                }
                // Render Point B's white center dot + 6 Direct rays + 6 Mirror Bank rays
                drawTargetAndRadialGuides(canvas)
            } else {
                // Normal modes (Auto-Reflect, Manual 3-Pt, Edge-Marker)
                drawStemTethers(canvas)
                when (currentMode) {
                    RulerMode.AUTO_REFLECT -> drawAutoReflection(canvas)
                    RulerMode.MANUAL_3_POINT -> drawManualThreePoint(canvas)
                    RulerMode.EDGE_MARKER_ONLY -> drawEdgeMarkerOnly(canvas)
                }
            }
        }

        canvas.restoreToCount(saveCount)
    }

    /**
     * Visual connection between finger Grip Pad and the actual Aim Point.
     */
    private fun drawStemTethers(canvas: Canvas) {
        if (hasGripA) {
            canvas.drawLine(gripA.x, gripA.y, pointA.x, pointA.y, stemLinePaint)
            canvas.drawCircle(pointA.x, pointA.y, 4f * density, aimDotPaint)
        }
        if (hasGripB) {
            canvas.drawLine(gripB.x, gripB.y, pointB.x, pointB.y, stemLinePaint)
        }
        if (hasGripC && currentMode == RulerMode.MANUAL_3_POINT) {
            canvas.drawLine(gripC.x, gripC.y, pointC.x, pointC.y, stemLinePaint)
            canvas.drawCircle(pointC.x, pointC.y, 4f * density, aimDotPaint)
        }
    }

    /**
     * Zero-allocation helper to draw a radial ray from (bx, by) toward target (tx, ty).
     */
    private fun drawRadialRay(
        canvas: Canvas,
        bx: Float,
        by: Float,
        tx: Float,
        ty: Float,
        startDist: Float,
        endDist: Float,
        paint: Paint
    ) {
        val dx = tx - bx
        val dy = ty - by
        val dist = hypot(dx, dy)
        if (dist > 1f) {
            val ux = dx / dist
            val uy = dy / dist
            val startX = bx + ux * startDist
            val startY = by + uy * startDist
            val endX = bx + ux * endDist
            val endY = by + uy * endDist
            canvas.drawLine(startX, startY, endX, endY, paint)
        }
    }

    /**
     * Precision Lateral Vertex Ray (Top & Bottom Center Gate Clearance).
     * Accounts for approach tilt angle (kemiringan tembakan) across all cut angles.
     * In pool physics, as the shot becomes more angled (tiltRatio = |dx| / dist),
     * the near cushion knuckle physically obstructs the direct line of sight to the pocket center.
     * To prevent the ball from colliding with the near cushion knuckle and rattling out,
     * the entry target along the rail line MUST dynamically shift AWAY from the near knuckle
     * (towards the pocket opening center / far facing) based on ball radius and approach inclination.
     * Zero-allocation primitive Float calculations.
     */
    private fun drawLateralVertexRay(
        canvas: Canvas,
        bx: Float,
        by: Float,
        cx: Float,
        railY: Float,
        isTopGate: Boolean,
        startDist: Float,
        endDist: Float,
        paint: Paint
    ) {
        val dx = bx - cx
        val dy = if (isTopGate) (by - railY).coerceAtLeast(1f) else (railY - by).coerceAtLeast(1f)
        val dist = hypot(dx, dy)

        val ballR = ringRadiusDp * density
        val maxShift = (ballR * 0.60f).coerceIn(6f * density, 9f * density)

        // Tilt ratio ranges from 0.0 (straight-in) to 1.0 (parallel cut along cushion)
        val tiltRatio = if (dist > 1f) (abs(dx) / dist).coerceIn(0f, 1f) else 0f

        // Dynamic tilt compensation: shifts AWAY from the near cushion knuckle
        // (dx > 0 means ball is to the right, near knuckle is right, shift LEFT; vice-versa)
        val shiftMagnitude = (ballR * (tiltRatio * tiltRatio * 0.55f + tiltRatio * 0.15f)).coerceAtMost(maxShift)
        val shiftSign = if (dx > 0.5f) -1f else if (dx < -0.5f) 1f else 0f

        val targetX = cx + (shiftSign * shiftMagnitude)
        val targetY = railY

        val rayDx = targetX - bx
        val rayDy = targetY - by
        val rayDist = hypot(rayDx, rayDy)
        if (rayDist > 1f) {
            val ux = rayDx / rayDist
            val uy = rayDy / rayDist
            val actualEnd = if (isFullLengthGuides) rayDist else endDist.coerceAtMost(rayDist)
            if (actualEnd > startDist) {
                canvas.drawLine(
                    bx + ux * startDist,
                    by + uy * startDist,
                    bx + ux * actualEnd,
                    by + uy * actualEnd,
                    paint
                )
            }
        }
    }

    /**
     * Precision Corner Vertex Ray (4 Corner Gates: Top-Left, Top-Right, Bottom-Left, Bottom-Right).
     * Accounts for physical corner pocket mouth center, ensuring all rays accurately target the
     * corner pocket openings across any position on the table without clipping cushions.
     * Zero-allocation primitive Float calculations.
     */
    private fun drawCornerVertexRay(
        canvas: Canvas,
        bx: Float,
        by: Float,
        cornerX: Float,
        cornerY: Float,
        isLeft: Boolean,
        isTop: Boolean,
        startDist: Float,
        endDist: Float,
        paint: Paint
    ) {
        val signX = if (isLeft) 1f else -1f
        val signY = if (isTop) 1f else -1f

        // Precision corner pocket target center (titik pusat arah 4 lubang sudut)
        // Offset horizontal (9.0dp) dan vertikal (11.2dp) tepat mengarah ke titik pusat mulut lubang kantong
        val cornerMouthInsetX = 9.0f * density
        val cornerMouthInsetY = 11.2f * density
        val targetX = cornerX + (signX * cornerMouthInsetX)
        val targetY = cornerY + (signY * cornerMouthInsetY)

        val rayDx = targetX - bx
        val rayDy = targetY - by
        val rayDist = hypot(rayDx, rayDy)
        if (rayDist > 1f) {
            val ux = rayDx / rayDist
            val uy = rayDy / rayDist
            val actualEnd = if (isFullLengthGuides) rayDist else endDist.coerceAtMost(rayDist)
            if (actualEnd > startDist) {
                canvas.drawLine(
                    bx + ux * startDist,
                    by + uy * startDist,
                    bx + ux * actualEnd,
                    by + uy * actualEnd,
                    paint
                )
            }
        }
    }

    /**
     * Precision Bank / Cushion Reflection Ray.
     * Computes exact geometric reflection off cushion plane (horizontal or vertical)
     * towards destination pocket, drawing:
     * - In Short Mode: dashed ray (85dp) from ball toward cushion bounce point (or along reflection path if at cushion).
     * - In Full Mode: full dashed ray from ball to cushion bounce point, contact dot marker,
     *   and full dashed reflected ray from cushion bounce point into destination pocket mouth!
     * Rendered via bankRayPath + DashPathEffect for hardware-accelerated dashed styling.
     * Fully preserves bank rays even when target ball is directly adjacent to or touching the cushion rail.
     */
    private fun drawBankRayToPocket(
        canvas: Canvas,
        bx: Float,
        by: Float,
        pocketX: Float,
        pocketY: Float,
        cushionIsHorizontal: Boolean,
        cushionCoord: Float,
        cushionMin: Float,
        cushionMax: Float,
        startDist: Float,
        shortRayLength: Float
    ) {
        val hitX: Float
        val hitY: Float
        if (cushionIsHorizontal) {
            val effBy = if (cushionCoord < pocketY) by.coerceAtLeast(cushionCoord) else by.coerceAtMost(cushionCoord)
            val mirrorY = 2f * cushionCoord - pocketY
            val dy = mirrorY - effBy
            if (abs(dy) < 0.001f) return
            val t = (cushionCoord - effBy) / dy
            if (t < 0f || t > 1f) return
            hitX = bx + t * (pocketX - bx)
            hitY = cushionCoord
            if (hitX < cushionMin || hitX > cushionMax) return
        } else {
            val effBx = if (cushionCoord < pocketX) bx.coerceAtLeast(cushionCoord) else bx.coerceAtMost(cushionCoord)
            val mirrorX = 2f * cushionCoord - pocketX
            val dx = mirrorX - effBx
            if (abs(dx) < 0.001f) return
            val t = (cushionCoord - effBx) / dx
            if (t < 0f || t > 1f) return
            hitX = cushionCoord
            hitY = by + t * (pocketY - by)
            if (hitY < cushionMin || hitY > cushionMax) return
        }

        val dx1 = hitX - bx
        val dy1 = hitY - by
        val dist1 = hypot(dx1, dy1)

        val dx2 = pocketX - hitX
        val dy2 = pocketY - hitY
        val dist2 = hypot(dx2, dy2)
        if (dist2 < 1f) return
        val ux2 = dx2 / dist2
        val uy2 = dy2 / dist2

        if (isFullLengthGuides) {
            // Full Mode:
            // 1. Incoming ray from ball to cushion hit point
            if (dist1 > startDist) {
                val ux1 = dx1 / dist1
                val uy1 = dy1 / dist1
                bankRayPath.reset()
                bankRayPath.moveTo(bx + ux1 * startDist, by + uy1 * startDist)
                bankRayPath.lineTo(hitX, hitY)
                canvas.drawPath(bankRayPath, bankLinePaint)
            } else if (dist1 > 2f * density) {
                // Ball is very close to cushion, draw incoming segment from ball to hit point
                bankRayPath.reset()
                bankRayPath.moveTo(bx, by)
                bankRayPath.lineTo(hitX, hitY)
                canvas.drawPath(bankRayPath, bankLinePaint)
            }

            // 2. Cushion contact dot marker
            canvas.drawCircle(hitX, hitY, 3.5f * density, bankDotPaint)

            // 3. Full reflected ray from cushion hit point into destination pocket
            bankRayPath.reset()
            bankRayPath.moveTo(hitX, hitY)
            bankRayPath.lineTo(pocketX, pocketY)
            canvas.drawPath(bankRayPath, bankLinePaint)
        } else {
            // Short Mode:
            if (dist1 > startDist) {
                val ux1 = dx1 / dist1
                val uy1 = dy1 / dist1
                val actualEnd = shortRayLength.coerceAtMost(dist1)
                if (actualEnd > startDist) {
                    bankRayPath.reset()
                    bankRayPath.moveTo(bx + ux1 * startDist, by + uy1 * startDist)
                    bankRayPath.lineTo(bx + ux1 * actualEnd, by + uy1 * actualEnd)
                    canvas.drawPath(bankRayPath, bankLinePaint)
                }
            } else {
                // Ball is already close to or on cushion!
                // Draw short dashed ray along the reflected direction from cushion towards pocket!
                val shortReflectEnd = shortRayLength.coerceAtMost(dist2)
                bankRayPath.reset()
                bankRayPath.moveTo(hitX, hitY)
                bankRayPath.lineTo(hitX + ux2 * shortReflectEnd, hitY + uy2 * shortReflectEnd)
                canvas.drawPath(bankRayPath, bankLinePaint)
            }
        }
    }

    /**
     * Zero-Allocation Target Mode: 6 Direct Rays with 12-Jaw Gate Clearance + 10 Cushion Bank Rays.
     * Direct primitive calculations per frame without allocating any Array or PointF objects.
     */
    private fun drawTargetAndRadialGuides(canvas: Canvas) {
        val bx = pointB.x
        val by = pointB.y

        // 1. Ball-Fit Ring: High-Contrast Hollow Reticle matching ball size
        val ringRadius = ringRadiusDp * density
        canvas.drawCircle(bx, by, ringRadius, aimShadowPaint)
        canvas.drawCircle(bx, by, ringRadius, aimRingPaint)

        // 2. Micro Center Pinpoint: Double contrast micro center dot
        canvas.drawCircle(bx, by, 2.5f * density, aimCenterShadowPaint)
        canvas.drawCircle(bx, by, 1.5f * density, aimCenterDotPaint)

        // Draw the 6 direct rays and 10 mirror reflection rays if enabled
        if (showRadialGuides) {
            val startDist = ringRadius + (3f * density)
            val directEndDist = 85f * density

            val l = boundaryBox.left
            val r = boundaryBox.right
            val t = boundaryBox.top
            val b = boundaryBox.bottom
            val cx = boundaryBox.centerX()

            // 1. 6 Direct Vertex Rays (4 Corner Gates with Rail Clearance + 2 Precision Lateral Gates with Tilt Modeling)
            // Top-Left (Corner)
            drawCornerVertexRay(canvas, bx, by, l, t, isLeft = true, isTop = true, startDist, directEndDist, sixLinePaint)

            // Top-Center (Precision Lateral Gate with Dynamic Tilt Angle Modeling - UNTOUCHED)
            drawLateralVertexRay(canvas, bx, by, cx, t, isTopGate = true, startDist, directEndDist, sixLinePaint)

            // Top-Right (Corner)
            drawCornerVertexRay(canvas, bx, by, r, t, isLeft = false, isTop = true, startDist, directEndDist, sixLinePaint)

            // Bottom-Left (Corner)
            drawCornerVertexRay(canvas, bx, by, l, b, isLeft = true, isTop = false, startDist, directEndDist, sixLinePaint)

            // Bottom-Center (Precision Lateral Gate with Dynamic Tilt Angle Modeling - UNTOUCHED)
            drawLateralVertexRay(canvas, bx, by, cx, b, isTopGate = false, startDist, directEndDist, sixLinePaint)

            // Bottom-Right (Corner)
            drawCornerVertexRay(canvas, bx, by, r, b, isLeft = false, isTop = false, startDist, directEndDist, sixLinePaint)

            // 2. 10 Mirror Bank Rays (Pantulan Dinding Rel) - Toggleable via showBankGuides
            if (showBankGuides) {
                val ballR = ringRadiusDp * density
                val bounceL = l + ballR
                val bounceR = r - ballR
                val bounceT = t + ballR
                val bounceB = b - ballR

                val cornerMouthInsetX = 9.0f * density
                val cornerMouthInsetY = 11.2f * density

                val pocketTL_X = l + cornerMouthInsetX
                val pocketTL_Y = t + cornerMouthInsetY

                val pocketTR_X = r - cornerMouthInsetX
                val pocketTR_Y = t + cornerMouthInsetY

                val pocketBL_X = l + cornerMouthInsetX
                val pocketBL_Y = b - cornerMouthInsetY

                val pocketBR_X = r - cornerMouthInsetX
                val pocketBR_Y = b - cornerMouthInsetY

                val pocketTC_X = cx
                val pocketTC_Y = t

                val pocketBC_X = cx
                val pocketBC_Y = b

                val shortBankLength = 85f * density

                // Cushion min/max along rails (excluding corner mouth drop areas)
                val railH_Min = l + cornerMouthInsetX
                val railH_Max = r - cornerMouthInsetX
                val railV_Min = t + cornerMouthInsetY
                val railV_Max = b - cornerMouthInsetY

                // Top Cushion (3 rays: bounce off top rail toward bottom pockets)
                // Enabled whenever ball is on table above bottom pockets (even when touching top rail)
                if (by < b - 15f * density && by >= t - 5f * density) {
                    drawBankRayToPocket(canvas, bx, by, pocketBL_X, pocketBL_Y, true, bounceT, railH_Min, railH_Max, startDist, shortBankLength)
                    drawBankRayToPocket(canvas, bx, by, pocketBC_X, pocketBC_Y, true, bounceT, railH_Min, railH_Max, startDist, shortBankLength)
                    drawBankRayToPocket(canvas, bx, by, pocketBR_X, pocketBR_Y, true, bounceT, railH_Min, railH_Max, startDist, shortBankLength)
                }

                // Bottom Cushion (3 rays: bounce off bottom rail toward top pockets)
                // Enabled whenever ball is on table below top pockets (even when touching bottom rail)
                if (by > t + 15f * density && by <= b + 5f * density) {
                    drawBankRayToPocket(canvas, bx, by, pocketTL_X, pocketTL_Y, true, bounceB, railH_Min, railH_Max, startDist, shortBankLength)
                    drawBankRayToPocket(canvas, bx, by, pocketTC_X, pocketTC_Y, true, bounceB, railH_Min, railH_Max, startDist, shortBankLength)
                    drawBankRayToPocket(canvas, bx, by, pocketTR_X, pocketTR_Y, true, bounceB, railH_Min, railH_Max, startDist, shortBankLength)
                }

                // Right Cushion (2 rays: bounce off right rail toward left pockets)
                // Enabled whenever ball is on table to the right of left pockets (even when touching right rail)
                if (bx > l + 15f * density && bx <= r + 5f * density) {
                    drawBankRayToPocket(canvas, bx, by, pocketTL_X, pocketTL_Y, false, bounceR, railV_Min, railV_Max, startDist, shortBankLength)
                    drawBankRayToPocket(canvas, bx, by, pocketBL_X, pocketBL_Y, false, bounceR, railV_Min, railV_Max, startDist, shortBankLength)
                }

                // Left Cushion (2 rays: bounce off left rail toward right pockets)
                // Enabled whenever ball is on table to the left of right pockets (even when touching left rail)
                if (bx < r - 15f * density && bx >= l - 5f * density) {
                    drawBankRayToPocket(canvas, bx, by, pocketTR_X, pocketTR_Y, false, bounceL, railV_Min, railV_Max, startDist, shortBankLength)
                    drawBankRayToPocket(canvas, bx, by, pocketBR_X, pocketBR_Y, false, bounceL, railV_Min, railV_Max, startDist, shortBankLength)
                }
            }
        }
    }

    private fun drawBoundaryBox(canvas: Canvas) {
        canvas.drawRect(boundaryBox, boundaryCalibPaint)

        // Feature 1: 6 Pocket Mouth Target Rings (Jaw Guides in Calibration Mode)
        val l = boundaryBox.left
        val r = boundaryBox.right
        val t = boundaryBox.top
        val b = boundaryBox.bottom
        val cx = boundaryBox.centerX()

        val cornerMouthInsetX = 9.0f * density
        val cornerMouthInsetY = 11.2f * density

        drawVertexGuideRing(canvas, l + cornerMouthInsetX, t + cornerMouthInsetY)   // Top-Left
        drawVertexGuideRing(canvas, cx, t)                                         // Top-Center
        drawVertexGuideRing(canvas, r - cornerMouthInsetX, t + cornerMouthInsetY)   // Top-Right
        drawVertexGuideRing(canvas, l + cornerMouthInsetX, b - cornerMouthInsetY)   // Bottom-Left
        drawVertexGuideRing(canvas, cx, b)                                         // Bottom-Center
        drawVertexGuideRing(canvas, r - cornerMouthInsetX, b - cornerMouthInsetY)   // Bottom-Right

        val label = "Calibration: Drag 8 handles to align target bounds"
        textPaint.getTextBounds(label, 0, label.length, textBounds)
        val textX = boundaryBox.left + 16f * density
        val textY = (boundaryBox.top - 12f * density).coerceAtLeast(36f * density)
        val padding = 6f * density
        canvas.drawRect(
            textX - padding,
            textY - textBounds.height() - padding,
            textX + textBounds.width() + padding,
            textY + padding,
            textBgPaint
        )
        canvas.drawText(label, textX, textY, textPaint)
    }

    private fun drawVertexGuideRing(canvas: Canvas, px: Float, py: Float) {
        val ringRadius = 12f * density
        canvas.drawCircle(px, py, ringRadius, vertexRingPaint)
        val crossArm = 4f * density
        canvas.drawLine(px - crossArm, py, px + crossArm, py, vertexCrossPaint)
        canvas.drawLine(px, py - crossArm, px, py + crossArm, vertexCrossPaint)
    }

    /**
     * Requirement 2: Render side-rail calibration handles (left and right edges)
     * and top/bottom center indicators.
     * The 6 pocket positions are clearly indicated by the 6 cyan jaw target rings.
     */
    private fun drawEightCalibrationHandles(canvas: Canvas) {
        val cx = boundaryBox.centerX()
        val cy = boundaryBox.centerY()
        val sideRadius = edgeHandleRadius * 0.75f

        // 2 Side Edge Centers (Left & Right rail resize handles)
        drawHandlePin(canvas, boundaryBox.left, cy, edgeHandleRadius)
        drawHandlePin(canvas, boundaryBox.right, cy, edgeHandleRadius)

        // Top & Bottom Edge Centers (Resize indicators)
        drawHandlePin(canvas, cx, boundaryBox.top, sideRadius)
        drawHandlePin(canvas, cx, boundaryBox.bottom, sideRadius)
    }

    private fun drawHandlePin(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        canvas.drawCircle(cx, cy, radius, handleOuterPaint)
        canvas.drawCircle(cx, cy, radius, handleStrokePaint)
        canvas.drawCircle(cx, cy, 4f * density, handleCenterPaint)
    }

    private fun updateInnerInsetBox() {
        val cushionBounceInset = (5f * density) + (ringRadiusDp * density)
        innerInsetBox.set(
            boundaryBox.left + cushionBounceInset,
            boundaryBox.top + cushionBounceInset,
            boundaryBox.right - cushionBounceInset,
            boundaryBox.bottom - cushionBounceInset
        )
    }

    private fun drawAutoReflection(canvas: Canvas) {
        updateInnerInsetBox()
        val reflection = GeometryUtils.computeReflection(pointA, pointB, innerInsetBox)

        if (reflection != null) {
            // Primary Line: A -> Hit Point
            linePath.reset()
            linePath.moveTo(pointA.x, pointA.y)
            linePath.lineTo(reflection.hitPoint.x, reflection.hitPoint.y)
            canvas.drawPath(linePath, primaryLinePaint)

            // Reflected Line: Hit Point -> Reflected End Point
            linePath.reset()
            linePath.moveTo(reflection.hitPoint.x, reflection.hitPoint.y)
            linePath.lineTo(reflection.reflectedPoint.x, reflection.reflectedPoint.y)
            canvas.drawPath(linePath, reflectedLinePaint)

            // Collision ring at boundary hit point
            canvas.drawCircle(
                reflection.hitPoint.x,
                reflection.hitPoint.y,
                6f * density,
                edgeMarkerPaint
            )
            canvas.drawCircle(
                reflection.hitPoint.x,
                reflection.hitPoint.y,
                6f * density,
                edgeMarkerStrokePaint
            )

            // Angle text badge near reflection point
            val angleText = String.format(Locale.US, "θ = %.1f°", reflection.angleOfIncidenceDeg)
            drawTextBadge(canvas, angleText, reflection.hitPoint.x + 12f * density, reflection.hitPoint.y - 12f * density)
        } else {
            // Fallback straight ray through B if no boundary hit
            linePath.reset()
            linePath.moveTo(pointA.x, pointA.y)
            linePath.lineTo(pointB.x, pointB.y)
            canvas.drawPath(linePath, primaryLinePaint)
        }
    }

    private fun drawManualThreePoint(canvas: Canvas) {
        // Line 1: A -> B
        linePath.reset()
        linePath.moveTo(pointA.x, pointA.y)
        linePath.lineTo(pointB.x, pointB.y)
        canvas.drawPath(linePath, primaryLinePaint)

        // Line 2: B -> C
        linePath.reset()
        linePath.moveTo(pointB.x, pointB.y)
        linePath.lineTo(pointC.x, pointC.y)
        canvas.drawPath(linePath, reflectedLinePaint)

        // Draw Angle at pivot Point B
        val angle = GeometryUtils.computeAngleBetweenLines(pointA, pointB, pointC)
        val angleText = String.format(Locale.US, "∠ = %.1f°", angle)
        drawTextBadge(canvas, angleText, pointB.x + 14f * density, pointB.y - 14f * density)
    }

    private fun drawEdgeMarkerOnly(canvas: Canvas) {
        updateInnerInsetBox()
        val reflection = GeometryUtils.computeReflection(pointA, pointB, innerInsetBox)

        if (reflection != null) {
            canvas.drawCircle(
                reflection.hitPoint.x,
                reflection.hitPoint.y,
                edgeMarkerRadius,
                edgeMarkerPaint
            )
            canvas.drawCircle(
                reflection.hitPoint.x,
                reflection.hitPoint.y,
                edgeMarkerRadius,
                edgeMarkerStrokePaint
            )

            canvas.drawCircle(
                reflection.reflectedPoint.x,
                reflection.reflectedPoint.y,
                edgeMarkerRadius * 0.75f,
                edgeMarkerPaint
            )
        }
    }

    private fun drawTextBadge(canvas: Canvas, text: String, x: Float, y: Float) {
        textPaint.getTextBounds(text, 0, text.length, textBounds)
        val pad = 4f * density
        badgeRect.set(
            x - pad,
            y - textBounds.height() - pad,
            x + textBounds.width() + pad,
            y + pad
        )
        canvas.drawRoundRect(badgeRect, 4f * density, 4f * density, textBgPaint)
        canvas.drawText(text, x, y, textPaint)
    }

    /**
     * Requirement 2: 8-Handle touch dragging in calibration mode.
     */
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isCalibrating) {
            return false
        }

        val touchX = event.x
        val touchY = event.y

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activeDragTarget = findTouchedCalibrationTarget(touchX, touchY)
                if (activeDragTarget != DragTarget.NONE) {
                    val targetPos = getTargetPos(activeDragTarget)
                    touchOffset.set(touchX - targetPos.x, touchY - targetPos.y)
                    return true
                }
                return false
            }

            MotionEvent.ACTION_MOVE -> {
                if (activeDragTarget != DragTarget.NONE) {
                    val minSize = 60f * density
                    val landSize = getLandscapeScreenSize()
                    val screenW = landSize.x
                    val screenH = landSize.y

                    val curX = (touchX - touchOffset.x).coerceIn(0f, screenW)
                    val curY = (touchY - touchOffset.y).coerceIn(0f, screenH)

                    when (activeDragTarget) {
                        DragTarget.CORNER_TOP_LEFT -> {
                            boundaryBox.left = curX.coerceAtMost(boundaryBox.right - minSize)
                            boundaryBox.top = curY.coerceAtMost(boundaryBox.bottom - minSize)
                        }
                        DragTarget.CORNER_TOP_RIGHT -> {
                            boundaryBox.right = curX.coerceAtLeast(boundaryBox.left + minSize)
                            boundaryBox.top = curY.coerceAtMost(boundaryBox.bottom - minSize)
                        }
                        DragTarget.CORNER_BOTTOM_LEFT -> {
                            boundaryBox.left = curX.coerceAtMost(boundaryBox.right - minSize)
                            boundaryBox.bottom = curY.coerceAtLeast(boundaryBox.top + minSize)
                        }
                        DragTarget.CORNER_BOTTOM_RIGHT -> {
                            boundaryBox.right = curX.coerceAtLeast(boundaryBox.left + minSize)
                            boundaryBox.bottom = curY.coerceAtLeast(boundaryBox.top + minSize)
                        }
                        DragTarget.HANDLE_TOP_CENTER -> {
                            boundaryBox.top = curY.coerceAtMost(boundaryBox.bottom - minSize)
                        }
                        DragTarget.HANDLE_BOTTOM_CENTER -> {
                            boundaryBox.bottom = curY.coerceAtLeast(boundaryBox.top + minSize)
                        }
                        DragTarget.HANDLE_LEFT_CENTER -> {
                            boundaryBox.left = curX.coerceAtMost(boundaryBox.right - minSize)
                        }
                        DragTarget.HANDLE_RIGHT_CENTER -> {
                            boundaryBox.right = curX.coerceAtLeast(boundaryBox.left + minSize)
                        }
                        DragTarget.NONE -> Unit
                    }
                    invalidate()
                    return true
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (activeDragTarget != DragTarget.NONE) {
                    activeDragTarget = DragTarget.NONE
                    prefsManager.saveBoundaryBox(boundaryBox.left, boundaryBox.top, boundaryBox.right, boundaryBox.bottom)
                    invalidate()
                    return true
                }
            }
        }

        return super.onTouchEvent(event)
    }

    private fun findTouchedCalibrationTarget(x: Float, y: Float): DragTarget {
        val cornerThreshold = 40f * density
        val edgeThreshold = 36f * density

        val cx = boundaryBox.centerX()
        val cy = boundaryBox.centerY()

        // 1. Check 4 Corners
        if (GeometryUtils.distance(x, y, boundaryBox.left, boundaryBox.top) <= cornerThreshold) {
            return DragTarget.CORNER_TOP_LEFT
        }
        if (GeometryUtils.distance(x, y, boundaryBox.right, boundaryBox.top) <= cornerThreshold) {
            return DragTarget.CORNER_TOP_RIGHT
        }
        if (GeometryUtils.distance(x, y, boundaryBox.left, boundaryBox.bottom) <= cornerThreshold) {
            return DragTarget.CORNER_BOTTOM_LEFT
        }
        if (GeometryUtils.distance(x, y, boundaryBox.right, boundaryBox.bottom) <= cornerThreshold) {
            return DragTarget.CORNER_BOTTOM_RIGHT
        }

        // 2. Check 4 Edge Centers
        if (GeometryUtils.distance(x, y, cx, boundaryBox.top) <= edgeThreshold) {
            return DragTarget.HANDLE_TOP_CENTER
        }
        if (GeometryUtils.distance(x, y, cx, boundaryBox.bottom) <= edgeThreshold) {
            return DragTarget.HANDLE_BOTTOM_CENTER
        }
        if (GeometryUtils.distance(x, y, boundaryBox.left, cy) <= edgeThreshold) {
            return DragTarget.HANDLE_LEFT_CENTER
        }
        if (GeometryUtils.distance(x, y, boundaryBox.right, cy) <= edgeThreshold) {
            return DragTarget.HANDLE_RIGHT_CENTER
        }

        return DragTarget.NONE
    }

    private fun getTargetPos(target: DragTarget): PointF {
        val cx = boundaryBox.centerX()
        val cy = boundaryBox.centerY()
        return when (target) {
            DragTarget.CORNER_TOP_LEFT -> PointF(boundaryBox.left, boundaryBox.top)
            DragTarget.CORNER_TOP_RIGHT -> PointF(boundaryBox.right, boundaryBox.top)
            DragTarget.CORNER_BOTTOM_LEFT -> PointF(boundaryBox.left, boundaryBox.bottom)
            DragTarget.CORNER_BOTTOM_RIGHT -> PointF(boundaryBox.right, boundaryBox.bottom)
            DragTarget.HANDLE_TOP_CENTER -> PointF(cx, boundaryBox.top)
            DragTarget.HANDLE_BOTTOM_CENTER -> PointF(cx, boundaryBox.bottom)
            DragTarget.HANDLE_LEFT_CENTER -> PointF(boundaryBox.left, cy)
            DragTarget.HANDLE_RIGHT_CENTER -> PointF(boundaryBox.right, cy)
            DragTarget.NONE -> PointF(0f, 0f)
        }
    }
}
