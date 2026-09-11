package com.wikrytas.coolplayer.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.RemoteViews
import com.wikrytas.coolplayer.MainActivity
import com.wikrytas.coolplayer.R
import com.wikrytas.coolplayer.audio.PlaybackService
import com.wikrytas.coolplayer.data.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

private const val TAG = "Widget"
private const val COVER_TARGET_PX = 220

class MusicWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_UPDATE = "com.wikrytas.coolplayer.WIDGET_UPDATE"
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        AppLogger.d(TAG, "onUpdate: ids=${ids.size}")
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val views = build(context)
                ids.forEach { manager.updateAppWidget(it, views) }
            } catch (e: Exception) {
                AppLogger.w(TAG, "onUpdate failed: ${e.message}")
            } finally {
                pending.finish()
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_UPDATE) {
            val pending = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    refresh(context)
                } catch (e: Exception) {
                    AppLogger.w(TAG, "refresh failed: ${e.message}")
                } finally {
                    pending.finish()
                }
            }
        }
    }

    private fun refresh(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, MusicWidgetProvider::class.java))
        if (ids.isEmpty()) return
        AppLogger.d(
            TAG,
            "refresh: ${ids.size} widget(s), title='${WidgetState.title}', playing=${WidgetState.isPlaying}"
        )
        val views = build(context)
        ids.forEach { manager.updateAppWidget(it, views) }
    }

    private fun build(context: Context): RemoteViews {
        val v = RemoteViews(context.packageName, R.layout.widget_main)
        v.setTextViewText(R.id.w_title, WidgetState.title)
        v.setTextViewText(R.id.w_artist, WidgetState.artist)
        v.setTextViewText(R.id.w_play, if (WidgetState.isPlaying) "⏸" else "▶")

        val bmp = decodeCover(WidgetState.coverFile)
        if (bmp != null) v.setImageViewBitmap(R.id.w_cover, bmp)
        else v.setImageViewResource(R.id.w_cover, R.mipmap.ic_launcher)

        v.setOnClickPendingIntent(R.id.w_prev, servicePending(context, PlaybackService.ACTION_WIDGET_PREV))
        v.setOnClickPendingIntent(R.id.w_play, servicePending(context, PlaybackService.ACTION_WIDGET_PLAY))
        v.setOnClickPendingIntent(R.id.w_next, servicePending(context, PlaybackService.ACTION_WIDGET_NEXT))
        v.setOnClickPendingIntent(R.id.w_root, pendingOpenApp(context))
        return v
    }

    // getService вместо getForegroundService — Media3 уже держит сервис как foreground
    private fun servicePending(context: Context, action: String): PendingIntent {
        val intent = Intent(context, PlaybackService::class.java).setAction(action)
        return PendingIntent.getService(
            context, action.hashCode(), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun pendingOpenApp(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun decodeCover(f: File?): Bitmap? {
        if (f == null || !f.exists()) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, bounds)
            var sample = 1
            // Даунсэмпл по большей стороне до ~COVER_TARGET_PX
            while (bounds.outWidth / sample > COVER_TARGET_PX ||
                bounds.outHeight / sample > COVER_TARGET_PX
            ) sample *= 2
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            val bmp = BitmapFactory.decodeFile(f.path, opts)
            AppLogger.d(TAG, "decodeCover: ${f.name}, sample=$sample, ${bmp?.width}x${bmp?.height}")
            bmp
        } catch (e: Exception) {
            AppLogger.w(TAG, "decodeCover failed: ${f.name}: ${e.message}")
            null
        }
    }
}