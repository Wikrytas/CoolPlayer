package com.wikrytas.coolplayer.data

import android.content.Context
import com.wikrytas.coolplayer.models.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Синхронизация через сервер выравнивания: локальный LAN или публичный VPS. */
object PcSync {
    private const val TAG = "PcSync"
    private const val PREFS = "pc_sync"
    private const val KEY_HOST = "host"
    private const val KEY_ENABLED = "enabled"
    private const val TOKEN = "coolplayer-lrc-2026"
    private const val POLL_MS = 4000L
    private const val JOB_TIMEOUT_MS = 15 * 60 * 1000L

    fun host(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_HOST, "") ?: ""
    fun setHost(ctx: Context, h: String) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_HOST, h).apply()
    fun enabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)
    fun setEnabled(ctx: Context, e: Boolean) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, e).apply()

    private fun normalize(raw: String): String {
        var h = raw.trim()
        if (h.isBlank()) return ""
        if (!h.startsWith("http://") && !h.startsWith("https://")) h = "http://$h"
        return h.trimEnd('/')
    }

    /** Свой хост из настроек, а если пусто — публичный сервер. */
    fun hostOrPublic(ctx: Context): String =
        normalize(host(ctx)).ifBlank { CoolDb.PUBLIC_BASE }

    suspend fun ping(ctx: Context): Boolean = withContext(Dispatchers.IO) {
        val h = hostOrPublic(ctx)
        try {
            val conn = URL("$h/ping").openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            val ok = conn.responseCode == 200
            conn.disconnect()
            if (!ok) AppLogger.w(TAG, "ping http for $h")
            ok
        } catch (e: Exception) {
            AppLogger.w(TAG, "ping failed: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    /** Шлёт plain-текст + аудио на сервер, ждёт задачу и возвращает готовый LRC или null. */
    suspend fun alignViaPc(ctx: Context, track: Track, plainLyrics: String): String? =
        withContext(Dispatchers.IO) {
            val h = hostOrPublic(ctx)
            try {
                val lyricsBytes = plainLyrics.toByteArray(Charsets.UTF_8)
                val conn = URL("$h/align").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 10000
                conn.readTimeout = 60000
                conn.setRequestProperty("Content-Type", "application/octet-stream")
                conn.setRequestProperty("X-Lyrics-Len", lyricsBytes.size.toString())
                conn.setRequestProperty("X-Token", TOKEN)
                conn.setRequestProperty("X-Artist", track.artist)
                conn.setRequestProperty("X-Title", track.title)
                conn.setRequestProperty("X-Duration", track.duration.toString())
                conn.setChunkedStreamingMode(64 * 1024)
                val out = conn.outputStream
                out.write(lyricsBytes)
                ctx.contentResolver.openInputStream(track.uri)?.use { inp ->
                    val buf = ByteArray(64 * 1024)
                    var r: Int
                    while (inp.read(buf).also { r = it } != -1) out.write(buf, 0, r)
                } ?: run {
                    out.close(); conn.disconnect()
                    AppLogger.w(TAG, "cannot read track uri")
                    return@withContext null
                }
                out.flush(); out.close()
                val code = conn.responseCode
                if (code != 200) {
                    val err = runCatching { conn.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull()
                    AppLogger.w(TAG, "align http $code: ${err?.take(200)}")
                    conn.disconnect()
                    return@withContext null
                }
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                val json = JSONObject(body)
                if (!json.optBoolean("ok")) return@withContext null
                // Старый синхронный сервер отдаёт lrc сразу
                if (!json.has("job_id")) return@withContext json.optString("lrc").takeIf { it.isNotBlank() }

                val jid = json.optString("job_id")
                AppLogger.i(TAG, "job $jid queued (queue=${json.optInt("queue")})")
                val deadline = System.currentTimeMillis() + JOB_TIMEOUT_MS
                while (System.currentTimeMillis() < deadline) {
                    delay(POLL_MS)
                    val st = runCatching {
                        val c = URL("$h/job/$jid").openConnection() as HttpURLConnection
                        c.connectTimeout = 5000
                        c.readTimeout = 10000
                        val s = if (c.responseCode == 200)
                            JSONObject(c.inputStream.bufferedReader().use { it.readText() }) else null
                        c.disconnect()
                        s
                    }.getOrNull() ?: continue
                    when (st.optString("status")) {
                        "done" -> {
                            AppLogger.i(TAG, "job $jid done")
                            return@withContext st.optString("lrc").takeIf { it.isNotBlank() }
                        }
                        "failed" -> {
                            AppLogger.w(TAG, "job $jid failed: ${st.optString("error")}")
                            return@withContext null
                        }
                    }
                }
                AppLogger.w(TAG, "job $jid timeout")
                null
            } catch (e: Exception) {
                AppLogger.w(TAG, "align failed: ${e.javaClass.simpleName}: ${e.message}")
                null
            }
        }
}