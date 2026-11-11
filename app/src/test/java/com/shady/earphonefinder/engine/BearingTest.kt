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
