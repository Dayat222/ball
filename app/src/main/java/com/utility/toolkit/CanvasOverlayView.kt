package com.utility.toolkit

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
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * High-performance transparent canvas rendering:
 * 1. 6-pocket direct vertex rays from the target ball (Point B).
 * 2. 10 cushion bank rays with pocket-quality reflection paths.
 * 3. Connecting stem/tether line from the offset finger grip to the real aim coordinate.
 * 4. Interactive 8-handle boundary box calibration (auto-clamped for Landscape & Portrait,
 *    and completely hidden when not calibrating for clean view).
 */
class CanvasOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private enum class DragTarget {
        NONE,
        CORNER_TOP_LEFT, CORNER_TOP_RIGHT, CORNER_BOTTOM_LEFT, CORNER_BOTTOM_RIGHT,
        HANDLE_TOP_CENTER, HANDLE_BOTTOM_CENTER, HANDLE_LEFT_CENTER, HANDLE_RIGHT_CENTER
    }

    private val prefsManager = PrefsManager(context)

    // Calibration
    var isCalibrating: Boolean = false
        private set

    // Target Ring Radius & Bank Ray Toggle
    var showBankGuides: Boolean = true
        private set

    var isFullLengthGuides: Boolean = false
        private set

    var ringRadiusDp: Float = 13.5f
        private set

    // Cushion rebound ratio: 1.0 = pure specular mirror, 0.90 = real cloth "short" bank.
    var bankRebound: Float = 1f
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

    // Actual aim coordinate of the target ball and its offset finger grip
    var pointB = PointF()
        private set
    private var gripB = PointF()
    private var hasGripB = false

    var boundaryBox = RectF()
        private set

    // Touch Dragging State for Calibration
    private var activeDragTarget = DragTarget.NONE
    private var touchOffset = PointF(0f, 0f)

    // Density scaled dimensions
    private val density = resources.displayMetrics.density
    private val edgeHandleRadius = 14f * density

    // Paint: 6-Pocket Radial Short Lines from Target Ball (sixLinePaint)
    private val sixLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        style = Paint.Style.STROKE
        strokeWidth = 3.5f * density
        strokeCap = Paint.Cap.ROUND
    }

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

    var onCanvasSizeChanged: ((w: Int, h: Int) -> Unit)? = null

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

    // Paint: Calibration label text
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 13f * density
    }

    private val textBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#B3000000")
        style = Paint.Style.FILL
    }

    private val textBounds = Rect()

    init {
        // Stealth: keep the overlay out of the accessibility tree. Another app
        // holding AccessibilityService can enumerate overlay windows and would
        // otherwise read this package/class name straight off the node.
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO

        val landSize = getLandscapeScreenSize()
        boundaryBox = prefsManager.getBoundaryBox(landSize.x, landSize.y)
        clampBoundaryBox(landSize.x, landSize.y)

        pointB = prefsManager.getTargetPoint(landSize.x, landSize.y)

        currentLineColor = prefsManager.getLineColor()
        currentBankLineColor = prefsManager.getSecondaryRayColor()
        currentAlpha = prefsManager.getLineAlpha()
        currentStrokeWidth = prefsManager.getLineStrokeWidth()
        updateLineColor(currentLineColor)
        updateBankLineColor(currentBankLineColor)
        updateLineStyle(currentStrokeWidth, currentAlpha)

        showBankGuides = prefsManager.getShowBankGuides()
        isFullLengthGuides = prefsManager.isFullLengthGuides()
        ringRadiusDp = prefsManager.getRingRadius()
        bankRebound = prefsManager.getBankRebound()
    }

    override fun getAccessibilityClassName(): CharSequence = "android.view.View"

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
     * Requirement 2: Dynamically adjust line color of the pocket rays,
     * preserving currently active opacity alpha.
     */
    fun updateLineColor(color: Int) {
        currentLineColor = color
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        val argb = Color.argb(currentAlpha, r, g, b)

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

        sixLinePaint.strokeWidth = strokePx
        bankLinePaint.strokeWidth = strokePx

        val r = Color.red(currentLineColor)
        val g = Color.green(currentLineColor)
        val b = Color.blue(currentLineColor)
        val argb = Color.argb(currentAlpha, r, g, b)

        sixLinePaint.color = argb

        val br = Color.red(currentBankLineColor)
        val bg = Color.green(currentBankLineColor)
        val bb = Color.blue(currentBankLineColor)
        val bankArgb = Color.argb(currentAlpha, br, bg, bb)

        bankLinePaint.color = bankArgb
        bankDotPaint.color = bankArgb
        bankLinePaint.pathEffect = DashPathEffect(floatArrayOf(10f * density, 7f * density), 0f)

        // Synchronize stem/tether line alpha
        val stemAlpha = (currentAlpha * 0.7f).roundToInt().coerceIn(20, 255)
        stemLinePaint.color = Color.argb(stemAlpha, 255, 255, 255)

        val shadowAlpha = (currentAlpha * 0.85f).roundToInt().coerceIn(20, 255)
        aimShadowPaint.color = Color.argb(shadowAlpha, 0, 0, 0)
        aimCenterShadowPaint.color = Color.argb(shadowAlpha, 0, 0, 0)
        aimRingPaint.color = Color.argb(currentAlpha, 255, 255, 255)
        aimCenterDotPaint.color = Color.argb(currentAlpha, 255, 255, 255)

        invalidate()
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

    /**
     * Cushion restitution as an angle ratio. 1.0 keeps exact mirror geometry;
     * lower values make every bank roll short the way a real cloth does.
     */
    fun setBankRebound(ratio: Float) {
        bankRebound = ratio.coerceIn(0.70f, 1.0f)
        prefsManager.saveBankRebound(bankRebound)
        invalidate()
    }

    fun setPointB(aimX: Float, aimY: Float, gripX: Float = aimX, gripY: Float = aimY) {
        pointB.set(aimX, aimY)
        gripB.set(gripX, gripY)
        hasGripB = (GeometryUtils.distance(aimX, aimY, gripX, gripY) > 5f)
        invalidate()
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
        } else {
            // Single-point target mode: stem tether + ring + 6 direct rays + 10 bank rays
            if (hasGripB) {
                canvas.drawLine(gripB.x, gripB.y, pointB.x, pointB.y, stemLinePaint)
            }
            drawTargetAndRadialGuides(canvas)
        }

        canvas.restoreToCount(saveCount)
    }

    // --- Aim solver (scratch instance, allocation-free per frame) ---

    private val aim = AimGeometry()

    private val allPockets = arrayOf(
        AimGeometry.Pocket.TOP_LEFT,
        AimGeometry.Pocket.TOP_CENTER,
        AimGeometry.Pocket.TOP_RIGHT,
        AimGeometry.Pocket.BOTTOM_LEFT,
        AimGeometry.Pocket.BOTTOM_CENTER,
        AimGeometry.Pocket.BOTTOM_RIGHT
    )

    private val topCushionPockets = arrayOf(
        AimGeometry.Pocket.BOTTOM_LEFT,
        AimGeometry.Pocket.BOTTOM_CENTER,
        AimGeometry.Pocket.BOTTOM_RIGHT
    )

    private val bottomCushionPockets = arrayOf(
        AimGeometry.Pocket.TOP_LEFT,
        AimGeometry.Pocket.TOP_CENTER,
        AimGeometry.Pocket.TOP_RIGHT
    )

    private val rightCushionPockets = arrayOf(
        AimGeometry.Pocket.TOP_LEFT,
        AimGeometry.Pocket.BOTTOM_LEFT
    )

    private val leftCushionPockets = arrayOf(
        AimGeometry.Pocket.TOP_RIGHT,
        AimGeometry.Pocket.BOTTOM_RIGHT
    )

    /**
     * One direct ray: ball centre -> gate point of the last solved pocket.
     * Short mode stops at 85dp, Full mode runs through the mouth.
     */
    private fun drawDirectRay(
        canvas: Canvas,
        bx: Float,
        by: Float,
        startDist: Float,
        shortLen: Float
    ) {
        val tx = aim.targetX.toFloat()
        val ty = aim.targetY.toFloat()
        val dx = tx - bx
        val dy = ty - by
        val dist = hypot(dx, dy)
        if (dist <= startDist) return

        val ux = dx / dist
        val uy = dy / dist
        val end = if (isFullLengthGuides) dist + aim.mouthDepthPx.toFloat() else shortLen.coerceAtMost(dist)
        if (end <= startDist) return

        canvas.drawLine(
            bx + ux * startDist,
            by + uy * startDist,
            bx + ux * end,
            by + uy * end,
            sixLinePaint
        )
    }

    /**
     * All ten one-cushion banks: 3 off each long rail, 2 off each short rail.
     * Bounce validity is checked against the real cushion segment between the
     * pocket mouths, so a ray whose bounce lands inside a pocket opening is
     * rejected instead of being drawn through the gap.
     */
    private fun drawBankGuides(
        canvas: Canvas,
        bx: Float,
        by: Float,
        ringRadius: Float,
        startDist: Float,
        shortLen: Float
    ) {
        val l = boundaryBox.left
        val r = boundaryBox.right
        val t = boundaryBox.top
        val b = boundaryBox.bottom
        val cx = boundaryBox.centerX()

        val ballR = ringRadius
        val jaw = aim.cornerJawPx.toFloat()
        val mouth = aim.middleHalfPx.toFloat()

        // Nothing to bank off while the handle is dragged outside the cloth.
        if (bx < l - ballR || bx > r + ballR || by < t - ballR || by > b + ballR) return

        // A ball centre can never roll closer than one radius to a jaw tip.
        val topSegA0 = l + jaw + ballR
        val topSegA1 = cx - mouth - ballR
        val topSegB0 = cx + mouth + ballR
        val topSegB1 = r - jaw - ballR

        val sideSeg0 = t + jaw + ballR
        val sideSeg1 = b - jaw - ballR

        drawBankSet(
            canvas, bx, by, AimGeometry.Axis.HORIZONTAL, t + ballR, 1,
            topCushionPockets,
            topSegA0, topSegA1, topSegB0, topSegB1,
            startDist, shortLen
        )

        drawBankSet(
            canvas, bx, by, AimGeometry.Axis.HORIZONTAL, b - ballR, -1,
            bottomCushionPockets,
            topSegA0, topSegA1, topSegB0, topSegB1,
            startDist, shortLen
        )

        drawBankSet(
            canvas, bx, by, AimGeometry.Axis.VERTICAL, r - ballR, -1,
            rightCushionPockets,
            sideSeg0, sideSeg1, Float.NaN, Float.NaN,
            startDist, shortLen
        )

        drawBankSet(
            canvas, bx, by, AimGeometry.Axis.VERTICAL, l + ballR, 1,
            leftCushionPockets,
            sideSeg0, sideSeg1, Float.NaN, Float.NaN,
            startDist, shortLen
        )
    }

    private fun drawBankSet(
        canvas: Canvas,
        bx: Float,
        by: Float,
        axis: AimGeometry.Axis,
        plane: Float,
        side: Int,
        pockets: Array<AimGeometry.Pocket>,
        segAStart: Float,
        segAEnd: Float,
        segBStart: Float,
        segBEnd: Float,
        startDist: Float,
        shortLen: Float
    ) {
        val hasA = segAEnd > segAStart
        val hasB = segBEnd > segBStart
        if (!hasA && !hasB) return

        for (pocket in pockets) {
            // Seed: aim straight at the mouth, then let the bank solve fold it.
            aim.gate(pocket, bx.toDouble(), by.toDouble())
            if (!solveOnRail(bx, by, axis, plane, side, segAStart, segAEnd, segBStart, segBEnd, hasA, hasB)) {
                continue
            }

            // Re-thread the entry from the real bounce point, then re-solve once.
            val seedX = aim.hitX
            val seedY = aim.hitY
            aim.gate(pocket, seedX, seedY)
            val gateX = aim.targetX.toFloat()
            val gateY = aim.targetY.toFloat()

            if (!solveOnRail(bx, by, axis, plane, side, segAStart, segAEnd, segBStart, segBEnd, hasA, hasB)) {
                continue
            }

            drawBankShot(canvas, bx, by, gateX, gateY, startDist, shortLen)
        }
    }

    private fun solveOnRail(
        bx: Float,
        by: Float,
        axis: AimGeometry.Axis,
        plane: Float,
        side: Int,
        segAStart: Float,
        segAEnd: Float,
        segBStart: Float,
        segBEnd: Float,
        hasA: Boolean,
        hasB: Boolean
    ): Boolean {
        val gx = aim.targetX
        val gy = aim.targetY
        val ballX = bx.toDouble()
        val ballY = by.toDouble()

        if (hasA && aim.bankFrom(ballX, ballY, gx, gy, axis, plane, side, segAStart.toDouble(), segAEnd.toDouble())) {
            return true
        }
        if (hasB && aim.bankFrom(ballX, ballY, gx, gy, axis, plane, side, segBStart.toDouble(), segBEnd.toDouble())) {
            return true
        }
        return false
    }

    private fun drawBankShot(
        canvas: Canvas,
        bx: Float,
        by: Float,
        gateX: Float,
        gateY: Float,
        startDist: Float,
        shortLen: Float
    ) {
        val hx = aim.hitX.toFloat()
        val hy = aim.hitY.toFloat()

        val dx1 = hx - bx
        val dy1 = hy - by
        val dist1 = hypot(dx1, dy1)

        val dx2 = gateX - hx
        val dy2 = gateY - hy
        val dist2 = hypot(dx2, dy2)
        if (dist2 < 1f) return

        if (isFullLengthGuides) {
            if (dist1 > startDist) {
                val ux1 = dx1 / dist1
                val uy1 = dy1 / dist1
                bankRayPath.reset()
                bankRayPath.moveTo(bx + ux1 * startDist, by + uy1 * startDist)
                bankRayPath.lineTo(hx, hy)
                canvas.drawPath(bankRayPath, bankLinePaint)
            } else if (dist1 > 1f) {
                bankRayPath.reset()
                bankRayPath.moveTo(bx, by)
                bankRayPath.lineTo(hx, hy)
                canvas.drawPath(bankRayPath, bankLinePaint)
            }

            canvas.drawCircle(hx, hy, 3.5f * density, bankDotPaint)

            val ux2 = dx2 / dist2
            val uy2 = dy2 / dist2
            val extend = aim.mouthDepthPx.toFloat()
            bankRayPath.reset()
            bankRayPath.moveTo(hx, hy)
            bankRayPath.lineTo(gateX + ux2 * extend, gateY + uy2 * extend)
            canvas.drawPath(bankRayPath, bankLinePaint)
        } else {
            // Short mode must not shorten the reflected leg.
            //
            // Near a cushion the incoming leg is only a few dp long (the bounce
            // point sits next to the ball), so capping the whole path at the
            // short length collapsed the bank guide into a stub that could not
            // be used as an alignment reference. The reflection therefore always
            // runs the full short length from the contact point, independent of
            // how close the ball is to the cushion.
            val ux2 = dx2 / dist2
            val uy2 = dy2 / dist2
            val outgoing = shortLen.coerceAtMost(dist2)

            if (dist1 > shortLen) {
                // Bounce beyond the short reach: show the incoming leg only.
                if (dist1 > startDist) {
                    val ux1 = dx1 / dist1
                    val uy1 = dy1 / dist1
                    bankRayPath.reset()
                    bankRayPath.moveTo(bx + ux1 * startDist, by + uy1 * startDist)
                    bankRayPath.lineTo(bx + ux1 * shortLen, by + uy1 * shortLen)
                    canvas.drawPath(bankRayPath, bankLinePaint)
                }
            } else {
                // Bounce inside the short reach: full path, fixed-length reflection.
                if (dist1 > startDist) {
                    val ux1 = dx1 / dist1
                    val uy1 = dy1 / dist1
                    bankRayPath.reset()
                    bankRayPath.moveTo(bx + ux1 * startDist, by + uy1 * startDist)
                    bankRayPath.lineTo(hx, hy)
                    canvas.drawPath(bankRayPath, bankLinePaint)
                }

                canvas.drawCircle(hx, hy, 3.5f * density, bankDotPaint)

                bankRayPath.reset()
                bankRayPath.moveTo(hx, hy)
                bankRayPath.lineTo(hx + ux2 * outgoing, hy + uy2 * outgoing)
                canvas.drawPath(bankRayPath, bankLinePaint)
            }
        }
    }

    /**
     * Target mode render: ball-fit ring, 6 direct pocket rays, 10 cushion banks.
     * Direct rays and banks share one gate solver, so both agree on where the
     * mouth really is (the old fixed 9.0dp / 11.2dp inset did not match the
     * vertex-targeted bank rays).
     */
    private fun drawTargetAndRadialGuides(canvas: Canvas) {
        val bx = pointB.x
        val by = pointB.y
        val ringRadius = ringRadiusDp * density

        // 1. Ball-Fit Ring: high-contrast hollow reticle matching ball size
        canvas.drawCircle(bx, by, ringRadius, aimShadowPaint)
        canvas.drawCircle(bx, by, ringRadius, aimRingPaint)

        // 2. Micro centre pinpoint: double contrast dot
        canvas.drawCircle(bx, by, 2.5f * density, aimCenterShadowPaint)
        canvas.drawCircle(bx, by, 1.5f * density, aimCenterDotPaint)

        aim.rebound = bankRebound.toDouble()
        aim.configure(boundaryBox.left, boundaryBox.top, boundaryBox.right, boundaryBox.bottom, ringRadius)

        val startDist = ringRadius + (3f * density)
        val shortLen = 85f * density

        // 3. Six direct pocket rays
        for (pocket in allPockets) {
            aim.gate(pocket, bx.toDouble(), by.toDouble())
            drawDirectRay(canvas, bx, by, startDist, shortLen)
        }

        // 4. Ten cushion bank rays
        if (showBankGuides) {
            drawBankGuides(canvas, bx, by, ringRadius, startDist, shortLen)
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

        // Same mouth model the live rays use, so calibration and play agree.
        aim.configure(boundaryBox.left, boundaryBox.top, boundaryBox.right, boundaryBox.bottom, ringRadiusDp * density)
        val cornerMouthInset = aim.cornerJawPx.toFloat() * 0.5f

        drawVertexGuideRing(canvas, l + cornerMouthInset, t + cornerMouthInset)   // Top-Left
        drawVertexGuideRing(canvas, cx, t)                                        // Top-Center
        drawVertexGuideRing(canvas, r - cornerMouthInset, t + cornerMouthInset)   // Top-Right
        drawVertexGuideRing(canvas, l + cornerMouthInset, b - cornerMouthInset)   // Bottom-Left
        drawVertexGuideRing(canvas, cx, b)                                        // Bottom-Center
        drawVertexGuideRing(canvas, r - cornerMouthInset, b - cornerMouthInset)   // Bottom-Right

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
