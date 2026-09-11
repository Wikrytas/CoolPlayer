package com.wikrytas.coolplayer.data

import android.content.Context
import android.net.Uri
import android.util.Log
import android.util.LruCache
import com.wikrytas.coolplayer.models.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

class CoverRepository private constructor(private val context: Context) {

    companion object {
        private const val TAG = "Covers"

        @Volatile
        private var instance: CoverRepository? = null

        fun getInstance(context: Context): CoverRepository {
            return instance ?: synchronized(this) {
                instance ?: CoverRepository(context.applicationContext).also { instance = it }
            }
        }

        private val uriCache = LruCache<Long, Uri>(128)
        private val inFlight = ConcurrentHashMap<Long, CompletableDeferred<Uri?>>()
        private val networkSemaphore = Semaphore(3)
    }

    private val coversDir: File
        get() = File(context.filesDir, "covers").apply { mkdirs() }

    private val _coverSaved = MutableSharedFlow<Long>(extraBufferCapacity = 16)
    val coverSaved: SharedFlow<Long> = _coverSaved

    fun cachedCoverUri(track: Track): Uri? {
        uriCache.get(track.id)?.let { return it }
        val f = File(coversDir, "${track.id}.jpg")
        return if (f.exists() && f.length() > 0) {
            val uri = Uri.fromFile(f)
            uriCache.put(track.id, uri)
            uri
        } else null
    }

    private fun isMissing(trackId: Long): Boolean =
        File(coversDir, "$trackId.missing").exists()

    private fun markMissing(trackId: Long) {
        runCatching { File(coversDir, "$trackId.missing").writeBytes(ByteArray(0)) }
    }

    fun clearCoverCache(trackId: Long) {
        AppLogger.d(TAG, "clearCoverCache: id=$trackId")
        runCatching {
            File(coversDir, "$trackId.jpg").delete()
            File(coversDir, "$trackId.missing").delete()
            uriCache.remove(trackId)
        }
    }

    suspend fun resolveCover(track: Track): Uri? = withContext(Dispatchers.IO) {
        uriCache.get(track.id)?.let {
            AppLogger.d(TAG, "resolve id=${track.id}: memory cache hit")
            return@withContext it
        }
        cachedCoverUri(track)?.let {
            AppLogger.d(TAG, "resolve id=${track.id}: file cache hit")
            return@withContext it
        }

        if (isMissing(track.id)) {
            AppLogger.d(TAG, "resolve id=${track.id}: missing-marker hit, skip network")
            return@withContext null
        }

        inFlight[track.id]?.let { existing ->
            AppLogger.d(TAG, "resolve id=${track.id}: joining in-flight request")
            return@withContext existing.await()
        }
        val deferred = CompletableDeferred<Uri?>()
        val previous = inFlight.putIfAbsent(track.id, deferred)
        if (previous != null) return@withContext previous.await()

        AppLogger.d(TAG, "resolve id=${track.id}: start full resolve '${track.title}'")
        try {
            val result = resolveInternal(track)
            if (result != null) {
                uriCache.put(track.id, result)
            } else {
                markMissing(track.id)
            }
            AppLogger.i(TAG, "resolve id=${track.id}: result=${if (result != null) "ok" else "not found"}")
            deferred.complete(result)
            result
        } catch (e: CancellationException) {
            deferred.cancel(e)
            throw e
        } catch (e: Exception) {
            AppLogger.e(TAG, "resolve id=${track.id} failed", e)
            deferred.completeExceptionally(e)
            throw e
        } finally {
            inFlight.remove(track.id)
        }
    }

