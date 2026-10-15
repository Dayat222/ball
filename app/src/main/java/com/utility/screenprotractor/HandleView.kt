package com.utility.screenprotractor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

/**
 * Minimalist independent floating handle view (42dp x 42dp touch target).
 * Features a clean crosshair plus (+) sign in the center without circles or labels,
 * with full alpha transparency synchronization.
 */
class HandleView(
    context: Context,
    val label: String = ""
) : View(context) {

    private val density = resources.displayMetrics.density

    // Paint: Center Plus (+) Crosshair
    private val plusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        strokeCap = Paint.Cap.ROUND
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = (42f * density).toInt()
        setMeasuredDimension(size, size)
    }

    /**
     * Update crosshair color (e.g. matching primary line color or white)
     */
    fun updateColor(color: Int) {
        val currentAlpha = plusPaint.alpha
        plusPaint.color = color
        plusPaint.alpha = currentAlpha
        invalidate()
    }

    /**
     * Synchronize handle alpha with line opacity (25 - 255) directly via Paint alpha
     * without creating expensive GPU offscreen alpha layers for the window.
     */
    fun updateHandleAlpha(alphaInt: Int) {
        plusPaint.alpha = alphaInt.coerceIn(25, 255)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cx = width / 2f
        val cy = height / 2f

        // Two intersecting lines forming a clean '+' crosshair (total length 20dp)
        val armLength = 10f * density
        canvas.drawLine(cx - armLength, cy, cx + armLength, cy, plusPaint)
        canvas.drawLine(cx, cy - armLength, cx, cy + armLength, plusPaint)
    }
}
