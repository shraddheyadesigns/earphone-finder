package com.shady.earphonefinder.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.SoundPool
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.shady.earphonefinder.R
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * The audio guide for the hunt:
 *  - a Geiger-style "digital chirp" (two quick notes, 1800 then 2400 Hz) that speeds up as you close in
 *  - Michelle's voice lines when the distance zone changes, when calibrating and when the signal is lost
 *
 * Only plays when audio goes to earbuds, headphones or a Bluetooth speaker (not the phone speaker).
 * The chirp sits quietly underneath: peaks at [chirpLevel] of full scale (0.15 = about -16 dB),
 * and goes silent while Michelle is talking.
 */
class GuideAudio(private val context: Context) {

    enum class Line(val res: Int, val ms: Long) {
        CALIBRATE(R.raw.voice_calibrate, 3840),
        LOST(R.raw.voice_lost, 3480),
        OVER_20(R.raw.voice_over20, 4104),
        WITHIN_15(R.raw.voice_within15, 4152),
        WITHIN_10(R.raw.voice_within10, 2928),
        WITHIN_5(R.raw.voice_within5, 4368),
        WITHIN_REACH(R.raw.voice_within_reach, 3768),
    }

    /** 0.15 of full scale is about -16 dB: background, never louder than the voice. */
    @Volatile var chirpLevel = 0.15f

    /** Seconds between chirps; <= 0 means quiet. Set from the hunt every few frames. */
    @Volatile var intervalSec = 0.0

    /** True when audio goes somewhere you'd be wearing or near: earbuds, headphones, BT speaker. */
    @Volatile var outputIsPersonal = false
        private set

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val pool = SoundPool.Builder().setMaxStreams(1).setAudioAttributes(attrs).build()
    private val ids = HashMap<Line, Int>()
    @Volatile private var speakingUntil = 0L
    @Volatile private var running = false
    private var thread: Thread? = null

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = refreshOutput()
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = refreshOutput()
    }

    init {
        Line.entries.forEach { ids[it] = pool.load(context, it.res, 1) }
        refreshOutput()
    }

    fun start() {
        if (running) return
        running = true
        audioManager?.registerAudioDeviceCallback(deviceCallback, main)
        refreshOutput()
        thread = Thread(::chirpLoop, "chirps").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        audioManager?.unregisterAudioDeviceCallback(deviceCallback)
        thread?.join(300)
        thread = null
        pool.autoPause()
    }

    fun release() {
        stop()
        pool.release()
    }

    val isSpeaking: Boolean get() = SystemClock.elapsedRealtime() < speakingUntil

    /** Plays a line in Michelle's voice. Returns false if it didn't play (wrong output, or already talking). */
    fun say(line: Line): Boolean {
        if (!outputIsPersonal || isSpeaking) return false
        val id = ids[line] ?: return false
        pool.play(id, 1f, 1f, 1, 0, 1f)
        speakingUntil = SystemClock.elapsedRealtime() + line.ms + 300
        return true
    }

    private fun refreshOutput() {
        val outs = audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS) ?: emptyArray()
        outputIsPersonal = outs.any { it.type in PERSONAL }
    }

    // ---------- chirp synth: one streaming track, chirps placed sample-accurately ----------

    private fun chirpLoop() {
        val rate = 44_100
        val minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
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
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(max(minBuf, rate / 10 * 2))
            .build()
        track.play()

        val chunk = ShortArray(rate / 50)      // 20 ms
        var sample = 0L                          // running sample clock
        var nextChirp = 0L
        var chirpStart = -1L
        var noteLen = 0                          // samples per note of the current chirp

        try {
            while (running) {
                val gap = intervalSec
                val audible = gap > 0 && outputIsPersonal && !isSpeaking
                for (i in chunk.indices) {
                    var v = 0.0
                    if (audible && sample >= nextChirp) {
                        val noteSec = min(0.03, gap * 0.25)
                        noteLen = (noteSec * rate).toInt().coerceAtLeast(64)
                        chirpStart = sample
                        nextChirp = sample + (gap * rate).toLong()
                    }
                    if (chirpStart >= 0) {
                        val k = (sample - chirpStart).toInt()
                        when {
                            k < noteLen -> v = note(k, noteLen, 1800.0, rate)
                            k < 2 * noteLen -> v = note(k - noteLen, noteLen, 2400.0, rate)
                            else -> chirpStart = -1
                        }
                    }
                    if (!audible && chirpStart < 0) nextChirp = sample   // start promptly when it resumes
                    chunk[i] = (v * chirpLevel * Short.MAX_VALUE).toInt().toShort()
                    sample++
                }
                track.write(chunk, 0, chunk.size)
            }
        } finally {
            track.stop()
            track.release()
        }
    }

    /** One short sine note: 1 ms attack, exponential decay to silence over the note. */
    private fun note(k: Int, len: Int, freq: Double, rate: Int): Double {
        val t = k.toDouble() / rate
        val attack = rate / 1000
        val envAttack = if (k < attack) k.toDouble() / attack else 1.0
        val envDecay = exp(-6.9 * k / len)      // down to ~0.001 at the end of the note
        return sin(2 * PI * freq * t) * envAttack * envDecay
    }

    companion object {
        private val PERSONAL = setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            26,   // TYPE_BLE_HEADSET (Android 12+)
            27,   // TYPE_BLE_SPEAKER (Android 12+)
        )

        /** Same curve as the sound board: ~16 chirps a second at 1 ft, one every ~1.6 s at 30 ft. */
        fun intervalFor(feet: Double): Double {
            val fast = 0.06; val slow = 1.6
            val k = (ln(max(feet, 1.0)) / ln(30.0)).coerceIn(0.0, 1.0)
            return fast * (slow / fast).pow(k)
        }
    }
}
