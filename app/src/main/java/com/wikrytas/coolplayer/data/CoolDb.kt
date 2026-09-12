package com.wikrytas.coolplayer.data

import com.wikrytas.coolplayer.models.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Наша публичная база синхротекстов (VPS). Отдаёт LRC, которые уже
 * выравнивались ранее для этого же трека (artist|title|duration ±4с).
 */
object CoolDb {
    const val PUBLIC_BASE = "http://94.228.167.78:8787"
    private const val TAG = "CoolDb"

    suspend fun fetch(track: Track): String? = withContext(Dispatchers.IO) {
        try {
            val url = "$PUBLIC_BASE/lrc?artist=${enc(track.artist)}&title=${enc(track.title)}&duration=${track.duration}"
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 8000
            if (conn.responseCode != 200) {
                conn.disconnect()
                return@withContext null
            }
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            conn.disconnect()
            val lrc = json.optString("lrc")
            if (json.optBoolean("ok") && lrc.isNotBlank()) {
                AppLogger.i(TAG, "hit: id=${track.id}")
                lrc
            } else null
        } catch (e: Exception) {
            AppLogger.d(TAG, "miss: ${e.javaClass.simpleName}")
            null
        }
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}