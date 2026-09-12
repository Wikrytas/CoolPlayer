package com.wikrytas.coolplayer.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wikrytas.coolplayer.ui.theme.NeonColors
import kotlin.math.PI
import kotlin.math.sin

/**
 * Фирменная заглушка обложки в цветах темы:
 * имя сверху с цельной бегущей лентой градиента, пульсирующая кнопка play,
 * дрейфующие неоновые пятна фона, частицы без видимого сброса.
 */
@Composable
fun AnimatedLogo(
    modifier: Modifier = Modifier,
    dim: Boolean = false,
    top: Color = Color(0xFF081426),
    bottom: Color = Color(0xFF050B18),
    glow1: Color = NeonColors.Cyan,
    glow2: Color = NeonColors.Purple,
    showName: Boolean = true
) {
    val inf = rememberInfiniteTransition(label = "logoV4")
    val flow by inf.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Restart),
        label = "flow"
    )
    val spin by inf.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(9000, easing = LinearEasing), RepeatMode.Restart),
        label = "spin"
    )
    val pulse by inf.animateFloat(
        initialValue = 0.92f, targetValue = 1.08f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Reverse),
        label = "pulse"
    )
    val ripple by inf.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart),
        label = "ripple"
    )
    val a = if (dim) 0.85f else 1f

    Box(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val cx = w / 2f
            val cy = h * 0.52f

            // Фон из темы + два дрейфующих пятна => аккуратное переливание
            drawRect(Brush.verticalGradient(listOf(top, bottom)))
            drawRect(
                Brush.radialGradient(
                    colors = listOf(glow1.copy(0.22f * a), Color.Transparent),
                    center = Offset(cx + sin(flow * (2.0 * PI).toFloat()) * w * 0.18f, cy - h * 0.10f),
                    radius = w * 0.85f
                )
            )
            drawRect(
                Brush.radialGradient(
                    colors = listOf(glow2.copy(0.18f * a), Color.Transparent),
                    center = Offset(cx - sin(flow * (2.0 * PI).toFloat()) * w * 0.20f, h * 0.90f),
                    radius = w * 0.70f
                )
            )

            // Вращающееся свечение за кнопкой
            rotate(spin, pivot = Offset(cx, cy)) {
                drawCircle(
                    brush = Brush.sweepGradient(
                        colors = listOf(
                            Color.Transparent,
                            glow1.copy(0.45f * a),
                            glow2.copy(0.45f * a),
                            Color.Transparent
                        ),
                        center = Offset(cx, cy)
                    ),
                    radius = w * 0.26f,
                    center = Offset(cx, cy),
                    style = Stroke(width = w * 0.012f, cap = StrokeCap.Round)
                )
            }

            // Частицы: целые скорости => граница цикла бесшовная
            for (i in 0..11) {
                val speed = 1 + (i % 3)
                val off = (i * 0.618f) % 1f
                val t = ((off + flow * speed) % 1f + 1f) % 1f
                val px = w * (((i * 0.618f + 0.11f) % 1f) + sin(flow * (2.0 * PI).toFloat() + i) * 0.02f)
                val py = h * (1f - t)
                val alpha = sin(PI.toFloat() * t) * 0.40f * a
                val col = if (i % 2 == 0) glow1 else glow2
                drawCircle(
                    color = col.copy(alpha = alpha),
                    radius = w * (0.004f + (i % 3) * 0.002f),
                    center = Offset(px, py)
                )
            }

            // Расходящееся кольцо от кнопки
            val rr = w * 0.16f * (1.1f + 0.9f * ripple)
            drawCircle(
                color = glow1.copy(alpha = (1f - ripple) * 0.25f * a),
                radius = rr,
                center = Offset(cx, cy),
                style = Stroke(width = w * 0.008f)
            )

            // Стеклянная кнопка play с пульсацией
            val r = w * 0.16f * pulse
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color.White.copy(0.14f), Color.Transparent),
                    center = Offset(cx, cy), radius = r * 1.5f
                ),
                radius = r * 1.5f,
                center = Offset(cx, cy)
            )
            val grad = Brush.linearGradient(
                colors = listOf(glow1, glow2),
                start = Offset(cx - r, cy - r), end = Offset(cx + r, cy + r)
            )
            drawCircle(brush = grad, radius = r, center = Offset(cx, cy), style = Stroke(width = w * 0.010f), alpha = 0.9f * a)
            val tw = r * 0.95f
            val play = Path().apply {
                moveTo(cx - tw * 0.35f, cy - tw * 0.55f)
                lineTo(cx + tw * 0.75f, cy)
                lineTo(cx - tw * 0.35f, cy + tw * 0.55f)
                close()
            }
            drawPath(play, color = Color.White.copy(alpha = 0.12f))
            drawPath(play, brush = grad, alpha = 0.95f * a)
        }

        // Имя СВЕРХУ; лента градиента в 2 ширины и sweep на 3 ширины => без обрыва
        if (showName) {
            BoxWithConstraints(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(top = 10.dp)
            ) {
                val w = constraints.maxWidth.toFloat()
                val bandStart = -2f * w + flow * 3f * w
                Text(
                    text = "CoolPlayer",
                    style = TextStyle(
                        brush = Brush.linearGradient(
                            colors = listOf(glow1, glow2, glow1),
                            start = Offset(bandStart, 0f),
                            end = Offset(bandStart + 2f * w, 0f)
                        ),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 2.sp,
                        textAlign = TextAlign.Center
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}