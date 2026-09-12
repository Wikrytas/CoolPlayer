package com.wikrytas.coolplayer.ui.screens

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wikrytas.coolplayer.data.AppLogger
import com.wikrytas.coolplayer.data.LyricsOffsetStore
import com.wikrytas.coolplayer.data.LyricsRepository
import com.wikrytas.coolplayer.data.OnDeviceAligner
import com.wikrytas.coolplayer.data.PcSync
import com.wikrytas.coolplayer.models.Track
import com.wikrytas.coolplayer.ui.PlayerViewModel
import com.wikrytas.coolplayer.ui.theme.PlayerTheme
import kotlinx.coroutines.launch

data class LyricLine(val timeMs: Long, val text: String)

fun parseLrcContent(content: String): List<LyricLine> {
    val lines = mutableListOf<LyricLine>()
    val timePattern = Regex("\\[(\\d{2}):(\\d{2})(?:[.:](\\d{2,3}))?\\]")
    content.lines().forEach { line ->
        val matches = timePattern.findAll(line).toList()
        if (matches.isNotEmpty()) {
            val lastMatch = matches.last()
            val text = line.substring(lastMatch.range.last + 1).trim()
            if (text.isNotBlank()) {
                matches.forEach { match ->
                    val minutes = match.groupValues[1].toLong()
                    val seconds = match.groupValues[2].toLong()
                    val millis = match.groupValues.getOrNull(3) ?: "0"
                    val timeMs = minutes * 60000 + seconds * 1000 +
                            when (millis.length) { 2 -> millis.toLong() * 10; 3 -> millis.toLong(); else -> 0L }
                    lines.add(LyricLine(timeMs, text))
                }
            }
        }
    }
    return lines.distinctBy { it.timeMs }.sortedBy { it.timeMs }
}

fun parsePlainLyrics(content: String): List<LyricLine> =
    content.lines()
        .mapNotNull { line ->
            val cleaned = line.replace(Regex("\\[\\d{2}:\\d{2}\\.\\d{2,3}]"), "").trim()
            if (cleaned.isNotBlank()) LyricLine(0L, cleaned) else null
        }

private val LRC_REGEX = Regex("\\[\\d{2}:\\d{2}")

