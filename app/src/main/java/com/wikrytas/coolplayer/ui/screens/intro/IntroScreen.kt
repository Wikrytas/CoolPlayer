package com.wikrytas.coolplayer.ui.screens.intro

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun IntroScreen(onDone: () -> Unit) {
    val cyan = Color(0xFF38E8FF)
    val purple = Color(0xFFB06BFF)
    val tileScale = remember { Animatable(0.4f) }
    val tileAlpha = remember { Animatable(0f) }
    val waveProgress = remember { Animatable(0f) }
    val playScale = remember { Animatable(0.5f) }
    val playAlpha = remember { Animatable(0f) }
    val barProgress = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        launch { tileScale.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessLow)) }
        launch { tileAlpha.animateTo(1f, tween(400)) }
        launch {
            delay(300)
            waveProgress.animateTo(1f, tween(1200, easing = EaseOut))
        }
        launch {
            delay(1000)
            playScale.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMediumLow))
        }
        launch {
            delay(1000)
            playAlpha.animateTo(1f, tween(300))
        }
        launch {
            delay(200)
            barProgress.animateTo(1f, tween(2200, easing = EaseInOut))
            onDone()
        }
    }

    val infinite = rememberInfiniteTransition(label = "shine")
    val shine by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(3000, easing = LinearEasing)),
        label = "s"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            // Радиальный градиент: края совпадают с Bg1 (#05070F) → бесшовно
            .background(Brush.radialGradient(listOf(Color(0xFF0B1024), Color(0xFF05070F)))),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Box(
                modifier = Modifier
                    .size(190.dp)
                    .graphicsLayer {
                        scaleX = tileScale.value
                        scaleY = tileScale.value
                        alpha = tileAlpha.value
                    }
                    .clip(RoundedCornerShape(26.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFF101A33), Color(0xFF060A16))))
                    .drawBehind {
                        drawRect(Brush.linearGradient(listOf(Color(0x24FFFFFF), Color.Transparent)), size = size)
                    },
                contentAlignment = Alignment.Center
            ) {
                LogoMark(
                    modifier = Modifier.fillMaxSize(0.76f),
                    waveProgress = waveProgress.value,
                    playScale = playScale.value,
                    playAlpha = playAlpha.value,
                    cyan = cyan,
                    purple = purple
                )
            }
            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.ExtraBold)) { append("COOL") }
                    withStyle(SpanStyle(fontWeight = FontWeight.Light)) { append("PLAYER") }
                },
                fontSize = 40.sp,
                letterSpacing = 3.sp,
                color = Color.White,
                style = TextStyle(
                    brush = Brush.linearGradient(
                        colors = listOf(cyan, purple, cyan),
                        start = Offset(shine * 600f - 200f, 0f),
                        end = Offset(shine * 600f + 200f, 0f)
                    )
                )
            )
            Text(
                "СЛУШАЙ СТИЛЬНО",
                color = Color(0xFF8FA3C8),
                fontSize = 12.sp,
                letterSpacing = 6.sp
            )
            Box(
                Modifier
                    .width(260.dp)
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color(0x14FFFFFF))
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(barProgress.value)
                        .height(3.dp)
                        .background(Brush.horizontalGradient(listOf(cyan, purple)))
                )
            }
        }
    }
}

@Composable
private fun LogoMark(
    modifier: Modifier = Modifier,
    waveProgress: Float,
    playScale: Float,
    playAlpha: Float,
    cyan: Color,
    purple: Color
) {
    val gradient = Brush.linearGradient(listOf(cyan, purple))
    val wave1 = remember {
        Path().apply {
            moveTo(10f, 100f)
            quadraticTo(22f, 100f, 28f, 78f)
            quadraticTo(34f, 56f, 40f, 100f)
            quadraticTo(46f, 144f, 52f, 40f)
            quadraticTo(58f, -64f, 64f, 100f)
            quadraticTo(70f, 264f, 76f, 128f)
            quadraticTo(82f, -8f, 88f, 100f)
        }
    }
    val wave2 = remember {
        Path().apply {
            moveTo(112f, 100f)
            quadraticTo(124f, 100f, 130f, 66f)
            quadraticTo(136f, 32f, 142f, 100f)
            quadraticTo(148f, 168f, 154f, 122f)
            quadraticTo(160f, 76f, 166f, 100f)
            quadraticTo(172f, 124f, 178f, 88f)
            quadraticTo(184f, 52f, 190f, 100f)
        }
    }
    val playPath = remember {
        Path().apply {
            moveTo(84f, 62f)
            lineTo(138f, 100f)
            lineTo(84f, 138f)
            close()
        }
    }
    Canvas(modifier) {
        val phase = (1f - waveProgress) * 400f
        val dash = PathEffect.dashPathEffect(floatArrayOf(400f, 400f), phase)
        drawPath(
            path = wave1,
            brush = gradient,
            style = Stroke(width = 7f, cap = StrokeCap.Round, pathEffect = dash)
        )
        drawPath(
            path = wave2,
            brush = gradient,
            style = Stroke(width = 7f, cap = StrokeCap.Round, pathEffect = dash)
        )
        withTransform({
            scale(playScale, playScale, pivot = center)
        }) {
            drawPath(
                path = playPath,
                brush = gradient,
                style = Stroke(width = 5f, join = StrokeJoin.Round),
                alpha = playAlpha
            )
            drawPath(playPath, color = Color(0x14FFFFFF), alpha = playAlpha)
        }
    }
}