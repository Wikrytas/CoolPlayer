package com.wikrytas.coolplayer.ui.screens

import android.app.Activity
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.wikrytas.coolplayer.data.LibraryPrefs
import com.wikrytas.coolplayer.data.StatsRepository
import com.wikrytas.coolplayer.data.ThemeRepository
import com.wikrytas.coolplayer.models.SortMode
import com.wikrytas.coolplayer.models.Track
import com.wikrytas.coolplayer.ui.PlayerViewModel
import com.wikrytas.coolplayer.ui.components.CoverImage
import com.wikrytas.coolplayer.ui.components.FlowingThemeBackground
import com.wikrytas.coolplayer.ui.theme.PlayerTheme
import kotlinx.coroutines.launch

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

private fun SortMode.label(): String = when (this) {
    SortMode.TITLE -> "По названию"
    SortMode.ARTIST -> "По исполнителю"
    SortMode.DURATION -> "По длительности"
    SortMode.DATE_ADDED -> "Сначала новые"
}

private fun formatDuration(ms: Long): String {
    if (ms <= 0) return "0:00"
    val m = ms / 60000
    val s = (ms / 1000) % 60
    return String.format("%d:%02d", m, s)
}

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
    onOpenLyrics: (Track) -> Unit,
    onBack: () -> Unit,
    onOpenPlayer: () -> Unit,
    onLibraryChanged: () -> Unit
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val scope = rememberCoroutineScope()
    val themeRepository = remember { ThemeRepository(appContext) }
    val autoScroll = remember { LibraryPrefs.autoScrollEnabled(appContext) }
    val state by playerViewModel.uiState.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var searchOpen by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf(LibraryFilter.ALL) }
    var sortMenu by remember { mutableStateOf(false) }
    var menuTrack by remember { mutableStateOf<Track?>(null) }
    var deleteConfirm by remember { mutableStateOf<Track?>(null) }
    var pendingDeleteUri by remember { mutableStateOf<Uri?>(null) }
    var verticalDrag by remember { mutableFloatStateOf(0f) }
    val listState = rememberLazyListState()

    val statsRepository = remember { StatsRepository(appContext) }
    val playCounts by statsRepository.countsFlow.collectAsStateWithLifecycle(initialValue = emptyMap())

    val deleteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val uri = pendingDeleteUri
        pendingDeleteUri = null
        if (result.resultCode == Activity.RESULT_OK && uri != null) {
            scope.launch {
                val removed = runCatching { context.contentResolver.delete(uri, null, null) }.getOrDefault(0)
                AppLogger.i(TAG, "delete after system grant: removed=$removed")
                Toast.makeText(context, "Трек удалён с устройства", Toast.LENGTH_SHORT).show()
                onLibraryChanged()
            }
        } else {
            Toast.makeText(context, "Удаление отменено", Toast.LENGTH_SHORT).show()
        }
    }

    fun deleteFromDevice(track: Track) {
        scope.launch {
            try {
                val removed = context.contentResolver.delete(track.uri, null, null)
                if (removed > 0) {
                    AppLogger.i(TAG, "deleted from device: id=${track.id}")
                    Toast.makeText(context, "Трек удалён с устройства", Toast.LENGTH_SHORT).show()
                    onLibraryChanged()
                } else {
                    Toast.makeText(context, "Не удалось удалить файл", Toast.LENGTH_SHORT).show()
                }
            } catch (e: SecurityException) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    runCatching {
                        val pi = MediaStore.createDeleteRequest(context.contentResolver, listOf(track.uri))
                        pendingDeleteUri = track.uri
                        deleteLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
                    }.onFailure {
                        Toast.makeText(context, "Не удалось запросить удаление", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(context, "Нет прав на удаление файла", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "delete failed: id=${track.id}", e)
                Toast.makeText(context, "Ошибка удаления", Toast.LENGTH_SHORT).show()
            }
        }
    }

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
                SortMode.DURATION -> list.sortedBy { it.duration }
                SortMode.DATE_ADDED -> list.sortedByDescending { it.dateAdded }
            }
        }
        list
    }

    // Автофокус: один раз при входе, только если включён в настройках
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(displayed.isNotEmpty(), autoScroll) {
        if (!autoScroll || entered || displayed.isEmpty()) return@LaunchedEffect
        entered = true
        val id = state.selectedTrack?.id ?: return@LaunchedEffect
        val idx = displayed.indexOfFirst { it.id == id }
        if (idx >= 0) {
            AppLogger.d(TAG, "enter autoscroll: idx=$idx, id=$id")
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
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад", tint = Color.White, modifier = Modifier.size(24.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("Библиотека", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, fontStyle = FontStyle.Normal)
                    Text(
                        "${displayed.size} треков · ${sortMode.label()}",
                        color = Color.White.copy(alpha = 0.5f),
                        fontSize = 12.sp,
                        fontStyle = FontStyle.Normal
                    )
                }
                // Поиск раскрывается по лупе
                IconButton(onClick = { searchOpen = !searchOpen }) {
                    Icon(
                        Icons.Default.Search,
                        "Поиск",
                        tint = if (searchOpen) theme.accent else Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.size(22.dp)
                    )
                }
                Box {
                    IconButton(onClick = { sortMenu = true }) {
                        Icon(Icons.AutoMirrored.Filled.Sort, "Сортировка", tint = theme.accent, modifier = Modifier.size(22.dp))
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
                                        mode.label(),
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

            AnimatedVisibility(
                visible = searchOpen,
                enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
                exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut()
            ) {
                TextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Поиск по названию и исполнителю", color = Color.White.copy(alpha = 0.4f), fontStyle = FontStyle.Normal) },
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
            }

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
                items(displayed, key = { it.id }) { track ->
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
                                shape = RoundedCornerShape(10.dp),
                                theme = theme,
                                showName = false
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
                            Text(
                                formatDuration(track.duration),
                                color = Color.White.copy(alpha = 0.35f),
                                fontSize = 11.sp,
                                fontStyle = FontStyle.Normal,
                                modifier = Modifier.padding(end = 8.dp)
                            )
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
                                text = { Text("Текст песни", color = Color.White) },
                                onClick = {
                                    menuTrack = null
                                    AppLogger.d(TAG, "menu lyrics: id=${track.id}")
                                    onOpenLyrics(track)
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
                            DropdownMenuItem(
                                text = { Text("Скрыть из списка", color = Color.White) },
                                onClick = {
                                    menuTrack = null
                                    AppLogger.i(TAG, "menu hide: id=${track.id}")
                                    scope.launch { themeRepository.hideTrack(track.id) }
                                    Toast.makeText(context, "Трек скрыт из списка", Toast.LENGTH_SHORT).show()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Удалить с устройства", color = Color(0xFFFF8A80)) },
                                onClick = {
                                    menuTrack = null
                                    AppLogger.w(TAG, "menu delete confirm: id=${track.id}")
                                    deleteConfirm = track
                                }
                            )
                        }
                    }
                }
            }
        }

        deleteConfirm?.let { t ->
            AlertDialog(
                onDismissRequest = { deleteConfirm = null },
                title = { Text("Удалить с устройства?", color = Color.White) },
                text = {
                    Text(
                        "«${t.title}» будет удалён безвозвратно вместе с файлом на диске.",
                        color = Color.White.copy(alpha = 0.7f)
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        deleteConfirm = null
                        deleteFromDevice(t)
                    }) {
                        Text("Удалить", color = Color(0xFFFF5252))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { deleteConfirm = null }) {
                        Text("Отмена", color = Color.White.copy(alpha = 0.7f))
                    }
                },
                containerColor = Color(0xFF171021)
            )
        }
    }
}