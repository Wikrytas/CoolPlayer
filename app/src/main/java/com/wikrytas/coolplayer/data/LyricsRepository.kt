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
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/** Сетевая ошибка (таймаут/5xx) — отличаем от «в базе нет». */
private class LyricsNetworkException(msg: String) : Exception(msg)

/** Кандидат текста из онлайн-источника (для ручного выбора и скоринга). */
data class LyricsCandidate(
    val source: String,        // "lrclib" / "netease" / "lyrics.ovh"
    val remoteId: Long?,       // id в источнике
    val artist: String,
    val title: String,
    val content: String,
    val synced: Boolean,
    val durationMs: Long?,
    val score: Float           // 0..1
)

class LyricsRepository(private val context: Context) {

    companion object {
        private const val TAG = "Lyrics"
        private const val CACHE_DIR = "lyrics"
        private const val MIN_LYRICS_LENGTH = 15
        private const val MIN_SCORE = 0.55f
        private const val DURATION_TOLERANCE_MS = 4000L
        private const val MISSING_TTL_MS = 7L * 24 * 60 * 60 * 1000
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

    // ────────────────────────── Публичный API ──────────────────────────

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
            AppLogger.d(TAG, "cached id=${track.id}, synced=$isSynced, len=${content.length}")
        } catch (e: Exception) {
            AppLogger.w(TAG, "cacheLyrics failed id=${track.id}", e)
        }
    }

    /** TTL: метка «не найдено» протухает раз в неделю. */
    private fun isMissing(trackId: Long): Boolean {
        val f = File(cacheDir, "$trackId.missing")
        if (!f.exists()) return false
        return System.currentTimeMillis() - f.lastModified() < MISSING_TTL_MS
    }

    private fun markMissing(trackId: Long) {
        runCatching { File(cacheDir, "$trackId.missing").writeBytes(ByteArray(0)) }
    }

    fun clearCache(track: Track) {
        AppLogger.d(TAG, "clearCache id=${track.id}")
        listOf("lrc", "txt", "missing").forEach {
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
            // Первый запрос отменён (пользователь ушёл с экрана) — не роняем ожидающих
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

    // ────────────────────────── Внутренний резолв ──────────────────────────

    private suspend fun resolveInternal(track: Track, forceOnline: Boolean, useOnline: Boolean): String? =
        withContext(Dispatchers.IO) {
            if (!forceOnline) {
                if (isMissing(track.id)) {
                    AppLogger.d(TAG, "resolve id=${track.id}: missing-marker hit")
                    return@withContext null
                }
                getCachedLyrics(track)?.let {
                    AppLogger.d(TAG, "resolve id=${track.id}: cache hit, len=${it.length}")
                    return@withContext it
                }
                readEmbeddedLyrics(track)?.let { content ->
                    AppLogger.d(TAG, "resolve id=${track.id}: embedded hit, len=${content.length}")
                    cacheLyrics(track, content, content.contains(LRC_REGEX))
                    return@withContext content
                }
                readLocalLrc(track)?.let { content ->
                    AppLogger.d(TAG, "resolve id=${track.id}: local .lrc hit, len=${content.length}")
                    cacheLyrics(track, content, content.contains(LRC_REGEX))
                    return@withContext content
                }
            }

            if (forceOnline || useOnline) {
                AppLogger.d(TAG, "resolve id=${track.id}: online search start (force=$forceOnline)")
                when (val res = fetchOnline(track)) {
                    is OnlineResult.Found -> {
                        AppLogger.i(TAG, "resolve id=${track.id}: online OK src=${res.candidate?.source}, len=${res.content.length}, synced=${res.content.contains(LRC_REGEX)}")
                        cacheLyrics(track, res.content, res.content.contains(LRC_REGEX))
                        File(cacheDir, "${track.id}.missing").delete()
                        return@withContext res.content
                    }
                    OnlineResult.NotFound -> {
                        if (!forceOnline) {
                            AppLogger.d(TAG, "resolve id=${track.id}: definitively not found, mark missing")
                            markMissing(track.id)
                        }
                        return@withContext null
                    }
                    OnlineResult.Error -> {
                        AppLogger.w(TAG, "resolve id=${track.id}: network error, will retry later")
                        return@withContext null
                    }
                }
            }
            null
        }

    private suspend fun fetchOnline(track: Track): OnlineResult = withContext(Dispatchers.IO) {
        // 1) LRCLIB (основной)
        val candidates = searchCandidates(track) ?: return@withContext OnlineResult.Error
        val best = candidates.firstOrNull()
        if (best != null && best.score >= MIN_SCORE) {
            return@withContext OnlineResult.Found(best.content, best)
        }
        // 2) NetEase (fallback: у LRCLIB нет — у китайцев может быть)
        val ne = fetchNeteaseCandidates(track.artist, track.title)
            .map { it.copy(score = scoreCandidate(it, track)) }
            .filter { it.score >= MIN_SCORE }
            .sortedByDescending { it.score }
        if (ne.isNotEmpty()) {
            AppLogger.i(TAG, "netease match: score=${"%.2f".format(ne.first().score)}")
            return@withContext OnlineResult.Found(ne.first().content, ne.first())
        }
        // 3) lyrics.ovh (последний фолбэк, обычно plain)
        val ovh = fetchLyricsOvh(track.artist, track.title)
        if (ovh != null) {
            val scored = ovh.copy(score = scoreCandidate(ovh, track))
            if (scored.score >= MIN_SCORE) return@withContext OnlineResult.Found(scored.content, scored)
        }
        OnlineResult.NotFound
    }

    // ────────────────────────── Уровень 1: лестница + скоринг ──────────────────────────

    /** Автопоиск LRCLIB: отсортированные кандидаты. null = сетевая ошибка (отлично от «пусто»). */
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

        if (found.isEmpty()) {
            AppLogger.d(TAG, "lrclib: no results for id=${track.id}")
        }

        found.values
            .map { it.copy(score = scoreCandidate(it, track)) }
            .sortedWith(compareByDescending<LyricsCandidate> { it.synced }.thenByDescending { it.score })
            .toList()
    }

    /** Ручной поиск по произвольному запросу. Сетевые ошибки НЕ падают наружу. */
    suspend fun searchLyricsCandidates(query: String, syncedOnly: Boolean = false): List<LyricsCandidate> =
        withContext(Dispatchers.IO) {
            val cleaned = cleanPart(query)
            if (cleaned.isBlank()) return@withContext emptyList()

            val results = LinkedHashMap<String, LyricsCandidate>()

            // LRCLIB — ошибки глотаем, идём дальше по цепочке
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
                val ne = if (parts.size == 2)
                    fetchNeteaseCandidates(parts[0].trim(), parts[1].trim())
                else fetchNeteaseCandidates("", cleaned)
                ne.forEach { c -> results.putIfAbsent("${c.artist}|${c.title}|${c.content.hashCode()}", c) }
                list = results.values.toList()
                if (syncedOnly) list = list.filter { it.synced }
            }

            if (list.isEmpty()) {
                val ovh = fetchLyricsOvh("", cleaned)
                if (ovh != null) results[ovh.title] = ovh
                list = results.values.toList()
                if (syncedOnly) list = list.filter { it.synced }
            }

            list.map { c -> c.copy(score = queryMatchScore(c, cleaned)) }
                .sortedWith(compareByDescending<LyricsCandidate> { it.synced }.thenByDescending { it.score })
                .toList()
        }

    /** Ручной поиск по раздельным полям artist/title (из LyricsCover). Сетевые ошибки НЕ падают наружу. */
    suspend fun searchByQuery(artist: String, title: String, syncedOnly: Boolean = false): List<LyricsCandidate> =
        withContext(Dispatchers.IO) {
            val results = LinkedHashMap<String, LyricsCandidate>()
            val a = artist.trim()
            val t = title.trim()

            try {
                if (a.isNotBlank() && t.isNotBlank()) {
                    fetchLrclibCandidates(a, t).forEach { c ->
                        results.putIfAbsent("${c.artist}|${c.title}|${c.content.hashCode()}", c)
                    }
                }
                if (t.isNotBlank()) {
                    fetchLrclibCandidates("", t).forEach { c ->
                        results.putIfAbsent("${c.artist}|${c.title}|${c.content.hashCode()}", c)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: LyricsNetworkException) {
                AppLogger.w(TAG, "searchByQuery: lrclib unavailable (${e.message})")
            }

            var list = results.values.toList()
            if (syncedOnly) list = list.filter { it.synced }

            // NetEase-фолбэк (внутри сам всё глотает)
            if (list.isEmpty()) {
                fetchNeteaseCandidates(a, t).forEach { c ->
                    results.putIfAbsent("${c.artist}|${c.title}|${c.content.hashCode()}", c)
                }
                list = results.values.toList()
                if (syncedOnly) list = list.filter { it.synced }
            }
            list
        }

    /** Публикация текста в LRCLIB. Для текстов НЕ из LRCLIB (netease/ovh/будущий aeneas).
     *  Неофициальная схема /api/requests — если отвалится, просто не публикуем. */
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

    /** Лестница LRCLIB: точный get → структурный search → свободный q=. Ошибки пробрасываются. */
    private suspend fun fetchLrclibCandidates(artist: String, title: String): List<LyricsCandidate> =
        withContext(Dispatchers.IO) {
            // 1) Точный match
            if (artist.isNotBlank() && title.isNotBlank()) {
                getJson("https://lrclib.net/api/get?artist_name=${enc(artist)}&track_name=${enc(title)}")
                    ?.let { obj -> candidateFromJson(obj)?.let { return@withContext listOf(it) } }
                delay(300)
            }
            // 2) Структурный search
            if (title.isNotBlank()) {
                val p = buildString {
                    append("track_name=").append(enc(title))
                    if (artist.isNotBlank()) append("&artist_name=").append(enc(artist))
                }
                val structured = searchJson("https://lrclib.net/api/search?$p")
                if (structured.isNotEmpty()) return@withContext structured
                delay(300)
            }
            // 3) Свободный текст
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

    // ────────────────────────── NetEase (неофициальный фолбэк) ──────────────────────────

    /** NetEase Cloud Music: search → lyric. Неофициальное API, без гарантий;
     *  любая ошибка = пустой результат (это фолбэк, не источник истины). */
    private suspend fun fetchNeteaseCandidates(artist: String, title: String): List<LyricsCandidate> =
        withContext(Dispatchers.IO) {
            val q = listOf(artist, title).filter { it.isNotBlank() }.joinToString(" ")
            if (q.isBlank()) return@withContext emptyList()
            try {
                val obj = getJson("https://music.163.com/api/search/get?s=${enc(q)}&limit=8&type=1")
                    ?: return@withContext emptyList()
                val songs = obj.optJSONObject("result")?.optJSONArray("songs") ?: return@withContext emptyList()
                val results = LinkedHashMap<String, LyricsCandidate>()
                var checked = 0
                for (i in 0 until songs.length()) {
                    if (checked >= 5) break
                    val s = songs.optJSONObject(i) ?: continue
                    val id = s.optLong("id"); if (id <= 0) continue
                    val name = s.optString("name", "")
                    val art = s.optJSONArray("artists")?.optJSONObject(0)?.optString("name", "") ?: ""
                    // берём только близкие по названию, иначе уйдём в мусор
                    val titleRef = title.ifBlank { name }
                    if (tokenSimilarity(normalize(name), normalize(titleRef)) < 0.45f) continue
                    checked++
                    val lrcObj = try {
                        getJson("https://music.163.com/api/song/lyric?id=$id&lv=1&kv=1&tv=-1")
                    } catch (e: Exception) { null } ?: continue
                    val lyric = lrcObj.optJSONObject("lrc")?.optString("lyric", "") ?: ""
                    if (lyric.length < MIN_LYRICS_LENGTH) continue
                    val c = LyricsCandidate(
                        source = "netease",
                        remoteId = id,
                        artist = art,
                        title = name,
                        content = lyric,
                        synced = lyric.contains(LRC_REGEX),
                        durationMs = s.optLong("duration").takeIf { it > 0 },
                        score = 0f
                    )
                    results.putIfAbsent("${c.artist}|${c.title}|${c.content.hashCode()}", c)
                    if (c.synced) break
                    delay(250)
                }
                results.values.toList()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.w(TAG, "netease failed: ${e.message}")
                emptyList()
            }
        }

    // ────────────────────────── HTTP ──────────────────────────

    private suspend fun getJson(url: String): JSONObject? {
        val body = httpGet(url) ?: return null
        return runCatching { JSONObject(body) }.getOrNull()
    }

    private suspend fun getJsonArray(url: String): JSONArray? {
        val body = httpGet(url) ?: return null
        return runCatching { JSONArray(body) }.getOrNull()
    }

    /** GET с одним повтором при 429/5xx. null = 404 (нет данных), исключение = сбой сети. */
    private suspend fun httpGet(url: String, attempt: Int = 0): String? = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("User-Agent", "CoolPlayer/1.1 (android)")
            val code = conn.responseCode
            if (code == 429 || code >= 500) {
                AppLogger.w(TAG, "http $code for $url")
                if (attempt == 0) {
                    conn.disconnect()
                    delay(1500)
                    return@withContext httpGet(url, 1)
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

    /** lyrics.ovh: exact-match, plain-тексты. */
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
                LyricsCandidate(
                    source = "lyrics.ovh",
                    remoteId = null,
                    artist = artist,
                    title = title,
                    content = lyrics,
                    synced = false,
                    durationMs = null,
                    score = 0f
                )
            } catch (e: Exception) {
                AppLogger.w(TAG, "lyrics.ovh failed: ${e.message}")
                null
            }
        }

    // ────────────────────────── Нормализация и скоринг ──────────────────────────

    private fun titleVariants(raw: String): List<String> {
        val base = cleanPart(raw)
        val noDash = base.substringBefore(" - ").trim()
        val noFeat = base.replace(Regex("(?i)\\s*feat\\.?\\s.*$"), "").trim()
        return listOf(base, noDash, noFeat)
            .map { it.trim() }
            .distinct()
            .filter { it.isNotBlank() }
    }

    private fun artistVariants(raw: String): List<String> =
        listOf(raw.split(",")[0])
            .map { cleanPart(it) }
            .filter { it.isNotBlank() && !it.contains("unknown", true) }
            .ifEmpty { listOf("") }

    private fun normalize(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-z0-9а-яё ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    /** Скоринг кандидата относительно трека. */
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

    /** Скоринг для ручного поиска: совпадение токенов с запросом. */
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

    // ────────────────────────── Локальные источники ──────────────────────────

    fun readEmbeddedLyrics(track: Track): String? {
        return try {
            val extension = getFileExtension(track)
            val tempFile = File(context.cacheDir, "temp_read_${track.id}.$extension")
            context.contentResolver.openInputStream(track.uri)?.use { input ->
                tempFile.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            try {
                val audio = AudioFileIO.read(tempFile)
                val tag = audio.tag
                tag?.getFirst(FieldKey.LYRICS)?.takeIf { it.isNotBlank() }
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
            val lrcFileName = "$baseName.lrc"
            val filesProjection = arrayOf(MediaStore.Files.FileColumns.DATA)
            val filesSelection =
                "${MediaStore.Files.FileColumns.RELATIVE_PATH} = ? AND ${MediaStore.Files.FileColumns.DISPLAY_NAME} = ?"
            val filesArgs = arrayOf(relativePath, lrcFileName)
            val filesCursor = resolver.query(
                MediaStore.Files.getContentUri("external"),
                filesProjection, filesSelection, filesArgs, null
            )
            val lrcPath = filesCursor?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: return null

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

    // ────────────────────────── Встраивание в файл ──────────────────────────

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
        val mime = context.contentResolver.getType(track.uri)
        return when (mime) {
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
        AppLogger.i(TAG, "embed start id=${track.id}, ext=$extension, len=${content.length}")
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