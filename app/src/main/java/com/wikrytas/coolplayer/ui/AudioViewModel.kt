package com.wikrytas.coolplayer.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wikrytas.coolplayer.data.AppLogger
import com.wikrytas.coolplayer.data.AudioRepository
import com.wikrytas.coolplayer.models.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AudioViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "Library"
    }

    private val repository = AudioRepository(application)

    private val _tracks = MutableStateFlow<List<Track>>(emptyList())
    val tracks: StateFlow<List<Track>> = _tracks.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        refreshTracks()
    }

    fun refreshTracks() {
        viewModelScope.launch {
            AppLogger.d(TAG, "library scan start")
            val started = System.currentTimeMillis()
            _isLoading.value = true
            try {
                val result = repository.getLocalTracks()
                _tracks.value = result
                AppLogger.i(
                    TAG,
                    "library scan done: ${result.size} tracks in ${System.currentTimeMillis() - started}ms"
                )
            } catch (e: Exception) {
                AppLogger.e(TAG, "library scan failed", e)
            } finally {
                _isLoading.value = false
            }
        }
    }
}