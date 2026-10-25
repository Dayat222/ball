package com.utility.screenprotractor

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * In-App OTA Update Manager for seamless Over-The-Air application updates.
 * Checks for updates via GitHub raw version.json (with GitHub API fallback),
 * downloads APK in background following HTTP redirects, and triggers system package installer.
 */
object AppUpdateManager {

    private const val VERSION_JSON_URL =
        "https://raw.githubusercontent.com/Dayat222/ball/main/version.json"
    private const val GITHUB_LATEST_RELEASE_API =
        "https://api.github.com/repos/Dayat222/ball/releases/latest"

    data class UpdateInfo(
        val remoteVersionCode: Long,
        val remoteVersionName: String,
        val downloadUrl: String,
        val changelog: String,
        val isUpdateAvailable: Boolean
    )

    fun getInstalledVersionCode(context: Context): Long {
        return try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toLong()
            }
        } catch (e: Exception) {
            1L
        }
    }

    fun getInstalledVersionName(context: Context): String {
        return try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            packageInfo.versionName ?: "1.0"
        } catch (e: Exception) {
            "1.0"
        }
    }

    /**
     * Checks if a newer version exists online.
     * Runs asynchronously on Dispatchers.IO and posts result back on Dispatchers.Main.
     */
    fun checkForUpdate(
        context: Context,
        onResult: (UpdateInfo) -> Unit,
        onError: (Exception) -> Unit
    ) {
        val currentVersionCode = getInstalledVersionCode(context)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                var updateInfo = fetchFromVersionJson(currentVersionCode)
                if (updateInfo == null) {
                    // Fallback to GitHub Releases API if version.json is unreachable
                    updateInfo = fetchFromGitHubReleases(currentVersionCode)
                }

                if (updateInfo != null) {
                    withContext(Dispatchers.Main) {
                        onResult(updateInfo)
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        onError(IllegalStateException("Gagal memuat informasi versi rilis."))
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onError(e)
                }
            }
        }
    }

    private fun fetchFromVersionJson(currentVersionCode: Long): UpdateInfo? {
        return try {
            val cacheBusterUrl = "$VERSION_JSON_URL?t=${System.currentTimeMillis()}"
            val conn = openConnectionWithRedirects(cacheBusterUrl)
            try {
                if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
                parseVersionJson(
                    conn.inputStream.bufferedReader().use { it.readText() },
                    currentVersionCode
                )
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Fallback channel: reads the version.json published as an asset on the latest GitHub release.
     *
     * Why: the release tag is a floating "latest" tag, so the tag name carries no version number.
     * Guessing `currentVersionCode + 1` from it caused a permanent false "update available" prompt
     * whenever raw.githubusercontent.com was unreachable. The manifest asset is the only reliable
     * versionCode source in that case; if it is missing we report no update instead of guessing.
     */
    private fun fetchFromGitHubReleases(currentVersionCode: Long): UpdateInfo? {
        return try {
            val conn = openConnectionWithRedirects(GITHUB_LATEST_RELEASE_API)
            val releaseJsonText = try {
                if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
                conn.inputStream.bufferedReader().use { it.readText() }
            } finally {
                conn.disconnect()
            }

            val json = JSONObject(releaseJsonText)
            var apkUrl = ""
            var manifestUrl = ""
            val assets = json.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val name = asset.optString("name", "")
                    val url = asset.optString("browser_download_url", "")
                    when {
                        name.equals("version.json", ignoreCase = true) -> manifestUrl = url
                        apkUrl.isEmpty() && name.endsWith(".apk", ignoreCase = true) -> apkUrl = url
                    }
                }
            }

            if (manifestUrl.isEmpty() || apkUrl.isEmpty()) return null

            val manifestConn = openConnectionWithRedirects("$manifestUrl?t=${System.currentTimeMillis()}")
            val manifestText = try {
                if (manifestConn.responseCode != HttpURLConnection.HTTP_OK) return null
                manifestConn.inputStream.bufferedReader().use { it.readText() }
            } finally {
                manifestConn.disconnect()
            }

            parseVersionJson(manifestText, currentVersionCode)?.let { info ->
                if (info.downloadUrl.isEmpty()) info.copy(downloadUrl = apkUrl) else info
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Parses the OTA manifest. Its versionCode is the single source of truth for comparisons,
     * so an up-to-date install is never prompted to install the same build again.
     */
    private fun parseVersionJson(jsonText: String, currentVersionCode: Long): UpdateInfo? {
        return try {
            val json = JSONObject(jsonText)
            val remoteCode = json.optLong("versionCode", currentVersionCode)
            val remoteName = json.optString("versionName", "")
            if (remoteName.isEmpty()) return null

            UpdateInfo(
                remoteVersionCode = remoteCode,
                remoteVersionName = remoteName,
                downloadUrl = json.optString("downloadUrl", ""),
                changelog = json.optString("changelog", "Pembaruan kestabilan dan perbaikan fitur."),
                isUpdateAvailable = remoteCode > currentVersionCode
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Downloads APK from updateInfo.downloadUrl with multi-hop redirect following,
     * reporting percentage progress (0-100), and then triggers installApk.
     */
    fun downloadAndInstall(
        context: Context,
        updateInfo: UpdateInfo,
        onProgress: (Int) -> Unit = {},
        onComplete: (File) -> Unit = {},
        onError: (Exception) -> Unit = {}
    ) {
        val destFile = File(context.cacheDir, "app-update.apk")

        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (destFile.exists()) {
                    destFile.delete()
                }

                val busterUrl = if (updateInfo.downloadUrl.contains("?")) {
                    "${updateInfo.downloadUrl}&t=${System.currentTimeMillis()}"
                } else {
                    "${updateInfo.downloadUrl}?t=${System.currentTimeMillis()}"
                }
                val conn = openConnectionWithRedirects(busterUrl)
                if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                    throw IllegalStateException("Server merespons kode status: ${conn.responseCode}")
                }

                val totalLength = conn.contentLength.toLong()
                var input: InputStream? = null
                var output: FileOutputStream? = null

                try {
                    input = conn.inputStream
                    output = FileOutputStream(destFile)

                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var totalDownloaded = 0L
                    var lastReportedPercent = -1

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalDownloaded += bytesRead

                        if (totalLength > 0) {
                            val percent = ((totalDownloaded * 100) / totalLength).toInt().coerceIn(0, 100)
                            if (percent != lastReportedPercent) {
                                lastReportedPercent = percent
                                withContext(Dispatchers.Main) {
                                    onProgress(percent)
                                }
                            }
                        }
                    }
                    output.flush()
                } finally {
                    output?.close()
                    input?.close()
                    conn.disconnect()
                }

                if (!destFile.exists() || destFile.length() == 0L) {
                    throw IllegalStateException("File unduhan kosong atau rusak.")
                }

                destFile.setReadable(true, false)

                withContext(Dispatchers.Main) {
                    onComplete(destFile)
                    installApk(context, destFile)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onError(e)
                }
            }
        }
    }

    /**
     * Launches the native Android package installer using FileProvider.
     * Directs user to grant Unknown App Sources permission if needed on Android 8.0+.
     */
    fun installApk(context: Context, apkFile: File) {
        if (!apkFile.exists() || apkFile.length() == 0L) {
            Toast.makeText(context, R.string.update_download_failed, Toast.LENGTH_SHORT).show()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!context.packageManager.canRequestPackageInstalls()) {
                val settingsIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(settingsIntent)
                Toast.makeText(
                    context,
                    "Izinkan 'Instal aplikasi tidak dikenal' lalu ulangi update",
                    Toast.LENGTH_LONG
                ).show()
                return
            }
        }

        try {
            val apkUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(installIntent)
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, "Gagal meluncurkan installer: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Opens an HttpURLConnection, following 301/302/307/308 redirects up to 6 times.
     * Essential for GitHub Release downloads redirecting to AWS S3 storage.
     */
    private fun openConnectionWithRedirects(initialUrl: String): HttpURLConnection {
        var currentUrl = initialUrl
        var redirects = 0

        while (redirects < 6) {
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                useCaches = false
                connectTimeout = 15000
                readTimeout = 15000
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
                setRequestProperty("Cache-Control", "no-cache")
                setRequestProperty("Pragma", "no-cache")
            }
            conn.connect()

            val status = conn.responseCode
            if (status == HttpURLConnection.HTTP_MOVED_TEMP ||
                status == HttpURLConnection.HTTP_MOVED_PERM ||
                status == HttpURLConnection.HTTP_SEE_OTHER ||
                status == 307 || status == 308
            ) {
                val newUrl = conn.getHeaderField("Location")
                conn.disconnect()
                if (newUrl.isNullOrEmpty()) {
                    throw IllegalStateException("Redirected with empty Location header")
                }
                currentUrl = newUrl
                redirects++
            } else {
                return conn
            }
        }
        throw IllegalStateException("Too many HTTP redirects from $initialUrl")
    }

    /**
     * Helper to show a clean Material Dialog on an Activity when an update is available.
     */
    fun showUpdateDialog(
        activity: Activity,
        updateInfo: UpdateInfo,
        onDownloadStarted: (() -> Unit)? = null
    ) {
        val message = "${updateInfo.changelog}\n\nVersi: ${updateInfo.remoteVersionName}"

        MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.update_available_title))
            .setMessage(message)
            .setPositiveButton(activity.getString(R.string.update_now)) { _, _ ->
                onDownloadStarted?.invoke()
                val progressDialog = MaterialAlertDialogBuilder(activity)
                    .setTitle(activity.getString(R.string.update_checking))
                    .setMessage(activity.getString(R.string.update_downloading, 0))
                    .setCancelable(false)
                    .create()
                progressDialog.show()

                downloadAndInstall(
                    context = activity,
                    updateInfo = updateInfo,
                    onProgress = { percent ->
                        if (!activity.isFinishing && !activity.isDestroyed && progressDialog.isShowing) {
                            progressDialog.setMessage(activity.getString(R.string.update_downloading, percent))
                        }
                    },
                    onComplete = {
                        if (!activity.isFinishing && !activity.isDestroyed && progressDialog.isShowing) {
                            progressDialog.dismiss()
                        }
                    },
                    onError = { err ->
                        if (!activity.isFinishing && !activity.isDestroyed && progressDialog.isShowing) {
                            progressDialog.dismiss()
                        }
                        if (!activity.isFinishing && !activity.isDestroyed) {
                            Toast.makeText(
                                activity,
                                "${activity.getString(R.string.update_download_failed)}: ${err.message}",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                )
            }
            .setNegativeButton(activity.getString(R.string.update_later), null)
            .show()
    }
}
