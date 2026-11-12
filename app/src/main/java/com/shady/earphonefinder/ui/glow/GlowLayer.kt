package com.shady.earphonefinder.ui.glow

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** Everything the glow needs for one frame. */
data class GlowFrame(
    val timeSec: Float,
    val dirRad: Float,
    val closeness: Float,
    val warm: Float,
    val found: Float,
    val spreadHalfRad: Float,
    val even: Float = 0f,          // last, so positional callers keep working
    val patch: Float = 0f,
)

@Composable
fun GlowLayer(frame: State<GlowFrame>, tuning: GlowTuning, modifier: Modifier = Modifier) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) ShaderGlow(frame, tuning, modifier)
    else FallbackGlow(frame, modifier)
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun ShaderGlow(frame: State<GlowFrame>, k: GlowTuning, modifier: Modifier) {
    val shader = remember { RuntimeShader(GLOW_AGSL) }
    val brush = remember { ShaderBrush(shader) }
    Canvas(modifier.fillMaxSize()) {
        val f = frame.value                  // reading the state here redraws every frame
        shader.setFloatUniform("uRes", size.width, size.height)
        shader.setFloatUniform("uTime", f.timeSec)
        shader.setFloatUniform("uDir", f.dirRad)
        shader.setFloatUniform("uHot", f.closeness)
        shader.setFloatUniform("uWarm", f.warm)
        shader.setFloatUniform("uFound", f.found)
        shader.setFloatUniform("uEven", f.even)
        shader.setFloatUniform("uPatch", f.patch)
        shader.setFloatUniform("uWidth", k.glowWidth)
        shader.setFloatUniform("uBright", k.brightness)
        shader.setFloatUniform("uShimmer", k.shimmer)
        shader.setFloatUniform("uRadius", 0f)          // shader rounds the field itself
        shader.setFloatUniform("uFlow", k.flow)
        shader.setFloatUniform("uPix", if (k.pixelSizeDp > 0) max(2f, Math.round(k.pixelSizeDp * density).toFloat()) else 0f)
        shader.setFloatUniform("uTwinkle", k.twinkle)
        shader.setFloatUniform("uDither", k.dither)
        shader.setFloatUniform("uGap", k.dotGap)
        shader.setFloatUniform("uGlowMix", k.glowMix)
        shader.setFloatUniform("uSpread", f.spreadHalfRad)
        shader.setFloatUniform("uSoft", (k.softnessDeg * Math.PI / 180).toFloat())
        shader.setFloatUniform("uAmbient", k.ambient)
        shader.setFloatUniform("uSpinFocus", k.spinFocus)
        drawRect(brush)
    }
}

/** Android 12 and older: no runtime shaders, so a soft gradient blob in the right colour and side. */
@Composable
private fun FallbackGlow(frame: State<GlowFrame>, modifier: Modifier) {
    Canvas(modifier.fillMaxSize()) {
        val f = frame.value
        drawRect(Color(0xFF050506))
        val col = lerpColor(Color(0xFF85999F), tempColor(f.closeness), f.warm)
        val cx = size.width / 2 + sin(f.dirRad) * size.width * 0.55f
        val cy = size.height / 2 - cos(f.dirRad) * size.height * 0.55f
        val r = size.minDimension * (0.55f + 0.5f * f.closeness)
        drawRect(Brush.radialGradient(listOf(col.copy(alpha = 0.75f), Color.Transparent), Offset(cx, cy), r))
        drawRect(col.copy(alpha = 0.10f * f.warm * f.closeness))
    }
}

/** Same stops as the shader's temp(). */
fun tempColor(c: Float): Color {
    val stops = listOf(
        0f to Color(0.12f, 0.22f, 0.38f), 0.37f to Color(0.32f, 0.68f, 1f), 0.48f to Color(0.62f, 0.86f, 1f),
        0.60f to Color(1f, 0.56f, 0.26f), 0.80f to Color(1f, 0.36f, 0.07f), 1f to Color(1f, 0.64f, 0.22f),
    )
    for (i in 0 until stops.size - 1) {
        val (a, ca) = stops[i]; val (b, cb) = stops[i + 1]
        if (c <= b) {
            var t = ((c - a) / (b - a)).coerceIn(0f, 1f); t = t * t * (3 - 2 * t)
            return lerpColor(ca, cb, t)
        }
    }
    return stops.last().second
}

private fun lerpColor(a: Color, b: Color, t: Float) =
    Color(a.red + (b.red - a.red) * t, a.green + (b.green - a.green) * t, a.blue + (b.blue - a.blue) * t)
