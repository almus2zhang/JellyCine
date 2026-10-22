package com.jellycine.app.widget

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

object WidgetDataManager {

    private const val TAG = "WidgetDataManager"
    private const val PREFS_NAME = "jellycine_widget_prefs"
    private const val KEY_MEDIA_LIST = "key_media_list"
    private const val PREFIX_WIDGET_BINDING = "widget_bind_"

    private val json = Json { ignoreUnknownKeys = true }
    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * 获取用户所有添加到小组件的媒体列表
     */
    @Synchronized
    fun getMediaList(context: Context): List<WidgetMediaItem> {
        val rawJson = getPrefs(context).getString(KEY_MEDIA_LIST, null) ?: return emptyList()
        return try {
            json.decodeFromString<List<WidgetMediaItem>>(rawJson)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode widget media list", e)
            emptyList()
        }
    }

    /**
     * 添加或更新一个小组件媒体条目
     */
    @Synchronized
    fun addOrUpdateMedia(context: Context, item: WidgetMediaItem) {
        val currentList = getMediaList(context).toMutableList()
        val index = currentList.indexOfFirst {
            it.mediaId == item.mediaId || (!item.seriesId.isNullOrBlank() && it.seriesId == item.seriesId)
        }
        if (index >= 0) {
            // 合并现有条目
            val existing = currentList[index]
            currentList[index] = item.copy(
                localPosterPath = item.localPosterPath ?: existing.localPosterPath
            )
        } else {
            currentList.add(0, item)
        }

        // 最多保留 30 条
        val trimmed = if (currentList.size > 30) currentList.take(30) else currentList
        saveMediaList(context, trimmed)
    }

    /**
     * 删除指定媒体
     */
    @Synchronized
    fun removeMedia(context: Context, mediaId: String) {
        val currentList = getMediaList(context).toMutableList()
        val itemToRemove = currentList.firstOrNull { it.mediaId == mediaId }
        currentList.removeAll { it.mediaId == mediaId }
        saveMediaList(context, currentList)

        // 清理缓存海报文件
        itemToRemove?.localPosterPath?.let { path ->
            try {
                val file = File(path)
                if (file.exists()) file.delete()
            } catch (_: Exception) {}
        }
    }

    /**
     * 更新播放进度
     */
    @Synchronized
    fun updateProgress(
        context: Context,
        mediaId: String,
        seriesId: String?,
        positionMs: Long,
        durationMs: Long
    ): Boolean {
        val currentList = getMediaList(context).toMutableList()
        val index = currentList.indexOfFirst {
            it.mediaId == mediaId || (!seriesId.isNullOrBlank() && it.seriesId == seriesId)
        }
        if (index < 0) return false

        val item = currentList[index]
        val percent = if (durationMs > 0) {
            ((positionMs * 100) / durationMs).toInt().coerceIn(0, 100)
        } else {
            item.progressPercent
        }

        currentList[index] = item.copy(
            positionMs = positionMs,
            durationMs = durationMs,
            progressPercent = percent,
            updatedAt = System.currentTimeMillis()
        )
        saveMediaList(context, currentList)
        return true
    }

    /**
     * 绑定桌面小组件 ID 到指定 mediaId
     */
    fun bindWidgetId(context: Context, appWidgetId: Int, mediaId: String) {
        getPrefs(context).edit().putString("$PREFIX_WIDGET_BINDING$appWidgetId", mediaId).apply()
    }

    /**
     * 解绑桌面小组件 ID
     */
    fun unbindWidgetId(context: Context, appWidgetId: Int) {
        getPrefs(context).edit().remove("$PREFIX_WIDGET_BINDING$appWidgetId").apply()
    }

    /**
     * 根据小组件 ID 获取绑定的媒体，如果没有绑定则返回最新一条
     */
    fun getMediaForWidgetId(context: Context, appWidgetId: Int): WidgetMediaItem? {
        val boundMediaId = getPrefs(context).getString("$PREFIX_WIDGET_BINDING$appWidgetId", null)
        val list = getMediaList(context)
        if (list.isEmpty()) return null
        if (!boundMediaId.isNullOrBlank()) {
            val matched = list.firstOrNull { it.mediaId == boundMediaId }
            if (matched != null) return matched
        }
        return list.firstOrNull()
    }

    /**
     * 下载海报到小组件私有缓存目录
     */
    suspend fun cachePosterBitmap(context: Context, mediaId: String, url: String?): String? = withContext(Dispatchers.IO) {
        if (url.isNullOrBlank()) return@withContext null
        try {
            val cacheDir = File(context.filesDir, "widget_posters").apply { if (!exists()) mkdirs() }
            val targetFile = File(cacheDir, "poster_$mediaId.jpg")
            if (targetFile.exists() && targetFile.length() > 0) {
                return@withContext targetFile.absolutePath
            }

            val request = Request.Builder().url(url).build()
            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful && response.body != null) {
                val bytes = response.body!!.bytes()
                FileOutputStream(targetFile).use { it.write(bytes) }
                targetFile.absolutePath
            } else null
        } catch (e: Exception) {
            Log.w(TAG, "Failed to download poster for widget: ${e.message}")
            null
        }
    }

    fun loadCachedBitmap(path: String?): Bitmap? {
        if (path.isNullOrBlank()) return null
        return try {
            val file = File(path)
            if (file.exists()) {
                BitmapFactory.decodeFile(file.absolutePath)
            } else null
        } catch (_: Exception) {
            null
        }
    }

    private fun saveMediaList(context: Context, list: List<WidgetMediaItem>) {
        try {
            val rawJson = json.encodeToString(list)
            getPrefs(context).edit().putString(KEY_MEDIA_LIST, rawJson).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save widget media list", e)
        }
    }
}
