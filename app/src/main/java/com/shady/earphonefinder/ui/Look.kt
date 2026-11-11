package com.shady.earphonefinder.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.shady.earphonefinder.R
import com.shady.earphonefinder.ui.glow.GlowAnimator
import com.shady.earphonefinder.ui.glow.GlowFrame
import com.shady.earphonefinder.ui.glow.GlowLayer
import com.shady.earphonefinder.ui.glow.GlowTuning
import com.shady.earphonefinder.ui.glow.HuntState

/** One look for every screen: near-black, Inter Tight for names and numbers, Space Mono for small print. */
object Look {
    val Bg = Color(0xFF050506)
    val InterTight = FontFamily(Font(R.font.inter_tight_medium, FontWeight.Medium))
    val SpaceMono = FontFamily(Font(R.font.space_mono_regular, FontWeight.Normal))

    val title = TextStyle(fontFamily = InterTight, fontWeight = FontWeight.Medium, fontSize = 30.sp,
        letterSpacing = (-0.02).em, color = Color.White)
    val big = TextStyle(fontFamily = InterTight, fontWeight = FontWeight.Medium, fontSize = 56.sp,
        letterSpacing = (-0.025).em, color = Color.White)
    val name = TextStyle(fontFamily = InterTight, fontWeight = FontWeight.Medium, fontSize = 18.sp,
        color = Color.White.copy(alpha = 0.92f))
    val body = TextStyle(fontFamily = InterTight, fontWeight = FontWeight.Medium, fontSize = 15.sp,
        lineHeight = 21.sp, color = Color.White.copy(alpha = 0.55f))
    val mono = TextStyle(fontFamily = SpaceMono, fontSize = 12.sp, color = Color.White.copy(alpha = 0.32f))
    val link = TextStyle(fontFamily = SpaceMono, fontSize = 13.sp, color = Color.White.copy(alpha = 0.75f))
}

/** Plain-text button: no pill, no ripple. */
@Composable
fun TextLink(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Text(
        text,
        style = Look.link.copy(color = Look.link.color.copy(alpha = if (enabled) 0.75f else 0.25f)),
        modifier = modifier
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(vertical = 10.dp),
    )
}

/** "label   on" / "label   off" as one tappable line. */
@Composable
fun ToggleLine(label: String, on: Boolean, onClick: () -> Unit) {
    TextLink("$label   ${if (on) "on" else "off"}", onClick = onClick)
}

/** The slate loader glow, slowly circling the edge. Sits behind the non-hunt screens. */
@Composable
fun Backdrop() {
    val tuning = remember { GlowTuning(brightness = 0.7f) }
    val anim = remember { GlowAnimator(tuning) }
    val frame = remember { mutableStateOf(GlowFrame(0f, 0f, 0f, 0f, 0f, 1f)) }
    LaunchedEffect(Unit) {
        var last = 0L
        val start = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0.016 else ((now - last) / 1e9).coerceIn(0.0, 0.05)
                last = now
                anim.step(dt, HuntState.CALIBRATING, null, null)
                frame.value = GlowFrame(((now - start) / 1e9).toFloat(), anim.dir.toFloat(), 0f, 0f, 0f, anim.spreadHalfRad)
            }
        }
    }
    GlowLayer(frame, tuning)
}
