package com.utility.screenprotractor

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import java.util.Locale
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

    // Feature 1: 6-Pocket Radial Lines & Ball Radius
    var showSixPocketLines: Boolean = true
        private set
    var ballRadius: Float = 28f
        private set

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
    private val cornerHandleRadius = 16f * density
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
        color = Color.parseColor("#FFD600")
        style = Paint.Style.STROKE
        strokeWidth = 3.5f * density
        pathEffect = DashPathEffect(floatArrayOf(15f * density, 10f * density), 0f)
    }

    // Paint: 6-Pocket Radial Short Lines from Target Ball
    private val pocketLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        style = Paint.Style.STROKE
        strokeWidth = 3.5f * density
        strokeCap = Paint.Cap.ROUND
    }

    // Paint: Target Ball (Point B) Circle Representation
    private val targetBallFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2600E5FF")
        style = Paint.Style.FILL
    }

    private val targetBallStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CC00E5FF")
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }

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

    // Paint: Boundary Box Active Calibration Highlight
    private val boundaryCalibPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFE082")
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
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
        setShadowLayer(4f, 0f, 0f, Color.BLACK)
    }

    private val textBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#B3000000")
        style = Paint.Style.FILL
    }

    private val linePath = Path()
    private val textBounds = Rect()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return

        val screenW = w.toFloat()
        val screenH = h.toFloat()

        // Load saved boundary box and auto-clamp to active screen size (Landscape/Portrait safe)
        boundaryBox = prefsManager.getBoundaryBox(screenW, screenH)
        clampBoundaryBox(screenW, screenH)

        // Load saved points
        val (savedA, savedB, savedC) = prefsManager.getPoints(screenW, screenH)
        pointA = savedA
        pointB = savedB
        pointC = savedC

        // Load saved mode
        val savedModeOrdinal = prefsManager.getMode()
        if (savedModeOrdinal in RulerMode.values().indices) {
            currentMode = RulerMode.values()[savedModeOrdinal]
        }

        // Load saved line styling
        val savedStroke = prefsManager.getLineStrokeWidth()
        val savedAlpha = prefsManager.getLineAlpha()
        updateLineStyle(savedStroke, savedAlpha)

        // Load 6-pocket line preference and ball radius
        showSixPocketLines = prefsManager.getShowSixLines()
        ballRadius = prefsManager.getBallRadius()
    }

    /**
     * Requirement 2: Auto-clamp boundaryBox to prevent overflowing screen edges in Landscape mode.
     */
    fun clampBoundaryBox(w: Float, h: Float) {
        if (w <= 0 || h <= 0) return
        val minMargin = 30f * density
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
        val w = width.toFloat().coerceAtLeast(200f)
        val h = height.toFloat().coerceAtLeast(200f)
        val l = w * 0.18f
        val t = h * 0.18f
        val r = w * 0.82f
        val b = h * 0.85f
        boundaryBox.set(l, t, r, b)
        prefsManager.saveBoundaryBox(l, t, r, b)
        invalidate()
    }

    /**
     * Dynamically adjust line thickness and opacity.
     */
    fun updateLineStyle(strokeWidth: Float, alpha: Int) {
        val clampedAlpha = alpha.coerceIn(25, 255)
        val strokePx = strokeWidth * density

        primaryLinePaint.strokeWidth = strokePx
        primaryLinePaint.alpha = clampedAlpha

        reflectedLinePaint.strokeWidth = strokePx
        reflectedLinePaint.alpha = clampedAlpha

        pocketLinePaint.strokeWidth = strokePx
        pocketLinePaint.alpha = clampedAlpha

        targetBallStrokePaint.strokeWidth = (strokeWidth * 0.7f).coerceAtLeast(1.5f) * density

        invalidate()
    }

    fun setShowSixPocketLines(show: Boolean) {
        showSixPocketLines = show
        prefsManager.saveShowSixLines(show)
        invalidate()
    }

    fun toggleSixPocketLines(): Boolean {
        setShowSixPocketLines(!showSixPocketLines)
        return showSixPocketLines
    }

    fun setBallRadius(radius: Float) {
        ballRadius = radius
        prefsManager.saveBallRadius(radius)
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
        val screenW = width.toFloat().coerceAtLeast(100f)
        val screenH = height.toFloat().coerceAtLeast(100f)
        prefsManager.resetBoundaryBox()
        boundaryBox = prefsManager.getBoundaryBox(screenW, screenH)
        clampBoundaryBox(screenW, screenH)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Requirement 1: Clean View. ONLY draw boundary box and handles when isCalibrating == true
        if (isCalibrating) {
            drawBoundaryBox(canvas)
            drawEightCalibrationHandles(canvas)
        }

        // Draw stem/tether connecting lines from Grip Pads to Aim Points
        if (!isCalibrating) {
            drawStemTethers(canvas)
        }

        // Render geometry lines and markers according to mode
        when (currentMode) {
            RulerMode.AUTO_REFLECT -> drawAutoReflection(canvas)
            RulerMode.MANUAL_3_POINT -> drawManualThreePoint(canvas)
            RulerMode.EDGE_MARKER_ONLY -> drawEdgeMarkerOnly(canvas)
        }

        // Feature 1: Draw Target Ball circle and 6-Pocket Radial Lines on Point B
        if (!isCalibrating) {
            drawTargetBallAndRadialLines(canvas)
        }
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
            canvas.drawCircle(pointB.x, pointB.y, 4f * density, aimDotPaint)
        }
        if (hasGripC && currentMode == RulerMode.MANUAL_3_POINT) {
            canvas.drawLine(gripC.x, gripC.y, pointC.x, pointC.y, stemLinePaint)
            canvas.drawCircle(pointC.x, pointC.y, 4f * density, aimDotPaint)
        }
    }

    /**
     * Feature 1: Draw target ball circle (radius 28f) and 6-Pocket radial lines on Point B.
     */
    private fun drawTargetBallAndRadialLines(canvas: Canvas) {
        val bx = pointB.x
        val by = pointB.y
        val ballR = ballRadius * density

        // Draw target ball representation circle
        canvas.drawCircle(bx, by, ballR, targetBallFillPaint)
        canvas.drawCircle(bx, by, ballR, targetBallStrokePaint)
        canvas.drawCircle(bx, by, 3f * density, aimDotPaint)

        // Draw the 6 pocket radial guide lines if enabled
        if (showSixPocketLines) {
            val pockets = arrayOf(
                PointF(boundaryBox.left, boundaryBox.top),                     // Top-Left
                PointF(boundaryBox.centerX(), boundaryBox.top),                // Top-Center
                PointF(boundaryBox.right, boundaryBox.top),                    // Top-Right
                PointF(boundaryBox.left, boundaryBox.bottom),                  // Bottom-Left
                PointF(boundaryBox.centerX(), boundaryBox.bottom),             // Bottom-Center
                PointF(boundaryBox.right, boundaryBox.bottom)                  // Bottom-Right
            )

            val startDist = ballR + (10f * density)
            val endDist = startDist + (75f * density)

            for (pocket in pockets) {
                val dx = pocket.x - bx
                val dy = pocket.y - by
                val dist = sqrt(dx * dx + dy * dy)
                if (dist > 1e-4f) {
                    val ux = dx / dist
                    val uy = dy / dist

                    val startX = bx + ux * startDist
                    val startY = by + uy * startDist
                    val endX = bx + ux * endDist
                    val endY = by + uy * endDist

                    canvas.drawLine(startX, startY, endX, endY, pocketLinePaint)
                    canvas.drawCircle(endX, endY, 2.5f * density, aimDotPaint)
                }
            }
        }
    }

    private fun drawBoundaryBox(canvas: Canvas) {
        canvas.drawRect(boundaryBox, boundaryCalibPaint)

        val label = "Calibration Mode: Drag 8 handles to align table border"
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

    /**
     * Requirement 2: Render 8 draggable calibration handles (4 corners + 4 edge centers).
     */
    private fun drawEightCalibrationHandles(canvas: Canvas) {
        val cx = boundaryBox.centerX()
        val cy = boundaryBox.centerY()

        // 4 Corners
        drawHandlePin(canvas, boundaryBox.left, boundaryBox.top, cornerHandleRadius)
        drawHandlePin(canvas, boundaryBox.right, boundaryBox.top, cornerHandleRadius)
        drawHandlePin(canvas, boundaryBox.left, boundaryBox.bottom, cornerHandleRadius)
        drawHandlePin(canvas, boundaryBox.right, boundaryBox.bottom, cornerHandleRadius)

        // 4 Edge Centers
        drawHandlePin(canvas, cx, boundaryBox.top, edgeHandleRadius)
        drawHandlePin(canvas, cx, boundaryBox.bottom, edgeHandleRadius)
        drawHandlePin(canvas, boundaryBox.left, cy, edgeHandleRadius)
        drawHandlePin(canvas, boundaryBox.right, cy, edgeHandleRadius)
    }

    private fun drawHandlePin(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        canvas.drawCircle(cx, cy, radius, handleOuterPaint)
        canvas.drawCircle(cx, cy, radius, handleStrokePaint)
        canvas.drawCircle(cx, cy, 4f * density, handleCenterPaint)
    }

    private fun drawAutoReflection(canvas: Canvas) {
        val reflection = GeometryUtils.computeReflection(pointA, pointB, boundaryBox)

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
        val reflection = GeometryUtils.computeReflection(pointA, pointB, boundaryBox)

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
        val badgeRect = RectF(
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
                    val screenW = width.toFloat()
                    val screenH = height.toFloat()

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
