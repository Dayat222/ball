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
import android.graphics.PixelFormat
import android.graphics.PointF
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt

/**
 * Multi-window foreground service managing:
 * 1. Always pass-through canvas overlay (FLAG_NOT_TOUCHABLE).
 * 2. Independent floating handle windows for points A, B, and C.
 * 3. Draggable compact floating control bar with Quick-Ghost (4s auto-hide).
 */
class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var prefsManager: PrefsManager

    // Canvas Overlay View (Always pass-through touch)
    private var canvasOverlayView: CanvasOverlayView? = null
    private lateinit var canvasLayoutParams: WindowManager.LayoutParams

    // Independent Floating Handles
    private var handleViewA: HandleView? = null
    private var handleViewB: HandleView? = null
    private var handleViewC: HandleView? = null

    private lateinit var handleParamsA: WindowManager.LayoutParams
    private lateinit var handleParamsB: WindowManager.LayoutParams
    private lateinit var handleParamsC: WindowManager.LayoutParams
    private var handleSizePx = 0

    // Floating Control Bar
    private var floatingControlView: View? = null
    private lateinit var controlLayoutParams: WindowManager.LayoutParams
    private var btnGhost: TextView? = null

    // State Tracking
    private var isCanvasHidden = false
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

        // 1. Promote to Foreground Service
        startForegroundServiceWithNotification()

        // 2. Initialize and attach Always Pass-Through Canvas Overlay View
        setupCanvasOverlay()

        // 3. Initialize and attach 3 Independent Floating Handles
        setupFloatingHandles()

        // 4. Initialize and attach Floating Control View
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

    /**
     * Requirement 1: CanvasOverlayView is PERMANENTLY pass-through (FLAG_NOT_TOUCHABLE).
     */
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
     * Requirement 2: Independent Floating HandleViews (42dp x 42dp) for Points A, B, and C.
     */
    private fun setupFloatingHandles() {
        val displayMetrics = resources.displayMetrics
        val screenW = displayMetrics.widthPixels.toFloat()
        val screenH = displayMetrics.heightPixels.toFloat()

        val (ptA, ptB, ptC) = prefsManager.getPoints(screenW, screenH)

        // Initialize positions in canvas
        canvasOverlayView?.let {
            it.setPointA(ptA.x, ptA.y)
            it.setPointB(ptB.x, ptB.y)
            it.setPointC(ptC.x, ptC.y)
        }

        // 1. Handle A
        handleViewA = HandleView(this, "A")
        handleParamsA = createHandleLayoutParams(ptA)
        setupHandleTouchListener(handleViewA!!, handleParamsA) { cx, cy ->
            canvasOverlayView?.setPointA(cx, cy)
        }
        windowManager.addView(handleViewA, handleParamsA)

        // 2. Handle B
        handleViewB = HandleView(this, "B")
        handleParamsB = createHandleLayoutParams(ptB)
        setupHandleTouchListener(handleViewB!!, handleParamsB) { cx, cy ->
            canvasOverlayView?.setPointB(cx, cy)
        }
        windowManager.addView(handleViewB, handleParamsB)

        // 3. Handle C (visible only in MANUAL_3_POINT mode)
        handleViewC = HandleView(this, "C")
        handleParamsC = createHandleLayoutParams(ptC)
        setupHandleTouchListener(handleViewC!!, handleParamsC) { cx, cy ->
            canvasOverlayView?.setPointC(cx, cy)
        }
        windowManager.addView(handleViewC, handleParamsC)

        // Sync initial visibility of Handle C with active mode
        val activeModeOrdinal = prefsManager.getMode()
        val isManual3Pt = activeModeOrdinal == CanvasOverlayView.RulerMode.MANUAL_3_POINT.ordinal
        handleViewC?.visibility = if (isManual3Pt) View.VISIBLE else View.GONE
    }

    private fun createHandleLayoutParams(initialCenter: PointF): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            handleSizePx,
            handleSizePx,
            getOverlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (initialCenter.x - handleSizePx / 2f).toInt()
            y = (initialCenter.y - handleSizePx / 2f).toInt()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupHandleTouchListener(
        view: View,
        params: WindowManager.LayoutParams,
        onCenterMoved: (Float, Float) -> Unit
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

                    val centerX = params.x + (handleSizePx / 2f)
                    val centerY = params.y + (handleSizePx / 2f)
                    onCenterMoved(centerX, centerY)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    // Persist positions when dragging finishes
                    persistHandlePositions()
                    true
                }
                else -> false
            }
        }
    }

    private fun persistHandlePositions() {
        val half = handleSizePx / 2f
        val ptA = PointF(handleParamsA.x + half, handleParamsA.y + half)
        val ptB = PointF(handleParamsB.x + half, handleParamsB.y + half)
        val ptC = PointF(handleParamsC.x + half, handleParamsC.y + half)
        prefsManager.savePoints(ptA, ptB, ptC)
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

        // References to buttons
        val btnDrag = controlView.findViewById<TextView>(R.id.btnDragHandle)
        btnGhost = controlView.findViewById(R.id.btnQuickGhost)
        val btnVisibility = controlView.findViewById<TextView>(R.id.btnToggleVisibility)
        val btnMode = controlView.findViewById<TextView>(R.id.btnCycleMode)
        val btnCalibrate = controlView.findViewById<TextView>(R.id.btnToggleCalibrate)
        val btnClose = controlView.findViewById<TextView>(R.id.btnCloseOverlay)

        // 1. Dragging toolbar
        setupDraggableBar(btnDrag, controlView)

        // 2. Requirement 3: Quick-Ghost (Auto-Hide 4 Detik)
        btnGhost?.setOnClickListener {
            toggleQuickGhost()
        }

        // 3. Hide / Show canvas overlay and handles manually
        btnVisibility.setOnClickListener {
            if (isGhostActive) {
                restoreFromGhost()
            }

            isCanvasHidden = !isCanvasHidden
            if (isCanvasHidden) {
                canvasOverlayView?.visibility = View.GONE
                handleViewA?.visibility = View.GONE
                handleViewB?.visibility = View.GONE
                handleViewC?.visibility = View.GONE

                btnVisibility.text = getString(R.string.control_show)
                btnVisibility.setBackgroundResource(R.drawable.bg_control_btn_active)
            } else {
                canvasOverlayView?.visibility = View.VISIBLE
                handleViewA?.visibility = View.VISIBLE
                handleViewB?.visibility = View.VISIBLE
                syncHandleCVisibility()

                btnVisibility.text = getString(R.string.control_hide)
                btnVisibility.setBackgroundResource(R.drawable.bg_control_btn)
            }
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

            syncHandleCVisibility()
        }

        // 5. Calibrate Boundary Box
        btnCalibrate.setOnClickListener {
            val canvas = canvasOverlayView ?: return@setOnClickListener
            val newMargin = canvas.cycleBoundaryPreset()
            Toast.makeText(this, "Boundary Margin: $newMargin", Toast.LENGTH_SHORT).show()
        }

        // 6. Close Overlay
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
     * Requirement 3: Quick-Ghost 4s auto-hide implementation.
     */
    private fun toggleQuickGhost() {
        if (isGhostActive) {
            // Cancel early if pressed while ghosting
            restoreFromGhost()
            return
        }

        isGhostActive = true

        // Temporarily hide canvas and all 3 handles
        canvasOverlayView?.visibility = View.GONE
        handleViewA?.visibility = View.GONE
        handleViewB?.visibility = View.GONE
        handleViewC?.visibility = View.GONE

        // Update button UI
        btnGhost?.text = getString(R.string.control_ghosting)
        btnGhost?.setBackgroundResource(R.drawable.bg_control_btn_active)

        // Automatically restore after 4000ms
        ghostHandler.postDelayed(ghostRestoreRunnable, 4000)
    }

    private fun restoreFromGhost() {
        isGhostActive = false
        ghostHandler.removeCallbacks(ghostRestoreRunnable)

        // Reset button UI
        btnGhost?.text = getString(R.string.control_ghost)
        btnGhost?.setBackgroundResource(R.drawable.bg_control_btn)

        // Restore views if not manually hidden
        if (!isCanvasHidden) {
            canvasOverlayView?.visibility = View.VISIBLE
            handleViewA?.visibility = View.VISIBLE
            handleViewB?.visibility = View.VISIBLE
            syncHandleCVisibility()
        }
    }

    private fun syncHandleCVisibility() {
        if (isCanvasHidden || isGhostActive) {
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
