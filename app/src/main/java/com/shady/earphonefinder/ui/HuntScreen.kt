package com.shady.earphonefinder.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.shady.earphonefinder.FinderViewModel
import com.shady.earphonefinder.engine.Trend
import com.shady.earphonefinder.ui.glow.GlowAnimator
import com.shady.earphonefinder.ui.glow.GlowFrame
import com.shady.earphonefinder.ui.glow.GlowLayer
import com.shady.earphonefinder.ui.glow.GlowTuning
import com.shady.earphonefinder.ui.glow.HuntState

private val InterTight = Look.InterTight
private val SpaceMono = Look.SpaceMono

/** The real hunt screen: edge glow, distance, quiet subtext, Pixel Man when we need you to move. */
@Composable
fun HuntScreen(vm: FinderViewModel) {
    BackHandler { if (vm.showDetails) vm.showDetails = false else vm.stopTracking() }
    val tuning = remember { GlowTuning() }
    val anim = remember { GlowAnimator(tuning) }
    val frame = remember { mutableStateOf(GlowFrame(0f, 0f, 0f, 0f, 0f, 1f)) }
    val pmPhase = remember { mutableFloatStateOf(0f) }

    // one clock drives everything
    LaunchedEffect(Unit) {
        var last = 0L
        val start = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0.016 else ((now - last) / 1e9).coerceIn(0.0, 0.05)
                last = now
                anim.step(dt, vm.huntState, vm.feet, vm.relativeDirDeg)
                pmPhase.floatValue += (dt * 6).toFloat()
                frame.value = GlowFrame(
                    timeSec = ((now - start) / 1e9).toFloat(),
                    dirRad = anim.dir.toFloat(),
                    closeness = anim.closeness.toFloat(),
                    warm = anim.warm.toFloat(),
                    found = anim.found.toFloat(),
                    even = anim.even.toFloat(),
                    spreadHalfRad = anim.spreadHalfRad,
                )
            }
        }
    }

    val f = frame.value
    // only ask you to move when it helps: cold and no direction yet, or lost. When you're warm, just guide.
    val farOrUnknown = vm.feet?.let { it > 10 } ?: true
    val asking = vm.sweeping || vm.huntState == HuntState.LOST || (vm.huntState == HuntState.CALIBRATING && farOrUnknown)
    // up close the background gets bright: add a soft shadow and lift the subtext a little
    val nearK = ((f.closeness * f.warm - 0.6f) / 0.35f).coerceIn(0f, 1f)
    val scrim = nearK * nearK * (3 - 2 * nearK)
    val subAlpha = 0.2f + 0.18f * scrim

    val lost = vm.huntState == HuntState.LOST
    // lost: don't show a stale distance as if it were live
    val (num, unit) = if (lost) "lost" to "" else GlowAnimator.formatFeet(anim.feet)
    val hint = when {
        vm.sweeping -> "Turn slowly in a full circle"
        vm.spinNote != null && vm.huntState == HuntState.CALIBRATING -> vm.spinNote!!
        vm.huntState == HuntState.CALIBRATING && farOrUnknown ->
            if (vm.stepsAvailable) "Move around to find the direction" else "Allow motion access for direction"
        vm.huntState == HuntState.LOST -> "Lost it, keep moving"
        anim.feet < 2.5 -> "Should be within your reach"   // same point Michelle says it
        vm.trend == Trend.WARMER -> "Warmer"
        vm.trend == Trend.COLDER -> "Colder"
        else -> "Steady"
    }
    // Walls weaken the signal too, so a big number can mean "close, behind something".
    val wallNote = when {
        lost -> {
            val (n, u) = GlowAnimator.formatFeet(anim.feet)
            val secs = ((vm.msSinceLast ?: 0L) / 1000).coerceAtLeast(0)
            val where = if (u.isEmpty()) "far away" else "~$n $u"
            "last seen $where, ${secs}s ago"
        }
        anim.feet > 20 -> "maybe closer, behind a wall"   // same zones as Michelle's lines,
        anim.feet > 12 -> "maybe in the next room"        // worded to stand on their own
        anim.feet > 6 -> "probably in this room"
        else -> null
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF050506))) {
        GlowLayer(frame, tuning)

        if (vm.showDetails) {
            // details: same live glow underneath, a soft veil so the numbers read
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)))
            DetailsContent(vm)
        } else {
            // soft shadow behind the text, only when it's hot
            Canvas(Modifier.fillMaxSize().alpha(scrim)) {
                val c = Offset(size.width / 2, size.height * 0.52f)
                drawRect(Brush.radialGradient(listOf(Color(0x99280A04), Color.Transparent), c, size.width * 0.62f))
            }

            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.Bottom) {
                    val mono = TextStyle(fontFamily = SpaceMono, fontSize = 22.sp, color = Color.White.copy(alpha = subAlpha))
                    if (!lost) {
                        Text("~", style = mono, modifier = Modifier.alignByBaseline())
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        num,
                        modifier = Modifier.alignByBaseline(),
                        style = TextStyle(
                            fontFamily = InterTight, fontWeight = FontWeight.Medium, fontSize = 96.sp,
                            letterSpacing = (-0.025).em,
                            brush = Brush.verticalGradient(
                                if (lost) listOf(Color.White.copy(alpha = 0.55f), Color.White.copy(alpha = 0.25f))
                                else listOf(Color.White, Color.White.copy(alpha = 0.55f))
                            ),
                        ),
                    )
                    if (unit.isNotEmpty()) {
                        Spacer(Modifier.width(6.dp))
                        Text(unit, style = mono, modifier = Modifier.alignByBaseline())
                    }
                }
                // wall note: a footnote to the number, so it sits tight under it
                Spacer(Modifier.height(2.dp))
                Box(Modifier.height(16.dp), contentAlignment = Alignment.Center) {
                    if (wallNote != null) {
                        Text(wallNote, style = TextStyle(fontFamily = SpaceMono, fontSize = 11.sp, color = Color.White.copy(alpha = subAlpha * 0.75f)))
                    }
                }
                // then a clear gap, then the instruction
                Spacer(Modifier.height(18.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val pmAlpha by animateFloatAsState(if (asking) 1f else 0f, tween(300), label = "pm")
                    val pmWidth by animateFloatAsState(if (asking) 20f else 0f, tween(300), label = "pmw")
                    Box(Modifier.width(pmWidth.dp).alpha(pmAlpha)) {
                        PixelMan(vm.huntState, pmPhase.floatValue, f.timeSec)
                    }
                    Text(hint, style = TextStyle(fontFamily = SpaceMono, fontSize = 13.sp, color = Color.White.copy(alpha = subAlpha)))
                }
                if (vm.sweeping) {
                    Spacer(Modifier.height(12.dp))
                    Box(Modifier.width(140.dp).height(2.dp).background(Color.White.copy(alpha = 0.1f))) {
                        Box(Modifier.fillMaxWidth(vm.sweepCoverage.coerceIn(0f, 1f)).height(2.dp).background(Color.White.copy(alpha = 0.6f)))
                    }
                }
            }

            // spin to find direction: the strongest direction source on most phones
            Row(
                Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(bottom = 64.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                when {
                    !vm.compassAvailable -> {}
                    vm.sweeping -> {
                        if (vm.sweepCoverage >= 0.75f) IconButtonLike("done", null) { vm.finishSweep() }
                        IconButtonLike("cancel", null) { vm.cancelSweep() }
                    }
                    else -> IconButtonLike(
                        if (vm.huntState == HuntState.FOUND) "spin again" else "spin to find direction", Glyph.SPIN,
                    ) { vm.startSweep() }
                }
            }

            // quiet controls at the bottom: sound and details
            Row(
                Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(36.dp),
            ) {
                val quiet = TextStyle(fontFamily = SpaceMono, fontSize = 12.sp, color = Color.White.copy(alpha = 0.2f))
                Text(
                    when {
                        !vm.soundOn -> "sound off"
                        vm.guideOutputOk -> "sound on"
                        else -> "sound: connect earbuds"
                    },
                    style = quiet,
                    modifier = Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() }, indication = null,
                    ) { vm.soundOn = !vm.soundOn },
                )
                Text(
                    "details",
                    style = quiet,
                    modifier = Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() }, indication = null,
                    ) { vm.showDetails = true },
                )
            }
        }
    }
}

