package com.shady.earphonefinder.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shady.earphonefinder.DeviceRow
import com.shady.earphonefinder.FinderViewModel
import com.shady.earphonefinder.audio.Sounds
import com.shady.earphonefinder.engine.DirectionSweep
import com.shady.earphonefinder.engine.Proximity
import com.shady.earphonefinder.engine.Trend
import kotlin.math.abs
import kotlin.math.roundToInt

/** Near-black page with the slow slate glow behind it, content inside safe padding. */
@Composable
private fun Page(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(Look.Bg)) {
        Backdrop()
        Box(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp)) { content() }
    }
}

/** A thin quiet bar instead of a chunky progress indicator. */
@Composable
private fun Meter(fraction: Float) {
    Box(Modifier.fillMaxWidth().height(2.dp).background(Color.White.copy(alpha = 0.08f))) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().background(Color.White.copy(alpha = 0.6f)))
    }
}

@Composable
fun PermissionScreen(oldAndroid: Boolean, onRequest: () -> Unit) {
    Page {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
            Text("Earphone Finder", style = Look.title)
            Spacer(Modifier.height(12.dp))
            Text(
                "Scans for nearby Bluetooth and counts your steps to work out which way to go. Nothing leaves your phone.",
                style = Look.body,
            )
            if (oldAndroid) {
                Spacer(Modifier.height(8.dp))
                Text("On this Android version, scanning also needs location turned on.", style = Look.body)
            }
            Spacer(Modifier.height(20.dp))
            TextLink("Allow  →", onClick = onRequest)
        }
    }
}

@Composable
fun DeviceListScreen(vm: FinderViewModel) {
    val now = android.os.SystemClock.elapsedRealtime()
    Page {
        Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.height(28.dp))
            Text("Which one is yours?", style = Look.title)
            Spacer(Modifier.height(6.dp))
            Text("Paired on top. Not there? Take the other bud out or open the case.", style = Look.body)
            vm.error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = Look.body.copy(color = Color(0xFFFF8A5B)))
            }
            Spacer(Modifier.height(8.dp))
            ToggleLine("hide unnamed", vm.hideUnnamed) { vm.hideUnnamed = !vm.hideUnnamed }
            ToggleLine("classic scan (slow, older buds)", vm.classicOn) { vm.toggleClassic() }
            Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.fillMaxSize()) {
                items(vm.devices, key = { it.address }) { d -> DeviceItem(d, now) { vm.track(d) } }
            }
        }
    }
}

@Composable
private fun DeviceItem(d: DeviceRow, now: Long, onClick: () -> Unit) {
    val quiet = !d.paired && d.name.isNullOrBlank()
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                d.name?.takeIf { it.isNotBlank() } ?: "No name",
                style = Look.name.copy(color = Color.White.copy(alpha = if (quiet) 0.45f else 0.92f)),
            )
            Spacer(Modifier.height(3.dp))
            val age = ((now - d.lastSeenMs) / 1000).coerceAtLeast(0)
            Text(
                (if (d.paired) "paired  " else "") + "${d.address}  ${age}s",
                style = Look.mono,
            )
        }
        Text("${d.rssi}", style = Look.name.copy(fontSize = 22.sp, color = Color.White.copy(alpha = 0.7f)))
    }
}

