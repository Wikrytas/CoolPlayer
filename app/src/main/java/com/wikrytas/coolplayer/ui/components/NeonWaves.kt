package com.wikrytas.coolplayer.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.sin

@Composable
fun NeonWaves(isPlaying: Boolean, colors: List<Color>, modifier: Modifier = Modifier) {
    var time by remember { mutableStateOf(0f) }
    LaunchedEffect(Unit) {
        var last = 0L
        androidx.compose.runtime.withFrameNanos { last = it }
        while (true) {
            androidx.compose.runtime.withFrameNanos { now ->
                val dt = (now - last) / 1_000_000_000f
                last = now
                time += dt
            }
        }
    }
    val amp by animateFloatAsState(targetValue = if (isPlaying) 1f else 0f, label = "amp")

    Box(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxSize().blur(10.dp)) {
            drawWaves(time, amp, colors, 6.dp.toPx(), 0.7f)
        }
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawWaves(time, amp, colors, 2.2.dp.toPx(), 0.95f)
        }
    }
}

private fun DrawScope.drawWaves(time: Float, amp: Float, colors: List<Color>, strokeWidth: Float, alpha: Float) {
    val mid = size.height / 2
    for (w in colors.indices) {
        val path = Path()
        val steps = 100
        for (i in 0..steps) {
            val px = i.toFloat() / steps * size.width
            val env = sin(i.toFloat() / steps * Math.PI).toFloat()
            val y = mid + (sin(i * 0.09f * (1 + w * 0.15f) + time * (1.2f + w * 0.3f) + w * 2) * 0.5f +
                    sin(i * 0.023f - time * 0.7f + w) * 0.5f) * size.height * 0.32f * amp * env
            if (i == 0) path.moveTo(px, y) else path.lineTo(px, y)
        }
        drawPath(path, color = colors[w].copy(alpha = alpha), style = Stroke(strokeWidth, cap = StrokeCap.Round))
    }
}