package com.wikrytas.coolplayer.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wikrytas.coolplayer.data.LyricsCandidate
import com.wikrytas.coolplayer.data.LyricsRepository
import com.wikrytas.coolplayer.models.Track
import com.wikrytas.coolplayer.ui.PlayerViewModel
import com.wikrytas.coolplayer.ui.theme.PlayerTheme
import kotlinx.coroutines.launch

data class LyricLine(
    val timeMs: Long,
    val text: String
)

/**
 * Парсит LRC-контент с поддержкой разных форматов таймкодов:
 * [00:12.34], [00:12.345], [00:12], [00:12.34][00:45.67]текст
 */
fun parseLrcContent(content: String): List<LyricLine> {
    val lines = mutableListOf<LyricLine>()
    val timePattern = Regex("""\[(\d{2}):(\d{2})(?:[.:](\d{2,3}))?\]""")
    content.lines().forEach { line ->
        val matches = timePattern.findAll(line).toList()
        if (matches.isNotEmpty()) {
            val lastMatch = matches.last()
            val text = line.substring(lastMatch.range.last + 1).trim()
            if (text.isNotBlank()) {
                matches.forEach { match ->
                    val minutes = match.groupValues[1].toLong()
                    val seconds = match.groupValues[2].toLong()
                    val milliseconds = match.groupValues.getOrNull(3) ?: "0"
                    val timeMs = minutes * 60000 +
                            seconds * 1000 +
                            when (milliseconds.length) {
                                2 -> milliseconds.toLong() * 10
                                3 -> milliseconds.toLong()
                                else -> 0L
                            }
                    lines.add(LyricLine(timeMs, text))
                }
            }
        }
    }
    return lines.distinctBy { it.timeMs }.sortedBy { it.timeMs }
}

/** Парсит обычный текст без таймкодов. */
fun parsePlainLyrics(content: String): List<LyricLine> {
    return content.lines()
        .mapNotNull { line ->
            val cleaned = line.replace(Regex("""\[\d{2}:\d{2}\.\d{2,3}]"""), "").trim()
            if (cleaned.isNotBlank()) LyricLine(0L, cleaned) else null
        }
}

