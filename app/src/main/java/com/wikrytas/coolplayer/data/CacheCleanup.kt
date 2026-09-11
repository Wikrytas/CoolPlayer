package com.wikrytas.coolplayer.data

import android.content.Context
import android.util.Log
import java.io.File

object CacheCleanup {

    private const val TAG = "CacheCleanup"

    /**
     * Удаляет кэш-сироты: файлы обложек и текстов треков,
     * отсутствующих в библиотеке, а также временные .tmp файлы.
     */
    fun cleanOrphaned(context: Context, validIds: Set<Long>) {
        listOf("covers", "lyrics").forEach { dirName ->
            val dir = File(context.filesDir, dirName)
            if (!dir.exists()) return@forEach
            dir.listFiles()?.forEach { f ->
                try {
                    when {
                        f.name.endsWith(".tmp") -> f.delete()
                        else -> {
                            val id = f.name.substringBeforeLast('.').toLongOrNull()
                            if (id != null && id !in validIds) f.delete()
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to delete ${f.name}", e)
                }
            }
        }
    }
}