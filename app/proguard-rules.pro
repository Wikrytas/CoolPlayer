# ── Компоненты, упомянутые в манифесте (на всякий случай) ──
-keep class com.wikrytas.coolplayer.audio.PlaybackService { *; }
-keep class com.wikrytas.coolplayer.widget.MusicWidgetProvider { *; }

# ── jaudiotagger: теги читаются через рефлексию,
#    внутри есть ссылки на desktop-only классы — гасим предупреждения ──
-keep class org.jaudiotagger.** { *; }
-keep interface org.jaudiotagger.** { *; }
-keep enum org.jaudiotagger.** { *; }
-dontwarn org.jaudiotagger.**
-dontwarn java.awt.**
-dontwarn javax.sound.**
-dontwarn javax.activation.**
-dontwarn org.apache.**
-dontwarn sun.**
-dontwarn com.sun.**

# ── Coroutines / Kotlin ──
-dontwarn kotlinx.**
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# ── Читаемые стектрейсы в релизных крашах ──
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ── Модели данных (используются в Compose по имени полей) ──
-keepclassmembers class com.wikrytas.coolplayer.models.** { *; }
-keepclassmembers class com.wikrytas.coolplayer.data.LyricsCandidate { *; }