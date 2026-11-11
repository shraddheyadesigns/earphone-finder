package com.shady.earphonefinder.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
    BackHandler { vm.stopTracking() }
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
                    spreadHalfRad = anim.spreadHalfRad,
                )
            }
        }
    }

    val f = frame.value
    val asking = vm.huntState != HuntState.FOUND
    // up close the background gets bright: add a soft shadow and lift the subtext a little
    val nearK = ((f.closeness * f.warm - 0.6f) / 0.35f).coerceIn(0f, 1f)
    val scrim = nearK * nearK * (3 - 2 * nearK)
    val subAlpha = 0.2f + 0.18f * scrim

    val (num, unit) = GlowAnimator.formatFeet(anim.feet)
    val hint = when {
        vm.huntState == HuntState.CALIBRATING -> if (vm.stepsAvailable) "Walk around a bit" else "Allow motion access for direction"
        vm.huntState == HuntState.LOST -> "Lost it, keep moving"
        anim.feet < 2 -> "Right here, look down"
        vm.trend == Trend.WARMER -> "Warmer"
        vm.trend == Trend.COLDER -> "Colder"
        else -> "Steady"
    }
    // Walls weaken the signal too, so a big number can mean "close, behind something".
    val wallNote = when {
        vm.huntState == HuntState.LOST -> null
        anim.feet > 20 -> "or in the next room"
        anim.feet > 10 -> "or in the same room"
        else -> null
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF050506))) {
        GlowLayer(frame, tuning)

        // soft shadow behind the text, only when it's hot
        Canvas(Modifier.fillMaxSize().alpha(scrim)) {
            val c = Offset(size.width / 2, size.height * 0.52f)
            drawRect(Brush.radialGradient(listOf(Color(0x99280A04), Color.Transparent), c, size.width * 0.62f))
        }

        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.Bottom) {
                val mono = TextStyle(fontFamily = SpaceMono, fontSize = 22.sp, color = Color.White.copy(alpha = subAlpha))
                Text("~", style = mono, modifier = Modifier.alignByBaseline())
                Spacer(Modifier.width(4.dp))
                Text(
                    num,
                    modifier = Modifier.alignByBaseline(),
                    style = TextStyle(
                        fontFamily = InterTight, fontWeight = FontWeight.Medium, fontSize = 96.sp,
                        letterSpacing = (-0.025).em,
                        brush = Brush.verticalGradient(listOf(Color.White, Color.White.copy(alpha = 0.55f))),
                    ),
                )
                if (unit.isNotEmpty()) {
                    Spacer(Modifier.width(6.dp))
                    Text(unit, style = mono, modifier = Modifier.alignByBaseline())
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val pmAlpha by animateFloatAsState(if (asking) 1f else 0f, tween(300), label = "pm")
                val pmWidth by animateFloatAsState(if (asking) 20f else 0f, tween(300), label = "pmw")
                Box(Modifier.width(pmWidth.dp).alpha(pmAlpha)) {
                    PixelMan(vm.huntState, pmPhase.floatValue, f.timeSec)
                }
                Text(hint, style = TextStyle(fontFamily = SpaceMono, fontSize = 13.sp, color = Color.White.copy(alpha = subAlpha)))
            }
            if (wallNote != null) {
                Spacer(Modifier.height(4.dp))
                Text(wallNote, style = TextStyle(fontFamily = SpaceMono, fontSize = 12.sp, color = Color.White.copy(alpha = subAlpha * 0.8f)))
            }
        }

        // quiet way out to the numbers
        Text(
            "details",
            style = TextStyle(fontFamily = SpaceMono, fontSize = 12.sp, color = Color.White.copy(alpha = 0.2f)),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .safeDrawingPadding()
                .padding(bottom = 16.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { vm.showDetails = true },
        )
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
