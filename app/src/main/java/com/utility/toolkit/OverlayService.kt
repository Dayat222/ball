package com.utility.toolkit

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.PointF
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Multi-window foreground service managing:
 * 1. Table-bounded pass-through canvas overlay with pocket rays and bank rays.
 * 2. Independent floating target-ball handle with anti-finger offset.
 * 3. Draggable compact floating control bar with collapsible mini capsule,
 *    calibration toggle, ray length toggle, bank ray toggle, and styling sliders.
 */
class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var prefsManager: PrefsManager

    // Canvas Overlay View
    private var canvasOverlayView: CanvasOverlayView? = null
    private lateinit var canvasLayoutParams: WindowManager.LayoutParams

    // Independent Floating Handle (Grip Pad)
    private var handleViewB: HandleView? = null
    private lateinit var handleParamsB: WindowManager.LayoutParams
    private var handleSizePx = 0
    private var handleOffsetX = 48f
    private var handleOffsetY = 36f
    private var isOffsetInvertedB = false

    // Floating Control Bar
    private var floatingControlView: View? = null
    private lateinit var controlLayoutParams: WindowManager.LayoutParams

    private var fullToolbarContainer: View? = null
    private var minimizedCapsule: View? = null
    private var btnMiniDrag: TextView? = null
    private var btnMiniToggleOnOff: TextView? = null
    private var btnMiniExpand: TextView? = null
    private var btnCalibrate: TextView? = null
    private var btnSettings: TextView? = null
    private var panelSettings: View? = null

    // State Tracking
    private var isCanvasHidden = false
    private var isCalibrationActive = false
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        prefsManager = PrefsManager(this)

        val density = resources.displayMetrics.density
        handleSizePx = (42f * density).roundToInt()
        handleOffsetX = 48f * density
        handleOffsetY = 36f * density

        // 1. Promote to Foreground Service
        startForegroundServiceWithNotification()

        // 2. Initialize and attach Canvas Overlay View
        setupCanvasOverlay()

        // 3. Initialize and attach 3 Independent Floating Handles with Anti-Finger Offset
        setupFloatingHandles()

        // 4. Initialize and attach Floating Control View (with Collapsible Minimize)
        setupFloatingControl()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SERVICE) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun startForegroundServiceWithNotification() {
        createNotificationChannel()

        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, OverlayService::class.java).apply {
            action = ACTION_STOP_SERVICE
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openAppPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                R.drawable.ic_notification,
                getString(R.string.notification_action_stop),
                stopPendingIntent
            )
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun getOverlayWindowType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
    }

    private fun getRealScreenSize(): Point {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            Point(bounds.width(), bounds.height())
        } else {
            val point = Point()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealSize(point)
            point
        }
    }

    private fun setupCanvasOverlay() {
        canvasOverlayView = CanvasOverlayView(this).apply {
            onCanvasSizeChanged = { _, _ ->
                repositionHandlesToAimPoints()
            }
        }

        canvasLayoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            getOverlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            alpha = 0.78f
        }

        try {
            windowManager.addView(canvasOverlayView, canvasLayoutParams)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        applyTableBoundedWindow()
    }

    /**
     * Window fitting.
     *
     * Calibration: full screen so all 8 handles are reachable.
     * Play + Full guides: table box + 18dp, as before.
     * Play + Short guides: only the capsule the guides can actually reach.
     * Keeping the window small is the one passive anti-cheat lever an overlay
     * owns: MotionEvent.FLAG_WINDOW_IS_OBSCURED is raised on the game's touches
     * for every pixel this window covers.
     * Hidden: the canvas window is removed from WindowManager entirely, so it
     * stops obscuring touches instead of sitting there invisible.
     */
    private fun applyTableBoundedWindow() {
        val canvas = canvasOverlayView ?: return
        val density = resources.displayMetrics.density

        if (isCalibrationActive) {
            // Saat kalibrasi aktif, gunakan layar penuh agar pengguna bebas menarik 8 titik kalibrasi
            canvasLayoutParams.width = WindowManager.LayoutParams.MATCH_PARENT
            canvasLayoutParams.height = WindowManager.LayoutParams.MATCH_PARENT
            canvasLayoutParams.x = 0
            canvasLayoutParams.y = 0
            canvasLayoutParams.alpha = 1.0f
            canvasLayoutParams.gravity = Gravity.TOP or Gravity.LEFT
            canvasLayoutParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                canvasLayoutParams.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            canvasLayoutParams.setTitle(WINDOW_TITLE)
            canvas.setWindowOffset(0f, 0f)
            pushCanvasLayout(canvas)
            return
        }

        if (isCanvasHidden) {
            if (canvas.parent != null) {
                try {
                    windowManager.removeView(canvas)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            return
        }

        // Saat bermain normal: pangkas jendela kanvas sekecil mungkin.
        val box = canvas.boundaryBox
        val realSize = getRealScreenSize()
        val screenW = kotlin.math.max(realSize.x, realSize.y)
        val screenH = kotlin.math.min(realSize.x, realSize.y)
        val pad = (18f * density).roundToInt()
        val ballR = (canvas.ringRadiusDp * density).roundToInt()

        val winX: Int
        val winY: Int
        val winW: Int
        val winH: Int

        if (canvas.isFullLengthGuides) {
            winX = (box.left.roundToInt() - pad).coerceIn(0, (screenW - 1).coerceAtLeast(0))
            winY = (box.top.roundToInt() - pad).coerceIn(0, (screenH - 1).coerceAtLeast(0))
            winW = (box.width().roundToInt() + pad * 2).coerceIn(1, screenW - winX)
            winH = (box.height().roundToInt() + pad * 2).coerceIn(1, screenH - winY)
        } else {
            // Fixed-size capsule that only MOVES with the target.
            //
            // Resizing the window on every touch sample was the cause of the
            // choppy, clipped Short-mode guides: each resize reallocates the
            // surface and lands on a different vsync than the redraw, so the
            // buffer is briefly composited at the old offset while the pointer
            // keeps moving. A constant size turns every drag update into a
            // cheap move transaction, and the radius covers the whole 85dp ray,
            // so nothing is ever clipped mid-drag.
            val shortLen = (85f * density).roundToInt()
            val reach = if (canvas.showBankGuides) {
                // A bank is two legs: ball -> cushion -> reflected leg. The
                // reflected leg keeps its full short length even next to a
                // cushion, so the envelope is twice the short length while
                // banks are visible.
                shortLen * 2 + (8f * density).roundToInt()
            } else {
                // Direct rays need one leg only; keeping the window small when
                // banks are off preserves the minimal obscured area.
                shortLen + ballR + (24f * density).roundToInt()
            }
            val size = (reach * 2).coerceIn(64, kotlin.math.min(screenW, screenH))
            winW = size
            winH = size
            winX = (canvas.pointB.x.roundToInt() - size / 2).coerceIn(0, (screenW - size).coerceAtLeast(0))
            winY = (canvas.pointB.y.roundToInt() - size / 2).coerceIn(0, (screenH - size).coerceAtLeast(0))
        }

        // Nothing to do when the window already sits exactly there.
        if (canvas.parent != null &&
            canvasLayoutParams.x == winX &&
            canvasLayoutParams.y == winY &&
            canvasLayoutParams.width == winW &&
            canvasLayoutParams.height == winH
        ) {
            return
        }

        canvasLayoutParams.width = winW
        canvasLayoutParams.height = winH
        canvasLayoutParams.x = winX
        canvasLayoutParams.y = winY
        canvasLayoutParams.alpha = 0.78f
        canvasLayoutParams.gravity = Gravity.TOP or Gravity.LEFT
        canvasLayoutParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            canvasLayoutParams.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        canvasLayoutParams.setTitle(WINDOW_TITLE)

        canvas.setWindowOffset(winX.toFloat(), winY.toFloat())
        pushCanvasLayout(canvas)
    }

    /** Attach on first use, re-layout afterwards (window is detached while hidden). */
    private fun pushCanvasLayout(canvas: View) {
        try {
            if (canvas.parent == null) {
                windowManager.addView(canvas, canvasLayoutParams)
            } else {
                windowManager.updateViewLayout(canvas, canvasLayoutParams)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Calibration Mode: Enable touch on canvas and hide handles.
     */
    private fun setCalibrationMode(enabled: Boolean) {
        val canvas = canvasOverlayView ?: return
        isCalibrationActive = enabled

        if (enabled) {
            canvas.setCalibrating(true)
            syncHandlesVisibility()

            btnCalibrate?.text = getString(R.string.control_save_bound)
            btnCalibrate?.setBackgroundResource(R.drawable.bg_control_btn_active)
            btnCalibrate?.setTextColor(ContextCompat.getColor(this, R.color.green_status))
        } else {
            canvas.setCalibrating(false)
            syncHandlesVisibility()

            btnCalibrate?.text = getString(R.string.control_calibrate)
            btnCalibrate?.setBackgroundResource(R.drawable.bg_control_btn)
            btnCalibrate?.setTextColor(Color.WHITE)

            Toast.makeText(this, R.string.toast_calib_saved, Toast.LENGTH_SHORT).show()
        }

        applyTableBoundedWindow()
    }

    /**
     * Feature 2: Anti-Finger Obstruction (Offset Touch Handles).
     * The grip pad is positioned below the actual target ball to keep the ball visible.
     */
    private fun setupFloatingHandles() {
        val realSize = getRealScreenSize()
        val screenW = kotlin.math.max(realSize.x, realSize.y).toFloat()
        val screenH = kotlin.math.min(realSize.x, realSize.y).toFloat()

        val targetPoint = prefsManager.getTargetPoint(screenW, screenH)
        val initialAlpha = prefsManager.getLineAlpha()
        val initialColor = prefsManager.getLineColor()

        // Target Ball Handle (Point B)
        handleViewB = HandleView(this, "B").apply {
            updateColor(initialColor)
            updateHandleAlpha(initialAlpha)
        }
        val gripPosB = calculateGripFromAim(targetPoint, isOffsetInvertedB)
        handleParamsB = createHandleLayoutParams(gripPosB)
        setupHandleTouchListener(
            handleViewB!!,
            handleParamsB,
            initialInverted = isOffsetInvertedB,
            onInversionChanged = { isOffsetInvertedB = it }
        ) { aimX, aimY, gripX, gripY ->
            canvasOverlayView?.setPointB(aimX, aimY, gripX, gripY)
            applyTableBoundedWindow()
        }
        windowManager.addView(handleViewB, handleParamsB)
        canvasOverlayView?.setPointB(targetPoint.x, targetPoint.y, gripPosB.x, gripPosB.y)
        applyTableBoundedWindow()

        syncHandlesVisibility()
    }

    private fun calculateGripFromAim(aim: PointF, isInverted: Boolean = false): PointF {
        val realSize = getRealScreenSize()
        val currentScreenW = kotlin.math.max(realSize.x, realSize.y).toFloat()
        val currentScreenH = kotlin.math.min(realSize.x, realSize.y).toFloat()

        val gripX = (aim.x + handleOffsetX).coerceIn(handleSizePx / 2f, currentScreenW - handleSizePx / 2f)
        val targetGripY = if (isInverted) aim.y - handleOffsetY else aim.y + handleOffsetY
        val gripY = if (targetGripY + handleSizePx > currentScreenH - (20f * resources.displayMetrics.density)) {
            aim.y - handleOffsetY
        } else {
            targetGripY
        }.coerceIn(0f, currentScreenH)
        return PointF(gripX, gripY)
    }

    private fun createHandleLayoutParams(center: PointF): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            handleSizePx,
            handleSizePx,
            getOverlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            x = (center.x - handleSizePx / 2f).roundToInt()
            y = (center.y - handleSizePx / 2f).roundToInt()
            setTitle(WINDOW_TITLE)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupHandleTouchListener(
        view: View,
        params: WindowManager.LayoutParams,
        initialInverted: Boolean = false,
        onInversionChanged: ((Boolean) -> Unit)? = null,
        onMoved: (aimX: Float, aimY: Float, gripX: Float, gripY: Float) -> Unit
    ) {
        var isOffsetInverted = initialInverted

        // Touch Tracking & Velocity Sensitivity
        var lastRawX = 0f
        var lastRawY = 0f
        var currentGripX = 0f
        var currentGripY = 0f

        var downRawX = 0f
        var downRawY = 0f

        // Long-Press Tracking (Smart Offset Flip)
        var hasMovedSignificantly = false

        fun flipOffset() {
            val newInverted = !isOffsetInverted
            isOffsetInverted = newInverted
            onInversionChanged?.invoke(newInverted)

            val realSize = getRealScreenSize()
            val currentScreenW = kotlin.math.max(realSize.x, realSize.y).toFloat()
            val currentScreenH = kotlin.math.min(realSize.x, realSize.y).toFloat()

            // Invert grip Y: if newInverted, grip jumps above ball (-2 * handleOffsetY)
            // if !newInverted, grip jumps below ball (+2 * handleOffsetY)
            val deltaY = if (newInverted) -2f * handleOffsetY else 2f * handleOffsetY
            currentGripY = (currentGripY + deltaY).coerceIn(0f, currentScreenH)

            params.y = (currentGripY - handleSizePx / 2f).roundToInt()
            try {
                windowManager.updateViewLayout(view, params)
            } catch (e: Exception) {
                e.printStackTrace()
            }

            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)

            val aimX = (currentGripX - handleOffsetX).coerceIn(0f, currentScreenW)
            val offsetDir = if (isOffsetInverted) handleOffsetY else -handleOffsetY
            val aimY = (currentGripY + offsetDir).coerceIn(0f, currentScreenH)
            onMoved(aimX, aimY, currentGripX, currentGripY)
        }

        val longPressRunnable = Runnable {
            if (!hasMovedSignificantly) {
                flipOffset()
            }
        }

        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastRawX = event.rawX
                    lastRawY = event.rawY
                    downRawX = event.rawX
                    downRawY = event.rawY

                    currentGripX = params.x + (handleSizePx / 2f)
                    currentGripY = params.y + (handleSizePx / 2f)

                    hasMovedSignificantly = false
                    mainHandler.postDelayed(longPressRunnable, 450L)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val frameDx = event.rawX - lastRawX
                    val frameDy = event.rawY - lastRawY
                    lastRawX = event.rawX
                    lastRawY = event.rawY

                    val totalMoveFromDown = hypot(event.rawX - downRawX, event.rawY - downRawY)
                    if (totalMoveFromDown > 8f * resources.displayMetrics.density) {
                        hasMovedSignificantly = true
                        mainHandler.removeCallbacks(longPressRunnable)
                    }

                    val speed = hypot(frameDx, frameDy)
                    // Deadzone Anti-Jitter: ignore micro movements during rest or lift-off
                    if (speed < 0.4f) {
                        return@setOnTouchListener true
                    }

                    // Dynamic Sensitivity Multiplier (Sniper Fine-Tuning Mode)
                    val sensitivity = when {
                        speed < 3.5f -> 0.28f  // Sniper Mode: ultra-fine alignment
                        speed < 10f -> 0.65f   // Medium adjustments
                        else -> 1.0f           // Fast panning
                    }

                    currentGripX += frameDx * sensitivity
                    currentGripY += frameDy * sensitivity

                    val realSize = getRealScreenSize()
                    val currentScreenW = kotlin.math.max(realSize.x, realSize.y).toFloat()
                    val currentScreenH = kotlin.math.min(realSize.x, realSize.y).toFloat()

                    // Auto-Flip when dragged past bottom limit and handle is below ball
                    if (!isOffsetInverted && currentGripY > currentScreenH - (handleSizePx * 0.8f)) {
                        flipOffset()
                    }

                    currentGripX = currentGripX.coerceIn(0f, currentScreenW)
                    currentGripY = currentGripY.coerceIn(0f, currentScreenH)

                    params.x = (currentGripX - handleSizePx / 2f).roundToInt()
                    params.y = (currentGripY - handleSizePx / 2f).roundToInt()

                    try {
                        windowManager.updateViewLayout(view, params)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }

                    val aimX = (currentGripX - handleOffsetX).coerceIn(0f, currentScreenW)
                    val offsetDir = if (isOffsetInverted) handleOffsetY else -handleOffsetY
                    val aimY = (currentGripY + offsetDir).coerceIn(0f, currentScreenH)

                    onMoved(aimX, aimY, currentGripX, currentGripY)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    mainHandler.removeCallbacks(longPressRunnable)
                    persistHandlePositions()
                    true
                }
                else -> false
            }
        }
    }

    private fun persistHandlePositions() {
        val canvas = canvasOverlayView ?: return
        prefsManager.saveTargetPoint(canvas.pointB)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupFloatingControl() {
        val inflater = LayoutInflater.from(this)
        val controlView = inflater.inflate(R.layout.layout_floating_control, null)
        floatingControlView = controlView

        // Stealth: the toolbar is a real view hierarchy, so it is stripped from
        // the accessibility tree; nothing over the game is readable as a node.
        controlView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS

        controlLayoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            getOverlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            x = 40
            y = 140
            setTitle(WINDOW_TITLE)
        }

        // References to UI components
        fullToolbarContainer = controlView.findViewById(R.id.fullToolbarContainer)
        minimizedCapsule = controlView.findViewById(R.id.minimizedCapsule)
        btnMiniDrag = controlView.findViewById(R.id.btnMiniDrag)
        btnMiniToggleOnOff = controlView.findViewById(R.id.btnMiniToggleOnOff)
        btnMiniExpand = controlView.findViewById(R.id.btnMiniExpand)

        val btnDrag = controlView.findViewById<TextView>(R.id.btnDragHandle)
        btnCalibrate = controlView.findViewById(R.id.btnToggleCalibrate)
        btnSettings = controlView.findViewById(R.id.btnToggleSettings)
        val btnClose = controlView.findViewById<TextView>(R.id.btnCloseOverlay)

        panelSettings = controlView.findViewById(R.id.panelSettings)
        val tvThickness = controlView.findViewById<TextView>(R.id.tvThicknessValue)
        val seekThickness = controlView.findViewById<SeekBar>(R.id.seekThickness)
        val tvOpacity = controlView.findViewById<TextView>(R.id.tvOpacityValue)
        val seekOpacity = controlView.findViewById<SeekBar>(R.id.seekOpacity)
        val tvBallSize = controlView.findViewById<TextView>(R.id.tvBallSizeValue)
        val seekBallSize = controlView.findViewById<SeekBar>(R.id.seekBallSize)

        // 1. Dragging toolbar
        setupDraggableBar(btnDrag, controlView)

        // Smart Mini Bubble (Drag to move, Tap to expand, Long-press to toggle ON/OFF)
        btnMiniExpand?.let { setupSmartMiniBubble(it, controlView) }

        // Guideline Length (Short / Full Mode)
        val btnToggleLineLength = controlView.findViewById<TextView>(R.id.btnToggleLineLength)
        val btnSettingLineShort = controlView.findViewById<TextView>(R.id.btnSettingLineShort)
        val btnSettingLineFull = controlView.findViewById<TextView>(R.id.btnSettingLineFull)

        fun updateLineLengthUI(isFull: Boolean) {
            if (isFull) {
                btnToggleLineLength?.text = getString(R.string.control_line_full)
                btnToggleLineLength?.setBackgroundResource(R.drawable.bg_control_btn_active)
                btnToggleLineLength?.setTextColor(ContextCompat.getColor(this, R.color.cyan_accent))

                btnSettingLineShort?.setBackgroundResource(R.drawable.bg_control_btn)
                btnSettingLineShort?.setTextColor(Color.WHITE)
                btnSettingLineFull?.setBackgroundResource(R.drawable.bg_control_btn_active)
                btnSettingLineFull?.setTextColor(ContextCompat.getColor(this, R.color.cyan_accent))
            } else {
                btnToggleLineLength?.text = getString(R.string.control_line_short)
                btnToggleLineLength?.setBackgroundResource(R.drawable.bg_control_btn)
                btnToggleLineLength?.setTextColor(Color.WHITE)

                btnSettingLineShort?.setBackgroundResource(R.drawable.bg_control_btn_active)
                btnSettingLineShort?.setTextColor(ContextCompat.getColor(this, R.color.cyan_accent))
                btnSettingLineFull?.setBackgroundResource(R.drawable.bg_control_btn)
                btnSettingLineFull?.setTextColor(Color.WHITE)
            }
        }

        val initialIsFull = prefsManager.isFullLengthGuides()
        canvasOverlayView?.setFullLengthGuides(initialIsFull)
        updateLineLengthUI(initialIsFull)

        btnToggleLineLength?.setOnClickListener {
            val isNowFull = canvasOverlayView?.toggleFullLengthGuides() ?: false
            updateLineLengthUI(isNowFull)
            applyTableBoundedWindow()
            Toast.makeText(
                this,
                if (isNowFull) getString(R.string.toast_line_full) else getString(R.string.toast_line_short),
                Toast.LENGTH_SHORT
            ).show()
        }

        btnSettingLineShort?.setOnClickListener {
            canvasOverlayView?.setFullLengthGuides(false)
            updateLineLengthUI(false)
            applyTableBoundedWindow()
            Toast.makeText(this, getString(R.string.toast_line_short), Toast.LENGTH_SHORT).show()
        }

        btnSettingLineFull?.setOnClickListener {
            canvasOverlayView?.setFullLengthGuides(true)
            updateLineLengthUI(true)
            applyTableBoundedWindow()
            Toast.makeText(this, getString(R.string.toast_line_full), Toast.LENGTH_SHORT).show()
        }

        // Bank / Reflection Guides Toggle Button (Bank ON / OFF)
        val btnToggleBankGuides = controlView.findViewById<TextView>(R.id.btnToggleBankGuides)

        fun updateBankGuidesUI(isBankOn: Boolean) {
            if (isBankOn) {
                btnToggleBankGuides?.setBackgroundResource(R.drawable.bg_control_btn_active)
                btnToggleBankGuides?.setTextColor(ContextCompat.getColor(this, R.color.yellow_accent))
            } else {
                btnToggleBankGuides?.setBackgroundResource(R.drawable.bg_control_btn)
                btnToggleBankGuides?.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            }
        }

        val initialBankOn = prefsManager.getShowBankGuides()
        canvasOverlayView?.setShowBankGuides(initialBankOn)
        updateBankGuidesUI(initialBankOn)

        btnToggleBankGuides?.setOnClickListener {
            val isNowBankOn = canvasOverlayView?.toggleBankGuides() ?: true
            updateBankGuidesUI(isNowBankOn)
            // The Short-mode window envelope depends on whether bank rays are
            // visible, so refit once when the toggle changes.
            applyTableBoundedWindow()
            Toast.makeText(
                this,
                if (isNowBankOn) getString(R.string.toast_bank_on) else getString(R.string.toast_bank_off),
                Toast.LENGTH_SHORT
            ).show()
        }

        // 2. Calibration Mode Toggle
        btnCalibrate?.setOnClickListener {
            if (isCanvasHidden) {
                toggleCanvasVisibility()
            }
            setCalibrationMode(!isCalibrationActive)
        }

        // 7. Settings Sub-Panel (Thickness & Opacity Sliders)
        val initialStroke = prefsManager.getLineStrokeWidth()
        val initialAlpha = prefsManager.getLineAlpha()

        seekThickness.progress = (initialStroke / 0.5f).roundToInt().coerceIn(1, 30)
        seekOpacity.progress = initialAlpha.coerceIn(25, 255)

        tvThickness.text = getString(R.string.label_thickness, initialStroke)
        val initialPct = (initialAlpha * 100 / 255)
        tvOpacity.text = getString(R.string.label_opacity, initialPct)

        btnSettings?.setOnClickListener {
            val panel = panelSettings ?: return@setOnClickListener
            val isPanelOpen = panel.visibility == View.VISIBLE
            if (isPanelOpen) {
                panel.visibility = View.GONE
                btnSettings?.setBackgroundResource(R.drawable.bg_control_btn)
            } else {
                // Dynamically adjust scrollable panel height to fit screen safely
                val density = resources.displayMetrics.density
                val screenH = kotlin.math.min(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
                val currentY = controlLayoutParams.y
                val toolbarH = (44f * density).roundToInt()
                val marginBot = (16f * density).roundToInt()
                val availableSpace = screenH - currentY - toolbarH - marginBot
                val targetHeight = availableSpace.coerceIn((170f * density).roundToInt(), (235f * density).roundToInt())
                panel.layoutParams = panel.layoutParams.apply {
                    height = targetHeight
                }
                panel.visibility = View.VISIBLE
                btnSettings?.setBackgroundResource(R.drawable.bg_control_btn_active)
            }
        }

        seekThickness.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val strokeWidth = (progress.coerceAtLeast(1) * 0.5f)
                tvThickness.text = getString(R.string.label_thickness, strokeWidth)
                val alpha = seekOpacity.progress
                canvasOverlayView?.updateLineStyle(strokeWidth, alpha)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val progress = seekBar?.progress ?: 7
                val strokeWidth = (progress.coerceAtLeast(1) * 0.5f)
                val alpha = seekOpacity.progress
                prefsManager.saveLineStyle(strokeWidth, alpha)
            }
        })

        seekOpacity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val alpha = progress.coerceIn(25, 255)
                val pct = (alpha * 100 / 255)
                tvOpacity.text = getString(R.string.label_opacity, pct)
                val strokeWidth = (seekThickness.progress.coerceAtLeast(1) * 0.5f)
                canvasOverlayView?.updateLineStyle(strokeWidth, alpha)
                updateAllHandlesAlpha(alpha)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val alpha = (seekBar?.progress ?: 220).coerceIn(25, 255)
                val strokeWidth = (seekThickness.progress.coerceAtLeast(1) * 0.5f)
                prefsManager.saveLineStyle(strokeWidth, alpha)
                updateAllHandlesAlpha(alpha)
            }
        })

        // Ball Ring Size Slider Setup
        val initialBallRadius = prefsManager.getRingRadius()
        seekBallSize.progress = initialBallRadius.roundToInt().coerceIn(8, 30)
        tvBallSize.text = getString(R.string.label_ball_size, initialBallRadius)

        seekBallSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val radiusDp = progress.coerceIn(8, 30) * 1.0f
                tvBallSize.text = getString(R.string.label_ball_size, radiusDp)
                if (fromUser) {
                    canvasOverlayView?.setRingRadius(radiusDp)
                    applyTableBoundedWindow()
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val progress = seekBar?.progress ?: 14
                val radiusDp = progress.coerceIn(8, 30) * 1.0f
                prefsManager.saveRingRadius(radiusDp)
                canvasOverlayView?.setRingRadius(radiusDp)
                applyTableBoundedWindow()
            }
        })

        // 8. Feature 2: Line Color Picker (Preset Swatches & Hue Spectrum Slider)
        val density = resources.displayMetrics.density
        val btnColorWhite = controlView.findViewById<View>(R.id.btnColorWhite)
        val btnColorCyan = controlView.findViewById<View>(R.id.btnColorCyan)
        val btnColorYellow = controlView.findViewById<View>(R.id.btnColorYellow)
        val btnColorGreen = controlView.findViewById<View>(R.id.btnColorGreen)
        val btnColorRed = controlView.findViewById<View>(R.id.btnColorRed)
        val btnColorMagenta = controlView.findViewById<View>(R.id.btnColorMagenta)
        val viewColorPreview = controlView.findViewById<View>(R.id.viewColorPreview)
        val tvHue = controlView.findViewById<TextView>(R.id.tvHueValue)
        val seekHue = controlView.findViewById<SeekBar>(R.id.seekHue)

        val presetButtons = listOf(
            btnColorWhite to Color.WHITE,
            btnColorCyan to Color.parseColor("#00E5FF"),
            btnColorYellow to Color.parseColor("#FFD600"),
            btnColorGreen to Color.parseColor("#00E676"),
            btnColorRed to Color.parseColor("#FF1744"),
            btnColorMagenta to Color.parseColor("#D500F9")
        )

        var selectedColor = prefsManager.getLineColor()

        // 9. Bank Ray Color Swatches (Amber, Yellow, White, Cyan, Green, Red)
        val btnBankColorAmber = controlView.findViewById<View>(R.id.btnBankColorAmber)
        val btnBankColorYellow = controlView.findViewById<View>(R.id.btnBankColorYellow)
        val btnBankColorWhite = controlView.findViewById<View>(R.id.btnBankColorWhite)
        val btnBankColorCyan = controlView.findViewById<View>(R.id.btnBankColorCyan)
        val btnBankColorGreen = controlView.findViewById<View>(R.id.btnBankColorGreen)
        val btnBankColorRed = controlView.findViewById<View>(R.id.btnBankColorRed)
        val viewBankColorPreview = controlView.findViewById<View>(R.id.viewBankColorPreview)

        val bankPresetButtons = listOf(
            btnBankColorAmber to Color.parseColor("#FFAB00"),
            btnBankColorYellow to Color.parseColor("#FFD600"),
            btnBankColorWhite to Color.WHITE,
            btnBankColorCyan to Color.parseColor("#00E5FF"),
            btnBankColorGreen to Color.parseColor("#00E676"),
            btnBankColorRed to Color.parseColor("#FF1744")
        )

        var selectedBankColor = prefsManager.getBankLineColor()
        var isEditingBankColor = false

        // Rainbow gradient for seekHue track
        val rainbowColors = intArrayOf(
            Color.RED,
            Color.YELLOW,
            Color.GREEN,
            Color.CYAN,
            Color.BLUE,
            Color.MAGENTA,
            Color.RED
        )
        val rainbowGradient = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, rainbowColors).apply {
            cornerRadius = 4f * density
        }
        seekHue.progressDrawable = rainbowGradient

        val previewDrawable = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(selectedColor)
            setStroke((2f * density).roundToInt(), Color.WHITE)
        }
        viewColorPreview.background = previewDrawable

        val bankPreviewDrawable = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(selectedBankColor)
            setStroke((2f * density).roundToInt(), Color.WHITE)
        }
        viewBankColorPreview?.background = bankPreviewDrawable

        fun createSwatchDrawable(color: Int, isSelected: Boolean): GradientDrawable {
            return GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
                if (isSelected) {
                    setStroke((3f * density).roundToInt(), Color.WHITE)
                } else {
                    setStroke((1f * density).roundToInt(), Color.parseColor("#66FFFFFF"))
                }
            }
        }

        fun updateColorUI(color: Int) {
            selectedColor = color
            updateAllHandlesColor(color)
            for ((view, pColor) in presetButtons) {
                val isSelected = (color == pColor)
                view.background = createSwatchDrawable(pColor, isSelected)
            }
            previewDrawable.setColor(color)

            if (color == Color.WHITE) {
                tvHue.text = getString(R.string.label_hue_white)
            } else {
                val hsv = FloatArray(3)
                Color.colorToHSV(color, hsv)
                val hue = hsv[0].roundToInt().coerceIn(0, 360)
                tvHue.text = getString(R.string.label_hue, hue)
                seekHue.progress = hue
            }
        }

        fun updateBankColorUI(color: Int) {
            selectedBankColor = color
            for ((view, pColor) in bankPresetButtons) {
                val isSelected = (color == pColor)
                view?.background = createSwatchDrawable(pColor, isSelected)
            }
            bankPreviewDrawable.setColor(color)

            if (color == Color.WHITE) {
                tvHue.text = getString(R.string.label_hue_white)
            } else {
                val hsv = FloatArray(3)
                Color.colorToHSV(color, hsv)
                val hue = hsv[0].roundToInt().coerceIn(0, 360)
                tvHue.text = getString(R.string.label_hue, hue)
                seekHue.progress = hue
            }
        }

        updateColorUI(selectedColor)
        updateBankColorUI(selectedBankColor)

        viewColorPreview.setOnClickListener {
            isEditingBankColor = false
            updateColorUI(selectedColor)
        }

        viewBankColorPreview?.setOnClickListener {
            isEditingBankColor = true
            updateBankColorUI(selectedBankColor)
        }

        for ((view, color) in presetButtons) {
            view.setOnClickListener {
                isEditingBankColor = false
                updateColorUI(color)
                canvasOverlayView?.updateLineColor(color)
                prefsManager.saveLineColor(color)
            }
        }

        for ((view, color) in bankPresetButtons) {
            view?.setOnClickListener {
                isEditingBankColor = true
                updateBankColorUI(color)
                canvasOverlayView?.updateBankLineColor(color)
                prefsManager.saveBankLineColor(color)
            }
        }

        seekHue.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val hue = progress.toFloat()
                    val color = Color.HSVToColor(floatArrayOf(hue, 1f, 1f))
                    tvHue.text = getString(R.string.label_hue, progress)
                    if (isEditingBankColor) {
                        selectedBankColor = color
                        canvasOverlayView?.updateBankLineColor(color)
                        bankPreviewDrawable.setColor(color)
                        for ((view, pColor) in bankPresetButtons) {
                            view?.background = createSwatchDrawable(pColor, color == pColor)
                        }
                    } else {
                        selectedColor = color
                        canvasOverlayView?.updateLineColor(color)
                        updateAllHandlesColor(color)
                        previewDrawable.setColor(color)
                        for ((view, pColor) in presetButtons) {
                            view.background = createSwatchDrawable(pColor, color == pColor)
                        }
                    }
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                if (isEditingBankColor) {
                    prefsManager.saveBankLineColor(selectedBankColor)
                } else {
                    prefsManager.saveLineColor(selectedColor)
                }
            }
        })

        // In-App Update Trigger from Floating Bar
        val btnFloatingCheckUpdate = controlView.findViewById<TextView>(R.id.btnFloatingCheckUpdate)
        btnFloatingCheckUpdate?.setOnClickListener {
            Toast.makeText(this, R.string.update_checking, Toast.LENGTH_SHORT).show()
            AppUpdateManager.checkForUpdate(this, onResult = { info ->
                if (info.isUpdateAvailable) {
                    Toast.makeText(
                        this,
                        "Versi baru ${info.remoteVersionName} ditemukan! Mengunduh...",
                        Toast.LENGTH_LONG
                    ).show()
                    AppUpdateManager.downloadAndInstall(
                        context = this,
                        updateInfo = info,
                        onProgress = { percent ->
                            if (percent == 25 || percent == 50 || percent == 75) {
                                Toast.makeText(this, "Mengunduh pembaruan: $percent%", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onComplete = {
                            Toast.makeText(this, "Unduhan selesai. Membuka installer...", Toast.LENGTH_SHORT).show()
                        },
                        onError = { err ->
                            Toast.makeText(this, "Gagal mengunduh: ${err.message}", Toast.LENGTH_LONG).show()
                        }
                    )
                } else {
                    Toast.makeText(
                        this,
                        getString(R.string.update_already_latest, info.remoteVersionName),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }, onError = { err ->
                Toast.makeText(
                    this,
                    "${getString(R.string.update_check_failed)}: ${err.message}",
                    Toast.LENGTH_LONG
                ).show()
            })
        }


        // 10. Close Overlay
        btnClose.setOnClickListener {
            stopSelf()
        }

        try {
            windowManager.addView(controlView, controlLayoutParams)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Minimize full toolbar into a draggable mini capsule with ON/OFF switch.
     */
    private fun minimizeToolbar() {
        if (isCalibrationActive) {
            setCalibrationMode(false)
        }
        fullToolbarContainer?.visibility = View.GONE
        minimizedCapsule?.visibility = View.VISIBLE
        updateMiniOnOffButtonUI()
        floatingControlView?.let {
            try {
                windowManager.updateViewLayout(it, controlLayoutParams)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun expandToolbar() {
        minimizedCapsule?.visibility = View.GONE
        fullToolbarContainer?.visibility = View.VISIBLE
        floatingControlView?.let {
            try {
                windowManager.updateViewLayout(it, controlLayoutParams)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * Toggles overlay canvas and handles visibility.
     * Synchronizes state between full toolbar and mini capsule.
     */
    private fun toggleCanvasVisibility() {
        isCanvasHidden = !isCanvasHidden
        if (isCanvasHidden) {
            canvasOverlayView?.visibility = View.GONE
        } else {
            canvasOverlayView?.visibility = View.VISIBLE
        }
        applyTableBoundedWindow()
        syncHandlesVisibility()
        updateMiniOnOffButtonUI()
    }

    private fun updateMiniOnOffButtonUI() {
        if (isCanvasHidden) {
            btnMiniToggleOnOff?.text = getString(R.string.control_off)
            btnMiniToggleOnOff?.setBackgroundResource(R.drawable.bg_btn_off)
            btnMiniExpand?.setTextColor(ContextCompat.getColor(this, R.color.red_danger))
            btnMiniExpand?.alpha = 0.5f
        } else {
            btnMiniToggleOnOff?.text = getString(R.string.control_on)
            btnMiniToggleOnOff?.setBackgroundResource(R.drawable.bg_btn_on)
            btnMiniExpand?.setTextColor(ContextCompat.getColor(this, R.color.cyan_accent))
            btnMiniExpand?.alpha = 0.65f
        }
    }

    private fun syncHandlesVisibility() {
        if (isCanvasHidden || isCalibrationActive) {
            handleViewB?.visibility = View.GONE
            return
        }
        handleViewB?.visibility = View.VISIBLE
    }

    private fun updateAllHandlesAlpha(alpha: Int) {
        handleViewB?.updateHandleAlpha(alpha)
    }

    private fun updateAllHandlesColor(color: Int) {
        handleViewB?.updateColor(color)
    }

    private fun repositionHandlesToAimPoints() {
        val canvas = canvasOverlayView ?: return
        val hB = handleViewB ?: return

        val gripPosB = calculateGripFromAim(canvas.pointB, isOffsetInvertedB)

        handleParamsB.x = (gripPosB.x - handleSizePx / 2f).roundToInt()
        handleParamsB.y = (gripPosB.y - handleSizePx / 2f).roundToInt()

        try {
            windowManager.updateViewLayout(hB, handleParamsB)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        canvas.setPointB(canvas.pointB.x, canvas.pointB.y, gripPosB.x, gripPosB.y)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyTableBoundedWindow()
        repositionHandlesToAimPoints()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDraggableBar(dragHandle: View, entireView: View) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false
        val touchSlop = 8f * resources.displayMetrics.density

        dragHandle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = controlLayoutParams.x
                    initialY = controlLayoutParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (kotlin.math.hypot(dx, dy) > touchSlop) {
                        isDragging = true
                        controlLayoutParams.x = initialX + dx.roundToInt()
                        controlLayoutParams.y = initialY + dy.roundToInt()
                        try {
                            windowManager.updateViewLayout(entireView, controlLayoutParams)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        // Single Tap: Tutup (minimize) toolbar
                        minimizeToolbar()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    isDragging = false
                    true
                }
                else -> false
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupSmartMiniBubble(bubbleView: TextView, rootView: View) {
        var initialX = 0
        var initialY = 0
        var downRawX = 0f
        var downRawY = 0f
        var isDragging = false
        var isLongPressTriggered = false
        val touchSlop = 8f * resources.displayMetrics.density

        val longPressRunnable = Runnable {
            if (!isDragging) {
                isLongPressTriggered = true
                bubbleView.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                toggleCanvasVisibility()
            }
        }

        bubbleView.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = controlLayoutParams.x
                    initialY = controlLayoutParams.y
                    downRawX = event.rawX
                    downRawY = event.rawY
                    isDragging = false
                    isLongPressTriggered = false
                    mainHandler.postDelayed(longPressRunnable, 400L)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (kotlin.math.hypot(dx, dy) > touchSlop) {
                        isDragging = true
                        mainHandler.removeCallbacks(longPressRunnable)
                        controlLayoutParams.x = initialX + dx.roundToInt()
                        controlLayoutParams.y = initialY + dy.roundToInt()
                        try {
                            windowManager.updateViewLayout(rootView, controlLayoutParams)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    mainHandler.removeCallbacks(longPressRunnable)
                    if (!isDragging && !isLongPressTriggered) {
                        // Single Tap: Buka kembali toolbar penuh
                        expandToolbar()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    mainHandler.removeCallbacks(longPressRunnable)
                    true
                }
                else -> false
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false

        mainHandler.removeCallbacksAndMessages(null)

        if (isCalibrationActive) {
            isCalibrationActive = false
            canvasOverlayView?.setCalibrating(false)
        }

        // Remove canvas view
        canvasOverlayView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            canvasOverlayView = null
        }

        // Remove floating handle
        handleViewB?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            handleViewB = null
        }

        // Remove control bar
        floatingControlView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            floatingControlView = null
        }
    }

    companion object {
        const val CHANNEL_ID = "toolkit_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP_SERVICE = "com.utility.toolkit.ACTION_STOP"

        /** Neutral window title; the default is the leaking view class name. */
        private const val WINDOW_TITLE = "Utility"

        @Volatile
        var isServiceRunning = false
            private set
    }
}
