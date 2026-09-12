package com.wikrytas.coolplayer.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Синхронизация текстов через ПК: настройки хоста + отправка аудио+текста на lrc_server. */
object PcSync {
    private const val PREFS = "pc_sync"
    private const val KEY_HOST = "host"
    private const val KEY_ENABLED = "enabled"

    fun host(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_HOST, "") ?: ""
    fun setHost(ctx: Context, h: String) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_HOST, h).apply()
    fun enabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)
    fun setEnabled(ctx: Context, e: Boolean) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, e).apply()

    suspend fun ping(ctx: Context): Boolean = withContext(Dispatchers.IO) {
        val h = host(ctx)
        if (h.isBlank()) return@withContext false
        try {
            val conn = URL("$h/ping").openConnection() as HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            val ok = conn.responseCode == 200
            conn.disconnect()
            ok
        } catch (e: Exception) {
            false
        }
    }

    /** Шлёт plain-текст + аудио на ПК, возвращает готовый LRC или null. */
    suspend fun alignViaPc(ctx: Context, trackUri: Uri, plainLyrics: String, trackId: Long): String? =
        withContext(Dispatchers.IO) {
            val h = host(ctx)
            if (h.isBlank()) return@withContext null
            try {
                val lyricsBytes = plainLyrics.toByteArray(Charsets.UTF_8)
                val conn = URL("$h/align").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 10000
                conn.readTimeout = 900000
                conn.setRequestProperty("Content-Type", "application/octet-stream")
                conn.setRequestProperty("X-Lyrics-Len", lyricsBytes.size.toString())
                conn.setChunkedStreamingMode(64 * 1024)   // метод, не свойство
                val out = conn.outputStream
                out.write(lyricsBytes)
                ctx.contentResolver.openInputStream(trackUri)?.use { inp ->
                    val buf = ByteArray(64 * 1024)
                    var r: Int
                    while (inp.read(buf).also { r = it } != -1) out.write(buf, 0, r)
                } ?: run {
                    out.close(); conn.disconnect(); return@withContext null
                }
                out.flush(); out.close()
                val code = conn.responseCode
                if (code != 200) { conn.disconnect(); return@withContext null }
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                val json = JSONObject(body)
                if (json.optBoolean("ok")) json.optString("lrc").takeIf { it.isNotBlank() } else null
            } catch (e: Exception) {
                AppLogger.w("PcSync", "align failed: ${e.message}")
                null
            }
        }
}