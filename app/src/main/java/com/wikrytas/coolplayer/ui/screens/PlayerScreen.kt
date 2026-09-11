package com.wikrytas.coolplayer.ui.screens

import android.app.Activity
import android.content.res.Configuration
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import com.wikrytas.coolplayer.audio.EqualizerController
import com.wikrytas.coolplayer.data.AppSettings
import com.wikrytas.coolplayer.data.LyricsOffsetStore
import com.wikrytas.coolplayer.data.LyricsRepository
import com.wikrytas.coolplayer.data.ThemeRepository
import com.wikrytas.coolplayer.models.Track
import com.wikrytas.coolplayer.ui.PlayerViewModel
import com.wikrytas.coolplayer.ui.components.CoverImage
import com.wikrytas.coolplayer.ui.components.FlowingThemeBackground
import com.wikrytas.coolplayer.ui.components.LyricsCover
import com.wikrytas.coolplayer.ui.components.player.AuroraVisualizer
import com.wikrytas.coolplayer.ui.components.player.EqPanel
import com.wikrytas.coolplayer.ui.components.player.GlowSeekBar
import com.wikrytas.coolplayer.ui.components.player.PlayerControlsRow
import com.wikrytas.coolplayer.ui.components.player.Pressable
import com.wikrytas.coolplayer.ui.theme.PlayerTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.wikrytas.coolplayer.data.AppLogger

private const val TAG = "Player"

