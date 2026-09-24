package com.dsicalib.app

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sqrt

/** One calibration mark: where it was, where it was touched, and how. */
data class TouchSample(
    val target: Pt,
    val touch: Pt,
    /** Time from the mark appearing to the touch landing. */
    val reactionMs: Long,
    /** Furthest the touch wandered from where it first landed before release. */
    val driftPx: Float,
)

/** Accuracy figures for one calibration run. Distances are in screen pixels; [mmPerPx] converts. */
class CalibrationStats(
    val samples: List<TouchSample>,
    val calibration: Calibration,
    val mmPerPx: Float,
) {
    init {
        require(samples.isNotEmpty())
    }

    val errorsPx: List<Float> = samples.map { dist(it.touch, it.target) }
    val avgErrorPx: Float = errorsPx.average().toFloat()
    val worstIndex: Int = errorsPx.indices.maxBy { errorsPx[it] }
    val avgReactionMs: Long = samples.map { it.reactionMs }.average().roundToLong()
    val avgDriftPx: Float = samples.map { it.driftPx }.average().toFloat()

    /** RMS error left after the fitted correction: how consistent the touches were with each other. */
    val residualPx: Float = sqrt(
        samples.map {
            val d = dist(calibration.map(it.touch), it.target).toDouble()
            d * d
        }.average(),
    ).toFloat()

    /** Average offset of the touches from their marks (positive y is downward). */
    val bias: Pt = Pt(
        samples.map { it.touch.x - it.target.x }.average().toFloat(),
        samples.map { it.touch.y - it.target.y }.average().toFloat(),
    )

    val avgErrorMm: Float get() = avgErrorPx * mmPerPx
    val score: Int = scoreFor(avgErrorPx * mmPerPx)
    val grade: Char = gradeFor(score)

    companion object {
        /** 100 for a perfect run, losing 18 points per millimetre of average error. */
        fun scoreFor(avgErrorMm: Float): Int = (100f - avgErrorMm * 18f).roundToInt().coerceIn(0, 100)

        fun gradeFor(score: Int): Char = when {
            score >= 85 -> 'S'
            score >= 70 -> 'A'
            score >= 55 -> 'B'
            score >= 40 -> 'C'
            else -> 'D'
        }

        /** Index into right, down-right, down, down-left, left, up-left, up, up-right (y grows downward). */
        fun directionIndex(v: Pt): Int {
            val octant = (atan2(v.y, v.x) / (PI / 4)).roundToInt()
            return ((octant % 8) + 8) % 8
        }
    }
}
