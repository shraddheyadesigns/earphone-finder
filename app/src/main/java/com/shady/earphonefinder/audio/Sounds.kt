package com.shady.earphonefinder.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin

/**
 * Two sounds:
 *  - Ticker: a short click that speeds up as you get warmer (hot/cold game).
 *  - Ring: a loud chirp on ONE stereo channel. If the lost bud is still connected
 *    to the phone, ringing its side makes it beep from under the sofa.
 *
 * Audio goes wherever Android routes media: the connected buds if any, else the speaker.
 */
class Sounds(private val scope: CoroutineScope) {

    enum class Side { LEFT, RIGHT, BOTH }

    private val rate = 44_100
    private var tickJob: Job? = null
    private var ringJob: Job? = null

    /** Read on every tick, so the speed follows the live signal. 0..1 */
    @Volatile var hotness: Float = 0f

    val ticking get() = tickJob?.isActive == true
    val ringing get() = ringJob?.isActive == true

    fun startTicking() {
        if (ticking) return
        val click = tone(freq = 1_800.0, ms = 35, side = Side.BOTH, volume = 0.6)
        tickJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                play(click)
                // 1.2 s apart when cold, 0.1 s apart when on top of it
                val gap = (1_200 - 1_100 * hotness).toLong()
                delay(gap)
            }
        }
    }

    fun stopTicking() { tickJob?.cancel(); tickJob = null }

    /** Rings for [seconds] on one side. Up-down chirps are easier for ears to locate. */
    fun ring(side: Side, seconds: Int = 10) {
        ringJob?.cancel()
        val chirp = chirp(side)
        ringJob = scope.launch(Dispatchers.Default) {
            val end = System.currentTimeMillis() + seconds * 1_000L
            while (isActive && System.currentTimeMillis() < end) {
                play(chirp)
                delay(150)
            }
        }
    }

    fun stopRing() { ringJob?.cancel(); ringJob = null }

    fun stopAll() { stopTicking(); stopRing() }

    // ---------- synthesis ----------

    private fun tone(freq: Double, ms: Int, side: Side, volume: Double): ShortArray {
        val n = rate * ms / 1000
        val out = ShortArray(n * 2)
        for (i in 0 until n) {
            val env = fade(i, n)
            val s = (sin(2 * PI * freq * i / rate) * env * volume * Short.MAX_VALUE).toInt().toShort()
            put(out, i, s, side)
        }
        return out
    }

    private fun chirp(side: Side): ShortArray {
        val n = rate * 600 / 1000
        val out = ShortArray(n * 2)
        var phase = 0.0
        for (i in 0 until n) {
            val p = i.toDouble() / n
            val f = if (p < 0.5) 2_000 + 4_000 * p * 2 else 6_000 - 4_000 * (p - 0.5) * 2
            phase += 2 * PI * f / rate
            val s = (sin(phase) * fade(i, n) * 0.9 * Short.MAX_VALUE).toInt().toShort()
            put(out, i, s, side)
        }
        return out
    }

    private fun fade(i: Int, n: Int): Double {
        val edge = (n / 10).coerceAtLeast(1)
        return when {
            i < edge -> i.toDouble() / edge
            i > n - edge -> (n - i).toDouble() / edge
            else -> 1.0
        }
    }

    private fun put(out: ShortArray, i: Int, s: Short, side: Side) {
        val silent: Short = 0
        out[2 * i] = if (side != Side.RIGHT) s else silent
        out[2 * i + 1] = if (side != Side.LEFT) s else silent
    }

    private fun play(pcm: ShortArray) {
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
            .build()
        try {
            track.write(pcm, 0, pcm.size)
            track.play()
            Thread.sleep((pcm.size / 2) * 1000L / rate + 20)
        } finally {
            track.release()
        }
    }
}
