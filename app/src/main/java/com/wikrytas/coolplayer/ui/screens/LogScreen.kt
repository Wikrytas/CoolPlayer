package com.wikrytas.coolplayer.ui.screens

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wikrytas.coolplayer.data.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "LogScreen"

@Composable
fun LogScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    var content by remember { mutableStateOf("") }
    var refreshKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(refreshKey) {
        content = withContext(Dispatchers.IO) { AppLogger.readAll() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0E14))
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, "Назад", tint = Color.White)
            }
            Text(
                "Лог приложения",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = {
                AppLogger.d(TAG, "refresh")
                refreshKey++
            }) {
                Icon(Icons.Default.Refresh, "Обновить", tint = Color.White)
            }
            IconButton(onClick = {
                AppLogger.i(TAG, "copied to clipboard: ${content.length} chars")
                clipboard.setText(AnnotatedString(content))
                Toast.makeText(context, "Лог скопирован", Toast.LENGTH_SHORT).show()
            }) {
                Icon(Icons.Default.ContentCopy, "Копировать", tint = Color.White)
            }
            IconButton(onClick = {
                AppLogger.i(TAG, "shared via intent: ${content.length} chars")
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "CoolPlayer log")
                    putExtra(Intent.EXTRA_TEXT, content)
                }
                context.startActivity(Intent.createChooser(send, "Отправить лог"))
            }) {
                Icon(Icons.Default.Share, "Поделиться", tint = Color.White)
            }
            IconButton(onClick = {
                AppLogger.clear()
                refreshKey++
                Toast.makeText(context, "Лог очищен", Toast.LENGTH_SHORT).show()
            }) {
                Icon(Icons.Default.Delete, "Очистить", tint = Color(0xFFFF5252))
            }
        }

        Text(
            text = if (content.isBlank()) "Лог пуст" else content,
            color = Color(0xFFB8C4D0),
            fontSize = 10.sp,
            lineHeight = 14.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp)
        )
    }
}