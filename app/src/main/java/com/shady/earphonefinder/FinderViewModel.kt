package com.shady.earphonefinder

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.shady.earphonefinder.audio.Sounds
import com.shady.earphonefinder.bt.BtScanner
import com.shady.earphonefinder.bt.Sighting
import com.shady.earphonefinder.engine.BearingEstimator
import com.shady.earphonefinder.engine.DirectionSweep
import com.shady.earphonefinder.engine.Proximity
import com.shady.earphonefinder.engine.RssiSmoother
import com.shady.earphonefinder.engine.Trend
import com.shady.earphonefinder.sensors.Compass
import com.shady.earphonefinder.sensors.StepSensor
import com.shady.earphonefinder.ui.glow.HuntState
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class DeviceRow(
    val address: String,
    val name: String?,
    val rssi: Int,
    val lastSeenMs: Long,
    val source: Sighting.Source,
    val paired: Boolean,
)

/** All state lives here and is only touched on the main thread. */
class FinderViewModel(app: Application) : AndroidViewModel(app) {

    private val main = Handler(Looper.getMainLooper())
    private fun now() = SystemClock.elapsedRealtime()

    // ---------- scanning ----------
    private val seen = HashMap<String, DeviceRow>()
    private var bonded: Map<String, String> = emptyMap()

