package com.dsicalib.app

import kotlin.math.abs

data class Pt(val x: Float, val y: Float)

/**
 * Affine map from raw touch coordinates to screen coordinates:
 *   x' = a*x + b*y + c
 *   y' = d*x + e*y + f
 */
data class Calibration(
    val a: Float, val b: Float, val c: Float,
    val d: Float, val e: Float, val f: Float,
) {
    fun map(p: Pt) = Pt(a * p.x + b * p.y + c, d * p.x + e * p.y + f)

    fun encode() = listOf(a, b, c, d, e, f).joinToString(",")

    companion object {
        val IDENTITY = Calibration(1f, 0f, 0f, 0f, 1f, 0f)

        /** Twice the smallest triangle area (in screen pixels²) accepted as non-degenerate. */
        private const val MIN_DET = 100.0

        fun decode(s: String?): Calibration? {
            val v = s?.split(',')?.mapNotNull { it.toFloatOrNull() } ?: return null
            if (v.size != 6) return null
            return Calibration(v[0], v[1], v[2], v[3], v[4], v[5])
        }

        /** Solves the affine map that sends the three [raw] points onto the three [target] points. */
        fun solve(raw: List<Pt>, target: List<Pt>): Calibration? {
            require(raw.size == 3 && target.size == 3)
            val x0 = raw[0].x.toDouble(); val y0 = raw[0].y.toDouble()
            val x1 = raw[1].x.toDouble(); val y1 = raw[1].y.toDouble()
            val x2 = raw[2].x.toDouble(); val y2 = raw[2].y.toDouble()

            val det = x0 * (y1 - y2) - y0 * (x1 - x2) + (x1 * y2 - x2 * y1)
            if (abs(det) < MIN_DET) return null

            // Cramer's rule for [x y 1] * [p q r]^T = t, once per output axis.
            fun axis(t0: Double, t1: Double, t2: Double): Triple<Float, Float, Float> {
                val p = t0 * (y1 - y2) - y0 * (t1 - t2) + (t1 * y2 - t2 * y1)
                val q = x0 * (t1 - t2) - t0 * (x1 - x2) + (x1 * t2 - x2 * t1)
                val r = x0 * (y1 * t2 - y2 * t1) - y0 * (x1 * t2 - x2 * t1) + t0 * (x1 * y2 - x2 * y1)
                return Triple((p / det).toFloat(), (q / det).toFloat(), (r / det).toFloat())
            }

            val (a, b, c) = axis(target[0].x.toDouble(), target[1].x.toDouble(), target[2].x.toDouble())
            val (d, e, f) = axis(target[0].y.toDouble(), target[1].y.toDouble(), target[2].y.toDouble())
            return Calibration(a, b, c, d, e, f)
        }
    }
}
