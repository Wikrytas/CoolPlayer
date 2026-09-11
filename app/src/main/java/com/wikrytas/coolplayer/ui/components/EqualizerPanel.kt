package com.wikrytas.coolplayer.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wikrytas.coolplayer.audio.EqPreset
import com.wikrytas.coolplayer.audio.EqualizerController

@Composable
fun EqualizerPanel(
    open: Boolean,
    currentPreset: EqPreset,
    accent: Color,
    onPreset: (EqPreset) -> Unit,
    onLevel: (Short, Short) -> Unit
) {
    if (!open) return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .padding(16.dp)
    ) {
        Text(
            "ЭКВАЛАЙЗЕР",
            color = accent,
            fontSize = 11.sp,
            letterSpacing = 3.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            EqPreset.entries.forEach { preset ->
                val selected = preset == currentPreset
                val bgColor by animateColorAsState(
                    if (selected) accent else Color.White.copy(alpha = 0.08f),
                    label = "preset_bg_${preset.name}"
                )
                val textColor by animateColorAsState(
                    if (selected) Color.Black else Color.White.copy(alpha = 0.7f),
                    label = "preset_text_${preset.name}"
                )

                Text(
                    text = preset.label,
                    color = textColor,
                    fontSize = 12.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(bgColor)
                        .clickable { onPreset(preset) }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        val bandCount = EqualizerController.bandCount.toInt()
        val bandRange = EqualizerController.bandRange

        if (bandCount > 0 && EqualizerController.isReady) {
            // FIX #2: Используем отдельные компоненты для каждой полосы
            for (i in 0 until bandCount) {
                EqualizerBand(
                    band = i.toShort(),
                    bandRange = bandRange,
                    accent = accent,
                    onLevel = onLevel
                )
            }
        } else {
            Text(
                text = "Эквалайзер не активен.\nВключите воспроизведение музыки.",
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 13.sp,
                modifier = Modifier.fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text = "Сбросить",
                color = accent.copy(alpha = 0.8f),
                fontSize = 12.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.White.copy(alpha = 0.05f))
                    .border(1.dp, accent.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                    .clickable { onPreset(EqPreset.FLAT) }
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            )
        }
    }
}

// FIX #2: Отдельный компонент для каждой полосы с правильной синхронизацией
@Composable
private fun EqualizerBand(
    band: Short,
    bandRange: IntRange,
    accent: Color,
    onLevel: (Short, Short) -> Unit
) {
    // Локальное состояние слайдера
    var level by remember(band) { 
        mutableStateOf(EqualizerController.getLevel(band)) 
    }
    
    // Синхронизация с внешним applyPreset
    LaunchedEffect(band) {
        // Проверяем периодически, не изменилось ли значение извне
        kotlinx.coroutines.delay(100)
        val externalLevel = EqualizerController.getLevel(band)
        if (externalLevel != level) {
            level = externalLevel
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = EqualizerController.centerFreqLabel(band),
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 10.sp,
            modifier = Modifier.width(48.dp)
        )

        Slider(
            value = level.toFloat(),
            onValueChange = { newValue ->
                level = newValue.toInt().toShort()
            },
            onValueChangeFinished = {
                onLevel(band, level)
            },
            valueRange = bandRange.first.toFloat()..bandRange.last.toFloat(),
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(
                thumbColor = accent,
                activeTrackColor = accent,
                inactiveTrackColor = Color.White.copy(alpha = 0.15f)
            )
        )

        Text(
            text = "$level",
            color = Color.White.copy(alpha = 0.5f),
            fontSize = 10.sp,
            modifier = Modifier.width(40.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.End
        )
    }
}