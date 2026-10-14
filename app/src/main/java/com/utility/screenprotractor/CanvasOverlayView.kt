package com.utility.screenprotractor

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import java.util.Locale

/**
 * High-performance full-screen transparent canvas rendering geometry lines,
 * touch handles, boundary box, and ray reflections.
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
        NONE, POINT_A, POINT_B, POINT_C,
        CORNER_TOP_LEFT, CORNER_TOP_RIGHT, CORNER_BOTTOM_LEFT, CORNER_BOTTOM_RIGHT
    }

    private val prefsManager = PrefsManager(context)

    // Current State
    var currentMode: RulerMode = RulerMode.AUTO_REFLECT
        private set
    var isLocked: Boolean = false
        private set
    var isCalibrating: Boolean = false
        private set

    // Geometry points
    private var pointA = PointF()
    private var pointB = PointF()
    private var pointC = PointF()
    private var boundaryBox = RectF()

    private var activeDragTarget = DragTarget.NONE
    private var touchOffset = PointF(0f, 0f)

    // Density scaled dimensions
    private val density = resources.displayMetrics.density
    private val handleTouchRadius = 45f * density
    private val handleInnerRadius = 5f * density
    private val crosshairLength = 14f * density
    private val edgeMarkerRadius = 8f * density // 16dp diameter
    private val cornerHandleRadius = 14f * density

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

    // Paint: Handle Touch Outer Circle
    private val handleTouchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4000E5FF")
        style = Paint.Style.FILL
    }

    // Paint: Handle Inner Dot & Crosshair
    private val handleCenterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private val handleCrosshairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
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
    private val textBounds = android.graphics.Rect()

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

    fun setLocked(locked: Boolean) {
        isLocked = locked
        if (locked) {
            isCalibrating = false
        }
        invalidate()
    }

    fun setCalibrating(calibrating: Boolean) {
        if (isLocked) return
        isCalibrating = calibrating
        if (!calibrating) {
            // Save boundary box whenever calibration is finished
            prefsManager.saveBoundaryBox(
                boundaryBox.left,
                boundaryBox.top,
                boundaryBox.right,
                boundaryBox.bottom
            )
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

        // 3. Draw touch handles if in EDIT mode (not locked)
        if (!isLocked) {
            drawTouchHandles(canvas)
            if (isCalibrating) {
                drawCalibrationHandles(canvas)
            }
        }
    }

    private fun drawBoundaryBox(canvas: Canvas) {
        val paint = if (isCalibrating) boundaryCalibPaint else boundaryBoxPaint
        canvas.drawRect(boundaryBox, paint)

        if (isCalibrating) {
            val label = "Calibration Mode: Drag corners to adjust boundary"
            textPaint.getTextBounds(label, 0, label.length, textBounds)
            val textX = boundaryBox.left + 16f * density
            val textY = boundaryBox.top + 24f * density
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
            if (!isLocked) {
                // When unlocked, show subtle guide lines so the user can still aim
                linePath.reset()
                linePath.moveTo(pointA.x, pointA.y)
                linePath.lineTo(reflection.hitPoint.x, reflection.hitPoint.y)
                canvas.drawPath(linePath, primaryLinePaint)
            }

            // In Locked mode, only the edge marker dot (16dp) appears at the boundary intersection!
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

            // Also draw marker at reflected destination hit point if on edge
            canvas.drawCircle(
                reflection.reflectedPoint.x,
                reflection.reflectedPoint.y,
                edgeMarkerRadius * 0.75f,
                edgeMarkerPaint
            )
        }
    }

    private fun drawTouchHandles(canvas: Canvas) {
        // Handle A (Start)
        drawHandle(canvas, pointA, "A")

        // Handle B (Aim / Pivot)
        drawHandle(canvas, pointB, "B")

        // Handle C (Only in Manual 3-Point Mode)
        if (currentMode == RulerMode.MANUAL_3_POINT) {
            drawHandle(canvas, pointC, "C")
        }
    }

    private fun drawHandle(canvas: Canvas, point: PointF, label: String) {
        // Outer translucent touch area
        canvas.drawCircle(point.x, point.y, handleTouchRadius, handleTouchPaint)

        // Crosshair lines
        canvas.drawLine(
            point.x - crosshairLength,
            point.y,
            point.x + crosshairLength,
            point.y,
            handleCrosshairPaint
        )
        canvas.drawLine(
            point.x,
            point.y - crosshairLength,
            point.x,
            point.y + crosshairLength,
            handleCrosshairPaint
        )

        // Center dot
        canvas.drawCircle(point.x, point.y, handleInnerRadius, handleCenterPaint)

        // Label
        canvas.drawText(
            label,
            point.x + handleInnerRadius + 8f * density,
            point.y - handleInnerRadius - 4f * density,
            textPaint
        )
    }

    private fun drawCalibrationHandles(canvas: Canvas) {
        drawCornerPin(canvas, boundaryBox.left, boundaryBox.top)
        drawCornerPin(canvas, boundaryBox.right, boundaryBox.top)
        drawCornerPin(canvas, boundaryBox.left, boundaryBox.bottom)
        drawCornerPin(canvas, boundaryBox.right, boundaryBox.bottom)
    }

    private fun drawCornerPin(canvas: Canvas, x: Float, y: Float) {
        canvas.drawCircle(x, y, cornerHandleRadius, boundaryCalibPaint)
        canvas.drawCircle(x, y, 4f * density, handleCenterPaint)
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

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        // If locked, touches pass through (this view shouldn't receive touch due to FLAG_NOT_TOUCHABLE,
        // but return false as failsafe)
        if (isLocked) {
            return false
        }

        val touchX = event.x
        val touchY = event.y

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activeDragTarget = findTouchedTarget(touchX, touchY)
                if (activeDragTarget != DragTarget.NONE) {
                    val targetPos = getTargetPosition(activeDragTarget)
                    touchOffset.set(touchX - targetPos.x, touchY - targetPos.y)
                    return true
                }
                return false
            }

            MotionEvent.ACTION_MOVE -> {
                if (activeDragTarget != DragTarget.NONE) {
                    val newX = (touchX - touchOffset.x).coerceIn(0f, width.toFloat())
                    val newY = (touchY - touchOffset.y).coerceIn(0f, height.toFloat())
                    updateTargetPosition(activeDragTarget, newX, newY)
                    invalidate()
                    return true
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (activeDragTarget != DragTarget.NONE) {
                    activeDragTarget = DragTarget.NONE
                    // Persist positions
                    prefsManager.savePoints(pointA, pointB, pointC)
                    if (isCalibrating) {
                        prefsManager.saveBoundaryBox(
                            boundaryBox.left,
                            boundaryBox.top,
                            boundaryBox.right,
                            boundaryBox.bottom
                        )
                    }
                    invalidate()
                    return true
                }
            }
        }

        return super.onTouchEvent(event)
    }

    private fun findTouchedTarget(x: Float, y: Float): DragTarget {
        // If calibrating, prioritize boundary corners
        if (isCalibrating) {
            val cornerThreshold = 40f * density
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
        }

        // Check geometry points with generous touch target
        val touchThreshold = handleTouchRadius * 1.3f
        if (GeometryUtils.distance(x, y, pointA.x, pointA.y) <= touchThreshold) {
            return DragTarget.POINT_A
        }
        if (GeometryUtils.distance(x, y, pointB.x, pointB.y) <= touchThreshold) {
            return DragTarget.POINT_B
        }
        if (currentMode == RulerMode.MANUAL_3_POINT &&
            GeometryUtils.distance(x, y, pointC.x, pointC.y) <= touchThreshold
        ) {
            return DragTarget.POINT_C
        }

        return DragTarget.NONE
    }

    private fun getTargetPosition(target: DragTarget): PointF {
        return when (target) {
            DragTarget.POINT_A -> pointA
            DragTarget.POINT_B -> pointB
            DragTarget.POINT_C -> pointC
            DragTarget.CORNER_TOP_LEFT -> PointF(boundaryBox.left, boundaryBox.top)
            DragTarget.CORNER_TOP_RIGHT -> PointF(boundaryBox.right, boundaryBox.top)
            DragTarget.CORNER_BOTTOM_LEFT -> PointF(boundaryBox.left, boundaryBox.bottom)
            DragTarget.CORNER_BOTTOM_RIGHT -> PointF(boundaryBox.right, boundaryBox.bottom)
            DragTarget.NONE -> PointF(0f, 0f)
        }
    }

    private fun updateTargetPosition(target: DragTarget, x: Float, y: Float) {
        val minBoxSize = 60f * density
        when (target) {
            DragTarget.POINT_A -> pointA.set(x, y)
            DragTarget.POINT_B -> pointB.set(x, y)
            DragTarget.POINT_C -> pointC.set(x, y)
            DragTarget.CORNER_TOP_LEFT -> {
                boundaryBox.left = x.coerceAtMost(boundaryBox.right - minBoxSize)
                boundaryBox.top = y.coerceAtMost(boundaryBox.bottom - minBoxSize)
            }
            DragTarget.CORNER_TOP_RIGHT -> {
                boundaryBox.right = x.coerceAtLeast(boundaryBox.left + minBoxSize)
                boundaryBox.top = y.coerceAtMost(boundaryBox.bottom - minBoxSize)
            }
            DragTarget.CORNER_BOTTOM_LEFT -> {
                boundaryBox.left = x.coerceAtMost(boundaryBox.right - minBoxSize)
                boundaryBox.bottom = y.coerceAtLeast(boundaryBox.top + minBoxSize)
            }
            DragTarget.CORNER_BOTTOM_RIGHT -> {
                boundaryBox.right = x.coerceAtLeast(boundaryBox.left + minBoxSize)
                boundaryBox.bottom = y.coerceAtLeast(boundaryBox.top + minBoxSize)
            }
            DragTarget.NONE -> Unit
        }
    }
}
