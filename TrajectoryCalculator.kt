package com.example.poolaimassist

import kotlin.math.acos
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Screen-space pool geometry:
 * - direct ghost-ball shots
 * - one-rail bank shots using a mirrored-pocket construction
 * - simple obstruction checks against detected balls
 *
 * This is geometric guidance, not a full physics simulation. Spin, speed, cushions,
 * pocket jaws, perspective and detector errors are not modeled.
 */
object TrajectoryCalculator {
    private const val MAX_CUT_ANGLE_DEG = 82f
    private const val MIN_CLEARANCE_PX = 1.5f

    private data class Bounds(val left: Float, val top: Float, val right: Float, val bottom: Float)

    fun computeShotGuides(
        cueBall: Ball,
        objectBalls: List<Ball>,
        pockets: List<Pt>
    ): List<ShotGuide> {
        if (pockets.isEmpty() || objectBalls.isEmpty()) return emptyList()
        val allBalls = listOf(cueBall) + objectBalls
        val bounds = tableBounds(pockets, allBalls) ?: return emptyList()
        val guides = mutableListOf<ShotGuide>()
        val diameter = cueBall.radiusPx + objectBalls.map { it.radiusPx }.average().toFloat()

        for (obj in objectBalls) {
            for (pocket in pockets) {
                // Direct shot.
                val direct = makeGuide(cueBall, obj, pocket, null, pocket, allBalls, diameter)
                if (direct != null) guides.add(direct)

                // One-cushion banks: mirror the pocket across each rail and solve the
                // straight virtual path. Keep only intersections that land on that rail.
                val mirrors = listOf(
                    Triple(Pt(pocket.x, 2f * bounds.top - pocket.y), 0, "top"),
                    Triple(Pt(pocket.x, 2f * bounds.bottom - pocket.y), 1, "bottom"),
                    Triple(Pt(2f * bounds.left - pocket.x, pocket.y), 2, "left"),
                    Triple(Pt(2f * bounds.right - pocket.x, pocket.y), 3, "right")
                )
                for ((virtualPocket, _, rail) in mirrors) {
                    val railPoint = railIntersection(obj.center, virtualPocket, bounds, rail) ?: continue
                    // Avoid degenerate banks where the rail point is essentially the ball/pocket.
                    if ((railPoint - obj.center).length() < diameter ||
                        (railPoint - pocket).length() < diameter * 0.5f) continue
                    val bank = makeGuide(cueBall, obj, pocket, railPoint, virtualPocket, allBalls, diameter)
                    if (bank != null) guides.add(bank)
                }
            }
        }

        // Prefer unobstructed direct shots, then smaller cut angles and shorter cue paths.
        return guides.distinctBy {
            listOf(it.objectBall.center.x.toInt(), it.objectBall.center.y.toInt(),
                it.pocket.x.toInt(), it.pocket.y.toInt(), it.railPoint?.x?.toInt() ?: -1,
                it.railPoint?.y?.toInt() ?: -1)
        }.sortedWith(compareBy<ShotGuide> { it.blocked }
            .thenBy { it.cutAngleDeg }
            .thenBy { (it.ghostBallCenter - cueBall.center).length() })
            .take(18)
    }

    private fun makeGuide(
        cue: Ball,
        obj: Ball,
        realPocket: Pt,
        railPoint: Pt?,
        aimPocket: Pt,
        allBalls: List<Ball>,
        diameter: Float
    ): ShotGuide? {
        val travelDir = (aimPocket - obj.center).normalized()
        if (travelDir.length() < 0.01f) return null
        val ghost = obj.center - travelDir * diameter
        val cueDir = (ghost - cue.center).normalized()
        if (cueDir.length() < 0.01f) return null
        val cutAngle = angleBetweenDeg(cueDir, travelDir)
        if (cutAngle > MAX_CUT_ANGLE_DEG) return null

        val blockers = allBalls.filter { it !== cue && it !== obj }
        val cueBlocked = blockers.any {
            segmentDistance(it.center, cue.center, ghost) < cue.radiusPx + it.radiusPx - MIN_CLEARANCE_PX
        }
        val objectBlocked = if (railPoint == null) {
            blockers.any {
                segmentDistance(it.center, obj.center, realPocket) <
                    obj.radiusPx + it.radiusPx - MIN_CLEARANCE_PX
            }
        } else {
            blockers.any {
                segmentDistance(it.center, obj.center, railPoint) <
                    obj.radiusPx + it.radiusPx - MIN_CLEARANCE_PX ||
                segmentDistance(it.center, railPoint, realPocket) <
                    obj.radiusPx + it.radiusPx - MIN_CLEARANCE_PX
            }
        }
        return ShotGuide(obj, realPocket, ghost, cutAngle, railPoint, cueBlocked || objectBlocked)
    }

    private fun tableBounds(pockets: List<Pt>, balls: List<Ball>): Bounds? {
        if (pockets.size < 4) return null
        val xs = pockets.map { it.x }
        val ys = pockets.map { it.y }
        val radius = balls.maxOfOrNull { it.radiusPx } ?: 0f
        val left = xs.minOrNull() ?: return null
        val right = xs.maxOrNull() ?: return null
        val top = ys.minOrNull() ?: return null
        val bottom = ys.maxOrNull() ?: return null
        if (right - left < radius * 8f || bottom - top < radius * 4f) return null
        return Bounds(left + radius, top + radius, right - radius, bottom - radius)
    }

    private fun railIntersection(a: Pt, b: Pt, bounds: Bounds, rail: String): Pt? {
        val d = b - a
        return when (rail) {
            "top", "bottom" -> {
                if (abs(d.y) < 0.0001f) null else {
                    val y = if (rail == "top") bounds.top else bounds.bottom
                    val t = (y - a.y) / d.y
                    if (t <= 0f || t >= 1f) null else {
                        val x = a.x + d.x * t
                        if (x in bounds.left..bounds.right) Pt(x, y) else null
                    }
                }
            }
            "left", "right" -> {
                if (abs(d.x) < 0.0001f) null else {
                    val x = if (rail == "left") bounds.left else bounds.right
                    val t = (x - a.x) / d.x
                    if (t <= 0f || t >= 1f) null else {
                        val y = a.y + d.y * t
                        if (y in bounds.top..bounds.bottom) Pt(x, y) else null
                    }
                }
            }
            else -> null
        }
    }

    private fun segmentDistance(p: Pt, a: Pt, b: Pt): Float {
        val ab = b - a
        val denom = ab.dot(ab)
        if (denom < 0.0001f) return (p - a).length()
        val t = ((p - a).dot(ab) / denom).coerceIn(0f, 1f)
        return (p - (a + ab * t)).length()
    }

    private fun angleBetweenDeg(a: Pt, b: Pt): Float {
        val dot = (a.x * b.x + a.y * b.y).toDouble()
        val denom = (a.length() * b.length()).toDouble()
        if (denom < 1e-9) return 180f
        val clamped = (dot / denom).coerceIn(-1.0, 1.0)
        return Math.toDegrees(acos(clamped)).toFloat()
    }
}
