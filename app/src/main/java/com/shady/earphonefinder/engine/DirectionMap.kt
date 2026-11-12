package com.shady.earphonefinder.engine

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin

/**
 * One shared, always-on estimate of where the bud is. Walking, natural turning and spins all feed it.
 *
 * A grid of candidate spots (±[halfM] m around where the hunt started). Every reading carries where
 * you were (steps + compass) and which way you faced. For each spot the expected signal shape is
 *     -10·n·log10(distance) - BODY·blocked(facing vs direction to the spot)
 * The bud's own loudness is unknown, so each spot fits its own offset: we keep running sums of the
 * residual and score spots by how consistent it is. Old evidence fades over [tauMs].
 *
 * tools/map_sim.py, with reflections and a body loss the model doesn't know:
 * one spin ~16° median, walking ~15°, spin then walk ~6°, confidently wrong 0-5%.
 */
class DirectionMap(
    private val halfM: Double = 10.0,
    private val cellM: Double = 0.5,
    private val tauMs: Double = 30_000.0,
    private val stepM: Double = 0.7,
) {
    data class Estimate(
        val bearingDeg: Double,     // compass bearing from you to the likely spot
        val confidence: Double,     // 0..1, how concentrated the likely spots are in one direction
        val distanceM: Double,
        val evidence: Double,       // effective number of readings behind it
    )

    private val n = (2 * halfM / cellM).toInt() + 1
    private val gx = DoubleArray(n) { -halfM + it * cellM }
    private val w = DoubleArray(n * n)
    private val s1 = DoubleArray(n * n)
    private val s2 = DoubleArray(n * n)
    private var lastT = -1L

    var x = 0.0; private set
    var y = 0.0; private set
    var steps = 0; private set

    fun onStep(headingDeg: Double) {
        val a = Math.toRadians(headingDeg)
        x += stepM * sin(a); y += stepM * cos(a)
        steps++
    }

    /** One reading where you stand now, facing [headingDeg]. Spin samples come in with a higher [weight]. */
    fun add(tMs: Long, rssi: Int, headingDeg: Double, weight: Double = 1.0) {
        add(tMs, rssi, headingDeg, x, y, weight)
    }

    /** Same, at a given spot (used to replay buffered spin samples). */
    fun add(tMs: Long, rssi: Int, headingDeg: Double, atX: Double, atY: Double, weight: Double) {
        if (lastT >= 0 && tMs > lastT) {
            val k = exp(-(tMs - lastT) / tauMs)
            for (i in w.indices) { w[i] *= k; s1[i] *= k; s2[i] *= k }
        }
        if (tMs > lastT) lastT = tMs
        val h = Math.toRadians(headingDeg)
        for (j in 0 until n) {
            val dy = gx[j] - atY
            for (i in 0 until n) {
                val dx = gx[i] - atX
                val d = max(0.3, hypot(dx, dy))
                val off = abs(wrap(atan2(dx, dy) - h))
                val blocked = max(0.0, (off - PI / 2) / (PI / 2))
                val r = rssi - (-10 * N_EXP * log10(d) - BODY_DB * blocked)
                val idx = j * n + i
                w[idx] += weight; s1[idx] += weight * r; s2[idx] += weight * r * r
            }
        }
    }

    fun estimate(): Estimate? {
        if (w[0] <= 1e-6) return null
        var minCost = Double.MAX_VALUE
        val cost = DoubleArray(w.size)
        for (i in w.indices) {
            cost[i] = (s2[i] - s1[i] * s1[i] / w[i]) / (2 * SIGMA_DB * SIGMA_DB)
            if (cost[i] < minCost) minCost = cost[i]
        }
        var bx = 0.0; var by = 0.0; var z = 0.0; var sx = 0.0; var sy = 0.0
        for (idx in w.indices) {
            val p = exp(-(cost[idx] - minCost))
            if (p < 1e-6) continue
            val dx = gx[idx % n] - x
            val dy = gx[idx / n] - y
            val b = atan2(dx, dy)
            bx += p * sin(b); by += p * cos(b); z += p
            sx += p * dx; sy += p * dy
        }
        if (z <= 0) return null
        return Estimate(
            bearingDeg = (Math.toDegrees(atan2(bx, by)) + 360.0) % 360.0,
            confidence = hypot(bx, by) / z,
            distanceM = hypot(sx / z, sy / z),
            evidence = w[0],
        )
    }

    fun reset() {
        w.fill(0.0); s1.fill(0.0); s2.fill(0.0); lastT = -1L; x = 0.0; y = 0.0; steps = 0
    }

    companion object {
        const val N_EXP = 2.5
        const val BODY_DB = 9.0
        const val SIGMA_DB = 7.0    // wide on purpose: keeps confidence honest when the room misbehaves
        private fun wrap(a: Double): Double {
            var v = (a + PI) % (2 * PI)
            if (v < 0) v += 2 * PI
            return v - PI
        }
    }
}
