package com.wikrytas.coolplayer.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import com.wikrytas.coolplayer.data.AppLogger
import com.wikrytas.coolplayer.data.CoverRepository
import com.wikrytas.coolplayer.data.SessionStore
import com.wikrytas.coolplayer.data.StatsRepository
import com.wikrytas.coolplayer.models.Track
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val TAG = "PlayerVM"

enum class SleepMode(val label: String, val minutes: Int) {
    OFF("Выключен", 0), M15("15 минут", 15), M30("30 минут", 30),
    M60("60 минут", 60), M90("90 минут", 90), END_TRACK("До конца трека", -1)
}

enum class PlaybackSpeed(val label: String, val value: Float) {
    SLOW("0.75×", 0.75f), NORMAL("1×", 1.0f), FAST("1.25×", 1.25f), TURBO("1.5×", 1.5f)
}

data class PlayerUiState(
    val isPlaying: Boolean = false,
    val position: Long = 0L,
    val duration: Long = 0L,
    val shuffleEnabled: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val sleepMode: SleepMode = SleepMode.OFF,
    val selectedTrack: Track? = null,
    val isSeeking: Boolean = false,
    val playbackSpeed: PlaybackSpeed = PlaybackSpeed.NORMAL,
    val queue: List<Track> = emptyList(),
    val queueIndex: Int = -1
)

