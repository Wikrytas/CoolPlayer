package com.wikrytas.coolplayer.audio

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.MediaStore
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.wikrytas.coolplayer.R
import com.wikrytas.coolplayer.data.AppLogger
import com.wikrytas.coolplayer.widget.MusicWidgetProvider
import com.wikrytas.coolplayer.widget.WidgetState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class PlaybackService : MediaSessionService() {

    companion object {
        var audioSessionId: Int = 0
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "coolplayer_playback"
        private const val TAG = "Playback"

        const val ACTION_WIDGET_PLAY = "com.wikrytas.coolplayer.WIDGET_PLAY"
        const val ACTION_WIDGET_NEXT = "com.wikrytas.coolplayer.WIDGET_NEXT"
        const val ACTION_WIDGET_PREV = "com.wikrytas.coolplayer.WIDGET_PREV"
    }

    private var mediaSession: MediaSession? = null
    private var player: ExoPlayer? = null
    private val equalizerController = EqualizerController()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        AppLogger.i(TAG, "service onCreate")

        createNotificationChannel()

        val exoPlayer = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .build()

        exoPlayer.setWakeMode(C.WAKE_MODE_LOCAL)
        player = exoPlayer

        audioSessionId = exoPlayer.audioSessionId
        AppLogger.d(TAG, "audioSessionId=$audioSessionId")
        equalizerController.init(audioSessionId)

        exoPlayer.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                AppLogger.d(TAG, "isPlaying=$playing")
                WidgetState.isPlaying = playing
                pushWidget()
            }

            override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                AppLogger.d(TAG, "transition: mediaId=${item?.mediaId}, reason=$reason")
                item?.mediaId?.toLongOrNull()?.let { id ->
                    WidgetState.coverFile = File(filesDir, "covers/$id.jpg").takeIf { it.exists() }

                    serviceScope.launch {
                        val (t, a) = queryTrack(id)
                        WidgetState.title = t
                        WidgetState.artist = a
                        pushWidget()
                    }
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                AppLogger.d(TAG, "playbackState=$state")
            }

            override fun onPlayerError(error: PlaybackException) {
                AppLogger.e(TAG, "playerError: code=${error.errorCode}, msg=${error.message}", error)

                val errorMsg = when {
                    error.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "Файл не найден"
                    error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED -> "Неподдерживаемый формат"
                    error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED -> "Ошибка декодера"
                    else -> "Ошибка воспроизведения"
                }

                player?.let { p ->
                    if (p.hasNextMediaItem()) {
                        AppLogger.w(TAG, "skipping to next after error")
                        p.seekToNext()
                    } else {
                        p.stop()
                    }
                }

                WidgetState.title = errorMsg
                WidgetState.artist = ""
                pushWidget()
            }
        })

        mediaSession = MediaSession.Builder(this, exoPlayer).build()
        AppLogger.i(TAG, "MediaSession created")

        val notificationProvider = DefaultMediaNotificationProvider.Builder(this)
            .setNotificationId(NOTIFICATION_ID)
            .setChannelId(CHANNEL_ID)
            .setChannelName(R.string.app_name)
            .build()

        setMediaNotificationProvider(notificationProvider)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Воспроизведение",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Управление музыкой CoolPlayer"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_WIDGET_PLAY -> {
                AppLogger.d(TAG, "widget action: PLAY")
                player?.let { p -> if (p.isPlaying) p.pause() else p.play() }
            }
            ACTION_WIDGET_NEXT -> {
                AppLogger.d(TAG, "widget action: NEXT")
                player?.seekToNext()
            }
            ACTION_WIDGET_PREV -> {
                AppLogger.d(TAG, "widget action: PREV")
                player?.seekToPrevious()
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = player
        AppLogger.d(TAG, "onTaskRemoved: playing=${p?.isPlaying}, items=${p?.mediaItemCount}")
        if (p == null || p.mediaItemCount == 0 || p.playbackState == Player.STATE_ENDED) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        AppLogger.i(TAG, "service onDestroy")
        serviceScope.cancel()
        equalizerController.release()
        mediaSession?.runCatching { release() }
        mediaSession = null
        player?.runCatching { release() }
        player = null
        super.onDestroy()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    private fun pushWidget() {
        sendBroadcast(
            Intent(MusicWidgetProvider.ACTION_UPDATE).setPackage(packageName)
        )
    }

    private suspend fun queryTrack(id: Long): Pair<String, String> = withContext(Dispatchers.IO) {
        val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
        contentResolver.query(
            uri,
            arrayOf(MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST),
            null, null, null
        )?.use { c ->
            if (c.moveToFirst()) {
                return@withContext (c.getString(0) ?: "Трек") to (c.getString(1) ?: "")
            }
        }
        "Трек" to ""
    }
}