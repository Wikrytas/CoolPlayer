package com.wikrytas.coolplayer.data

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.wikrytas.coolplayer.audio.EqPreset
import com.wikrytas.coolplayer.models.SortMode
import com.wikrytas.coolplayer.ui.theme.PlayerThemes
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.dataStore by preferencesDataStore(name = "settings")

data class AppSettings(
    val selectedThemeName: String = PlayerThemes.all.first().name,
    val dynamicEnabled: Boolean = false,
    val favorites: Set<Long> = emptySet(),
    val sortMode: SortMode = SortMode.TITLE,
    val eqPreset: EqPreset = EqPreset.FLAT,
    val lastTrackId: Long? = null,
    val hiddenTracks: Set<Long> = emptySet(),
    val introShown: Boolean = false,
    val autoLyricsOnline: Boolean = true
)

class ThemeRepository(private val context: Context) {

    companion object {
        private const val TAG = "ThemeRepository"

        private val THEME_KEY = stringPreferencesKey("selected_theme")
        private val DYNAMIC_KEY = booleanPreferencesKey("dynamic_color_enabled")
        private val FAVORITES_KEY = stringSetPreferencesKey("favorites")
        private val SORT_KEY = stringPreferencesKey("sort_mode")
        private val EQ_KEY = stringPreferencesKey("eq_preset")
        private val LAST_TRACK_KEY = longPreferencesKey("last_track_id")
        private val HIDDEN_KEY = stringSetPreferencesKey("hidden_tracks")
        private val INTRO_SHOWN_KEY = booleanPreferencesKey("intro_shown")
        private val AUTO_LYRICS_KEY = booleanPreferencesKey("auto_lyrics_online")
    }

    val settings: Flow<AppSettings> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                Log.e(TAG, "Error reading DataStore, using defaults", exception)
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { prefs ->
            AppSettings(
                selectedThemeName = prefs[THEME_KEY] ?: PlayerThemes.all.first().name,
                dynamicEnabled = prefs[DYNAMIC_KEY] ?: false,
                favorites = prefs[FAVORITES_KEY]?.mapNotNull { it.toLongOrNull() }?.toSet() ?: emptySet(),
                sortMode = SortMode.entries.find { m -> m.name == prefs[SORT_KEY] } ?: SortMode.TITLE,
                eqPreset = EqPreset.entries.find { p -> p.name == prefs[EQ_KEY] } ?: EqPreset.FLAT,
                lastTrackId = prefs[LAST_TRACK_KEY],
                hiddenTracks = prefs[HIDDEN_KEY]?.mapNotNull { it.toLongOrNull() }?.toSet() ?: emptySet(),
                introShown = prefs[INTRO_SHOWN_KEY] ?: false,
                autoLyricsOnline = prefs[AUTO_LYRICS_KEY] ?: true
            )
        }
        .distinctUntilChanged()

    val introShown: Flow<Boolean> = settings
        .map { it.introShown }
        .distinctUntilChanged()

    suspend fun saveTheme(name: String) = safeEdit { it[THEME_KEY] = name }
    suspend fun saveDynamicEnabled(enabled: Boolean) = safeEdit { it[DYNAMIC_KEY] = enabled }
    suspend fun saveSortMode(mode: SortMode) = safeEdit { it[SORT_KEY] = mode.name }
    suspend fun saveEqPreset(preset: EqPreset) = safeEdit { it[EQ_KEY] = preset.name }
    suspend fun saveLastTrack(id: Long) = safeEdit { it[LAST_TRACK_KEY] = id }
    suspend fun markIntroShown() = safeEdit { it[INTRO_SHOWN_KEY] = true }
    suspend fun saveAutoLyricsOnline(enabled: Boolean) = safeEdit { it[AUTO_LYRICS_KEY] = enabled }

    suspend fun toggleFavorite(trackId: Long) = safeEdit { prefs ->
        val current = prefs[FAVORITES_KEY]?.mapNotNull { it.toLongOrNull() }?.toMutableSet() ?: mutableSetOf()
        if (current.contains(trackId)) current.remove(trackId) else current.add(trackId)
        prefs[FAVORITES_KEY] = current.map { it.toString() }.toSet()
    }

    suspend fun hideTrack(trackId: Long) = safeEdit { prefs ->
        val current = prefs[HIDDEN_KEY]?.mapNotNull { it.toLongOrNull() }?.toMutableSet() ?: mutableSetOf()
        current.add(trackId)
        prefs[HIDDEN_KEY] = current.map { it.toString() }.toSet()
    }

    suspend fun unhideTrack(trackId: Long) = safeEdit { prefs ->
        val current = prefs[HIDDEN_KEY]?.mapNotNull { it.toLongOrNull() }?.toMutableSet() ?: mutableSetOf()
        current.remove(trackId)
        prefs[HIDDEN_KEY] = current.map { it.toString() }.toSet()
    }

    private suspend fun safeEdit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        try {
            context.dataStore.edit(block)
        } catch (e: IOException) {
            Log.e(TAG, "Error writing to DataStore", e)
        }
    }
}