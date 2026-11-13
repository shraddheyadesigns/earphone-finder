package com.shady.earphonefinder.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
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
import androidx.compose.ui.graphics.graphicsLayer
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

/** A switch you can read at a glance: a small track with a dot that slides and fills when on. */
@Composable
fun ToggleLine(label: String, on: Boolean, onClick: () -> Unit) {
    val knob by animateFloatAsState(if (on) 1f else 0f, tween(180), label = "knob")
    Row(
        Modifier
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(width = 30.dp, height = 18.dp)) {
            val h = size.height
            drawRoundRect(Color.White.copy(alpha = 0.08f + 0.22f * knob), cornerRadius = CornerRadius(h / 2, h / 2))
            val r = h / 2 - 3.dp.toPx()
            val cx = h / 2 + (size.width - h) * knob
            drawCircle(Color.White.copy(alpha = 0.45f + 0.5f * knob), r, Offset(cx, h / 2))
        }
        Spacer(Modifier.width(12.dp))
        Text(label, style = Look.link.copy(color = Color.White.copy(alpha = if (on) 0.85f else 0.55f)))
    }
}

/** Soft grouping surface: no border, just a faint lift off the background. */
fun Modifier.panel(): Modifier =
    this.clip(RoundedCornerShape(18.dp)).background(Color.White.copy(alpha = 0.04f))

enum class Glyph { EARBUD_LEFT, EARBUD_RIGHT, STOP, SPIN }

/** A real button: soft fill, small drawn icon, mono label. */
@Composable
fun IconButtonLike(label: String, glyph: Glyph?, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.07f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (glyph == Glyph.EARBUD_LEFT || glyph == Glyph.EARBUD_RIGHT) {
            // the supplied earbud icon, mirrored for the right side
            androidx.compose.foundation.Image(
                androidx.compose.ui.res.painterResource(com.shady.earphonefinder.R.drawable.ic_bud),
                contentDescription = null,
                colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Color.White.copy(alpha = 0.85f)),
                modifier = Modifier.size(17.dp).graphicsLayer { scaleX = if (glyph == Glyph.EARBUD_RIGHT) -1f else 1f },
            )
            Spacer(Modifier.width(8.dp))
        } else if (glyph != null) {
            Canvas(Modifier.size(16.dp)) { drawGlyph(glyph) }
            Spacer(Modifier.width(8.dp))
        }
        Text(label, style = Look.link.copy(color = Color.White.copy(alpha = 0.85f)))
    }
}

private fun DrawScope.drawGlyph(g: Glyph) {
    val c = Color.White.copy(alpha = 0.85f)
    val w = size.width; val h = size.height
    if (g == Glyph.SPIN) {
        // a circular arrow
        val sw = 1.7.dp.toPx()
        val r = w * 0.34f
        drawArc(c, startAngle = -60f, sweepAngle = 290f, useCenter = false,
            topLeft = Offset(w / 2 - r, h / 2 - r), size = Size(r * 2, r * 2),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = sw, cap = androidx.compose.ui.graphics.StrokeCap.Round))
        val tip = Offset(w / 2 + r * 0.5f, h / 2 - r * 0.866f)
        val head = Path().apply {
            moveTo(tip.x + w * 0.16f, tip.y - h * 0.02f); lineTo(tip.x - w * 0.02f, tip.y - h * 0.14f); lineTo(tip.x - w * 0.02f, tip.y + h * 0.12f); close()
        }
        drawPath(head, c)
        return
    }
    if (g == Glyph.STOP) {
        drawRoundRect(c, topLeft = Offset(w * 0.22f, h * 0.22f), size = Size(w * 0.56f, h * 0.56f), cornerRadius = CornerRadius(2.dp.toPx()))
        return
    }
    // an earbud: round bud with a short stem, tipped toward its ear; mirrored for the other side
    val flip = g == Glyph.EARBUD_RIGHT
    fun x(v: Float) = if (flip) w - v * w else v * w
    drawCircle(c, radius = w * 0.27f, center = Offset(x(0.40f), h * 0.33f))
    drawCircle(Color.Black.copy(alpha = 0.55f), radius = w * 0.09f, center = Offset(x(0.30f), h * 0.30f))   // speaker tip
    val stem = Path().apply {
        moveTo(x(0.50f), h * 0.42f); lineTo(x(0.66f), h * 0.42f)
        lineTo(x(0.70f), h * 0.92f); lineTo(x(0.56f), h * 0.92f); close()
    }
    drawPath(stem, c)
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
