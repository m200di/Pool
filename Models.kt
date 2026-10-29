package com.example.poolaimassist

/** 2D point in captured-screen pixel coordinates. */
data class Pt(val x: Float, val y: Float) {
    operator fun minus(o: Pt) = Pt(x - o.x, y - o.y)
    operator fun plus(o: Pt) = Pt(x + o.x, y + o.y)
    operator fun times(s: Float) = Pt(x * s, y * s)
    fun dot(o: Pt): Float = x * o.x + y * o.y
    fun length(): Float = kotlin.math.sqrt(x * x + y * y)
    fun normalized(): Pt {
        val l = length()
        return if (l < 0.0001f) Pt(0f, 0f) else Pt(x / l, y / l)
    }
}

data class Ball(
    val center: Pt,
    val radiusPx: Float,
    val isCueBall: Boolean
)

/**
 * One suggested shot. For a bank shot, railPoint is the first rail contact.
 * blocked means another detected ball intersects the cue-to-ghost or object-ball path.
 */
data class ShotGuide(
    val objectBall: Ball,
    val pocket: Pt,
    val ghostBallCenter: Pt,
    val cutAngleDeg: Float,
    val railPoint: Pt? = null,
    val blocked: Boolean = false
)
