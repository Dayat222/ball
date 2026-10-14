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
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Multi-window foreground service managing the transparent canvas overlay
 * and the draggable compact floating control bar.
 */
class OverlayService : Service() {

    private lateinit var windowManager: WindowManager

    // Canvas Overlay
    private var canvasOverlayView: CanvasOverlayView? = null
    private lateinit var canvasLayoutParams: WindowManager.LayoutParams

    // Floating Control Bar
    private var floatingControlView: View? = null
    private lateinit var controlLayoutParams: WindowManager.LayoutParams

    // State tracking
    private var isCanvasHidden = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        // 1. Promote to Foreground Service
        startForegroundServiceWithNotification()

        // 2. Initialize and attach Canvas Overlay View
        setupCanvasOverlay()

        // 3. Initialize and attach Floating Control View
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

    private fun setupCanvasOverlay() {
        canvasOverlayView = CanvasOverlayView(this)

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        canvasLayoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
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

    @SuppressLint("ClickableViewAccessibility")
    private fun setupFloatingControl() {
        val inflater = LayoutInflater.from(this)
        val controlView = inflater.inflate(R.layout.layout_floating_control, null)
        floatingControlView = controlView

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        controlLayoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
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
        val btnLock = controlView.findViewById<TextView>(R.id.btnToggleLock)
        val btnVisibility = controlView.findViewById<TextView>(R.id.btnToggleVisibility)
        val btnMode = controlView.findViewById<TextView>(R.id.btnCycleMode)
        val btnCalibrate = controlView.findViewById<TextView>(R.id.btnToggleCalibrate)
        val btnClose = controlView.findViewById<TextView>(R.id.btnCloseOverlay)

        // 1. Dragging control bar
        setupDraggableBar(btnDrag, controlView)

        // 2. Lock / Edit mode toggle
        btnLock.setOnClickListener {
            val canvas = canvasOverlayView ?: return@setOnClickListener
            val isNowLocked = !canvas.isLocked
            canvas.setLocked(isNowLocked)

            if (isNowLocked) {
                // Pass-through mode: touches penetrate through to underlying apps
                canvasLayoutParams.flags = canvasLayoutParams.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                btnLock.text = getString(R.string.control_edit)
                btnLock.setBackgroundResource(R.drawable.bg_control_btn_active)
                btnLock.setTextColor(ContextCompat.getColor(this, R.color.yellow_accent))

                // Turn off calibration if locked
                btnCalibrate.setBackgroundResource(R.drawable.bg_control_btn)
                btnCalibrate.setTextColor(Color.WHITE)
            } else {
                // Edit mode: receive touch events
                canvasLayoutParams.flags = canvasLayoutParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                btnLock.text = getString(R.string.control_lock)
                btnLock.setBackgroundResource(R.drawable.bg_control_btn)
                btnLock.setTextColor(Color.WHITE)
            }

            try {
                windowManager.updateViewLayout(canvas, canvasLayoutParams)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // 3. Hide / Show canvas overlay
        btnVisibility.setOnClickListener {
            val canvas = canvasOverlayView ?: return@setOnClickListener
            isCanvasHidden = !isCanvasHidden

            if (isCanvasHidden) {
                canvas.visibility = View.GONE
                btnVisibility.text = getString(R.string.control_show)
                btnVisibility.setBackgroundResource(R.drawable.bg_control_btn_active)
            } else {
                canvas.visibility = View.VISIBLE
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
        }

        // 5. Calibrate Boundary Box
        btnCalibrate.setOnClickListener {
            val canvas = canvasOverlayView ?: return@setOnClickListener
            if (canvas.isLocked) {
                // Auto-unlock when entering calibration
                canvas.setLocked(false)
                canvasLayoutParams.flags = canvasLayoutParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                try {
                    windowManager.updateViewLayout(canvas, canvasLayoutParams)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                btnLock.text = getString(R.string.control_lock)
                btnLock.setBackgroundResource(R.drawable.bg_control_btn)
                btnLock.setTextColor(Color.WHITE)
            }

            val isNowCalibrating = !canvas.isCalibrating
            canvas.setCalibrating(isNowCalibrating)

            if (isNowCalibrating) {
                btnCalibrate.setBackgroundResource(R.drawable.bg_control_btn_active)
                btnCalibrate.setTextColor(ContextCompat.getColor(this, R.color.cyan_accent))
            } else {
                btnCalibrate.setBackgroundResource(R.drawable.bg_control_btn)
                btnCalibrate.setTextColor(Color.WHITE)
            }
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

        // Remove canvas view
        canvasOverlayView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            canvasOverlayView = null
        }

        // Remove control view
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
