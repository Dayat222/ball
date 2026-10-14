package com.utility.screenprotractor

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
import android.view.View
import java.util.Locale

/**
 * High-performance full-screen transparent canvas rendering geometry lines,
 * reflection rays, and boundary boxes.
 *
 * NOTE: This view is permanently pass-through (FLAG_NOT_TOUCHABLE) and pure onDraw,
 * coordinate inputs are supplied directly by independent HandleViews.
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

    // Margin preset cycle for calibration
    private val marginPresets = floatArrayOf(0.04f, 0.08f, 0.12f, 0.16f)
    private var currentMarginIndex = 1

    // Density scaled dimensions
    private val density = resources.displayMetrics.density
    private val edgeMarkerRadius = 8f * density // 16dp diameter

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
        invalidate()
    }

    /**
     * Cycles through preset boundary margins (4%, 8%, 12%, 16%) for quick calibration
     * without needing touch interception on the canvas.
     */
    fun cycleBoundaryPreset(): String {
        currentMarginIndex = (currentMarginIndex + 1) % marginPresets.size
        val ratio = marginPresets[currentMarginIndex]
        val screenW = width.toFloat().coerceAtLeast(100f)
        val screenH = height.toFloat().coerceAtLeast(100f)

        val insetX = screenW * ratio
        val insetY = screenH * ratio
        boundaryBox.set(insetX, insetY, screenW - insetX, screenH - insetY)
        prefsManager.saveBoundaryBox(boundaryBox.left, boundaryBox.top, boundaryBox.right, boundaryBox.bottom)
        invalidate()
        return "${(ratio * 100).toInt()}%"
    }

    fun resetBoundaryCalibration() {
        val screenW = width.toFloat().coerceAtLeast(100f)
        val screenH = height.toFloat().coerceAtLeast(100f)
        prefsManager.resetBoundaryBox()
        boundaryBox = prefsManager.getBoundaryBox(screenW, screenH)
        currentMarginIndex = 1
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
    }

    private fun drawBoundaryBox(canvas: Canvas) {
        val paint = if (isCalibrating) boundaryCalibPaint else boundaryBoxPaint
        canvas.drawRect(boundaryBox, paint)

        if (isCalibrating) {
            val label = "Boundary Margin: ${(marginPresets[currentMarginIndex] * 100).toInt()}% (Tap Calib to cycle)"
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
}
