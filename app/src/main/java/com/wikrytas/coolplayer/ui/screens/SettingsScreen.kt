package com.wikrytas.coolplayer.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wikrytas.coolplayer.audio.EqualizerController
import com.wikrytas.coolplayer.data.AppLogger
import com.wikrytas.coolplayer.data.AppSettings
import com.wikrytas.coolplayer.data.LibraryPrefs
import com.wikrytas.coolplayer.data.ThemeRepository
import com.wikrytas.coolplayer.models.Track
import com.wikrytas.coolplayer.ui.PlayerViewModel
import com.wikrytas.coolplayer.ui.PlaybackSpeed
import com.wikrytas.coolplayer.ui.SleepMode
import com.wikrytas.coolplayer.ui.components.AppUpdateCard
import com.wikrytas.coolplayer.ui.components.EqualizerPanel
import com.wikrytas.coolplayer.ui.theme.PlayerTheme
import com.wikrytas.coolplayer.ui.theme.PlayerThemes
import kotlinx.coroutines.launch

private const val TAG = "Settings"

@Composable
fun SettingsScreen(
    theme: PlayerTheme,
    playerViewModel: PlayerViewModel,
    allTracks: List<Track>,
    onBack: () -> Unit,
    onOpenLog: () -> Unit = {}
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val repo = remember { ThemeRepository(appContext) }
    val scope = rememberCoroutineScope()
    val settings by repo.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val playerState by playerViewModel.uiState.collectAsStateWithLifecycle()
    var selectedTab by remember { mutableStateOf(0) }
    var autoScroll by remember { mutableStateOf(LibraryPrefs.autoScrollEnabled(appContext)) }

    val color1 by animateColorAsState(theme.backgroundTop, label = "s1")
    val color2 by animateColorAsState(theme.backgroundBottom, label = "s2")
    val accent by animateColorAsState(theme.accent, label = "sAccent")

    val hiddenTracks = remember(allTracks, settings.hiddenTracks) {
        allTracks.filter { settings.hiddenTracks.contains(it.id) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(color1, color2)))
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
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад", tint = Color.White)
            }
            Text(
                "Настройки",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                fontStyle = FontStyle.Normal
            )
        }

        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = Color.Transparent,
            contentColor = accent,
            indicator = { tabPositions ->
                Box(
                    Modifier
                        .tabIndicatorOffset(tabPositions[selectedTab])
                        .height(3.dp)
                        .background(color = accent)
                )
            }
        ) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = { Text("Оформление", color = if (selectedTab == 0) accent else Color.White.copy(alpha = 0.6f)) }
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { Text("Эквалайзер", color = if (selectedTab == 1) accent else Color.White.copy(alpha = 0.6f)) }
            )
        }

        when (selectedTab) {
            0 -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp)
            ) {
                Text(
                    "ПЛЕЕР",
                    color = accent,
                    fontSize = 12.sp,
                    letterSpacing = 3.sp,
                    fontWeight = FontWeight.Bold,
                    fontStyle = FontStyle.Normal
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.White.copy(alpha = 0.06f))
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Таймер сна",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontStyle = FontStyle.Normal,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        playerState.sleepMode.label,
                        color = if (playerState.sleepMode == SleepMode.OFF) Color.White.copy(alpha = 0.5f) else accent,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontStyle = FontStyle.Normal,
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(accent.copy(alpha = 0.12f))
                            .clickable {
                                val next = when (playerState.sleepMode) {
                                    SleepMode.OFF -> SleepMode.M15
                                    SleepMode.M15 -> SleepMode.M30
                                    SleepMode.M30 -> SleepMode.M60
                                    SleepMode.M60 -> SleepMode.M90
                                    SleepMode.M90 -> SleepMode.END_TRACK
                                    SleepMode.END_TRACK -> SleepMode.OFF
                                }
                                AppLogger.i(TAG, "sleep mode: ${next.label}")
                                playerViewModel.setSleepMode(next)
                            }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.White.copy(alpha = 0.06f))
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Скорость воспроизведения",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontStyle = FontStyle.Normal,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        playerState.playbackSpeed.label,
                        color = if (playerState.playbackSpeed == PlaybackSpeed.NORMAL) Color.White.copy(alpha = 0.5f) else accent,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontStyle = FontStyle.Normal,
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(accent.copy(alpha = 0.12f))
                            .clickable {
                                AppLogger.i(TAG, "speed cycle from ${playerState.playbackSpeed.label}")
                                playerViewModel.cyclePlaybackSpeed()
                            }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
                Spacer(Modifier.height(8.dp))
                // Автофокус в библиотеке
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.White.copy(alpha = 0.06f))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Автофокус в библиотеке", color = Color.White, fontSize = 15.sp, fontStyle = FontStyle.Normal)
                        Text(
                            "При входе подсвечивать текущий трек",
                            color = Color.White.copy(alpha = 0.5f),
                            fontSize = 11.sp,
                            fontStyle = FontStyle.Normal
                        )
                    }
                    Switch(
                        checked = autoScroll,
                        onCheckedChange = { enabled ->
                            autoScroll = enabled
                            LibraryPrefs.setAutoScrollEnabled(appContext, enabled)
                        },
                        colors = SwitchDefaults.colors(checkedTrackColor = accent)
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.White.copy(alpha = 0.06f))
                        .clickable {
                            AppLogger.d(TAG, "open log screen")
                            onOpenLog()
                        }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Description,
                        null,
                        tint = accent,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Лог приложения",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontStyle = FontStyle.Normal
                        )
                        Text(
                            "Просмотр и отправка отчёта об ошибках",
                            color = Color.White.copy(alpha = 0.5f),
                            fontSize = 11.sp,
                            fontStyle = FontStyle.Normal
                        )
                    }
                }

                Spacer(Modifier.height(24.dp))
                Text(
                    "ОБНОВЛЕНИЕ ПРИЛОЖЕНИЯ",
                    color = accent,
                    fontSize = 12.sp,
                    letterSpacing = 3.sp,
                    fontWeight = FontWeight.Bold,
                    fontStyle = FontStyle.Normal
                )
                Spacer(Modifier.height(8.dp))
                AppUpdateCard(theme = theme)

                Spacer(Modifier.height(24.dp))
                Text(
                    "ТЕМА",
                    color = accent,
                    fontSize = 12.sp,
                    letterSpacing = 3.sp,
                    fontWeight = FontWeight.Bold,
                    fontStyle = FontStyle.Normal
                )
                Spacer(Modifier.height(8.dp))
                PlayerThemes.all.forEach { t ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                if (settings.selectedThemeName == t.name) Color.White.copy(alpha = 0.08f)
                                else Color.Transparent
                            )
                            .clickable {
                                AppLogger.i(TAG, "theme selected: ${t.name}")
                                scope.launch { repo.saveTheme(t.name) }
                            }
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier
                                .size(26.dp)
                                .clip(CircleShape)
                                .background(Brush.linearGradient(listOf(t.backgroundBottom, t.accent)))
                        )
                        Spacer(Modifier.width(14.dp))
                        Text(
                            t.name,
                            color = Color.White,
                            fontSize = 15.sp,
                            fontStyle = FontStyle.Normal,
                            modifier = Modifier.weight(1f)
                        )
                        if (settings.selectedThemeName == t.name) {
                            Icon(Icons.Default.Check, null, tint = accent, modifier = Modifier.size(20.dp))
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.White.copy(alpha = 0.06f))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Авто-цвет из обложки", color = Color.White, fontSize = 15.sp, fontStyle = FontStyle.Normal)
                        Text(
                            "Тема подстраивается под текущий трек",
                            color = Color.White.copy(alpha = 0.5f),
                            fontSize = 11.sp,
                            fontStyle = FontStyle.Normal
                        )
                    }
                    Switch(
                        checked = settings.dynamicEnabled,
                        onCheckedChange = { enabled ->
                            AppLogger.i(TAG, "dynamic theme: $enabled")
                            scope.launch { repo.saveDynamicEnabled(enabled) }
                        },
                        colors = SwitchDefaults.colors(checkedTrackColor = accent)
                    )
                }

                if (hiddenTracks.isNotEmpty()) {
                    Spacer(Modifier.height(24.dp))
                    Text(
                        "СКРЫТЫЕ ТРЕКИ · ${hiddenTracks.size}",
                        color = accent,
                        fontSize = 12.sp,
                        letterSpacing = 3.sp,
                        fontWeight = FontWeight.Bold,
                        fontStyle = FontStyle.Normal
                    )
                    Spacer(Modifier.height(8.dp))
                    hiddenTracks.forEach { track ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color.White.copy(alpha = 0.05f))
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    track.title,
                                    color = Color.White.copy(alpha = 0.8f),
                                    fontSize = 14.sp,
                                    fontStyle = FontStyle.Normal,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    track.artist,
                                    color = Color.White.copy(alpha = 0.45f),
                                    fontSize = 11.sp,
                                    fontStyle = FontStyle.Normal,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            IconButton(onClick = {
                                AppLogger.i(TAG, "unhide track: id=${track.id}")
                                scope.launch { repo.unhideTrack(track.id) }
                            }) {
                                Icon(
                                    Icons.Default.Visibility,
                                    "Показать трек",
                                    tint = accent,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                }

                Spacer(Modifier.height(32.dp))
                Text(
                    "Версия 1.0 • CoolPlayer",
                    color = Color.White.copy(alpha = 0.4f),
                    fontSize = 11.sp,
                    letterSpacing = 2.sp,
                    fontStyle = FontStyle.Normal,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(24.dp))
            }
            1 -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp)
            ) {
                EqualizerPanel(
                    open = true,
                    currentPreset = settings.eqPreset,
                    accent = accent,
                    onPreset = { p ->
                        EqualizerController.applyPreset(p)
                        scope.launch { repo.saveEqPreset(p) }
                    },
                    onLevel = { b, l -> EqualizerController.setLevel(b, l) }
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}