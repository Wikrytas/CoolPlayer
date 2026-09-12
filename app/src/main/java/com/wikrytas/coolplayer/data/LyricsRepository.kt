package com.wikrytas.coolplayer.data

import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import com.wikrytas.coolplayer.models.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.Tag
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.Normalizer
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

private class LyricsNetworkException(msg: String) : Exception(msg)

data class LyricsCandidate(
    val source: String,
    val remoteId: Long?,
    val artist: String,
    val title: String,
    val content: String,
    val synced: Boolean,
    val durationMs: Long?,
    val score: Float
)

class LyricsRepository(private val context: Context) {

    companion object {
        private const val TAG = "Lyrics"
        private const val CACHE_DIR = "lyrics"
        private const val MIN_LYRICS_LENGTH = 15
        private const val MIN_SCORE = 0.55f
        private const val DURATION_TOLERANCE_MS = 4000L
        private const val MISSING_TTL_MS = 7L * 24 * 60 * 60 * 1000
        private const val NOSYNC_TTL_MS = 24L * 60 * 60 * 1000
        private val LRC_REGEX = Regex("\\[\\d{2}:\\d{2}")
    }

    private sealed interface OnlineResult {
        data class Found(val content: String, val candidate: LyricsCandidate?) : OnlineResult
        object NotFound : OnlineResult
        object Error : OnlineResult
    }

    private val cacheDir: File
        get() = File(context.filesDir, CACHE_DIR).apply { mkdirs() }

    private val inFlight = ConcurrentHashMap<Long, CompletableDeferred<String?>>()

    fun getCachedLyrics(track: Track): String? {
        val lrc = File(cacheDir, "${track.id}.lrc")
        val txt = File(cacheDir, "${track.id}.txt")
        return when {
            lrc.exists() && lrc.length() > 0 -> lrc.readText()
            txt.exists() && txt.length() > 0 -> txt.readText()
            else -> null
        }
    }

    suspend fun cacheLyrics(track: Track, content: String, isSynced: Boolean) = withContext(Dispatchers.IO) {
        try {
            val ext = if (isSynced) "lrc" else "txt"
            val tmp = File(cacheDir, "${track.id}.tmp")
            tmp.writeText(content)
            val target = File(cacheDir, "${track.id}.$ext")
            if (!tmp.renameTo(target)) {
                target.writeText(content)
                tmp.delete()
            }
            if (isSynced) File(cacheDir, "${track.id}.txt").delete()
            File(cacheDir, "${track.id}.missing").delete()
            if (isSynced) File(cacheDir, "${track.id}.nosync").delete()
            AppLogger.d(TAG, "cached id=${track.id}, synced=$isSynced, len=${content.length}")
        } catch (e: Exception) {
            AppLogger.w(TAG, "cacheLyrics failed id=${track.id}", e)
        }
    }

    private fun isMissing(trackId: Long): Boolean {
        val f = File(cacheDir, "$trackId.missing")
        if (!f.exists()) return false
        return System.currentTimeMillis() - f.lastModified() < MISSING_TTL_MS
    }

    private fun markMissing(trackId: Long) {
        runCatching { File(cacheDir, "$trackId.missing").writeBytes(ByteArray(0)) }
    }

    private fun isNoSync(trackId: Long): Boolean {
        val f = File(cacheDir, "$trackId.nosync")
        if (!f.exists()) return false
        return System.currentTimeMillis() - f.lastModified() < NOSYNC_TTL_MS
    }

    private fun markNoSync(trackId: Long) {
        AppLogger.d(TAG, "nosync marked id=$trackId")
        runCatching { File(cacheDir, "$trackId.nosync").writeBytes(ByteArray(0)) }
    }

    fun clearCache(track: Track) {
        AppLogger.d(TAG, "clearCache id=${track.id}")
        listOf("lrc", "txt", "missing", "nosync").forEach {
            runCatching { File(cacheDir, "${track.id}.$it").delete() }
        }
    }

    suspend fun resolveLyrics(track: Track, forceOnline: Boolean = false, useOnline: Boolean = true): String? {
        if (!forceOnline) {
            inFlight[track.id]?.let {
                AppLogger.d(TAG, "resolve id=${track.id}: joining in-flight")
                return it.await()
            }
        }
        val deferred = CompletableDeferred<String?>()
        val prev = inFlight.putIfAbsent(track.id, deferred)
        if (prev != null) return prev.await()
        return try {
            val r = resolveInternal(track, forceOnline, useOnline)
            deferred.complete(r)
            r
        } catch (e: CancellationException) {
            deferred.complete(null)
            throw e
        } catch (e: Exception) {
            AppLogger.e(TAG, "resolve failed id=${track.id}", e)
            deferred.completeExceptionally(e)
            throw e
        } finally {
            inFlight.remove(track.id)
        }
    }

