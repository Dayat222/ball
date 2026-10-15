package com.utility.screenprotractor

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.PointF
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
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
import kotlin.math.roundToInt

/**
 * Multi-window foreground service managing:
 * 1. Pass-through canvas overlay with 6-pocket radial lines and offset tethers.
 * 2. Independent floating handle windows with vertical offset to prevent finger obstruction.
 * 3. Draggable compact floating control bar with collapsible minimize bubble,
 *    Quick-Ghost 4s, calibration toggle, 6-line toggle, and line sliders.
 */
class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var prefsManager: PrefsManager

    // Canvas Overlay View
    private var canvasOverlayView: CanvasOverlayView? = null
    private lateinit var canvasLayoutParams: WindowManager.LayoutParams

    // Independent Floating Handles (Grip Pads)
    private var handleViewA: HandleView? = null
    private var handleViewB: HandleView? = null
    private var handleViewC: HandleView? = null

    private lateinit var handleParamsA: WindowManager.LayoutParams
    private lateinit var handleParamsB: WindowManager.LayoutParams
    private lateinit var handleParamsC: WindowManager.LayoutParams
    private var handleSizePx = 0
    private var handleOffsetY = 140f

    // Floating Control Bar
    private var floatingControlView: View? = null
    private lateinit var controlLayoutParams: WindowManager.LayoutParams

    private var fullToolbarContainer: View? = null
    private var minimizedCapsule: View? = null
    private var btnMiniDrag: TextView? = null
    private var btnMiniToggleOnOff: TextView? = null
    private var btnMiniExpand: TextView? = null
    private var btnGhost: TextView? = null
    private var btnVisibility: TextView? = null
    private var btnCalibrate: TextView? = null
    private var btnResetBound: TextView? = null
    private var btnSixLines: TextView? = null
    private var btnSettings: TextView? = null
    private var panelSettings: View? = null

    // State Tracking
    private var isCanvasHidden = false
    private var isCalibrationActive = false
    private var isGhostActive = false
    private val ghostHandler = Handler(Looper.getMainLooper())
    private val ghostRestoreRunnable = Runnable { restoreFromGhost() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        prefsManager = PrefsManager(this)

        val density = resources.displayMetrics.density
        handleSizePx = (42f * density).roundToInt()
        handleOffsetY = 50f * density // ~140px on xxxhdpi / xxhdpi

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

    private fun setupCanvasOverlay() {
        canvasOverlayView = CanvasOverlayView(this)

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
            gravity = Gravity.TOP or Gravity.START
        }

        try {
            windowManager.addView(canvasOverlayView, canvasLayoutParams)
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
            canvasLayoutParams.flags = canvasLayoutParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            try {
                windowManager.updateViewLayout(canvas, canvasLayoutParams)
            } catch (e: Exception) {
                e.printStackTrace()
            }

            canvas.setCalibrating(true)

            handleViewA?.visibility = View.GONE
            handleViewB?.visibility = View.GONE
            handleViewC?.visibility = View.GONE

            btnCalibrate?.text = getString(R.string.control_save_bound)
            btnCalibrate?.setBackgroundResource(R.drawable.bg_control_btn_active)
            btnCalibrate?.setTextColor(ContextCompat.getColor(this, R.color.green_status))
            btnResetBound?.visibility = View.VISIBLE
        } else {
            canvas.setCalibrating(false)

            canvasLayoutParams.flags = canvasLayoutParams.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            try {
                windowManager.updateViewLayout(canvas, canvasLayoutParams)
            } catch (e: Exception) {
                e.printStackTrace()
            }

            if (!isCanvasHidden && !isGhostActive) {
                handleViewA?.visibility = View.VISIBLE
                handleViewB?.visibility = View.VISIBLE
                syncHandleCVisibility()
            }

            btnCalibrate?.text = getString(R.string.control_calibrate)
            btnCalibrate?.setBackgroundResource(R.drawable.bg_control_btn)
            btnCalibrate?.setTextColor(Color.WHITE)
            btnResetBound?.visibility = View.GONE

            Toast.makeText(this, R.string.toast_calib_saved, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Feature 2: Anti-Finger Obstruction (Offset Touch Handles).
     * The grip pad is positioned below the actual target ball to keep the ball visible.
     */
    private fun setupFloatingHandles() {
        val displayMetrics = resources.displayMetrics
        val screenW = displayMetrics.widthPixels.toFloat()
        val screenH = displayMetrics.heightPixels.toFloat()

        val (ptA, ptB, ptC) = prefsManager.getPoints(screenW, screenH)

        // 1. Handle A
        handleViewA = HandleView(this, "A")
        val gripPosA = calculateGripFromAim(ptA, screenH)
        handleParamsA = createHandleLayoutParams(gripPosA)
        setupHandleTouchListener(handleViewA!!, handleParamsA, screenH) { aimX, aimY, gripX, gripY ->
            canvasOverlayView?.setPointA(aimX, aimY, gripX, gripY)
        }
        windowManager.addView(handleViewA, handleParamsA)
        canvasOverlayView?.setPointA(ptA.x, ptA.y, gripPosA.x, gripPosA.y)

        // 2. Handle B (Target Ball)
        handleViewB = HandleView(this, "B")
        val gripPosB = calculateGripFromAim(ptB, screenH)
        handleParamsB = createHandleLayoutParams(gripPosB)
        setupHandleTouchListener(handleViewB!!, handleParamsB, screenH) { aimX, aimY, gripX, gripY ->
            canvasOverlayView?.setPointB(aimX, aimY, gripX, gripY)
        }
        windowManager.addView(handleViewB, handleParamsB)
        canvasOverlayView?.setPointB(ptB.x, ptB.y, gripPosB.x, gripPosB.y)

        // 3. Handle C
        handleViewC = HandleView(this, "C")
        val gripPosC = calculateGripFromAim(ptC, screenH)
        handleParamsC = createHandleLayoutParams(gripPosC)
        setupHandleTouchListener(handleViewC!!, handleParamsC, screenH) { aimX, aimY, gripX, gripY ->
            canvasOverlayView?.setPointC(aimX, aimY, gripX, gripY)
        }
        windowManager.addView(handleViewC, handleParamsC)
        canvasOverlayView?.setPointC(ptC.x, ptC.y, gripPosC.x, gripPosC.y)

        val activeModeOrdinal = prefsManager.getMode()
        val isManual3Pt = activeModeOrdinal == CanvasOverlayView.RulerMode.MANUAL_3_POINT.ordinal
        handleViewC?.visibility = if (isManual3Pt) View.VISIBLE else View.GONE
    }

    private fun calculateGripFromAim(aim: PointF, screenH: Float): PointF {
        val targetGripY = aim.y + handleOffsetY
        val gripY = if (targetGripY + handleSizePx > screenH - (20f * resources.displayMetrics.density)) {
            aim.y - handleOffsetY
        } else {
            targetGripY
        }
        return PointF(aim.x, gripY)
    }

    private fun createHandleLayoutParams(center: PointF): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            handleSizePx,
            handleSizePx,
            getOverlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (center.x - handleSizePx / 2f).toInt()
            y = (center.y - handleSizePx / 2f).toInt()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupHandleTouchListener(
        view: View,
        params: WindowManager.LayoutParams,
        screenH: Float,
        onMoved: (aimX: Float, aimY: Float, gripX: Float, gripY: Float) -> Unit
    ) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    params.x = initialX + dx
                    params.y = initialY + dy

                    try {
                        windowManager.updateViewLayout(view, params)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }

                    val gripX = params.x + (handleSizePx / 2f)
                    val gripY = params.y + (handleSizePx / 2f)

                    // Calculate aim coordinate above the grip pad (anti-finger obstruction)
                    val aimX = gripX
                    val aimY = (gripY - handleOffsetY).coerceIn(0f, screenH)

                    onMoved(aimX, aimY, gripX, gripY)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    persistHandlePositions()
                    true
                }
                else -> false
            }
        }
    }

    private fun persistHandlePositions() {
        val canvas = canvasOverlayView ?: return
        prefsManager.savePoints(canvas.pointA, canvas.pointB, canvas.pointC)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupFloatingControl() {
        val inflater = LayoutInflater.from(this)
        val controlView = inflater.inflate(R.layout.layout_floating_control, null)
        floatingControlView = controlView

        controlLayoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            getOverlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 40
            y = 140
        }

        // References to UI components
        fullToolbarContainer = controlView.findViewById(R.id.fullToolbarContainer)
        minimizedCapsule = controlView.findViewById(R.id.minimizedCapsule)
        btnMiniDrag = controlView.findViewById(R.id.btnMiniDrag)
        btnMiniToggleOnOff = controlView.findViewById(R.id.btnMiniToggleOnOff)
        btnMiniExpand = controlView.findViewById(R.id.btnMiniExpand)

        val btnDrag = controlView.findViewById<TextView>(R.id.btnDragHandle)
        btnGhost = controlView.findViewById(R.id.btnQuickGhost)
        btnVisibility = controlView.findViewById(R.id.btnToggleVisibility)
        val btnMode = controlView.findViewById<TextView>(R.id.btnCycleMode)
        btnCalibrate = controlView.findViewById(R.id.btnToggleCalibrate)
        btnResetBound = controlView.findViewById(R.id.btnResetBound)
        btnSixLines = controlView.findViewById(R.id.btnToggleSixLines)
        btnSettings = controlView.findViewById(R.id.btnToggleSettings)
        val btnMinimize = controlView.findViewById<TextView>(R.id.btnMinimize)
        val btnClose = controlView.findViewById<TextView>(R.id.btnCloseOverlay)

        panelSettings = controlView.findViewById(R.id.panelSettings)
        val tvThickness = controlView.findViewById<TextView>(R.id.tvThicknessValue)
        val seekThickness = controlView.findViewById<SeekBar>(R.id.seekThickness)
        val tvOpacity = controlView.findViewById<TextView>(R.id.tvOpacityValue)
        val seekOpacity = controlView.findViewById<SeekBar>(R.id.seekOpacity)

        // 1. Dragging toolbar
        setupDraggableBar(btnDrag, controlView)

        // Mini capsule dragging and controls
        btnMiniDrag?.let { setupDraggableBar(it, controlView) }
        btnMiniToggleOnOff?.setOnClickListener {
            if (isGhostActive) {
                restoreFromGhost()
            }
            toggleCanvasVisibility()
        }
        btnMiniExpand?.setOnClickListener {
            expandToolbar()
        }

        // 2. Quick-Ghost (Auto-Hide 4s)
        btnGhost?.setOnClickListener {
            if (isCalibrationActive) {
                setCalibrationMode(false)
            }
            toggleQuickGhost()
        }

        // 3. Hide / Show canvas overlay and handles manually
        btnVisibility?.setOnClickListener {
            if (isCalibrationActive) {
                setCalibrationMode(false)
            }
            if (isGhostActive) {
                restoreFromGhost()
            }
            toggleCanvasVisibility()
        }

        // 4. Cycle Mode
        btnMode.setOnClickListener {
            val canvas = canvasOverlayView ?: return@setOnClickListener
            val nextMode = canvas.cycleRulerMode()

            btnMode.text = when (nextMode) {
                CanvasOverlayView.RulerMode.AUTO_REFLECT -> getString(R.string.control_mode_auto)
                CanvasOverlayView.RulerMode.MANUAL_3_POINT -> getString(R.string.control_mode_manual)
                CanvasOverlayView.RulerMode.EDGE_MARKER_ONLY -> getString(R.string.control_mode_edge)
            }

            if (!isCalibrationActive) {
                syncHandleCVisibility()
            }
        }

        // 5. Calibration Mode Toggle & Boundary Reset
        btnCalibrate?.setOnClickListener {
            if (isGhostActive) {
                restoreFromGhost()
            }
            if (isCanvasHidden) {
                toggleCanvasVisibility()
            }
            setCalibrationMode(!isCalibrationActive)
        }

        btnResetBound?.setOnClickListener {
            canvasOverlayView?.resetToProportionalCenter()
            Toast.makeText(this, R.string.toast_calib_reset, Toast.LENGTH_SHORT).show()
        }

        // 6. Feature 1: Toggle 6-Pocket Radial Lines on Target Ball
        updateSixLinesButtonUI()
        btnSixLines?.setOnClickListener {
            val canvas = canvasOverlayView ?: return@setOnClickListener
            canvas.toggleSixPocketLines()
            updateSixLinesButtonUI()
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
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val alpha = (seekBar?.progress ?: 220).coerceIn(25, 255)
                val strokeWidth = (seekThickness.progress.coerceAtLeast(1) * 0.5f)
                prefsManager.saveLineStyle(strokeWidth, alpha)
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
            for ((view, pColor) in presetButtons) {
                val isSelected = (color == pColor)
                view.background = createSwatchDrawable(pColor, isSelected)
            }
            viewColorPreview.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
                setStroke((2f * density).roundToInt(), Color.WHITE)
            }

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

        for ((view, color) in presetButtons) {
            view.setOnClickListener {
                updateColorUI(color)
                canvasOverlayView?.updateLineColor(color)
                prefsManager.saveLineColor(color)
            }
        }

        seekHue.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val hue = progress.toFloat()
                    val color = Color.HSVToColor(floatArrayOf(hue, 1f, 1f))
                    selectedColor = color
                    tvHue.text = getString(R.string.label_hue, progress)
                    canvasOverlayView?.updateLineColor(color)

                    for ((view, pColor) in presetButtons) {
                        view.background = createSwatchDrawable(pColor, color == pColor)
                    }
                    viewColorPreview.background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(color)
                        setStroke((2f * density).roundToInt(), Color.WHITE)
                    }
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val hue = (seekBar?.progress ?: 185).toFloat()
                val color = Color.HSVToColor(floatArrayOf(hue, 1f, 1f))
                prefsManager.saveLineColor(color)
            }
        })

        // 9. Collapsible Floating Control (Minimize Toolbar)
        btnMinimize.setOnClickListener {
            minimizeToolbar()
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

    private fun updateSixLinesButtonUI() {
        val isActive = canvasOverlayView?.showSixPocketLines ?: true
        if (isActive) {
            btnSixLines?.setBackgroundResource(R.drawable.bg_control_btn_active)
            btnSixLines?.setTextColor(ContextCompat.getColor(this, R.color.cyan_accent))
        } else {
            btnSixLines?.setBackgroundResource(R.drawable.bg_control_btn)
            btnSixLines?.setTextColor(Color.WHITE)
        }
    }

    /**
     * Requirement 3: Minimize full toolbar into a draggable mini capsule with ON/OFF switch.
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
        updateVisibilityButtonUI()
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
            handleViewA?.visibility = View.GONE
            handleViewB?.visibility = View.GONE
            handleViewC?.visibility = View.GONE
        } else {
            canvasOverlayView?.visibility = View.VISIBLE
            handleViewA?.visibility = View.VISIBLE
            handleViewB?.visibility = View.VISIBLE
            syncHandleCVisibility()
        }
        updateVisibilityButtonUI()
        updateMiniOnOffButtonUI()
    }

    private fun updateVisibilityButtonUI() {
        if (isCanvasHidden) {
            btnVisibility?.text = getString(R.string.control_show)
            btnVisibility?.setBackgroundResource(R.drawable.bg_control_btn_active)
        } else {
            btnVisibility?.text = getString(R.string.control_hide)
            btnVisibility?.setBackgroundResource(R.drawable.bg_control_btn)
        }
    }

    private fun updateMiniOnOffButtonUI() {
        if (isCanvasHidden) {
            btnMiniToggleOnOff?.text = getString(R.string.control_off)
            btnMiniToggleOnOff?.setBackgroundResource(R.drawable.bg_btn_off)
        } else {
            btnMiniToggleOnOff?.text = getString(R.string.control_on)
            btnMiniToggleOnOff?.setBackgroundResource(R.drawable.bg_btn_on)
        }
    }

    private fun toggleQuickGhost() {
        if (isGhostActive) {
            restoreFromGhost()
            return
        }

        isGhostActive = true

        canvasOverlayView?.visibility = View.GONE
        handleViewA?.visibility = View.GONE
        handleViewB?.visibility = View.GONE
        handleViewC?.visibility = View.GONE

        btnGhost?.text = getString(R.string.control_ghosting)
        btnGhost?.setBackgroundResource(R.drawable.bg_control_btn_active)

        ghostHandler.postDelayed(ghostRestoreRunnable, 4000)
    }

    private fun restoreFromGhost() {
        isGhostActive = false
        ghostHandler.removeCallbacks(ghostRestoreRunnable)

        btnGhost?.text = getString(R.string.control_ghost)
        btnGhost?.setBackgroundResource(R.drawable.bg_control_btn)

        if (!isCanvasHidden) {
            canvasOverlayView?.visibility = View.VISIBLE
            if (!isCalibrationActive) {
                handleViewA?.visibility = View.VISIBLE
                handleViewB?.visibility = View.VISIBLE
                syncHandleCVisibility()
            }
        }
        updateVisibilityButtonUI()
        updateMiniOnOffButtonUI()
    }

    private fun syncHandleCVisibility() {
        if (isCanvasHidden || isGhostActive || isCalibrationActive) {
            handleViewC?.visibility = View.GONE
            return
        }

        val currentMode = canvasOverlayView?.currentMode ?: CanvasOverlayView.RulerMode.AUTO_REFLECT
        handleViewC?.visibility = if (currentMode == CanvasOverlayView.RulerMode.MANUAL_3_POINT) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDraggableBar(dragHandle: View, entireView: View) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        dragHandle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = controlLayoutParams.x
                    initialY = controlLayoutParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    controlLayoutParams.x = initialX + dx
                    controlLayoutParams.y = initialY + dy
                    try {
                        windowManager.updateViewLayout(entireView, controlLayoutParams)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                    true
                }
                else -> false
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false

        ghostHandler.removeCallbacksAndMessages(null)

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

        // Remove floating handles
        handleViewA?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            handleViewA = null
        }

        handleViewB?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            handleViewB = null
        }

        handleViewC?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            handleViewC = null
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
        const val CHANNEL_ID = "screen_angle_ruler_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP_SERVICE = "com.utility.screenprotractor.ACTION_STOP"

        @Volatile
        var isServiceRunning = false
            private set
    }
}