// ---------- Pixel Man: a 7 x 9 dot glyph, about the height of the subtext ----------

private val STRIDE = listOf("...##..", "...##..", ".......", "..###..", ".#.##.#", "...##..", "..#..#.", ".#....#", "#......")
private val MID = listOf("...##..", "...##..", ".......", "..###..", "..###..", "...##..", "...#.#.", "..#..#.", "..#....")
private val PASS = listOf("...##..", "...##..", ".......", "...##..", "...##..", "...##..", "...#...", "...#...", "..##...")
private fun dots(rows: List<String>, mirror: Boolean = false) = buildList {
    rows.forEachIndexed { y, r -> r.forEachIndexed { x, ch -> if (ch == '#') add((if (mirror) 6 - x else x) to y) } }
}
private val WALK = listOf(dots(STRIDE), dots(MID), dots(PASS), dots(MID))
private val FACE_R = dots(PASS)
private val FACE_L = dots(PASS, mirror = true)

@Composable
private fun PixelMan(state: HuntState, phase: Float, t: Float) {
    val frame = when (state) {
        HuntState.LOST -> {
            val k = t % 3.6f
            when {
                k < 0.9f -> FACE_R
                k < 1.8f -> FACE_L
                k < 2.6f -> FACE_R
                else -> WALK[(phase.toInt() % 4 + 4) % 4]
            }
        }
        else -> WALK[(phase.toInt() % 4 + 4) % 4]
    }
    Canvas(Modifier.size(width = 14.dp, height = 17.dp)) {
        val pitch = size.height / 9f
        val r = pitch * 0.34f
        for ((x, y) in frame) {
            drawCircle(Color.White.copy(alpha = 0.32f), r, Offset(x * pitch + pitch / 2, y * pitch + pitch / 2))
        }
    }
}