    private suspend fun resolveInternal(track: Track, forceOnline: Boolean, useOnline: Boolean): String? =
        withContext(Dispatchers.IO) {
            var plainFallback: String? = null
            if (!forceOnline) {
                if (isMissing(track.id)) {
                    AppLogger.d(TAG, "resolve id=${track.id}: missing-marker hit")
                    return@withContext null
                }
                val lrc = File(cacheDir, "${track.id}.lrc")
                if (lrc.exists() && lrc.length() > 0) {
                    AppLogger.d(TAG, "resolve id=${track.id}: cache synced hit")
                    return@withContext lrc.readText()
                }
                val txt = File(cacheDir, "${track.id}.txt")
                if (txt.exists() && txt.length() > 0) plainFallback = txt.readText()
                readEmbeddedLyrics(track)?.let { content ->
                    if (content.contains(LRC_REGEX)) {
                        AppLogger.d(TAG, "resolve id=${track.id}: embedded synced hit")
                        cacheLyrics(track, content, true)
                        return@withContext content
                    }
                    if (plainFallback == null) plainFallback = content
                }
                readLocalLrc(track)?.let { content ->
                    if (content.contains(LRC_REGEX)) {
                        AppLogger.d(TAG, "resolve id=${track.id}: local .lrc hit")
                        cacheLyrics(track, content, true)
                        return@withContext content
                    }
                    if (plainFallback == null) plainFallback = content
                }
            }
            if (forceOnline || useOnline) {
                val skipSynced = !forceOnline && isNoSync(track.id)
                if (!skipSynced) {
                    when (val res = fetchOnline(track, syncedOnly = true)) {
                        is OnlineResult.Found -> {
                            AppLogger.i(TAG, "resolve id=${track.id}: synced OK src=${res.candidate?.source}")
                            cacheLyrics(track, res.content, true)
                            File(cacheDir, "${track.id}.missing").delete()
                            return@withContext res.content
                        }
                        OnlineResult.Error -> {
                            AppLogger.w(TAG, "resolve id=${track.id}: network error, will retry later")
                            return@withContext plainFallback
                        }
                        OnlineResult.NotFound -> {}
                    }
                } else {
                    AppLogger.d(TAG, "resolve id=${track.id}: nosync marker, skip synced phase")
                }
                when (val res = fetchOnline(track, syncedOnly = false)) {
                    is OnlineResult.Found -> {
                        AppLogger.i(TAG, "resolve id=${track.id}: plain fallback src=${res.candidate?.source}")
                        cacheLyrics(track, res.content, false)
                        markNoSync(track.id)
                        File(cacheDir, "${track.id}.missing").delete()
                        return@withContext res.content
                    }
                    else -> {}
                }
                if (!forceOnline && plainFallback == null) {
                    AppLogger.d(TAG, "resolve id=${track.id}: nothing found, mark missing")
                    markMissing(track.id)
                }
            }
            plainFallback
        }

    private suspend fun fetchOnline(track: Track, syncedOnly: Boolean): OnlineResult = withContext(Dispatchers.IO) {
        var netAlive = false
        val candidates = searchCandidates(track)
        if (candidates != null) {
            netAlive = true
            val pool = if (syncedOnly) candidates.filter { it.synced } else candidates
            val best = pool.firstOrNull()
            if (best != null && best.score >= MIN_SCORE) {
                return@withContext OnlineResult.Found(best.content, best)
            }
        } else {
            AppLogger.w(TAG, "phase: lrclib down, trying mirrors")
        }
        val kg = fetchKugouCandidates(track.artist, track.title, track.duration)
        if (kg != null) {
            netAlive = true
            kg.map { it.copy(score = scoreCandidate(it, track)) }
                .filter { it.score >= MIN_SCORE }
                .sortedByDescending { it.score }
                .firstOrNull()
                ?.let {
                    AppLogger.i(TAG, "kugou match: score=${"%.2f".format(it.score)}")
                    return@withContext OnlineResult.Found(it.content, it)
                }
        }
        val qq = fetchQqCandidates(track.artist, track.title)
        if (qq != null) {
            netAlive = true
            qq.map { it.copy(score = scoreCandidate(it, track)) }
                .filter { it.score >= MIN_SCORE }
                .sortedByDescending { it.score }
                .firstOrNull()
                ?.let {
                    AppLogger.i(TAG, "qq match: score=${"%.2f".format(it.score)}")
                    return@withContext OnlineResult.Found(it.content, it)
                }
        }
        val ne = fetchNeteaseCandidates(track.artist, track.title)
        if (ne != null) {
            netAlive = true
            ne.map { it.copy(score = scoreCandidate(it, track)) }
                .filter { it.score >= MIN_SCORE && (!syncedOnly || it.synced) }
                .sortedByDescending { it.score }
                .firstOrNull()
                ?.let {
                    AppLogger.i(TAG, "netease match: score=${"%.2f".format(it.score)}")
                    return@withContext OnlineResult.Found(it.content, it)
                }
        }
        if (!syncedOnly) {
            val ovh = fetchLyricsOvh(track.artist, track.title)
            if (ovh != null) {
                val scored = ovh.copy(score = scoreCandidate(ovh, track))
                if (scored.score >= MIN_SCORE) return@withContext OnlineResult.Found(scored.content, scored)
            }
        }
        if (netAlive) OnlineResult.NotFound else OnlineResult.Error
    }

