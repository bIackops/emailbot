package com.dsicalib.app

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt

data class Pt(val x: Float, val y: Float)

fun dist(a: Pt, b: Pt) = hypot(a.x - b.x, a.y - b.y)

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

    /** Overall scale of the correction (1 = unchanged size). */
    val scale: Float get() = sqrt(abs(a * e - b * d))

    /** Rotation of the correction in degrees. */
    val rotationDeg: Float get() = Math.toDegrees(atan2((d - b).toDouble(), (a + e).toDouble())).toFloat()

    /** How far the correction moves a touch at [p]. */
    fun shiftAt(p: Pt): Pt {
        val q = map(p)
        return Pt(q.x - p.x, q.y - p.y)
    }

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

        /**
         * Least-squares affine map sending the [raw] points onto the [target] points.
         * Exact for three points; with more it is the best fit. Null if the points are
         * (nearly) collinear.
         */
        fun fit(raw: List<Pt>, target: List<Pt>): Calibration? {
            require(raw.size == target.size && raw.size >= 3)
            var sxx = 0.0; var sxy = 0.0; var syy = 0.0; var sx = 0.0; var sy = 0.0
            var sxu = 0.0; var syu = 0.0; var su = 0.0
            var sxv = 0.0; var syv = 0.0; var sv = 0.0
            for (i in raw.indices) {
                val x = raw[i].x.toDouble(); val y = raw[i].y.toDouble()
                val u = target[i].x.toDouble(); val v = target[i].y.toDouble()
                sxx += x * x; sxy += x * y; syy += y * y; sx += x; sy += y
                sxu += x * u; syu += y * u; su += u
                sxv += x * v; syv += y * v; sv += v
            }
            val n = raw.size.toDouble()

            // Normal equations N * [p q r]^T = rhs, with N = AᵀA for rows [x y 1].
            // det(N) is the sum of squared triangle determinants, so square the threshold.
            val det = det3(sxx, sxy, sx, sxy, syy, sy, sx, sy, n)
            if (abs(det) < MIN_DET * MIN_DET) return null

            // Cramer's rule, once per output axis.
            fun axis(bx: Double, by: Double, b1: Double) = Triple(
                (det3(bx, sxy, sx, by, syy, sy, b1, sy, n) / det).toFloat(),
                (det3(sxx, bx, sx, sxy, by, sy, sx, b1, n) / det).toFloat(),
                (det3(sxx, sxy, bx, sxy, syy, by, sx, sy, b1) / det).toFloat(),
            )

            val (a, b, c) = axis(sxu, syu, su)
            val (d, e, f) = axis(sxv, syv, sv)
            return Calibration(a, b, c, d, e, f)
        }

        private fun det3(
            a11: Double, a12: Double, a13: Double,
            a21: Double, a22: Double, a23: Double,
            a31: Double, a32: Double, a33: Double,
        ) = a11 * (a22 * a33 - a23 * a32) - a12 * (a21 * a33 - a23 * a31) + a13 * (a21 * a32 - a22 * a31)
    }
}
