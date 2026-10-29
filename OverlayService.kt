package com.example.poolaimassist

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import androidx.core.app.NotificationCompat

/**
 * Foreground service that owns the MediaProjection session, runs the capture ->
 * detect -> compute-guides -> draw loop, and hosts the overlay window.
 *
 * Started with the MediaProjection permission result Intent + resultCode from MainActivity.
 */
class OverlayService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        private const val CHANNEL_ID = "pool_aim_assist_channel"
        private const val NOTIFICATION_ID = 1
    }

    private var mediaProjection: MediaProjection? = null
    private var captureEngine: CaptureEngine? = null
    private var overlayView: OverlayView? = null
    private var controlPanel: ControlPanelView? = null
    private var calibrationOverlay: CalibrationOverlay? = null
    private var windowManager: WindowManager? = null
    private val detector = BallDetector()
    @Volatile private var calibrating = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, -1) ?: -1
        val resultData: Intent? = intent?.getParcelableExtra(EXTRA_RESULT_DATA)

        if (resultCode == -1 || resultData == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)

        // Required since Android 14: a callback must be registered before createVirtualDisplay()
        // is called, or the system throws a SecurityException.
        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopSelf()
            }
        }, Handler(Looper.getMainLooper()))

        addOverlayWindow()
        addControlPanel()

        if (!CalibrationStore.hasCalibration(this)) {
            // First run: walk the user through tapping the 6 pockets before showing guides.
            startCalibration()
        }

        mediaProjection?.let { projection ->
            captureEngine = CaptureEngine(this, projection) { bitmap ->
                processFrame(bitmap)
            }
            captureEngine?.start()
        }

        return START_STICKY
    }

    private fun processFrame(bitmap: android.graphics.Bitmap) {
        try {
            if (calibrating || isFinishingOrDestroyed()) return
            val balls = detector.detectBalls(bitmap)
            val cue = balls.firstOrNull { it.isCueBall }
            val objectBalls = balls.filterNot { it.isCueBall }
            val pockets = CalibrationStore.loadPockets(this)

            val guides = if (cue != null && pockets.size == 6) {
                TrajectoryCalculator.computeShotGuides(cue, objectBalls, pockets)
            } else emptyList()

            overlayView?.setPockets(pockets)
            overlayView?.update(cue, guides)
        } catch (_: Exception) {
            // A malformed/unsupported frame should not terminate the capture loop.
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    @Volatile private var serviceDestroyed = false
    private fun isFinishingOrDestroyed(): Boolean = serviceDestroyed

    private fun addOverlayWindow() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val view = OverlayView(this)
        overlayView = view

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START

        windowManager?.addView(view, params)
    }

    private fun addControlPanel() {
        val wm = windowManager ?: return
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 24
        params.y = 120

        val panel = ControlPanelView(
            this, wm, params,
            onCalibrate = { startCalibration() },
            onStop = { stopSelf() }
        )
        controlPanel = panel
        wm.addView(panel, params)
    }

    private fun startCalibration() {
        if (calibrating) return
        calibrating = true
        val wm = windowManager ?: return

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START

        val calib = CalibrationOverlay(this) { pockets ->
            CalibrationStore.savePockets(this, pockets)
            stopCalibration()
        }
        calibrationOverlay = calib
        wm.addView(calib, params)
    }

    private fun stopCalibration() {
        calibrating = false
        calibrationOverlay?.let { windowManager?.removeView(it) }
        calibrationOverlay = null
        overlayView?.setPockets(CalibrationStore.loadPockets(this))
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Pool Aim Assist", NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Pool Aim Assist is running")
            .setContentText("Showing shot guides over the table")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        serviceDestroyed = true
        super.onDestroy()
        captureEngine?.stop()
        overlayView?.let { runCatching { windowManager?.removeView(it) } }
        controlPanel?.let { runCatching { windowManager?.removeView(it) } }
        calibrationOverlay?.let { runCatching { windowManager?.removeView(it) } }
        mediaProjection?.stop()
    }
}
