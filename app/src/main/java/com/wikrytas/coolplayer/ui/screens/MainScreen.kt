package com.wikrytas.coolplayer.ui.screens

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.session.MediaController
import com.wikrytas.coolplayer.data.AppLogger
import com.wikrytas.coolplayer.data.AppSettings
import com.wikrytas.coolplayer.data.CacheCleanup
import com.wikrytas.coolplayer.data.CoverRepository
import com.wikrytas.coolplayer.data.LyricsRepository
import com.wikrytas.coolplayer.data.ThemeRepository
import com.wikrytas.coolplayer.models.Track
import com.wikrytas.coolplayer.ui.AudioViewModel
import com.wikrytas.coolplayer.ui.PlayerViewModel
import com.wikrytas.coolplayer.ui.theme.DynamicThemeExtractor
import com.wikrytas.coolplayer.ui.theme.PlayerTheme
import com.wikrytas.coolplayer.ui.theme.PlayerThemes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class AppScreen { PLAYER, LIBRARY, SETTINGS, LOG }

@Composable
fun MainScreen(
    mediaController: MediaController,
    tracks: List<Track>,
    audioViewModel: AudioViewModel,
    playerViewModel: PlayerViewModel,
    pendingUri: Uri? = null,
    onPendingUriConsumed: () -> Unit = {}
) {
    val context = LocalContext.current
    val appContext = context.applicationContext

    val themeRepository = remember { ThemeRepository(appContext) }
    val coverRepository = remember { CoverRepository.getInstance(appContext) }
    val lyricsRepository = remember { LyricsRepository(appContext) }
    val scope = rememberCoroutineScope()

    var currentScreen by remember { mutableStateOf(AppScreen.PLAYER) }
    val backStack = remember { mutableStateListOf<AppScreen>() }

    val uiState by playerViewModel.uiState.collectAsStateWithLifecycle()
    val selectedTrack = uiState.selectedTrack

    val appSettings by themeRepository.settings.collectAsStateWithLifecycle(initialValue = AppSettings())

    val hidden = appSettings.hiddenTracks
    val visibleTracks = remember(tracks, hidden) { tracks.filterNot { hidden.contains(it.id) } }

    // FIX: тема выводится напрямую — без промежуточного state и эффекта,
    // поэтому на старте нет мелькания дефолтной темы
    val currentTheme = PlayerThemes.byName(appSettings.selectedThemeName)

    fun navigateTo(target: AppScreen) {
        if (target != currentScreen) {
            AppLogger.d("Nav", "navigate: $currentScreen -> $target")
            backStack.add(currentScreen)
            currentScreen = target
        }
    }

    fun navigateBack() {
        val from = currentScreen
        currentScreen = backStack.removeLastOrNull() ?: AppScreen.PLAYER
        AppLogger.d("Nav", "back: $from -> $currentScreen")
    }

    BackHandler(enabled = currentScreen != AppScreen.PLAYER) {
        navigateBack()
    }

    LaunchedEffect(mediaController, visibleTracks) {
        AppLogger.d("Main", "bindController: tracks=${visibleTracks.size}")
        playerViewModel.bindController(mediaController, visibleTracks)
    }

    LaunchedEffect(visibleTracks) {
        playerViewModel.setQueue(visibleTracks)
    }

    LaunchedEffect(tracks) {
        if (tracks.isNotEmpty()) {
            val ids = tracks.map { it.id }.toSet()
            withContext(Dispatchers.IO) {
                CacheCleanup.cleanOrphaned(appContext, ids)
            }
        }
    }

    LaunchedEffect(selectedTrack?.id) {
        selectedTrack?.id?.let { id ->
            AppLogger.d("Main", "selectedTrack changed: id=$id")
            scope.launch { themeRepository.saveLastTrack(id) }
        }
    }

    val dynamicEnabled = appSettings.dynamicEnabled
    val favorites = appSettings.favorites
    val sortMode = appSettings.sortMode

    var effectiveTheme by remember { mutableStateOf(currentTheme) }
    LaunchedEffect(selectedTrack?.id, dynamicEnabled, currentTheme) {
        val track = selectedTrack
        effectiveTheme = if (dynamicEnabled && track != null) {
            DynamicThemeExtractor.getCached(track.id)
                ?: run {
                    val coverUri = coverRepository.cachedCoverUri(track)
                        ?: coverRepository.resolveCover(track)
                    if (coverUri != null) {
                        DynamicThemeExtractor.extractTheme(appContext, track.id, coverUri, currentTheme)
                    } else currentTheme
                }
        } else currentTheme
        AppLogger.d("Theme", "effective=${effectiveTheme.name}, dynamic=$dynamicEnabled")
    }

    val lastTrackId = appSettings.lastTrackId
    LaunchedEffect(lastTrackId, visibleTracks, mediaController, pendingUri) {
        if (selectedTrack == null && visibleTracks.isNotEmpty()) {
            if (pendingUri != null) {
                AppLogger.i("Main", "playing external uri: $pendingUri")
                val existing = visibleTracks.find { it.uri.toString() == pendingUri.toString() }
                if (existing != null) {
                    playerViewModel.playTrack(existing, visibleTracks)
                } else {
                    val adHoc = Track(
                        id = -Math.abs(pendingUri.hashCode().toLong()) - 1,
                        uri = pendingUri,
                        title = "Внешний трек",
                        artist = "Открыт из другого приложения",
                        duration = 0L,
                        albumArtUri = null,
                        dateAdded = System.currentTimeMillis()
                    )
                    playerViewModel.playTrack(adHoc, listOf(adHoc))
                }
                onPendingUriConsumed()
                return@LaunchedEffect
            }
            val restored = playerViewModel.restoreSession(visibleTracks)
            AppLogger.i("Main", "restoreSession=$restored, lastTrackId=$lastTrackId")
            if (!restored) {
                val id = lastTrackId ?: return@LaunchedEffect
                visibleTracks.find { it.id == id }?.let { track ->
                    playerViewModel.playTrack(track, visibleTracks)
                }
            }
        }
    }

    LaunchedEffect(selectedTrack?.id, uiState.isPlaying) {
        if (uiState.isPlaying) {
            selectedTrack?.id?.let { id ->
                playerViewModel.onTrackStartedPlaying(id)
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when (currentScreen) {
            AppScreen.PLAYER -> PlayerScreen(
                viewModel = playerViewModel,
                theme = effectiveTheme,
                favorites = favorites,
                lyricsRepository = lyricsRepository,
                onToggleFavorite = { id -> scope.launch { themeRepository.toggleFavorite(id) } },
                onOpenLibrary = { navigateTo(AppScreen.LIBRARY) },
                onOpenSettings = { navigateTo(AppScreen.SETTINGS) }
            )
            AppScreen.LIBRARY -> LibraryScreen(
                tracks = visibleTracks,
                theme = effectiveTheme,
                playerViewModel = playerViewModel,
                favorites = favorites,
                sortMode = sortMode,
                onSortModeChange = { mode -> scope.launch { themeRepository.saveSortMode(mode) } },
                onToggleFavorite = { id -> scope.launch { themeRepository.toggleFavorite(id) } },
                onBack = { navigateBack() },
                onOpenPlayer = { navigateTo(AppScreen.PLAYER) },
                onLibraryChanged = { audioViewModel.refreshTracks() }
            )
            AppScreen.SETTINGS -> SettingsScreen(
                theme = effectiveTheme,
                playerViewModel = playerViewModel,
                allTracks = tracks,
                onBack = { navigateBack() },
                onOpenLog = { navigateTo(AppScreen.LOG) }
            )
            AppScreen.LOG -> LogScreen(
                onBack = { navigateBack() }
            )
        }
    }
}