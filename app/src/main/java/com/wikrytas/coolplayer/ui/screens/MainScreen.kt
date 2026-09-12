package com.wikrytas.coolplayer.ui.screens

import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.session.MediaController
import com.wikrytas.coolplayer.data.AppLogger
import com.wikrytas.coolplayer.data.AppSettings
import com.wikrytas.coolplayer.data.AppUpdater
import com.wikrytas.coolplayer.data.CacheCleanup
import com.wikrytas.coolplayer.data.CoverRepository
import com.wikrytas.coolplayer.data.LyricsRepository
import com.wikrytas.coolplayer.data.ThemeRepository
import com.wikrytas.coolplayer.data.UpdateInfo
import com.wikrytas.coolplayer.models.Track
import com.wikrytas.coolplayer.ui.AudioViewModel
import com.wikrytas.coolplayer.ui.PlayerViewModel
import com.wikrytas.coolplayer.ui.theme.DynamicThemeExtractor
import com.wikrytas.coolplayer.ui.theme.PlayerTheme
import com.wikrytas.coolplayer.ui.theme.PlayerThemes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class AppScreen { PLAYER, LIBRARY, SETTINGS, LOG, LYRICS }

/** Тексты подсказок путеводителя (плеер). */
private val PLAYER_HINTS = listOf(
    "Тап по обложке открывает текст песни, повторный тап возвращает обложку. Долгое нажатие на обложке — меню текста: встроить, найти, синхронизация.",
    "Свайп по обложке влево/вправо — предыдущий и следующий трек. Свайп вниз — библиотека, свайп вверх из библиотеки — плеер.",
    "Тексты с таймкодами подсвечиваются в такт музыке и запоминаются в файле. Обычные тексты прокручиваются рукой.",
    "Долгое нажатие на треке в библиотеке — меню: играть, текст песни, избранное, скрыть, удалить с устройства.",
    "Настройки: темы и авто-цвет из обложки, эквалайзер, таймер сна, скорость, автофокус библиотеки и проверка обновлений."
)

/** Тексты подсказок путеводителя (библиотека). */
private val LIBRARY_HINTS = listOf(
    "Чипы под строкой поиска — фильтры: все, избранное, часто, новые, давние. Кнопка сортировки — справа в шапке.",
    "Лупа открывает поиск по названию и исполнителю. Кнопка-прицел подсвечивает текущий играющий трек.",
    "Долгое нажатие на треке открывает меню: играть, текст песни, избранное, скрыть из списка, удалить с устройства."
)

/** Флаги «подсказки показаны» — общие ключи, чтобы состояние сохранялось. */
private object MainHints {
    private const val PREFS = "coach_marks"
    private const val KEY_PLAYER = "player_hints_shown"
    private const val KEY_LIBRARY = "library_hints_shown"
    fun shouldShowPlayer(ctx: Context): Boolean =
        !ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_PLAYER, false)
    fun shouldShowLibrary(ctx: Context): Boolean =
        !ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_LIBRARY, false)
    fun markPlayerShown(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_PLAYER, true).apply()
    fun markLibraryShown(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_LIBRARY, true).apply()
}

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
    var lyricsTrack by remember { mutableStateOf<Track?>(null) }

    var showPlayerHints by remember { mutableStateOf(false) }
    var showLibraryHints by remember { mutableStateOf(false) }
    var updateInfo by remember { mutableStateOf<UpdateInfo?>(null) }
    var updateDismissed by remember { mutableStateOf(false) }

    val uiState by playerViewModel.uiState.collectAsStateWithLifecycle()
    val selectedTrack = uiState.selectedTrack
    val appSettings by themeRepository.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val hidden = appSettings.hiddenTracks
    val visibleTracks = remember(tracks, hidden) { tracks.filterNot { hidden.contains(it.id) } }

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

    // Путеводитель: один раз при первом входе на экран
    LaunchedEffect(currentScreen) {
        if (currentScreen == AppScreen.PLAYER && MainHints.shouldShowPlayer(appContext)) showPlayerHints = true
        if (currentScreen == AppScreen.LIBRARY && MainHints.shouldShowLibrary(appContext)) showLibraryHints = true
    }

    // Автопроверка обновления при старте (раз за сессию)
    LaunchedEffect(Unit) {
        val info = AppUpdater.checkForUpdate(appContext)
        if (info != null && info.isNewer) {
            AppLogger.i("Main", "update available: ${info.versionName}")
            updateInfo = info
        }
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

    // FIX внешнего аудио: отдельный эффект — работает и на холодном старте,
    // и при onNewIntent поверх играющей сессии
    LaunchedEffect(pendingUri, visibleTracks) {
        val uri = pendingUri ?: return@LaunchedEffect
        if (visibleTracks.isEmpty()) return@LaunchedEffect
        AppLogger.i("Main", "playing external uri: $uri")
        val existing = visibleTracks.find { it.uri.toString() == uri.toString() }
        if (existing != null) {
            playerViewModel.playTrack(existing, visibleTracks)
        } else {
            val adHoc = Track(
                id = -Math.abs(uri.hashCode().toLong()) - 1,
                uri = uri,
                title = "Внешний трек",
                artist = "Открыт из другого приложения",
                duration = 0L,
                albumArtUri = null,
                dateAdded = System.currentTimeMillis()
            )
            playerViewModel.playTrack(adHoc, listOf(adHoc))
        }
        onPendingUriConsumed()
    }

    val lastTrackId = appSettings.lastTrackId
    LaunchedEffect(lastTrackId, visibleTracks, mediaController) {
        if (selectedTrack == null && visibleTracks.isNotEmpty() && pendingUri == null) {
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
                onOpenSettings = { navigateTo(AppScreen.SETTINGS) },
                onOpenLyrics = {
                    lyricsTrack = selectedTrack
                    navigateTo(AppScreen.LYRICS)
                }
            )
            AppScreen.LIBRARY -> LibraryScreen(
                tracks = visibleTracks,
                theme = effectiveTheme,
                playerViewModel = playerViewModel,
                favorites = favorites,
                sortMode = sortMode,
                onSortModeChange = { mode -> scope.launch { themeRepository.saveSortMode(mode) } },
                onToggleFavorite = { id -> scope.launch { themeRepository.toggleFavorite(id) } },
                onOpenLyrics = { track ->
                    lyricsTrack = track
                    navigateTo(AppScreen.LYRICS)
                },
                onBack = { navigateBack() },
                onOpenPlayer = { navigateTo(AppScreen.PLAYER) },
                onLibraryChanged = { audioViewModel.refreshTracks() }
            )
            AppScreen.LYRICS -> LyricsScreen(
                track = lyricsTrack ?: selectedTrack,
                theme = effectiveTheme,
                playerViewModel = playerViewModel,
                lyricsRepository = lyricsRepository,
                onBack = { navigateBack() }
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

        // ── Баннер обновления поверх плеера ──
        val info = updateInfo
        if (info != null && !updateDismissed && currentScreen == AppScreen.PLAYER && !showPlayerHints) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
            ) {
                MainUpdateBanner(info = info, accent = effectiveTheme.accent, onDismiss = { updateDismissed = true })
            }
        }

        // ── Путеводитель при первом входе ──
        if (showPlayerHints && currentScreen == AppScreen.PLAYER) {
            MainCoachOverlay(
                hints = PLAYER_HINTS,
                accent = effectiveTheme.accent,
                onDone = {
                    MainHints.markPlayerShown(appContext)
                    showPlayerHints = false
                }
            )
        }
        if (showLibraryHints && currentScreen == AppScreen.LIBRARY) {
            MainCoachOverlay(
                hints = LIBRARY_HINTS,
                accent = effectiveTheme.accent,
                onDone = {
                    MainHints.markLibraryShown(appContext)
                    showLibraryHints = false
                }
            )
        }
    }
}

