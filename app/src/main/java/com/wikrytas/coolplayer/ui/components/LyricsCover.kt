package com.wikrytas.coolplayer.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wikrytas.coolplayer.data.AppLogger
import com.wikrytas.coolplayer.data.LyricsCandidate
import com.wikrytas.coolplayer.data.LyricsRepository
import com.wikrytas.coolplayer.models.Track
import com.wikrytas.coolplayer.ui.PlayerViewModel
import com.wikrytas.coolplayer.ui.screens.LyricLine
import com.wikrytas.coolplayer.ui.screens.parseLrcContent
import com.wikrytas.coolplayer.ui.screens.parsePlainLyrics
import com.wikrytas.coolplayer.ui.theme.PlayerTheme
import kotlinx.coroutines.launch

private fun formatDurationShort(ms: Long?): String {
    if (ms == null || ms <= 0) return ""
    val m = ms / 60000
    val s = (ms / 1000) % 60
    return String.format("%d:%02d", m, s)
}

@Composable
fun LyricsCover(
    track: Track?,
    playerViewModel: PlayerViewModel,
    theme: PlayerTheme,
    lyricsRepository: LyricsRepository,
    refreshKey: Int = 0,
    onlineEnabled: Boolean = true,
    offsetMs: Long = 0L,
    onContentApplied: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()

    var lyricLines by remember(track?.id, refreshKey) { mutableStateOf<List<LyricLine>?>(null) }
    var isLoading by remember(track?.id, refreshKey) { mutableStateOf(true) }
    var notFound by remember(track?.id, refreshKey) { mutableStateOf(false) }

    var manualArtist by remember(track?.id) {
        mutableStateOf(track?.artist?.takeIf { !it.contains("unknown", true) } ?: "")
    }
    var manualTitle by remember(track?.id) { mutableStateOf(track?.title ?: "") }
    var manualSearching by remember { mutableStateOf(false) }
    var syncedOnly by remember(track?.id) { mutableStateOf(false) }
    var candidates by remember(track?.id) { mutableStateOf<List<LyricsCandidate>>(emptyList()) }

    val uiState by playerViewModel.uiState.collectAsStateWithLifecycle()
    val currentPosition = uiState.position

    fun applyContent(content: String) {
        lyricLines = if (content.contains(Regex("\\[\\d{2}:\\d{2}"))) {
            parseLrcContent(content)
        } else {
            parsePlainLyrics(content)
        }
        notFound = false
        isLoading = false
        onContentApplied?.invoke(content)
    }

    LaunchedEffect(track?.id, refreshKey) {
        isLoading = true
        notFound = false
        lyricLines = null

        val t = track ?: run {
            isLoading = false
            return@LaunchedEffect
        }

        val content = lyricsRepository.resolveLyrics(t, useOnline = onlineEnabled)
        if (content != null && content.isNotBlank()) {
            applyContent(content)
        } else {
            isLoading = false
            notFound = true
        }
    }

    // FIX: режим выбираем по наличию таймкодов, а не по текущему индексу.
    val isSynced = remember(lyricLines) { lyricLines?.any { it.timeMs > 0L } == true }

    val currentLineIndex = remember(lyricLines, currentPosition, offsetMs) {
        lyricLines?.let { lines ->
            if (lines.isEmpty()) return@let -1
            if (lines.all { it.timeMs == 0L }) return@let -1
            val shifted = currentPosition + offsetMs
            var left = 0
            var right = lines.size - 1
            var result = -1
            while (left <= right) {
                val mid = (left + right) / 2
                if (lines[mid].timeMs <= shifted) {
                    result = mid
                    left = mid + 1
                } else {
                    right = mid - 1
                }
            }
            result
        } ?: -1
    }

    // FIX: до первого таймкода не проваливаемся в plain, а держим первую строку активной
    val activeIndex = if (isSynced) currentLineIndex.coerceAtLeast(0) else -1

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .clipToBounds()
            .background(
                brush = Brush.linearGradient(
                    listOf(
                        theme.backgroundTop.copy(alpha = 0.95f),
                        theme.backgroundBottom.copy(alpha = 0.95f)
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        when {
            isLoading -> {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = theme.accent, modifier = Modifier.size(32.dp))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Поиск текста...", color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)
                }
            }

            notFound && track != null -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "Текст не найден",
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    TextField(
                        value = manualArtist,
                        onValueChange = { manualArtist = it },
                        singleLine = true,
                        placeholder = { Text("Исполнитель", color = Color.White.copy(alpha = 0.4f), fontSize = 12.sp) },
                        colors = TextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedContainerColor = Color.White.copy(alpha = 0.08f),
                            unfocusedContainerColor = Color.White.copy(alpha = 0.06f),
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    TextField(
                        value = manualTitle,
                        onValueChange = { manualTitle = it },
                        singleLine = true,
                        placeholder = { Text("Название", color = Color.White.copy(alpha = 0.4f), fontSize = 12.sp) },
                        colors = TextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedContainerColor = Color.White.copy(alpha = 0.08f),
                            unfocusedContainerColor = Color.White.copy(alpha = 0.06f),
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(
                            modifier = Modifier
                                .size(16.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (syncedOnly) theme.accent else Color.White.copy(alpha = 0.15f))
                                .clickable { syncedOnly = !syncedOnly },
                            contentAlignment = Alignment.Center
                        ) {
                            if (syncedOnly) {
                                Text("✓", color = theme.backgroundTop, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "Только с таймкодами (караоке)",
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 11.sp,
                            modifier = Modifier.clickable { syncedOnly = !syncedOnly }
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    if (manualSearching) {
                        CircularProgressIndicator(color = theme.accent, modifier = Modifier.size(24.dp))
                    } else {
                        Text(
                            "НАЙТИ",
                            color = theme.backgroundTop,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .background(theme.accent)
                                .clickable {
                                    manualSearching = true
                                    candidates = emptyList()
                                    scope.launch {
                                        runCatching {
                                            candidates = lyricsRepository.searchByQuery(
                                                manualArtist.trim(),
                                                manualTitle.trim(),
                                                syncedOnly = syncedOnly
                                            )
                                        }.onFailure {
                                            AppLogger.w("LyricsCover", "manual search failed: ${it.message}")
                                            candidates = emptyList()
                                        }
                                        manualSearching = false
                                    }
                                }
                                .padding(horizontal = 28.dp, vertical = 8.dp)
                        )
                    }
                    if (candidates.isEmpty() && !manualSearching) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            if (syncedOnly) "Синхронизированный текст не найден — попробуйте без фильтра"
                            else "Ничего не найдено — попробуйте изменить запрос",
                            color = Color.White.copy(alpha = 0.45f),
                            fontSize = 11.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    candidates.forEach { c ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color.White.copy(alpha = 0.07f))
                                .clickable {
                                    scope.launch {
                                        runCatching {
                                            lyricsRepository.cacheLyrics(track, c.content, c.synced)
                                            if (c.source != "lrclib") {
                                                lyricsRepository.publishToLrclib(track, c.content)
                                            }
                                            applyContent(c.content)
                                        }.onFailure {
                                            AppLogger.w("LyricsCover", "apply candidate failed: ${it.message}")
                                        }
                                    }
                                }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "${c.artist} — ${c.title}",
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            val dur = formatDurationShort(c.durationMs)
                            Text(
                                if (c.synced) "SYNCED" else if (dur.isNotEmpty()) dur else "PLAIN",
                                color = if (c.synced) theme.accent else Color.White.copy(alpha = 0.5f),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            lyricLines.isNullOrEmpty() -> {
                Text(
                    text = "Текст пуст",
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center
                )
            }

            else -> {
                val lines = lyricLines!!
                if (isSynced) {
                    // FIX: караоке сразу, даже если позиция ещё до первого таймкода
                    val centerIdx = activeIndex
                    val prevLine = if (centerIdx > 0) lines[centerIdx - 1] else null
                    val activeLine = lines[centerIdx]
                    val nextLine = if (centerIdx + 1 < lines.size) lines[centerIdx + 1] else null

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(32.dp)
                                .then(
                                    if (prevLine != null && prevLine.timeMs > 0) {
                                        Modifier.clickable { playerViewModel.seekTo(prevLine.timeMs) }
                                    } else Modifier
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            prevLine?.let { line ->
                                Text(
                                    text = line.text,
                                    color = Color.White.copy(alpha = 0.25f),
                                    fontSize = 11.sp,
                                    textAlign = TextAlign.Center,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        AnimatedContent(
                            targetState = activeLine.text,
                            transitionSpec = {
                                slideInVertically(initialOffsetY = { it / 3 }, animationSpec = tween(300)) +
                                    fadeIn(animationSpec = tween(300)) togetherWith
                                    slideOutVertically(targetOffsetY = { -it / 3 }, animationSpec = tween(300)) +
                                    fadeOut(animationSpec = tween(300))
                            },
                            label = "active_line_transition"
                        ) { text ->
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp, max = 72.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                if (text.isNotEmpty()) {
                                    Text(
                                        text = text,
                                        color = theme.accent,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold,
                                        textAlign = TextAlign.Center,
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis,
                                        lineHeight = 21.sp,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(32.dp)
                                .then(
                                    if (nextLine != null && nextLine.timeMs > 0) {
                                        Modifier.clickable { playerViewModel.seekTo(nextLine.timeMs) }
                                    } else Modifier
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            nextLine?.let { line ->
                                Text(
                                    text = line.text,
                                    color = Color.White.copy(alpha = 0.35f),
                                    fontSize = 11.sp,
                                    textAlign = TextAlign.Center,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        lines.forEach { line ->
                            Text(
                                text = line.text,
                                color = Color.White.copy(alpha = 0.75f),
                                fontSize = 14.sp,
                                lineHeight = 22.sp,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp)
                            )
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .height(24.dp)
                        .background(Brush.verticalGradient(listOf(theme.backgroundTop, Color.Transparent)))
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(24.dp)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, theme.backgroundBottom)))
                )
            }
        }
    }
}