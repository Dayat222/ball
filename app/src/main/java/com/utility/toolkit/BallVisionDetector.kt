package com.utility.toolkit

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Pure Kotlin computer-vision detector for 8 Ball Pool table balls.
 *
 * Operates on an ARGB_8888 pixel buffer of the calibrated playing surface
 * (cropped to the table boundary rectangle) and identifies:
 *  - Object / Target balls (high contrast against the cloth felt with circular
 *    boundary gradient and specular highlight).
 *  - The white Cue Ball (highest luminance + low saturation + circular radial
 *    symmetry).
 *
 * Designed to run allocation-light on a background thread at 6-10 Hz without
 * requiring massive native OpenCV binaries.
 */
object BallVisionDetector {

    data class DetectedBall(
        val x: Float,
        val y: Float,
        val score: Float,
        val isCueCandidate: Boolean
    )

    data class VisionResult(
        val targetBall: DetectedBall?,
        val cueBall: DetectedBall?,
        val allBalls: List<DetectedBall>
    )

    // Precomputed 12-point unit circle directions for radial gradient ring scoring
    private const val RING_SAMPLES = 12
    private val ringCos = FloatArray(RING_SAMPLES) { i ->
        cos(2.0 * Math.PI * i / RING_SAMPLES).toFloat()
    }
    private val ringSin = FloatArray(RING_SAMPLES) { i ->
        sin(2.0 * Math.PI * i / RING_SAMPLES).toFloat()
    }

    /**
     * Scans a cropped table bitmap buffer ([pixels] of size [width] x [height])
     * and returns detected balls mapped back to full-screen coordinates using
     * [tableLeft] and [tableTop].
     *
     * @param ballRadiusPx Expected ball radius in pixels from [AimGeometry.ballRadius].
     * @param currentTargetX Current target ball X in screen space (used for nearest-neighbor lock).
     * @param currentTargetY Current target ball Y in screen space.
     * @param currentCueX Current cue ball X in screen space (optional).
     * @param currentCueY Current cue ball Y in screen space (optional).
     */
    fun detectBalls(
        pixels: IntArray,
        width: Int,
        height: Int,
        tableLeft: Float,
        tableTop: Float,
        ballRadiusPx: Float,
        currentTargetX: Float,
        currentTargetY: Float,
        currentCueX: Float? = null,
        currentCueY: Float? = null
    ): VisionResult {
        val r = ballRadiusPx.coerceIn(6f, 64f)
        val rInt = r.roundToInt().coerceAtLeast(6)
        if (width < rInt * 4 || height < rInt * 4 || pixels.size < width * height) {
            return VisionResult(null, null, emptyList())
        }

        // 1. Estimate dominant cloth background colour (R, G, B) by sampling a sparse
        //    grid across the central playing surface. Since ~85% of the table is open
        //    felt, the median/trimmed mean of these samples locks onto the cloth colour
        //    regardless of which table theme is active (Green, Blue, Red, Purple, Gold).
        val clothRgb = estimateClothColor(pixels, width, height)
        val clothR = clothRgb[0]
        val clothG = clothRgb[1]
        val clothB = clothRgb[2]

        // 2. Coarse scan grid (step = ~0.35 * radius) inside the cushion noses
        val step = max(3, (r * 0.35f).roundToInt())
        val margin = rInt + 2
        val rawCandidates = ArrayList<DetectedBall>(24)

        val innerR = r * 0.45f
        val outerR = r * 1.25f

        var y = margin
        while (y < height - margin) {
            var x = margin
            while (x < width - margin) {
                val idx = y * width + x
                val c = pixels[idx]
                val pr = (c shr 16) and 0xFF
                val pg = (c shr 8) and 0xFF
                val pb = c and 0xFF

                // Fast rejection: centre pixel must differ from the cloth felt
                // (or be a dark 8-ball / bright white cue ball)
                val diffCloth = colorDist(pr, pg, pb, clothR, clothG, clothB)
                if (diffCloth >= 36f) {
                    val eval = evaluateCandidate(
                        pixels, width, height,
                        x.toFloat(), y.toFloat(),
                        innerR, r, outerR,
                        clothR, clothG, clothB
                    )
                    if (eval != null) {
                        rawCandidates.add(
                            DetectedBall(
                                x = tableLeft + eval.x,
                                y = tableTop + eval.y,
                                score = eval.score,
                                isCueCandidate = eval.isCueCandidate
                            )
                        )
                    }
                }
                x += step
            }
            y += step
        }

        if (rawCandidates.isEmpty()) {
            return VisionResult(null, null, emptyList())
        }

        // 3. Non-Maximum Suppression (NMS): merge candidates closer than 1.75 * ballRadius
        rawCandidates.sortByDescending { it.score }
        val suppressed = ArrayList<DetectedBall>(16)
        val minSep = r * 1.75f

        for (cand in rawCandidates) {
            var overlaps = false
            for (kept in suppressed) {
                if (hypot(cand.x - kept.x, cand.y - kept.y) < minSep) {
                    overlaps = true
                    break
                }
            }
            if (!overlaps) {
                // Fine-refine centroid around the winning peak
                val localX = (cand.x - tableLeft).coerceIn(margin.toFloat(), (width - margin).toFloat())
                val localY = (cand.y - tableTop).coerceIn(margin.toFloat(), (height - margin).toFloat())
                val refined = refineCentroid(
                    pixels, width, height,
                    localX, localY, r,
                    clothR, clothG, clothB,
                    cand.score, cand.isCueCandidate
                )
                suppressed.add(
                    DetectedBall(
                        x = tableLeft + refined.x,
                        y = tableTop + refined.y,
                        score = refined.score,
                        isCueCandidate = refined.isCueCandidate
                    )
                )
                if (suppressed.size >= 16) break
            }
        }

        // 4. Identify best Cue Ball (white ball) and best Target Ball (nearest to current pointB)
        var bestCue: DetectedBall? = null
        val cueSearchRadius = r * 8.5f

        for (b in suppressed) {
            if (!b.isCueCandidate) continue
            if (currentCueX != null && currentCueY != null) {
                val d = hypot(b.x - currentCueX, b.y - currentCueY)
                if (d <= cueSearchRadius && (bestCue == null || d < hypot(bestCue.x - currentCueX, bestCue.y - currentCueY))) {
                    bestCue = b
                }
            } else if (bestCue == null || b.score > bestCue.score) {
                bestCue = b
            }
        }

        // Target ball: snap to the detected ball closest to currentTargetX/Y (within 6.5Rmagnetic capture),
        // or the highest-scoring non-cue ball if none is inside the capture window.
        var bestTarget: DetectedBall? = null
        var bestTargetDist = Float.MAX_VALUE
        val captureRadius = r * 6.5f

        for (b in suppressed) {
            // Avoid snapping the target ring onto the white cue ball if another ball is closer
            if (b === bestCue && suppressed.size > 1) continue
            val d = hypot(b.x - currentTargetX, b.y - currentTargetY)
            if (d <= captureRadius && d < bestTargetDist) {
                bestTargetDist = d
                bestTarget = b
            }
        }

        return VisionResult(
            targetBall = bestTarget,
            cueBall = bestCue,
            allBalls = suppressed
        )
    }

