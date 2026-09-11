package com.wikrytas.coolplayer.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wikrytas.coolplayer.data.AppLogger
import com.wikrytas.coolplayer.data.StatsRepository
import com.wikrytas.coolplayer.models.SortMode
import com.wikrytas.coolplayer.models.Track
import com.wikrytas.coolplayer.ui.PlayerViewModel
import com.wikrytas.coolplayer.ui.components.CoverImage
import com.wikrytas.coolplayer.ui.components.FlowingThemeBackground
import com.wikrytas.coolplayer.ui.theme.PlayerTheme

private const val TAG = "Library"

private enum class LibraryFilter(val label: String) {
    ALL("Все"),
    FAVORITES("Избранное"),
    FREQUENT("Часто"),
    NEW("Новые"),
    OLD("Давние")
}

private const val MONTH_MS = 30L * 24 * 60 * 60 * 1000
private const val HALF_YEAR_MS = 180L * 24 * 60 * 60 * 1000

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(
    tracks: List<Track>,
    theme: PlayerTheme,
    playerViewModel: PlayerViewModel,
    favorites: Set<Long>,
    sortMode: SortMode,
    onSortModeChange: (SortMode) -> Unit,
    onToggleFavorite: (Long) -> Unit,
    onBack: () -> Unit,
    onOpenPlayer: () -> Unit,
    onLibraryChanged: () -> Unit
) {
    val context = LocalContext.current
    val state by playerViewModel.uiState.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(LibraryFilter.ALL) }
    var sortMenu by remember { mutableStateOf(false) }
    var menuTrack by remember { mutableStateOf<Track?>(null) }
    var verticalDrag by remember { mutableFloatStateOf(0f) }

    val listState = rememberLazyListState()

    val statsRepository = remember { StatsRepository(context.applicationContext) }
    val playCounts by statsRepository.countsFlow.collectAsStateWithLifecycle(initialValue = emptyMap())

    val displayed = remember(tracks, favorites, filter, query, sortMode, playCounts) {
        val now = System.currentTimeMillis()
        var list = tracks
        list = when (filter) {
            LibraryFilter.ALL -> list
            LibraryFilter.FAVORITES -> list.filter { favorites.contains(it.id) }
            LibraryFilter.FREQUENT -> list
                .filter { (playCounts[it.id] ?: 0) > 0 }
                .sortedByDescending { playCounts[it.id] ?: 0 }
            LibraryFilter.NEW -> list.filter { it.dateAdded > 0 && now - it.dateAdded < MONTH_MS }
            LibraryFilter.OLD -> list.filter { it.dateAdded > 0 && now - it.dateAdded > HALF_YEAR_MS }
        }
        if (query.isNotBlank()) {
            list = list.filter {
                it.title.contains(query, ignoreCase = true) ||
                it.artist.contains(query, ignoreCase = true)
            }
        }
        if (filter != LibraryFilter.FREQUENT) {
            list = when (sortMode) {
                SortMode.TITLE -> list.sortedBy { it.title.lowercase() }
                SortMode.ARTIST -> list.sortedBy { it.artist.lowercase() }
                else -> list.sortedBy { it.title.lowercase() }
            }
        }
        list
    }

    val sections = remember(displayed, sortMode, filter) {
        val useLetters = filter != LibraryFilter.FREQUENT &&
            (sortMode == SortMode.TITLE || sortMode == SortMode.ARTIST)
        if (useLetters) {
            displayed.groupBy { t ->
                val src = if (sortMode == SortMode.TITLE) t.title else t.artist
                val ch = src.firstOrNull()?.uppercaseChar()
                if (ch != null && (ch in 'A'..'Z' || ch in 'А'..'Я' || ch == 'Ё')) ch.toString() else "#"
            }.toList()
        } else {
            listOf("" to displayed)
        }
    }

    var lastScrolledId by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(state.selectedTrack?.id) {
        val id = state.selectedTrack?.id ?: return@LaunchedEffect
        if (lastScrolledId == id) return@LaunchedEffect
        lastScrolledId = id
        val idx = displayed.indexOfFirst { it.id == id }
        if (idx >= 0) {
            AppLogger.d(TAG, "autoscroll to current: idx=$idx, id=$id")
            listState.scrollToItem(idx)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(theme.backgroundTop, theme.backgroundBottom)))
    ) {
        FlowingThemeBackground(top = theme.backgroundTop, bottom = theme.backgroundBottom, accent = theme.accent)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp)
                    .pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onDragStart = { verticalDrag = 0f },
                            onDragEnd = {
                                if (verticalDrag < -100f) {
                                    AppLogger.d(TAG, "swipe up -> player")
                                    onOpenPlayer()
                                }
                                verticalDrag = 0f
                            },
                            onDragCancel = { verticalDrag = 0f }
                        ) { change, dragAmount ->
                            change.consume()
                            verticalDrag += dragAmount
                        }
                    },
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, "Назад", tint = Color.White, modifier = Modifier.size(24.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("Библиотека", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, fontStyle = FontStyle.Normal)
                    Text("${displayed.size} треков", color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp, fontStyle = FontStyle.Normal)
                }
                Box {
                    IconButton(onClick = { sortMenu = true }) {
                        Icon(Icons.Default.Sort, "Сортировка", tint = theme.accent, modifier = Modifier.size(22.dp))
                    }
                    DropdownMenu(
                        expanded = sortMenu,
                        onDismissRequest = { sortMenu = false },
                        modifier = Modifier.background(color = Color(0xFF171021), shape = RoundedCornerShape(16.dp))
                    ) {
                        SortMode.entries.forEach { mode ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        mode.name.lowercase().replaceFirstChar { it.uppercase() }.replace('_', ' '),
                                        color = if (mode == sortMode) theme.accent else Color.White
                                    )
                                },
                                onClick = {
                                    sortMenu = false
                                    AppLogger.d(TAG, "sort changed: $mode")
                                    onSortModeChange(mode)
                                }
                            )
                        }
                    }
                }
            }

            TextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Поиск", color = Color.White.copy(alpha = 0.4f), fontStyle = FontStyle.Normal) },
                leadingIcon = { Icon(Icons.Default.Search, null, tint = Color.White.copy(alpha = 0.5f), modifier = Modifier.size(20.dp)) },
                singleLine = true,
                colors = TextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedContainerColor = Color.White.copy(alpha = 0.08f),
                    unfocusedContainerColor = Color.White.copy(alpha = 0.06f),
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                ),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .height(48.dp)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LibraryFilter.entries.forEach { f ->
                    val active = filter == f
                    val count = when (f) {
                        LibraryFilter.ALL -> tracks.size
                        LibraryFilter.FAVORITES -> favorites.size
                        LibraryFilter.FREQUENT -> playCounts.size
                        else -> -1
                    }
                    Text(
                        text = if (count >= 0) "${f.label} · $count" else f.label,
                        color = if (active) theme.backgroundTop else Color.White.copy(alpha = 0.75f),
                        fontSize = 13.sp,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                        fontStyle = FontStyle.Normal,
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(if (active) theme.accent else Color.White.copy(alpha = 0.08f))
                            .clickable {
                                filter = f
                                AppLogger.d(TAG, "filter changed: $f")
                            }
                            .padding(horizontal = 16.dp, vertical = 7.dp)
                    )
                }
            }

            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(bottom = 16.dp, top = 4.dp)
            ) {
                sections.forEach { (letter, items) ->
                    if (letter.isNotEmpty()) {
                        stickyHeader(key = "header_$letter") {
                            Text(
                                text = letter,
                                color = theme.accent,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                fontStyle = FontStyle.Normal,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(theme.backgroundTop.copy(alpha = 0.92f))
                                    .padding(horizontal = 20.dp, vertical = 4.dp)
                            )
                        }
                    }
                    items(items, key = { it.id }) { track ->
                        val isCurrent = state.selectedTrack?.id == track.id
                        val plays = playCounts[track.id] ?: 0
                        Box {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(
                                        if (isCurrent) theme.accent.copy(alpha = 0.12f) else Color.Transparent
                                    )
                                    .combinedClickable(
                                        onClick = {
                                            AppLogger.i(TAG, "track tap: id=${track.id}, title='${track.title}'")
                                            playerViewModel.playTrack(track, displayed)
                                            onOpenPlayer()
                                        },
                                        onLongClick = { menuTrack = track }
                                    )
                                    .padding(horizontal = 8.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CoverImage(
                                    track = track,
                                    modifier = Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)),
                                    shape = RoundedCornerShape(10.dp)
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        track.title,
                                        color = if (isCurrent) theme.accent else Color.White,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Medium,
                                        fontStyle = FontStyle.Normal,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        track.artist,
                                        color = Color.White.copy(alpha = 0.55f),
                                        fontSize = 12.sp,
                                        fontStyle = FontStyle.Normal,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                if (plays > 0) {
                                    Text(
                                        "▶ $plays",
                                        color = Color.White.copy(alpha = 0.35f),
                                        fontSize = 10.sp,
                                        fontStyle = FontStyle.Normal,
                                        modifier = Modifier.padding(end = 6.dp)
                                    )
                                }
                                if (isCurrent) {
                                    Icon(
                                        Icons.Default.PlayArrow,
                                        null,
                                        tint = theme.accent,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }

                            DropdownMenu(
                                expanded = menuTrack?.id == track.id,
                                onDismissRequest = { menuTrack = null },
                                modifier = Modifier.background(color = Color(0xFF171021), shape = RoundedCornerShape(16.dp))
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Играть", color = Color.White) },
                                    onClick = {
                                        menuTrack = null
                                        AppLogger.i(TAG, "menu play: id=${track.id}")
                                        playerViewModel.playTrack(track, displayed)
                                        onOpenPlayer()
                                    }
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            if (favorites.contains(track.id)) "Убрать из избранного" else "В избранное",
                                            color = Color.White
                                        )
                                    },
                                    onClick = {
                                        menuTrack = null
                                        AppLogger.d(TAG, "menu favorite: id=${track.id}")
                                        onToggleFavorite(track.id)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}