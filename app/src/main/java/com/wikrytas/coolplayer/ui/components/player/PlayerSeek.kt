package com.wikrytas.coolplayer.ui.components.player

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/** Прогресс-бар со свечением и анимацией толщины при перетаскивании. */
@Composable
fun GlowSeekBar(
    positionMs: Long,
    durationMs: Long,
    accent: Color,
    onSeekStart: () -> Unit,
    onSeek: (Long) -> Unit,
    onSeekEnd: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    var dragPos by remember { mutableLongStateOf(-1L) }
    var dragging by remember { mutableStateOf(false) }

    val trackH by animateDpAsState(if (dragging) 6.dp else 3.dp, tween(180), label = "trackH")
    val thumbR by animateDpAsState(if (dragging) 9.dp else 6.dp, tween(180), label = "thumbR")

    BoxWithConstraints(modifier.height(28.dp), contentAlignment = Alignment.CenterStart) {
        val wState = rememberUpdatedState(constraints.maxWidth.toFloat())
        val shown = if (dragPos >= 0) dragPos else positionMs
        val frac = if (durationMs > 0) (shown.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(durationMs) {
                    detectTapGestures { pos ->
                        val w = wState.value
                        if (durationMs > 0 && w > 0) {
                            val target = (pos.x / w * durationMs).toLong().coerceIn(0, durationMs)
                            onSeekStart(); onSeek(target); onSeekEnd(target)
                        }
                    }
                }
                .pointerInput(durationMs) {
                    detectHorizontalDragGestures(
                        onDragStart = { dragging = true; onSeekStart(); dragPos = positionMs },
                        onDragEnd = {
                            dragging = false
                            if (dragPos >= 0) onSeekEnd(dragPos)
                            dragPos = -1
                        },
                        onDragCancel = { dragging = false; dragPos = -1 },
                        onHorizontalDrag = { change, amount ->
                            change.consume()
                            val w = wState.value
                            if (durationMs > 0 && w > 0) {
                                val next = (dragPos + (amount / w * durationMs).toLong()).coerceIn(0, durationMs)
                                dragPos = next
                                onSeek(next)
                            }
                        }
                    )
                }
        ) {
            val y = size.height / 2f
            val x = size.width * frac
            val th = trackH.toPx()

            drawRoundRect(
                Color.White.copy(alpha = 0.2f),
                topLeft = Offset(0f, y - th / 2f),
                size = Size(size.width, th),
                cornerRadius = CornerRadius(th / 2f)
            )
            if (durationMs > 0) {
                drawRoundRect(
                    brush = Brush.horizontalGradient(listOf(accent.copy(alpha = 0.7f), accent)),
                    topLeft = Offset(0f, y - th / 2f),
                    size = Size(x, th),
                    cornerRadius = CornerRadius(th / 2f)
                )
                drawCircle(accent.copy(alpha = 0.35f), radius = thumbR.toPx() * 2.1f, center = Offset(x, y))
                drawCircle(Color.White, radius = thumbR.toPx(), center = Offset(x, y))
            }
        }
    }
}