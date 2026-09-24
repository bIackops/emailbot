package com.dsicalib.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionPointerTest {
    private val strong = 30f // about 3 g

    @Test
    fun twoSpikesMakeAShake() {
        val d = ShakeDetector()
        assertFalse(d.onReading(strong, 0f, 0f, 1000))
        assertFalse(d.onReading(0f, 0f, 9.8f, 1100))
        assertTrue(d.onReading(-strong, 0f, 0f, 1250))
    }

    @Test
    fun oneLongSpikeIsNotAShake() {
        val d = ShakeDetector()
        for (t in 1000L..1300L step 20) assertFalse(d.onReading(strong, 0f, 0f, t))
    }

    @Test
    fun spikesTooFarApartAreNotAShake() {
        val d = ShakeDetector()
        assertFalse(d.onReading(strong, 0f, 0f, 1000))
        assertFalse(d.onReading(strong, 0f, 0f, 2000))
    }

    @Test
    fun gentleMovementIsIgnored() {
        val d = ShakeDetector()
        for (t in 1000L..3000L step 20) assertFalse(d.onReading(12f, 3f, 9.8f, t))
    }

    @Test
    fun cooldownStopsInstantRetrigger() {
        val d = ShakeDetector()
        d.onReading(strong, 0f, 0f, 1000)
        assertTrue(d.onReading(strong, 0f, 0f, 1200))
        assertFalse(d.onReading(strong, 0f, 0f, 1400))
        assertFalse(d.onReading(strong, 0f, 0f, 1600))
        assertFalse(d.onReading(strong, 0f, 0f, 3000))
        assertTrue(d.onReading(strong, 0f, 0f, 3200))
    }

    @Test
    fun gyroMovesAndClampsCursor() {
        val p = GyroPointer()
        p.center(200, 400)
        // Aiming right is a negative rotation about Y; aiming up is positive about X.
        p.update(gx = 0.5f, gy = -1f, dtSec = 0.1f, pxPerRad = 100f, width = 200, height = 400)
        assertEquals(110f, p.x, 1e-3f)
        assertEquals(195f, p.y, 1e-3f)
        p.update(gx = 0f, gy = -100f, dtSec = 1f, pxPerRad = 100f, width = 200, height = 400)
        assertEquals(199f, p.x, 1e-3f)
    }

    @Test
    fun gyroNoiseIsIgnored() {
        val p = GyroPointer()
        p.center(200, 400)
        p.update(gx = 0.01f, gy = -0.01f, dtSec = 1f, pxPerRad = 100f, width = 200, height = 400)
        assertEquals(100f, p.x, 1e-3f)
        assertEquals(200f, p.y, 1e-3f)
    }
}
