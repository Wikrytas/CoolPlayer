package com.wikrytas.coolplayer.data

import android.content.Context
import com.wikrytas.coolplayer.models.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import java.io.File

/**
 * Чинит метаданные, которые системный экстрактор прочитал в неверной кодировке
 * (UTF-8/cp1251 байты, интерпретированные как GBK, выглядят как иероглифы).
 * jaudiotagger читает байт кодировки из ID3v2 корректно — берём его версию.
 * Решение по треку кешируется, чтобы не копировать файл при каждом скане.
 */
object MetadataRepair {

    private const val PREFS = "metadata_fix"
    private const val SEP = ' '

    /** Похожа ли строка на мусор из CJK-иероглифов. */
    fun looksGarbled(s: String): Boolean {
        var letters = 0
        var cjk = 0
        for (ch in s) {
            if (!ch.isLetter() && !ch.isDigit()) continue
            letters++
            val c = ch.code
            if (c in 0x4E00..0x9FFF || c in 0x3400..0x4DBF ||
                c in 0xF900..0xFAFF || c in 0x3040..0x30FF
            ) cjk++
        }
        return letters >= 3 && cjk * 2 >= letters
    }

    suspend fun repairIfNeeded(context: Context, track: Track): Track = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = track.id.toString()

        // Уже решали однажды — не перечитываем файл
        prefs.getString(key, null)?.let { saved ->
            val parts = saved.split(SEP)
            if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
                return@withContext track.copy(title = parts[0], artist = parts[1])
            }
        }

        if (!looksGarbled(track.title) && !looksGarbled(track.artist)) return@withContext track

        val tmp = File(context.cacheDir, "meta_${track.id}.tmp")
        val fixed = try {
            context.contentResolver.openInputStream(track.uri)?.use { input ->
                tmp.outputStream().use { out -> input.copyTo(out) }
            }
            val audio = runCatching { AudioFileIO.read(tmp) }.getOrNull()
            val tag = audio?.tag
            if (tag == null) {
                track
            } else {
                val t = tag.getFirst(FieldKey.TITLE)?.trim().orEmpty()
                val a = tag.getFirst(FieldKey.ARTIST)?.trim().orEmpty()
                val fixedTitle = if (t.isNotEmpty() && !looksGarbled(t)) t else track.title
                val fixedArtist = if (a.isNotEmpty() && !looksGarbled(a)) a else track.artist
                val candidate = track.copy(title = fixedTitle, artist = fixedArtist)
                if (candidate.title != track.title || candidate.artist != track.artist) {
                    AppLogger.i("MetadataRepair", "fixed id=${track.id}: '${candidate.title}' / '${candidate.artist}'")
                }
                candidate
            }
        } catch (e: Exception) {
            AppLogger.w("MetadataRepair", "repair failed id=${track.id}: ${e.message}")
            track
        } finally {
            tmp.delete()
        }

        // Запоминаем решение (даже «не лечится»), чтобы сканы были быстрыми
        prefs.edit().putString(key, "${fixed.title}$SEP${fixed.artist}").apply()
        fixed
    }

    /** Сброс кэша починок (если пользователь сам пересохранит теги). */
    fun clearCache(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}