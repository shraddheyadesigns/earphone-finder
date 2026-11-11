package com.shady.earphonefinder.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
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

// Engine-first screens: plain on purpose. Real UI comes after the engine is proven.

@Composable
fun PermissionScreen(oldAndroid: Boolean, onRequest: () -> Unit) {
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Earphone Finder", fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(12.dp))
        Text("To find your lost earbud, the app scans for nearby Bluetooth devices and counts your steps to work out direction. Nothing leaves your phone.")
        if (oldAndroid) {
            Spacer(Modifier.height(8.dp))
            Text("On this Android version, scanning also needs location permission and location turned on.")
        }
        Spacer(Modifier.height(20.dp))
        Button(onClick = onRequest) { Text("Allow") }
    }
}

@Composable
fun DeviceListScreen(vm: FinderViewModel) {
    val now = android.os.SystemClock.elapsedRealtime()
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(12.dp))
        Text("Which one is your earbud?", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "Paired devices are on top. Not there? Take the other bud out of your ear, open the case, " +
                "or try classic scan.",
            style = MaterialTheme.typography.bodySmall,
        )
        vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        ToggleRow("Hide devices with no name", vm.hideUnnamed) { vm.hideUnnamed = it }
        ToggleRow("Classic scan too (slow, for older buds)", vm.classicOn) { vm.toggleClassic() }

        LazyColumn(Modifier.fillMaxSize()) {
            items(vm.devices, key = { it.address }) { d -> DeviceItem(d, now) { vm.track(d) } }
        }
    }
}

@Composable
private fun DeviceItem(d: DeviceRow, now: Long, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                (d.name?.takeIf { it.isNotBlank() } ?: "No name") + if (d.paired) "  ·  paired" else "",
                fontWeight = if (d.paired) FontWeight.SemiBold else FontWeight.Normal,
            )
            val age = ((now - d.lastSeenMs) / 1000).coerceAtLeast(0)
            Text(
                "${d.address}  ·  ${d.source.name.lowercase()}  ·  ${age}s ago",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text("${d.rssi}", fontSize = 18.sp)
    }
}

@Composable
fun TrackScreen(vm: FinderViewModel) {
    val t = vm.target ?: return
    BackHandler { vm.showDetails = false }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp).verticalScroll(rememberScrollState()),
    ) {
        TextButton(onClick = { vm.showDetails = false }) { Text("← Back to the hunt") }
        Text(
            "Hunt: ${vm.huntState.name.lowercase()}  ·  steps ${vm.stepCount}  ·  " +
                "direction confidence ${"%.2f".format(vm.bearingConfidence)}" +
                (vm.relativeDirDeg?.let { "  ·  bud ${"%.0f".format(it)}° from top" } ?: ""),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(t.name?.takeIf { it.isNotBlank() } ?: "No name", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Text(t.address, style = MaterialTheme.typography.bodySmall)

        // ----- the number -----
        Spacer(Modifier.height(20.dp))
        val v = vm.smoothed
        Text(
            if (v == null) "Listening…" else "${v.roundToInt()} dBm",
            fontSize = 56.sp, fontWeight = FontWeight.Bold,
        )
        if (v != null) {
            Text("${Proximity.label(v)}, roughly ${"%.1f".format(Proximity.metres(v))} m")
        }
        Text(
            when (vm.trend) {
                Trend.WARMER -> "Warmer ↑"
                Trend.COLDER -> "Colder ↓"
                Trend.STEADY -> "Steady"
                Trend.UNKNOWN -> " "
            },
            fontSize = 22.sp,
        )
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(progress = { vm.hotness }, modifier = Modifier.fillMaxWidth().height(10.dp))
        Spacer(Modifier.height(6.dp))
        val since = vm.msSinceLast
        Text(
            "raw ${vm.lastRaw ?: "–"}  ·  ${"%.1f".format(vm.readingsPerSec)} readings/s  ·  " +
                "last ${since?.let { "%.1f".format(it / 1000f) + " s" } ?: "never"} ago",
            style = MaterialTheme.typography.bodySmall,
        )
        if (since == null || since > 5_000) {
            Text(
                "No signal right now. The bud may be asleep, in its closed case, out of battery, or still " +
                    "connected to your phone (then it stops advertising). Try Ring below.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        // ----- sound + logging -----
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.toggleTick() }) { Text(if (vm.ticking) "Stop ticking" else "Hot/cold ticks") }
            OutlinedButton(onClick = { vm.toggleRecording() }) { Text(if (vm.recordingTo != null) "Stop recording" else "Record log") }
        }
        (vm.recordingTo ?: vm.lastRecording)?.let {
            Text(
                (if (vm.recordingTo != null) "Recording to " else "Saved ") + it,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        // ----- direction -----
        Spacer(Modifier.height(24.dp))
        Text("Direction", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        if (!vm.compassAvailable) {
            Text("This phone has no rotation sensor, so direction finding is off.")
        } else {
            DirectionBlock(vm)
        }

        // ----- ring -----
        Spacer(Modifier.height(24.dp))
        Text("Still connected to your phone?", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "Make only the lost side beep loudly. Take the other bud out first so you can hear it.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.ring(Sounds.Side.LEFT) }) { Text("Ring left") }
            OutlinedButton(onClick = { vm.ring(Sounds.Side.RIGHT) }) { Text("Ring right") }
            TextButton(onClick = { vm.stopRing() }) { Text("Stop") }
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun DirectionBlock(vm: FinderViewModel) {
    val result = vm.sweepResult
    val heading = vm.heading
    when {
        vm.sweeping -> {
            Text("Hold the phone in front of your chest and turn slowly in a full circle, about 20 seconds.")
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(progress = { vm.sweepCoverage }, modifier = Modifier.fillMaxWidth())
            Text("${(vm.sweepCoverage * 100).roundToInt()}% of the circle", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.finishSweep() }, enabled = vm.sweepCoverage >= 0.75f) { Text("Done") }
                TextButton(onClick = { vm.cancelSweep() }) { Text("Cancel") }
            }
        }
        result != null && heading != null -> {
            val turn = DirectionSweep.turn(heading, result.headingDeg)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("↑", fontSize = 72.sp, modifier = Modifier.rotate(turn.toFloat()))
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(
                        when {
                            abs(turn) < 15 -> "Straight ahead"
                            turn > 0 -> "Turn right ${turn.roundToInt()}°"
                            else -> "Turn left ${(-turn).roundToInt()}°"
                        },
                        fontSize = 20.sp,
                    )
                    Text(
                        "Confidence: ${result.confidence} (${"%.0f".format(result.spreadDb)} dB difference)",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            TextButton(onClick = { vm.startSweep() }) { Text("Sweep again") }
        }
        else -> {
            Text(
                "Your body blocks Bluetooth. Turn in a circle and the app finds the way you were facing " +
                    "when the signal was strongest.",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = { vm.startSweep() }) { Text("Find direction") }
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
