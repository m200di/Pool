package com.example.poolaimassist

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View

/**
 * A touchable, full-screen, semi-transparent overlay used ONLY during calibration.
 * Drawn on top of the live game (which stays fully visible underneath) so the user can
 * tap each pocket exactly where it appears on their table skin.
 *
 * This view is added/removed from the WindowManager by OverlayService; it is NOT part of
 * the always-on guide overlay (which is non-touchable so it never blocks game input).
 */
class CalibrationOverlay(
    context: Context,
    private val onComplete: (List<Pt>) -> Unit
) : View(context) {

    private val labels = listOf(
        "1/6: Tap TOP-LEFT pocket",
        "2/6: Tap TOP-MIDDLE pocket",
        "3/6: Tap TOP-RIGHT pocket",
        "4/6: Tap BOTTOM-RIGHT pocket",
        "5/6: Tap BOTTOM-MIDDLE pocket",
        "6/6: Tap BOTTOM-LEFT pocket"
    )
    private val tapped = mutableListOf<Pt>()

    private val dimPaint = Paint().apply { color = Color.argb(60, 0, 0, 0) }
    private val markerPaint = Paint().apply {
        color = Color.CYAN
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 42f
        isAntiAlias = true
    }
    private val textBgPaint = Paint().apply { color = Color.argb(180, 0, 0, 0) }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)

        for (p in tapped) {
            canvas.drawCircle(p.x, p.y, 16f, markerPaint)
        }

        val label = if (tapped.size < labels.size) labels[tapped.size] else "Saved!"
        canvas.drawRect(24f, 60f, 24f + textPaint.measureText(label) + 32f, 60f + 70f, textBgPaint)
        canvas.drawText(label, 40f, 110f, textPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            if (tapped.size < labels.size) {
                tapped.add(Pt(event.rawX, event.rawY))
                invalidate()
                if (tapped.size == labels.size) {
                    postDelayed({ onComplete(tapped.toList()) }, 300)
                }
            }
        }
        return true
    }
}
