package com.wikrytas.coolplayer.ui

import android.app.Application
import android.content.ComponentName
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.wikrytas.coolplayer.audio.PlaybackService
import com.wikrytas.coolplayer.data.AppLogger
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MediaControllerViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "MediaCtrl"
        private const val MAX_RETRY = 5

        fun factory(application: Application): ViewModelProvider.Factory = viewModelFactory {
            initializer { MediaControllerViewModel(application) }
        }
    }

    private val _controller = MutableStateFlow<MediaController?>(null)
    val controller: StateFlow<MediaController?> = _controller.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var retryCount = 0

    init {
        connect()
    }

    private fun connect() {
        disconnect()
        AppLogger.d(TAG, "connect attempt ${retryCount + 1}/$MAX_RETRY")

        val token = SessionToken(
            getApplication(),
            ComponentName(getApplication(), PlaybackService::class.java)
        )

        val future = MediaController.Builder(getApplication(), token).buildAsync()
        controllerFuture = future

        future.addListener({
            _controller.value = if (future.isDone && !future.isCancelled) {
                try {
                    val c = future.get()
                    retryCount = 0
                    _error.value = null
                    AppLogger.i(TAG, "controller connected")
                    c
                } catch (e: Exception) {
                    AppLogger.w(TAG, "connect failed (attempt ${retryCount + 1}/$MAX_RETRY): ${e.message}")
                    retryCount++

                    if (retryCount < MAX_RETRY) {
                        viewModelScope.launch {
                            delay(2000)
                            if (_controller.value == null) connect()
                        }
                    } else {
                        AppLogger.e(TAG, "retry exhausted, giving up")
                        _error.value = "Не удалось подключиться к плееру после $MAX_RETRY попыток"
                    }
                    null
                }
            } else {
                AppLogger.w(TAG, "future not done or cancelled")
                null
            }
        }, MoreExecutors.directExecutor())
    }

    fun reconnect() {
        AppLogger.i(TAG, "manual reconnect")
        retryCount = 0
        _error.value = null
        disconnect()
        connect()
    }

    private fun disconnect() {
        controllerFuture?.let { future ->
            try {
                if (!future.isDone) {
                    future.cancel(true)
                }
                MediaController.releaseFuture(future)
            } catch (e: Exception) {
                AppLogger.w(TAG, "releaseFuture failed: ${e.message}")
            }
        }
        controllerFuture = null
        _controller.value = null
    }

    override fun onCleared() {
        AppLogger.d(TAG, "onCleared")
        disconnect()
        super.onCleared()
    }
}