    private suspend fun resolveInternal(track: Track): Uri? {
        track.albumArtUri?.let { uri ->
            readBytesFromContent(uri)?.let { bytes ->
                if (bytes.isNotEmpty()) {
                    AppLogger.d(TAG, "id=${track.id}: cover from MediaStore albumArt")
                    return saveToCache(track, bytes)
                }
            }
        }

        val embedded = withContext(Dispatchers.Default) { extractEmbedded(track) }
        if (embedded != null) {
            AppLogger.d(TAG, "id=${track.id}: cover from embedded picture")
            return saveToCache(track, embedded)
        }

        return networkSemaphore.withPermit {
            coroutineScope {
                val cleanTitle = cleanQuery(track.title)
                val cleanArtist = cleanQuery(track.artist)
                    .takeIf { it.isNotBlank() && !it.contains("unknown", ignoreCase = true) }

                AppLogger.d(TAG, "id=${track.id}: network race start ('${cleanArtist ?: "-"}' / '$cleanTitle')")
                val itunesDeferred = async { downloadFromItunes(cleanArtist, cleanTitle) }
                val deezerDeferred = async { downloadFromDeezer(cleanArtist, cleanTitle) }
                val deferreds = listOf(itunesDeferred, deezerDeferred)

                var found: ByteArray? = null
                var source = ""
                for ((i, d) in deferreds.withIndex()) {
                    try {
                        val bytes = d.await()
                        if (bytes != null && bytes.isNotEmpty()) {
                            found = bytes
                            source = if (i == 0) "itunes" else "deezer"
                            deferreds.forEach { if (it != d) it.cancel() }
                            break
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Download failed: ${e.message}")
                    }
                }
                if (found != null) {
                    AppLogger.i(TAG, "id=${track.id}: network cover from $source, ${found.size} bytes")
                }
                found?.let { saveToCache(track, it) }
            }
        }
    }

    /** Единая очистка: схема http(s), домены, скобки, теги, слеши, лишние пробелы. */
    private fun cleanQuery(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return raw
            .replace(Regex("\\([^)]*\\)"), "")
            .replace(Regex("\\[[^]]*]"), "")
            .replace(Regex("<[^>]+>"), "")
            .replace(Regex("https?://", RegexOption.IGNORE_CASE), "")
            .replace(Regex("www\\.[a-z.]+", RegexOption.IGNORE_CASE), "")
            .replace(Regex("[a-z0-9-]+\\.(ru|com|net|org|click|su|io|me|site|fm|info|uk)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("[/\\\\|_]+"), " ")
            .replace(Regex("[-]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun saveToCache(track: Track, bytes: ByteArray): Uri? = try {
        val file = File(coversDir, "${track.id}.jpg")
        val tmp = File(coversDir, "${track.id}.tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(file)) {
            file.writeBytes(bytes)
            tmp.delete()
        }
        File(coversDir, "${track.id}.missing").delete()
        _coverSaved.tryEmit(track.id)
        Uri.fromFile(file)
    } catch (e: Exception) {
        AppLogger.e(TAG, "saveToCache id=${track.id} failed", e)
        null
    }

    private fun extractEmbedded(track: Track): ByteArray? = try {
        val retriever = android.media.MediaMetadataRetriever()
        try {
            context.contentResolver.openFileDescriptor(track.uri, "r")?.use { pfd ->
                retriever.setDataSource(pfd.fileDescriptor)
                retriever.embeddedPicture
            } ?: run {
                retriever.setDataSource(context, track.uri)
                retriever.embeddedPicture
            }
        } finally {
            runCatching { retriever.release() }
        }
    } catch (e: Exception) {
        Log.w(TAG, "Failed to extract embedded cover", e)
        null
    }

    private fun readBytesFromContent(uri: Uri): ByteArray? = try {
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
    } catch (e: Exception) {
        null
    }

    private fun downloadFromItunes(artist: String?, title: String): ByteArray? {
        if (title.isBlank()) return null
        return try {
            val query = listOfNotNull(artist?.takeIf { it.isNotBlank() }, title).joinToString(" ")
            val url = URL("https://itunes.apple.com/search?term=${URLEncoder.encode(query, "UTF-8")}&media=music&limit=1")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 4000
            conn.readTimeout = 4000
            if (conn.responseCode != 200) { conn.disconnect(); return null }
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            conn.disconnect()
            val results = json.optJSONArray("results") ?: return null
            var art = results.optJSONObject(0)?.optString("artworkUrl100", "") ?: return null
            if (art.isEmpty()) return null
            art = art.replace("100x100bb", "600x600bb").replace("100x100", "600x600")
            downloadImage(art)
        } catch (e: Exception) { null }
    }

    private fun downloadFromDeezer(artist: String?, title: String): ByteArray? {
        if (title.isBlank()) return null
        return try {
            val query = listOfNotNull(artist?.takeIf { it.isNotBlank() }, title).joinToString(" ")
            val url = URL("https://api.deezer.com/search?q=${URLEncoder.encode(query, "UTF-8")}&limit=1")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 4000
            conn.readTimeout = 6000
            if (conn.responseCode != 200) { conn.disconnect(); return null }
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            conn.disconnect()
            val data = json.optJSONArray("data") ?: return null
            var cover = data.optJSONObject(0)?.optJSONObject("album")?.optString("cover_xl", "") ?: ""
            if (cover.isEmpty()) cover = data.optJSONObject(0)?.optJSONObject("album")?.optString("cover_big", "") ?: ""
            if (cover.isEmpty()) return null
            downloadImage(cover)
        } catch (e: Exception) { null }
    }

    private fun downloadImage(urlString: String): ByteArray? = try {
        val conn = URL(urlString).openConnection() as HttpURLConnection
        conn.connectTimeout = 6000
        conn.readTimeout = 6000
        conn.instanceFollowRedirects = true
        val bytes = conn.inputStream.use { it.readBytes() }
        conn.disconnect()
        bytes
    } catch (e: Exception) { null }
}