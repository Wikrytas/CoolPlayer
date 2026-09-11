package com.wikrytas.coolplayer.widget

import java.io.File

/**
 * Состояние виджета. Пишется из потока плеера и IO-корутин,
 * читается из потока виджета — поэтому все поля @Volatile.
 */
object WidgetState {
    @Volatile var title: String = "CoolPlayer"
    @Volatile var artist: String = ""
    @Volatile var isPlaying: Boolean = false
    @Volatile var coverFile: File? = null
}