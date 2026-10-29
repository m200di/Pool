package com.example.poolaimassist

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import kotlin.math.abs

/**
 * Small, draggable floating control bar (Recalibrate / Stop) that sits on top of the game
 * alongside the guide lines. This is the only always-touchable piece of UI; the guide
 * overlay itself stays FLAG_NOT_TOUCHABLE so it never blocks game input.
 */
class ControlPanelView(
    context: Context,
    private val windowManager: WindowManager,
    private val layoutParams: WindowManager.LayoutParams,
    private val onCalibrate: () -> Unit,
    private val onStop: () -> Unit
) : LinearLayout(context) {

    private var downX = 0f
    private var downY = 0f
    private var startWx = 0
    private var startWy = 0
    private var isDrag = false

    init {
        orientation = HORIZONTAL
        setBackgroundColor(Color.argb(200, 20, 20, 20))
        setPadding(16, 12, 16, 12)

        val calibrateBtn = Button(context).apply {
            text = "Calibrate"
            setOnClickListener { onCalibrate() }
        }
        val stopBtn = Button(context).apply {
            text = "Stop"
            setOnClickListener { onStop() }
        }
        addView(calibrateBtn)
        addView(stopBtn)

        setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startWx = layoutParams.x
                    startWy = layoutParams.y
                    isDrag = false
                    false // let the click also propagate to the buttons
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (abs(dx) > 12 || abs(dy) > 12) {
                        isDrag = true
                        layoutParams.x = startWx + dx.toInt()
                        layoutParams.y = startWy + dy.toInt()
                        windowManager.updateViewLayout(this, layoutParams)
                    }
                    isDrag
                }
                else -> false
            }
        }
    }
}
