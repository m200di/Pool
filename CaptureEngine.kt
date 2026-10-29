package com.example.poolaimassist

import android.content.Context
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.view.WindowManager
import java.util.concurrent.atomic.AtomicBoolean

/** Event-driven screen capture. Frames are consumed as soon as ImageReader publishes them;
 * there is no 200 ms polling delay. Processing is throttled to avoid overheating. */
class CaptureEngine(
    private val context: Context,
    private val mediaProjection: MediaProjection,
    private val minFrameIntervalMs: Long = 90L,
    private val onFrame: (Bitmap) -> Unit
) {
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val thread = HandlerThread("CaptureEngineThread").apply { start() }
    private val handler = Handler(thread.looper)
    private val running = AtomicBoolean(false)
    private var lastFrameAt = 0L
    private val width: Int
    private val height: Int
    private val density: Int

    init {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        width = metrics.widthPixels.coerceAtLeast(1)
        height = metrics.heightPixels.coerceAtLeast(1)
        density = metrics.densityDpi
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val reader = ImageReader.newInstance(width, height, android.graphics.PixelFormat.RGBA_8888, 3)
        imageReader = reader
        reader.setOnImageAvailableListener({ source -> consumeLatest(source) }, handler)
        try {
            virtualDisplay = mediaProjection.createVirtualDisplay(
                "PoolAimAssistCapture", width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface, null, handler
            )
        } catch (t: Throwable) {
            running.set(false)
            reader.close()
            imageReader = null
            throw t
        }
    }

    private fun consumeLatest(reader: ImageReader) {
        if (!running.get()) return
        val now = android.os.SystemClock.elapsedRealtime()
        val image = try { reader.acquireLatestImage() } catch (_: Exception) { null } ?: return
        try {
            // Throttle processing, not capture: acquireLatestImage discards stale frames.
            if (now - lastFrameAt < minFrameIntervalMs) return
            lastFrameAt = now
            val bitmap = imageToBitmap(image) ?: return
            try { onFrame(bitmap) } catch (_: Exception) { /* one bad frame must not stop capture */ }
        } finally {
            image.close()
        }
    }

    private fun imageToBitmap(image: Image): Bitmap? {
        val plane = image.planes.firstOrNull() ?: return null
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        if (pixelStride <= 0 || rowStride <= 0) return null
        val paddedWidth = rowStride / pixelStride
        val padded = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
        return try {
            plane.buffer.rewind()
            padded.copyPixelsFromBuffer(plane.buffer)
            if (paddedWidth == image.width) padded else {
                Bitmap.createBitmap(padded, 0, 0, image.width, image.height).also { padded.recycle() }
            }
        } catch (_: Exception) {
            if (!padded.isRecycled) padded.recycle()
            null
        }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        imageReader?.setOnImageAvailableListener(null, null)
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        handler.removeCallbacksAndMessages(null)
        thread.quitSafely()
    }
}
