package com.wikrytas.coolplayer.ui.components.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.draw.alpha

/** Кнопка с пружинной анимацией нажатия. */
@Composable
fun Pressable(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.86f else 1f,
        animationSpec = spring(dampingRatio = 0.45f, stiffness = 320f),
        label = "press"
    )
    Box(
        modifier = modifier
            .scale(scale)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
        content = content
    )
}

/** Play/Pause с пульсирующим кольцом и кроссфейдом иконки. */
@Composable
fun PlayButton(
    isPlaying: Boolean,
    accent: Color,
    iconTint: Color,
    onClick: () -> Unit
) {
    val pulse = rememberInfiniteTransition(label = "pulse")
    val p by pulse.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart),
        label = "p"
    )
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.9f else 1f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = 300f),
        label = "playPress"
    )

    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(84.dp)) {
        if (isPlaying) {
            Box(
                Modifier
                    .size(72.dp)
                    .scale(0.9f + p * 0.35f)
                    .alpha((1f - p) * 0.5f)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.35f))
            )
        }
        Box(
            modifier = Modifier
                .size(68.dp)
                .scale(scale)
                .shadow(20.dp, CircleShape, ambientColor = accent, spotColor = accent)
                .clip(CircleShape)
                .background(Brush.radialGradient(listOf(accent, accent.copy(alpha = 0.75f))))
                .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            androidx.compose.animation.AnimatedContent(targetState = isPlaying, label = "playIcon") { playing ->
                Icon(
                    if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                    null,
                    tint = iconTint,
                    modifier = Modifier.size(32.dp)
                )
            }
        }
    }
}

/** Ряд транспортных контролов: shuffle / prev / play / next / repeat. */
@Composable
fun PlayerControlsRow(
    isPlaying: Boolean,
    shuffleEnabled: Boolean,
    repeatMode: Int,
    accent: Color,
    playIconTint: Color,
    onShuffle: () -> Unit,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onRepeat: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Pressable(onClick = onShuffle, modifier = Modifier.size(44.dp)) {
            Icon(
                Icons.Default.Shuffle, null,
                tint = if (shuffleEnabled) accent else Color.White.copy(alpha = 0.55f),
                modifier = Modifier.size(20.dp)
            )
        }

        Pressable(onClick = onPrevious, modifier = Modifier.size(56.dp)) {
            Icon(Icons.Default.SkipPrevious, null, tint = Color.White, modifier = Modifier.size(32.dp))
        }

        PlayButton(isPlaying = isPlaying, accent = accent, iconTint = playIconTint, onClick = onPlayPause)

        Pressable(onClick = onNext, modifier = Modifier.size(56.dp)) {
            Icon(Icons.Default.SkipNext, null, tint = Color.White, modifier = Modifier.size(32.dp))
        }

        Pressable(onClick = onRepeat, modifier = Modifier.size(44.dp)) {
            Icon(
                if (repeatMode == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                null,
                tint = if (repeatMode != Player.REPEAT_MODE_OFF) accent else Color.White.copy(alpha = 0.55f),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}