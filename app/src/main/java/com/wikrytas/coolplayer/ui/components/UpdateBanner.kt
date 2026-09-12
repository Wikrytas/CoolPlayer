package com.wikrytas.coolplayer.ui.components

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wikrytas.coolplayer.data.AppUpdater
import com.wikrytas.coolplayer.data.UpdateInfo
import kotlinx.coroutines.launch
import java.io.File

/**
 * Полоска-уведомление о новой версии поверх плеера:
 * автопроверка при появлении, скачивание с прогрессом, установка внутри приложения.
 */
@Composable
fun UpdateBanner(
    accent: Color,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var info by remember { mutableStateOf<UpdateInfo?>(null) }
    var progress by remember { mutableStateOf<Int?>(null) }
    var pendingApk by remember { mutableStateOf<File?>(null) }

    LaunchedEffect(Unit) {
        val res = AppUpdater.checkForUpdate(context)
        if (res != null && res.isNewer) info = res
    }

    val current = info ?: return

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(accent.copy(alpha = 0.18f))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "Доступна версия ${current.versionName}",
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                when {
                    progress != null -> "Скачивание... $progress%"
                    pendingApk != null -> "Разрешите установку и нажмите «Установить»"
                    else -> "Обновление установится поверх текущей версии"
                },
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 11.sp
            )
        }
        if (pendingApk != null) {
            Text(
                "УСТАНОВИТЬ",
                color = accent,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable {
                        pendingApk?.let { apk -> AppUpdater.installApk(context, apk) }
                    }
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            )
        } else {
            Text(
                "ОБНОВИТЬ",
                color = accent,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(enabled = progress == null) {
                        scope.launch {
                            progress = 0
                            val apk = AppUpdater.downloadApk(context, current.downloadUrl) { p ->
                                progress = p
                            }
                            progress = null
                            if (apk == null) {
                                Toast.makeText(context, "Не удалось скачать обновление", Toast.LENGTH_SHORT).show()
                                return@launch
                            }
                            if (!AppUpdater.canInstall(context)) {
                                pendingApk = apk
                                AppUpdater.openInstallSettings(context)
                                return@launch
                            }
                            AppUpdater.installApk(context, apk)
                        }
                    }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }
        Text(
            "✕",
            color = Color.White.copy(alpha = 0.5f),
            fontSize = 14.sp,
            modifier = Modifier
                .padding(start = 8.dp)
                .clickable { onDismiss() }
                .padding(4.dp)
        )
    }
}