@Composable
fun LyricsScreen(
    track: Track?,
    theme: PlayerTheme,
    playerViewModel: PlayerViewModel,
    lyricsRepository: LyricsRepository,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val offsetStore = remember { LyricsOffsetStore(context.applicationContext) }

    var lyricLines by remember(track?.id) { mutableStateOf<List<LyricLine>?>(null) }
    var rawContent by remember(track?.id) { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var notFound by remember(track?.id) { mutableStateOf(false) }
    var isCached by remember(track?.id) { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    var isEmbedding by remember { mutableStateOf(false) }
    var isAligning by remember { mutableStateOf(false) }
    var pcSyncing by remember { mutableStateOf(false) }
    var hostDialog by remember { mutableStateOf(false) }
    var hostInput by remember { mutableStateOf("") }
    var autoSyncInput by remember { mutableStateOf(false) }
    var lyricsOffset by remember(track?.id) { mutableStateOf(0L) }
    var pendingEmbedTrack by remember { mutableStateOf<Track?>(null) }
    var pendingEmbedContent by remember { mutableStateOf<String?>(null) }

    val writeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val t = pendingEmbedTrack
        val content = pendingEmbedContent
        if (result.resultCode == Activity.RESULT_OK && t != null && content != null) {
            scope.launch {
                isEmbedding = true
                val ok = lyricsRepository.retryEmbedWithPermission(t, content)
                isEmbedding = false
                Toast.makeText(context, if (ok) "Текст встроен" else "Не удалось встроить", Toast.LENGTH_SHORT).show()
            }
        }
        pendingEmbedTrack = null
        pendingEmbedContent = null
    }

    val uiState by playerViewModel.uiState.collectAsState()
    val currentPosition = uiState.position
    val listState = rememberLazyListState()

    fun applyContent(content: String) {
        rawContent = content
        lyricLines = if (content.contains(LRC_REGEX)) parseLrcContent(content) else parsePlainLyrics(content)
        notFound = false
    }

    fun runPcSync(showToast: Boolean) {
        val t = track ?: return
        val plain = rawContent ?: return
        if (plain.contains(LRC_REGEX)) return
        pcSyncing = true
        scope.launch {
            AppLogger.i("PcSync", "start align via PC: id=${t.id}")
            val lrc = PcSync.alignViaPc(context, t.uri, plain, t.id)
            pcSyncing = false
            if (lrc != null) {
                lyricsRepository.cacheLyrics(t, lrc, true)
                applyContent(lrc)
                AppLogger.i("PcSync", "align OK: id=${t.id}")
                if (showToast) Toast.makeText(context, "✅ Синхротекст получен с ПК", Toast.LENGTH_SHORT).show()
            } else {
                AppLogger.w("PcSync", "align failed: id=${t.id}")
                if (showToast) Toast.makeText(context, "ПК не ответил / ошибка выравнивания", Toast.LENGTH_SHORT).show()
            }
        }
    }

    suspend fun loadLyrics(forceRefresh: Boolean = false) {
        isLoading = true
        notFound = false
        val t = track ?: run { isLoading = false; return }
        lyricsOffset = offsetStore.getOffset(t.id)
        if (forceRefresh) { lyricsRepository.clearCache(t); isCached = false }
        else isCached = lyricsRepository.getCachedLyrics(t) != null
        val content = runCatching {
            lyricsRepository.resolveLyrics(t, forceOnline = forceRefresh, useOnline = true)
        }.getOrNull()
        if (!content.isNullOrBlank()) {
            applyContent(content)
        } else {
            rawContent = null; lyricLines = null; notFound = true
        }
        isLoading = false
        // Автосинхронизация через ПК, если нашли только plain и ПК включён
        if (!content.isNullOrBlank() && !content.contains(LRC_REGEX)
            && PcSync.enabled(context) && PcSync.host(context).isNotBlank()
        ) {
            runPcSync(showToast = false)
        }
    }

    LaunchedEffect(track?.id) { loadLyrics() }

    val isSynced = remember(lyricLines) { lyricLines?.any { it.timeMs > 0L } == true }

    val currentLineIndex = remember(lyricLines, currentPosition, lyricsOffset) {
        lyricLines?.let { lines ->
            if (lines.isEmpty() || lines.all { it.timeMs == 0L }) return@let -1
            val shifted = currentPosition + lyricsOffset
            var left = 0; var right = lines.size - 1; var result = -1
            while (left <= right) {
                val mid = (left + right) / 2
                if (lines[mid].timeMs <= shifted) { result = mid; left = mid + 1 } else right = mid - 1
            }
            result
        } ?: -1
    }
    val activeIndex = if (isSynced) currentLineIndex.coerceAtLeast(0) else -1

    LaunchedEffect(activeIndex, lyricLines) {
        if (isSynced && activeIndex >= 0) listState.animateScrollToItem((activeIndex - 2).coerceAtLeast(0))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(theme.backgroundTop, theme.backgroundBottom)))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, "Назад", tint = Color.White)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("Текст песни", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                if (!isLoading) {
                    val mode = when {
                        pcSyncing -> "синхронизация через ПК..."
                        isAligning -> "создание таймкодов..."
                        isSynced -> "караоке"
                        lyricLines != null -> "прокрутка вручную"
                        else -> ""
                    }
                    val src = if (isCached) "кэш" else "поиск"
                    if (mode.isNotEmpty()) Text("$src · $mode", color = theme.accent.copy(alpha = 0.6f), fontSize = 11.sp)
                }
            }
            if (pcSyncing || isAligning) CircularProgressIndicator(color = theme.accent, modifier = Modifier.width(20.dp).height(20.dp))
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Default.MoreVert, "Действия", tint = Color.White)
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                    modifier = Modifier.background(theme.backgroundBottom)
                ) {
                    DropdownMenuItem(
                        text = { Text("Найти текст в сети", color = Color.White) },
                        leadingIcon = { Icon(Icons.Default.Refresh, null, tint = theme.accent) },
                        onClick = { menuExpanded = false; scope.launch { loadLyrics(forceRefresh = true) } }
                    )
                    DropdownMenuItem(
                        text = { Text("Синхронизировать через ПК", color = Color.White) },
                        enabled = !pcSyncing && rawContent != null && !isSynced,
                        onClick = {
                            menuExpanded = false
                            if (PcSync.host(context).isBlank()) {
                                hostInput = ""
                                autoSyncInput = PcSync.enabled(context)
                                hostDialog = true
                            } else runPcSync(showToast = true)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Настроить ПК-синхронизацию", color = Color.White) },
                        onClick = {
                            menuExpanded = false
                            hostInput = PcSync.host(context)
                            autoSyncInput = PcSync.enabled(context)
                            hostDialog = true
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Создать таймкоды (на устройстве)", color = Color.White) },
                        enabled = !isAligning && rawContent != null && !isSynced,
                        onClick = {
                            menuExpanded = false
                            val t = track ?: return@DropdownMenuItem
                            val plain = rawContent ?: return@DropdownMenuItem
                            isAligning = true
                            scope.launch {
                                val lrc = OnDeviceAligner.alignPlainToTimed(t, plain.lines())
                                isAligning = false
                                if (lrc != null) { lyricsRepository.cacheLyrics(t, lrc, true); applyContent(lrc) }
                                else Toast.makeText(context, "Не удалось создать таймкоды", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(if (isEmbedding) "Встраивание..." else "Встроить в песню", color = Color.White) },
                        enabled = !isEmbedding && rawContent != null,
                        onClick = {
                            menuExpanded = false
                            val t = track ?: return@DropdownMenuItem
                            val content = lyricsRepository.getCachedLyrics(t) ?: rawContent ?: return@DropdownMenuItem
                            isEmbedding = true
                            scope.launch {
                                val synced = content.contains(LRC_REGEX)
                                val r = lyricsRepository.embedInFile(t, content, synced)
                                isEmbedding = false
                                when {
                                    r.success -> Toast.makeText(context, "Текст встроен", Toast.LENGTH_SHORT).show()
                                    r.needsPermission && r.intentSender != null -> {
                                        pendingEmbedTrack = t; pendingEmbedContent = content
                                        runCatching { writeLauncher.launch(IntentSenderRequest.Builder(r.intentSender).build()) }
                                    }
                                    else -> Toast.makeText(context, r.error ?: "Не удалось встроить", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Текст раньше на 0.5 с", color = Color.White) },
                        onClick = { val t = track ?: return@DropdownMenuItem; lyricsOffset -= 500; scope.launch { offsetStore.setOffset(t.id, lyricsOffset) } }
                    )
                    DropdownMenuItem(
                        text = { Text("Текст позже на 0.5 с", color = Color.White) },
                        onClick = { val t = track ?: return@DropdownMenuItem; lyricsOffset += 500; scope.launch { offsetStore.setOffset(t.id, lyricsOffset) } }
                    )
                }
            }
        }

        if (track != null && !isLoading && !notFound) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(track.title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Text(track.artist, color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp, textAlign = TextAlign.Center)
            }
        }

        Box(modifier = Modifier.weight(1f)) {
            when {
                isLoading || pcSyncing || isAligning -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = theme.accent, modifier = Modifier.width(40.dp).height(40.dp))
                            Spacer(Modifier.height(16.dp))
                            Text(
                                when { pcSyncing -> "ПК выравнивает текст..."; isAligning -> "Создаём таймкоды..."; else -> "Ищем текст..." },
                                color = Color.White.copy(alpha = 0.5f), fontSize = 15.sp
                            )
                        }
                    }
                }
                notFound -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Текст не найден", color = Color(0xFFFF8A80), fontSize = 16.sp, textAlign = TextAlign.Center, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.height(16.dp))
                            TextButton(onClick = { scope.launch { loadLyrics(forceRefresh = true) } }) { Text("Повторить", color = theme.accent) }
                        }
                    }
                }
                lyricLines.isNullOrEmpty() -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Текст песни не найден", color = Color.White.copy(alpha = 0.7f), fontSize = 16.sp, textAlign = TextAlign.Center)
                    }
                }
                else -> {
                    val lines = lyricLines!!
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
                        contentPadding = PaddingValues(vertical = 100.dp)
                    ) {
                        itemsIndexed(lines) { index, line ->
                            val isActive = isSynced && index == activeIndex
                            val isPast = isSynced && index < activeIndex
                            val textColor = when {
                                isActive -> theme.accent
                                isPast -> Color.White.copy(alpha = 0.35f)
                                else -> Color.White.copy(alpha = if (isSynced) 0.6f else 0.8f)
                            }
                            Text(
                                text = line.text,
                                color = textColor,
                                fontSize = if (isActive) 20.sp else 16.sp,
                                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                                lineHeight = if (isActive) 32.sp else 28.sp,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    // Диалог настройки ПК-синхронизации
    if (hostDialog) {
        AlertDialog(
            onDismissRequest = { hostDialog = false },
            title = { Text("ПК-синхронизация", color = Color.White) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text("Адрес сервера на ПК (http://IP:8787):", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
                    Spacer(Modifier.height(6.dp))
                    TextField(
                        value = hostInput,
                        onValueChange = { hostInput = it },
                        placeholder = { Text("http://192.168.0.10:8787", color = Color.White.copy(alpha = 0.4f)) },
                        singleLine = true,
                        colors = TextFieldDefaults.colors(
                            focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                            focusedContainerColor = Color.White.copy(alpha = 0.08f),
                            unfocusedContainerColor = Color.White.copy(alpha = 0.06f),
                            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = autoSyncInput, onCheckedChange = { autoSyncInput = it })
                        Text("Автосинхронизация, когда найден только plain", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    PcSync.setHost(context, hostInput.trim())
                    PcSync.setEnabled(context, autoSyncInput)
                    hostDialog = false
                    scope.launch {
                        val ok = PcSync.ping(context)
                        Toast.makeText(context, if (ok) "ПК доступен" else "ПК не доступен — проверь адрес/фаервол", Toast.LENGTH_SHORT).show()
                        if (ok && rawContent != null && rawContent?.contains(LRC_REGEX) == false) runPcSync(showToast = true)
                    }
                }) { Text("Сохранить", color = theme.accent) }
            },
            dismissButton = { TextButton(onClick = { hostDialog = false }) { Text("Отмена", color = Color.White.copy(alpha = 0.7f)) } },
            containerColor = Color(0xFF171021)
        )
    }
}