/** Баннер «Доступна версия X» со скачиванием и установкой. */
@Composable
private fun MainUpdateBanner(
    info: UpdateInfo,
    accent: Color,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var progress by remember { mutableIntStateOf(-1) }
    var needPermission by remember { mutableStateOf<File?>(null) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(accent.copy(alpha = 0.18f))
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Доступна версия ${info.versionName}",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    when {
                        needPermission != null -> "Разрешите установку из этого источника"
                        progress >= 0 -> "Скачивание... $progress%"
                        else -> "Нажмите, чтобы обновиться"
                    },
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 11.sp
                )
            }
            if (needPermission != null) {
                Text(
                    "УСТАНОВИТЬ",
                    color = accent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { needPermission?.let { AppUpdater.installApk(context, it) } }
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                )
            } else if (progress < 0) {
                Text(
                    "ОБНОВИТЬ",
                    color = accent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable {
                            scope.launch {
                                progress = 0
                                val apk = AppUpdater.downloadApk(context, info.downloadUrl) { p -> progress = p }
                                progress = -1
                                if (apk != null) {
                                    if (!AppUpdater.canInstall(context)) {
                                        needPermission = apk
                                        AppUpdater.openInstallSettings(context)
                                    } else {
                                        AppUpdater.installApk(context, apk)
                                    }
                                }
                            }
                        }
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                )
            } else {
                CircularProgressIndicator(color = accent, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(8.dp))
            Text(
                "✕",
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 13.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onDismiss() }
                    .padding(6.dp)
            )
        }
    }
}

/** Оверлей-путеводитель: карточки с шагами, точки прогресса, «Пропустить/Далее». */
@Composable
private fun MainCoachOverlay(
    hints: List<String>,
    accent: Color,
    onDone: () -> Unit
) {
    var step by remember { mutableIntStateOf(0) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.78f))
            .clickable(enabled = false) { },
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 28.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xFF141A2A))
                .padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "ПОДСКАЗКА ${step + 1} ИЗ ${hints.size}",
                color = accent.copy(alpha = 0.7f),
                fontSize = 10.sp,
                letterSpacing = 3.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(10.dp))
            Text(
                hints[step],
                color = Color.White,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                hints.forEachIndexed { i, _ ->
                    Box(
                        Modifier
                            .padding(horizontal = 3.dp)
                            .size(if (i == step) 8.dp else 6.dp)
                            .clip(CircleShape)
                            .background(if (i == step) accent else Color.White.copy(alpha = 0.25f))
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Пропустить",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 12.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onDone() }
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                )
                Spacer(Modifier.weight(1f))
                Text(
                    if (step == hints.size - 1) "ГОТОВО" else "ДАЛЕЕ",
                    color = Color(0xFF0B0F1A),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(accent)
                        .clickable { if (step == hints.size - 1) onDone() else step++ }
                        .padding(horizontal = 18.dp, vertical = 9.dp)
                )
            }
        }
    }
}