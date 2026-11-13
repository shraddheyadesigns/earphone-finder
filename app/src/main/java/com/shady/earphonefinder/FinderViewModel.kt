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
import com.shady.earphonefinder.audio.GuideAudio
import com.shady.earphonefinder.audio.Sounds
import com.shady.earphonefinder.bt.BtScanner
import com.shady.earphonefinder.bt.Sighting
import com.shady.earphonefinder.engine.BearingEstimator
import com.shady.earphonefinder.engine.DirectionMap
import com.shady.earphonefinder.engine.DirectionSweep
import com.shady.earphonefinder.engine.Proximity
import com.shady.earphonefinder.engine.RssiSmoother
import com.shady.earphonefinder.engine.Trend
import com.shady.earphonefinder.sensors.Compass
import com.shady.earphonefinder.sensors.StepSensor
import com.shady.earphonefinder.ui.glow.HuntState
import kotlin.math.abs
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
    val hint: BudHint = BudHint.NONE,
)

/** For a pair of buds with the same name: which one is probably in your ear, which one is lost. */
enum class BudHint { NONE, WITH_YOU, LOST, UNSURE }

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

    // ---------- spin test as a direction source ----------
    // ---------- shared direction map: walking, turning and spins all feed one estimate ----------
    private val map = DirectionMap()
    private data class SpinSample(val t: Long, val rssi: Int, val heading: Double)
    private val spinBuffer = ArrayList<SpinSample>()
    private var mapFound = false
    var spinNote by mutableStateOf<String?>(null)
    /** Extra arc width when the map is unsure, degrees. */
    var arcExtraDeg by mutableStateOf(90.0); private set
    /** 0..1: the soft yellow patch toward the likely spot, up close. */
    var patch by mutableStateOf(0.0); private set
    private var headingAtLastReading: Double? = null
    private var wasFound = false
    private var lookAroundUntil = 0L
    private var lastBearing: Double? = null
    private var lastBearingAt = 0L
    /** True for a few seconds after the direction lock slips: "Look left and right". */
    var lookAround by mutableStateOf(false); private set

    /** Where the yellow patch points, relative to the top of the phone (last known direction). */
    val patchDirDeg: Double?
        get() {
            val b = lastBearing ?: return null
            val h = heading ?: return null
            return ((b - h) % 360 + 540) % 360 - 180
        }

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
        map.onStep(h)
        stepCount = map.steps
    }

    // ---------- audio guide: chirps + Michelle's voice ----------
    private val guide = GuideAudio(app)
    var soundOn by mutableStateOf(false)   // off until asked: a lost bud is too quiet to guide you anyway
    var guideOutputOk by mutableStateOf(false); private set
    private var zone: GuideAudio.Line? = null          // last zone announced
    private var zoneCandidate: GuideAudio.Line? = null
    private var zoneSince = 0L
    private var lastLineAt = 0L
    private var lastCalibrateAt = 0L
    private var lastLostAt = 0L
    private var prevHunt = HuntState.CALIBRATING

    private fun rawZone(ft: Double) = when {
        ft > 20 -> GuideAudio.Line.OVER_20
        ft > 12 -> GuideAudio.Line.WITHIN_15
        ft > 6 -> GuideAudio.Line.WITHIN_10
        ft > 2.5 -> GuideAudio.Line.WITHIN_5
        else -> GuideAudio.Line.WITHIN_REACH          // matches "Should be within your reach" on screen
    }

    /** Zone with a buffer (30% of the distance, 0.4 to 1.5 ft), so standing on a boundary doesn't flip-flop. */
    private fun zoneFor(ft: Double): GuideAudio.Line {
        val cur = zone ?: return rawZone(ft)
        val cand = rawZone(ft)
        if (cand == cur) return cur
        val closer = cand.ordinal > cur.ordinal
        val margin = (ft * 0.3).coerceIn(0.4, 1.5)
        return if (rawZone(if (closer) ft + margin else ft - margin) == cand) cand else cur
    }

    private fun say(line: GuideAudio.Line, t: Long): Boolean {
        if (!soundOn) return false
        val ok = guide.say(line)
        if (ok) lastLineAt = t
        return ok
    }

    private fun updateGuide(t: Long) {
        guideOutputOk = guide.outputIsPersonal
        val ft = feet
        guide.intervalSec = if (soundOn && huntState != HuntState.LOST && ft != null) GuideAudio.intervalFor(ft) else 0.0
        if (!soundOn) { prevHunt = huntState; return }

        // connection lost: say it on the way in, at most every 15 s
        if (huntState == HuntState.LOST && prevHunt != HuntState.LOST && t - lastLostAt > 15_000) {
            if (say(GuideAudio.Line.LOST, t)) lastLostAt = t
        }
        // calibrating: once when the first reading lands, then every 30 s while still at it
        // only when it helps: cold (over ~10 ft). When warm, the chirps and zones guide you instead.
        if (huntState == HuntState.CALIBRATING && ft != null && ft > 10 && t - lastCalibrateAt > 30_000 && t - lastLineAt > 4_000) {
            if (say(GuideAudio.Line.CALIBRATE, t)) lastCalibrateAt = t
        }
        // distance zone: announce once it has held for 2 s, at most every 5 s
        if (ft != null && huntState != HuntState.LOST) {
            val z = zoneFor(ft)
            if (z != zoneCandidate) { zoneCandidate = z; zoneSince = t }
            if (z != zone && t - zoneSince > 2_000 && t - lastLineAt > 5_000) {
                if (say(z, t)) zone = z
            }
        }
        prevHunt = huntState
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
        if (target != null) guide.start()
    }

    fun pause() {
        scanner.stopAll()
        compass.stop()
        stepSensor.stop()
        guide.stop()
        sounds.stopAll()
        ticking = false
        stopRecording()
    }

    override fun onCleared() {
        pause()
        guide.release()
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
        heading?.let { h ->
            if (sweeping) spinBuffer.add(SpinSample(s.tMs, s.rssi, h))
            else {
                // turning your body is a mini-spin: readings taken mid-turn carry more weight
                val turning = abs(DirectionSweep.turn(headingAtLastReading ?: h, h)) > 25
                map.add(s.tMs, s.rssi, h, if (turning) 1.3 else 1.0)
            }
            headingAtLastReading = h
        }
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

    /** Last time each name showed up as a pair (two rows), so a lone survivor can be called the lost one. */
    private val pairSeenAt = HashMap<String, Long>()
    /** True when your paired buds were around a moment ago and now none are: the lost one may be off. */
    var budsGone by mutableStateOf(false); private set

    private fun refreshList() {
        val t = now()
        // BLE buds advertise about once a second, so drop them quickly once they stop (a closed case);
        // classic results come in slow batches, so they get longer.
        seen.values.removeAll { t - it.lastSeenMs > if (it.source == Sighting.Source.BLE) 10_000 else 30_000 }
        val rows = seen.values.filter { !hideUnnamed || !it.name.isNullOrBlank() }

        val hinted = rows.groupBy { it.name?.takeIf { n -> n.isNotBlank() } }.flatMap { (name, group) ->
            when {
                name == null -> group
                group.size >= 2 -> {
                    pairSeenAt[name] = t
                    val sorted = group.sortedByDescending { it.rssi }
                    val gap = sorted[0].rssi - sorted[1].rssi
                    if (gap >= 8) {
                        listOf(sorted[0].copy(hint = BudHint.WITH_YOU)) +
                            sorted.drop(1).map { it.copy(hint = BudHint.LOST) }
                    } else sorted.map { it.copy(hint = BudHint.UNSURE) }
                }
                // its twin was here in the last two minutes and has gone quiet (back in the case)
                (pairSeenAt[name] ?: 0L) > t - 120_000 -> group.map { it.copy(hint = BudHint.LOST) }
                else -> group
            }
        }
        val bondedNames = bonded.values.filter { it.isNotBlank() }.toSet()
        budsGone = pairSeenAt.any { (n, at) -> n in bondedNames && at > t - 120_000 } &&
            hinted.none { it.paired && it.name != null && it.name in bondedNames }

        devices = hinted.sortedWith(
            compareByDescending<DeviceRow> { it.hint == BudHint.LOST }
                .thenByDescending { it.paired }
                .thenByDescending { it.rssi },
        )
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
        bearing.update(t)   // kept for logging; the map is the direction source now
        val est = map.estimate()
        val conf = est?.confidence ?: 0.0
        bearingConfidence = conf
        // lock with hysteresis, and only with enough evidence behind it
        if (!mapFound && est != null && conf > 0.6 && est.evidence > 6) mapFound = true
        if (mapFound && (est == null || conf < 0.45)) mapFound = false
        if (wasFound && !mapFound) lookAroundUntil = t + 6_000      // lock just slipped: ask for a quick look around
        wasFound = mapFound
        bearingDeg = if (mapFound) est?.bearingDeg else null
        if (est != null && conf > 0.35) { lastBearing = est.bearingDeg; lastBearingAt = t }
        arcExtraDeg = (1.0 - conf).coerceIn(0.0, 1.0) * 90.0
        // yellow patch up close: shows with any recent sense of direction, stronger when sure
        val recent = lastBearing != null && t - lastBearingAt < 60_000
        patch = when {
            mapFound -> (0.6 + 0.4 * ((conf - 0.6) / 0.4)).coerceIn(0.6, 1.0)
            recent -> 0.45 * (1.0 - (t - lastBearingAt) / 60_000.0)
            else -> 0.0
        }
        lookAround = t < lookAroundUntil && huntState != HuntState.LOST
        huntState = when {
            since != null && since > 8_000 -> HuntState.LOST   // some buds advertise only ~1-2x a second
            bearingDeg != null -> HuntState.FOUND
            else -> HuntState.CALIBRATING
        }
        updateGuide(t)
    }

    // ---------- tracking controls ----------

    fun track(row: DeviceRow) {
        target = row
        smoother.reset()
        recent.clear()
        smoothed = null; lastRaw = null; trend = Trend.UNKNOWN; hotness = 0f
        sweepResult = null; sweeping = false
        bearing.reset(); bearingDeg = null; bearingConfidence = 0.0; stepCount = 0
        map.reset(); spinBuffer.clear(); mapFound = false; spinNote = null; patch = 0.0; arcExtraDeg = 90.0
        headingAtLastReading = null; wasFound = false; lookAroundUntil = 0L; lastBearing = null; lookAround = false
        feet = null; huntState = HuntState.CALIBRATING; showDetails = false
        zone = null; zoneCandidate = null; lastLineAt = 0; lastCalibrateAt = 0; lastLostAt = 0
        prevHunt = HuntState.CALIBRATING
        guide.start()
    }

    fun stopTracking() {
        stopRecording()
        sounds.stopAll()
        ticking = false
        sweeping = false
        showDetails = false
        guide.intervalSec = 0.0
        guide.stop()
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
        spinNote = null
        spinBuffer.clear()
        sweep.reset()
        sweepCoverage = 0f
        sweepResult = null
        sweeping = true
    }

    fun finishSweep() {
        sweeping = false
        val r = sweep.result()
        sweepResult = r
        // a good spin is a high-quality burst; a weak or flipped one still counts, just quietly
        val weight = when {
            r == null -> { spinNote = "Not enough of the circle. Try again, slower"; 0.6 }
            r.spreadDb < 4 -> { spinNote = "Didn't catch it. Try again, slower"; 0.6 }
            !r.consistent -> { spinNote = "Reflections confused it. Try again from another spot"; 0.4 }
            else -> { spinNote = null; 1.5 }
        }
        flushSpin(weight)
    }

    fun cancelSweep() {
        sweeping = false
        flushSpin(1.0)
    }

    private fun flushSpin(weight: Double) {
        for (p in spinBuffer) map.add(p.t, p.rssi, p.heading, map.x, map.y, weight)
        spinBuffer.clear()
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
