package com.wikrytas.coolplayer.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.sin

@Composable
fun AudioVisualizer(isPlaying: Boolean, color: Color, modifier: Modifier = Modifier) {
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

    Canvas(modifier = modifier.fillMaxSize()) {
        drawBars(time, amp, color)
    }
}

private fun DrawScope.drawBars(time: Float, amp: Float, color: Color) {
    val bars = 40
    val barWidth = size.width / bars * 0.6f
    val gap = size.width / bars * 0.4f
    val maxHeight = size.height * 0.9f

    for (i in 0 until bars) {
        val t = i.toFloat() / bars
        val h = (sin(t * Math.PI.toFloat() * 2 + time * 3) * 0.5f + 0.5f) * maxHeight * amp + maxHeight * 0.08f
        val x = i * (barWidth + gap) + gap / 2
        val y = size.height - h

        drawLine(
            color = color,
            start = Offset(x + barWidth / 2, size.height),
            end = Offset(x + barWidth / 2, y),
            strokeWidth = barWidth,
            cap = StrokeCap.Round
        )
    }
}