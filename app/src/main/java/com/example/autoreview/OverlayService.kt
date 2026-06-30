package com.example.autoreview

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import androidx.core.content.edit

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var prefs: android.content.SharedPreferences
    private var overlayView: View? = null
    private var overlayIcon: CustomIconView? = null
    private var trashView: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var trashParams: WindowManager.LayoutParams? = null
    private var isViewAdded = false
    private var isOverTrash = false

    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var initialX = 0
    private var initialY = 0
    private var lastTapTime = 0L

    enum class AutomationState { IDLE, RUNNING, ERROR, DONE }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        prefs = getSharedPreferences("overlay_prefs", MODE_PRIVATE)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.getStringExtra(EXTRA_ACTION)) {
            ACTION_UPDATE_STATE -> {
                val state = intent.getStringExtra(EXTRA_STATE)
                    ?.let { runCatching { AutomationState.valueOf(it) }.getOrNull() }
                if (state != null) updateVisualState(state)
            }
            ACTION_STOP -> {
                AutoFillAccessibilityService.instance?.stopAutomation()
                updateVisualState(AutomationState.IDLE)
            }
        }
        if (!isViewAdded) showOverlay()
        _isRunningFlow.value = true
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                createNotification(AutomationState.IDLE),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, createNotification(AutomationState.IDLE))
        }
        return START_STICKY
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun showOverlay() {

        val frameLayout = android.widget.FrameLayout(this).apply {
            val shape = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(IDLE_COLOR)
            }
            background = shape
            elevation = dpToPx(8).toFloat()

            overlayIcon = CustomIconView(this@OverlayService).apply {
                layoutParams = android.widget.FrameLayout.LayoutParams(
                    dpToPx(36),
                    dpToPx(36),
                    Gravity.CENTER
                )
            }
            addView(overlayIcon)

            @android.annotation.SuppressLint("ClickableViewAccessibility")
            setOnTouchListener { _, event ->
                handleTouch(event)
                true
            }
        }
        overlayView = frameLayout

        val trashSize = dpToPx(64)
        trashView = android.widget.FrameLayout(this).apply {
            val shape = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.DKGRAY)
            }
            background = shape
            elevation = dpToPx(8).toFloat()
            visibility = View.GONE

            val trashIcon = android.widget.ImageView(this@OverlayService).apply {
                setImageResource(android.R.drawable.ic_menu_delete)
                setColorFilter(Color.WHITE)
                layoutParams = android.widget.FrameLayout.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                )
            }
            addView(trashIcon)
        }

        val savedX = prefs.getInt(KEY_POS_X, dpToPx(16))
        val savedY = prefs.getInt(KEY_POS_Y, dpToPx(100))

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = savedX
            y = savedY
        }

        trashParams = WindowManager.LayoutParams(
            trashSize,
            trashSize,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dpToPx(48)
        }

        if (Settings.canDrawOverlays(this)) {
            windowManager.addView(frameLayout, params)
            windowManager.addView(trashView, trashParams)
            isViewAdded = true
        } else {
            stopSelf()
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun handleTouch(event: MotionEvent): Boolean {
        val p = params ?: return false
        val v = overlayView ?: return false
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                initialX = p.x
                initialY = p.y
                v.animate().scaleX(0.85f).scaleY(0.85f).setDuration(100).start()
                
                trashView?.visibility = View.VISIBLE
                trashView?.alpha = 0f
                trashView?.animate()?.alpha(1f)?.setDuration(200)?.start()
            }
            MotionEvent.ACTION_MOVE -> {
                p.x = initialX + (event.rawX - initialTouchX).toInt()
                p.y = initialY + (event.rawY - initialTouchY).toInt()
                windowManager.updateViewLayout(overlayView, p)
                
                val screenWidth = resources.displayMetrics.widthPixels
                val screenHeight = resources.displayMetrics.heightPixels
                
                val oX = p.x + v.width / 2
                val oY = p.y + v.height / 2
                
                val tX = screenWidth / 2
                val tY = screenHeight - dpToPx(48) - (trashView?.height ?: dpToPx(64)) / 2
                
                val dist = kotlin.math.sqrt(((oX - tX) * (oX - tX) + (oY - tY) * (oY - tY)).toDouble())
                if (dist < dpToPx(80)) {
                    isOverTrash = true
                    trashView?.animate()?.scaleX(1.2f)?.scaleY(1.2f)?.setDuration(100)?.start()
                    (trashView?.background as? GradientDrawable)?.setColor(Color.RED)
                } else {
                    isOverTrash = false
                    trashView?.animate()?.scaleX(1.0f)?.scaleY(1.0f)?.setDuration(100)?.start()
                    (trashView?.background as? GradientDrawable)?.setColor(Color.DKGRAY)
                }
            }
            MotionEvent.ACTION_UP -> {
                v.animate().scaleX(1f).scaleY(1f).setDuration(100).start()
                trashView?.animate()?.alpha(0f)?.setDuration(200)?.withEndAction {
                    trashView?.visibility = View.GONE
                }?.start()

                if (isOverTrash) {
                    AutoFillAccessibilityService.instance?.stopAutomation()
                    stopSelf()
                    return true
                }

                val dx = event.rawX - initialTouchX
                val dy = event.rawY - initialTouchY
                val distance = kotlin.math.sqrt(dx * dx + dy * dy)
                if (distance < 20f) {
                    v.performClick()
                    val currentTime = System.currentTimeMillis()
                    val isDoubleTap = currentTime - lastTapTime < DOUBLE_TAP_MS
                    lastTapTime = currentTime

                    if (AutoFillAccessibilityService.instance?.automationRunning?.get() == true) {
                        if (isDoubleTap) {
                            AutoFillAccessibilityService.instance?.stopAutomation()
                            updateVisualState(AutomationState.IDLE)
                        } else {
                            android.widget.Toast.makeText(this@OverlayService, "Double-tap to stop", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        if (AutoFillAccessibilityService.instance == null) {
                            android.widget.Toast.makeText(this@OverlayService, "Accessibility Service is not enabled!", android.widget.Toast.LENGTH_SHORT).show()
                            updateVisualState(AutomationState.ERROR)
                        } else {
                            v.alpha = 0.5f
                            v.postDelayed({ v.alpha = 1f }, 250)
                            AutoFillAccessibilityService.instance?.startAutomation()
                        }
                    }
                } else {
                    prefs.edit {
                        putInt(KEY_POS_X, p.x)
                            .putInt(KEY_POS_Y, p.y)
                    }
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                v.animate().scaleX(1f).scaleY(1f).setDuration(100).start()
                trashView?.animate()?.alpha(0f)?.setDuration(200)?.withEndAction {
                    trashView?.visibility = View.GONE
                }?.start()
            }
        }
        return true
    }

    fun updateVisualState(state: AutomationState) {
        val color = when (state) {
            AutomationState.IDLE -> IDLE_COLOR
            AutomationState.RUNNING -> RUNNING_COLOR
            AutomationState.ERROR -> ERROR_COLOR
            AutomationState.DONE -> DONE_COLOR
        }
        val innerColor = when (state) {
            AutomationState.DONE -> Color.GREEN
            AutomationState.ERROR -> Color.RED
            else -> Color.WHITE
        }
        overlayView?.apply {
            (background as? GradientDrawable)?.setColor(color)
        }
        overlayIcon?.setInnerColor(innerColor)
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, createNotification(state))
    }

    override fun onDestroy() {
        if (isViewAdded && ::windowManager.isInitialized) {
            overlayView?.let { windowManager.removeView(it) }
            trashView?.let { windowManager.removeView(it) }
        }
        _isRunningFlow.value = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotification(state: AutomationState): Notification {
        val channelId = "overlay_service_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "AutoReview Overlay",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Floating overlay for auto-filling review forms"
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }

        val contentText = when (state) {
            AutomationState.IDLE -> "Floating overlay active \u2014 tap to auto-fill"
            AutomationState.RUNNING -> "Auto-fill in progress \u2014 double-tap to stop"
            AutomationState.ERROR -> "Auto-fill failed \u2014 tap to retry"
            AutomationState.DONE -> "Auto-fill complete \u2014 tap to run again"
        }

        val stopIntent = Intent(this, OverlayService::class.java).apply {
            putExtra(EXTRA_ACTION, ACTION_STOP)
        }
        val stopPending = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("AutoReview")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopPending)
            .build()
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val KEY_POS_X = "overlay_pos_x"
        private const val KEY_POS_Y = "overlay_pos_y"
        private const val DOUBLE_TAP_MS = 400L
        private const val EXTRA_ACTION = "action"
        private const val EXTRA_STATE = "state"
        private const val ACTION_UPDATE_STATE = "update_state"
        private const val ACTION_STOP = "stop"

        private const val IDLE_COLOR = 0xFF6200EE.toInt()
        private const val RUNNING_COLOR = 0xFF03DAC5.toInt()
        private const val ERROR_COLOR = 0xFFB00020.toInt()
        private const val DONE_COLOR = 0xFF4CAF50.toInt()


        private val _isRunningFlow = MutableStateFlow(false)
        val isRunningFlow: StateFlow<Boolean> = _isRunningFlow.asStateFlow()

        val isRunning: Boolean get() = _isRunningFlow.value

        fun updateState(context: Context, state: AutomationState) {
            val intent = Intent(context, OverlayService::class.java).apply {
                putExtra(EXTRA_ACTION, ACTION_UPDATE_STATE)
                putExtra(EXTRA_STATE, state.name)
            }
            context.startService(intent)
        }
    }
}

class CustomIconView(context: Context) : View(context) {
    private val paintOuter = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = 6f
    }
    private val paintInner = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = android.graphics.Paint.Style.FILL
    }

    fun setInnerColor(color: Int) {
        paintInner.color = color
        invalidate()
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radiusOuter = (kotlin.math.min(width, height) / 2f) - paintOuter.strokeWidth
        val radiusInner = radiusOuter * 0.5f
        canvas.drawCircle(cx, cy, radiusOuter, paintOuter)
        canvas.drawCircle(cx, cy, radiusInner, paintInner)
    }
}