class PlayerViewModel(
    private val statsRepository: StatsRepository,
    private val sessionStore: SessionStore,
    private val coverRepository: CoverRepository
) : ViewModel() {

    companion object {
        fun factory(application: android.app.Application): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                PlayerViewModel(
                    StatsRepository(application),
                    SessionStore(application),
                    CoverRepository.getInstance(application)
                )
            }
        }
    }

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private var controller: MediaController? = null
    private var updateJob: Job? = null
    private var sleepJob: Job? = null
    private var countedForId: Long? = null
    private var lastQueue: List<Track> = emptyList()
    private var listener: Player.Listener? = null

    init {
        viewModelScope.launch {
            coverRepository.coverSaved.collect { id ->
                val c = controller ?: return@collect
                val idx = c.currentMediaItemIndex
                val track = lastQueue.getOrNull(idx) ?: return@collect
                if (track.id != id) return@collect
                if (c.currentMediaItem?.mediaMetadata?.artworkUri != null) return@collect
                val uri = coverRepository.cachedCoverUri(track) ?: return@collect
                AppLogger.i(TAG, "cover arrived late for current track id=$id, updating metadata")
                c.replaceMediaItem(idx, track.toMediaItem(uri))
            }
        }
    }

    private fun Track.toMediaItem(artwork: Uri?): MediaItem =
        MediaItem.Builder()
            .setUri(uri)
            .setMediaId(id.toString())
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist)
                    .setIsPlayable(true)
                    .setArtworkUri(artwork)
                    .build()
            )
            .build()

    private fun safeDuration(raw: Long): Long = if (raw > 0 && raw != C.TIME_UNSET) raw else 0L

    private fun persistModes() {
        controller?.let { c ->
            sessionStore.saveModes(c.shuffleModeEnabled, c.repeatMode, c.playbackParameters.speed)
        }
    }

    private fun createListener(): Player.Listener {
        return object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                updateState { it.copy(isPlaying = playing) }
                if (playing) {
                    startPositionUpdates()
                } else {
                    stopPositionUpdates()
                    controller?.let { c -> sessionStore.savePosition(c.currentPosition.coerceAtLeast(0L)) }
                }
            }

            override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                val c = controller ?: return
                val dur = safeDuration(c.duration)
                val pos = if (dur > 0) c.currentPosition.coerceIn(0, dur) else 0L
                val idx = lastQueue.indexOfFirst { it.id.toString() == item?.mediaId }
                if (idx >= 0) sessionStore.saveIndex(idx)
                AppLogger.d(TAG, "transition: idx=$idx, mediaId=${item?.mediaId}, pos=$pos")
                updateState { state ->
                    state.copy(
                        duration = dur,
                        position = pos,
                        selectedTrack = lastQueue.find { t -> t.id.toString() == item?.mediaId },
                        queueIndex = idx
                    )
                }
                countedForId = null
                if (_uiState.value.sleepMode != SleepMode.END_TRACK) sleepJob?.cancel()
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    val c = controller ?: return
                    val dur = safeDuration(c.duration)
                    if (dur > 0 && _uiState.value.duration == 0L) {
                        updateState { it.copy(duration = dur) }
                    }
                }
            }

            override fun onShuffleModeEnabledChanged(enabled: Boolean) {
                updateState { it.copy(shuffleEnabled = enabled) }
            }

            override fun onRepeatModeChanged(mode: Int) {
                updateState { it.copy(repeatMode = mode) }
            }
        }
    }

    fun bindController(c: MediaController, queue: List<Track>) {
        if (controller == c && lastQueue == queue) return
        controller?.let { old -> listener?.let { old.removeListener(it) } }
        controller = c
        lastQueue = queue
        listener = createListener()
        c.addListener(listener!!)

        sessionStore.snapshot()?.let { snap ->
            AppLogger.d(TAG, "restoring modes: shuffle=${snap.shuffle}, repeat=${snap.repeat}, speed=${snap.speed}")
            c.shuffleModeEnabled = snap.shuffle
            c.repeatMode = snap.repeat
            c.playbackParameters = PlaybackParameters(snap.speed)
        }

        val dur = safeDuration(c.duration)
        val pos = if (dur > 0) c.currentPosition.coerceIn(0, dur) else 0L
        AppLogger.i(TAG, "bindController: queue=${queue.size}, isPlaying=${c.isPlaying}, pos=$pos")
        updateState { state ->
            state.copy(
                isPlaying = c.isPlaying,
                duration = dur,
                position = pos,
                shuffleEnabled = c.shuffleModeEnabled,
                repeatMode = c.repeatMode,
                playbackSpeed = PlaybackSpeed.entries.find { it.value == c.playbackParameters.speed }
                    ?: PlaybackSpeed.NORMAL,
                queue = queue
            )
        }
        if (c.isPlaying) startPositionUpdates()
    }

    fun setQueue(queue: List<Track>) {
        lastQueue = queue
        updateState { it.copy(queue = queue) }
    }

    fun restoreSession(allTracks: List<Track>): Boolean {
        val c = controller ?: return false
        val snap = sessionStore.snapshot() ?: run {
            AppLogger.d(TAG, "restoreSession: no snapshot")
            return false
        }
        val byId = allTracks.associateBy { it.id }
        val queue = snap.ids.mapNotNull { byId[it] }
        if (queue.isEmpty()) {
            AppLogger.w(TAG, "restoreSession: snapshot ids not found in library")
            return false
        }
        val index = snap.index.coerceIn(0, queue.size - 1)
        lastQueue = queue
        val items = queue.map { t -> t.toMediaItem(coverRepository.cachedCoverUri(t)) }
        c.setMediaItems(items, index, snap.position.coerceAtLeast(0L))
        c.prepare()
        c.play()
        AppLogger.i(TAG, "restoreSession OK: queue=${queue.size}, idx=$index, pos=${snap.position}")
        updateState { state ->
            state.copy(
                queue = queue,
                queueIndex = index,
                selectedTrack = queue[index],
                position = snap.position,
                duration = 0L
            )
        }
        return true
    }

    fun playPause() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else c.play()
    }

    fun next() {
        AppLogger.d(TAG, "next")
        controller?.seekToNext()
    }

    fun previous() {
        AppLogger.d(TAG, "previous")
        controller?.seekToPrevious()
    }

    fun seekTo(ms: Long) {
        val dur = _uiState.value.duration
        val safe = if (dur > 0) ms.coerceIn(0, dur) else ms.coerceAtLeast(0L)
        controller?.seekTo(safe)
        updateState { it.copy(position = safe) }
    }

    fun seekToPosition(pos: Long) {
        val dur = _uiState.value.duration
        val safePos = if (dur > 0) pos.coerceIn(0, dur) else 0L
        updateState { it.copy(position = safePos) }
    }

    fun startSeek() {
        updateState { it.copy(isSeeking = true) }
    }

    fun finishSeek(pos: Long) {
        val dur = _uiState.value.duration
        if (dur > 0) {
            val safePos = pos.coerceIn(0, dur)
            controller?.seekTo(safePos)
            sessionStore.savePosition(safePos)
            updateState { it.copy(isSeeking = false, position = safePos) }
        } else {
            updateState { it.copy(isSeeking = false) }
        }
    }

    fun toggleShuffle() {
        val c = controller ?: return
        c.shuffleModeEnabled = !c.shuffleModeEnabled
        AppLogger.d(TAG, "shuffle=${c.shuffleModeEnabled}")
        persistModes()
    }

    fun cycleRepeat() {
        val c = controller ?: return
        c.repeatMode = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        persistModes()
    }

    fun setPlaybackSpeed(speed: PlaybackSpeed) {
        controller?.playbackParameters = PlaybackParameters(speed.value)
        updateState { it.copy(playbackSpeed = speed) }
        AppLogger.d(TAG, "speed=${speed.label}")
        persistModes()
    }

    fun cyclePlaybackSpeed() {
        val next = when (_uiState.value.playbackSpeed) {
            PlaybackSpeed.SLOW -> PlaybackSpeed.NORMAL
            PlaybackSpeed.NORMAL -> PlaybackSpeed.FAST
            PlaybackSpeed.FAST -> PlaybackSpeed.TURBO
            PlaybackSpeed.TURBO -> PlaybackSpeed.SLOW
        }
        setPlaybackSpeed(next)
    }

    fun setSleepMode(mode: SleepMode) {
        AppLogger.i(TAG, "sleepMode=${mode.label}")
        updateState { it.copy(sleepMode = mode) }
        sleepJob?.cancel()
        if (mode == SleepMode.OFF) return
        sleepJob = viewModelScope.launch {
            if (mode == SleepMode.END_TRACK) {
                while (true) {
                    val st = _uiState.value
                    val c = controller
                    if (c != null && st.isPlaying && st.duration > 0 && st.position >= st.duration - 800) {
                        c.pause()
                        updateState { it.copy(sleepMode = SleepMode.OFF) }
                        break
                    }
                    delay(500)
                }
            } else {
                delay(mode.minutes * 60_000L)
                controller?.pause()
                updateState { it.copy(sleepMode = SleepMode.OFF) }
            }
        }
    }

    fun playTrack(track: Track, queueContext: List<Track>) {
        val c = controller ?: return
        lastQueue = queueContext
        val startIndex = queueContext.indexOfFirst { it.id == track.id }
        if (startIndex < 0) return
        AppLogger.i(TAG, "playTrack: id=${track.id}, title='${track.title}', queue=${queueContext.size}, idx=$startIndex")
        val items = queueContext.map { t -> t.toMediaItem(coverRepository.cachedCoverUri(t)) }
        c.setMediaItems(items, startIndex, 0L)
        c.prepare()
        c.play()
        sessionStore.saveQueue(queueContext.map { it.id }, startIndex)
        sessionStore.savePosition(0L)
        updateState {
            it.copy(
                selectedTrack = track,
                duration = 0L,
                position = 0L,
                queue = queueContext,
                queueIndex = startIndex
            )
        }
    }

    fun onTrackStartedPlaying(trackId: Long) {
        if (countedForId != trackId) {
            countedForId = trackId
            AppLogger.d(TAG, "stats increment: id=$trackId")
            viewModelScope.launch { statsRepository.increment(trackId) }
        }
    }

    private fun startPositionUpdates() {
        updateJob?.cancel()
        updateJob = viewModelScope.launch {
            var tick = 0
            while (true) {
                val c = controller
                if (c != null && !_uiState.value.isSeeking) {
                    val dur = safeDuration(c.duration)
                    val pos = if (dur > 0) c.currentPosition.coerceIn(0, dur) else 0L
                    val curDur = _uiState.value.duration
                    if (dur > 0 || curDur != 0L) {
                        updateState { it.copy(position = pos, duration = if (dur > 0) dur else it.duration) }
                    }
                    tick++
                    if (tick % 10 == 0 && dur > 0) sessionStore.savePosition(pos)
                }
                delay(500)
            }
        }
    }

    private fun stopPositionUpdates() {
        updateJob?.cancel()
        updateJob = null
    }

    private inline fun updateState(update: (PlayerUiState) -> PlayerUiState) {
        _uiState.value = update(_uiState.value)
    }

    override fun onCleared() {
        AppLogger.d(TAG, "onCleared")
        controller?.let { c ->
            listener?.let { c.removeListener(it) }
            sessionStore.savePosition(c.currentPosition.coerceAtLeast(0L))
        }
        listener = null
        controller = null
        updateJob?.cancel()
        sleepJob?.cancel()
        super.onCleared()
    }
}