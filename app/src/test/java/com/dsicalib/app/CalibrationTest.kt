package com.dsicalib.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CalibrationTest {
    private val three = listOf(Pt(32f, 24f), Pt(224f, 168f), Pt(32f, 168f))
    private val five = listOf(Pt(24f, 80f), Pt(232f, 420f), Pt(232f, 80f), Pt(24f, 420f), Pt(128f, 250f))

    private fun assertPt(expected: Pt, actual: Pt, delta: Float = 0.01f) {
        assertEquals(expected.x, actual.x, delta)
        assertEquals(expected.y, actual.y, delta)
    }

    @Test
    fun exactTouchesGiveIdentity() {
        val cal = Calibration.fit(five, five)!!
        assertPt(Pt(10f, 20f), cal.map(Pt(10f, 20f)))
        assertPt(Pt(250f, 5f), cal.map(Pt(250f, 5f)))
        assertEquals(1f, cal.scale, 0.001f)
        assertEquals(0f, cal.rotationDeg, 0.001f)
    }

    @Test
    fun threePointsAreSolvedExactly() {
        val raw = three.map { Pt(it.x + 6f, it.y - 4f) }
        val cal = Calibration.fit(raw, three)!!
        raw.zip(three).forEach { (r, e) -> assertPt(e, cal.map(r)) }
        assertPt(Pt(100f, 100f), cal.map(Pt(106f, 96f)))
        assertPt(Pt(-6f, 4f), cal.shiftAt(Pt(50f, 50f)))
    }

    @Test
    fun offsetAndScaleAreCorrectedWithFivePoints() {
        val raw = five.map { Pt(it.x / 2f + 3f, it.y / 2f - 1f) }
        val cal = Calibration.fit(raw, five)!!
        raw.zip(five).forEach { (r, e) -> assertPt(e, cal.map(r), 0.05f) }
        assertEquals(2f, cal.scale, 0.001f)
    }

    @Test
    fun noisyTouchesGiveBestFit() {
        // Symmetric noise cancels out, so the best fit is the identity.
        val noise = listOf(Pt(2f, 0f), Pt(2f, 0f), Pt(-2f, 0f), Pt(-2f, 0f), Pt(0f, 0f))
        val raw = five.zip(noise).map { (t, n) -> Pt(t.x + n.x, t.y + n.y) }
        val cal = Calibration.fit(raw, five)!!
        assertPt(Pt(128f, 250f), cal.map(Pt(128f, 250f)), 0.5f)
    }

    @Test
    fun collinearTouchesAreRejected() {
        assertNull(Calibration.fit(listOf(Pt(0f, 0f), Pt(10f, 10f), Pt(20f, 20f)), three))
    }

    @Test
    fun encodeRoundTrips() {
        val cal = Calibration(1.1f, 0.02f, -3f, 0.01f, 0.95f, 4.5f)
        assertEquals(cal, Calibration.decode(cal.encode()))
        assertNull(Calibration.decode("garbage"))
        assertNull(Calibration.decode(null))
    }
}
