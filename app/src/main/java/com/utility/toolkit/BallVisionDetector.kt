package com.utility.toolkit

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * High-precision pure-Kotlin computer-vision detector for 8 Ball Pool table balls.
 *
 * Operates on an ARGB_8888 pixel buffer of the calibrated playing surface
 * (cropped to the table boundary rectangle) and identifies:
 *  - Target / Object balls (solids, stripes, 8-ball) via 16-point trimmed-mean
 *    radial boundary contrast, 3D specular highlight detection, and diameter
 *    symmetry validation.
 *  - Cue ball (white / ivory ball) with multi-point saturation rejection to
 *    prevent false-positives on striped balls.
 *  - Resilient to cushion-frozen balls, adjacent touching balls, and cue stick
 *    crossings via directional outlier trimming.
 *
 * Runs allocation-light at 12-15 Hz on a background thread.
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

    // Precomputed 16-point unit circle directions for radial edge contrast (22.5° steps)
    private const val RING_SAMPLES = 16
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

        // 1. Estimate dominant cloth background colour by sampling a 12x8 grid (96 samples)
        //    across the central 15%..85% playing area. Median R/G/B locks onto felt regardless
        //    of active table theme (green, tournament blue, red, purple, dark, gold).
        val clothRgb = estimateClothColor(pixels, width, height)
        val clothR = clothRgb[0]
        val clothG = clothRgb[1]
        val clothB = clothRgb[2]
        val clothLum = clothR * 0.299f + clothG * 0.587f + clothB * 0.114f

        // 2. Coarse scan grid (step = ~0.28 * radius) inside cushion margins
        val step = max(2, (r * 0.28f).roundToInt())
        val margin = rInt + 2
        val rawCandidates = ArrayList<DetectedBall>(32)

        val innerR1 = r * 0.30f
        val innerR2 = r * 0.60f
        val edgeR = r * 0.82f
        val rimR = max(r + 1.0f, r * 1.07f)
        val outerR = max(r + 3.0f, r * 1.24f)
        val specOffsetY = (r * 0.35f).roundToInt()

        var y = margin
        while (y < height - margin) {
            val yOffset = y * width
            var x = margin
            while (x < width - margin) {
                val idx = yOffset + x
                val c = pixels[idx]
                val pr = (c shr 16) and 0xFF
                val pg = (c shr 8) and 0xFF
                val pb = c and 0xFF

                val diffCloth = colorDist(pr, pg, pb, clothR, clothG, clothB)
                val pLum = pr * 0.299f + pg * 0.587f + pb * 0.114f

                // Test upper specular offset to catch tone-on-tone balls (e.g. green ball on green cloth)
                val specY = (y - specOffsetY).coerceIn(0, height - 1)
                val specC = pixels[specY * width + x]
                val sr = (specC shr 16) and 0xFF
                val sg = (specC shr 8) and 0xFF
                val sb = specC and 0xFF
                val diffSpec = colorDist(sr, sg, sb, clothR, clothG, clothB)
                val sLum = sr * 0.299f + sg * 0.587f + sb * 0.114f

                val isPotentialBall = diffCloth >= 22f ||
                        diffSpec >= 25f ||
                        abs(pLum - clothLum) >= 22f ||
                        abs(sLum - clothLum) >= 25f

                if (isPotentialBall) {
                    val eval = evaluateCandidate(
                        pixels, width, height,
                        x.toFloat(), y.toFloat(),
                        innerR1, innerR2, edgeR, rimR, outerR,
                        clothR, clothG, clothB, clothLum
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

        // 3. Non-Maximum Suppression (NMS) with 1.70 * R suppression radius
        rawCandidates.sortByDescending { it.score }
        val suppressed = ArrayList<DetectedBall>(16)
        val minSep = r * 1.70f

        for (cand in rawCandidates) {
            var overlaps = false
            for (kept in suppressed) {
                if (hypot(cand.x - kept.x, cand.y - kept.y) < minSep) {
                    overlaps = true
                    break
                }
            }
            if (!overlaps) {
                // Fine 2D local search + binary foreground silhouette centroid refinement
                val localX = (cand.x - tableLeft).coerceIn(margin.toFloat(), (width - margin).toFloat())
                val localY = (cand.y - tableTop).coerceIn(margin.toFloat(), (height - margin).toFloat())
                val refined = refineCentroidHighPrecision(
                    pixels, width, height,
                    localX, localY, r,
                    innerR1, innerR2, edgeR, rimR, outerR,
                    clothR, clothG, clothB, clothLum,
                    cand.isCueCandidate
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

        // Target ball: snap to detected ball closest to currentTargetX/Y (within 6.5R magnetic window),
        // skipping cue ball if other object balls are present.
        var bestTarget: DetectedBall? = null
        var bestTargetDist = Float.MAX_VALUE
        val captureRadius = r * 6.5f

        for (b in suppressed) {
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
        val samplesR = IntArray(96)
        val samplesG = IntArray(96)
        val samplesB = IntArray(96)
        var count = 0

        val xStart = (width * 0.15f).roundToInt()
        val xEnd = (width * 0.85f).roundToInt()
        val yStart = (height * 0.15f).roundToInt()
        val yEnd = (height * 0.85f).roundToInt()
        val stepX = max(1, (xEnd - xStart) / 12)
        val stepY = max(1, (yEnd - yStart) / 8)

        var sy = yStart
        while (sy < yEnd && count < 96) {
            val rowOffset = sy * width
            var sx = xStart
            while (sx < xEnd && count < 96) {
                val c = pixels[rowOffset + sx]
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
        innerR1: Float,
        innerR2: Float,
        edgeR: Float,
        rimR: Float,
        outerR: Float,
        clothR: Int,
        clothG: Int,
        clothB: Int,
        clothLum: Float
    ): DetectedBall? {
        // 1. Sample concentric interior points (inner core + mid disk)
        var innerDiffSum = 0f
        var lumSum = 0f
        var maxSat = 0f
        var highLumCount = 0
        var coloredCount = 0

        val cxInt = cx.roundToInt().coerceIn(0, width - 1)
        val cyInt = cy.roundToInt().coerceIn(0, height - 1)
        val centerPixel = pixels[cyInt * width + cxInt]
        val cR = (centerPixel shr 16) and 0xFF
        val cG = (centerPixel shr 8) and 0xFF
        val cB = centerPixel and 0xFF
        val cMax = max(cR, max(cG, cB))
        val cMin = min(cR, min(cG, cB))
        val cSat = if (cMax > 0) (cMax - cMin).toFloat() / cMax else 0f
        val cLum = cR * 0.299f + cG * 0.587f + cB * 0.114f

        lumSum += cLum
        if (cSat > maxSat) maxSat = cSat
        if (cLum > 175f && cSat < 0.22f) highLumCount++
        if (cSat > 0.35f && cLum in 35f..235f) coloredCount++

        for (i in 0 until RING_SAMPLES) {
            val cosV = ringCos[i]
            val sinV = ringSin[i]

            // Ring 1 (inner core 0.30 R)
            val px1 = (cx + cosV * innerR1).roundToInt().coerceIn(0, width - 1)
            val py1 = (cy + sinV * innerR1).roundToInt().coerceIn(0, height - 1)
            val c1 = pixels[py1 * width + px1]
            val r1 = (c1 shr 16) and 0xFF
            val g1 = (c1 shr 8) and 0xFF
            val b1 = c1 and 0xFF

            innerDiffSum += colorDist(r1, g1, b1, clothR, clothG, clothB)
            val max1 = max(r1, max(g1, b1))
            val min1 = min(r1, min(g1, b1))
            val sat1 = if (max1 > 0) (max1 - min1).toFloat() / max1 else 0f
            val lum1 = r1 * 0.299f + g1 * 0.587f + b1 * 0.114f
            lumSum += lum1
            if (sat1 > maxSat) maxSat = sat1
            if (lum1 > 175f && sat1 < 0.22f) highLumCount++
            if (sat1 > 0.35f && lum1 in 35f..235f) coloredCount++

            // Ring 2 (mid disk 0.60 R)
            val px2 = (cx + cosV * innerR2).roundToInt().coerceIn(0, width - 1)
            val py2 = (cy + sinV * innerR2).roundToInt().coerceIn(0, height - 1)
            val c2 = pixels[py2 * width + px2]
            val r2 = (c2 shr 16) and 0xFF
            val g2 = (c2 shr 8) and 0xFF
            val b2 = c2 and 0xFF

            innerDiffSum += colorDist(r2, g2, b2, clothR, clothG, clothB)
            val max2 = max(r2, max(g2, b2))
            val min2 = min(r2, min(g2, b2))
            val sat2 = if (max2 > 0) (max2 - min2).toFloat() / max2 else 0f
            val lum2 = r2 * 0.299f + g2 * 0.587f + b2 * 0.114f
            lumSum += lum2
            if (sat2 > maxSat) maxSat = sat2
            if (lum2 > 175f && sat2 < 0.22f) highLumCount++
            if (sat2 > 0.35f && lum2 in 35f..235f) coloredCount++
        }

        val totalInnerSamples = RING_SAMPLES * 2 + 1
        val avgInnerDiff = innerDiffSum / (RING_SAMPLES * 2)
        val avgLum = lumSum / totalInnerSamples

        // 2. Sample 16 radial directions across the perimeter (inner rim vs edge/shadow vs outer felt)
        val edgeDeltas = FloatArray(RING_SAMPLES)
        var clothSurroundCount = 0
        var oppositePairContrastCount = 0

        for (i in 0 until RING_SAMPLES) {
            val inX = (cx + ringCos[i] * edgeR).roundToInt().coerceIn(0, width - 1)
            val inY = (cy + ringSin[i] * edgeR).roundToInt().coerceIn(0, height - 1)
            val midX = (cx + ringCos[i] * rimR).roundToInt().coerceIn(0, width - 1)
            val midY = (cy + ringSin[i] * rimR).roundToInt().coerceIn(0, height - 1)
            val outX = (cx + ringCos[i] * outerR).roundToInt().coerceIn(0, width - 1)
            val outY = (cy + ringSin[i] * outerR).roundToInt().coerceIn(0, height - 1)

            val cIn = pixels[inY * width + inX]
            val cMid = pixels[midY * width + midX]
            val cOut = pixels[outY * width + outX]

            val inR = (cIn shr 16) and 0xFF
            val inG = (cIn shr 8) and 0xFF
            val inB = cIn and 0xFF

            val mR = (cMid shr 16) and 0xFF
            val mG = (cMid shr 8) and 0xFF
            val mB = cMid and 0xFF

            val outR = (cOut shr 16) and 0xFF
            val outG = (cOut shr 8) and 0xFF
            val outB = cOut and 0xFF

            val dInMid = colorDist(inR, inG, inB, mR, mG, mB)
            val dInOut = colorDist(inR, inG, inB, outR, outG, outB)
            val dMidOut = colorDist(mR, mG, mB, outR, outG, outB)
            val dEdge = max(dInOut, max(dInMid, dMidOut))
            edgeDeltas[i] = dEdge

            val dClothOut = min(
                colorDist(outR, outG, outB, clothR, clothG, clothB),
                colorDist(mR, mG, mB, clothR, clothG, clothB)
            )
            val outLum = outR * 0.299f + outG * 0.587f + outB * 0.114f
            val midLum = mR * 0.299f + mG * 0.587f + mB * 0.114f
            // Outer point is accepted if it matches cloth felt or ambient drop shadow
            if (dClothOut < 54f || (outLum < clothLum * 0.78f) || (midLum < clothLum * 0.78f)) {
                clothSurroundCount++
            }
        }

        // Check opposite diameter contrast symmetry across 8 axes
        for (i in 0 until RING_SAMPLES / 2) {
            if (edgeDeltas[i] >= 20f && edgeDeltas[i + RING_SAMPLES / 2] >= 20f) {
                oppositePairContrastCount++
            }
        }

        // At least 4 of 8 diameters must show boundary contrast on both sides
        // (prevents triggering on straight rail edges or single cue stick line)
        if (oppositePairContrastCount < 3) return null

        // At least 5 of 16 outer points must face cloth or drop shadow
        if (clothSurroundCount < 5) return null

        // Trimmed Mean: sort edge deltas and discard lowest 3 (allows cue stick or touching ball)
        edgeDeltas.sort()
        var trimmedEdgeSum = 0f
        val trimStart = 3
        for (i in trimStart until RING_SAMPLES) {
            trimmedEdgeSum += edgeDeltas[i]
        }
        val trimmedAvgEdge = trimmedEdgeSum / (RING_SAMPLES - trimStart)

        if (trimmedAvgEdge < 24f && avgInnerDiff < 32f) return null

        // Cue ball detection: high luminance + low saturation across ALL inner points
        // Striped balls have white caps, but coloredCount > 2 or maxSat > 0.32f
        val isCue = (highLumCount >= 18 && avgLum > 165f && maxSat < 0.26f && coloredCount <= 1)

        val score = trimmedAvgEdge * 0.50f +
                avgInnerDiff * 0.40f +
                (clothSurroundCount * 2.8f) +
                (oppositePairContrastCount * 4.5f)

        return DetectedBall(cx, cy, score, isCue)
    }

    private fun refineCentroidHighPrecision(
        pixels: IntArray,
        width: Int,
        height: Int,
        startX: Float,
        startY: Float,
        ballR: Float,
        innerR1: Float,
        innerR2: Float,
        edgeR: Float,
        rimR: Float,
        outerR: Float,
        clothR: Int,
        clothG: Int,
        clothB: Int,
        clothLum: Float,
        initialIsCue: Boolean
    ): DetectedBall {
        // 1. Fine integer peak search: evaluate candidate at dx, dy in -2..+2 (1px resolution)
        var bestX = startX
        var bestY = startY
        var bestScore = -1f
        var bestCue = initialIsCue

        for (dy in -2..2) {
            for (dx in -2..2) {
                val cx = (startX + dx).coerceIn(ballR + 2f, (width - ballR - 2f))
                val cy = (startY + dy).coerceIn(ballR + 2f, (height - ballR - 2f))
                val ev = evaluateCandidate(
                    pixels, width, height,
                    cx, cy,
                    innerR1, innerR2, edgeR, rimR, outerR,
                    clothR, clothG, clothB, clothLum
                )
                if (ev != null && ev.score > bestScore) {
                    bestScore = ev.score
                    bestX = cx
                    bestY = cy
                    bestCue = ev.isCueCandidate
                }
            }
        }

        // 2. Binary foreground silhouette mask center-of-mass (unbiased by specular or stripe luminance)
        val rInt = ballR.roundToInt().coerceAtLeast(4)
        val cxInt = bestX.roundToInt()
        val cyInt = bestY.roundToInt()
        val y0 = max(1, cyInt - rInt)
        val y1 = min(height - 2, cyInt + rInt)
        val x0 = max(1, cxInt - rInt)
        val x1 = min(width - 2, cxInt + rInt)
        val r2 = (ballR * 0.96f) * (ballR * 0.96f)

        var count = 0f
        var sumX = 0f
        var sumY = 0f

        for (py in y0..y1) {
            val dy = (py - bestY)
            val rowOffset = py * width
            for (px in x0..x1) {
                val dx = (px - bestX)
                if (dx * dx + dy * dy > r2) continue

                val c = pixels[rowOffset + px]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                val dCloth = colorDist(r, g, b, clothR, clothG, clothB)
                val lum = r * 0.299f + g * 0.587f + b * 0.114f

                // Binary mask: 1.0 if not cloth felt, 0.0 if cloth
                if (dCloth >= 26f || abs(lum - clothLum) >= 24f) {
                    count += 1f
                    sumX += px
                    sumY += py
                }
            }
        }

        return if (count >= ballR * ballR * 1.1f) {
            val subX = sumX / count
            val subY = sumY / count
            // Constrain sub-pixel nudge to within 1.8 px of fine peak
            val finalX = if (abs(subX - bestX) <= 1.8f) subX else bestX
            val finalY = if (abs(subY - bestY) <= 1.8f) subY else bestY
            DetectedBall(finalX, finalY, max(bestScore, 10f), bestCue)
        } else {
            DetectedBall(bestX, bestY, max(bestScore, 10f), bestCue)
        }
    }

    private fun colorDist(r1: Int, g1: Int, b1: Int, r2: Int, g2: Int, b2: Int): Float {
        val dr = (r1 - r2).toFloat()
        val dg = (g1 - g2).toFloat()
        val db = (b1 - b2).toFloat()
        return abs(dr) * 0.30f + abs(dg) * 0.50f + abs(db) * 0.20f
    }
}
