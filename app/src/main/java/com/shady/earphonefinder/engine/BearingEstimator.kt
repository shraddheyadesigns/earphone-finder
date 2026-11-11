package com.shady.earphonefinder.engine

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Walk-to-calibrate. As you walk, each step moves you ~[stepM] along the compass heading,
 * so we know roughly where you've been. Every signal reading is tagged with that spot.
 * Fitting  rssi = a + gx*x + gy*y  over the spots gives a gradient that points at the bud.
 *
 * Recent readings weigh more ([tauMs]) so the answer follows you as you move.
 * Locks when confidence passes [lockConf], unlocks below [unlockConf] (hysteresis).
 *
 * tools/bearing_sim.py (3-6 m away, 4 dB noise, 10 deg compass error):
 * locks in ~7 s of walking, median error ~22 deg, 80% within ~40 deg.
 * Up close (1.5-3 m) error grows, which is why the glow arc widens near the bud.
 */
class BearingEstimator(
    private val stepM: Double = 0.7,
    private val windowMs: Long = 20_000,
    private val tauMs: Double = 8_000.0,
    private val minSpanM: Double = 1.2,
    private val lockConf: Double = 0.55,
    private val unlockConf: Double = 0.35,
) {
    data class Estimate(
        val bearingDeg: Double,     // compass bearing toward the bud (0 = north)
        val confidence: Double,     // 0..1
        val gradientDbPerM: Double,
    )

    private data class P(val t: Long, val x: Double, val y: Double, val r: Double)

    private val pts = ArrayDeque<P>()
    private var x = 0.0
    private var y = 0.0

    var steps = 0
        private set
    var locked = false
        private set
    var last: Estimate? = null
        private set

    fun onStep(headingDeg: Double) {
        val a = Math.toRadians(headingDeg)
        x += stepM * sin(a)     // east
        y += stepM * cos(a)     // north
        steps++
    }

    fun onRssi(tMs: Long, rssi: Int) {
        pts.addLast(P(tMs, x, y, rssi.toDouble()))
        while (pts.isNotEmpty() && tMs - pts.first().t > windowMs) pts.removeFirst()
    }

    /** Recompute. Call a few times a second. */
    fun update(nowMs: Long): Estimate? {
        val e = estimate(nowMs)
        last = e
        val c = e?.confidence ?: 0.0
        if (!locked && c > lockConf) locked = true
        if (locked && c < unlockConf) locked = false
        return e
    }

    fun reset() {
        pts.clear(); x = 0.0; y = 0.0; steps = 0; locked = false; last = null
    }

    private fun estimate(nowMs: Long): Estimate? {
        val n = pts.size
        if (n < 15) return null

        // How far did we actually walk? Need some spread or the fit is meaningless.
        var mx = 0.0; var my = 0.0
        for (p in pts) { mx += p.x; my += p.y }
        mx /= n; my /= n
        var sxx = 0.0; var syy = 0.0; var sxy = 0.0
        for (p in pts) {
            val dx = p.x - mx; val dy = p.y - my
            sxx += dx * dx; syy += dy * dy; sxy += dx * dy
        }
        sxx /= n; syy /= n; sxy /= n
        val tr = sxx + syy
        val det = sxx * syy - sxy * sxy
        val lamMax = tr / 2 + sqrt(max(tr * tr / 4 - det, 0.0))
        if (2 * sqrt(lamMax) < minSpanM) return null

        // Weighted least squares on centred coordinates.
        var sw = 0.0; var sw2 = 0.0; var cx = 0.0; var cy = 0.0; var cr = 0.0
        val w = DoubleArray(n)
        pts.forEachIndexed { i, p ->
            w[i] = exp(-(nowMs - p.t) / tauMs)
            sw += w[i]; sw2 += w[i] * w[i]
            cx += w[i] * p.x; cy += w[i] * p.y; cr += w[i] * p.r
        }
        cx /= sw; cy /= sw; cr /= sw
        var a11 = 0.0; var a12 = 0.0; var a22 = 0.0; var b1 = 0.0; var b2 = 0.0
        pts.forEachIndexed { i, p ->
            val dx = p.x - cx; val dy = p.y - cy; val dr = p.r - cr
            a11 += w[i] * dx * dx; a12 += w[i] * dx * dy; a22 += w[i] * dy * dy
            b1 += w[i] * dx * dr; b2 += w[i] * dy * dr
        }
        val ridge = 1e-3 * sw          // keeps a straight-line walk from blowing up
        a11 += ridge; a22 += ridge
        val d = a11 * a22 - a12 * a12
        if (d <= 0) return null
        val gx = (a22 * b1 - a12 * b2) / d
        val gy = (a11 * b2 - a12 * b1) / d

        var res2 = 0.0
        pts.forEachIndexed { i, p ->
            val r = p.r - (cr + gx * (p.x - cx) + gy * (p.y - cy))
            res2 += w[i] * r * r
        }
        val sig2 = res2 / sw
        val se = sqrt(sig2 * (a11 + a22) / d * sw2 / sw)
        val g = hypot(gx, gy)
        val conf = g / (g + 2.5 * se + 1e-9)
        val bearing = (Math.toDegrees(atan2(gx, gy)) + 360.0) % 360.0
        return Estimate(bearing, conf, g)
    }
}