    var devices by mutableStateOf<List<DeviceRow>>(emptyList()); private set
    var hideUnnamed by mutableStateOf(false)
    var classicOn by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null)
    var bluetoothOn by mutableStateOf(true); private set

    private val scanner = BtScanner(
        app,
        onSighting = { s -> main.post { onSighting(s) } },
        onError = { e -> main.post { error = e } },
    )

    // ---------- tracking ----------
    var target by mutableStateOf<DeviceRow?>(null); private set
    var smoothed by mutableStateOf<Double?>(null); private set
    var lastRaw by mutableStateOf<Int?>(null); private set
    var trend by mutableStateOf(Trend.UNKNOWN); private set
    var hotness by mutableStateOf(0f); private set
    var readingsPerSec by mutableStateOf(0f); private set
    var msSinceLast by mutableStateOf<Long?>(null); private set

    private val smoother = RssiSmoother()
    private val recent = ArrayDeque<Long>()

    // ---------- direction ----------
    var heading by mutableStateOf<Double?>(null); private set
    var sweeping by mutableStateOf(false); private set
    var sweepCoverage by mutableStateOf(0f); private set
    var sweepResult by mutableStateOf<DirectionSweep.Result?>(null); private set
    private val sweep = DirectionSweep()
    private val compass = Compass(app) { h -> main.post { heading = h } }
    val compassAvailable get() = compass.available

    // ---------- hunt (walk-to-calibrate) ----------
    private val bearing = BearingEstimator()
    private val stepSensor = StepSensor(app) { main.post { onStep() } }
    var stepsGranted by mutableStateOf(false)
    val stepsAvailable get() = stepSensor.available && stepsGranted
    var huntState by mutableStateOf(HuntState.CALIBRATING); private set
    var feet by mutableStateOf<Double?>(null); private set
    var bearingDeg by mutableStateOf<Double?>(null); private set     // compass bearing to the bud
    var bearingConfidence by mutableStateOf(0.0); private set
    var stepCount by mutableStateOf(0); private set
    var showDetails by mutableStateOf(false)

    /** Where the bud is relative to the top of the phone, degrees clockwise. */
    val relativeDirDeg: Double?
        get() {
            val b = bearingDeg ?: return null
            val h = heading ?: return null
            return ((b - h) % 360 + 540) % 360 - 180
        }

    private fun onStep() {
        val h = heading ?: return
        if (target == null) return
        bearing.onStep(h)
        stepCount = bearing.steps
    }

    // ---------- sound + logging ----------
    private val sounds = Sounds(viewModelScope)
    var ticking by mutableStateOf(false); private set
    var recordingTo by mutableStateOf<String?>(null); private set
    var lastRecording by mutableStateOf<String?>(null); private set
    private var log: BufferedWriter? = null

    init {
        // UI refresh loop: list twice a second, tracking stats four times a second.
        viewModelScope.launch {
            var n = 0
            while (isActive) {
                delay(250)
                refreshTracking()
                if (n++ % 2 == 0) refreshList()
            }
        }
    }

    // ---------- lifecycle (called from MainActivity) ----------

    fun resume() {
        bluetoothOn = scanner.isBluetoothOn
        if (!bluetoothOn) {
            error = "Bluetooth is off. Turn it on and come back."
            return
        }
        error = null
        bonded = scanner.bonded()
        scanner.startBle()
        if (classicOn) scanner.startClassic()
        compass.start()
        if (stepsGranted) stepSensor.start()
    }

    fun pause() {
        scanner.stopAll()
        compass.stop()
        stepSensor.stop()
        sounds.stopAll()
        ticking = false
        stopRecording()
    }

    override fun onCleared() {
        pause()
    }

    fun toggleClassic() {
        classicOn = !classicOn
        if (classicOn) scanner.startClassic() else scanner.stopClassic()
    }

    // ---------- sightings ----------

    private fun onSighting(s: Sighting) {
        val paired = s.address in bonded || (s.name != null && s.name.isNotBlank() && s.name in bonded.values)
        val old = seen[s.address]
        seen[s.address] = DeviceRow(
            address = s.address,
            name = s.name ?: old?.name,
            rssi = s.rssi,
            lastSeenMs = s.tMs,
            source = s.source,
            paired = paired,
        )

        if (s.address != target?.address) return

        val v = smoother.add(s.tMs, s.rssi)
        smoothed = v
        bearing.onRssi(s.tMs, s.rssi)
        lastRaw = s.rssi
        recent.addLast(s.tMs)
        while (recent.isNotEmpty() && s.tMs - recent.first() > 5_000) recent.removeFirst()

        val h = heading
        if (sweeping && h != null) {
            sweep.add(h, s.rssi)
            sweepCoverage = sweep.coverage
            if (sweepCoverage >= 1f) finishSweep()
        }

        log?.let {
            try {
                it.write("${s.tMs},${s.rssi},${"%.2f".format(Locale.US, v)},${h?.let { x -> "%.1f".format(Locale.US, x) } ?: ""},${if (sweeping) 1 else 0}\n")
            } catch (_: Exception) {
            }
        }
    }

    private fun refreshList() {
        val t = now()
        seen.values.removeAll { t - it.lastSeenMs > 30_000 }
        devices = seen.values
            .filter { !hideUnnamed || !it.name.isNullOrBlank() }
            .sortedWith(compareByDescending<DeviceRow> { it.paired }.thenByDescending { it.rssi })
    }

    private fun refreshTracking() {
        if (target == null) return
        val t = now()
        val since = smoother.msSinceLastSample(t)
        msSinceLast = since
        trend = smoother.trend(t)
        readingsPerSec = recent.count { t - it <= 5_000 } / 5f
        val v = smoothed
        val stale = since == null || since > 5_000
        hotness = if (v == null || stale) 0f else Proximity.hotness(v)
        sounds.hotness = hotness

        // hunt: distance in feet, direction from walking, and which state we're in
        if (v != null) feet = Proximity.metres(v) * 3.281
        val est = bearing.update(t)
        bearingConfidence = est?.confidence ?: 0.0
        if (bearing.locked && est != null) bearingDeg = est.bearingDeg
        huntState = when {
            since != null && since > 8_000 -> HuntState.LOST   // some buds advertise only ~1-2x a second
            bearing.locked -> HuntState.FOUND
            else -> HuntState.CALIBRATING
        }
    }

    // ---------- tracking controls ----------

    fun track(row: DeviceRow) {
        target = row
        smoother.reset()
        recent.clear()
        smoothed = null; lastRaw = null; trend = Trend.UNKNOWN; hotness = 0f
        sweepResult = null; sweeping = false
        bearing.reset(); bearingDeg = null; bearingConfidence = 0.0; stepCount = 0
        feet = null; huntState = HuntState.CALIBRATING; showDetails = false
    }

    fun stopTracking() {
        stopRecording()
        sounds.stopAll()
        ticking = false
        sweeping = false
        showDetails = false
        target = null
    }

    fun toggleTick() {
        if (sounds.ticking) sounds.stopTicking() else sounds.startTicking()
        ticking = sounds.ticking
    }

    fun ring(side: Sounds.Side) = sounds.ring(side)
    fun stopRing() = sounds.stopRing()

    // ---------- direction ----------

    fun startSweep() {
        sweep.reset()
        sweepCoverage = 0f
        sweepResult = null
        sweeping = true
    }

    fun finishSweep() {
        sweeping = false
        sweepResult = sweep.result()
        if (sweepResult == null) error = "Not enough of the circle covered. Turn a full circle, slowly."
    }

    fun cancelSweep() {
        sweeping = false
    }

    // ---------- recording ----------

    fun toggleRecording() {
        if (log != null) { stopRecording(); return }
        val t = target ?: return
        val dir = File(getApplication<Application>().getExternalFilesDir(null), "logs").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val safeName = (t.name ?: "unnamed").replace(Regex("[^A-Za-z0-9]+"), "_")
        val f = File(dir, "track-$stamp-$safeName.csv")
        log = f.bufferedWriter().apply { write("t_ms,rssi,smoothed,heading,sweeping\n") }
        recordingTo = f.absolutePath
    }

    private fun stopRecording() {
        try { log?.close() } catch (_: Exception) {}
        recordingTo?.let { lastRecording = it }
        log = null
        recordingTo = null
    }
}
