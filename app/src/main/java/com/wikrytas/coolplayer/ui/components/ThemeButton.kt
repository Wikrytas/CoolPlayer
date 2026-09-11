package com.wikrytas.coolplayer.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wikrytas.coolplayer.ui.theme.PlayerTheme

@Composable
fun ThemeButton(
    currentTheme: PlayerTheme,
    nextTheme: PlayerTheme,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var turns by remember { mutableStateOf(0f) }
    val iconRotation by animateFloatAsState(
        targetValue = turns,
        animationSpec = tween(600, easing = FastOutSlowInEasing),
        label = "icon_rotation"
    )

    val pillShape = RoundedCornerShape(percent = 50)

    Row(
        modifier = modifier
            .border(
                width = 1.5.dp,
                brush = Brush.horizontalGradient(listOf(currentTheme.accent, nextTheme.accent)),
                shape = pillShape
            )
            .background(
                brush = Brush.horizontalGradient(
                    listOf(currentTheme.accent.copy(alpha = 0.18f), nextTheme.accent.copy(alpha = 0.18f))
                ),
                shape = pillShape
            )
            .clickable {
                turns += 360f
                onClick()
            }
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            imageVector = Icons.Default.Palette,
            contentDescription = "Сменить тему",
            tint = currentTheme.accent,
            modifier = Modifier
                .size(20.dp)
                .graphicsLayer { rotationZ = iconRotation }
        )
        Text(
            text = currentTheme.name,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold
        )
        // Превью следующей темы
        Box(
            modifier = Modifier
                .size(14.dp)
                .clip(CircleShape)
                .background(Brush.radialGradient(listOf(nextTheme.accent, nextTheme.backgroundBottom)))
                .border(1.dp, Color.White.copy(alpha = 0.4f), CircleShape)
        )
    }
}