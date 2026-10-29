package com.example.poolaimassist

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

/** Transparent overlay. It never consumes touches intended for the game. */
class OverlayView(context: Context) : View(context) {
    @Volatile var cueBall: Ball? = null
    @Volatile var guides: List<ShotGuide> = emptyList()
    @Volatile var pocketPoints: List<Pt> = emptyList()

    private val bestLinePaint = Paint().apply {
        color = Color.YELLOW
        strokeWidth = 5f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }
    private val altLinePaint = Paint().apply {
        color = Color.argb(125, 255, 255, 255)
        strokeWidth = 2.5f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }
    private val blockedPaint = Paint().apply {
        color = Color.argb(190, 255, 70, 70)
        strokeWidth = 3f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }
    private val ghostBallPaint = Paint().apply {
        color = Color.argb(190, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 3f
        isAntiAlias = true
    }
    private val pocketPaint = Paint().apply {
        color = Color.argb(150, 0, 200, 255)
        style = Paint.Style.STROKE
        strokeWidth = 3f
        isAntiAlias = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        pocketPoints.forEach { canvas.drawCircle(it.x, it.y, 18f, pocketPaint) }
        val cue = cueBall ?: return
        if (guides.isEmpty()) return

        // Show a limited set of alternatives, with obstructed paths in red.
        guides.drop(1).take(8).forEach { g ->
            val paint = if (g.blocked) blockedPaint else altLinePaint
            canvas.drawLine(cue.center.x, cue.center.y,
                g.ghostBallCenter.x, g.ghostBallCenter.y, paint)
            drawObjectPath(canvas, g, paint)
        }

        val best = guides.first()
        val bestPaint = if (best.blocked) blockedPaint else bestLinePaint
        canvas.drawLine(cue.center.x, cue.center.y,
            best.ghostBallCenter.x, best.ghostBallCenter.y, bestPaint)
        drawObjectPath(canvas, best, bestPaint)
        canvas.drawCircle(best.ghostBallCenter.x, best.ghostBallCenter.y,
            best.objectBall.radiusPx, ghostBallPaint)
    }

    private fun drawObjectPath(canvas: Canvas, guide: ShotGuide, paint: Paint) {
        val start = guide.objectBall.center
        val rail = guide.railPoint
        if (rail == null) {
            canvas.drawLine(start.x, start.y, guide.pocket.x, guide.pocket.y, paint)
        } else {
            canvas.drawLine(start.x, start.y, rail.x, rail.y, paint)
            canvas.drawLine(rail.x, rail.y, guide.pocket.x, guide.pocket.y, paint)
            canvas.drawCircle(rail.x, rail.y, 7f, paint)
        }
    }

    fun update(cue: Ball?, newGuides: List<ShotGuide>) {
        cueBall = cue
        guides = newGuides
        postInvalidate()
    }

    fun setPockets(newPockets: List<Pt>) {
        pocketPoints = newPockets
        postInvalidate()
    }
}