private fun formatTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val m = ms / 60000
    val s = (ms / 1000) % 60
    return String.format("%d:%02d", m, s)
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun PlayerScreen(
    viewModel: PlayerViewModel,
    theme: PlayerTheme,
    favorites: Set<Long>,
    lyricsRepository: LyricsRepository,
    onToggleFavorite: (Long) -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    val themeRepository = remember { ThemeRepository(context.applicationContext) }
    val appSettings by themeRepository.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val offsetStore = remember { LyricsOffsetStore(context.applicationContext) }

    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showLyricsInsteadOfCover by remember { mutableStateOf(false) }
    var lyricsMenuExpanded by remember { mutableStateOf(false) }
    var lyricsRefreshKey by remember { mutableIntStateOf(0) }
    var lyricsOffset by remember(state.selectedTrack?.id) { mutableLongStateOf(0L) }
    var isEmbedding by remember { mutableStateOf(false) }
    var rawLyricsContent by remember(state.selectedTrack?.id) { mutableStateOf<String?>(null) }
    var pendingEmbedTrack by remember { mutableStateOf<Track?>(null) }
    var pendingEmbedContent by remember { mutableStateOf<String?>(null) }
    var verticalDrag by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(isLandscape) {
        AppLogger.d(TAG, "orientation: ${if (isLandscape) "landscape" else "portrait"}")
    }

    LaunchedEffect(state.selectedTrack?.id) {
        val t = state.selectedTrack ?: return@LaunchedEffect
        lyricsOffset = offsetStore.getOffset(t.id)
        delay(400) // дебаунс: не дёргаем сеть при частых переключениях
        rawLyricsContent = lyricsRepository.resolveLyrics(t, useOnline = appSettings.autoLyricsOnline)
    }

    val writeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        AppLogger.i(TAG, "SAF write result: code=${result.resultCode}")
        val t = pendingEmbedTrack
        val content = pendingEmbedContent
        if (result.resultCode == Activity.RESULT_OK && t != null && content != null) {
            scope.launch {
                isEmbedding = true
                val success = lyricsRepository.retryEmbedWithPermission(t, content)
                isEmbedding = false
                Toast.makeText(
                    context,
                    if (success) "✅ Текст встроен в файл" else "❌ Не удалось встроить",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        pendingEmbedTrack = null
        pendingEmbedContent = null
    }

    val accent by animateColorAsState(theme.accent, label = "accent")
    val color1 by animateColorAsState(theme.backgroundTop, label = "bg1")
    val color2 by animateColorAsState(theme.backgroundBottom, label = "bg2")

    val isFavorite = state.selectedTrack?.id?.let { favorites.contains(it) } ?: false

    fun tryEmbed() {
        val t = state.selectedTrack ?: return
        val content = rawLyricsContent ?: run {
            Toast.makeText(context, "Текст не найден", Toast.LENGTH_SHORT).show()
            return
        }
        AppLogger.i(TAG, "embed requested: id=${t.id}, len=${content.length}")
        isEmbedding = true
        scope.launch {
            val result = lyricsRepository.embedInFile(t, content, true)
            isEmbedding = false
            when {
                result.success ->
                    Toast.makeText(context, "✅ Текст встроен в файл", Toast.LENGTH_SHORT).show()
                result.needsPermission && result.intentSender != null -> {
                    AppLogger.w(TAG, "embed needs SAF permission: id=${t.id}")
                    pendingEmbedTrack = t
                    pendingEmbedContent = content
                    runCatching {
                        writeLauncher.launch(IntentSenderRequest.Builder(result.intentSender).build())
                    }.onFailure {
                        Toast.makeText(context, "Не удалось запросить права", Toast.LENGTH_SHORT).show()
                    }
                }
                else ->
                    Toast.makeText(context, result.error ?: "Не удалось встроить", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun changeOffset(delta: Long) {
        val t = state.selectedTrack ?: return
        val newOffset = (lyricsOffset + delta).coerceIn(-10_000L, 10_000L)
        lyricsOffset = newOffset
        AppLogger.d(TAG, "lyrics offset: ${newOffset}ms (delta ${delta}ms)")
        scope.launch { offsetStore.setOffset(t.id, newOffset) }
    }

    val headerBlock: @Composable () -> Unit = {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Pressable(onClick = onOpenLibrary, modifier = Modifier.align(Alignment.Center).size(44.dp)) {
                Icon(
                    Icons.Default.KeyboardArrowDown,
                    contentDescription = "К библиотеке",
                    tint = Color.White.copy(alpha = 0.9f),
                    modifier = Modifier.size(28.dp)
                )
            }
            Pressable(onClick = onOpenSettings, modifier = Modifier.align(Alignment.CenterEnd).size(40.dp)) {
                Icon(
                    Icons.Default.Settings,
                    contentDescription = "Настройки",
                    tint = Color.White.copy(alpha = 0.9f),
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }

    val coverBlock: @Composable (Modifier) -> Unit = { mod ->
        AnimatedContent(
            targetState = showLyricsInsteadOfCover,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "cover_lyrics"
        ) { showLyrics ->
            Box(
                modifier = mod
                    .pointerInput(Unit) {
                        var acc = 0f
                        var consumed = false
                        detectHorizontalDragGestures(
                            onDragStart = {
                                acc = 0f
                                consumed = false
                            },
                            onDragEnd = { consumed = false },
                            onDragCancel = { consumed = false }
                        ) { change, dragAmount ->
                            change.consume()
                            acc += dragAmount
                            if (!consumed) {
                                when {
                                    acc < -120f -> {
                                        consumed = true
                                        AppLogger.d(TAG, "swipe next (gesture)")
                                        viewModel.next()
                                    }
                                    acc > 120f -> {
                                        consumed = true
                                        AppLogger.d(TAG, "swipe previous (gesture)")
                                        viewModel.previous()
                                    }
                                }
                            }
                        }
                    }
                    .combinedClickable(
                        onClick = {
                            showLyricsInsteadOfCover = !showLyricsInsteadOfCover
                            AppLogger.d(TAG, "cover tap: ${if (showLyricsInsteadOfCover) "lyrics shown" else "cover shown"}")
                        },
                        onLongClick = { lyricsMenuExpanded = true }
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (showLyrics) {
                    LyricsCover(
                        track = state.selectedTrack,
                        playerViewModel = viewModel,
                        theme = theme,
                        lyricsRepository = lyricsRepository,
                        refreshKey = lyricsRefreshKey,
                        onlineEnabled = appSettings.autoLyricsOnline,
                        offsetMs = lyricsOffset,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    CoverImage(
                        track = state.selectedTrack,
                        modifier = Modifier
                            .fillMaxSize()
                            .shadow(28.dp, RoundedCornerShape(20.dp), spotColor = Color.Black.copy(alpha = 0.6f))
                            .clip(RoundedCornerShape(20.dp)),
                        shape = RoundedCornerShape(20.dp)
                    )
                }
            }
        }
    }

    val metaBlock: @Composable () -> Unit = {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = state.selectedTrack?.title ?: "Выберите трек",
                    fontSize = 20.sp, fontWeight = FontWeight.Bold, fontStyle = FontStyle.Normal,
                    color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = state.selectedTrack?.artist ?: "Исполнитель",
                    fontSize = 14.sp, fontStyle = FontStyle.Normal,
                    color = Color.White.copy(alpha = 0.6f), maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            Pressable(
                onClick = {
                    state.selectedTrack?.id?.let { id ->
                        AppLogger.d(TAG, "favorite toggled: id=$id, was=${favorites.contains(id)}")
                        onToggleFavorite(id)
                    }
                },
                modifier = Modifier.size(44.dp)
            ) {
                Icon(
                    if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = "В избранное",
                    tint = if (isFavorite) Color(0xFFFF4081) else Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }

    val seekBlock: @Composable () -> Unit = {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                formatTime(if (state.duration > 0) state.position else 0),
                color = Color.White.copy(alpha = 0.55f), fontSize = 11.sp, fontStyle = FontStyle.Normal,
                modifier = Modifier.width(38.dp)
            )
            Box(Modifier.weight(1f)) {
                GlowSeekBar(
                    positionMs = state.position,
                    durationMs = state.duration,
                    accent = accent,
                    onSeekStart = {
                        AppLogger.d(TAG, "seek start")
                        viewModel.startSeek()
                    },
                    onSeek = { viewModel.seekToPosition(it) },
                    onSeekEnd = { viewModel.finishSeek(it) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Text(
                formatTime(state.duration),
                color = Color.White.copy(alpha = 0.55f), fontSize = 11.sp, fontStyle = FontStyle.Normal,
                modifier = Modifier.width(38.dp), textAlign = TextAlign.End
            )
        }
    }

    val controlsBlock: @Composable () -> Unit = {
        PlayerControlsRow(
            isPlaying = state.isPlaying,
            shuffleEnabled = state.shuffleEnabled,
            repeatMode = state.repeatMode,
            accent = accent,
            playIconTint = color1,
            onShuffle = { viewModel.toggleShuffle() },
            onPrevious = { viewModel.previous() },
            onPlayPause = { viewModel.playPause() },
            onNext = { viewModel.next() },
            onRepeat = { viewModel.cycleRepeat() }
        )
    }

    val visualizerBlock: @Composable (Modifier) -> Unit = { mod ->
        AuroraVisualizer(
            isPlaying = state.isPlaying,
            accent = accent,
            modifier = mod
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { verticalDrag = 0f },
                    onDragEnd = {
                        if (verticalDrag > 120f) {
                            AppLogger.d(TAG, "swipe down -> library")
                            onOpenLibrary()
                        }
                        verticalDrag = 0f
                    },
                    onDragCancel = { verticalDrag = 0f }
                ) { change, dragAmount ->
                    change.consume()
                    verticalDrag += dragAmount
                }
            }
    ) {
        FlowingThemeBackground(top = color1, bottom = color2, accent = accent)

        if (isLandscape) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
            ) {
                headerBlock()
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BoxWithConstraints(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        contentAlignment = Alignment.Center
                    ) {
                        val s = maxWidth.coerceAtMost(maxHeight * 0.95f)
                        coverBlock(Modifier.size(s))
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(start = 12.dp),
                        verticalArrangement = Arrangement.Center
                    ) {
                        visualizerBlock(Modifier.fillMaxWidth().height(36.dp))
                        Spacer(Modifier.height(6.dp))
                        metaBlock()
                        Spacer(Modifier.height(10.dp))
                        seekBlock()
                        Spacer(Modifier.height(10.dp))
                        controlsBlock()
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
            ) {
                headerBlock()

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    coverBlock(Modifier.fillMaxWidth(0.74f).aspectRatio(1f))
                }

                visualizerBlock(
                    Modifier
                        .fillMaxWidth()
                        .height(72.dp)
                        .padding(horizontal = 12.dp)
                )

                Spacer(Modifier.height(4.dp))

                Column(Modifier.padding(horizontal = 24.dp)) {
                    metaBlock()
                }

                Spacer(Modifier.height(10.dp))

                AnimatedVisibility(
                    visible = false, // EQ-панель открывается из настроек; на плеере не дублируем
                    enter = expandVertically(expandFrom = Alignment.Bottom) + fadeIn(),
                    exit = shrinkVertically(shrinkTowards = Alignment.Bottom) + fadeOut(),
                    modifier = Modifier.padding(horizontal = 24.dp)
                ) {
                    EqPanel(accent = accent)
                }

                Column(Modifier.padding(horizontal = 24.dp)) {
                    seekBlock()
                }

                Spacer(Modifier.height(16.dp))

                Column(Modifier.padding(horizontal = 28.dp)) {
                    controlsBlock()
                }

                Spacer(Modifier.height(16.dp))
            }
        }

        DropdownMenu(
            expanded = lyricsMenuExpanded,
            onDismissRequest = { lyricsMenuExpanded = false },
            modifier = Modifier.background(color = Color(0xFF171021), shape = RoundedCornerShape(16.dp))
        ) {
            DropdownMenuItem(
                text = { Text(if (isEmbedding) "Встраивание..." else "Встроить в песню", color = Color.White) },
                leadingIcon = { Icon(Icons.Default.Save, null, tint = accent) },
                enabled = !isEmbedding && rawLyricsContent != null,
                onClick = { lyricsMenuExpanded = false; tryEmbed() }
            )
            DropdownMenuItem(
                text = { Text("Найти текст в сети", color = Color.White) },
                leadingIcon = { Icon(Icons.Default.Refresh, null, tint = accent) },
                onClick = {
                    lyricsMenuExpanded = false
                    val t = state.selectedTrack ?: return@DropdownMenuItem
                    AppLogger.i(TAG, "menu: force lyrics search id=${t.id}")
                    scope.launch {
                        lyricsRepository.clearCache(t)
                        val content = lyricsRepository.resolveLyrics(t, forceOnline = true, useOnline = true)
                        rawLyricsContent = content
                        lyricsRefreshKey++
                        Toast.makeText(
                            context,
                            if (content != null) "✅ Текст найден" else "❌ Текст не найден в сети",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            )
            DropdownMenuItem(
                text = {
                    Text(
                        if (appSettings.autoLyricsOnline) "Автопоиск текстов: включён" else "Автопоиск текстов: выключен",
                        color = Color.White
                    )
                },
                onClick = {
                    lyricsMenuExpanded = false
                    val next = !appSettings.autoLyricsOnline
                    AppLogger.i(TAG, "auto-lyrics: ${if (next) "enabled" else "disabled"}")
                    scope.launch { themeRepository.saveAutoLyricsOnline(next) }
                }
            )
            HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
            DropdownMenuItem(
                text = { Text("Синхронизация: ${if (lyricsOffset >= 0) "+" else ""}$lyricsOffset мс (сброс)", color = Color.White.copy(alpha = 0.7f)) },
                onClick = {
                    lyricsMenuExpanded = false
                    val t = state.selectedTrack ?: return@DropdownMenuItem
                    lyricsOffset = 0L
                    AppLogger.d(TAG, "lyrics offset reset: id=${t.id}")
                    scope.launch { offsetStore.setOffset(t.id, 0L) }
                }
            )
            DropdownMenuItem(
                text = { Text("Текст раньше на 0.5 с", color = Color.White) },
                onClick = { changeOffset(-500L) }
            )
            DropdownMenuItem(
                text = { Text("Текст позже на 0.5 с", color = Color.White) },
                onClick = { changeOffset(+500L) }
            )
        }
    }
}