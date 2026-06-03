package com.rgblight.controller.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * HSV color wheel: angle = hue, radius = saturation.
 * Tap or drag to pick a color. Center circle shows current selection.
 */
@Composable
fun HsvColorWheel(
    hue: Float,          // 0..360
    saturation: Float,   // 0..1
    onColorPicked: (hue: Float, saturation: Float) -> Unit,
    modifier: Modifier = Modifier,
    diameter: Float = 260f  // dp
) {
    val diameterPx = with(androidx.compose.ui.platform.LocalDensity.current) {
        diameter.dp.toPx()
    }
    val radius = diameterPx / 2f

    // Build a radial sweep gradient from hue 0° to 360°
    val colorSweep = remember {
        Brush.sweepGradient(
            (0..36).map { i ->
                val h = i * 10f
                Color.hsv(h, 1f, 1f)
            }
        )
    }

    Box(contentAlignment = Alignment.Center, modifier = modifier.size(diameter.dp)) {
        Canvas(
            modifier = Modifier
                .size(diameter.dp)
                .pointerInput(Unit) {
                    detectTapGestures { offset -> pickColor(offset, radius, onColorPicked) }
                }
                .pointerInput(Unit) {
                    detectDragGestures { change, _ ->
                        change.consume()
                        pickColor(change.position, radius, onColorPicked)
                    }
                }
        ) {
            // Outer ring: white background for saturation blending
            drawCircle(color = Color.White)

            // Hue sweep on top, with radial alpha for saturation
            drawCircle(brush = colorSweep)

            // Saturate from center (white) to edge (full color)
            // Achieved by drawing radial gradient in "overlay" mode via alpha circles:
            // Center → transparent (shows white base = low saturation)
            // Edge → opaque (shows hue at full saturation)
            // Actually: draw white circle with alphaRadial from center outward.
            // A simpler approach: for each pixel, sat = dist/radius.
            // But Compose Canvas sweep works pixel-exact — we draw the hue ring
            // and mask with a radial blend: center white (0 sat) → edge transparent.

            drawCircle(
                brush = Brush.radialGradient(
                    0f to Color.White.copy(alpha = 1f),
                    1f to Color.White.copy(alpha = 0f),
                    radius = radius
                )
            )

            // Hue indicator ring (small circle at picked position)
            val angleRad = Math.toRadians(hue.toDouble()).toFloat()
            val dist = saturation * radius * 0.98f
            val cx = radius + dist * cos(angleRad)
            val cy = radius + dist * sin(angleRad)

            drawCircle(
                color = Color.White,
                radius = 8f,
                center = Offset(cx, cy),
                style = Stroke(width = 2.5f)
            )
            drawCircle(
                color = Color.Black.copy(alpha = 0.4f),
                radius = 8f,
                center = Offset(cx, cy),
                style = Stroke(width = 1f)
            )

            // Center color preview (current) — drawn smaller inside
            val currentColor = Color.hsv(hue, saturation, 1f)
            drawCircle(
                color = currentColor,
                radius = radius * 0.26f
            )
            drawCircle(
                color = Color.White.copy(alpha = 0.25f),
                radius = radius * 0.26f,
                style = Stroke(width = 1.5f)
            )
        }
    }
}

private fun pickColor(offset: Offset, radius: Float, onPicked: (Float, Float) -> Unit) {
    val dx = offset.x - radius
    val dy = offset.y - radius
    val dist = sqrt(dx * dx + dy * dy).coerceAtMost(radius)
    val sat = (dist / radius).coerceIn(0f, 1f)
    val angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
    val hue = ((angle + 360f) % 360f)
    onPicked(hue, sat)
}
