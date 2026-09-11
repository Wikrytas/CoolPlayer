package com.wikrytas.coolplayer.ui.theme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import android.util.LruCache
import androidx.palette.graphics.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object DynamicThemeExtractor {

    private const val TAG = "DynamicTheme"
    
    // Кэш тем — чтобы не пересчитывать для уже обработанных треков
    private val themeCache = LruCache<Long, PlayerTheme>(32)
    
    // FIX #7: Кэш Bitmap — чтобы не читать файл дважды
    private val bitmapCache = LruCache<Long, Bitmap>(16)

    fun getCached(trackId: Long): PlayerTheme? {
        return themeCache.get(trackId)
    }

    suspend fun extractTheme(
        context: Context,
        trackId: Long,
        coverUri: Uri,
        fallbackTheme: PlayerTheme
    ): PlayerTheme {
        // Проверяем кэш тем
        themeCache.get(trackId)?.let { return it }

        return try {
            // FIX #5: Вся тяжёлая работа в Dispatchers.Default (не Main)
            withContext(Dispatchers.Default) {
                val bitmap = getBitmap(context, trackId, coverUri) ?: return@withContext fallbackTheme
                
                val palette = Palette.from(bitmap)
                    .maximumColorCount(32)
                    .generate()
                
                val vibrant = palette.vibrantSwatch
                val darkVibrant = palette.darkVibrantSwatch
                val lightVibrant = palette.lightVibrantSwatch
                val darkMuted = palette.darkMutedSwatch
                
                val accent = vibrant?.rgb?.let { android.graphics.Color.valueOf(it).toComposeColor() }
                    ?: darkVibrant?.rgb?.let { android.graphics.Color.valueOf(it).toComposeColor() }
                    ?: fallbackTheme.accent
                
                val backgroundTop = darkMuted?.rgb?.let { 
                    android.graphics.Color.valueOf(it).toComposeColor().copy(alpha = 0.95f) 
                } ?: fallbackTheme.backgroundTop
                
                val backgroundBottom = darkVibrant?.rgb?.let { 
                    android.graphics.Color.valueOf(it).toComposeColor().copy(alpha = 0.8f) 
                } ?: fallbackTheme.backgroundBottom
                
                val newTheme = PlayerTheme(
                    name = "Dynamic",
                    accent = accent,
                    backgroundTop = backgroundTop,
                    backgroundBottom = backgroundBottom
                )
                
                themeCache.put(trackId, newTheme)
                newTheme
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract theme", e)
            fallbackTheme
        }
    }

    // FIX #7: Читаем Bitmap один раз и кэшируем
    private fun getBitmap(context: Context, trackId: Long, uri: Uri): Bitmap? {
        bitmapCache.get(trackId)?.let { return it }
        
        val bitmap = try {
            val options = BitmapFactory.Options().apply {
                inSampleSize = 4  // Уменьшаем для экономии памяти
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to decode bitmap", e)
            null
        }
        
        if (bitmap != null) {
            bitmapCache.put(trackId, bitmap)
        }
        return bitmap
    }

    // Extension для конвертации android.graphics.Color в Compose Color
    private fun android.graphics.Color.toComposeColor(): androidx.compose.ui.graphics.Color {
        return androidx.compose.ui.graphics.Color(this.toArgb())
    }
    
    private fun Int.toComposeColor(): androidx.compose.ui.graphics.Color {
        return androidx.compose.ui.graphics.Color(this)
    }
}