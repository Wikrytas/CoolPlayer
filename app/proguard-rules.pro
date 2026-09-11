# ── jaudiotagger (LGPL): теги аудио, активная рефлексия ──
-keep class org.jaudiotagger.** { *; }
-keep interface org.jaudiotagger.** { *; }
-dontwarn org.jaudiotagger.**
-dontwarn java.awt.**
-dontwarn javax.sound.**

# ── Media3 / ExoPlayer ──
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# ── Coil ──
-keep class coil.** { *; }
-dontwarn coil.**

# ── Точки входа, которые вызывает система ──
-keep class com.example.coolplayer.audio.PlaybackService { *; }
-keep class com.example.coolplayer.widget.MusicWidgetProvider { *; }

# ── Модели, используемые в Compose/сериализации ──
-keep class com.example.coolplayer.models.** { *; }

# ── Enum (SortMode, EqPreset, SleepMode...) ──
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}