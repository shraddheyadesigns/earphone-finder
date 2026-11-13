package com.shady.earphonefinder.ui

import androidx.compose.foundation.background
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.res.painterResource
import com.shady.earphonefinder.BudHint
import com.shady.earphonefinder.R
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
            Spacer(Modifier.height(32.dp))
            Text("Which one is yours?", style = Look.title)
            Spacer(Modifier.height(20.dp))
            CaseTip()
            if (vm.budsGone) {
                Spacer(Modifier.height(10.dp))
                Text("Your buds just went quiet. The lost one may be off, out of range, or in a closed case.",
                    style = Look.body.copy(color = Color(0xFFFF8A5B)))
            }
            vm.error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = Look.body.copy(color = Color(0xFFFF8A5B)))
            }
            Spacer(Modifier.height(20.dp))
            ToggleLine("hide unnamed", vm.hideUnnamed) { vm.hideUnnamed = !vm.hideUnnamed }
            ToggleLine("classic scan, for older buds", vm.classicOn) { vm.toggleClassic() }
            Spacer(Modifier.height(24.dp))
            Text("nearby  ${vm.devices.size}", style = Look.mono)
            Spacer(Modifier.height(10.dp))
            LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).panel().padding(horizontal = 18.dp, vertical = 6.dp)) {
                items(vm.devices, key = { it.address }) { d -> DeviceItem(d, now) { vm.track(d) } }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun DeviceItem(d: DeviceRow, now: Long, onClick: () -> Unit) {
    val quiet = !d.paired && d.name.isNullOrBlank()
    val isBud = d.hint != BudHint.NONE
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isBud) {
            Image(
                painterResource(R.drawable.ic_bud), contentDescription = null,
                colorFilter = ColorFilter.tint(Color.White.copy(alpha = if (d.hint == BudHint.LOST) 0.9f else 0.4f)),
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                d.name?.takeIf { it.isNotBlank() } ?: "No name",
                style = Look.name.copy(color = Color.White.copy(alpha = if (quiet) 0.45f else 0.92f)),
            )
            when (d.hint) {
                BudHint.LOST -> HintLine("probably the lost one", Color(0xFFFF9A3C))
                BudHint.WITH_YOU -> HintLine("probably with you", Color.White.copy(alpha = 0.45f))
                BudHint.UNSURE -> HintLine("both close, can’t tell yet", Color.White.copy(alpha = 0.45f))
                BudHint.NONE -> {}
            }
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
private fun HintLine(text: String, color: Color) {
    Spacer(Modifier.height(3.dp))
    Text(text, style = Look.body.copy(fontSize = 13.sp, color = color))
}

/** Line drawing: a bud drops into its case, the lid shuts, a small light goes out. Loops gently. */
@Composable
private fun CaseTip() {
    val t by rememberInfiniteTransition(label = "case").animateFloat(
        0f, 1f, infiniteRepeatable(tween(4200, easing = LinearEasing)), label = "caseT",
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(width = 64.dp, height = 52.dp)) {
            val ink = Color.White.copy(alpha = 0.55f)
            val sw = Stroke(width = 1.4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            val w = size.width; val h = size.height
            fun ss(a: Float, b: Float, x: Float): Float { val k = ((x - a) / (b - a)).coerceIn(0f, 1f); return k * k * (3 - 2 * k) }
            val fadeAll = 1f - ss(0.9f, 1f, t)
            val left = w * 0.14f; val right = w * 0.86f; val hinge = h * 0.56f; val bottom = h * 0.94f
            // the bud, dropping in
            val drop = ss(0.08f, 0.34f, t)
            val budY = h * 0.06f + (h * 0.52f - h * 0.06f) * drop
            val budA = ss(0.02f, 0.12f, t) * fadeAll * (1f - 0.6f * ss(0.45f, 0.62f, t))
            if (budA > 0f) {
                val c = Color.White.copy(alpha = 0.6f * budA)
                drawCircle(c, radius = w * 0.075f, center = Offset(w * 0.5f, budY), style = sw)
                drawLine(c, Offset(w * 0.53f, budY + w * 0.07f), Offset(w * 0.56f, budY + w * 0.2f), strokeWidth = 1.4.dp.toPx(), cap = StrokeCap.Round)
            }
            // case body
            drawRoundRect(ink.copy(alpha = 0.55f * fadeAll), topLeft = Offset(left, hinge), size = Size(right - left, bottom - hinge),
                cornerRadius = CornerRadius(h * 0.16f), style = sw)
            // lid: open at the start, swings shut around the back hinge
            val close = ss(0.42f, 0.62f, t)
            val ang = -72f * (1f - close)
            rotate(ang, pivot = Offset(left, hinge)) {
                val lid = Path().apply {
                    moveTo(left, hinge); lineTo(left, h * 0.42f)
                    quadraticBezierTo(left, h * 0.3f, left + h * 0.14f, h * 0.3f)
                    lineTo(right - h * 0.14f, h * 0.3f)
                    quadraticBezierTo(right, h * 0.3f, right, h * 0.42f)
                    lineTo(right, hinge)
                }
                drawPath(lid, ink.copy(alpha = 0.55f * fadeAll), style = sw)
            }
            // the little status light: on while open, goes out once shut
            val light = (1f - ss(0.66f, 0.8f, t)) * ss(0.0f, 0.06f, t) * fadeAll
            drawCircle(Color(0xFFFF9A3C).copy(alpha = 0.9f * light), radius = 1.6.dp.toPx(), center = Offset(w * 0.5f, h * 0.78f))
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text("Got one bud with you?", style = Look.name.copy(fontSize = 15.sp))
            Spacer(Modifier.height(2.dp))
            Text("Put it in the case and close it. The one left in the list is the lost one.", style = Look.body.copy(fontSize = 13.sp))
        }
    }
}

/** One section: heading, a line of explanation, then its actions. Same rhythm everywhere. */
@Composable
private fun Section(title: String, body: String?, content: @Composable () -> Unit = {}) {
    Spacer(Modifier.height(40.dp))
    Text(title, style = Look.name)
    if (body != null) {
        Spacer(Modifier.height(6.dp))
        Text(body, style = Look.body)
    }
    Spacer(Modifier.height(14.dp))
    content()
}

/** Details, drawn over the live hunt glow (see HuntScreen). Extra numbers on top of the same hunt. */
@Composable
fun DetailsContent(vm: FinderViewModel) {
    val t = vm.target ?: return
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp).verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(8.dp))
        TextLink("←  back to the hunt") { vm.showDetails = false }

        // ----- who -----
        Spacer(Modifier.height(20.dp))
        Text(t.name?.takeIf { it.isNotBlank() } ?: "No name", style = Look.title)
        Spacer(Modifier.height(2.dp))
        Text(t.address, style = Look.mono)

        // ----- signal: the one big number on this page -----
        Spacer(Modifier.height(36.dp))
        Text("signal", style = Look.mono)
        Spacer(Modifier.height(4.dp))
        val v = vm.smoothed
        Text(if (v == null) "listening" else "${v.roundToInt()} dBm", style = Look.big)
        Spacer(Modifier.height(6.dp))
        if (v != null) {
            val trend = when (vm.trend) {
                Trend.WARMER -> ", getting warmer"
                Trend.COLDER -> ", getting colder"
                else -> ", steady"
            }
            Text("About ${"%.1f".format(Proximity.metres(v))} m away$trend", style = Look.body)
        }
        Spacer(Modifier.height(14.dp))
        Meter(vm.hotness)
        Spacer(Modifier.height(8.dp))
        val since = vm.msSinceLast
        Text(
            "raw ${vm.lastRaw ?: "–"}   ${"%.1f".format(vm.readingsPerSec)} readings/s   " +
                "last ${since?.let { "%.1f".format(it / 1000f) + "s" } ?: "never"} ago",
            style = Look.mono,
        )
        if (since == null || since > 5_000) {
            Spacer(Modifier.height(10.dp))
            Text(
                "No signal right now. It may be in a closed case, flat, or still connected to your phone. Try ringing it below.",
                style = Look.body.copy(color = Color(0xFFFF8A5B)),
            )
        }

        // ----- direction from walking -----
        Section(
            "Direction",
            "Found by walking with the phone in your hand. Needs a few steps and a turn or two.",
        ) {
            Text(
                "${vm.huntState.name.lowercase()}   steps ${vm.stepCount}   confidence ${"%.2f".format(vm.bearingConfidence)}" +
                    (vm.relativeDirDeg?.let { "   ${"%.0f".format(it)}° from top" } ?: ""),
                style = Look.mono,
            )
        }

        // ----- turn in a circle -----
        Section("Turn in a circle", "Another way to get direction. Your body blocks Bluetooth, so spin once slowly.") {
            if (!vm.compassAvailable) Text("No rotation sensor on this phone.", style = Look.mono)
            else DirectionBlock(vm)
        }

        // ----- ring -----
        Section("Ring the lost bud", "Still connected to your phone? Make only that side beep. Take the other bud out first.") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                IconButtonLike("left", Glyph.EARBUD_LEFT) { vm.ring(Sounds.Side.LEFT) }
                IconButtonLike("right", Glyph.EARBUD_RIGHT) { vm.ring(Sounds.Side.RIGHT) }
                IconButtonLike("stop", Glyph.STOP) { vm.stopRing() }
            }
        }

        // ----- logging -----
        Section("Log this hunt", "Saves the signal and direction to a file, for tuning later.") {
            TextLink(if (vm.recordingTo != null) "stop recording" else "start recording") { vm.toggleRecording() }
            (vm.recordingTo ?: vm.lastRecording)?.let {
                Text((if (vm.recordingTo != null) "recording to " else "saved ") + it, style = Look.mono)
            }
        }
        Spacer(Modifier.height(48.dp))
    }
}

@Composable
private fun DirectionBlock(vm: FinderViewModel) {
    val result = vm.sweepResult
    val heading = vm.heading
    when {
        vm.sweeping -> {
            Text("Hold the phone at your chest and keep turning.", style = Look.body)
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
            Spacer(Modifier.height(10.dp))
            IconButtonLike("spin again", null) { vm.startSweep() }
        }
        else -> IconButtonLike("start the spin", null) { vm.startSweep() }
    }
}
