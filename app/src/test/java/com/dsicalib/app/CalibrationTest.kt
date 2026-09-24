package com.dsicalib.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CalibrationTest {
    private val targets = listOf(Pt(32f, 24f), Pt(224f, 168f), Pt(32f, 168f))

    private fun assertPt(expected: Pt, actual: Pt) {
        assertEquals(expected.x, actual.x, 0.01f)
        assertEquals(expected.y, actual.y, 0.01f)
    }

    @Test
    fun exactTouchesGiveIdentity() {
        val cal = Calibration.solve(targets, targets)!!
        assertPt(Pt(10f, 20f), cal.map(Pt(10f, 20f)))
        assertPt(Pt(250f, 5f), cal.map(Pt(250f, 5f)))
    }

    @Test
    fun offsetTouchesAreCorrected() {
        val t = targets
        val raw = t.map { Pt(it.x + 6f, it.y - 4f) }
        val cal = Calibration.solve(raw, t)!!
        raw.zip(t).forEach { (r, e) -> assertPt(e, cal.map(r)) }
        assertPt(Pt(100f, 100f), cal.map(Pt(106f, 96f)))
    }

    @Test
    fun collinearTouchesAreRejected() {
        assertNull(Calibration.solve(listOf(Pt(0f, 0f), Pt(10f, 10f), Pt(20f, 20f)), targets))
    }

    @Test
    fun encodeRoundTrips() {
        val cal = Calibration(1.1f, 0.02f, -3f, 0.01f, 0.95f, 4.5f)
        assertEquals(cal, Calibration.decode(cal.encode()))
        assertNull(Calibration.decode("garbage"))
        assertNull(Calibration.decode(null))
    }
}
