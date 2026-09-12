package com.wikrytas.coolplayer.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Поиск текстов "по всему интернету" (последний резерв после LRCLIB/Kugou/QQ/NetEase/ovh):
 *  - Genius: публичный JSON-поиск + скрейп страницы песни;
 *  - DuckDuckGo HTML: топ-ссылки по запросу "<artist> <title> lyrics" + эвристическое
 *    извлечение самого длинного связного блока строк.
 * Всё обёрнуто в try/catch: любой сбой = пустой список, цепочка не падает.
 */
object WebLyrics {

    private const val TAG = "WebLyrics"
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36 CoolPlayer/1.1"

    suspend fun searchAll(artist: String, title: String): List<LyricsCandidate> =
        withContext(Dispatchers.IO) {
            val out = LinkedHashMap<String, LyricsCandidate>()
            searchGenius(artist, title).forEach { out.putIfAbsent(key(it), it) }
            searchWeb(artist, title).forEach { out.putIfAbsent(key(it), it) }
            out.values.toList()
        }

    private fun key(c: LyricsCandidate) = "${c.source}|${c.title}|${c.content.hashCode()}"

    // ── Genius ──
    private suspend fun searchGenius(artist: String, title: String): List<LyricsCandidate> =
        withContext(Dispatchers.IO) {
            try {
                val q = URLEncoder.encode("$artist $title", "UTF-8")
                val body = get("https://genius.com/api/search/multi?per_page=5&q=$q")
                    ?: return@withContext emptyList()
                val sections = JSONObject(body)
                    .optJSONObject("response")?.optJSONArray("sections")
                    ?: return@withContext emptyList()
                val out = mutableListOf<LyricsCandidate>()
                for (s in 0 until sections.length()) {
                    val results = sections.optJSONObject(s)?.optJSONArray("results") ?: continue
                    for (r in 0 until results.length()) {
                        val res = results.optJSONObject(r) ?: continue
                        if (res.optString("type") != "song") continue
                        val path = res.optJSONObject("result")?.optString("path", "") ?: ""
                        if (path.isBlank()) continue
                        val html = get("https://genius.com$path") ?: continue
                        val lyrics = extractLyricsBlock(stripHtml(html)) ?: continue
                        out.add(
                            LyricsCandidate(
                                source = "genius", remoteId = null, artist = artist,
                                title = title, content = lyrics, synced = false,
                                durationMs = null, score = 0f
                            )
                        )
                        if (out.size >= 2) return@withContext out
                    }
                }
                out
            } catch (e: Exception) {
                AppLogger.w(TAG, "genius failed: ${e.message}")
                emptyList()
            }
        }

    // ── Общий веб-поиск (DuckDuckGo HTML) ──
    private suspend fun searchWeb(artist: String, title: String): List<LyricsCandidate> =
        withContext(Dispatchers.IO) {
            try {
                val q = URLEncoder.encode("$artist $title lyrics", "UTF-8")
                val html = get("https://html.duckduckgo.com/html/?q=$q")
                    ?: return@withContext emptyList()
                val links = Regex("href=\"(https?://[^\"]+)\"")
                    .findAll(html)
                    .map { it.groupValues[1] }
                    .filter {
                        !it.contains("duckduckgo", true) &&
                                !it.contains("genius.com", true) &&
                                !it.contains("google.", true)
                    }
                    .distinct()
                    .take(3)
                    .toList()
                val out = mutableListOf<LyricsCandidate>()
                for (u in links) {
                    val page = get(u) ?: continue
                    val lyrics = extractLyricsBlock(stripHtml(page)) ?: continue
                    out.add(
                        LyricsCandidate(
                            source = "web", remoteId = null, artist = artist,
                            title = title, content = lyrics, synced = false,
                            durationMs = null, score = 0f
                        )
                    )
                    if (out.size >= 2) break
                }
                out
            } catch (e: Exception) {
                AppLogger.w(TAG, "web failed: ${e.message}")
                emptyList()
            }
        }

    // ── HTTP ──
    private fun get(url: String): String? = runCatching {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.setRequestProperty("User-Agent", UA)
        conn.setRequestProperty("Accept", "text/html,application/json,*/*")
        val code = conn.responseCode
        if (code != 200) {
            conn.disconnect()
            return null
        }
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()
        body
    }.getOrNull()

    // ── Разбор HTML ──
    private fun stripHtml(html: String): String = html
        .replace(Regex("(?is)<script.*?</script>"), " ")
        .replace(Regex("(?is)<style.*?</style>"), " ")
        .replace(Regex("(?is)<br\\s*/?>"), "\n")
        .replace(Regex("(?is)</p>"), "\n")
        .replace(Regex("(?is)<[^>]+>"), " ")
        .replace("&amp;", "&")
        .replace("&#39;", "'")
        .replace("&#x27;", "'")
        .replace("&apos;", "'")
        .replace("&quot;", "\"")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&nbsp;", " ")

    /** Эвристика: самый длинный связный блок «похожих на строки песни» строк. */
    private fun extractLyricsBlock(text: String): String? {
        val nav = Regex(
            "(?i)(copyright|all rights reserved|submit|login|sign in|menu|home|search|" +
                    "share|facebook|twitter|instagram|privacy|terms|recommended|related|" +
                    "more songs|writer\\(s\\)|produced by|embed|comment|ads? by|cookie)"
        )
        val lines = text.lines().map { it.trim() }.filter { it.length in 2..120 }
        var best = ""
        val cur = StringBuilder()
        fun flush() {
            val s = cur.toString().trim()
            if (s.length > best.length) best = s
            cur.setLength(0)
        }
        for (l in lines) {
            if (l.length < 3 || nav.containsMatchIn(l)) {
                flush()
                continue
            }
            cur.append(l).append('\n')
            if (cur.length > 4000) flush()
        }
        flush()
        val lineCount = best.lines().size
        return if (lineCount >= 4 && best.length >= 80) best else null
    }
}