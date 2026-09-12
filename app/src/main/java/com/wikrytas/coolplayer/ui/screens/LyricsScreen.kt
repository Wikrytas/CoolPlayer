package com.wikrytas.coolplayer.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
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
import com.wikrytas.coolplayer.data.CoolDb
import com.wikrytas.coolplayer.data.LyricsCandidate
import com.wikrytas.coolplayer.data.LyricsOffsetStore
import com.wikrytas.coolplayer.data.LyricsRepository
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
                            when (millis.length) {
                                2 -> millis.toLong() * 10
                                3 -> millis.toLong()
                                else -> 0L
                            }
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
private const val TAG = "LyricsScreen"

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

    var pcSyncing by remember { mutableStateOf(false) }
    var hostDialog by remember { mutableStateOf(false) }
    var hostInput by remember { mutableStateOf("") }
    var autoSyncInput by remember { mutableStateOf(false) }

    var searchOpen by remember(track?.id) { mutableStateOf(false) }
    var searchArtist by remember(track?.id) { mutableStateOf("") }
    var searchTitle by remember(track?.id) { mutableStateOf("") }
    var searchSyncedOnly by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var candidates by remember { mutableStateOf<List<LyricsCandidate>>(emptyList()) }

    var pasteDialog by remember { mutableStateOf(false) }
    var pasteText by remember { mutableStateOf("") }

    val uiState by playerViewModel.uiState.collectAsState()
    val currentPosition = uiState.position
    val listState = rememberLazyListState()

    fun applyContent(content: String) {
        rawContent = content
        lyricLines = if (content.contains(LRC_REGEX)) parseLrcContent(content) else parsePlainLyrics(content)
        notFound = false
    }

    fun openSearch() {
        val t = track ?: return
        searchArtist = t.artist.takeUnless { it.isBlank() || it.contains("unknown", true) || it.contains("неизвестн", true) } ?: ""
        searchTitle = t.title
        candidates = emptyList()
        searchOpen = true
    }

    fun runPcSync(showToast: Boolean) {
        val t = track ?: return
        val plain = rawContent ?: return
        if (plain.contains(LRC_REGEX)) return
        pcSyncing = true
        scope.launch {
            AppLogger.i(TAG, "pc sync start: id=${t.id}")
            val lrc = PcSync.alignViaPc(context, t, plain)
            pcSyncing = false
            if (lrc != null) {
                lyricsRepository.cacheLyrics(t, lrc, true)
                applyContent(lrc)
                isCached = true
                AppLogger.i(TAG, "pc sync OK: id=${t.id}")
                if (showToast) Toast.makeText(context, "✅ Синхротекст получен", Toast.LENGTH_SHORT).show()
            } else {
                AppLogger.w(TAG, "pc sync failed: id=${t.id}")
                if (showToast) Toast.makeText(context, "Сервер не ответил / ошибка выравнивания", Toast.LENGTH_SHORT).show()
            }
        }
    }

    suspend fun loadLyrics() {
        isLoading = true
        notFound = false
        val t = track ?: run { isLoading = false; return }
        var content = lyricsRepository.getCachedLyrics(t)
        isCached = !content.isNullOrBlank()
        if (content.isNullOrBlank()) {
            val hit = CoolDb.fetch(t)
            if (hit != null) {
                lyricsRepository.cacheLyrics(t, hit, true)
                content = hit
                isCached = true
                AppLogger.i(TAG, "cooldb hit: id=${t.id}")
            }
        }
        if (content.isNullOrBlank()) {
            content = runCatching {
                lyricsRepository.resolveLyrics(t, forceOnline = false, useOnline = true)
            }.getOrNull()
        }
        if (!content.isNullOrBlank()) {
            applyContent(content)
        } else {
            rawContent = null
            lyricLines = null
            notFound = true
            openSearch()
        }
        isLoading = false
        if (rawContent != null && rawContent?.contains(LRC_REGEX) == false && PcSync.enabled(context)) {
            runPcSync(showToast = false)
        }
    }

    LaunchedEffect(track?.id) { loadLyrics() }

    val lyricsOffset = remember(track?.id) { offsetStore.getOffset(track?.id ?: 0L) }
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

    fun doSearch() {
        searching = true
        candidates = emptyList()
        scope.launch {
            val res = runCatching {
                lyricsRepository.searchByQuery(searchArtist.trim(), searchTitle.trim(), searchSyncedOnly)
            }.getOrElse { emptyList() }
            candidates = res
            searching = false
            AppLogger.i(TAG, "manual search: '${searchArtist.trim()} / ${searchTitle.trim()}' -> ${res.size}")
        }
    }

    fun applyCandidate(c: LyricsCandidate) {
        val t = track ?: return
        scope.launch {
            lyricsRepository.cacheLyrics(t, c.content, c.synced)
            applyContent(c.content)
            searchOpen = false
            Toast.makeText(context, if (c.synced) "Синхротекст применён" else "Текст применён (plain)", Toast.LENGTH_SHORT).show()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
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
                        pcSyncing -> "сервер выравнивает..."
                        searching -> "поиск..."
                        isSynced -> "караоке"
                        lyricLines != null -> "прокрутка вручную"
                        else -> ""
                    }
                    val src = if (isCached) "кэш" else "поиск"
                    if (mode.isNotEmpty()) Text("$src · $mode", color = theme.accent.copy(alpha = 0.6f), fontSize = 11.sp)
                }
            }
            if (pcSyncing || searching) CircularProgressIndicator(color = theme.accent, modifier = Modifier.width(20.dp).height(20.dp))
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
                        text = { Text("Найти текст (вручную)", color = Color.White) },
                        leadingIcon = { Icon(Icons.Default.Search, null, tint = theme.accent) },
                        onClick = { menuExpanded = false; openSearch() }
                    )
                    DropdownMenuItem(
                        text = { Text("Вставить свой текст", color = Color.White) },
                        leadingIcon = { Icon(Icons.Default.ContentPaste, null, tint = theme.accent) },
                        onClick = {
                            menuExpanded = false
                            pasteText = rawContent ?: ""
                            pasteDialog = true
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Синхронизировать через сервер", color = Color.White) },
                        leadingIcon = { Icon(Icons.Default.Sync, null, tint = theme.accent) },
                        enabled = !pcSyncing && rawContent != null && !isSynced,
                        onClick = {
                            menuExpanded = false
                            runPcSync(showToast = true)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Настройки синхронизации", color = Color.White) },
                        leadingIcon = { Icon(Icons.Default.Settings, null, tint = theme.accent) },
                        onClick = {
                            menuExpanded = false
                            hostInput = PcSync.host(context)
                            autoSyncInput = PcSync.enabled(context)
                            hostDialog = true
                        }
                    )
                }
            }
        }

        if (track != null && !isLoading && !notFound && !searchOpen) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(track.title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Text(track.artist, color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp, textAlign = TextAlign.Center)
            }
        }

        Box(modifier = Modifier.weight(1f)) {
            when {
                isLoading || pcSyncing -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = theme.accent, modifier = Modifier.width(40.dp).height(40.dp))
                            Spacer(Modifier.height(16.dp))
                            Text(
                                if (pcSyncing) "Сервер выравнивает текст..." else "Загрузка текста...",
                                color = Color.White.copy(alpha = 0.5f), fontSize = 15.sp
                            )
                        }
                    }
                }
                searchOpen -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp)
                    ) {
                        TextField(
                            value = searchArtist,
                            onValueChange = { searchArtist = it },
                            singleLine = true,
                            placeholder = { Text("Исполнитель", color = Color.White.copy(alpha = 0.4f)) },
                            colors = TextFieldDefaults.colors(
                                focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                                focusedContainerColor = Color.White.copy(alpha = 0.08f),
                                unfocusedContainerColor = Color.White.copy(alpha = 0.06f),
                                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(6.dp))
                        TextField(
                            value = searchTitle,
                            onValueChange = { searchTitle = it },
                            singleLine = true,
                            placeholder = { Text("Название", color = Color.White.copy(alpha = 0.4f)) },
                            colors = TextFieldDefaults.colors(
                                focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                                focusedContainerColor = Color.White.copy(alpha = 0.08f),
                                unfocusedContainerColor = Color.White.copy(alpha = 0.06f),
                                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = searchSyncedOnly, onCheckedChange = { searchSyncedOnly = it })
                            Text("Только с таймкодами", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { doSearch() }, enabled = !searching) {
                                Text("НАЙТИ", color = theme.accent)
                            }
                            if (searching) CircularProgressIndicator(color = theme.accent, modifier = Modifier.width(18.dp).height(18.dp))
                        }
                        Spacer(Modifier.height(8.dp))
                        candidates.forEach { c ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp)
                                    .background(Color.White.copy(alpha = 0.07f))
                                    .clickable { applyCandidate(c) }
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("${c.artist} — ${c.title}", color = Color.White, fontSize = 13.sp, maxLines = 2)
                                }
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    if (c.synced) "SYNCED" else c.source,
                                    color = if (c.synced) theme.accent else Color.White.copy(alpha = 0.5f),
                                    fontSize = 10.sp, fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        if (candidates.isEmpty() && !searching) {
                            Text(
                                "Ничего не найдено. Проверь запрос или вставь свой текст через меню.",
                                color = Color.White.copy(alpha = 0.45f), fontSize = 12.sp,
                                modifier = Modifier.padding(vertical = 12.dp)
                            )
                        }
                        Spacer(Modifier.height(24.dp))
                    }
                }
                notFound -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Текст не найден", color = Color(0xFFFF8A80), fontSize = 16.sp, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.height(12.dp))
                            TextButton(onClick = { openSearch() }) { Text("Найти вручную", color = theme.accent) }
                            TextButton(onClick = { pasteText = ""; pasteDialog = true }) { Text("Вставить свой текст", color = theme.accent) }
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

    if (pasteDialog) {
        AlertDialog(
            onDismissRequest = { pasteDialog = false },
            title = { Text("Вставить свой текст", color = Color.White) },
            text = {
                TextField(
                    value = pasteText,
                    onValueChange = { pasteText = it },
                    minLines = 6,
                    maxLines = 10,
                    placeholder = { Text("Строки текста (или LRC с [mm:ss.xx])", color = Color.White.copy(alpha = 0.4f)) },
                    colors = TextFieldDefaults.colors(
                        focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                        focusedContainerColor = Color.White.copy(alpha = 0.08f),
                        unfocusedContainerColor = Color.White.copy(alpha = 0.06f),
                        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pasteDialog = false
                    val t = track ?: return@TextButton
                    if (pasteText.isBlank()) {
                        Toast.makeText(context, "Пустой текст", Toast.LENGTH_SHORT).show()
                        return@TextButton
                    }
                    val synced = pasteText.contains(LRC_REGEX)
                    scope.launch { lyricsRepository.cacheLyrics(t, pasteText, synced) }
                    applyContent(pasteText)
                    searchOpen = false
                    Toast.makeText(
                        context,
                        if (synced) "Текст с таймкодами применён" else "Текст вставлен: можно синхронизировать",
                        Toast.LENGTH_SHORT
                    ).show()
                    if (!synced && PcSync.enabled(context)) runPcSync(showToast = true)
                }) { Text("Применить", color = theme.accent) }
            },
            dismissButton = {
                TextButton(onClick = { pasteDialog = false }) { Text("Отмена", color = Color.White.copy(alpha = 0.7f)) }
            },
            containerColor = Color(0xFF171021)
        )
    }

    if (hostDialog) {
        AlertDialog(
            onDismissRequest = { hostDialog = false },
            title = { Text("Синхронизация текстов", color = Color.White) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text("Свой сервер (пусто = публичный):", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
                    Spacer(Modifier.height(6.dp))
                    TextField(
                        value = hostInput,
                        onValueChange = { hostInput = it },
                        singleLine = true,
                        placeholder = { Text(CoolDb.PUBLIC_BASE, color = Color.White.copy(alpha = 0.4f)) },
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
                        Toast.makeText(context, if (ok) "Сервер доступен" else "Сервер не доступен", Toast.LENGTH_SHORT).show()
                        if (ok && rawContent != null && rawContent?.contains(LRC_REGEX) == false) runPcSync(showToast = true)
                    }
                }) { Text("Сохранить", color = theme.accent) }
            },
            dismissButton = {
                TextButton(onClick = { hostDialog = false }) { Text("Отмена", color = Color.White.copy(alpha = 0.7f)) }
            },
            containerColor = Color(0xFF171021)
        )
    }
}