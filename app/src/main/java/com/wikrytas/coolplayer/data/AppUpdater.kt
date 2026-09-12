package com.wikrytas.coolplayer.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipFile

data class UpdateInfo(
    val versionName: String,
    val downloadUrl: String,
    val altDownloadUrl: String = "",
    val expectedSha256: String = "",
    val releaseNotes: String = "",
    val isNewer: Boolean = false
)

object AppUpdater {
    private const val TAG = "AppUpdater"
    private const val REPO_OWNER = "Wikrytas"
    private const val REPO_NAME = "CoolPlayer"

    var lastDownloadError: String? = null
        private set
    private var altUrlCache: String? = null
    private var expectedShaCache: String? = null

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
            var apkAltUrl = ""
            var apkDigest = ""
            val assets = json.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.optJSONObject(i) ?: continue
                    if (a.optString("name", "").endsWith(".apk")) {
                        apkUrl = a.optString("browser_download_url", "")
                        apkAltUrl = a.optString("url", "")
                        apkDigest = a.optString("digest", "").removePrefix("sha256:")
                        break
                    }
                }
            }
            if (apkUrl.isBlank()) {
                AppLogger.w(TAG, "release has no .apk asset")
                return@withContext null
            }
            altUrlCache = apkAltUrl.takeIf { it.isNotBlank() }
            expectedShaCache = apkDigest.takeIf { it.isNotBlank() }
            val remoteVersion = tag.removePrefix("v").trim()
            val currentVersion = currentVersionName(context)
            UpdateInfo(
                versionName = remoteVersion,
                downloadUrl = apkUrl,
                altDownloadUrl = apkAltUrl,
                expectedSha256 = apkDigest,
                releaseNotes = notes,
                isNewer = compareVersions(remoteVersion, currentVersion) > 0
            )
        } catch (e: Exception) {
            AppLogger.e(TAG, "update check failed", e)
            null
        }
    }

    private fun currentVersionName(context: Context): String =
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.0.0"
        } catch (e: Exception) {
            "0.0.0"
        }

    fun compareVersions(remote: String, current: String): Int {
        val r = remote.split(".").map { it.takeWhile { ch -> ch.isDigit() }.toIntOrNull() ?: 0 }
        val c = current.split(".").map { it.takeWhile { ch -> ch.isDigit() }.toIntOrNull() ?: 0 }
        val n = maxOf(r.size, c.size)
        for (i in 0 until n) {
            val vr = r.getOrNull(i) ?: 0
            val vc = c.getOrNull(i) ?: 0
            if (vr != vc) return if (vr > vc) 1 else -1
        }
        return 0
    }

    /** До 3 попыток (основной URL x2 + запасной API-URL), ZIP- и SHA256-контроль. */
    suspend fun downloadApk(context: Context, url: String, onProgress: (Int) -> Unit): File? =
        withContext(Dispatchers.IO) {
            lastDownloadError = null
            val alt = altUrlCache?.takeIf { it.isNotBlank() && it != url }
            val seq = listOfNotNull(url, url, alt)
            for ((idx, u) in seq.withIndex()) {
                AppLogger.i(TAG, "download attempt ${idx + 1}/${seq.size}: $u")
                val f = downloadOnce(context, u, onProgress)
                if (f != null && zipOk(f)) {
                    val expected = expectedShaCache
                    if (!expected.isNullOrBlank()) {
                        val actual = sha256Hex(f)
                        if (!actual.equals(expected, ignoreCase = true)) {
                            AppLogger.w(TAG, "sha256 mismatch: expected=$expected actual=$actual")
                            lastDownloadError = "Попытка ${idx + 1}: хеш не совпал (файл побит при скачивании)"
                            f.delete()
                            delay(1500L * (idx + 1))
                            continue
                        }
                        AppLogger.i(TAG, "sha256 ok: $actual")
                    }
                    AppLogger.i(TAG, "apk downloaded and verified: ${f.length()} bytes")
                    onProgress(100)
                    return@withContext f
                }
                lastDownloadError = "Попытка ${idx + 1}: файл битый или обрыв сети"
                delay(1500L * (idx + 1))
            }
            lastDownloadError = "Не удалось скачать целостный APK (3 попытки)"
            AppLogger.w(TAG, "download failed after ${seq.size} attempts")
            null
        }

    private fun zipOk(f: File): Boolean =
        runCatching { ZipFile(f).use { it.size() > 0 } }.getOrDefault(false)

    private fun sha256Hex(f: File): String =
        runCatching {
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { inp ->
                val buf = ByteArray(64 * 1024)
                var r: Int
                while (inp.read(buf).also { r = it } != -1) md.update(buf, 0, r)
            }
            md.digest().joinToString("") { "%02x".format(it) }
        }.getOrDefault("")

    private suspend fun downloadOnce(context: Context, url: String, onProgress: (Int) -> Unit): File? =
        withContext(Dispatchers.IO) {
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 60000
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", "CoolPlayer-Updater")
                if (url.contains("api.github.com")) {
                    conn.setRequestProperty("Accept", "application/octet-stream")
                }
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
                        val buf = ByteArray(8192)
                        var r: Int
                        while (input.read(buf).also { r = it } != -1) {
                            output.write(buf, 0, r)
                            downloaded += r
                            if (total > 0) onProgress(((downloaded * 100) / total).toInt())
                        }
                        output.flush()
                    }
                }
                conn.disconnect()
                if (total > 0 && downloaded != total) {
                    AppLogger.w(TAG, "size mismatch: expected=$total got=$downloaded")
                    outFile.delete()
                    return@withContext null
                }
                outFile
            } catch (e: Exception) {
                AppLogger.e(TAG, "download failed: ${e.javaClass.simpleName}: ${e.message}", e)
                null
            }
        }

    fun signatureMismatch(ctx: Context, file: File): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        val pm = ctx.packageManager
        val newInfo = pm.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: return false
        val oldInfo = runCatching {
            pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        }.getOrNull() ?: return false
        val newSigs = newInfo.signingInfo?.apkContentsSigners
            ?.map { it.toByteArray().contentToString() }?.toSet() ?: return false
        val oldSigs = oldInfo.signingInfo?.apkContentsSigners
            ?.map { it.toByteArray().contentToString() }?.toSet() ?: return false
        return newSigs != oldSigs
    }

    fun canInstall(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else true

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
        if (signatureMismatch(context, apk)) {
            Toast.makeText(
                context,
                "Подпись новой версии не совпадает с установленной. Удалите старую версию CoolPlayer и повторите обновление.",
                Toast.LENGTH_LONG
            ).show()
            AppLogger.w(TAG, "signature mismatch, install blocked")
            return false
        }
        return try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
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