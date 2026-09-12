package com.wikrytas.coolplayer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wikrytas.coolplayer.data.AppUpdater
import com.wikrytas.coolplayer.data.UpdateInfo
import com.wikrytas.coolplayer.ui.theme.PlayerTheme
import kotlinx.coroutines.launch
import java.io.File

private sealed interface UpdateUiState {
    object Idle : UpdateUiState
    object Checking : UpdateUiState
    object UpToDate : UpdateUiState
    data class Available(val info: UpdateInfo) : UpdateUiState
    data class Downloading(val progress: Int) : UpdateUiState
    data class NeedPermission(val apk: File) : UpdateUiState
    data class Error(val msg: String) : UpdateUiState
}

@Composable
fun AppUpdateCard(theme: PlayerTheme, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<UpdateUiState>(UpdateUiState.Idle) }

    fun check() {
        state = UpdateUiState.Checking
        scope.launch {
            val info = AppUpdater.checkForUpdate(context)
            state = when {
                info == null -> UpdateUiState.Error("Не удалось проверить обновления")
                !info.isNewer -> UpdateUiState.UpToDate
                else -> UpdateUiState.Available(info)
            }
        }
    }

    // FIX: авто-проверка при открытии карточки
    LaunchedEffect(Unit) { check() }

    fun startUpdate(info: UpdateInfo) {
        state = UpdateUiState.Downloading(0)
        scope.launch {
            val apk = AppUpdater.downloadApk(context, info.downloadUrl) { p ->
                state = UpdateUiState.Downloading(p)
            }
            if (apk == null) {
                state = UpdateUiState.Error("Не удалось скачать обновление")
                return@launch
            }
            if (!AppUpdater.canInstall(context)) {
                state = UpdateUiState.NeedPermission(apk)
                return@launch
            }
            AppUpdater.installApk(context, apk)
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .padding(14.dp)
    ) {
        Text(
            "Обновление приложения",
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(10.dp))
        when (val s = state) {
            UpdateUiState.Idle -> {
                UpdateButton("Проверить обновления", theme) { check() }
            }
            UpdateUiState.Checking -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(color = theme.accent, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("Проверяем...", color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
                }
            }
            UpdateUiState.UpToDate -> {
                Text("У вас последняя версия", color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                UpdateButton("Проверить снова", theme) { check() }
            }
            is UpdateUiState.Available -> {
                Text(
                    "Доступна версия ${s.info.versionName}",
                    color = theme.accent,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
                if (s.info.releaseNotes.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        s.info.releaseNotes,
                        color = Color.White.copy(alpha = 0.5f),
                        fontSize = 12.sp,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.height(10.dp))
                UpdateButton("Скачать и установить", theme) { startUpdate(s.info) }
            }
            is UpdateUiState.Downloading -> {
                Text("Скачивание... ${s.progress}%", color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color.White.copy(alpha = 0.1f))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(s.progress / 100f)
                            .clip(RoundedCornerShape(3.dp))
                            .background(theme.accent)
                    )
                }
            }
            is UpdateUiState.NeedPermission -> {
                Text(
                    "Разрешите установку приложений из этого источника",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 13.sp
                )
                Spacer(Modifier.height(8.dp))
                UpdateButton("Открыть настройки", theme) {
                    AppUpdater.openInstallSettings(context)
                }
                Spacer(Modifier.height(6.dp))
                UpdateButton("Установить", theme) {
                    AppUpdater.installApk(context, s.apk)
                }
            }
            is UpdateUiState.Error -> {
                Text(s.msg, color = Color(0xFFFF8A80), fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                UpdateButton("Повторить", theme) { check() }
            }
        }
    }
}

@Composable
private fun UpdateButton(text: String, theme: PlayerTheme, onClick: () -> Unit) {
    Text(
        text,
        color = theme.backgroundTop,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(theme.accent)
            .clickable { onClick() }
            .padding(horizontal = 20.dp, vertical = 9.dp)
    )
}