package com.wikrytas.coolplayer.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.statsDataStore by preferencesDataStore(name = "play_stats")

/**
 * Статистика прослушиваний на DataStore (единая система с настройками).
 * Хранится как stringSet записей "id=count".
 */
class StatsRepository(private val context: Context) {

    companion object {
        private val KEY = stringSetPreferencesKey("counts")
    }

    /** Живой поток счётчиков: id → количество прослушиваний. */
    val countsFlow: Flow<Map<Long, Int>> = context.statsDataStore.data.map { prefs ->
        parse(prefs[KEY])
    }

    /** Разовый снимок (для фоновых задач). */
    suspend fun counts(): Map<Long, Int> = countsFlow.first()

    suspend fun increment(trackId: Long) {
        context.statsDataStore.edit { prefs ->
            val current = parse(prefs[KEY]).toMutableMap()
            current[trackId] = (current[trackId] ?: 0) + 1
            prefs[KEY] = serialize(current)
        }
    }

    private fun parse(set: Set<String>?): Map<Long, Int> =
        set?.mapNotNull { entry ->
            val parts = entry.split("=")
            if (parts.size == 2) {
                val id = parts[0].toLongOrNull()
                val c = parts[1].toIntOrNull()
                if (id != null && c != null && c > 0) id to c else null
            } else null
        }?.toMap() ?: emptyMap()

    private fun serialize(map: Map<Long, Int>): Set<String> =
        map.map { (id, c) -> "$id=$c" }.toSet()
}