    private suspend fun searchCandidates(track: Track): List<LyricsCandidate>? = withContext(Dispatchers.IO) {
        val variants = titleVariants(track.title)
        val artists = artistVariants(track.artist)
        val found = LinkedHashMap<String, LyricsCandidate>()
        try {
            outer@ for (artist in artists) {
                var first = true
                for (title in variants) {
                    if (!first) delay(350)
                    first = false
                    AppLogger.d(TAG, "lrclib query: '$artist $title'")
                    val cands = fetchLrclibCandidates(artist, title)
                    cands.forEach { c ->
                        val key = "${c.artist}|${c.title}|${c.content.hashCode()}"
                        found.putIfAbsent(key, c)
                    }
                    if (found.values.any { it.synced }) break@outer
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: LyricsNetworkException) {
            AppLogger.w(TAG, "lrclib network error: ${e.message}")
            return@withContext null
        }
        if (found.isEmpty()) AppLogger.d(TAG, "lrclib: no results for id=${track.id}")
        found.values
            .map { it.copy(score = scoreCandidate(it, track)) }
            .sortedWith(compareByDescending<LyricsCandidate> { it.synced }.thenByDescending { it.score })
            .toList()
    }

    suspend fun searchLyricsCandidates(query: String, syncedOnly: Boolean = false): List<LyricsCandidate> =
        withContext(Dispatchers.IO) {
            val cleaned = cleanPart(query)
            if (cleaned.isBlank()) return@withContext emptyList()
            val results = LinkedHashMap<String, LyricsCandidate>()
            try {
                fetchLrclibCandidates("", cleaned).forEach { c ->
                    results.putIfAbsent("${c.artist}|${c.title}|${c.content.hashCode()}", c)
                }
                if (" - " in cleaned) {
                    val parts = cleaned.split(" - ", limit = 2)
                    fetchLrclibCandidates(parts[0].trim(), parts.getOrNull(1)?.trim() ?: "").forEach { c ->
                        results.putIfAbsent("${c.artist}|${c.title}|${c.content.hashCode()}", c)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: LyricsNetworkException) {
                AppLogger.w(TAG, "searchLyricsCandidates: lrclib unavailable (${e.message})")
            }
            var list = results.values.toList()
            if (syncedOnly) list = list.filter { it.synced }
            if (list.isEmpty()) {
                val parts = cleaned.split(" - ", limit = 2)
                val a = if (parts.size == 2) parts[0].trim() else ""
                val t = if (parts.size == 2) parts[1].trim() else cleaned
                fetchKugouCandidates(a, t, 0L)?.forEach { c -> results.putIfAbsent(key(c), c) }
                fetchQqCandidates(a, t)?.forEach { c -> results.putIfAbsent(key(c), c) }
                fetchNeteaseCandidates(a, t)?.forEach { c -> results.putIfAbsent(key(c), c) }
                list = results.values.toList()
                if (syncedOnly) list = list.filter { it.synced }
            }
            if (list.isEmpty()) {
                val ovh = fetchLyricsOvh("", cleaned)
                if (ovh != null) results[key(ovh)] = ovh
                list = results.values.toList()
                if (syncedOnly) list = list.filter { it.synced }
            }
            list.map { c -> c.copy(score = queryMatchScore(c, cleaned)) }
                .sortedWith(compareByDescending<LyricsCandidate> { it.synced }.thenByDescending { it.score })
                .toList()
        }

    suspend fun searchByQuery(artist: String, title: String, syncedOnly: Boolean = false): List<LyricsCandidate> =
        withContext(Dispatchers.IO) {
            val results = LinkedHashMap<String, LyricsCandidate>()
            val a = artist.trim()
            val t = title.trim()
            try {
                if (a.isNotBlank() && t.isNotBlank()) {
                    fetchLrclibCandidates(a, t).forEach { c -> results.putIfAbsent(key(c), c) }
                }
                if (t.isNotBlank()) {
                    fetchLrclibCandidates("", t).forEach { c -> results.putIfAbsent(key(c), c) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: LyricsNetworkException) {
                AppLogger.w(TAG, "searchByQuery: lrclib unavailable (${e.message})")
            }
            var list = results.values.toList()
            if (syncedOnly) list = list.filter { it.synced }
            if (list.isEmpty()) {
                fetchKugouCandidates(a, t, 0L)?.forEach { c -> results.putIfAbsent(key(c), c) }
                list = results.values.toList()
                if (syncedOnly) list = list.filter { it.synced }
            }
            if (list.isEmpty()) {
                fetchQqCandidates(a, t)?.forEach { c -> results.putIfAbsent(key(c), c) }
                list = results.values.toList()
                if (syncedOnly) list = list.filter { it.synced }
            }
            if (list.isEmpty()) {
                fetchNeteaseCandidates(a, t)?.forEach { c -> results.putIfAbsent(key(c), c) }
                list = results.values.toList()
                if (syncedOnly) list = list.filter { it.synced }
            }
            list
        }

    private fun key(c: LyricsCandidate) = "${c.source}|${c.artist}|${c.title}|${c.content.hashCode()}"

    suspend fun publishToLrclib(track: Track, content: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject().apply {
                put("id", 0)
                put("trackName", track.title)
                put("artistName", track.artist)
                put("albumName", "")
                put("duration", (track.duration / 1000).toInt())
                if (content.contains(LRC_REGEX)) put("syncedLyrics", content)
                else put("plainLyrics", content)
            }
            val conn = URL("https://lrclib.net/api/requests").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("User-Agent", "CoolPlayer/1.1 (android)")
            conn.outputStream.use { it.write(json.toString().toByteArray()) }
            val ok = conn.responseCode in 200..299
            AppLogger.i(TAG, "publish to lrclib: http ${conn.responseCode}")
            conn.disconnect()
            ok
        } catch (e: Exception) {
            AppLogger.w(TAG, "publish failed: ${e.message}")
            false
        }
    }

    private suspend fun fetchLrclibCandidates(artist: String, title: String): List<LyricsCandidate> =
        withContext(Dispatchers.IO) {
            if (artist.isNotBlank() && title.isNotBlank()) {
                getJson("https://lrclib.net/api/get?artist_name=${enc(artist)}&track_name=${enc(title)}")
                    ?.let { obj -> candidateFromJson(obj)?.let { return@withContext listOf(it) } }
                delay(300)
            }
            if (title.isNotBlank()) {
                val p = buildString {
                    append("track_name=").append(enc(title))
                    if (artist.isNotBlank()) append("&artist_name=").append(enc(artist))
                }
                val structured = searchJson("https://lrclib.net/api/search?$p")
                if (structured.isNotEmpty()) return@withContext structured
                delay(300)
            }
            val q = listOf(artist, title).filter { it.isNotBlank() }.joinToString(" ")
            if (q.isBlank()) return@withContext emptyList()
            searchJson("https://lrclib.net/api/search?q=${enc(q)}")
        }

    private fun candidateFromJson(obj: JSONObject): LyricsCandidate? {
        val synced = obj.optString("syncedLyrics", "")
        val plain = obj.optString("plainLyrics", "")
        val content = when {
            synced.isNotBlank() && synced.length >= MIN_LYRICS_LENGTH -> synced
            plain.isNotBlank() && plain.length >= MIN_LYRICS_LENGTH -> plain
            else -> return null
        }
        return LyricsCandidate(
            source = "lrclib",
            remoteId = obj.optLong("id").takeIf { it > 0 },
            artist = obj.optString("artistName", ""),
            title = obj.optString("trackName", ""),
            content = content,
            synced = synced.isNotBlank() && synced.length >= MIN_LYRICS_LENGTH,
            durationMs = obj.optLong("duration").takeIf { it > 0 }?.times(1000),
            score = 0f
        )
    }

    private suspend fun searchJson(url: String): List<LyricsCandidate> = withContext(Dispatchers.IO) {
        val arr = getJsonArray(url) ?: return@withContext emptyList()
        (0 until arr.length())
            .mapNotNull { arr.optJSONObject(it)?.let(::candidateFromJson) }
            .sortedByDescending { it.synced }
    }

    private suspend fun fetchKugouCandidates(artist: String, title: String, durationMs: Long): List<LyricsCandidate>? =
        withContext(Dispatchers.IO) {
            val keyword = listOf(artist, title).filter { it.isNotBlank() }.joinToString(" - ")
            if (keyword.isBlank()) return@withContext emptyList()
            try {
                val searchUrl = "https://lyrics.kugou.com/search?ver=1&man=yes&client=pc" +
                        "&keyword=${enc(keyword)}&hash=&timelength=$durationMs&duration=$durationMs"
                val obj = getJson(searchUrl, mapOf("User-Agent" to "Mozilla/5.0"))
                    ?: return@withContext null
                val cands = obj.optJSONArray("candidates") ?: return@withContext emptyList()
                val results = LinkedHashMap<String, LyricsCandidate>()
                var checked = 0
                for (i in 0 until cands.length()) {
                    if (checked >= 3 || results.isNotEmpty()) break
                    val c = cands.optJSONObject(i) ?: continue
                    val id = c.optString("id")
                    val accesskey = c.optString("accesskey")
                    if (id.isBlank() || accesskey.isBlank()) continue
                    val song = c.optString("song")
                    val singer = c.optString("singer")
                    val dur = c.optLong("duration").takeIf { it > 0 }
                    val titleRef = title.ifBlank { song }
                    if (tokenSimilarity(normalize(song), normalize(titleRef)) < 0.45f) continue
                    checked++
                    val dlUrl = "https://lyrics.kugou.com/download?ver=1&client=pc" +
                            "&id=${enc(id)}&accesskey=${enc(accesskey)}&fmt=lrc&charset=utf8"
                    val dl = runCatching { getJson(dlUrl, mapOf("User-Agent" to "Mozilla/5.0")) }.getOrNull()
                        ?: continue
                    val b64 = dl.optString("content", "")
                    if (b64.isBlank()) continue
                    val lrc = runCatching { String(Base64.getDecoder().decode(b64), Charsets.UTF_8) }.getOrNull()
                        ?: continue
                    if (lrc.length < MIN_LYRICS_LENGTH || !lrc.contains(LRC_REGEX)) continue
                    AppLogger.d(TAG, "kugou candidate: '$singer - $song'")
                    results.putIfAbsent(
                        "${singer}|${song}|${lrc.hashCode()}",
                        LyricsCandidate("kugou", null, singer, song, lrc, true, dur, 0f)
                    )
                }
                results.values.toList()
            } catch (e: CancellationException) {
                throw e
            } catch (e: LyricsNetworkException) {
                AppLogger.w(TAG, "kugou network error: ${e.message}")
                null
            } catch (e: Exception) {
                AppLogger.w(TAG, "kugou failed: ${e.message}")
                emptyList()
            }
        }

    private suspend fun fetchQqCandidates(artist: String, title: String): List<LyricsCandidate>? =
        withContext(Dispatchers.IO) {
            val q = listOf(artist, title).filter { it.isNotBlank() }.joinToString(" ")
            if (q.isBlank()) return@withContext emptyList()
            try {
                val searchUrl = "https://c.y.qq.com/soso/fcgi-bin/client_search_cp?w=${enc(q)}&format=json&p=1&n=8"
                val obj = getJson(searchUrl, mapOf("Referer" to "https://y.qq.com/"))
                    ?: return@withContext null
                val list = obj.optJSONObject("data")?.optJSONObject("song")?.optJSONArray("list")
                    ?: return@withContext emptyList()
                val results = LinkedHashMap<String, LyricsCandidate>()
                var checked = 0
                for (i in 0 until list.length()) {
                    if (checked >= 3 || results.isNotEmpty()) break
                    val s = list.optJSONObject(i) ?: continue
                    val mid = s.optString("songmid")
                    if (mid.isBlank()) continue
                    val name = s.optString("songname")
                    val singer = s.optJSONArray("singer")?.optJSONObject(0)?.optString("name", "")
                        ?: s.optString("singername", "")
                    val titleRef = title.ifBlank { name }
                    if (tokenSimilarity(normalize(name), normalize(titleRef)) < 0.45f) continue
                    checked++
                    val lyricUrl = "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid=${enc(mid)}&format=json"
                    val l = runCatching { getJson(lyricUrl, mapOf("Referer" to "https://y.qq.com/portal/player.html")) }.getOrNull()
                        ?: continue
                    val b64 = l.optString("lyric", "")
                    if (b64.isBlank()) continue
                    val lrc = runCatching { String(Base64.getDecoder().decode(b64), Charsets.UTF_8) }.getOrNull()
                        ?: continue
                    if (lrc.length < MIN_LYRICS_LENGTH || !lrc.contains(LRC_REGEX)) continue
                    val interval = s.optLong("interval").takeIf { it > 0 }?.times(1000)
                    AppLogger.d(TAG, "qq candidate: '$singer - $name'")
                    results.putIfAbsent(
                        "${singer}|${name}|${lrc.hashCode()}",
                        LyricsCandidate("qq", null, singer, name, lrc, true, interval, 0f)
                    )
                }
                results.values.toList()
            } catch (e: CancellationException) {
                throw e
            } catch (e: LyricsNetworkException) {
                AppLogger.w(TAG, "qq network error: ${e.message}")
                null
            } catch (e: Exception) {
                AppLogger.w(TAG, "qq failed: ${e.message}")
                emptyList()
            }
        }

    private suspend fun fetchNeteaseCandidates(artist: String, title: String): List<LyricsCandidate>? =
        withContext(Dispatchers.IO) {
            val q = listOf(artist, title).filter { it.isNotBlank() }.joinToString(" ")
            if (q.isBlank()) return@withContext emptyList()
            try {
                val obj = getJson("https://music.163.com/api/search/get?s=${enc(q)}&limit=8&type=1")
                    ?: return@withContext null
                val songs = obj.optJSONObject("result")?.optJSONArray("songs") ?: return@withContext emptyList()
                val results = LinkedHashMap<String, LyricsCandidate>()
                var checked = 0
                for (i in 0 until songs.length()) {
                    if (checked >= 5) break
                    val s = songs.optJSONObject(i) ?: continue
                    val id = s.optLong("id"); if (id <= 0) continue
                    val name = s.optString("name", "")
                    val art = s.optJSONArray("artists")?.optJSONObject(0)?.optString("name", "") ?: ""
                    val titleRef = title.ifBlank { name }
                    if (tokenSimilarity(normalize(name), normalize(titleRef)) < 0.45f) continue
                    checked++
                    val lrcObj = try {
                        getJson("https://music.163.com/api/song/lyric?id=$id&lv=1&kv=1&tv=-1")
                    } catch (e: Exception) { null } ?: continue
                    val lyric = lrcObj.optJSONObject("lrc")?.optString("lyric", "") ?: ""
                    if (lyric.length < MIN_LYRICS_LENGTH) continue
                    val c = LyricsCandidate(
                        "netease", id, art, name, lyric,
                        lyric.contains(LRC_REGEX), s.optLong("duration").takeIf { it > 0 }, 0f
                    )
                    results.putIfAbsent(key(c), c)
                    if (c.synced) break
                    delay(250)
                }
                results.values.toList()
            } catch (e: CancellationException) {
                throw e
            } catch (e: LyricsNetworkException) {
                AppLogger.w(TAG, "netease network error: ${e.message}")
                null
            } catch (e: Exception) {
                AppLogger.w(TAG, "netease failed: ${e.message}")
                emptyList()
            }
        }

    private suspend fun getJson(url: String, headers: Map<String, String> = emptyMap()): JSONObject? {
        val body = httpGet(url, headers = headers) ?: return null
        return runCatching { JSONObject(body) }.getOrNull()
    }

    private suspend fun getJsonArray(url: String): JSONArray? {
        val body = httpGet(url) ?: return null
        return runCatching { JSONArray(body) }.getOrNull()
    }

    private suspend fun httpGet(url: String, attempt: Int = 0, headers: Map<String, String> = emptyMap()): String? =
        withContext(Dispatchers.IO) {
            var conn: HttpURLConnection? = null
            try {
                conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000
                conn.setRequestProperty("User-Agent", "CoolPlayer/1.1 (android)")
                headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
                val code = conn.responseCode
                if (code == 429 || code >= 500) {
                    AppLogger.w(TAG, "http $code for $url")
                    if (attempt == 0) {
                        conn.disconnect()
                        delay(1500)
                        return@withContext httpGet(url, 1, headers)
                    }
                    throw LyricsNetworkException("http $code")
                }
                if (code == 404) return@withContext null
                if (code != 200) {
                    AppLogger.w(TAG, "http $code for $url")
                    throw LyricsNetworkException("http $code")
                }
                conn.inputStream.bufferedReader().use { it.readText() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: LyricsNetworkException) {
                throw e
            } catch (e: Exception) {
                AppLogger.w(TAG, "http failed: ${e.message}")
                throw LyricsNetworkException(e.message ?: "network error")
            } finally {
                conn?.disconnect()
            }
        }

    private suspend fun fetchLyricsOvh(artist: String, title: String): LyricsCandidate? =
        withContext(Dispatchers.IO) {
            val a = artist.ifBlank { "Unknown" }
            if (title.isBlank()) return@withContext null
            try {
                val url = URL("https://api.lyrics.ovh/v1/${enc(a)}/${enc(title)}")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000
                conn.setRequestProperty("User-Agent", "CoolPlayer/1.1 (android)")
                if (conn.responseCode != 200) {
                    conn.disconnect()
                    return@withContext null
                }
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                val lyrics = JSONObject(body).optString("lyrics", "")
                if (lyrics.length < MIN_LYRICS_LENGTH) return@withContext null
                LyricsCandidate("lyrics.ovh", null, artist, title, lyrics, false, null, 0f)
            } catch (e: Exception) {
                AppLogger.w(TAG, "lyrics.ovh failed: ${e.message}")
                null
            }
        }

    private fun titleVariants(raw: String): List<String> {
        val base = cleanPart(raw)
        val noDash = base.substringBefore(" - ").trim()
        val noFeat = base.replace(Regex("(?i)\\s*feat\\.?\\s.*$"), "").trim()
        return listOf(base, noDash, noFeat).map { it.trim() }.distinct().filter { it.isNotBlank() }
    }

    private fun artistVariants(raw: String): List<String> =
        listOf(raw.split(",")[0])
            .map { cleanPart(it) }
            .filter { it.isNotBlank() && !it.contains("unknown", true) && !it.contains("неизвестн", true) }
            .ifEmpty { listOf("") }

    private fun normalize(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-z0-9а-яё ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun scoreCandidate(c: LyricsCandidate, track: Track): Float {
        val titleSim = tokenSimilarity(normalize(c.title), normalize(track.title))
        val artistSim = tokenSimilarity(normalize(c.artist), normalize(track.artist))
        var s = 0.65f * titleSim + 0.35f * artistSim
        c.durationMs?.let { d ->
            if (track.duration > 0) {
                val diff = abs(d - track.duration)
                if (diff <= DURATION_TOLERANCE_MS) s = (s + 1f) / 2f
                else if (diff > 20_000) s *= 0.7f
            }
        }
        return s
    }

    private fun queryMatchScore(c: LyricsCandidate, query: String): Float {
        val q = normalize(query)
        val combined = normalize("${c.artist} ${c.title}")
        return tokenSimilarity(combined, q)
    }

    private fun tokenSimilarity(a: String, b: String): Float {
        if (a.isBlank() || b.isBlank()) return 0f
        val ta = a.split(" ").filter { it.isNotBlank() }.toSet()
        val tb = b.split(" ").filter { it.isNotBlank() }.toSet()
        if (ta.isEmpty() || tb.isEmpty()) return 0f
        return ta.intersect(tb).size.toFloat() / ta.union(tb).size
    }

    private fun cleanPart(raw: String): String = raw
        .replace(Regex("\\([^)]*\\)"), " ")
        .replace(Regex("\\[[^]]*]"), " ")
        .replace(Regex("<[^>]+>"), " ")
        .replace(Regex("https?://", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("www\\.[a-z.]+", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("[a-z0-9-]+\\.(ru|com|net|org|click|su|io|me|site|fm|info|uk)", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("[/\\\\|_]+"), " ")
        .replace(Regex("[-]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    fun readEmbeddedLyrics(track: Track): String? {
        return try {
            val extension = getFileExtension(track)
            val tempFile = File(context.cacheDir, "temp_read_${track.id}.$extension")
            context.contentResolver.openInputStream(track.uri)?.use { input ->
                tempFile.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            try {
                val audio = AudioFileIO.read(tempFile)
                audio.tag?.getFirst(FieldKey.LYRICS)?.takeIf { it.isNotBlank() }
            } finally {
                tempFile.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "readEmbeddedLyrics failed", e)
            null
        }
    }

    fun readLocalLrc(track: Track): String? {
        return try {
            val resolver = context.contentResolver
            val projection = arrayOf(
                MediaStore.Audio.Media.RELATIVE_PATH,
                MediaStore.Audio.Media.DISPLAY_NAME
            )
            val cursor = resolver.query(track.uri, projection, null, null, null)
            val pair = cursor?.use {
                if (it.moveToFirst()) {
                    val relPath = it.getString(0) ?: return@use null
                    val dispName = it.getString(1) ?: return@use null
                    relPath to dispName
                } else null
            } ?: return null
            val (relativePath, displayName) = pair
            val baseName = displayName.substringBeforeLast(".")
            val filesProjection = arrayOf(MediaStore.Files.FileColumns.DATA)
            val filesSelection =
                "${MediaStore.Files.FileColumns.RELATIVE_PATH} = ? AND ${MediaStore.Files.FileColumns.DISPLAY_NAME} = ?"
            val filesArgs = arrayOf(relativePath, "$baseName.lrc")
            val filesCursor = resolver.query(
                MediaStore.Files.getContentUri("external"),
                filesProjection, filesSelection, filesArgs, null
            )
            val lrcPath = filesCursor?.use { if (it.moveToFirst()) it.getString(0) else null } ?: return null
            val lrcFile = File(lrcPath)
            if (!lrcFile.exists() || !lrcFile.canRead()) return null
            val encodings = listOf(Charsets.UTF_8, charset("windows-1251"), Charsets.ISO_8859_1)
            for (encoding in encodings) {
                try {
                    val content = lrcFile.readText(encoding)
                    if (content.contains(LRC_REGEX)) return content
                } catch (e: Exception) {
                    Log.w(TAG, "readLocalLrc encoding failed", e)
                }
            }
            lrcFile.readText()
        } catch (e: Exception) {
            Log.w(TAG, "readLocalLrc failed", e)
            null
        }
    }

    data class EmbedResult(
        val success: Boolean,
        val needsPermission: Boolean = false,
        val intentSender: IntentSender? = null,
        val error: String? = null
    )

    private fun getFileExtension(track: Track): String {
        val pathExt = track.uri.path?.let { path ->
            val dotIndex = path.lastIndexOf('.')
            if (dotIndex > 0 && dotIndex < path.length - 1) path.substring(dotIndex + 1).lowercase() else null
        }
        if (!pathExt.isNullOrBlank()) {
            val known = listOf("mp3", "flac", "m4a", "mp4", "ogg", "wav", "wma", "aac", "opus")
            if (known.contains(pathExt)) return pathExt
        }
        return when (context.contentResolver.getType(track.uri)) {
            "audio/mpeg" -> "mp3"
            "audio/flac", "audio/x-flac" -> "flac"
            "audio/mp4", "audio/m4a", "audio/x-m4a" -> "m4a"
            "audio/ogg", "application/ogg" -> "ogg"
            "audio/wav", "audio/x-wav" -> "wav"
            "audio/x-ms-wma" -> "wma"
            "audio/aac" -> "aac"
            "audio/opus" -> "opus"
            else -> "mp3"
        }
    }

    suspend fun embedInFile(track: Track, content: String, isSynced: Boolean): EmbedResult = withContext(Dispatchers.IO) {
        val extension = getFileExtension(track)
        val tempFile = File(context.cacheDir, "temp_embed_${track.id}_${System.currentTimeMillis()}.$extension")
        AppLogger.i(TAG, "embed start id=${track.id}, ext=$extension, len=${content.length}, synced=$isSynced")
        try {
            context.contentResolver.openInputStream(track.uri)?.use { input ->
                tempFile.outputStream().use { output -> input.copyTo(output) }
            } ?: run {
                AppLogger.e(TAG, "embed: cannot read source id=${track.id}")
                return@withContext EmbedResult(false, error = "Не удалось прочитать файл")
            }
            try {
                val audio = AudioFileIO.read(tempFile)
                val tag: Tag = audio.tag ?: run {
                    val newTag = audio.createDefaultTag()
                    audio.tag = newTag
                    newTag
                }
                tag.setField(FieldKey.LYRICS, content)
                AudioFileIO.write(audio)
            } catch (e: Exception) {
                AppLogger.e(TAG, "embed: tag write failed id=${track.id}", e)
                tempFile.delete()
                return@withContext EmbedResult(false, error = "Ошибка парсинга: ${e.message?.take(80)}")
            }
            try {
                context.contentResolver.openOutputStream(track.uri, "wt")?.use { output ->
                    tempFile.inputStream().use { input -> input.copyTo(output) }
                } ?: run {
                    tempFile.delete()
                    return@withContext EmbedResult(false, error = "Не удалось открыть файл для записи")
                }
                tempFile.delete()
                AppLogger.i(TAG, "embed OK id=${track.id}")
                EmbedResult(true)
            } catch (e: SecurityException) {
                AppLogger.w(TAG, "embed: need permission id=${track.id}")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    try {
                        val pi = MediaStore.createWriteRequest(context.contentResolver, listOf(track.uri))
                        EmbedResult(false, needsPermission = true, intentSender = pi.intentSender)
                    } catch (ex: Exception) {
                        tempFile.delete()
                        EmbedResult(false, error = "Не удалось запросить права: ${ex.message?.take(80)}")
                    }
                } else {
                    tempFile.delete()
                    EmbedResult(false, error = "Нет прав на запись файла")
                }
            }
        } catch (e: Exception) {
            tempFile.delete()
            AppLogger.e(TAG, "embed unexpected id=${track.id}", e)
            EmbedResult(false, error = "Ошибка: ${e.message?.take(80)}")
        }
    }

    suspend fun retryEmbedWithPermission(track: Track, content: String): Boolean = withContext(Dispatchers.IO) {
        val extension = getFileExtension(track)
        val tempFile = File(context.cacheDir, "temp_embed_${track.id}_${System.currentTimeMillis()}.$extension")
        AppLogger.i(TAG, "retryEmbed id=${track.id}")
        try {
            context.contentResolver.openInputStream(track.uri)?.use { input ->
                tempFile.outputStream().use { output -> input.copyTo(output) }
            } ?: return@withContext false
            val audio = AudioFileIO.read(tempFile)
            val tag: Tag = audio.tag ?: run {
                val newTag = audio.createDefaultTag()
                audio.tag = newTag
                newTag
            }
            tag.setField(FieldKey.LYRICS, content)
            AudioFileIO.write(audio)
            context.contentResolver.openOutputStream(track.uri, "wt")?.use { output ->
                tempFile.inputStream().use { input -> input.copyTo(output) }
            } ?: run {
                tempFile.delete()
                return@withContext false
            }
            tempFile.delete()
            AppLogger.i(TAG, "retryEmbed OK id=${track.id}")
            true
        } catch (e: Exception) {
            tempFile.delete()
            AppLogger.e(TAG, "retryEmbed failed id=${track.id}", e)
            false
        }
    }
}