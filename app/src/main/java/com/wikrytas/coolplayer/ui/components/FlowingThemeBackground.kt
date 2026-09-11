package com.wikrytas.coolplayer.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import kotlin.math.PI
import kotlin.math.sin

/**
 * Живой фон: оттенки темы плавно переливаются.
 * Кисти статичны (remember), альфы читаются ВНУТРИ graphicsLayer —
 * анимация обновляет только слой отрисовки, без рекомпозиции детей.
 */
@Composable
fun FlowingThemeBackground(
    top: Color,
    bottom: Color,
    accent: Color,
    modifier: Modifier = Modifier
) {
    val inf = rememberInfiniteTransition(label = "flow")
    val p = inf.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(16000, easing = LinearEasing), RepeatMode.Restart),
        label = "p"
    )

    val twoPi = (2.0 * PI).toFloat()

    val baseBrush = remember(top, bottom) {
        Brush.verticalGradient(listOf(top, bottom))
    }
    val diagBrush = remember(top, accent, bottom) {
        Brush.linearGradient(
            colors = listOf(accent.copy(alpha = 0.30f), Color.Transparent, bottom.copy(alpha = 0.55f)),
            start = Offset.Zero,
            end = Offset(1000f, 1800f)
        )
    }
    val reverseBrush = remember(bottom, accent, top) {
        Brush.linearGradient(
            colors = listOf(bottom.copy(alpha = 0.50f), Color.Transparent, accent.copy(alpha = 0.22f)),
            start = Offset(1000f, 1800f),
            end = Offset.Zero
        )
    }
    val glowBrush = remember(accent) {
        Brush.radialGradient(
            colors = listOf(accent.copy(alpha = 0.16f), Color.Transparent),
            center = Offset(500f, 300f),
            radius = 1400f
        )
    }

    Box(modifier) {
        Box(Modifier.fillMaxSize().background(baseBrush))
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = (0.5f + 0.5f * sin(p.value * twoPi)) * 0.85f }
                .background(diagBrush)
        )
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = (0.5f + 0.5f * sin(p.value * twoPi + 2.094f)) * 0.85f }
                .background(reverseBrush)
        )
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = (0.5f + 0.5f * sin(p.value * twoPi + 4.188f)) * 0.6f }
                .background(glowBrush)
        )
    }
}