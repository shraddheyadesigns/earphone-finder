package com.shady.earphonefinder.engine

import com.shady.earphonefinder.ui.glow.GlowAnimator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.sin
import kotlin.random.Random

class BearingTest {

    private fun turn(a: Double, b: Double) = ((b - a + 540) % 360) - 180

    /** Walk an L shape near a bud and check the estimate points roughly at it. */
    private fun walkAndEstimate(budBearing: Double, budDist: Double, seed: Int): BearingEstimator.Estimate? {
        val rnd = Random(seed)
        val est = BearingEstimator()
        val bx = budDist * sin(Math.toRadians(budBearing))
        val by = budDist * cos(Math.toRadians(budBearing))
        var x = 0.0; var y = 0.0; var t = 0L
        val legs = listOf(30.0, 30.0, 30.0, 120.0, 120.0, 120.0, 220.0, 220.0, 220.0, 220.0)
        for (h in legs) {
            est.onStep(h)
            x += 0.7 * sin(Math.toRadians(h)); y += 0.7 * cos(Math.toRadians(h))
            repeat(5) {
                t += 120
                val d = maxOf(0.3, hypot(bx - x, by - y))
                est.onRssi(t, (-59 - 25 * log10(d) + rnd.nextDouble(-3.0, 3.0)).toInt())
            }
        }
        return est.update(t)
    }

    @Test fun pointsTowardTheBud() {
        var good = 0
        for ((i, b) in listOf(0.0, 80.0, 170.0, 260.0, 330.0).withIndex()) {
            val e = walkAndEstimate(b, 5.0, i)
            if (e != null) {
                // true bearing from the walker's final spot is close enough to the start bearing at 5 m
                if (abs(turn(e.bearingDeg, b)) < 60) good++
            }
        }
        assertTrue("only $good of 5 pointed the right way", good >= 4)
    }

    @Test fun noAnswerWithoutWalking() {
        val est = BearingEstimator()
        var t = 0L
        repeat(60) { t += 120; est.onRssi(t, -65) }
        assertEquals(null, est.update(t))
        assertFalse(est.locked)
    }

    @Test fun mapPointsTowardTheBudAfterASpinAndAWalk() {
        val rnd = Random(11)
        var good = 0
        for ((n, bud) in listOf(80.0, 200.0, 320.0).withIndex()) {
            val map = DirectionMap()
            val bx = 4.0 * sin(Math.toRadians(bud)); val by = 4.0 * cos(Math.toRadians(bud))
            var t = 0L; var h = 0.0
            fun read() {
                val dx = bx - map.x; val dy = by - map.y
                val d = maxOf(0.3, hypot(dx, dy))
                val off = abs(turn(h, (Math.toDegrees(kotlin.math.atan2(dx, dy)) + 360) % 360))
                val blocked = ((off - 90) / 90).coerceAtLeast(0.0)
                map.add(t, (-59 - 25 * log10(d) - 9 * blocked + rnd.nextDouble(-3.0, 3.0)).toInt(), h, 1.5)
            }
            repeat(20) { t += 1000; h = (h + 18) % 360; read() }      // a spin
            repeat(4) { t += 600; map.onStep(h); read() }             // a few steps
            val e = map.estimate()!!
            val trueB = (Math.toDegrees(kotlin.math.atan2(bx - map.x, by - map.y)) + 360) % 360
            if (abs(turn(e.bearingDeg, trueB)) < 45) good++
            assertTrue("case $n confidence ${e.confidence}", e.confidence in 0.0..1.0)
        }
        assertTrue("only $good of 3 pointed the right way", good >= 2)
    }

    @Test fun closenessScale() {
        assertEquals(0.0, GlowAnimator.closenessFromFeet(40.0), 0.01)
        assertTrue(GlowAnimator.closenessFromFeet(10.0) in 0.33..0.41)
        assertTrue(GlowAnimator.closenessFromFeet(1.0) > 0.95)
    }

    @Test fun feetLabels() {
        assertEquals("far" to "", GlowAnimator.formatFeet(40.0))
        assertEquals("15" to "ft", GlowAnimator.formatFeet(14.0))
        assertEquals("1" to "ft", GlowAnimator.formatFeet(0.3))
    }
}
