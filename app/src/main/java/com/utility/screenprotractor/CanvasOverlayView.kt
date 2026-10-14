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

/**
 * High-performance full-screen transparent canvas rendering geometry lines,
 * reflection rays, and boundary boxes.
 *
 * NOTE: When isCalibrating == false, this view is pass-through (FLAG_NOT_TOUCHABLE).
 * When isCalibrating == true, OverlayService enables touch on this window so the user
 * can drag the 4 corner handles and 4 edges to calibrate the boundary box.
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
        EDGE_LEFT, EDGE_TOP, EDGE_RIGHT, EDGE_BOTTOM
    }

    private val prefsManager = PrefsManager(context)

    // Current State
    var currentMode: RulerMode = RulerMode.AUTO_REFLECT
        private set
    var isCalibrating: Boolean = false
        private set

    // Geometry points (driven by HandleViews)
    var pointA = PointF()
        private set
    var pointB = PointF()
        private set
    var pointC = PointF()
        private set
    var boundaryBox = RectF()
        private set

    // Touch Dragging State for Calibration
    private var activeDragTarget = DragTarget.NONE
    private var touchOffset = PointF(0f, 0f)

    // Density scaled dimensions
    private val density = resources.displayMetrics.density
    private val edgeMarkerRadius = 8f * density // 16dp diameter
    private val cornerHandleRadius = 18f * density

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

    // Paint: Boundary Box
    private val boundaryBoxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#80FFFFFF")
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }

    // Paint: Boundary Box Active Calibration Highlight
    private val boundaryCalibPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFE082")
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
    }

    // Paint: Corner Handles in Calibration Mode
    private val cornerOuterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E6FFE082")
        style = Paint.Style.FILL
    }

    private val cornerStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFB300")
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }

    private val cornerCenterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
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

        // Load saved boundary box or compute defaults
        boundaryBox = prefsManager.getBoundaryBox(screenW, screenH)

        // Load saved points or compute defaults
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
    }

    /**
     * Requirement 2: Dynamically adjust line thickness and opacity.
     */
    fun updateLineStyle(strokeWidth: Float, alpha: Int) {
        val clampedAlpha = alpha.coerceIn(25, 255)
        val strokePx = strokeWidth * density

        primaryLinePaint.strokeWidth = strokePx
        primaryLinePaint.alpha = clampedAlpha

        reflectedLinePaint.strokeWidth = strokePx
        reflectedLinePaint.alpha = clampedAlpha

        invalidate()
    }

    fun setPointA(x: Float, y: Float) {
        pointA.set(x, y)
        invalidate()
    }

    fun setPointB(x: Float, y: Float) {
        pointB.set(x, y)
        invalidate()
    }

    fun setPointC(x: Float, y: Float) {
        pointC.set(x, y)
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
            // Save when calibration finishes
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
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 1. Draw Boundary Box
        drawBoundaryBox(canvas)

        // 2. Render geometry lines and markers according to mode
        when (currentMode) {
            RulerMode.AUTO_REFLECT -> drawAutoReflection(canvas)
            RulerMode.MANUAL_3_POINT -> drawManualThreePoint(canvas)
            RulerMode.EDGE_MARKER_ONLY -> drawEdgeMarkerOnly(canvas)
        }

        // 3. Render 4 draggable Corner Handles in calibration mode
        if (isCalibrating) {
            drawCornerHandles(canvas)
        }
    }

    private fun drawBoundaryBox(canvas: Canvas) {
        val paint = if (isCalibrating) boundaryCalibPaint else boundaryBoxPaint
        canvas.drawRect(boundaryBox, paint)

        if (isCalibrating) {
            val label = "Calibration Mode: Drag corners or edges to align with game border"
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
    }

    private fun drawCornerHandles(canvas: Canvas) {
        drawCornerPin(canvas, boundaryBox.left, boundaryBox.top)
        drawCornerPin(canvas, boundaryBox.right, boundaryBox.top)
        drawCornerPin(canvas, boundaryBox.left, boundaryBox.bottom)
        drawCornerPin(canvas, boundaryBox.right, boundaryBox.bottom)
    }

    private fun drawCornerPin(canvas: Canvas, cx: Float, cy: Float) {
        canvas.drawCircle(cx, cy, cornerHandleRadius, cornerOuterPaint)
        canvas.drawCircle(cx, cy, cornerHandleRadius, cornerStrokePaint)
        canvas.drawCircle(cx, cy, 5f * density, cornerCenterPaint)
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

            // Draw small collision ring at hit point
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

            // Draw incident angle text tag near reflection point
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
            // Edge marker dot (16dp) at boundary hit point
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

            // Marker at secondary bounce point
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
     * Requirement 1: Allow dragging the 4 corners or edges of the boundary box when calibrating.
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
                        DragTarget.EDGE_LEFT -> {
                            boundaryBox.left = curX.coerceAtMost(boundaryBox.right - minSize)
                        }
                        DragTarget.EDGE_RIGHT -> {
                            boundaryBox.right = curX.coerceAtLeast(boundaryBox.left + minSize)
                        }
                        DragTarget.EDGE_TOP -> {
                            boundaryBox.top = curY.coerceAtMost(boundaryBox.bottom - minSize)
                        }
                        DragTarget.EDGE_BOTTOM -> {
                            boundaryBox.bottom = curY.coerceAtLeast(boundaryBox.top + minSize)
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
        val edgeThreshold = 24f * density

        // Check Corners first
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

        // Check Edges
        if (Math.abs(x - boundaryBox.left) <= edgeThreshold && y >= boundaryBox.top && y <= boundaryBox.bottom) {
            return DragTarget.EDGE_LEFT
        }
        if (Math.abs(x - boundaryBox.right) <= edgeThreshold && y >= boundaryBox.top && y <= boundaryBox.bottom) {
            return DragTarget.EDGE_RIGHT
        }
        if (Math.abs(y - boundaryBox.top) <= edgeThreshold && x >= boundaryBox.left && x <= boundaryBox.right) {
            return DragTarget.EDGE_TOP
        }
        if (Math.abs(y - boundaryBox.bottom) <= edgeThreshold && x >= boundaryBox.left && x <= boundaryBox.right) {
            return DragTarget.EDGE_BOTTOM
        }

        return DragTarget.NONE
    }

    private fun getTargetPos(target: DragTarget): PointF {
        return when (target) {
            DragTarget.CORNER_TOP_LEFT -> PointF(boundaryBox.left, boundaryBox.top)
            DragTarget.CORNER_TOP_RIGHT -> PointF(boundaryBox.right, boundaryBox.top)
            DragTarget.CORNER_BOTTOM_LEFT -> PointF(boundaryBox.left, boundaryBox.bottom)
            DragTarget.CORNER_BOTTOM_RIGHT -> PointF(boundaryBox.right, boundaryBox.bottom)
            DragTarget.EDGE_LEFT -> PointF(boundaryBox.left, (boundaryBox.top + boundaryBox.bottom) / 2f)
            DragTarget.EDGE_RIGHT -> PointF(boundaryBox.right, (boundaryBox.top + boundaryBox.bottom) / 2f)
            DragTarget.EDGE_TOP -> PointF((boundaryBox.left + boundaryBox.right) / 2f, boundaryBox.top)
            DragTarget.EDGE_BOTTOM -> PointF((boundaryBox.left + boundaryBox.right) / 2f, boundaryBox.bottom)
            DragTarget.NONE -> PointF(0f, 0f)
        }
    }
}
