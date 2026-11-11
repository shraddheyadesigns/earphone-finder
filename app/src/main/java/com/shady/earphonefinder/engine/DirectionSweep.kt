package com.shady.earphonefinder.engine

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * The body-as-antenna trick. Hold the phone in front of your chest and turn slowly
 * in a full circle. Your body blocks the signal, so it is weakest when the bud is
 * behind you and strongest when you face it.
 *
 * Raw (not smoothed) readings go in, so the smoother's lag doesn't skew the angle.
 * Readings are binned into [sectors] slices; each slice keeps its median.
 * The answer is a weighted circular mean that leans on the strongest slices.
 *
 * In tools/sim.py: median error ~9 degrees, 80% of sweeps within ~20 degrees.
 */
class DirectionSweep(private val sectors: Int = 12) {

    data class Result(
        val headingDeg: Double,   // compass heading to face
        val spreadDb: Double,     // strongest slice minus weakest slice
    ) {
        val confidence: String
            get() = when {
                spreadDb >= 8 -> "Strong"
                spreadDb >= 4 -> "Okay"
                else -> "Weak. Try again, or step to another spot"
            }
    }

    private val size = 360.0 / sectors
    private val bins = Array(sectors) { mutableListOf<Double>() }

    fun add(headingDeg: Double, rssi: Int) {
        bins[sectorOf(headingDeg)].add(rssi.toDouble())
    }

    fun sectorOf(headingDeg: Double): Int {
        val h = ((headingDeg % 360.0) + 360.0) % 360.0
        return (((h + size / 2) / size).toInt()) % sectors
    }

    /** Share of the circle that has at least one reading, 0..1. */
    val coverage: Float get() = bins.count { it.isNotEmpty() }.toFloat() / sectors

    fun visited(): List<Boolean> = bins.map { it.isNotEmpty() }

    fun result(minCoverage: Float = 0.75f): Result? {
        if (coverage < minCoverage) return null
        val med = bins.withIndex()
            .filter { it.value.isNotEmpty() }
            .associate { it.index to RssiSmoother.median(it.value) }
        val lo = med.values.min()
        val hi = med.values.max()
        var x = 0.0
        var y = 0.0
        for ((i, m) in med) {
            val w = (m - lo) * (m - lo)
            val a = Math.toRadians(i * size)
            x += w * sin(a)
            y += w * cos(a)
        }
        if (x == 0.0 && y == 0.0) return Result(0.0, 0.0)
        val heading = (Math.toDegrees(atan2(x, y)) + 360.0) % 360.0
        return Result(heading, hi - lo)
    }

    fun reset() = bins.forEach { it.clear() }

    companion object {
        /** Signed turn from [from] to [to] in degrees, -180..180. Positive = turn right. */
        fun turn(from: Double, to: Double): Double = ((to - from + 540.0) % 360.0) - 180.0
    }
}
