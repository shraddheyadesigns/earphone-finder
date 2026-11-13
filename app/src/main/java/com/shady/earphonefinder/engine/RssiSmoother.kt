package com.shady.earphonefinder.engine

import kotlin.math.exp

enum class Trend { WARMER, COLDER, STEADY, UNKNOWN }

/**
 * Turns noisy Bluetooth signal readings into a steady number.
 *
 * Step 1: median of the last [medianWindowMs] of raw readings (kills single spikes).
 * Step 2: time-aware exponential smoothing with time constant [tauMs]
 *         (works even when adverts arrive at uneven gaps).
 * Trend compares the smoothed value now with [trendLookbackMs] ago.
 *
 * Defaults were picked with tools/sim.py: ~3% false warmer/colder when standing
 * still, <1% "colder" while actually walking closer.
 */
class RssiSmoother(
    private val medianWindowMs: Long = 2_000,
    private val tauMs: Double = 1_500.0,
    private val trendLookbackMs: Long = 3_000,
    private val trendThresholdDb: Double = 3.0,
    /** When the last three readings all moved this far from where we are, it's a real move, not noise.
     *  Picked with a quick sim: about 2.3 s instead of 4 s to catch a 10 ft → 1 ft walk at ~1 reading a
     *  second, with no more false jumps while standing still than before. */
    private val jumpDb: Double = 7.0,
    private val fastTauMs: Double = 350.0,
) {
    private data class Sample(val t: Long, val v: Double)

    private val window = ArrayDeque<Sample>()
    private val recent = ArrayDeque<Double>()
    private val history = ArrayDeque<Sample>()
    private var lastT = 0L

    var value: Double? = null
        private set
    var lastSampleAt: Long? = null
        private set

    fun add(tMs: Long, rssi: Int): Double {
        window.addLast(Sample(tMs, rssi.toDouble()))
        recent.addLast(rssi.toDouble()); while (recent.size > 3) recent.removeFirst()
        while (window.isNotEmpty() && tMs - window.first().t > medianWindowMs) window.removeFirst()

        val med = median(window.map { it.v })
        val prev = value
        val next = if (prev == null) med else {
            val dt = (tMs - lastT).coerceAtLeast(0L).toDouble()
            // Fast path: three readings in a row agree on a big change (you walked over, or away).
            // Follow them quickly instead of waiting for the 2 s median to catch up.
            val lastFew = recent.takeLast(3)
            val moved = lastFew.size == 3 &&
                (lastFew.all { it - prev > jumpDb } || lastFew.all { prev - it > jumpDb })
            if (moved) {
                val target = lastFew.average()
                prev + (1.0 - exp(-dt / fastTauMs)) * (target - prev)
            } else {
                val alpha = 1.0 - exp(-dt / tauMs)
                prev + alpha * (med - prev)
            }
        }
        value = next
        lastT = tMs
        lastSampleAt = tMs

        history.addLast(Sample(tMs, next))
        while (history.size > 2 && tMs - history.first().t > 10_000) history.removeFirst()
        return next
    }

    fun trend(nowMs: Long): Trend {
        val now = value ?: return Trend.UNKNOWN
        val past = history.lastOrNull { it.t <= nowMs - trendLookbackMs } ?: return Trend.UNKNOWN
        val diff = now - past.v
        return when {
            diff > trendThresholdDb -> Trend.WARMER
            diff < -trendThresholdDb -> Trend.COLDER
            else -> Trend.STEADY
        }
    }

    fun msSinceLastSample(nowMs: Long): Long? = lastSampleAt?.let { nowMs - it }

    fun reset() {
        window.clear(); recent.clear(); history.clear(); value = null; lastSampleAt = null; lastT = 0L
    }

    companion object {
        fun median(xs: List<Double>): Double {
            val s = xs.sorted()
            val n = s.size
            require(n > 0)
            return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2.0
        }
    }
}
