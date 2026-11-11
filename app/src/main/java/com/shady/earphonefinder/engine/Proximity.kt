package com.shady.earphonefinder.engine

import kotlin.math.pow

/**
 * Rough meaning of a smoothed signal strength. These cut-offs are first guesses for
 * typical earbuds; tune them with your own logs (tools/analyze_log.py).
 */
object Proximity {
    const val HOT_DBM = -45.0
    const val COLD_DBM = -95.0

    fun label(dbm: Double): String = when {
        dbm > -55 -> "Very close, within a metre"
        dbm > -67 -> "Close, a couple of metres"
        dbm > -78 -> "Same room, keep looking"
        else -> "Far, or blocked by something"
    }

    /** 0 = ice cold, 1 = on top of it. Drives the bar and the tick speed. */
    fun hotness(dbm: Double): Float =
        ((dbm - COLD_DBM) / (HOT_DBM - COLD_DBM)).coerceIn(0.0, 1.0).toFloat()

    /**
     * Very rough distance. [txAt1m] is the signal at 1 m; [n] is how fast it fades
     * indoors (2 = open air, 3 = cluttered room). Treat as "about", never exact.
     */
    fun metres(dbm: Double, txAt1m: Double = -59.0, n: Double = 2.5): Double =
        10.0.pow((txAt1m - dbm) / (10.0 * n))
}
