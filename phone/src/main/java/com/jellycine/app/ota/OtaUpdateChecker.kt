package com.jellycine.app.ota

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.jellycine.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * OTA update checker — fetches version.json from remote server,
 * compares with current BuildConfig version, and handles APK download & install.
 */
class OtaUpdateChecker(private val context: Context) {

    companion object {
        private const val UPDATE_URL =
            "https://chat.a66.nasnas.site/web/jellycine-ota/version.json"
        private const val OTA_DIR = "ota_updates"
    }

    @Serializable
    data class OtaVersionInfo(
        @SerialName("versionCode") val versionCode: Int,
        @SerialName("versionName") val versionName: String,
        @SerialName("apkUrl") val apkUrl: String,
        @SerialName("changelog") val changelog: String = "",
        @SerialName("minVersionCode") val minVersionCode: Int = 1,
        @SerialName("forceUpdate") val forceUpdate: Boolean = false
    )

    sealed class UpdateStatus {
        data object NoUpdate : UpdateStatus()
        data class UpdateAvailable(
            val info: OtaVersionInfo,
            val isIgnored: Boolean = false
        ) : UpdateStatus()
        data class Downloading(val progress: Int) : UpdateStatus()
        data class ReadyToInstall(val apkFile: File) : UpdateStatus()
        data class Error(val message: String) : UpdateStatus()
    }

    private val json = Json { ignoreUnknownKeys = true }

    private val httpClient: OkHttpClient by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Checks for update by fetching version.json from server.
     * @param checkIgnored If true, returns [UpdateStatus.NoUpdate] when the available version is <= [ignoredVersionCode].
     * @param ignoredVersionCode The version code that the user previously chose to ignore.
     * Returns [UpdateStatus.UpdateAvailable] if new version exists,
     * [UpdateStatus.NoUpdate] if current version is latest or ignored (when [checkIgnored] is true).
     */
    suspend fun checkForUpdate(
        checkIgnored: Boolean = false,
        ignoredVersionCode: Int = 0
    ): UpdateStatus = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(UPDATE_URL)
                .addHeader("Cache-Control", "no-cache")
                .build()
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext UpdateStatus.Error("HTTP ${response.code}")
            }
            val body = response.body.string()

            val versionInfo = json.decodeFromString<OtaVersionInfo>(body)
            if (versionInfo.versionCode > BuildConfig.VERSION_CODE) {
                val isIgnored = versionInfo.versionCode <= ignoredVersionCode
                if (checkIgnored && isIgnored) {
                    UpdateStatus.NoUpdate
                } else {
                    UpdateStatus.UpdateAvailable(versionInfo, isIgnored = isIgnored)
                }
            } else {
                UpdateStatus.NoUpdate
            }
        } catch (e: Exception) {
            UpdateStatus.Error(e.message ?: "Unknown error")
        }
    }

    /**
     * Downloads the APK from the given URL into cache/ota_updates/.
     * Calls [onProgress] with download percentage (0-100).
     * Returns the downloaded [File] on success.
     */
    suspend fun downloadApk(
        url: String,
        onProgress: (Int) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val otaDir = File(context.cacheDir, OTA_DIR).apply {
                if (!exists()) mkdirs()
            }
            // Clean old downloads
            otaDir.listFiles()?.forEach { it.delete() }

            val fileName = url.substringAfterLast("/")
            val outputFile = File(otaDir, fileName)

            val request = Request.Builder().url(url).build()
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("HTTP ${response.code}"))
            }

            val responseBody = response.body

            val totalBytes = responseBody.contentLength()
            var downloadedBytes = 0L
            var lastReportedProgress = -1

            responseBody.byteStream().use { input ->
                outputFile.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead
                        if (totalBytes > 0) {
                            val progress = ((downloadedBytes * 100) / totalBytes).toInt()
                            if (progress != lastReportedProgress) {
                                lastReportedProgress = progress
                                withContext(Dispatchers.Main) {
                                    onProgress(progress)
                                }
                            }
                        }
                    }
                }
            }

            Result.success(outputFile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Triggers APK installation via FileProvider + ACTION_VIEW intent.
     * On Android 8.0+, checks for install-from-unknown-sources permission first.
     */
    fun installApk(apkFile: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!context.packageManager.canRequestPackageInstalls()) {
                // Navigate user to enable unknown sources for this app
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                return
            }
        }

        val apkUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )

        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(installIntent)
    }
}
