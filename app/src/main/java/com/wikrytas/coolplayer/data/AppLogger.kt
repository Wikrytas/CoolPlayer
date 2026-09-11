package com.wikrytas.coolplayer.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLogger {

    private const val TAG = "AppLogger"
    private const val MAX_FILE_BYTES = 1_500_000L

    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var file: File? = null
    private var defaultHandler: Thread.UncaughtExceptionHandler? = null
    private var debuggable = false

    fun init(context: Context) {
        synchronized(lock) {
            if (file != null) return
            val app = context.applicationContext
            debuggable = (app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
            val dir = File(app.filesDir, "logs").apply { mkdirs() }
            file = File(dir, "app.log")

            defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, error ->
                writeLine("F", "Crash", "Uncaught on thread '${thread.name}': ${error.message}", error)
                Thread.sleep(150)
                defaultHandler?.uncaughtException(thread, error)
            }
        }
        i("App", "AppLogger init: file=${file?.absolutePath}, pid=${android.os.Process.myPid()}, debuggable=$debuggable")
    }

    fun d(tag: String, msg: String) = writeLine("D", tag, msg, null)
    fun i(tag: String, msg: String) = writeLine("I", tag, msg, null)
    fun w(tag: String, msg: String, t: Throwable? = null) = writeLine("W", tag, msg, t)
    fun e(tag: String, msg: String, t: Throwable? = null) = writeLine("E", tag, msg, t)

    private fun writeLine(level: String, tag: String, msg: String, t: Throwable?) {
        val stamp = synchronized(lock) { timeFormat.format(Date()) }
        val line = "$stamp $level/$tag: $msg"

        when (level) {
            "D" -> Log.d(tag, msg, t)
            "I" -> Log.i(tag, msg, t)
            "W" -> Log.w(tag, msg, t)
            else -> Log.e(tag, msg, t)
        }

        // Debug-строки в файл только в отладочных сборках — релизный лог не разрастается
        if (level == "D" && !debuggable) return

        scope.launch {
            synchronized(lock) {
                val f = file ?: return@launch
                try {
                    if (f.exists() && f.length() > MAX_FILE_BYTES) {
                        val old = File(f.parentFile, "app.old.log")
                        old.delete()
                        f.renameTo(old)
                    }
                    f.appendText(line + "\n")
                    if (t != null) f.appendText(Log.getStackTraceString(t))
                } catch (e: Exception) {
                    Log.w(TAG, "log write failed", e)
                }
            }
        }
    }

    fun readAll(): String = synchronized(lock) {
        buildString {
            val old = file?.parentFile?.let { File(it, "app.old.log") }
            if (old?.exists() == true) {
                runCatching { append(old.readText()) }
            }
            file?.takeIf { it.exists() }?.let { f ->
                runCatching { append(f.readText()) }
            }
        }
    }

    fun clear() {
        scope.launch {
            synchronized(lock) {
                file?.delete()
                file?.parentFile?.let { File(it, "app.old.log").delete() }
            }
            i("App", "Log cleared by user")
        }
    }
}