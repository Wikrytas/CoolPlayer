package com.wikrytas.coolplayer.ui.components.player

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.PI
import kotlin.math.sin

/** Толстые бесшовные волны: на паузе фаза не читается → нет лишних рекомпозиций. */
@Composable
fun AuroraVisualizer(
    isPlaying: Boolean,
    accent: Color,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "aurora")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(5000, easing = LinearEasing), RepeatMode.Restart),
        label = "phase"
    )
    val amp by animateFloatAsState(
        targetValue = if (isPlaying) 1f else 0.15f,
        animationSpec = tween(700),
        label = "amp"
    )

    val twoPi = (2.0 * PI).toFloat()
    val piF = PI.toFloat()

    Canvas(modifier) {
        val ph = if (isPlaying) phase else 0.3f
        val w = size.width
        val h = size.height
        val yMid = h / 2f

        for (i in 0 until 3) {
            val alphaMul = 1f - i * 0.25f
            val speed = (i + 1).toFloat()
            val freq = 1.2f + i * 0.7f
            val amplitude = h * 0.46f * amp * (1f - i * 0.14f)
            val strokeW = 11f - i * 3f

            val path = Path()
            path.moveTo(0f, yMid)
            var x = 0f
            while (x <= w) {
                val t = x / w
                val envelope = sin(t * piF)
                val y = yMid + sin(t * twoPi * freq + ph * twoPi * speed) * amplitude * envelope
                path.lineTo(x, y)
                x += w / 48f
            }
            drawPath(
                path,
                brush = Brush.horizontalGradient(
                    listOf(
                        accent.copy(alpha = 0.12f * alphaMul),
                        accent.copy(alpha = 0.95f * alphaMul),
                        Color.White.copy(alpha = 0.9f * alphaMul),
                        accent.copy(alpha = 0.95f * alphaMul),
                        accent.copy(alpha = 0.12f * alphaMul)
                    )
                ),
                style = Stroke(width = strokeW, cap = StrokeCap.Round)
            )
        }
    }
}