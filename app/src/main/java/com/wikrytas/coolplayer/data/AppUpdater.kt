package com.wikrytas.coolplayer.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(
    val versionName: String,
    val downloadUrl: String,
    val releaseNotes: String,
    val isNewer: Boolean
)

object AppUpdater {
    private const val TAG = "AppUpdater"

    // TODO: замените на ваш репозиторий GitHub
    private const val REPO_OWNER = "YOUR_GITHUB_LOGIN"
    private const val REPO_NAME = "CoolPlayer"

    suspend fun checkForUpdate(context: Context): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val url = URL("https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases/latest")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "CoolPlayer-Updater")
            if (conn.responseCode != 200) {
                AppLogger.w(TAG, "update check http ${conn.responseCode}")
                conn.disconnect()
                return@withContext null
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()

            val json = JSONObject(body)
            val tag = json.optString("tag_name", "")
            val notes = json.optString("body", "")

            var apkUrl = ""
            val assets = json.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.optJSONObject(i) ?: continue
                    if (a.optString("name", "").endsWith(".apk")) {
                        apkUrl = a.optString("browser_download_url", "")
                        break
                    }
                }
            }
            if (apkUrl.isBlank()) {
                AppLogger.w(TAG, "release has no .apk asset")
                return@withContext null
            }

            val remoteVersion = tag.removePrefix("v").trim()
            val currentVersion = currentVersionName(context)
            UpdateInfo(
                versionName = remoteVersion,
                downloadUrl = apkUrl,
                releaseNotes = notes,
                isNewer = isNewerVersion(remoteVersion, currentVersion)
            )
        } catch (e: Exception) {
            AppLogger.e(TAG, "update check failed", e)
            null
        }
    }

    private fun currentVersionName(context: Context): String {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.0.0"
        } catch (e: Exception) {
            "0.0.0"
        }
    }

    private fun isNewerVersion(remote: String, current: String): Boolean {
        val r = parseSemver(remote)
        val c = parseSemver(current)
        for (i in 0 until 3) {
            if (r[i] != c[i]) return r[i] > c[i]
        }
        return false
    }

    private fun parseSemver(name: String): IntArray {
        val parts = name.split(".").map { it.takeWhile { ch -> ch.isDigit() }.toIntOrNull() ?: 0 }
        return intArrayOf(
            parts.getOrNull(0) ?: 0,
            parts.getOrNull(1) ?: 0,
            parts.getOrNull(2) ?: 0
        )
    }

    suspend fun downloadApk(context: Context, url: String, onProgress: (Int) -> Unit): File? =
        withContext(Dispatchers.IO) {
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 20000
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", "CoolPlayer-Updater")
                if (conn.responseCode != 200) {
                    AppLogger.w(TAG, "download http ${conn.responseCode}")
                    conn.disconnect()
                    return@withContext null
                }
                val total = conn.contentLengthLong
                val outFile = File(context.cacheDir, "update.apk")
                var downloaded = 0L
                conn.inputStream.use { input ->
                    outFile.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            downloaded += read
                            if (total > 0) onProgress(((downloaded * 100) / total).toInt())
                        }
                        output.flush()
                    }
                }
                conn.disconnect()
                AppLogger.i(TAG, "apk downloaded: ${outFile.length()} bytes")
                outFile
            } catch (e: Exception) {
                AppLogger.e(TAG, "download failed", e)
                null
            }
        }

    fun canInstall(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else true
    }

    fun openInstallSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching {
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                    .setData(Uri.parse("package:${context.packageName}"))
                context.startActivity(intent)
            }
        }
    }

    fun installApk(context: Context, apk: File): Boolean {
        return try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apk
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            AppLogger.e(TAG, "install failed", e)
            false
        }
    }
}