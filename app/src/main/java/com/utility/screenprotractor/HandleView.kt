package com.utility.screenprotractor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.View

/**
 * Independent floating handle view (42dp x 42dp) with precision crosshair,
 * center dot, outer touch indicator ring, and point label.
 */
class HandleView(
    context: Context,
    val label: String
) : View(context) {

    private val density = resources.displayMetrics.density

    // Paint: Outer Touch Area (Translucent Cyan)
    private val outerFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4D00E5FF") // 30% alpha cyan
        style = Paint.Style.FILL
    }

    private val outerStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CC00E5FF") // 80% alpha cyan
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }

    // Paint: Center Dot & Crosshair
    private val crosshairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        strokeCap = Paint.Cap.ROUND
    }

    private val centerDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    // Paint: Point Label ("A", "B", "C")
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 10f * density
        isFakeBoldText = true
        setShadowLayer(3f, 0f, 0f, Color.BLACK)
    }

    private val textBounds = Rect()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = (42f * density).toInt()
        setMeasuredDimension(size, size)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cx = width / 2f
        val cy = height / 2f
        val radius = (width / 2f) - (2f * density)

        // 1. Outer Circle Fill & Stroke
        canvas.drawCircle(cx, cy, radius, outerFillPaint)
        canvas.drawCircle(cx, cy, radius, outerStrokePaint)

        // 2. Crosshair Lines
        val armLength = 8f * density
        canvas.drawLine(cx - armLength, cy, cx + armLength, cy, crosshairPaint)
        canvas.drawLine(cx, cy - armLength, cx, cy + armLength, crosshairPaint)

        // 3. Center Precision Dot
        canvas.drawCircle(cx, cy, 3f * density, centerDotPaint)

        // 4. Label Badge (e.g. "A", "B", "C")
        labelPaint.getTextBounds(label, 0, label.length, textBounds)
        val labelX = width - textBounds.width() - (5f * density)
        val labelY = textBounds.height() + (5f * density)
        canvas.drawText(label, labelX, labelY, labelPaint)
    }
}
