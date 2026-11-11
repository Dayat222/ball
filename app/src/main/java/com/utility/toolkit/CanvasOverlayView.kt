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

    /** Which dimension drives the 2:1 lock during calibration. */
    enum class Axis { WIDTH, HEIGHT }

    private val prefsManager = PrefsManager(context)

    // Calibration
    var isCalibrating: Boolean = false
        private set

    // Target Ring Radius & Bank Ray Toggle
    var showBankGuides: Boolean = true
        private set

    var isFullLengthGuides: Boolean = false
        private set

    var ballScaleCorrection: Float = 1.0f
        private set

    /** Pocket-mouth tuning against the hard-coded 9 ft table. 1.0 = real. */
    var jawScale: Float = 1.0f
        private set
    var mouthScale: Float = 1.0f
        private set

    /** Ghost-ball marker for the white ball's required contact position. */
    var showGhostBall: Boolean = true
        private set

    /** Cue-ball & Spin/Deflection guide mode (Handle C + tangent/spin curve). */
    var showCueMode: Boolean = false
        private set

    /** Obstacle / Blocker Ball mode (Handle O + ray collision masking). */
    var showObstacle: Boolean = false
        private set

    /** 3-Cushion (Triple Bank) guide toggle. */
    var showTripleBank: Boolean = false
        private set

    /** Horizontal sidespin [-1..1] (-1 = Left English, +1 = Right English). */
    var spinX: Float = 0f
        private set

    /** Vertical spin [-1..1] (-1 = Backspin/Draw, 0 = Stun, +1 = Topspin/Follow). */
    var spinY: Float = 0f
        private set

    /** Vision-detected ball candidates on the table (when Auto-Vision is active). */
    var detectedBalls: List<BallVisionDetector.DetectedBall> = emptyList()
        private set

    /**
     * Ball radius in px, derived from the calibrated table scale and the
     * user's fine-tune. Recomputed from the current boundary box so callers
     * never read a stale value before the first frame is drawn.
     */
    val ballRadiusPx: Float
        get() {
            aim.configure(
                boundaryBox.left, boundaryBox.top,
                boundaryBox.right, boundaryBox.bottom,
                ballScaleCorrection
            )
            return aim.ballRadius.toFloat()
        }

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

    // Cue ball coordinate and its offset finger grip (Handle C)
    var pointC = PointF()
        private set
    private var gripC = PointF()
    private var hasGripC = false

    // Obstacle / Blocker ball coordinate and its offset finger grip (Handle O)
    var pointO = PointF()
        private set
    private var gripO = PointF()
    private var hasGripO = false

    var boundaryBox = RectF()
        private set

    // Touch Dragging State for Calibration
    private var activeDragTarget = DragTarget.NONE
    private var touchOffset = PointF(0f, 0f)

    // Density scaled dimensions
    private val density = resources.displayMetrics.density
    private val edgeHandleRadius = 14f * density

    /** Playing-surface aspect: 1270 / 2540 mm. Height is half the width. */
    private val TABLE_HEIGHT_PER_WIDTH = 0.5f

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

    // Feasibility paints: a shot the mouth cannot accept is drawn dim, so a
    // guide that looks available is one that can actually drop.
    private val infeasibleLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        strokeCap = Paint.Cap.ROUND
        color = Color.argb(90, 255, 64, 64)
    }

    // Ghost-ball marker: white ball centre at the moment of contact.
    // Styled as a dashed translucent white ring to clearly distinguish from solid target ball
    private val ghostRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.8f * density
        color = Color.argb(220, 255, 255, 255)
        pathEffect = DashPathEffect(floatArrayOf(6f * density, 4f * density), 0f)
    }

    private val ghostShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.2f * density
        color = Color.argb(160, 0, 0, 0)
        pathEffect = DashPathEffect(floatArrayOf(6f * density, 4f * density), 0f)
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

    // Crosshair alignment reticle for precision concentric centering on target ball
    private val aimCrosshairShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb((220 * 0.85f).roundToInt(), 0, 0, 0)
        strokeWidth = 2.4f * density
        strokeCap = Paint.Cap.ROUND
    }

    private val aimCrosshairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(220, 255, 255, 255)
        strokeWidth = 1.2f * density
        strokeCap = Paint.Cap.ROUND
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
    private val deflectionPath = Path()

    // Paint: Cue Ball (Point C) Ring & Incoming Line
    private val cueRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#00E676")
        strokeWidth = 1.6f * density
    }

    private val cueIncomingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#B300E676")
        strokeWidth = 2.0f * density
        strokeCap = Paint.Cap.ROUND
        pathEffect = DashPathEffect(floatArrayOf(8f * density, 5f * density), 0f)
    }

    // Paint: 90-degree Stun Tangent Line (dotted reference)
    private val stunTangentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#99FFFFFF")
        strokeWidth = 1.6f * density
        strokeCap = Paint.Cap.ROUND
        pathEffect = DashPathEffect(floatArrayOf(4f * density, 5f * density), 0f)
    }

    // Paint: Post-Impact Spin Curve (Follow / Draw deflection trajectory)
    private val spinCurvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#00E676")
        strokeWidth = 2.8f * density
        strokeCap = Paint.Cap.ROUND
    }

    // Paint: Obstacle / Blocker Ball (Point O) Ring & Forbidden Zone
    private val obstacleRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#FF1744")
        strokeWidth = 2.0f * density
    }

    private val obstacleCollisionZonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#66FF1744")
        strokeWidth = 1.2f * density
        pathEffect = DashPathEffect(floatArrayOf(5f * density, 5f * density), 0f)
    }

    // Paint: Blocked / Obstructed Ray (dimmed red dashed line)
    private val blockedRayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#88FF1744")
        strokeWidth = 2.0f * density
        strokeCap = Paint.Cap.ROUND
        pathEffect = DashPathEffect(floatArrayOf(4f * density, 6f * density), 0f)
    }

    // Paint: 3-Cushion (Triple Bank) Ray
    private val tripleBankPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#E040FB")
        strokeWidth = 2.6f * density
        strokeCap = Paint.Cap.ROUND
        pathEffect = DashPathEffect(floatArrayOf(12f * density, 5f * density, 3f * density, 5f * density), 0f)
    }

    private val tripleBankDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#E040FB")
    }

    // Paint: Vision Auto-Detected Ball Candidate Rings
    private val visionCandidatePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#8800E5FF")
        strokeWidth = 1.2f * density
        pathEffect = DashPathEffect(floatArrayOf(3f * density, 4f * density), 0f)
    }

    private val visionCueCandidatePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#AA00E676")
        strokeWidth = 1.3f * density
        pathEffect = DashPathEffect(floatArrayOf(4f * density, 3f * density), 0f)
    }

    init {
        // Stealth: keep the overlay out of the accessibility tree. Another app
        // holding AccessibilityService can enumerate overlay windows and would
        // otherwise read this package/class name straight off the node.
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO

        val landSize = getLandscapeScreenSize()
        boundaryBox = prefsManager.getBoundaryBox(landSize.x, landSize.y)
        clampBoundaryBox(landSize.x, landSize.y)

        pointB = prefsManager.getTargetPoint(landSize.x, landSize.y)
        pointC = prefsManager.getCuePoint(landSize.x, landSize.y)
        pointO = prefsManager.getObstaclePoint(landSize.x, landSize.y)

        currentLineColor = prefsManager.getLineColor()
        currentBankLineColor = prefsManager.getSecondaryRayColor()
        currentAlpha = prefsManager.getLineAlpha()
        currentStrokeWidth = prefsManager.getLineStrokeWidth()
        updateLineColor(currentLineColor)
        updateBankLineColor(currentBankLineColor)
        updateLineStyle(currentStrokeWidth, currentAlpha)

        showBankGuides = prefsManager.getShowBankGuides()
        isFullLengthGuides = prefsManager.isFullLengthGuides()
        ballScaleCorrection = prefsManager.getBallScaleCorrection()
        bankRebound = prefsManager.getBankRebound()
        jawScale = prefsManager.getJawScale()
        mouthScale = prefsManager.getMouthScale()
        showGhostBall = prefsManager.getShowGhostBall()
        showCueMode = prefsManager.getShowCueMode()
        showObstacle = prefsManager.getShowObstacle()
        showTripleBank = prefsManager.getShowTripleBank()
        spinX = prefsManager.getSpinX()
        spinY = prefsManager.getSpinY()
    }

    override fun getAccessibilityClassName(): CharSequence = "android.view.View"

    /**
     * A real playing surface is 2:1, so the calibration rectangle must be too.
     * When both axis scales match, the jaw geometry and the ball radius share
     * one scale and the whole millimetre model is exact. [lockAxis] decides
     * which dimension drives the other:
     *   WIDTH  — height follows width (better for hairline finger drags on the
     *            side rails, which are the dominant calibration gesture),
     *   HEIGHT — width follows height.
     */
    fun enforceTableRatio(w: Float, h: Float, lockAxis: Axis = Axis.WIDTH) {
        if (boundaryBox.width() <= 1f || boundaryBox.height() <= 1f) return
        when (lockAxis) {
            Axis.WIDTH -> {
                val ratioH = boundaryBox.width() * TABLE_HEIGHT_PER_WIDTH
                val cy = boundaryBox.centerY()
                boundaryBox.top = cy - ratioH / 2f
                boundaryBox.bottom = cy + ratioH / 2f
            }
            Axis.HEIGHT -> {
                val ratioW = boundaryBox.height() / TABLE_HEIGHT_PER_WIDTH
                val cx = boundaryBox.centerX()
                boundaryBox.left = cx - ratioW / 2f
                boundaryBox.right = cx + ratioW / 2f
            }
        }
        clampBoundaryBox(w, h)
        invalidate()
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
        aimCrosshairShadowPaint.color = Color.argb(shadowAlpha, 0, 0, 0)
        aimCrosshairPaint.color = Color.argb(currentAlpha, 255, 255, 255)
        ghostShadowPaint.color = Color.argb(shadowAlpha, 0, 0, 0)
        ghostRingPaint.color = Color.argb(currentAlpha, 255, 255, 255)

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

    /**
     * Fine-tune of the ball radius that is derived from the table scale.
     * 1.0 = a real 57.15 mm ball on a correctly calibrated table.
     */
    fun setBallScaleCorrection(correction: Float) {
        ballScaleCorrection = correction.coerceIn(0.5f, 1.6f)
        prefsManager.saveBallScaleCorrection(ballScaleCorrection)
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

    /** Pocket-mouth tuning; both are multipliers on the real-table constants. */
    fun setJawScale(value: Float) {
        jawScale = value.coerceIn(0.5f, 2.0f)
        prefsManager.saveJawScale(jawScale)
        invalidate()
    }

    fun setMouthScale(value: Float) {
        mouthScale = value.coerceIn(0.5f, 2.0f)
        prefsManager.saveMouthScale(mouthScale)
        invalidate()
    }

    fun setShowGhostBall(show: Boolean) {
        showGhostBall = show
        prefsManager.saveShowGhostBall(show)
        invalidate()
    }

    fun setShowCueMode(show: Boolean) {
        showCueMode = show
        prefsManager.saveShowCueMode(show)
        invalidate()
    }

    fun setShowObstacle(show: Boolean) {
        showObstacle = show
        prefsManager.saveShowObstacle(show)
        invalidate()
    }

    fun setShowTripleBank(show: Boolean) {
        showTripleBank = show
        prefsManager.saveShowTripleBank(show)
        invalidate()
    }

    fun setSpinX(value: Float) {
        spinX = value.coerceIn(-1f, 1f)
        prefsManager.saveSpinX(spinX)
        invalidate()
    }

    fun setSpinY(value: Float) {
        spinY = value.coerceIn(-1f, 1f)
        prefsManager.saveSpinY(spinY)
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

    fun setPointO(aimX: Float, aimY: Float, gripX: Float = aimX, gripY: Float = aimY) {
        pointO.set(aimX, aimY)
        gripO.set(gripX, gripY)
        hasGripO = (GeometryUtils.distance(aimX, aimY, gripX, gripY) > 5f)
        invalidate()
    }

    fun setDetectedBalls(balls: List<BallVisionDetector.DetectedBall>) {
        detectedBalls = balls
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
            // Single/Multi-point target mode: stem tethers + rings + 6 direct rays + bank rays + spin curves
            if (hasGripB) {
                canvas.drawLine(gripB.x, gripB.y, pointB.x, pointB.y, stemLinePaint)
            }
            if (showCueMode && hasGripC) {
                canvas.drawLine(gripC.x, gripC.y, pointC.x, pointC.y, stemLinePaint)
            }
            if (showObstacle && hasGripO) {
                canvas.drawLine(gripO.x, gripO.y, pointO.x, pointO.y, stemLinePaint)
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

    // Corner pockets a two-cushion bank can reach from the opposite half.
    private val TOP_END_POCKETS = arrayOf(
        AimGeometry.Pocket.TOP_LEFT,
        AimGeometry.Pocket.TOP_RIGHT
    )
    private val BOTTOM_END_POCKETS = arrayOf(
        AimGeometry.Pocket.BOTTOM_LEFT,
        AimGeometry.Pocket.BOTTOM_RIGHT
    )

    /**
     * Determines the most natural / feasible pocket target for the current ball position.
     * Selects the feasible pocket with the shortest distance to the mouth.
     */
    private fun findBestPocket(bx: Float, by: Float): AimGeometry.Pocket? {
        var best: AimGeometry.Pocket? = null
        var bestDist = Double.MAX_VALUE
        for (pocket in allPockets) {
            aim.gate(pocket, bx.toDouble(), by.toDouble())
            if (!aim.gateFeasible) continue
            val d = hypot(aim.targetX - bx, aim.targetY - by)
            if (d < bestDist) {
                bestDist = d
                best = pocket
            }
        }
        return best
    }

    /**
     * One direct ray: ball centre -> gate point of the last solved pocket.
     * Short mode stops at 85dp, Full mode runs through the mouth.
     */
    private fun drawDirectRay(
        canvas: Canvas,
        bx: Float,
        by: Float,
        startDist: Float,
        shortLen: Float,
        paint: Paint
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
            paint
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

        // Two-cushion banks. Same unfolding maths, one extra rail per path.
        drawDoubleBanks(canvas, bx, by, ballR, startDist)

        // Three-cushion banks when enabled.
        if (showTripleBank) {
            drawTripleBanks(canvas, bx, by, ballR, startDist)
        }
    }

    /**
     * Three-rail banks: long -> side -> opposite long, or long -> opposite long -> side,
     * into the corner pockets.
     */
    private fun drawTripleBanks(
        canvas: Canvas,
        bx: Float,
        by: Float,
        ballR: Float,
        startDist: Float
    ) {
        val l = boundaryBox.left
        val r = boundaryBox.right
        val t = boundaryBox.top
        val b = boundaryBox.bottom
        val cx = boundaryBox.centerX()
        val jaw = aim.cornerJawPx.toFloat()
        val mouth = aim.middleHalfPx.toFloat()

        val longA0 = l + jaw + ballR
        val longB1 = r - jaw - ballR
        val side0 = t + jaw + ballR
        val side1 = b - jaw - ballR

        val topP = (t + ballR).toDouble()
        val botP = (b - ballR).toDouble()
        val leftP = (l + ballR).toDouble()
        val rightP = (r - ballR).toDouble()

        val triples = arrayOf(
            // Top -> Right -> Bottom into Top corners
            TripleRoute(
                AimGeometry.Axis.HORIZONTAL, topP, longA0.toDouble(), longB1.toDouble(),
                AimGeometry.Axis.VERTICAL, rightP, side0.toDouble(), side1.toDouble(),
                AimGeometry.Axis.HORIZONTAL, botP, longA0.toDouble(), longB1.toDouble(),
                TOP_END_POCKETS
            ),
            // Top -> Left -> Bottom into Top corners
            TripleRoute(
                AimGeometry.Axis.HORIZONTAL, topP, longA0.toDouble(), longB1.toDouble(),
                AimGeometry.Axis.VERTICAL, leftP, side0.toDouble(), side1.toDouble(),
                AimGeometry.Axis.HORIZONTAL, botP, longA0.toDouble(), longB1.toDouble(),
                TOP_END_POCKETS
            ),
            // Bottom -> Right -> Top into Bottom corners
            TripleRoute(
                AimGeometry.Axis.HORIZONTAL, botP, longA0.toDouble(), longB1.toDouble(),
                AimGeometry.Axis.VERTICAL, rightP, side0.toDouble(), side1.toDouble(),
                AimGeometry.Axis.HORIZONTAL, topP, longA0.toDouble(), longB1.toDouble(),
                BOTTOM_END_POCKETS
            ),
            // Bottom -> Left -> Top into Bottom corners
            TripleRoute(
                AimGeometry.Axis.HORIZONTAL, botP, longA0.toDouble(), longB1.toDouble(),
                AimGeometry.Axis.VERTICAL, leftP, side0.toDouble(), side1.toDouble(),
                AimGeometry.Axis.HORIZONTAL, topP, longA0.toDouble(), longB1.toDouble(),
                BOTTOM_END_POCKETS
            )
        )

        val midGapMin = (cx - mouth - ballR).toDouble()
        val midGapMax = (cx + mouth + ballR).toDouble()

        for (tr in triples) {
            for (pocket in tr.pockets) {
                aim.gate(pocket, bx.toDouble(), by.toDouble())
                if (!aim.bankFrom3(
                        bx.toDouble(), by.toDouble(),
                        aim.targetX, aim.targetY,
                        tr.axis1, tr.plane1, tr.min1, tr.max1,
                        tr.axis2, tr.plane2, tr.min2, tr.max2,
                        tr.axis3, tr.plane3, tr.min3, tr.max3
                    )
                ) continue

                // Reject if a horizontal rail bounce lands inside the side-pocket mouth gap
                if (tr.axis1 == AimGeometry.Axis.HORIZONTAL && aim.hitX in midGapMin..midGapMax) continue
                if (tr.axis3 == AimGeometry.Axis.HORIZONTAL && aim.hit3X in midGapMin..midGapMax) continue

                // Re-thread gate from 3rd cushion contact
                aim.gate(pocket, aim.hit3X, aim.hit3Y)
                if (!aim.bankFrom3(
                        bx.toDouble(), by.toDouble(),
                        aim.targetX, aim.targetY,
                        tr.axis1, tr.plane1, tr.min1, tr.max1,
                        tr.axis2, tr.plane2, tr.min2, tr.max2,
                        tr.axis3, tr.plane3, tr.min3, tr.max3
                    )
                ) continue

                if (tr.axis1 == AimGeometry.Axis.HORIZONTAL && aim.hitX in midGapMin..midGapMax) continue
                if (tr.axis3 == AimGeometry.Axis.HORIZONTAL && aim.hit3X in midGapMin..midGapMax) continue

                drawTripleBankPath(canvas, bx, by, startDist)
            }
        }
    }

    private class TripleRoute(
        val axis1: AimGeometry.Axis, val plane1: Double, val min1: Double, val max1: Double,
        val axis2: AimGeometry.Axis, val plane2: Double, val min2: Double, val max2: Double,
        val axis3: AimGeometry.Axis, val plane3: Double, val min3: Double, val max3: Double,
        val pockets: Array<AimGeometry.Pocket>
    )

    private fun drawTripleBankPath(
        canvas: Canvas,
        bx: Float,
        by: Float,
        startDist: Float
    ) {
        val h1x = aim.hitX.toFloat()
        val h1y = aim.hitY.toFloat()
        val h2x = aim.hit2X.toFloat()
        val h2y = aim.hit2Y.toFloat()
        val h3x = aim.hit3X.toFloat()
        val h3y = aim.hit3Y.toFloat()
        val gx = aim.targetX.toFloat()
        val gy = aim.targetY.toFloat()

        val blocked = showObstacle && (
            aim.isPathObstructed(bx.toDouble(), by.toDouble(), h1x.toDouble(), h1y.toDouble(), pointO.x.toDouble(), pointO.y.toDouble()) ||
            aim.isPathObstructed(h1x.toDouble(), h1y.toDouble(), h2x.toDouble(), h2y.toDouble(), pointO.x.toDouble(), pointO.y.toDouble()) ||
            aim.isPathObstructed(h2x.toDouble(), h2y.toDouble(), h3x.toDouble(), h3y.toDouble(), pointO.x.toDouble(), pointO.y.toDouble()) ||
            aim.isPathObstructed(h3x.toDouble(), h3y.toDouble(), gx.toDouble(), gy.toDouble(), pointO.x.toDouble(), pointO.y.toDouble())
        )
        val lineP = if (blocked) blockedRayPaint else tripleBankPaint

        val d1 = hypot(h1x - bx, h1y - by)
        if (d1 < 1f) return

        val ux = (h1x - bx) / d1
        val uy = (h1y - by) / d1
        val fromX = if (d1 > startDist) bx + ux * startDist else bx
        val fromY = if (d1 > startDist) by + uy * startDist else by

        bankRayPath.reset()
        bankRayPath.moveTo(fromX, fromY)
        bankRayPath.lineTo(h1x, h1y)
        bankRayPath.lineTo(h2x, h2y)
        bankRayPath.lineTo(h3x, h3y)
        bankRayPath.lineTo(gx, gy)
        canvas.drawPath(bankRayPath, lineP)

        canvas.drawCircle(h1x, h1y, 3f * density, tripleBankDotPaint)
        canvas.drawCircle(h2x, h2y, 2.5f * density, tripleBankDotPaint)
        canvas.drawCircle(h3x, h3y, 2.5f * density, tripleBankDotPaint)
    }

    /**
     * Two-rail banks: long rail then side rail into the far corner pockets.
     * [AimGeometry.bankFrom2] rejects any path whose contact leaves the real
     * cushion segment, so these only draw when both bounces are legal.
     */
    private fun drawDoubleBanks(
        canvas: Canvas,
        bx: Float,
        by: Float,
        ballR: Float,
        startDist: Float
    ) {
        val l = boundaryBox.left
        val r = boundaryBox.right
        val t = boundaryBox.top
        val b = boundaryBox.bottom
        val cx = boundaryBox.centerX()
        val jaw = aim.cornerJawPx.toFloat()
        val mouth = aim.middleHalfPx.toFloat()

        val longA0 = l + jaw + ballR
        val longA1 = cx - mouth - ballR
        val longB0 = cx + mouth + ballR
        val longB1 = r - jaw - ballR
        val side0 = t + jaw + ballR
        val side1 = b - jaw - ballR

        // (long rail plane, side rail plane, target pockets on the far end)
        val pairs = arrayOf(
            DoublePair(t + ballR, r - ballR, longA0, longA1, side0, side1, TOP_END_POCKETS),
            DoublePair(t + ballR, l + ballR, longB0, longB1, side0, side1, TOP_END_POCKETS),
            DoublePair(b - ballR, r - ballR, longA0, longA1, side0, side1, BOTTOM_END_POCKETS),
            DoublePair(b - ballR, l + ballR, longB0, longB1, side0, side1, BOTTOM_END_POCKETS)
        )

        for (p in pairs) {
            for (pocket in p.pockets) {
                aim.gate(pocket, bx.toDouble(), by.toDouble())
                if (!aim.bankFrom2(
                        bx.toDouble(), by.toDouble(),
                        aim.targetX, aim.targetY,
                        AimGeometry.Axis.HORIZONTAL, p.longPlane.toDouble(),
                        p.longMin.toDouble(), p.longMax.toDouble(),
                        AimGeometry.Axis.VERTICAL, p.sidePlane.toDouble(),
                        side0.toDouble(), side1.toDouble()
                    )
                ) continue

                // Re-thread the entry from the second contact and re-solve once,
                // mirroring the one-cushion seeding.
                aim.gate(pocket, aim.hit2X, aim.hit2Y)
                if (!aim.bankFrom2(
                        bx.toDouble(), by.toDouble(),
                        aim.targetX, aim.targetY,
                        AimGeometry.Axis.HORIZONTAL, p.longPlane.toDouble(),
                        p.longMin.toDouble(), p.longMax.toDouble(),
                        AimGeometry.Axis.VERTICAL, p.sidePlane.toDouble(),
                        side0.toDouble(), side1.toDouble()
                    )
                ) continue

                drawDoubleBankPath(canvas, bx, by, startDist)
            }
        }
    }

    private class DoublePair(
        val longPlane: Float,
        val sidePlane: Float,
        val longMin: Float,
        val longMax: Float,
        val sideMin: Float,
        val sideMax: Float,
        val pockets: Array<AimGeometry.Pocket>
    )

    private fun drawDoubleBankPath(
        canvas: Canvas,
        bx: Float,
        by: Float,
        startDist: Float
    ) {
        val h1x = aim.hitX.toFloat()
        val h1y = aim.hitY.toFloat()
        val h2x = aim.hit2X.toFloat()
        val h2y = aim.hit2Y.toFloat()
        val gx = aim.targetX.toFloat()
        val gy = aim.targetY.toFloat()

        val blocked = showObstacle && (
            aim.isPathObstructed(bx.toDouble(), by.toDouble(), h1x.toDouble(), h1y.toDouble(), pointO.x.toDouble(), pointO.y.toDouble()) ||
            aim.isPathObstructed(h1x.toDouble(), h1y.toDouble(), h2x.toDouble(), h2y.toDouble(), pointO.x.toDouble(), pointO.y.toDouble()) ||
            aim.isPathObstructed(h2x.toDouble(), h2y.toDouble(), gx.toDouble(), gy.toDouble(), pointO.x.toDouble(), pointO.y.toDouble())
        )
        val lineP = if (blocked) blockedRayPaint else bankLinePaint

        val d1 = hypot(h1x - bx, h1y - by)
        if (d1 < 1f) return

        val ux = (h1x - bx) / d1
        val uy = (h1y - by) / d1
        val fromX = if (d1 > startDist) bx + ux * startDist else bx
        val fromY = if (d1 > startDist) by + uy * startDist else by

        bankRayPath.reset()
        bankRayPath.moveTo(fromX, fromY)
        bankRayPath.lineTo(h1x, h1y)
        bankRayPath.lineTo(h2x, h2y)
        bankRayPath.lineTo(gx, gy)
        canvas.drawPath(bankRayPath, lineP)

        canvas.drawCircle(h1x, h1y, 3f * density, bankDotPaint)
        canvas.drawCircle(h2x, h2y, 2.5f * density, bankDotPaint)
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

        val leg1Blocked = showObstacle &&
            aim.isPathObstructed(bx.toDouble(), by.toDouble(), hx.toDouble(), hy.toDouble(), pointO.x.toDouble(), pointO.y.toDouble())
        val leg2Blocked = showObstacle &&
            aim.isPathObstructed(hx.toDouble(), hy.toDouble(), gateX.toDouble(), gateY.toDouble(), pointO.x.toDouble(), pointO.y.toDouble())
        val lineP1 = if (leg1Blocked || leg2Blocked) blockedRayPaint else bankLinePaint
        val lineP2 = if (leg1Blocked || leg2Blocked) blockedRayPaint else bankLinePaint

        if (isFullLengthGuides) {
            if (dist1 > startDist) {
                val ux1 = dx1 / dist1
                val uy1 = dy1 / dist1
                bankRayPath.reset()
                bankRayPath.moveTo(bx + ux1 * startDist, by + uy1 * startDist)
                bankRayPath.lineTo(hx, hy)
                canvas.drawPath(bankRayPath, lineP1)
            } else if (dist1 > 1f) {
                bankRayPath.reset()
                bankRayPath.moveTo(bx, by)
                bankRayPath.lineTo(hx, hy)
                canvas.drawPath(bankRayPath, lineP1)
            }

            canvas.drawCircle(hx, hy, 3.5f * density, bankDotPaint)

            val ux2 = dx2 / dist2
            val uy2 = dy2 / dist2
            val extend = aim.mouthDepthPx.toFloat()
            bankRayPath.reset()
            bankRayPath.moveTo(hx, hy)
            bankRayPath.lineTo(gateX + ux2 * extend, gateY + uy2 * extend)
            canvas.drawPath(bankRayPath, lineP2)
        } else {
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
                    canvas.drawPath(bankRayPath, lineP1)
                }
            } else {
                // Bounce inside the short reach: full path, fixed-length reflection.
                if (dist1 > startDist) {
                    val ux1 = dx1 / dist1
                    val uy1 = dy1 / dist1
                    bankRayPath.reset()
                    bankRayPath.moveTo(bx + ux1 * startDist, by + uy1 * startDist)
                    bankRayPath.lineTo(hx, hy)
                    canvas.drawPath(bankRayPath, lineP1)
                }

                canvas.drawCircle(hx, hy, 3.5f * density, bankDotPaint)

                bankRayPath.reset()
                bankRayPath.moveTo(hx, hy)
                bankRayPath.lineTo(hx + ux2 * outgoing, hy + uy2 * outgoing)
                canvas.drawPath(bankRayPath, lineP2)
            }
        }
    }

    /**
     * Target mode render: ball-fit ring, 6 direct pocket rays, 10 cushion banks,
     * plus advanced table physics (Spin/English CIT+SIT, Post-Impact Cue Ball
     * Deflection trajectory, Obstacle collision masking, and 3-Cushion banks).
     */
    private fun drawTargetAndRadialGuides(canvas: Canvas) {
        val bx = pointB.x
        val by = pointB.y
        // Derive the ball ring from the real table scale, not a dp constant.
        aim.rebound = bankRebound.toDouble()
        aim.jawScale = jawScale.toDouble()
        aim.mouthScale = mouthScale.toDouble()
        aim.spinX = spinX.toDouble()
        aim.spinY = spinY.toDouble()
        aim.configure(
            boundaryBox.left, boundaryBox.top,
            boundaryBox.right, boundaryBox.bottom,
            ballScaleCorrection
        )
        val ringRadius = aim.ballRadius.toFloat()

        // 0a. Vision-detected ball markers (subtle radar rings on detected balls)
        if (detectedBalls.isNotEmpty()) {
            val cueX = if (showCueMode) pointC.x else -9999f
            val cueY = if (showCueMode) pointC.y else -9999f
            for (db in detectedBalls) {
                val isUnderHandleB = hypot(db.x - bx, db.y - by) <= ringRadius * 0.6f
                val isUnderHandleC = hypot(db.x - cueX, db.y - cueY) <= ringRadius * 0.6f
                if (!isUnderHandleB && !isUnderHandleC) {
                    val paint = if (db.isCueCandidate) visionCueCandidatePaint else visionCandidatePaint
                    canvas.drawCircle(db.x, db.y, ringRadius * 0.88f, paint)
                }
            }
        }

        // 0. Obstacle / Blocker Ball (Point O)
        if (showObstacle) {
            val ox = pointO.x
            val oy = pointO.y
            // 2R collision exclusion zone around the obstacle ball
            canvas.drawCircle(ox, oy, ringRadius * 2f, obstacleCollisionZonePaint)
            canvas.drawCircle(ox, oy, ringRadius, aimShadowPaint)
            canvas.drawCircle(ox, oy, ringRadius, obstacleRingPaint)
            val xArm = ringRadius * 0.45f
            canvas.drawLine(ox - xArm, oy - xArm, ox + xArm, oy + xArm, obstacleRingPaint)
            canvas.drawLine(ox - xArm, oy + xArm, ox + xArm, oy - xArm, obstacleRingPaint)
        }

        // 1. Ghost Ball & Cue-Ball Post-Impact Deflection (Spin/English Physics)
        val bestPocket = findBestPocket(bx, by)
        if (bestPocket != null) {
            aim.gate(bestPocket, bx.toDouble(), by.toDouble())
            if (aim.gateFeasible) {
                val cueX = if (showCueMode) pointC.x else null
                val cueY = if (showCueMode) pointC.y else null
                val (gx, gy) = aim.ghostBall(bx, by, cueX, cueY, targetAimX = aim.targetX, targetAimY = aim.targetY)

                if (showGhostBall) {
                    canvas.drawCircle(gx, gy, ringRadius, ghostShadowPaint)
                    canvas.drawCircle(gx, gy, ringRadius, ghostRingPaint)
                }

                if (showCueMode) {
                    val cx = pointC.x
                    val cy = pointC.y
                    // Draw Cue Ball ring
                    canvas.drawCircle(cx, cy, ringRadius, aimShadowPaint)
                    canvas.drawCircle(cx, cy, ringRadius, cueRingPaint)

                    // Draw spin contact dot inside Cue Ball ring
                    val spinDotX = cx + spinX * (ringRadius * 0.65f)
                    val spinDotY = cy - spinY * (ringRadius * 0.65f)
                    canvas.drawCircle(spinDotX, spinDotY, 3.0f * density, aimCenterShadowPaint)
                    canvas.drawCircle(spinDotX, spinDotY, 2.2f * density, bankDotPaint)

                    // Incoming cue->ghost line (checked against obstacle ball if active)
                    val incomingBlocked = showObstacle &&
                        aim.isPathObstructed(cx.toDouble(), cy.toDouble(), gx.toDouble(), gy.toDouble(), pointO.x.toDouble(), pointO.y.toDouble())
                    val inPaint = if (incomingBlocked) blockedRayPaint else cueIncomingPaint
                    canvas.drawLine(cx, cy, gx, gy, inPaint)

                    // Solve and render 90-degree stun tangent + post-impact follow/draw curve
                    val guideLen = if (isFullLengthGuides) ringRadius * 9.0 else ringRadius * 5.5
                    if (aim.solveCueDeflection(
                            bx.toDouble(), by.toDouble(),
                            cx.toDouble(), cy.toDouble(),
                            aim.targetX, aim.targetY,
                            guideLen
                        )
                    ) {
                        // Stun tangent reference line
                        canvas.drawLine(
                            aim.deflectStartX.toFloat(),
                            aim.deflectStartY.toFloat(),
                            aim.deflectTangentEndX.toFloat(),
                            aim.deflectTangentEndY.toFloat(),
                            stunTangentPaint
                        )
                        // Actual spin-adjusted post-impact trajectory (Quadratic Bezier)
                        deflectionPath.reset()
                        deflectionPath.moveTo(aim.deflectStartX.toFloat(), aim.deflectStartY.toFloat())
                        deflectionPath.quadTo(
                            aim.deflectCtrlX.toFloat(),
                            aim.deflectCtrlY.toFloat(),
                            aim.deflectEndX.toFloat(),
                            aim.deflectEndY.toFloat()
                        )
                        canvas.drawPath(deflectionPath, spinCurvePaint)
                        canvas.drawCircle(
                            aim.deflectEndX.toFloat(),
                            aim.deflectEndY.toFloat(),
                            3.0f * density,
                            cueRingPaint
                        )
                    }
                }
            }
        } else if (showCueMode) {
            // Even when no pocket is feasible, still draw the Cue Ball reticle & spin dot
            val cx = pointC.x
            val cy = pointC.y
            canvas.drawCircle(cx, cy, ringRadius, aimShadowPaint)
            canvas.drawCircle(cx, cy, ringRadius, cueRingPaint)
            val spinDotX = cx + spinX * (ringRadius * 0.65f)
            val spinDotY = cy - spinY * (ringRadius * 0.65f)
            canvas.drawCircle(spinDotX, spinDotY, 3.0f * density, aimCenterShadowPaint)
            canvas.drawCircle(spinDotX, spinDotY, 2.2f * density, bankDotPaint)
        }

        // 2. Ball-Fit Ring: high-contrast hollow reticle matching ball size
        canvas.drawCircle(bx, by, ringRadius, aimShadowPaint)
        canvas.drawCircle(bx, by, ringRadius, aimRingPaint)

        // 3. Concentric Crosshair tick marks: visual alignment guide extending through ball edge
        val armInner = ringRadius * 0.45f
        val armOuter = ringRadius * 1.25f

        // Horizontal crosshair ticks (Left & Right)
        canvas.drawLine(bx - armOuter, by, bx - armInner, by, aimCrosshairShadowPaint)
        canvas.drawLine(bx - armOuter, by, bx - armInner, by, aimCrosshairPaint)
        canvas.drawLine(bx + armInner, by, bx + armOuter, by, aimCrosshairShadowPaint)
        canvas.drawLine(bx + armInner, by, bx + armOuter, by, aimCrosshairPaint)

        // Vertical crosshair ticks (Top & Bottom)
        canvas.drawLine(bx, by - armOuter, bx, by - armInner, aimCrosshairShadowPaint)
        canvas.drawLine(bx, by - armOuter, bx, by - armInner, aimCrosshairPaint)
        canvas.drawLine(bx, by + armInner, bx, by + armOuter, aimCrosshairShadowPaint)
        canvas.drawLine(bx, by + armInner, bx, by + armOuter, aimCrosshairPaint)

        // 4. Micro centre pinpoint: double contrast dot
        canvas.drawCircle(bx, by, 2.5f * density, aimCenterShadowPaint)
        canvas.drawCircle(bx, by, 1.5f * density, aimCenterDotPaint)

        val startDist = ringRadius + (3f * density)
        val shortLen = 85f * density

        // 5. Six direct pocket rays (with obstacle collision masking)
        for (pocket in allPockets) {
            aim.gate(pocket, bx.toDouble(), by.toDouble())
            if (!aim.gateFeasible) continue
            val isBlocked = showObstacle && aim.isPathObstructed(
                bx.toDouble(), by.toDouble(),
                aim.targetX, aim.targetY,
                pointO.x.toDouble(), pointO.y.toDouble()
            )
            val rayPaint = if (isBlocked) blockedRayPaint else sixLinePaint
            drawDirectRay(canvas, bx, by, startDist, shortLen, rayPaint)
        }

        // 6. Cushion bank rays (1-rail, 2-rail, and 3-rail)
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
        aim.jawScale = jawScale.toDouble()
        aim.mouthScale = mouthScale.toDouble()
        aim.configure(boundaryBox.left, boundaryBox.top, boundaryBox.right, boundaryBox.bottom, ballScaleCorrection)
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
                    // Keep the calibrated rectangle on the real 2:1 surface.
                    val lock = when (activeDragTarget) {
                        DragTarget.HANDLE_TOP_CENTER, DragTarget.HANDLE_BOTTOM_CENTER ->
                            Axis.HEIGHT
                        else -> Axis.WIDTH
                    }
                    enforceTableRatio(screenW, screenH, lock)
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
