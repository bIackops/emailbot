package com.dsicalib.app

import kotlin.math.abs
import kotlin.math.sqrt

/** Detects a shake: two strong acceleration spikes close together. */
class ShakeDetector(
    private val thresholdG: Float = 2.3f,
    private val windowMs: Long = 600,
    private val cooldownMs: Long = 1200,
) {
    private var firstSpikeAt: Long? = null
    private var lastHighAt: Long? = null
    private var lastShakeAt: Long? = null

    /** Feeds one accelerometer reading in m/s². Returns true when a shake completes. */
    fun onReading(x: Float, y: Float, z: Float, timeMs: Long): Boolean {
        val g = sqrt(x * x + y * y + z * z) / GRAVITY
        if (g < thresholdG) return false
        lastShakeAt?.let { if (timeMs - it < cooldownMs) return false }

        // Consecutive high readings belong to the same spike.
        val previousHigh = lastHighAt
        lastHighAt = timeMs
        if (previousHigh != null && timeMs - previousHigh < SPIKE_GAP_MS) return false

        val first = firstSpikeAt
        if (first == null || timeMs - first > windowMs) {
            firstSpikeAt = timeMs
            return false
        }
        firstSpikeAt = null
        lastShakeAt = timeMs
        return true
    }

    private companion object {
        const val GRAVITY = 9.81f
        const val SPIKE_GAP_MS = 80L
    }
}

/**
 * Cursor aimed by the gyroscope, like a motion remote. Aiming the back of the phone
 * right or up moves the cursor right or up.
 */
class GyroPointer {
    var x = 0f
        private set
    var y = 0f
        private set

    val pos get() = Pt(x, y)

    fun center(width: Int, height: Int) {
        x = width / 2f
        y = height / 2f
    }

    /**
     * Applies one gyroscope reading ([gx], [gy] in rad/s around the device X and Y axes)
     * over [dtSec] seconds, keeping the cursor inside [width] x [height].
     */
    fun update(gx: Float, gy: Float, dtSec: Float, pxPerRad: Float, width: Int, height: Int) {
        val rx = if (abs(gx) < DEAD_ZONE) 0f else gx
        val ry = if (abs(gy) < DEAD_ZONE) 0f else gy
        x = (x - ry * dtSec * pxPerRad).coerceIn(0f, width - 1f)
        y = (y - rx * dtSec * pxPerRad).coerceIn(0f, height - 1f)
    }

    private companion object {
        /** Ignore tiny rotation rates so gyro noise does not make the cursor creep. */
        const val DEAD_ZONE = 0.02f
    }
}
