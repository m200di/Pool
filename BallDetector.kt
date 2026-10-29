package com.example.poolaimassist

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Lightweight, dependency-free ball detector.
 *
 * Strategy (works reasonably well for the flat, evenly-lit look of a pool table screenshot,
 * without needing OpenCV / TensorFlow / any native CV library):
 *
 *  1. Downsample the captured frame to a coarse grid (keeps this fast enough to run several
 *     times per second in pure Kotlin on the CPU).
 *  2. Estimate the "table color" as the most common color among the sampled grid cells
 *     (the felt dominates the frame).
 *  3. Mark every grid cell whose color differs enough from the table color as "foreground".
 *  4. Group foreground cells into blobs with a flood fill (4-connected).
 *  5. Keep blobs whose bounding box is roughly square and within a plausible ball-size range
 *     -> those are candidate balls. Their average color classifies cue ball (near-white) vs
 *     object balls.
 *
 * This is intentionally simple and tunable (see the constants below) rather than a full
 * production CV pipeline -- swap this class out for an OpenCV/ML-based implementation later
 * if you want more robust detection across different table skins.
 */
class BallDetector(
    private val gridStep: Int = 6,              // sample every Nth pixel (perf knob)
    private val colorDiffThreshold: Int = 42,    // how different from table color = "foreground"
    private val minBlobDiameterPx: Int = 10,     // ignore tiny noise blobs
    private val maxBlobDiameterFraction: Float = 0.12f // ignore blobs bigger than X% of frame width (not a ball)
) {

    fun detectBalls(bitmap: Bitmap): List<Ball> {
        val w = bitmap.width
        val h = bitmap.height
        val cols = w / gridStep
        val rows = h / gridStep
        if (cols < 4 || rows < 4) return emptyList()

        // Sample a coarse grid of colors once.
        val grid = IntArray(cols * rows)
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                grid[r * cols + c] = bitmap.getPixel(c * gridStep, r * gridStep)
            }
        }

        val tableColor = estimateDominantColor(grid)

        val visited = BooleanArray(cols * rows)
        data class Candidate(val ball: Ball, val red: Int, val green: Int, val blue: Int)
        val candidates = mutableListOf<Candidate>()
        val maxBlobDiameterPx = w * maxBlobDiameterFraction

        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val idx = r * cols + c
                if (visited[idx]) continue
                val color = grid[idx]
                if (!isForeground(color, tableColor)) {
                    visited[idx] = true
                    continue
                }
                // Flood fill this blob.
                val stack = mutableListOf<Int>()
                stack.add(idx)
                visited[idx] = true
                var minC = c; var maxC = c; var minR = r; var maxR = r
                var sumR = 0L; var sumG = 0L; var sumB = 0L; var count = 0L
                var sumC = 0L; var sumRow = 0L

                while (stack.isNotEmpty()) {
                    val cur = stack.removeAt(stack.lastIndex)
                    val cr = cur / cols
                    val cc = cur % cols
                    val curColor = grid[cur]
                    sumR += (curColor shr 16) and 0xFF
                    sumG += (curColor shr 8) and 0xFF
                    sumB += curColor and 0xFF
                    count++
                    sumC += cc.toLong(); sumRow += cr.toLong()
                    minC = min(minC, cc); maxC = max(maxC, cc)
                    minR = min(minR, cr); maxR = max(maxR, cr)

                    // Inline 4-neighbour checks avoid allocating an IntArray for every
                    // foreground cell, which was a significant source of GC pressure.
                    if (cc > 0) visit(cur - 1, grid, visited, stack, tableColor)
                    if (cc + 1 < cols) visit(cur + 1, grid, visited, stack, tableColor)
                    if (cr > 0) visit(cur - cols, grid, visited, stack, tableColor)
                    if (cr + 1 < rows) visit(cur + cols, grid, visited, stack, tableColor)
                }

                val blobWpx = (maxC - minC + 1) * gridStep
                val blobHpx = (maxR - minR + 1) * gridStep
                val diameter = max(blobWpx, blobHpx).toFloat()
                val aspect = blobWpx.toFloat() / blobHpx.toFloat()

                val boxCells = (maxC - minC + 1).toLong() * (maxR - minR + 1).toLong()
                val fillRatio = if (boxCells > 0) count.toFloat() / boxCells.toFloat() else 0f
                val looksRound = aspect in 0.72f..1.38f && fillRatio >= 0.28f
                val plausibleSize = diameter in minBlobDiameterPx.toFloat()..maxBlobDiameterPx

                if (looksRound && plausibleSize && count > 0) {
                    val avgR = (sumR / count).toInt()
                    val avgG = (sumG / count).toInt()
                    val avgB = (sumB / count).toInt()
                    // Use the foreground-cell centroid rather than the bounding-box midpoint.
                    val centerX = (sumC.toFloat() / count.toFloat()) * gridStep + gridStep / 2f
                    val centerY = (sumRow.toFloat() / count.toFloat()) * gridStep + gridStep / 2f
                    // Keep color evidence until all candidates are known. Marking every
                    // white-looking blob as the cue ball made white stripes/highlights
                    // disappear from the object-ball list and could remove the real cue.
                    candidates.add(Candidate(Ball(Pt(centerX, centerY), diameter / 2f, false), avgR, avgG, avgB))
                }
            }
        }

        if (candidates.isEmpty()) return emptyList()
        val cueIndex = candidates.indices
            .filter { i -> isNearWhite(candidates[i].red, candidates[i].green, candidates[i].blue) }
            .maxByOrNull { i ->
                val c = candidates[i]
                min(c.red, min(c.green, c.blue)) * 2 -
                    (max(c.red, max(c.green, c.blue)) - min(c.red, min(c.green, c.blue)))
            }
        return candidates.mapIndexed { index, candidate ->
            candidate.ball.copy(isCueBall = index == cueIndex)
        }
    }

    private fun visit(
        index: Int,
        grid: IntArray,
        visited: BooleanArray,
        stack: MutableList<Int>,
        tableColor: Int
    ) {
        if (visited[index]) return
        visited[index] = true
        if (isForeground(grid[index], tableColor)) stack.add(index)
    }

    private fun isForeground(color: Int, tableColor: Int): Boolean {
        val dr = ((color shr 16) and 0xFF) - ((tableColor shr 16) and 0xFF)
        val dg = ((color shr 8) and 0xFF) - ((tableColor shr 8) and 0xFF)
        val db = (color and 0xFF) - (tableColor and 0xFF)
        val dist = sqrt((dr * dr + dg * dg + db * db).toDouble())
        return dist > colorDiffThreshold
    }

    private fun isNearWhite(r: Int, g: Int, b: Int): Boolean {
        return r > 190 && g > 190 && b > 190
    }

    /** Most common color in the sampled grid, bucketed to reduce noise from anti-aliasing/lighting. */
    private fun estimateDominantColor(grid: IntArray): Int {
        val buckets = HashMap<Int, Int>()
        val bucketSize = 16
        for (color in grid) {
            val r = ((color shr 16) and 0xFF) / bucketSize * bucketSize
            val g = ((color shr 8) and 0xFF) / bucketSize * bucketSize
            val b = (color and 0xFF) / bucketSize * bucketSize
            val key = (r shl 16) or (g shl 8) or b
            buckets[key] = (buckets[key] ?: 0) + 1
        }
        return buckets.maxByOrNull { it.value }?.key ?: 0x228B22 // fallback: felt green
    }
}
