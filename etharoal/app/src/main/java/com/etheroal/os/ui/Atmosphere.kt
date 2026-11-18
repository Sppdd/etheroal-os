package com.etheroal.os.ui

import android.graphics.RuntimeShader
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay

/**
 * The living background: three lights in slow orbit, seen through fluted glass, in half light.
 *
 * Built for a slow phone. The shader is a handful of arithmetic per pixel (no loops, no textures),
 * it is redrawn about 15 times a second rather than 60 because the motion is slow, and it stops
 * completely when Etheroal is not on screen or the user has turned animations off. Phones older
 * than Android 13 cannot run it and get a still gradient of the same colours.
 */
@Composable
fun Atmosphere(modifier: Modifier = Modifier) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) LiveAtmosphere(modifier) else StillAtmosphere(modifier)
}

@Composable
private fun StillAtmosphere(modifier: Modifier) {
    Box(
        modifier
            .background(Halo.Ink)
            .background(Brush.radialGradient(listOf(Color(0x664552D6), Color.Transparent), center = androidx.compose.ui.geometry.Offset(150f, 300f), radius = 900f))
            .background(Brush.radialGradient(listOf(Color(0x40C8623A), Color.Transparent), center = androidx.compose.ui.geometry.Offset(900f, 1300f), radius = 800f)),
    )
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun LiveAtmosphere(modifier: Modifier) {
    val context = LocalContext.current
    val shader = remember { RuntimeShader(SHADER) }
    val brush = remember { ShaderBrush(shader) }
    val time = remember { mutableFloatStateOf(37f) }      // start mid-orbit, not with the lights stacked
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        val animations = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        if (animations == 0f) return@LaunchedEffect       // "Remove animations": stay still
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var last = SystemClock.uptimeMillis()
            while (true) {
                delay(FRAME_MS)
                val now = SystemClock.uptimeMillis()
                time.floatValue += (now - last) / 1000f
                last = now
            }
        }
    }
    Canvas(modifier) {
        shader.setFloatUniform("iRes", size.width, size.height)
        shader.setFloatUniform("iTime", time.floatValue)
        drawRect(brush)
    }
}

private const val FRAME_MS = 66L

private const val SHADER = """
uniform float2 iRes;
uniform float iTime;

float hash(float2 p) {
    return fract(sin(dot(p, float2(12.9898, 78.233))) * 43758.5453);
}

half4 main(float2 fc) {
    float2 uv = fc / iRes;
    float aspect = iRes.x / iRes.y;

    // Fluted glass: each vertical rib bends what is behind it.
    float ribs = 11.0;
    float cell = fract(uv.x * ribs);
    float bend = cell - 0.5;
    float2 p = uv;
    p.x += bend * 0.07;
    p.y += bend * bend * 0.10;
    p.x *= aspect;

    // Three lights in slow orbit.
    float t = iTime * 0.045;
    float2 a = float2(aspect * 0.30, 0.22) + 0.20 * float2(cos(t), sin(t * 1.3));
    float2 b = float2(aspect * 0.75, 0.62) + 0.24 * float2(cos(t * 0.8 + 2.1), sin(t * 0.6 + 1.0));
    float2 c = float2(aspect * 0.40, 0.95) + 0.22 * float2(cos(t * 0.5 + 4.0), sin(t * 0.9 + 3.0));
    float la = exp(-dot(p - a, p - a) * 7.0);
    float lb = exp(-dot(p - b, p - b) * 9.0);
    float lc = exp(-dot(p - c, p - c) * 8.0);

    float3 col = float3(0.022, 0.026, 0.043);
    col += float3(0.30, 0.36, 0.95) * la * 0.60;      // periwinkle
    col += float3(1.00, 0.52, 0.30) * lb * 0.34;      // ember
    col += float3(0.25, 0.85, 0.78) * lc * 0.26;      // tide

    // Rib edges: a dark seam and a thin catch of light.
    col *= 1.0 - 0.22 * smoothstep(0.38, 0.5, abs(bend));
    col += 0.035 * smoothstep(0.10, 0.0, cell) * (la + lb + lc + 0.15);

    // Half light: the lower part of the screen falls into shadow so content stays readable.
    col *= mix(1.0, 0.55, smoothstep(0.25, 1.0, uv.y));

    // Fine static grain hides banding on cheap panels.
    col += (hash(fc) - 0.5) * 0.022;
    return half4(clamp(col, 0.0, 1.0), 1.0);
}
"""
