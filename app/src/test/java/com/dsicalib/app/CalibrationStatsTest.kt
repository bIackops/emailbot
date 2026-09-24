package com.dsicalib.app

import org.junit.Assert.assertEquals
import org.junit.Test

class CalibrationStatsTest {
    private val targets = listOf(Pt(24f, 80f), Pt(232f, 420f), Pt(232f, 80f), Pt(24f, 420f), Pt(128f, 250f))

    private fun stats(touches: List<Pt>, mmPerPx: Float = 0.25f): CalibrationStats {
        val samples = targets.zip(touches).map { (t, p) -> TouchSample(t, p, reactionMs = 500, driftPx = 1f) }
        return CalibrationStats(samples, Calibration.fit(touches, targets)!!, mmPerPx)
    }

    @Test
    fun perfectRunScoresFull() {
        val s = stats(targets)
        assertEquals(0f, s.avgErrorPx, 1e-4f)
        assertEquals(100, s.score)
        assertEquals('S', s.grade)
        assertEquals(0f, s.residualPx, 1e-3f)
    }

    @Test
    fun offsetRunIsMeasured() {
        val s = stats(targets.map { Pt(it.x + 4f, it.y) })
        assertEquals(4f, s.avgErrorPx, 1e-4f)
        assertEquals(1f, s.avgErrorMm, 1e-4f)
        assertEquals(82, s.score)
        assertEquals('A', s.grade)
        assertEquals(4f, s.bias.x, 1e-4f)
        assertEquals(0f, s.bias.y, 1e-4f)
        assertEquals(0, CalibrationStats.directionIndex(s.bias))
        // A pure offset is fully corrected, so nothing is left over.
        assertEquals(0f, s.residualPx, 0.01f)
        assertEquals(500L, s.avgReactionMs)
        assertEquals(1f, s.avgDriftPx, 1e-4f)
    }

    @Test
    fun worstPointIsFound() {
        val touches = targets.mapIndexed { i, t -> if (i == 3) Pt(t.x + 9f, t.y) else Pt(t.x + 1f, t.y) }
        val s = stats(touches)
        assertEquals(3, s.worstIndex)
        assertEquals(9f, s.errorsPx[3], 1e-4f)
    }

    @Test
    fun scoresAndGrades() {
        assertEquals(100, CalibrationStats.scoreFor(0f))
        assertEquals(0, CalibrationStats.scoreFor(10f))
        assertEquals('S', CalibrationStats.gradeFor(85))
        assertEquals('A', CalibrationStats.gradeFor(84))
        assertEquals('A', CalibrationStats.gradeFor(70))
        assertEquals('B', CalibrationStats.gradeFor(69))
        assertEquals('C', CalibrationStats.gradeFor(40))
        assertEquals('D', CalibrationStats.gradeFor(39))
    }

    @Test
    fun directions() {
        val expected = listOf(
            Pt(1f, 0f) to 0, Pt(1f, 1f) to 1, Pt(0f, 1f) to 2, Pt(-1f, 1f) to 3,
            Pt(-1f, 0f) to 4, Pt(-1f, -1f) to 5, Pt(0f, -1f) to 6, Pt(1f, -1f) to 7,
        )
        expected.forEach { (v, i) -> assertEquals("direction of $v", i, CalibrationStats.directionIndex(v)) }
    }
}
