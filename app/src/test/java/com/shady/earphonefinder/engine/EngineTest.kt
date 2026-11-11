package com.shady.earphonefinder.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class EngineTest {

    @Test fun medianIgnoresSpikes() {
        val s = RssiSmoother()
        var t = 0L
        repeat(20) { t += 150; s.add(t, -60) }
        s.add(t + 150, -30) // one crazy spike
        assertEquals(-60.0, s.value!!, 0.5)
    }

    @Test fun trendIsWarmerWhenWalkingCloser() {
        val s = RssiSmoother()
        var t = 0L
        for (i in 0 until 60) { t += 150; s.add(t, -80 + i / 3) } // +20 dB over 9 s
        assertEquals(Trend.WARMER, s.trend(t))
    }

    @Test fun trendIsSteadyWhenStill() {
        val s = RssiSmoother()
        val rnd = Random(1)
        var t = 0L
        var lies = 0
        repeat(200) {
            t += 150
            s.add(t, -65 + (rnd.nextDouble(-4.0, 4.0)).toInt())
            if (t > 5_000 && s.trend(t) != Trend.STEADY) lies++
        }
        assertTrue("trend flickered $lies times", lies < 15)
    }

    @Test fun sweepFindsBearing() {
        val rnd = Random(7)
        for (bud in listOf(0.0, 75.0, 190.0, 300.0)) {
            val sweep = DirectionSweep()
            var h = 0.0
            while (h < 360) {
                val off = abs(DirectionSweep.turn(h, bud))
                val block = ((off - 90) / 90).coerceAtLeast(0.0)
                sweep.add(h, (-65 - 10 * block + rnd.nextDouble(-3.0, 3.0)).toInt())
                h += 3
            }
            val r = sweep.result()
            assertNotNull(r)
            val err = abs(DirectionSweep.turn(r!!.headingDeg, bud))
            assertTrue("bud $bud, got ${r.headingDeg}", err < 35)
        }
    }

    @Test fun sweepNeedsMostOfTheCircle() {
        val sweep = DirectionSweep()
        for (h in 0 until 180 step 5) sweep.add(h.toDouble(), -60)
        assertNull(sweep.result())
    }

    @Test fun turnWrapsAround() {
        assertEquals(20.0, DirectionSweep.turn(350.0, 10.0), 1e-9)
        assertEquals(-20.0, DirectionSweep.turn(10.0, 350.0), 1e-9)
    }
}
