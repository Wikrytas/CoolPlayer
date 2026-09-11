package com.wikrytas.coolplayer.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.wikrytas.coolplayer.models.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AudioRepository(private val context: Context) {

    companion object {
        private const val TAG = "Library"
        private const val MIN_DURATION_MS = 30_000L
    }

    suspend fun getLocalTracks(): List<Track> = withContext(Dispatchers.IO) {
        val result = LinkedHashMap<String, Track>()
        val seenIds = mutableSetOf<Long>()
        var dupSkipped = 0

        val volumes = runCatching { MediaStore.getExternalVolumeNames(context) }
            .getOrDefault(setOf(MediaStore.VOLUME_EXTERNAL))

        // Основной том первым → first-wins детерминированный
        val ordered = volumes.sortedBy { if (it == MediaStore.VOLUME_EXTERNAL) 0 else 1 }

        for (volume in ordered) {
            val volumeUri: Uri = Uri.parse("content://media/$volume/audio/media")
            dupSkipped += queryVolume(volume, volumeUri, result, seenIds)
        }

        if (dupSkipped > 0) {
            AppLogger.w(TAG, "skipped $dupSkipped duplicate raw ids across volumes (first-wins)")
        }

        result.values.sortedBy { it.title.lowercase() }
    }

    private fun queryVolume(
        volume: String,
        volumeUri: Uri,
        out: MutableMap<String, Track>,
        seen: MutableSet<Long>
    ): Int {
        var skipped = 0
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DATE_ADDED
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} >= $MIN_DURATION_MS"
        val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"

        try {
            context.contentResolver.query(volumeUri, projection, selection, null, sortOrder)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val durCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val key = "$volume:$id"
                    if (out.containsKey(key)) continue
                    // First-wins по сырому id: Track.id остаётся уникальным во всех кэшах
                    if (!seen.add(id)) {
                        skipped++
                        continue
                    }

                    val trackUri = ContentUris.withAppendedId(volumeUri, id)
                    val albumId = cursor.getLong(albumCol)
                    val albumArtUri: Uri? = runCatching {
                        Uri.parse("content://media/$volume/audio/albumart/$albumId")
                    }.getOrNull()

                    out[key] = Track(
                        id = id,
                        uri = trackUri,
                        title = cursor.getString(titleCol) ?: "Неизвестный трек",
                        artist = cursor.getString(artistCol) ?: "Неизвестный исполнитель",
                        duration = cursor.getLong(durCol),
                        albumArtUri = albumArtUri,
                        dateAdded = cursor.getLong(dateCol) * 1000L
                    )
                }
            }
        } catch (e: SecurityException) {
            AppLogger.e(TAG, "no permission on volume $volume", e)
        } catch (e: Exception) {
            AppLogger.e(TAG, "query failed: $volumeUri", e)
        }
        return skipped
    }
}