    internal fun estimateClothColor(pixels: IntArray, width: Int, height: Int): IntArray {
        val samplesR = IntArray(64)
        val samplesG = IntArray(64)
        val samplesB = IntArray(64)
        var count = 0

        val xStart = width / 6
        val xEnd = width - xStart
        val yStart = height / 6
        val yEnd = height - yStart
        val stepX = max(1, (xEnd - xStart) / 8)
        val stepY = max(1, (yEnd - yStart) / 8)

        var sy = yStart
        while (sy < yEnd && count < 64) {
            var sx = xStart
            while (sx < xEnd && count < 64) {
                val c = pixels[sy * width + sx]
                samplesR[count] = (c shr 16) and 0xFF
                samplesG[count] = (c shr 8) and 0xFF
                samplesB[count] = c and 0xFF
                count++
                sx += stepX
            }
            sy += stepY
        }

        if (count == 0) return intArrayOf(30, 120, 60)
        samplesR.sort(0, count)
        samplesG.sort(0, count)
        samplesB.sort(0, count)
        val mid = count / 2
        return intArrayOf(samplesR[mid], samplesG[mid], samplesB[mid])
    }

    private fun evaluateCandidate(
        pixels: IntArray,
        width: Int,
        height: Int,
        cx: Float,
        cy: Float,
        innerR: Float,
        ballR: Float,
        outerR: Float,
        clothR: Int,
        clothG: Int,
        clothB: Int
    ): DetectedBall? {
        var innerDiffSum = 0f
        var whiteCount = 0
        var lumSum = 0f
        var satSum = 0f

        // Sample 12 points on the inner core (0.45 * R)
        for (i in 0 until RING_SAMPLES) {
            val px = (cx + ringCos[i] * innerR).roundToInt().coerceIn(0, width - 1)
            val py = (cy + ringSin[i] * innerR).roundToInt().coerceIn(0, height - 1)
            val c = pixels[py * width + px]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF

            val dCloth = colorDist(r, g, b, clothR, clothG, clothB)
            innerDiffSum += dCloth

            val maxCh = max(r, max(g, b))
            val minCh = min(r, min(g, b))
            val lum = (r * 0.299f + g * 0.587f + b * 0.114f)
            val sat = if (maxCh > 0) (maxCh - minCh).toFloat() / maxCh else 0f
            lumSum += lum
            satSum += sat
            if (lum > 185f && sat < 0.24f) {
                whiteCount++
            }
        }

        val avgInnerDiff = innerDiffSum / RING_SAMPLES
        if (avgInnerDiff < 42f) return null

        // Sample 12 points outside the ball edge (1.25 * R) and on the boundary (0.95 * R)
        // A real isolated ball has most of its outer ring sitting on cloth felt or dark shadow,
        // creating a strong radial contrast jump between inner core and outer ring.
        var boundaryContrastSum = 0f
        var clothSurroundCount = 0
        val edgeR = ballR * 0.85f

        for (i in 0 until RING_SAMPLES) {
            val inX = (cx + ringCos[i] * edgeR).roundToInt().coerceIn(0, width - 1)
            val inY = (cy + ringSin[i] * edgeR).roundToInt().coerceIn(0, height - 1)
            val outX = (cx + ringCos[i] * outerR).roundToInt().coerceIn(0, width - 1)
            val outY = (cy + ringSin[i] * outerR).roundToInt().coerceIn(0, height - 1)

            val cIn = pixels[inY * width + inX]
            val cOut = pixels[outY * width + outX]

            val inR = (cIn shr 16) and 0xFF
            val inG = (cIn shr 8) and 0xFF
            val inB = cIn and 0xFF

            val outR = (cOut shr 16) and 0xFF
            val outG = (cOut shr 8) and 0xFF
            val outB = cOut and 0xFF

            boundaryContrastSum += colorDist(inR, inG, inB, outR, outG, outB)
            if (colorDist(outR, outG, outB, clothR, clothG, clothB) < 48f) {
                clothSurroundCount++
            }
        }

        // At least 5 of the 12 outer samples must be table cloth (prevents locking onto
        // long cushion rails or UI banners)
        if (clothSurroundCount < 5) return null

        val avgBoundaryContrast = boundaryContrastSum / RING_SAMPLES
        if (avgBoundaryContrast < 28f) return null

        val avgLum = lumSum / RING_SAMPLES
        val avgSat = satSum / RING_SAMPLES
        val isCue = (whiteCount >= 8 && avgLum > 180f && avgSat < 0.25f)

        val score = avgInnerDiff * 0.55f + avgBoundaryContrast * 0.45f + (clothSurroundCount * 3.5f)
        return DetectedBall(cx, cy, score, isCue)
    }

