package com.wikrytas.coolplayer.audio

import android.media.audiofx.Equalizer
import android.util.Log
import com.wikrytas.coolplayer.data.AppLogger
import kotlin.math.roundToInt

enum class EqPreset(val label: String) {
    FLAT("Ровный"),
    ROCK("Рок"),
    POP("Поп"),
    JAZZ("Джаз"),
    CLASSICAL("Классика"),
    BASS("Бас")
}

class EqualizerController {
    companion object {
        private const val TAG = "Eq"
        private var eq: Equalizer? = null
        private var sessionId: Int = 0

        /** Пресет, который хотим слышать: применяется сразу при подключении эффекта. */
        private var desiredPreset: EqPreset? = null

        val isReady: Boolean get() = eq != null
        val currentSession: Int get() = sessionId
        val bandCount: Short get() = eq?.numberOfBands ?: 0

        val bandRange: IntRange
            get() = runCatching {
                val r = eq?.bandLevelRange
                if (r != null && r.size >= 2) {
                    r[0].toInt()..r[1].toInt()
                } else {
                    -1500..1500
                }
            }.getOrDefault(-1500..1500)

        fun getLevel(band: Short): Short =
            runCatching { eq?.getBandLevel(band) ?: 0 }.getOrDefault(0)

        fun setLevel(band: Short, millibels: Short) {
            val e = eq ?: return
            runCatching {
                val range = e.bandLevelRange
                if (range.size >= 2) {
                    e.setBandLevel(band, millibels.coerceIn(range[0], range[1]))
                } else {
                    e.setBandLevel(band, millibels)
                }
            }.onFailure { Log.w(TAG, "setLevel failed", it) }
        }

        fun centerFreqLabel(band: Short): String {
            val e = eq ?: return "—"
            return runCatching {
                val millihertz = e.getCenterFreq(band)
                val hz = millihertz / 1000f
                when {
                    hz <= 0f -> "—"
                    hz < 1000f -> "${hz.roundToInt()} Гц"
                    else -> String.format("%.1f кГц", hz / 1000f)
                }
            }.getOrDefault("—")
        }

        fun applyPreset(preset: EqPreset) {
            desiredPreset = preset
            val e = eq
            if (e == null) {
                AppLogger.w(TAG, "preset queued (eq not attached yet): ${preset.name}")
                return
            }
            AppLogger.i(TAG, "preset applied: ${preset.name}")
            runCatching {
                val n = e.numberOfBands
                if (n <= 0) return@runCatching
                val range = e.bandLevelRange
                val minMb = if (range.size >= 2) range[0] else -1500
                val maxMb = if (range.size >= 2) range[1] else 1500
                for (i in 0 until n) {
                    val p = if (n > 1) i.toFloat() / (n - 1) else 0.5f
                    val db = curveDb(preset, p)
                    val mb = (db * 100f).roundToInt().toShort()
                    e.setBandLevel(i.toShort(), mb.coerceIn(minMb, maxMb))
                }
            }.onFailure { Log.w(TAG, "applyPreset failed", it) }
        }

        private val xs = floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f)

        private fun curveDb(preset: EqPreset, p: Float): Float {
            val ys = when (preset) {
                EqPreset.FLAT -> floatArrayOf(0f, 0f, 0f, 0f, 0f)
                EqPreset.ROCK -> floatArrayOf(4.5f, 2f, -1.5f, 3f, 5f)
                EqPreset.POP -> floatArrayOf(-1.5f, 1f, 3.5f, 2f, -0.5f)
                EqPreset.JAZZ -> floatArrayOf(3.5f, 1f, -1.5f, 1.5f, 3.5f)
                EqPreset.CLASSICAL -> floatArrayOf(4f, 2f, -1f, 2.5f, 4.5f)
                EqPreset.BASS -> floatArrayOf(8f, 5f, 0.5f, 0f, 0f)
            }
            return lerpPoints(p, xs, ys)
        }

        private fun lerpPoints(p: Float, xs: FloatArray, ys: FloatArray): Float {
            if (p <= xs[0]) return ys[0]
            for (i in 1 until xs.size) {
                if (p <= xs[i]) {
                    val t = (p - xs[i - 1]) / (xs[i] - xs[i - 1])
                    return ys[i - 1] + (ys[i] - ys[i - 1]) * t
                }
            }
            return ys[ys.size - 1]
        }

        /** Подключение эффекта к аудиосессии плеера. Повторный вызов с той же сессией — no-op. */
        private fun initEq(newSessionId: Int) {
            if (eq != null && sessionId == newSessionId) return
            releaseEq()
            try {
                val e = Equalizer(0, newSessionId)
                e.enabled = true
                eq = e
                sessionId = newSessionId
                AppLogger.i(TAG, "init: session=$newSessionId, bands=${e.numberOfBands}")
                // Применяем пресет, выбранный до подключения (или сохранённый)
                desiredPreset?.let { p ->
                    AppLogger.i(TAG, "applying queued preset: ${p.name}")
                    applyPreset(p)
                }
            } catch (t: Throwable) {
                AppLogger.w(TAG, "init failed: session=$newSessionId, ${t.message}")
                eq = null
            }
        }

        private fun releaseEq() {
            if (eq != null) AppLogger.d(TAG, "release session=$sessionId")
            runCatching { eq?.release() }
            eq = null
            sessionId = 0
        }
    }

    fun init(sessionId: Int) = initEq(sessionId)
    fun release() = releaseEq()
}