@Composable
fun LyricsScreen(
    track: Track?,
    theme: PlayerTheme,
    playerViewModel: PlayerViewModel,
    lyricsRepository: LyricsRepository,
    onBack: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    var lyricLines by remember(track?.id) { mutableStateOf<List<LyricLine>?>(null) }
    var rawContent by remember(track?.id) { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var source by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var notFound by remember(track?.id) { mutableStateOf(false) }
    var isCached by remember(track?.id) { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    var isEmbedding by remember { mutableStateOf(false) }

    // --- Уровень 3: ручной поиск ---
    var showManualSearch by remember(track?.id) { mutableStateOf(false) }
    var manualQuery by remember(track?.id) {
        mutableStateOf(
            (track?.artist?.takeIf { it.isNotBlank() } ?: "") +
                (if (track?.artist?.isNotBlank() == true && track.title.isNotBlank()) " " else "") +
                (track?.title ?: "")
        )
    }
    var manualCandidates by remember(track?.id) { mutableStateOf<List<LyricsCandidate>>(emptyList()) }
    var isManualSearching by remember { mutableStateOf(false) }
    var manualError by remember(track?.id) { mutableStateOf<String?>(null) }

    val uiState by playerViewModel.uiState.collectAsState()
    val currentPosition = uiState.position
    val listState = rememberLazyListState()

    fun applyContent(content: String, newSource: String) {
        rawContent = content
        lyricLines = if (content.contains(Regex("""\[\d{2}:\d{2}"""))) {
            parseLrcContent(content)
        } else {
            parsePlainLyrics(content)
        }
        source = newSource
        errorMessage = null
        notFound = false
        showManualSearch = false
        manualError = null
    }

    suspend fun loadLyrics(forceRefresh: Boolean = false) {
        isLoading = true
        errorMessage = null
        notFound = false
        isCached = false

        val t = track ?: run {
            isLoading = false
            errorMessage = "Нет трека"
            return
        }

        if (!forceRefresh) {
            val cached = lyricsRepository.getCachedLyrics(t)
            if (cached != null) {
                applyContent(cached, "кэш")
                isCached = true
                isLoading = false
                return
            }
            val embedded = lyricsRepository.readEmbeddedLyrics(t)
            if (embedded != null) {
                applyContent(embedded, "встроенный")
                isCached = true
                isLoading = false
                return
            }
            val local = lyricsRepository.readLocalLrc(t)
            if (local != null) {
                applyContent(local, "локальный .lrc")
                isCached = true
                isLoading = false
                return
            }
        } else {
            lyricsRepository.clearCache(t)
        }

        val content = lyricsRepository.resolveLyrics(t, forceRefresh)
        if (content != null) {
            applyContent(content, "сеть")
        } else {
            lyricLines = null
            rawContent = null
            notFound = true
            errorMessage = "Текст не найден автоматически"
        }
        isLoading = false
    }

    LaunchedEffect(track?.id) {
        loadLyrics()
    }

    val currentLineIndex = remember(lyricLines, currentPosition) {
        lyricLines?.let { lines ->
            if (lines.isEmpty()) return@let -1
            if (lines.all { it.timeMs == 0L }) return@let -1 // плоский текст — не подсвечиваем
            var left = 0
            var right = lines.size - 1
            var result = -1
            while (left <= right) {
                val mid = (left + right) / 2
                if (lines[mid].timeMs <= currentPosition) {
                    result = mid
                    left = mid + 1
                } else {
                    right = mid - 1
                }
            }
            result
        } ?: -1
    }

    LaunchedEffect(currentLineIndex, lyricLines) {
        if (currentLineIndex >= 0 && lyricLines != null) {
            val targetIndex = (currentLineIndex - 3).coerceAtLeast(0)
            listState.animateScrollToItem(targetIndex)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(theme.backgroundTop, theme.backgroundBottom)))
    ) {
        // Заголовок
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Назад", tint = Color.White)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Текст песни",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                if (source.isNotBlank() && !isLoading && !notFound) {
                    Text(
                        text = "Источник: $source${if (isCached) " (локально)" else ""}",
                        color = theme.accent.copy(alpha = 0.7f),
                        fontSize = 11.sp
                    )
                }
            }
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Меню", tint = Color.White)
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                    modifier = Modifier.background(theme.backgroundBottom)
                ) {
                    DropdownMenuItem(
                        text = { Text("Обновить из сети", color = Color.White) },
                        leadingIcon = { Icon(Icons.Default.Refresh, null, tint = theme.accent) },
                        onClick = {
                            menuExpanded = false
                            scope.launch { loadLyrics(forceRefresh = true) }
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Искать вручную", color = Color.White) },
                        leadingIcon = { Icon(Icons.Default.Search, null, tint = theme.accent) },
                        onClick = {
                            menuExpanded = false
                            showManualSearch = true
                            notFound = false
                            errorMessage = null
                        }
                    )
                }
            }
        }

        // Информация о треке
        if (track != null && !isLoading && !notFound) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = track.title,
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    maxLines = 1
                )
                Text(
                    text = track.artist,
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 1
                )
            }
        }

        // --- Экран ручного поиска ---
        if (showManualSearch) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                Text(
                    text = "Ручной поиск текста",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = manualQuery,
                    onValueChange = { manualQuery = it },
                    label = { Text("Исполнитель и название", color = Color.White.copy(alpha = 0.5f)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        scope.launch {
                            if (manualQuery.isBlank()) {
                                manualError = "Введите запрос"
                                return@launch
                            }
                            isManualSearching = true
                            manualError = null
                            manualCandidates = emptyList()
                            val found = lyricsRepository.searchLyricsCandidates(manualQuery)
                            manualCandidates = found
                            if (found.isEmpty()) manualError = "Ничего не найдено. Попробуйте другой запрос."
                            isManualSearching = false
                        }
                    }),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = theme.accent,
                        unfocusedBorderColor = Color.White.copy(alpha = 0.3f),
                        cursorColor = theme.accent
                    )
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(onClick = {
                        scope.launch {
                            if (manualQuery.isBlank()) {
                                manualError = "Введите запрос"
                                return@launch
                            }
                            isManualSearching = true
                            manualError = null
                            manualCandidates = emptyList()
                            val found = lyricsRepository.searchLyricsCandidates(manualQuery)
                            manualCandidates = found
                            if (found.isEmpty()) manualError = "Ничего не найдено. Попробуйте другой запрос."
                            isManualSearching = false
                        }
                    }) {
                        Text("Найти", color = theme.accent, fontWeight = FontWeight.SemiBold)
                    }
                    TextButton(onClick = {
                        showManualSearch = false
                        manualError = null
                        if (lyricLines == null) notFound = true
                    }) {
                        Text("Отмена", color = Color.White.copy(alpha = 0.6f))
                    }
                }
                if (isManualSearching) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = theme.accent, modifier = Modifier.size(28.dp))
                    }
                }
                if (manualError != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = manualError!!,
                        color = Color(0xFFFF8A80),
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (manualCandidates.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Найдено вариантов: ${manualCandidates.size}. Выберите подходящий.",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f, fill = false)) {
                        itemsIndexed(manualCandidates) { _, cand ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .background(
                                        Color.White.copy(alpha = 0.06f),
                                        androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
                                    )
                                    .clickable {
                                        val t = track
                                        scope.launch {
                                            if (t != null) {
                                                lyricsRepository.cacheLyrics(t, cand.content, cand.synced)
                                            }
                                            applyContent(cand.content, cand.source)
                                        }
                                    }
                                    .padding(12.dp)
                            ) {
                                Text(
                                    text = "${cand.artist} — ${cand.title}",
                                    color = Color.White,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = cand.source + if (cand.synced) " · синхронизированный" else "",
                                    color = theme.accent.copy(alpha = 0.8f),
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                }
            }
            return@Column
        }

        // Основной контент
        Box(modifier = Modifier.weight(1f)) {
            when {
                isLoading -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = theme.accent, modifier = Modifier.size(40.dp))
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "Ищем текст песни...",
                                color = Color.White.copy(alpha = 0.5f),
                                fontSize = 15.sp
                            )
                        }
                    }
                }
                notFound -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = errorMessage ?: "Текст не найден",
                                color = Color(0xFFFF8A80),
                                fontSize = 16.sp,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 32.dp)
                            )
                            Spacer(modifier = Modifier.height(20.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                TextButton(onClick = { scope.launch { loadLyrics(forceRefresh = true) } }) {
                                    Text("Повторить", color = theme.accent, fontWeight = FontWeight.SemiBold)
                                }
                                TextButton(onClick = {
                                    showManualSearch = true
                                    notFound = false
                                    errorMessage = null
                                }) {
                                    Text("Искать вручную", color = theme.accent, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }
                lyricLines.isNullOrEmpty() -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = "Текст пуст",
                            color = Color.White.copy(alpha = 0.5f),
                            fontSize = 15.sp
                        )
                    }
                }
                else -> {
                    val lines = lyricLines!!
                    val isSyncedText = lines.any { it.timeMs > 0L }
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 24.dp),
                        contentPadding = PaddingValues(vertical = 16.dp)
                    ) {
                        itemsIndexed(lines) { index, line ->
                            val isActive = isSyncedText && index == currentLineIndex
                            val isPast = isSyncedText && index < currentLineIndex
                            val textColor by animateColorAsState(
                                targetValue = when {
                                    isActive -> Color.White
                                    isPast -> Color.White.copy(alpha = 0.35f)
                                    else -> Color.White.copy(alpha = if (isSyncedText) 0.6f else 0.85f)
                                },
                                label = "textColor_$index"
                            )
                            Text(
                                text = line.text,
                                color = textColor,
                                fontSize = if (isActive) 20.sp else 16.sp,
                                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                                lineHeight = if (isActive) 30.sp else 26.sp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}