    private fun refineCentroid(
        pixels: IntArray,
        width: Int,
        height: Int,
        startX: Float,
        startY: Float,
        ballR: Float,
        clothR: Int,
        clothG: Int,
        clothB: Int,
        baseScore: Float,
        isCue: Boolean
    ): DetectedBall {
        val rInt = ballR.roundToInt().coerceAtLeast(4)
        var sumW = 0f
        var sumX = 0f
        var sumY = 0f

        val cxInt = startX.roundToInt()
        val cyInt = startY.roundToInt()
        val y0 = max(1, cyInt - rInt)
        val y1 = min(height - 2, cyInt + rInt)
        val x0 = max(1, cxInt - rInt)
        val x1 = min(width - 2, cxInt + rInt)
        val r2 = ballR * ballR

        for (py in y0..y1) {
            val dy = (py - startY)
            for (px in x0..x1) {
                val dx = (px - startX)
                if (dx * dx + dy * dy > r2) continue
                val c = pixels[py * width + px]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                val w = colorDist(r, g, b, clothR, clothG, clothB)
                if (w > 32f) {
                    sumW += w
                    sumX += px * w
                    sumY += py * w
                }
            }
        }

        return if (sumW > 1e-3f) {
            DetectedBall(sumX / sumW, sumY / sumW, baseScore, isCue)
        } else {
            DetectedBall(startX, startY, baseScore, isCue)
        }
    }

    private fun colorDist(r1: Int, g1: Int, b1: Int, r2: Int, g2: Int, b2: Int): Float {
        val dr = (r1 - r2).toFloat()
        val dg = (g1 - g2).toFloat()
        val db = (b1 - b2).toFloat()
        return abs(dr) * 0.30f + abs(dg) * 0.50f + abs(db) * 0.20f
    }
}
