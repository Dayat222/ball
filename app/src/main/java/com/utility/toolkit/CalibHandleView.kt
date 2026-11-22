package com.utility.toolkit

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

/**
 * Lightweight independent floating handle view for interactive Table Boundary Calibration.
 * Each handle runs in its own tiny WindowManager overlay window (never blocking the full screen
 * or the Save toolbar button).
 */
class CalibHandleView(
    context: Context,
    val target: CanvasOverlayView.CalibTarget
) : View(context) {

    private val density = resources.displayMetrics.density

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun getAccessibilityClassName(): CharSequence = "android.view.View"

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (target == CanvasOverlayView.CalibTarget.CENTER_MOVE) {
            Color.parseColor("#CC00E5FF")
        } else if (target.isCorner) {
            Color.parseColor("#E6FFD600")
        } else {
            Color.parseColor("#CCFFE082")
        }
        style = Paint.Style.FILL
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (target == CanvasOverlayView.CalibTarget.CENTER_MOVE) {
            Color.WHITE
        } else {
            Color.parseColor("#FF6F00")
        }
        style = Paint.Style.STROKE
        strokeWidth = 2.0f * density
    }

    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF121212")
        style = Paint.Style.STROKE
        strokeWidth = 2.2f * density
        strokeCap = Paint.Cap.ROUND
    }

    private val centerDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val sizeDp = if (target == CanvasOverlayView.CalibTarget.CENTER_MOVE) 44f else 40f
        val size = (sizeDp * density).toInt()
        setMeasuredDimension(size, size)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f

        val radius = when {
            target == CanvasOverlayView.CalibTarget.CENTER_MOVE -> 16f * density
            target.isCorner -> 13f * density
            else -> 11f * density
        }

        canvas.drawCircle(cx, cy, radius, fillPaint)
        canvas.drawCircle(cx, cy, radius, borderPaint)

        when (target) {
            CanvasOverlayView.CalibTarget.CENTER_MOVE -> {
                // Draw 4-way move arrows '+'
                val arm = 8.5f * density
                val arrow = 3.0f * density
                canvas.drawLine(cx - arm, cy, cx + arm, cy, iconPaint)
                canvas.drawLine(cx, cy - arm, cx, cy + arm, iconPaint)
                // Arrowheads
                canvas.drawLine(cx - arm, cy, cx - arm + arrow, cy - arrow, iconPaint)
                canvas.drawLine(cx - arm, cy, cx - arm + arrow, cy + arrow, iconPaint)
                canvas.drawLine(cx + arm, cy, cx + arm - arrow, cy - arrow, iconPaint)
                canvas.drawLine(cx + arm, cy, cx + arm - arrow, cy + arrow, iconPaint)
                canvas.drawLine(cx, cy - arm, cx - arrow, cy - arm + arrow, iconPaint)
                canvas.drawLine(cx, cy - arm, cx + arrow, cy - arm + arrow, iconPaint)
                canvas.drawLine(cx, cy + arm, cx - arrow, cy + arm - arrow, iconPaint)
                canvas.drawLine(cx, cy + arm, cx + arrow, cy + arm - arrow, iconPaint)
            }
            CanvasOverlayView.CalibTarget.CORNER_TL -> drawCornerBracket(canvas, cx, cy, 1f, 1f)
            CanvasOverlayView.CalibTarget.CORNER_TR -> drawCornerBracket(canvas, cx, cy, -1f, 1f)
            CanvasOverlayView.CalibTarget.CORNER_BL -> drawCornerBracket(canvas, cx, cy, 1f, -1f)
            CanvasOverlayView.CalibTarget.CORNER_BR -> drawCornerBracket(canvas, cx, cy, -1f, -1f)
            CanvasOverlayView.CalibTarget.EDGE_LEFT,
            CanvasOverlayView.CalibTarget.EDGE_RIGHT -> {
                val h = 6f * density
                canvas.drawLine(cx, cy - h, cx, cy + h, iconPaint)
                canvas.drawCircle(cx, cy, 2.2f * density, centerDotPaint)
            }
            CanvasOverlayView.CalibTarget.EDGE_TOP,
            CanvasOverlayView.CalibTarget.EDGE_BOTTOM -> {
                val w = 6f * density
                canvas.drawLine(cx - w, cy, cx + w, cy, iconPaint)
                canvas.drawCircle(cx, cy, 2.2f * density, centerDotPaint)
            }
        }
    }

    private fun drawCornerBracket(canvas: Canvas, cx: Float, cy: Float, dxSign: Float, dySign: Float) {
        val len = 6.5f * density
        val off = 2.5f * density
        val sx = cx - dxSign * off
        val sy = cy - dySign * off
        canvas.drawLine(sx, sy, sx + dxSign * len, sy, iconPaint)
        canvas.drawLine(sx, sy, sx, sy + dySign * len, iconPaint)
        canvas.drawCircle(cx, cy, 2f * density, centerDotPaint)
    }
}
