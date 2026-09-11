package com.wikrytas.coolplayer.data

import android.content.Context
import android.content.SharedPreferences

class SessionStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("coolplayer_session", Context.MODE_PRIVATE)

    data class Snapshot(
        val ids: List<Long>,
        val index: Int,
        val position: Long,
        val shuffle: Boolean,
        val repeat: Int,
        val speed: Float
    )

    fun saveQueue(ids: List<Long>, index: Int) {
        prefs.edit()
            .putString("queue", ids.joinToString(","))
            .putInt("index", index)
            .apply()
    }

    fun saveIndex(index: Int) {
        prefs.edit().putInt("index", index).apply()
    }

    fun savePosition(position: Long) {
        prefs.edit().putLong("position", position).apply()
    }

    fun saveModes(shuffle: Boolean, repeat: Int, speed: Float) {
        prefs.edit()
            .putBoolean("shuffle", shuffle)
            .putInt("repeat", repeat)
            .putFloat("speed", speed)
            .apply()
    }

    fun snapshot(): Snapshot? {
        val raw = prefs.getString("queue", null) ?: return null
        val ids = raw.split(",").mapNotNull { it.toLongOrNull() }
        if (ids.isEmpty()) return null
        return Snapshot(
            ids = ids,
            index = prefs.getInt("index", 0),
            position = prefs.getLong("position", 0L),
            shuffle = prefs.getBoolean("shuffle", false),
            repeat = prefs.getInt("repeat", 0),
            speed = prefs.getFloat("speed", 1f)
        )
    }

    fun clear() {
        prefs.edit().clear().apply()
    }
}