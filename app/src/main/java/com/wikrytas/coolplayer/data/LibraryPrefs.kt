package com.wikrytas.coolplayer.data

import android.content.Context

/** Локальные настройки библиотеки: автофокус на текущем треке при входе. */
object LibraryPrefs {
    private const val PREFS = "library_prefs"
    private const val KEY_AUTO_SCROLL = "auto_scroll_to_current"

    fun autoScrollEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_SCROLL, true)

    fun setAutoScrollEnabled(ctx: Context, enabled: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_AUTO_SCROLL, enabled).apply()
        AppLogger.d("LibraryPrefs", "autoScroll=$enabled")
    }
}