package com.shady.earphonefinder.ui.glow

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

enum class HuntState { CALIBRATING, FOUND, LOST }

/** Shady's tuned values from the web prototype. */
data class GlowTuning(
    val glowWidth: Float = 0.12f,
    val brightness: Float = 1f,
    val spreadFarDeg: Float = 110f,
    val spreadNearDeg: Float = 360f,
    val softnessDeg: Float = 45f,
    val ambient: Float = 0.35f,
    val spinFocus: Float = 2.5f,
    val spinSpeedDeg: Float = 50f,
    val settleSec: Float = 1f,
    val shimmer: Float = 0.2f,
    val flow: Float = 0.5f,
    val pixelSizeDp: Float = 6f,
    val dotGap: Float = 0.85f,
    val glowMix: Float = 1.5f,
    val twinkle: Float = 1f,
    val dither: Float = 1f,
)

/**
 * Same easing as the prototype's step(): spins while calibrating or lost, settles into the
 * bud's side with a spring when found (carrying on in the spin direction), warms up, and
 * eases distance and closeness so nothing jumps.
 */
class GlowAnimator(private val k: GlowTuning = GlowTuning()) {
    var dir = 0.0; private set           // radians, 0 = top of screen, clockwise
    private var v = 0.0
    private var unwrapped = 0.0
    var warm = 0.0; private set          // 0 = slate, 1 = temperature colours (follows distance, any state but lost)
    var found = 0.0; private set         // 0 = no direction, 1 = arc on the bud's side
    var patch = 0.0; private set         // soft yellow patch toward the likely spot, up close
    private var extraSpread = 90.0
    var patchDir = 0.0; private set     // radians, eased the short way round
    var even = 0.0; private set          // 1 = even glow all round (calibrating), 0 = spinning loader (lost)
    var closeness = 0.0; private set     // 0 = legit far, 1 = ~1 ft
    var feet = 24.0; private set
    var state = HuntState.CALIBRATING; private set

    /** Arc half-width the shader wants, in radians. Widens as you close in. */
    val spreadHalfRad: Float
        get() {
            val c = closeness
            val near = c * c * (3 - 2 * c)
            val deg = k.spreadFarDeg + (k.spreadNearDeg - k.spreadFarDeg) * near + extraSpread   // wider when unsure
            return (min(deg, 359.0) / 2 * PI / 180).toFloat()
        }

    fun step(
        dt: Double, target: HuntState, targetFeet: Double?, targetDirDeg: Double?,
        extraSpreadDeg: Double = 0.0, patchTarget: Double = 0.0, patchDirDeg: Double? = null,
    ) {
        extraSpread = ease(extraSpread, extraSpreadDeg, dt, 0.8)
        if (patchDirDeg != null) patchDir += shortest(Math.toRadians(patchDirDeg) - patchDir) * (1 - exp(-dt / 0.35))
        patch = ease(patch, if (target != HuntState.LOST) patchTarget else 0.0, dt, 0.6)
        val tDir = (targetDirDeg ?: Math.toDegrees(dir)) * PI / 180

        if (target != state) {
            if (target == HuntState.FOUND) {
                // keep spinning the same way into place
                unwrapped = dir + (((tDir - dir) % TAU) + TAU) % TAU
            }
            state = target
        }

        if (state == HuntState.FOUND) {
            unwrapped += shortest(tDir - unwrapped)
            val w = 4.6 / k.settleSec
            val acc = w * w * (unwrapped - dir) - 2 * w * v     // critically damped spring
            v += acc * dt
            dir += v * dt
            found = ease(found, 1.0, dt, k.settleSec * 0.5)
        } else {
            val spin = k.spinSpeedDeg * PI / 180
            v = ease(v, spin, dt, 0.6)
            dir += v * dt
            found = ease(found, 0.0, dt, 0.5)
        }
        even = ease(even, if (state == HuntState.CALIBRATING && targetFeet != null) 1.0 else 0.0, dt, 0.6)
        val colourOn = state != HuntState.LOST && targetFeet != null
        warm = ease(warm, if (colourOn) 1.0 else 0.0, dt, 0.6)
        // keep dir in a sane range without visible jumps
        if (dir > 1000) { dir -= TAU * 100; unwrapped -= TAU * 100 }

        if (targetFeet != null) feet = ease(feet, targetFeet, dt, 0.6)
        closeness = ease(closeness, closenessFromFeet(feet), dt, 0.4)
    }

    companion object {
        private const val TAU = 2 * PI
        fun closenessFromFeet(ft: Double): Double =
            (1 - ln(max(ft, 0.9) / 0.9) / ln(40 / 0.9)).coerceIn(0.0, 1.0)

        private fun shortest(d: Double): Double = ((d % TAU) + TAU * 1.5) % TAU - PI
        private fun ease(x: Double, to: Double, dt: Double, tau: Double) =
            x + (to - x) * (1 - exp(-dt / max(tau, 1e-3)))

        /** "~12", "far", never "0". Whole feet when close, nearest 5 when far. */
        fun formatFeet(ft: Double): Pair<String, String> = when {
            ft > 32 -> "far" to ""
            ft < 10 -> "${max(1, Math.round(ft).toInt())}" to "ft"
            else -> "${(Math.round(ft / 5) * 5).toInt()}" to "ft"
        }
    }
}