@Composable
fun TrackScreen(vm: FinderViewModel) {
    val t = vm.target ?: return
    BackHandler { vm.showDetails = false }

    Page {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(8.dp))
            TextLink("←  back to the hunt") { vm.showDetails = false }
            Spacer(Modifier.height(12.dp))
            Text(t.name?.takeIf { it.isNotBlank() } ?: "No name", style = Look.title)
            Text(t.address, style = Look.mono)

            // ----- signal -----
            Spacer(Modifier.height(28.dp))
            val v = vm.smoothed
            Text(if (v == null) "listening" else "${v.roundToInt()} dBm", style = Look.big)
            if (v != null) {
                Text("${Proximity.label(v)}, about ${"%.1f".format(Proximity.metres(v))} m", style = Look.body)
            }
            Text(
                when (vm.trend) {
                    Trend.WARMER -> "warmer"
                    Trend.COLDER -> "colder"
                    Trend.STEADY -> "steady"
                    Trend.UNKNOWN -> " "
                },
                style = Look.link,
            )
            Spacer(Modifier.height(10.dp))
            Meter(vm.hotness)
            Spacer(Modifier.height(8.dp))
            val since = vm.msSinceLast
            Text(
                "raw ${vm.lastRaw ?: "–"}   ${"%.1f".format(vm.readingsPerSec)}/s   " +
                    "last ${since?.let { "%.1f".format(it / 1000f) + "s" } ?: "never"}",
                style = Look.mono,
            )
            if (since == null || since > 5_000) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "No signal right now. It may be asleep, in a closed case, flat, or still connected to your phone. Try ring below.",
                    style = Look.body.copy(color = Color(0xFFFF8A5B)),
                )
            }

            // ----- hunt engine -----
            Spacer(Modifier.height(28.dp))
            Text("Hunt", style = Look.name)
            Text(
                "${vm.huntState.name.lowercase()}   steps ${vm.stepCount}   confidence ${"%.2f".format(vm.bearingConfidence)}" +
                    (vm.relativeDirDeg?.let { "   bud ${"%.0f".format(it)}° from top" } ?: ""),
                style = Look.mono,
            )

            // ----- sound + logging -----
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                TextLink(if (vm.ticking) "stop ticks" else "hot/cold ticks") { vm.toggleTick() }
                TextLink(if (vm.recordingTo != null) "stop recording" else "record log") { vm.toggleRecording() }
            }
            (vm.recordingTo ?: vm.lastRecording)?.let {
                Text((if (vm.recordingTo != null) "recording to " else "saved ") + it, style = Look.mono)
            }

            // ----- sweep -----
            Spacer(Modifier.height(28.dp))
            Text("Turn-in-a-circle direction", style = Look.name)
            if (!vm.compassAvailable) {
                Text("No rotation sensor on this phone.", style = Look.body)
            } else {
                DirectionBlock(vm)
            }

            // ----- ring -----
            Spacer(Modifier.height(28.dp))
            Text("Still connected?", style = Look.name)
            Text("Make only the lost side beep. Take the other bud out first.", style = Look.body)
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                TextLink("ring left") { vm.ring(Sounds.Side.LEFT) }
                TextLink("ring right") { vm.ring(Sounds.Side.RIGHT) }
                TextLink("stop") { vm.stopRing() }
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun DirectionBlock(vm: FinderViewModel) {
    val result = vm.sweepResult
    val heading = vm.heading
    when {
        vm.sweeping -> {
            Text("Hold the phone at your chest and turn slowly in a full circle.", style = Look.body)
            Spacer(Modifier.height(10.dp))
            Meter(vm.sweepCoverage)
            Spacer(Modifier.height(6.dp))
            Text("${(vm.sweepCoverage * 100).roundToInt()}% of the circle", style = Look.mono)
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                TextLink("done", enabled = vm.sweepCoverage >= 0.75f) { vm.finishSweep() }
                TextLink("cancel") { vm.cancelSweep() }
            }
        }
        result != null && heading != null -> {
            val turn = DirectionSweep.turn(heading, result.headingDeg)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("↑", style = Look.big, modifier = Modifier.rotate(turn.toFloat()))
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(
                        when {
                            abs(turn) < 15 -> "Straight ahead"
                            turn > 0 -> "Turn right ${turn.roundToInt()}°"
                            else -> "Turn left ${(-turn).roundToInt()}°"
                        },
                        style = Look.name,
                    )
                    Text("${result.confidence.lowercase()}, ${"%.0f".format(result.spreadDb)} dB spread", style = Look.mono)
                }
            }
            TextLink("sweep again") { vm.startSweep() }
        }
        else -> {
            Text("Your body blocks Bluetooth. Spin once and it finds the way you faced when it was strongest.", style = Look.body)
            TextLink("start  →") { vm.startSweep() }
        }
    }
}
