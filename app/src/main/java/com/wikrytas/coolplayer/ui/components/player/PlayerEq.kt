package com.wikrytas.coolplayer.ui.components.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wikrytas.coolplayer.audio.EqualizerController
import com.wikrytas.coolplayer.audio.EqPreset
import kotlin.math.roundToInt

/** Панель эквалайзера: пресеты-чипы + слайдеры полос. */
@Composable
fun EqPanel(accent: Color) {
    var activePreset by remember { mutableStateOf(EqPreset.FLAT) }
    var tick by remember { mutableIntStateOf(0) }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.08f))
            .padding(14.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "ЭКВАЛАЙЗЕР",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 11.sp,
                letterSpacing = 3.sp,
                fontWeight = FontWeight.SemiBold,
                fontStyle = FontStyle.Normal
            )
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                EqPreset.entries.forEach { preset ->
                    val active = preset == activePreset
                    Text(
                        text = preset.label,
                        color = if (active) Color.White else Color.White.copy(alpha = 0.55f),
                        fontSize = 10.sp,
                        fontStyle = FontStyle.Normal,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier
                            .padding(start = 6.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(color = if (active) accent.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.06f))
                            .clickable {
                                activePreset = preset
                                EqualizerController.applyPreset(preset)
                                tick++
                            }
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        val bands = EqualizerController.bandCount.toInt().coerceAtMost(8)
        repeat(bands) { i ->
            val band = i.toShort()
            var db by remember(tick) { mutableStateOf(EqualizerController.getLevel(band) / 100f) }
            Row(
                Modifier.fillMaxWidth().padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    EqualizerController.centerFreqLabel(band),
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 10.sp,
                    fontStyle = FontStyle.Normal,
                    modifier = Modifier.width(44.dp)
                )
                EqSlider(
                    value = db,
                    onValueChange = {
                        db = it
                        EqualizerController.setLevel(band, (it * 100f).roundToInt().toShort())
                    },
                    modifier = Modifier.weight(1f)
                )
                Text(
                    String.format("%+.1f", db),
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 10.sp,
                    fontStyle = FontStyle.Normal,
                    modifier = Modifier.width(40.dp),
                    textAlign = TextAlign.End
                )
            }
        }
    }
}

@Composable
fun EqSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier.height(22.dp), contentAlignment = Alignment.CenterStart) {
        val wState = rememberUpdatedState(constraints.maxWidth.toFloat())
        val frac = ((value + 12f) / 24f).coerceIn(0f, 1f)

        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { },
                        onDragEnd = { },
                        onDragCancel = { },
                        onHorizontalDrag = { change, amount ->
                            change.consume()
                            val w = wState.value
                            if (w > 0) {
                                val next = value + amount / w * 24f
                                onValueChange(next.coerceIn(-12f, 12f))
                            }
                        }
                    )
                }
        ) {
            val y = size.height / 2f
            val x = size.width * frac
            drawLine(Color.White.copy(alpha = 0.15f), Offset(0f, y), Offset(size.width, y), strokeWidth = 3f, cap = StrokeCap.Round)
            drawLine(Color.White, Offset(0f, y), Offset(x, y), strokeWidth = 3f, cap = StrokeCap.Round)
            drawCircle(Color.White, radius = 5f, center = Offset(x, y))
        }
    }
}