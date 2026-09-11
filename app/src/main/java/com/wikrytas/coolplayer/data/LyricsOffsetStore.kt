package com.wikrytas.coolplayer.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Пер-трековый сдвиг синхронизации текста (мс). */
class LyricsOffsetStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("lyrics_offsets", Context.MODE_PRIVATE)

    fun getOffset(trackId: Long): Long = prefs.getLong(trackId.toString(), 0L)

    suspend fun setOffset(trackId: Long, ms: Long) = withContext(Dispatchers.IO) {
        prefs.edit().putLong(trackId.toString(), ms).apply()
    }
}