package com.wikrytas.coolplayer.ui.components

import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Одна карточка подсказки. */
data class Hint(val title: String, val text: String)

/** Флаги «подсказки уже показаны» — переживают перезапуски приложения. */
object HintsStore {
    private const val PREFS = "coach_marks"
    private const val KEY_PLAYER = "player_hints_shown"
    private const val KEY_LIBRARY = "library_hints_shown"

    fun shouldShowPlayer(ctx: Context): Boolean =
        !ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_PLAYER, false)

    fun shouldShowLibrary(ctx: Context): Boolean =
        !ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_LIBRARY, false)

    fun markPlayerShown(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_PLAYER, true).apply()
    }

    fun markLibraryShown(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_LIBRARY, true).apply()
    }

    /** Показать подсказки заново (для кнопки «Путеводитель» в настройках). */
    fun resetAll(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}

/**
 * Обучающий оверлей: затемнение + карточка шага + точки прогресса.
 * onDone — цепочка пройдена до конца, onNever — нажали «Больше не показывать».
 */
@Composable
fun CoachMarksOverlay(
    hints: List<Hint>,
    accent: Color,
    onDone: () -> Unit,
    onNever: () -> Unit
) {
    var step by remember { mutableIntStateOf(0) }

    LaunchedEffect(step) {
        if (step >= hints.size) onDone()
    }

    val hint = hints.getOrNull(step) ?: return

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.72f))
            .clickable(enabled = false) { },
        contentAlignment = Alignment.Center
    ) {
        AnimatedContent(
            targetState = step,
            transitionSpec = {
                (slideInHorizontally { it / 4 } + fadeIn()) togetherWith
                        (slideOutHorizontally { -it / 4 } + fadeOut())
            },
            label = "hint_step"
        ) { s ->
            val h = hints.getOrNull(s) ?: hints.last()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(Color(0xFF141A2A))
                    .padding(horizontal = 22.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "ПОДСКАЗКА ${s + 1} ИЗ ${hints.size}",
                    color = accent.copy(alpha = 0.7f),
                    fontSize = 10.sp,
                    letterSpacing = 3.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    h.title,
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    h.text,
                    color = Color.White.copy(alpha = 0.75f),
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(18.dp))
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    hints.forEachIndexed { i, _ ->
                        Box(
                            Modifier
                                .padding(horizontal = 3.dp)
                                .size(if (i == s) 8.dp else 6.dp)
                                .clip(CircleShape)
                                .background(if (i == s) accent else Color.White.copy(alpha = 0.25f))
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Больше не показывать",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 12.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onNever() }
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        if (s == hints.size - 1) "ГОТОВО" else "ДАЛЕЕ",
                        color = Color(0xFF0B0F1A),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(accent)
                            .clickable { step = s + 1 }
                            .padding(horizontal = 22.dp, vertical = 10.dp)
                    )
                }
            }
        }
    }
}