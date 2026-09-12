package com.wikrytas.coolplayer.data

import com.wikrytas.coolplayer.models.Track

/**
 * Он-девайс создание таймкодов из plain-текста БЕЗ Whisper:
 * пропорциональное распределение строк по длительности трека
 * (вес строки = её длина). Быстро, офлайн, монотонно.
 */
object OnDeviceAligner {
    private const val TAG = "Aligner"

    suspend fun alignPlainToTimed(track: Track, plainLines: List<String>): String? {
        val lines = plainLines.map { it.trim() }.filter { it.isNotBlank() }
        if (lines.isEmpty()) {
            AppLogger.w(TAG, "align abort: no lines id=${track.id}")
            return null
        }
        val dur = track.duration.toDouble()
        if (dur <= 0.0) {
            AppLogger.w(TAG, "align abort: duration<=0 id=${track.id}")
            return null
        }

        // Небольшие отступы intro/outro, чтобы первая/последняя строка не липли к краям
        val intro = (dur * 0.05).coerceAtMost(8000.0)
        val usable = (dur - intro - 2000.0).coerceAtLeast(1000.0)

        val weights = lines.map { it.length.coerceAtLeast(1).toDouble() }
        val total = weights.sum()
        if (total <= 0.0) return null

        val sb = StringBuilder()
        var t = intro
        var prev = -1L
        for ((i, line) in lines.withIndex()) {
            val step = usable * (weights[i] / total)
            var ts = t.toLong()
            if (ts <= prev) ts = prev + 1   // строгая монотонность
            sb.append('[').append(fmt(ts)).append(']').append(line).append('\n')
            prev = ts
            t += step
        }
        AppLogger.i(TAG, "proportional align id=${track.id}, lines=${lines.size}")
        return sb.toString()
    }

    private fun fmt(ms: Long): String {
        val m = ms / 60000
        val s = (ms % 60000) / 1000
        val cs = (ms % 1000) / 10
        return "%02d:%02d.%02d".format(m, s, cs)
    }
}