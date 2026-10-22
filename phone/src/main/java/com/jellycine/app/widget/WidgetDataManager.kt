package com.jellycine.app.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
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
    private const val KEY_PENDING_PIN_MEDIA_ID = "key_pending_pin_media_id"
    private const val KEY_KNOWN_WIDGET_IDS = "key_known_widget_ids"

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
        // 按 mediaId 匹配；如果是剧集且 mediaId 不同，按 seriesId 匹配
        val index = currentList.indexOfFirst {
            it.mediaId == item.mediaId || (!item.seriesId.isNullOrBlank() && it.seriesId == item.seriesId)
        }

        if (index >= 0) {
            val existing = currentList[index]
            val oldMediaId = existing.mediaId
            val updated = item.copy(
                localPosterPath = item.localPosterPath ?: existing.localPosterPath
            )
            currentList[index] = updated

            // 如果该剧集的 mediaId 发生变化 (比如从 E16 追到了 E17)，同步更新原本绑定该 mediaId 的 widget
            if (oldMediaId != item.mediaId) {
                migrateWidgetBindings(context, oldMediaId, item.mediaId)
            }
        } else {
            currentList.add(0, item)
        }

        // 最多保留 30 条
        val trimmed = if (currentList.size > 30) currentList.take(30) else currentList
        saveMediaList(context, trimmed)

        // 记录最新被添加到桌面的待绑定媒体
        setPendingPinMediaId(context, item.mediaId)
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

        // 清理解绑
        val prefs = getPrefs(context)
        val allKeys = prefs.all.keys
        val editor = prefs.edit()
        for (key in allKeys) {
            if (key.startsWith(PREFIX_WIDGET_BINDING) && prefs.getString(key, null) == mediaId) {
                editor.remove(key)
            }
        }
        editor.apply()

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
     * 设置待 Pin 到桌面的媒体 ID
     */
    fun setPendingPinMediaId(context: Context, mediaId: String) {
        getPrefs(context).edit().putString(KEY_PENDING_PIN_MEDIA_ID, mediaId).apply()
    }

    /**
     * 获取所有已知的桌面小组件 ID 集合
     */
    fun getKnownWidgetIds(context: Context): Set<String> {
        return getPrefs(context).getStringSet(KEY_KNOWN_WIDGET_IDS, emptySet()) ?: emptySet()
    }

    fun addKnownWidgetId(context: Context, appWidgetId: Int) {
        val current = getKnownWidgetIds(context).toMutableSet()
        current.add(appWidgetId.toString())
        getPrefs(context).edit().putStringSet(KEY_KNOWN_WIDGET_IDS, current).apply()
    }

    fun removeKnownWidgetId(context: Context, appWidgetId: Int) {
        val current = getKnownWidgetIds(context).toMutableSet()
        current.remove(appWidgetId.toString())
        getPrefs(context).edit().putStringSet(KEY_KNOWN_WIDGET_IDS, current).apply()
    }

    /**
     * 绑定桌面小组件 ID 到指定 mediaId (强绑定，永久生效)
     */
    fun bindWidgetId(context: Context, appWidgetId: Int, mediaId: String) {
        getPrefs(context).edit().putString("$PREFIX_WIDGET_BINDING$appWidgetId", mediaId).apply()
        addKnownWidgetId(context, appWidgetId)
        Log.d(TAG, "Bound widget $appWidgetId to media $mediaId")
    }

    /**
     * 解绑桌面小组件 ID
     */
    fun unbindWidgetId(context: Context, appWidgetId: Int) {
        getPrefs(context).edit().remove("$PREFIX_WIDGET_BINDING$appWidgetId").apply()
        removeKnownWidgetId(context, appWidgetId)
        Log.d(TAG, "Unbound widget $appWidgetId")
    }

    /**
     * 迁移旧绑定的媒体 ID 到新媒体 ID
     */
    private fun migrateWidgetBindings(context: Context, oldMediaId: String, newMediaId: String) {
        val prefs = getPrefs(context)
        val editor = prefs.edit()
        for (key in prefs.all.keys) {
            if (key.startsWith(PREFIX_WIDGET_BINDING) && prefs.getString(key, null) == oldMediaId) {
                editor.putString(key, newMediaId)
            }
        }
        editor.apply()
    }

    /**
     * 根据小组件 ID 获取绑定的媒体：
     * 1. 若已有强绑定，严格返回绑定的条目，绝不随其它新加媒体漂移；
     * 2. 若未绑定：如果是已存在的老小组件，杜绝掠夺 pendingPinMediaId；
     * 3. 只有全新的小组件才会消费 pendingPinMediaId，并立即固化专属绑定。
     */
    fun getMediaForWidgetId(context: Context, appWidgetId: Int): WidgetMediaItem? {
        val prefs = getPrefs(context)
        val boundMediaId = prefs.getString("$PREFIX_WIDGET_BINDING$appWidgetId", null)
        val list = getMediaList(context)
        if (list.isEmpty()) return null

        // 1. 已有持久化绑定 -> 忠实返回
        if (!boundMediaId.isNullOrBlank()) {
            val matched = list.firstOrNull { it.mediaId == boundMediaId }
            if (matched != null) {
                addKnownWidgetId(context, appWidgetId)
                return matched
            }
        }

        val knownWidgets = getKnownWidgetIds(context)
        val isExistingWidget = appWidgetId.toString() in knownWidgets

        // 2. 只有全新添加到桌面的小组件才消费 pendingPinMediaId，已有组件绝不抢夺
        val pendingId = prefs.getString(KEY_PENDING_PIN_MEDIA_ID, null)
        if (!isExistingWidget && !pendingId.isNullOrBlank()) {
            val pendingItem = list.firstOrNull { it.mediaId == pendingId }
            if (pendingItem != null) {
                bindWidgetId(context, appWidgetId, pendingItem.mediaId)
                addKnownWidgetId(context, appWidgetId)
                prefs.edit().remove(KEY_PENDING_PIN_MEDIA_ID).apply()
                return pendingItem
            }
        }

        // 3. 查找所有已被其他 widgetId 占用的 mediaId 集合
        val usedMediaIds = mutableSetOf<String>()
        for (key in prefs.all.keys) {
            if (key.startsWith(PREFIX_WIDGET_BINDING) && key != "$PREFIX_WIDGET_BINDING$appWidgetId") {
                prefs.getString(key, null)?.let { usedMediaIds.add(it) }
            }
        }

        // 挑选一个未被占用的媒体（如果是老组件，从列表中排除待添加的 pendingId，优先选未占用的已有条目）
        val candidates = if (isExistingWidget && !pendingId.isNullOrBlank()) {
            list.filter { it.mediaId != pendingId }
        } else {
            list
        }

        val candidate = candidates.firstOrNull { it.mediaId !in usedMediaIds }
            ?: candidates.firstOrNull()
            ?: list.first()

        // 立即固化绑定，从此专属
        bindWidgetId(context, appWidgetId, candidate.mediaId)
        addKnownWidgetId(context, appWidgetId)
        return candidate
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
